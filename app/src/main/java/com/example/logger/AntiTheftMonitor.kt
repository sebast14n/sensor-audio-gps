package com.example.logger

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Anti-furt pentru telefon lasat nesupravegheat in teren (DOAR mod scheduled/senzor fix).
 *
 * - SIGNIFICANT_MOTION (low power, one-shot, re-armat) -> mutat din pozitie -> alerta.
 *   Fallback pe ACCELEROMETRU daca telefonul nu are significant-motion.
 * - Baterie scazuta / BLE in apropiere / storage redus -> alerte.
 * - Ping locatie periodic (~15 min) -> serverul stie unde e telefonul.
 *
 * STEALTH: nicio alerta nu produce sunet/vibratie/notificare/UI — hotul nu vede nimic.
 * OFFLINE: daca nu e net, alertele se pun intr-o coada LOCALA PRIVATA si se trimit
 * de indata ce apare internet (la urmatorul tick sau la urmatoarea alerta).
 */
class AntiTheftMonitor(
    private val ctx: Context,
    private var locationLat: () -> Double?,
) {
    var locationLon: () -> Double? = { null }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val motion: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    // fallback doar daca nu exista significant-motion
    private val accel: Sensor? = if (motion == null) sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
    private var lastAccelAlert = 0L
    private var batteryReceiver: BroadcastReceiver? = null

    private val prefs = ctx.getSharedPreferences("bioecho_prefs", Context.MODE_PRIVATE)
    private val server = BuildConfig.SERVER_URL

    // coada offline (stocare INTERNA privata — hotul nu o vede/sterge)
    private val queueFile = File(ctx.filesDir, "alert_queue.jsonl")
    private val queueLock = Any()

    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD_MS = 90_000L
    private val SCAN_WINDOW_MS = 8_000L
    private val BT_RSSI_MIN = -85
    private val STORAGE_MIN_MB = 500L
    private val PING_EVERY_TICKS = 10        // 10 × 90s ≈ 15 min -> ping locatie
    private var tickCount = 0
    private var storageAlerted = false
    private var scanner: BluetoothLeScanner? = null
    private val seen = HashMap<String, Pair<Int, String>>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            if (result.rssi < BT_RSSI_MIN) return
            val addr = try { result.device?.address } catch (_: SecurityException) { null } ?: return
            val label = classifyBle(result)
            val prev = seen[addr]
            if (prev == null || result.rssi > prev.first) seen[addr] = result.rssi to label
        }
    }

    private val pollTick = object : Runnable {
        override fun run() {
            runBleScan()
            checkStorage()
            flushQueueAsync()                       // incearca sa trimita ce-a ramas in coada
            if (++tickCount % PING_EVERY_TICKS == 0) // ~15 min: ping locatie
                sendAlert("location_ping", emptyMap())
            handler.postDelayed(this, SCAN_PERIOD_MS)
        }
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            sendAlert("moved", mapOf("via" to "significant_motion"))
            motion?.let { sm?.requestTriggerSensor(this, it) }  // re-armeaza (one-shot)
        }
    }

    // fallback: accelerometru cu prag (telefon pe arbore stabil -> orice miscare = ridicat)
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
            val m = sqrt(x * x + y * y + z * z)
            if (abs(m - SensorManager.GRAVITY_EARTH) > 3.0) {  // miscare brusca
                val now = SystemClock.elapsedRealtime()
                if (now - lastAccelAlert > 60_000) {            // debounce 60s
                    lastAccelAlert = now
                    sendAlert("moved", mapOf("via" to "accelerometer"))
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun start() {
        motion?.let { sm?.requestTriggerSensor(triggerListener, it) }
        accel?.let { sm?.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == Intent.ACTION_BATTERY_LOW) sendAlert("battery_low", emptyMap())
            }
        }
        ContextCompat.registerReceiver(
            ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_LOW),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        flushQueueAsync()                           // trimite ce-a ramas necomunicat
        handler.postDelayed(pollTick, 15_000L)
    }

    fun stop() {
        motion?.let { sm?.cancelTriggerSensor(triggerListener, it) }
        accel?.let { sm?.unregisterListener(accelListener) }
        try { batteryReceiver?.let { ctx.unregisterReceiver(it) } } catch (_: Exception) {}
        handler.removeCallbacks(pollTick)
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    // === BLE proximitate ===
    private fun runBleScan() {
        if (!hasBtScanPerm()) return
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = mgr.adapter ?: return
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner ?: return
        seen.clear()
        try {
            scanner?.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback)
        } catch (_: Exception) { return }
        handler.postDelayed({
            try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
            evaluateBleScan()
        }, SCAN_WINDOW_MS)
    }

    private fun evaluateBleScan() {
        if (seen.isEmpty()) return
        val strongest = seen.values.maxByOrNull { it.first }
        val kinds = seen.values.map { it.second }.filter { it.isNotEmpty() }.distinct()
        val hasTag = kinds.any { it.contains("Tag", ignoreCase = true) }
        sendAlert(if (hasTag) "bt_tag" else "bt_proximity", mapOf(
            "bt_count" to seen.size,
            "bt_rssi" to (strongest?.first ?: -127),
            "bt_kinds" to kinds.joinToString(",").ifEmpty { "necunoscut" }))
    }

    private fun classifyBle(result: ScanResult): String {
        val mfg = result.scanRecord?.manufacturerSpecificData
        if (mfg != null) for (i in 0 until mfg.size()) when (mfg.keyAt(i)) {
            0x004C -> return "Apple/AirTag?"
            0x0075 -> return "Samsung/SmartTag?"
            0x00E0 -> return "Google"
            0x0006 -> return "Microsoft"
        }
        return ""
    }

    private fun hasBtScanPerm(): Boolean = if (Build.VERSION.SDK_INT >= 31)
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED else true

    // === Storage ===
    private fun checkStorage() {
        try {
            val freeMb = Storage.baseDir(ctx).usableSpace / 1_000_000L
            if (freeMb in 0 until STORAGE_MIN_MB) {
                if (!storageAlerted) { storageAlerted = true; sendAlert("storage_low", mapOf("free_mb" to freeMb)) }
            } else storageAlerted = false
        } catch (_: Exception) {}
    }

    private fun batteryPct(): Int =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private fun simState(): Int = try {
        (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simState
            ?: TelephonyManager.SIM_STATE_UNKNOWN
    } catch (_: Exception) { TelephonyManager.SIM_STATE_UNKNOWN }

    private fun titleFor(r: String) = when (r) {
        "moved" -> "Telefon mutat din pozitie"
        "battery_low" -> "Baterie scazuta"
        "bt_tag" -> "Tracker BLE langa senzor"
        "bt_proximity" -> "Dispozitiv BLE in apropiere"
        "storage_low" -> "Spatiu de stocare redus"
        "location_ping" -> "Pozitie"
        else -> "Alerta senzor"
    }

    private fun hasNetwork(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** Construieste corpul alertei in forma asteptata de server (kind/title/lat/lon/meta). */
    private fun buildBody(reason: String, extra: Map<String, Any?>): String {
        val sim = simState()
        val meta = JSONObject().apply {
            put("battery", batteryPct())
            put("sim_state", sim)
            put("sim_removed", sim == TelephonyManager.SIM_STATE_ABSENT)
            put("device", Build.MODEL)
            put("occurred_at", System.currentTimeMillis())   // ora reala a evenimentului
            extra.forEach { (k, v) -> put(k, v) }
        }
        return JSONObject().apply {
            put("kind", reason)
            put("title", titleFor(reason))
            put("lat", locationLat())
            put("lon", locationLon())
            put("meta", meta)
        }.toString()
    }

    private fun sendAlert(reason: String, extra: Map<String, Any?>) {
        val token = prefs.getString("jwt_token", null) ?: return
        val body = buildBody(reason, extra)
        Thread {
            if (!postAlert(token, body)) enqueue(body)   // esec/fara net -> coada locala
            flushQueue(token)                            // si incearca restul cozii
        }.start()
    }

    /** POST efectiv. Intoarce true la succes (2xx). Silentios la esec. */
    private fun postAlert(token: String, body: String): Boolean {
        if (!hasNetwork()) return false
        return try {
            val conn = (URL("$server/api/auth/mobile-alert").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 15000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode in 200..299
            try { conn.inputStream.use { it.readBytes() } } catch (_: Exception) {}
            conn.disconnect()
            ok
        } catch (_: Exception) { false }
    }

    private fun enqueue(body: String) {
        synchronized(queueLock) {
            try { queueFile.appendText(body.replace("\n", " ") + "\n") } catch (_: Exception) {}
        }
    }

    private fun flushQueueAsync() {
        val token = prefs.getString("jwt_token", null) ?: return
        Thread { flushQueue(token) }.start()
    }

    /** Reia coada: ia un snapshot + goleste fisierul sub lock (enqueue-urile noi merg intr-un fisier
     *  nou, deci nu se pierd), apoi trimite; esecurile se re-adauga la coada. Stealth (fara UI). */
    private fun flushQueue(token: String) {
        if (!hasNetwork()) return
        val lines: List<String> = synchronized(queueLock) {
            if (!queueFile.exists()) return
            val l = try { queueFile.readLines().filter { it.isNotBlank() } } catch (_: Exception) { return }
            if (l.isEmpty()) return
            try { queueFile.delete() } catch (_: Exception) {}
            l
        }
        for (l in lines) if (!postAlert(token, l)) enqueue(l)   // esecurile revin la coada
    }
}

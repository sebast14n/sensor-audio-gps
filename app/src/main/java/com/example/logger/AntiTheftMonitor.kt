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
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anti-furt pentru telefon lasat nesupravegheat in teren.
 *
 * - Senzor SIGNIFICANT_MOTION (very low power, one-shot) -> telefonul a fost
 *   mutat din pozitie -> trimite alerta cu locatie la server.
 * - Baterie scazuta (ACTION_BATTERY_LOW) -> alerta cu locatie + nivel.
 * - In fiecare alerta raporteaza si starea SIM (ABSENT = posibil scos).
 *
 * NB: nu poate comuta modul avion / reaprinde radio-ul (restrictie Android pentru
 * aplicatii normale). Trimite alerta DOAR daca reteaua e disponibila (date pornite).
 * Esecurile sunt non-fatale pentru inregistrare.
 */
class AntiTheftMonitor(
    private val ctx: Context,
    private var locationLat: () -> Double?,
) {
    var locationLon: () -> Double? = { null }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val motion: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var batteryReceiver: BroadcastReceiver? = null
    private var lastSimState: Int = simState()

    private val prefs = ctx.getSharedPreferences("bioecho_prefs", Context.MODE_PRIVATE)
    private val server = BuildConfig.SERVER_URL

    // --- anti-intruziune BLE + storage (poll periodic) ---
    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD_MS = 90_000L   // o data la 90s (low power)
    private val SCAN_WINDOW_MS = 8_000L    // scaneaza ~8s, apoi opreste
    private val BT_RSSI_MIN = -85          // ignora semnale foarte slabe (departe)
    private val STORAGE_MIN_MB = 500L      // alerta cand scade sub 500 MB liberi
    private var storageAlerted = false
    private var scanner: BluetoothLeScanner? = null
    // address -> (rssi cel mai bun, eticheta tip)
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
            handler.postDelayed(this, SCAN_PERIOD_MS)
        }
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            sendAlert("moved", mapOf("note" to "telefon mutat din pozitie"))
            // re-armeaza (senzorul e one-shot)
            motion?.let { sm?.requestTriggerSensor(this, it) }
        }
    }

    fun start() {
        motion?.let { sm?.requestTriggerSensor(triggerListener, it) }
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == Intent.ACTION_BATTERY_LOW) {
                    sendAlert("battery_low", mapOf("battery" to batteryPct()))
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_LOW),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // porneste poll-ul periodic BLE + storage (prima rulare dupa o intarziere scurta)
        handler.postDelayed(pollTick, 15_000L)
    }

    fun stop() {
        motion?.let { sm?.cancelTriggerSensor(triggerListener, it) }
        try { batteryReceiver?.let { ctx.unregisterReceiver(it) } } catch (_: Exception) {}
        handler.removeCallbacks(pollTick)
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    // === Anti-intruziune BLE ===
    // Scaneaza ~8s dupa dispozitive BLE din apropiere. Orice prezenta noua (telefon, tag,
    // ceas, casti) = posibil intrus langa senzorul lasat nesupravegheat -> alerta in CCC.
    private fun runBleScan() {
        if (!hasBtScanPerm()) return
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = mgr.adapter ?: return
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner ?: return
        seen.clear()
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            scanner?.startScan(null, settings, scanCallback)
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
            "bt_kinds" to kinds.joinToString(",").ifEmpty { "necunoscut" }
        ))
    }

    // Identifica tipul dispozitivului dupa manufacturer ID din advertising packet.
    private fun classifyBle(result: ScanResult): String {
        val mfg = result.scanRecord?.manufacturerSpecificData
        if (mfg != null) {
            for (i in 0 until mfg.size()) {
                when (mfg.keyAt(i)) {
                    0x004C -> return "Apple/AirTag?"   // Apple (AirTag, iPhone, AirPods)
                    0x0075 -> return "Samsung/SmartTag?" // Samsung (SmartTag, Galaxy)
                    0x00E0 -> return "Google"
                    0x0006 -> return "Microsoft"
                }
            }
        }
        return ""
    }

    private fun hasBtScanPerm(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    } else true

    // === Storage plin ===
    private fun checkStorage() {
        try {
            val dir = Storage.baseDir(ctx)
            val freeMb = dir.usableSpace / 1_000_000L
            if (freeMb in 0 until STORAGE_MIN_MB) {
                if (!storageAlerted) {
                    storageAlerted = true
                    sendAlert("storage_low", mapOf("free_mb" to freeMb))
                }
            } else {
                storageAlerted = false  // s-a eliberat spatiu -> permite o noua alerta viitoare
            }
        } catch (_: Exception) {}
    }

    private fun batteryPct(): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun simState(): Int = try {
        (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simState
            ?: TelephonyManager.SIM_STATE_UNKNOWN
    } catch (_: Exception) { TelephonyManager.SIM_STATE_UNKNOWN }

    private fun sendAlert(reason: String, extra: Map<String, Any?>) {
        val token = prefs.getString("jwt_token", null) ?: return  // fara token, fara alerta
        Thread {
            try {
                val sim = simState()
                val body = JSONObject().apply {
                    put("reason", reason)
                    put("lat", locationLat())
                    put("lon", locationLon())
                    put("battery", batteryPct())
                    put("sim_state", sim)            // 1=ABSENT, 5=READY
                    put("sim_removed", sim == TelephonyManager.SIM_STATE_ABSENT)
                    put("device", android.os.Build.MODEL)
                    extra.forEach { (k, v) -> put(k, v) }
                }
                val conn = (URL("$server/api/auth/mobile-alert").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000; readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (_: Exception) {
                // retea indisponibila / eroare -> ignora (non-fatal)
            }
        }.start()
    }
}

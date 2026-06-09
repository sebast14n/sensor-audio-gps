package com.example.logger

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        var isRunning = false
        var currentGpxPath: String? = null   // calea GPX a sesiunii curente (pt harta traseu live)
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIF_ID   = 1
        private const val SEGMENT_MS = 10 * 60 * 1000L
        private const val WINDOW_CHECK_MS = 60_000L   // verificare fereastra orara la 60s
    }

    private var segRec: AudioSegmentRecorder? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var gpxFile: File? = null
    private var sessionDir: File? = null
    private var segmentTimer: Timer? = null
    private var windowTimer: Timer? = null
    private var segmentIndex = 0
    private val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).also {
        it.timeZone = TimeZone.getTimeZone("UTC")
    }

    private var staticLat: Double? = null
    private var staticLon: Double? = null
    private var gpsAvailable = false
    private var isFixedPoint = false  // true = opreste GPS dupa primul fix bun

    // Mod programat: inregistreaza DOAR in fereastra nocturna (apus-buffer .. rasarit+buffer)
    private var scheduled = false
    private var recordingActive = false
    // ultima pozitie cunoscuta (pt. calculul ferestrei rasarit/apus)
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    // WakeLock — tinut DOAR cat timp inregistram efectiv (nu permanent)
    private var wakeLock: PowerManager.WakeLock? = null
    private var antiTheft: AntiTheftMonitor? = null
    private var batteryTimer: Timer? = null
    private var cmdTimer: Timer? = null            // C&C: poll comenzi la 15 min (senzor fix)
    @Volatile private var forcedRecording = false  // record_now -> inregistreaza si in afara ferestrei
    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also {
        it.timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorLogger::RecordingWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lat = intent?.getDoubleExtra("static_lat", Double.NaN) ?: Double.NaN
        val lon = intent?.getDoubleExtra("static_lon", Double.NaN) ?: Double.NaN
        if (!lat.isNaN() && !lon.isNaN()) {
            staticLat = lat; staticLon = lon; lastLat = lat; lastLon = lon
        }
        isFixedPoint = intent?.getBooleanExtra("fixed_point", false) ?: false
        scheduled = intent?.getBooleanExtra("scheduled", false) ?: false

        // Android 14+ cere declararea explicita a tipului de serviciu
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        startSession()
        startBatteryLog()
        // C&C: senzor fix nesupravegheat -> accepta comenzi de la operator (poll la 15 min)
        if (isFixedPoint) startCommandLoop()
        return START_STICKY
    }

    /** Logger baterie: la 5 min scrie un rand in /BioEcho/battery_log.csv pt analiza consumului
     *  (charge_counter µAh = consum exact intre 2 puncte). Pt optimizarea programului/autonomiei. */
    private fun startBatteryLog() {
        batteryTimer?.cancel()
        batteryTimer = Timer().also {
            it.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { try { logBatteryRow() } catch (_: Exception) {} }
            }, 0L, 5 * 60 * 1000L)
        }
    }

    private fun logBatteryRow() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return
        val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)   // µAh ramasi
        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)     // µA (− = descarcare)
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)        // %
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val volt = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temp = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL) 1 else 0
        val f = File(Storage.baseDir(this), "battery_log.csv")
        if (!f.exists()) f.appendText("utc,charge_uah,current_ua,level,temp_c,voltage_mv,charging,recording,session\n")
        f.appendText("${isoUtc.format(Date())},$cc,$cur,$lvl,${if (temp > 0) temp / 10.0 else ""}," +
            "$volt,$charging,${if (recordingActive) 1 else 0},${sessionDir?.name ?: ""}\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        currentGpxPath = null
        windowTimer?.cancel()
        batteryTimer?.cancel()
        cmdTimer?.cancel()
        pauseRecording()
        closeGpx()
        antiTheft?.stop()
        locationListener?.let { locationManager?.removeUpdates(it) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession() {
        val ts = sdf.format(Date())
        val base = Storage.baseDir(this)
        sessionDir = File(base, "session_$ts").also { it.mkdirs() }

        gpxFile = File(sessionDir, "track_$ts.gpx")
        currentGpxPath = gpxFile?.absolutePath
        gpxFile?.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"SensorLogger\">\n" +
            "  <trk><n>$ts</n><trkseg>\n"
        )

        if (staticLat != null && staticLon != null) {
            writeStaticPoint(staticLat!!, staticLon!!)
        } else {
            startGps()
        }

        // Anti-furt: DOAR in mod senzor nesupravegheat (scheduled) — altfel, in transect,
        // miscarea operatorului ar genera alarme false. Non-fatal daca esueaza.
        if (scheduled) {
            try {
                antiTheft = AntiTheftMonitor(this) { lastLat ?: staticLat }
                    .also { it.locationLon = { lastLon ?: staticLon }; it.start() }
            } catch (_: Exception) {}
        }

        if (scheduled) {
            // evalueaza imediat + verifica periodic fereastra
            evaluateWindow()
            windowTimer = Timer()
            windowTimer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { evaluateWindow() }
            }, WINDOW_CHECK_MS, WINDOW_CHECK_MS)
        } else {
            resumeRecording()
        }
    }

    /** In mod programat: porneste/opreste inregistrarea dupa fereastra nocturna.
     *  forcedRecording (record_now de la C&C) -> inregistreaza mereu, ignora fereastra. */
    @Synchronized
    private fun evaluateWindow() {
        if (forcedRecording) { if (!recordingActive) resumeRecording(); return }
        val active = RecordWindow.isActiveNow(lastLat ?: staticLat, lastLon ?: staticLon)
        if (active && !recordingActive) {
            resumeRecording()
        } else if (!active && recordingActive) {
            pauseRecording()
            updateNotification("⏸ In afara ferestrei — astept apusul (economie baterie)")
        }
    }

    /** Porneste inregistrarea audio + tine wakelock-ul. */
    @Synchronized
    private fun resumeRecording() {
        if (recordingActive) return
        recordingActive = true
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
        startAudioSegment()
        segmentTimer = Timer()
        segmentTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { stopAudio(); startAudioSegment() }
        }, SEGMENT_MS, SEGMENT_MS)
    }

    /** Opreste inregistrarea + elibereaza wakelock-ul (economie baterie). */
    @Synchronized
    private fun pauseRecording() {
        recordingActive = false
        segmentTimer?.cancel(); segmentTimer = null
        stopAudio()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun startAudioSegment() {
        val ts = sdf.format(Date())
        segmentIndex++
        // baza fara extensie; recorderul alege .flac sau .wav
        val base = File(sessionDir, "audio_${"%03d".format(segmentIndex)}_$ts")
        // LOSSLESS: WAV implicit (fiabil, = Song Meter); FLAC experimental daca userul a ales
        val useFlac = getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("audio_format", "wav") == "flac"
        var rec = AudioSegmentRecorder(48000, 1, preferFlac = useFlac)
        val f = try { rec.start(base) } catch (e: Exception) { null }
        if (f == null && useFlac) {
            // esec FLAC -> reincearca WAV (nu pierdem segmentul)
            rec = AudioSegmentRecorder(48000, 1, preferFlac = false)
            try { rec.start(base) } catch (_: Exception) {}
        }
        segRec = rec
        logAudioDevice()
    }

    private fun logAudioDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val activeInput = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSink.not() }
        val label = when (activeInput?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC       -> "microfon intern"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE        -> "microfon USB-C"
            AudioDeviceInfo.TYPE_WIRED_HEADSET     -> "microfon jack 3.5mm"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO     -> "microfon Bluetooth"
            else -> activeInput?.productName?.toString() ?: "necunoscut"
        }
        updateNotification("🔴 Audio + GPS · $label · 48kHz")
    }

    private fun stopAudio() {
        try { segRec?.stop() } catch (_: Exception) {}
        segRec = null
    }

    private fun startGps() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true) {
            updateNotification("⚠️ GPS dezactivat — doar audio")
            return
        }

        // Senzor fix: GPS activ pana la primul fix bun (precizie < 20m), apoi oprit
        // Transect: GPS activ tot timpul, update la 30s / 20m (suficient pentru deplasare pedestriana)
        val gpsIntervalMs = if (isFixedPoint) 3_000L else 30_000L
        val gpsMinDistance = if (isFixedPoint) 0f else 20f

        locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lastLat = loc.latitude; lastLon = loc.longitude
                writeGpxPoint(loc)
                if (!gpsAvailable) {
                    gpsAvailable = true
                }
                if (isFixedPoint && loc.hasAccuracy() && loc.accuracy < 20f) {
                    // Fix bun obtinut — oprim GPS-ul, economisim bateria
                    locationManager?.removeUpdates(this)
                    updateNotification("🔴 Audio · fix GPS ±${loc.accuracy.toInt()}m · GPS oprit")
                } else if (!isFixedPoint) {
                    updateNotification("🔴 Audio + GPS activ")
                }
            }
            @Deprecated("Deprecated")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {
                updateNotification("⚠️ GPS oprit în timpul înregistrării")
            }
        }

        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, gpsIntervalMs, gpsMinDistance, locationListener!!
            )
        } catch (e: SecurityException) { e.printStackTrace() }
    }

    private fun writeGpxPoint(loc: Location) {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .also { it.timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(loc.time))
        gpxFile?.appendText(
            "    <trkpt lat=\"${loc.latitude}\" lon=\"${loc.longitude}\">\n" +
            "      <ele>${loc.altitude}</ele><time>$utc</time>\n" +
            "    </trkpt>\n"
        )
    }

    private fun writeStaticPoint(lat: Double, lon: Double) {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .also { it.timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        gpxFile?.appendText(
            "    <!-- locatie statica introdusa manual -->\n" +
            "    <trkpt lat=\"$lat\" lon=\"$lon\">\n" +
            "      <ele>0</ele><time>$utc</time>\n" +
            "    </trkpt>\n"
        )
    }

    private fun closeGpx() {
        try { gpxFile?.appendText("  </trkseg></trk>\n</gpx>\n") } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Înregistrare", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String = "Pornire..."): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SensorLogger — înregistrare activă")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    // ── C&C: comenzi de la operator (poll la 15 min, doar senzor fix) ──────────

    private fun startCommandLoop() {
        cmdTimer?.cancel()
        cmdTimer = Timer().also {
            it.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { try { commandCycle() } catch (_: Exception) {} }
            }, 30_000L, 3 * 60 * 1000L)   // primul ciclu la 30s, apoi la 3 min
        }
    }

    /** Un ciclu: heartbeat -> primeste comenzile in asteptare -> executa -> ack. */
    private fun commandCycle() {
        val cmds = CommandClient.postHeartbeat(this, buildHeartbeat()) ?: return
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i) ?: continue
            val id = c.optString("id")
            val cmd = c.optString("cmd")
            val args = c.optJSONObject("args") ?: JSONObject()
            var status = "done"
            var result = ""
            try { result = executeCommand(cmd, args) }
            catch (e: Exception) { status = "failed"; result = e.message ?: "eroare" }
            if (id.isNotBlank()) CommandClient.ack(this, id, status, result)
        }
    }

    private fun buildHeartbeat(): JSONObject {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val lvl = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val freeMb = try {
            val s = android.os.StatFs(Storage.baseDir(this).absolutePath)
            (s.availableBytes / (1024L * 1024L)).toInt()
        } catch (_: Exception) { -1 }
        return JSONObject().apply {
            put("device_id", CommandClient.deviceId(this@RecordingService))
            put("label", "BioEcho ${Build.MODEL}")
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("app_version", BuildConfig.VERSION_NAME)
            put("battery", lvl)
            put("charging", charging)
            (lastLat ?: staticLat)?.let { put("lat", it) }
            (lastLon ?: staticLon)?.let { put("lon", it) }
            put("disk_free_mb", freeMb)
            put("recording", recordingActive)
            put("scheduled", scheduled)
            put("session", sessionDir?.name ?: "")
        }
    }

    /** Executa o comanda dintr-un SET FIX (niciodata cod arbitrar). Intoarce un rezultat scurt. */
    private fun executeCommand(cmd: String, args: JSONObject): String = when (cmd) {
        "status" -> "ok"
        "reconfigure" -> { applyScheduleFromPrefs(); evaluateWindow(); "reconfigurat" }
        "locate" -> {
            val la = lastLat ?: staticLat; val lo = lastLon ?: staticLon
            if (la != null && lo != null) "lat=$la lon=$lo" else "fara GPS"
        }
        "update" -> {
            val url = fetchLatestApkUrl()
            if (url == null) "nu am gasit APK"
            else if (AppUpdater.downloadAndInstallCtx(this, url)) "update pornit" else "update esuat"
        }
        "set_schedule" -> {
            val en = if (args.has("enabled")) args.optBoolean("enabled") else true
            getSharedPreferences("bioecho_prefs", MODE_PRIVATE).edit().putBoolean("schedule_enabled", en).apply()
            applyScheduleFromPrefs(); evaluateWindow()
            "schedule_enabled=$en"
        }
        "record_now" -> { forcedRecording = true; evaluateWindow(); "inregistrez acum" }
        "record_auto" -> { forcedRecording = false; evaluateWindow(); "revin la program" }
        else -> "comanda necunoscuta: $cmd"
    }

    /** schedule_enabled=false => inregistrare continua (forcedRecording). */
    private fun applyScheduleFromPrefs() {
        val en = getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getBoolean("schedule_enabled", true)
        forcedRecording = !en
    }

    private fun fetchLatestApkUrl(): String? = try {
        val c = URL("${BuildConfig.SERVER_URL}/api/app/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        JSONObject(body).optString("apk_url", "").ifBlank { null }
    } catch (_: Exception) { null }
}

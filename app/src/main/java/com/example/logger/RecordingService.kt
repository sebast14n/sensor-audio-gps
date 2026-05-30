package com.example.logger

import android.app.*
import android.content.Context
import android.content.Intent
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIF_ID   = 1
        private const val SEGMENT_MS = 10 * 60 * 1000L
        private const val WINDOW_CHECK_MS = 60_000L   // verificare fereastra orara la 60s
    }

    private var recorder: MediaRecorder? = null
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
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        windowTimer?.cancel()
        pauseRecording()
        closeGpx()
        antiTheft?.stop()
        locationListener?.let { locationManager?.removeUpdates(it) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession() {
        val ts = sdf.format(Date())
        val base = getExternalFilesDir(null) ?: filesDir
        sessionDir = File(base, "session_$ts").also { it.mkdirs() }

        gpxFile = File(sessionDir, "track_$ts.gpx")
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

    /** In mod programat: porneste/opreste inregistrarea dupa fereastra nocturna. */
    private fun evaluateWindow() {
        val active = RecordWindow.isActiveNow(lastLat ?: staticLat, lastLon ?: staticLon)
        if (active && !recordingActive) {
            resumeRecording()
        } else if (!active && recordingActive) {
            pauseRecording()
            updateNotification("⏸ In afara ferestrei — astept apusul (economie baterie)")
        }
    }

    /** Porneste inregistrarea audio + tine wakelock-ul. */
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
    private fun pauseRecording() {
        recordingActive = false
        segmentTimer?.cancel(); segmentTimer = null
        stopAudio()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun startAudioSegment() {
        val ts = sdf.format(Date())
        segmentIndex++
        val file = File(sessionDir, "audio_${"%03d".format(segmentIndex)}_$ts.m4a")

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this)
        else
            @Suppress("DEPRECATION") MediaRecorder()

        recorder?.apply {
            // UNPROCESSED = sunet brut fara AGC/noise-suppression Android (ideal pt. bioacustica)
            // Fallback la MIC daca dispozitivul nu suporta UNPROCESSED
            val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.MIC
            }
            setAudioSource(audioSource)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(48000)   // rata nativa BirdNET
            setAudioEncodingBitRate(256000)
            setAudioChannels(1)           // mono explicit
            setOutputFile(file.absolutePath)
            try {
                prepare(); start()
                logAudioDevice()
            } catch (e: Exception) {
                // UNPROCESSED nesustinut — reincearca cu MIC
                if (audioSource == MediaRecorder.AudioSource.UNPROCESSED) {
                    reset()
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(48000)
                    setAudioEncodingBitRate(256000)
                    setAudioChannels(1)
                    setOutputFile(file.absolutePath)
                    try { prepare(); start() } catch (e2: Exception) { e2.printStackTrace() }
                } else {
                    e.printStackTrace()
                }
            }
        }
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
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
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
}

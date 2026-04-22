package com.example.logger

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
    }

    private var recorder: MediaRecorder? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var gpxFile: File? = null
    private var sessionDir: File? = null
    private var segmentTimer: Timer? = null
    private var segmentIndex = 0
    private val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var staticLat: Double? = null
    private var staticLon: Double? = null
    private var gpsAvailable = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lat = intent?.getDoubleExtra("static_lat", Double.NaN) ?: Double.NaN
        val lon = intent?.getDoubleExtra("static_lon", Double.NaN) ?: Double.NaN
        if (!lat.isNaN() && !lon.isNaN()) { staticLat = lat; staticLon = lon }

        startForeground(NOTIF_ID, buildNotification())
        startSession()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        segmentTimer?.cancel()
        stopAudio()
        closeGpx()
        locationListener?.let { locationManager?.removeUpdates(it) }
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
            updateNotification("Audio + locație statică (${"%.4f".format(staticLat)}, ${"%.4f".format(staticLon)})")
        } else {
            startGps()
        }

        startAudioSegment()

        segmentTimer = Timer()
        segmentTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { stopAudio(); startAudioSegment() }
        }, SEGMENT_MS, SEGMENT_MS)
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
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(file.absolutePath)
            try { prepare(); start() } catch (e: Exception) { e.printStackTrace() }
        }
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

        locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (!gpsAvailable) {
                    gpsAvailable = true
                    updateNotification("🔴 Audio + GPS activ")
                }
                writeGpxPoint(loc)
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
                LocationManager.GPS_PROVIDER, 5_000L, 5f, locationListener!!
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

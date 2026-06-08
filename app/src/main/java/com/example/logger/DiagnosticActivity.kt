package com.example.logger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Diagnostic pre-amplasare: ce senzori are telefonul (✓/✗), probă VIZUALĂ de microfon
 * (bară de nivel care saltă când vorbești — „1-2-3"), test GPS și starea bateriei.
 */
class DiagnosticActivity : AppCompatActivity() {

    private lateinit var micBar: ProgressBar
    private lateinit var micDb: TextView
    private lateinit var micDev: TextView
    private lateinit var gpsText: TextView
    private lateinit var battText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var audio: AudioRecord? = null
    @Volatile private var micRunning = false
    private var micThread: Thread? = null
    private var locMgr: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var gpsStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFF121212.toInt())
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(h1("🔧 Diagnostic telefon"))
        root.addView(hint("Verifică înainte să lași telefonul în teren."))

        // ── Senzori ──
        root.addView(h2("Senzori"))
        root.addView(sensorReport())

        // ── Microfon ──
        root.addView(h2("🎙 Probă microfon"))
        root.addView(hint("Spune „1-2-3” sau bate din palme — bara trebuie să salte. Dacă stă pe loc, microfonul nu captează."))
        micDev = small(""); root.addView(micDev)
        micBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
        }
        root.addView(micBar)
        micDb = small("nivel: —"); root.addView(micDb)

        // ── GPS ──
        root.addView(h2("📡 GPS"))
        gpsText = small("apasă „Testează fix”"); root.addView(gpsText)
        root.addView(Button(this).apply { text = "Testează fix GPS"; setOnClickListener { testGps() } })

        // ── Baterie ──
        root.addView(h2("🔋 Baterie"))
        battText = small("…"); root.addView(battText)
        root.addView(Button(this).apply { text = "Reîmprospătează"; setOnClickListener { readBattery() } })

        // ── Format înregistrare (WAV implicit / FLAC experimental) ──
        root.addView(h2("💾 Format înregistrare"))
        root.addView(hint("Ambele lossless. FLAC ≈ ½ din WAV, dar e experimental: înregistrează un test, " +
            "verifică în „🗂 Înregistrări” că se aude + se urcă; dacă e bun, îl facem implicit."))
        val prefs = getSharedPreferences("bioecho_prefs", Context.MODE_PRIVATE)
        val fmtText = small("")
        fun refreshFmt() {
            fmtText.text = if (prefs.getString("audio_format", "wav") == "flac")
                "Acum: FLAC (experimental)" else "Acum: WAV (implicit, identic Song Meter)"
        }
        refreshFmt(); root.addView(fmtText)
        root.addView(Button(this).apply {
            text = "Comută WAV ⇄ FLAC"
            setOnClickListener {
                val cur = prefs.getString("audio_format", "wav")
                prefs.edit().putString("audio_format", if (cur == "flac") "wav" else "flac").apply()
                refreshFmt()
            }
        })

        root.addView(Button(this).apply { text = "← Înapoi"; setOnClickListener { finish() } })

        setContentView(scroll)
        locMgr = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        readBattery()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 11)
        }
    }

    // ── UI helpers ──
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun h1(s: String) = TextView(this).apply {
        text = s; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, 0, 6) }
    private fun h2(s: String) = TextView(this).apply {
        text = s; textSize = 16f; setTextColor(0xFF80CBC4.toInt()); setPadding(0, 22, 0, 8) }
    private fun hint(s: String) = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(0xFF9E9E9E.toInt()); setPadding(0, 0, 0, 6) }
    private fun small(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(0xFFE0E0E0.toInt()); setPadding(0, 4, 0, 4) }

    // ── Senzori ──
    private fun sensorReport(): TextView {
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        fun has(t: Int) = sm?.getDefaultSensor(t) != null
        fun line(ok: Boolean, name: String) = (if (ok) "✓ " else "✗ ") + name
        val gps = locMgr?.allProviders?.contains(LocationManager.GPS_PROVIDER) == true
        val sb = StringBuilder()
        sb.appendLine(line(has(Sensor.TYPE_ACCELEROMETER), "Accelerometru (mișcare)"))
        sb.appendLine(line(has(Sensor.TYPE_SIGNIFICANT_MOTION), "Significant-motion (anti-furt, low-power)"))
        sb.appendLine(line(has(Sensor.TYPE_GYROSCOPE), "Giroscop"))
        sb.appendLine(line(has(Sensor.TYPE_MAGNETIC_FIELD), "Magnetometru (busolă)"))
        sb.appendLine(line(has(Sensor.TYPE_LIGHT), "Senzor lumină"))
        sb.appendLine(line(gps, "GPS"))
        val all = sm?.getSensorList(Sensor.TYPE_ALL)?.size ?: 0
        sb.append("\nTotal senzori raportați: $all")
        return small(sb.toString())
    }

    // ── Microfon (VU meter live) ──
    private fun startMic() {
        if (micRunning) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micDb.text = "⚠ fără permisiune microfon"; return
        }
        micDev.text = "microfon activ: " + activeMic()
        val sr = 48000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { micDb.text = "⚠ microfon indisponibil"; return }
        val src = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val ar = try {
            AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        } catch (e: Exception) { micDb.text = "⚠ ${e.message?.take(40)}"; return }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            // fallback la MIC daca UNPROCESSED nu merge
            try { ar.release() } catch (_: Exception) {}
            micDb.text = "⚠ nu pot deschide microfonul"; return
        }
        audio = ar
        micRunning = true
        try { ar.startRecording() } catch (e: Exception) { micRunning = false; micDb.text = "⚠ start eșuat"; return }
        micThread = Thread {
            val buf = ShortArray(minBuf)
            while (micRunning) {
                val n = try { ar.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toInt(); sum += (s * s).toDouble() }
                val rms = sqrt(sum / n)
                val dbfs = if (rms > 0) 20 * log10(rms / 32768.0) else -90.0   // -90..0
                val pct = (((dbfs + 60.0) / 60.0) * 100).coerceIn(0.0, 100.0).toInt()  // -60dB->0, 0dB->100
                handler.post {
                    micBar.progress = pct
                    micDb.text = "nivel: ${dbfs.toInt()} dBFS" + (if (pct > 4) "  🔊" else "  (liniște)")
                }
            }
        }.also { it.start() }
    }

    private fun stopMic() {
        micRunning = false
        try { micThread?.join(300) } catch (_: Exception) {}
        micThread = null
        try { audio?.stop() } catch (_: Exception) {}
        try { audio?.release() } catch (_: Exception) {}
        audio = null
    }

    private fun activeMic(): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "necunoscut"
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val d = ins.firstOrNull() ?: return "intern (presupus)"
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "intern"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "jack 3.5mm"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> d.productName?.toString() ?: "necunoscut"
        }
    }

    // ── GPS ──
    private fun testGps() {
        val lm = locMgr ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 12)
            gpsText.text = "acordă permisiunea de locație și reîncearcă"; return
        }
        gpsText.text = "aștept fix GPS…"
        gpsStart = SystemClock.elapsedRealtime()
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val sec = (SystemClock.elapsedRealtime() - gpsStart) / 1000.0
                gpsText.text = "✓ %.5f, %.5f · ±%.0f m · %.0fs".format(loc.latitude, loc.longitude, loc.accuracy, sec)
                try { lm.removeUpdates(this) } catch (_: Exception) {}
                gpsListener = null
            }
            @Deprecated("compat") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderDisabled(p: String) { gpsText.text = "⚠ GPS dezactivat în setări" }
            override fun onProviderEnabled(p: String) {}
        }
        gpsListener = listener
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) { gpsText.text = "⚠ ${e.message?.take(40)}" }
    }

    // ── Baterie ──
    private fun readBattery() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val curUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val volt = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val mA = curUa / 1000
        battText.text = buildString {
            append("Nivel: $level%\n")
            if (volt > 0) append("Tensiune: ${volt / 1000.0} V\n")
            if (temp > 0) append("Temperatură: ${temp / 10.0} °C\n")
            append("Curent acum: ~$mA mA" + (if (mA < 0) " (descărcare)" else if (mA > 0) " (încărcare)" else "") + "\n")
            append("La priză: " + (if (charging) "da" else "nu"))
        }
    }

    override fun onResume() { super.onResume(); startMic() }
    override fun onPause() { super.onPause(); stopMic(); gpsListener?.let { try { locMgr?.removeUpdates(it) } catch (_: Exception) {} } }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 11 && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startMic()
    }
}

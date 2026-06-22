package com.example.logger

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * „🎤 Ascultă live" — identificare păsări pe telefon, în timp real, cu BirdNET (ca Merlin Sound ID).
 *
 * Microfonul -> ferestre de 3 s @ 48 kHz (pas 1.5 s) -> model BirdNET local -> specii probabile, live.
 * Modelul (~26 MB, CC BY-NC-SA 4.0) + etichetele RO se descarca O DATA de pe noze.ro si raman cache-uite.
 * 100% pe dispozitiv dupa descarcare; orientativ (de confirmat mereu auditiv/vizual).
 */
class LiveListenActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnRec: Button
    private lateinit var currentBox: LinearLayout
    private lateinit var tvSessionHdr: TextView
    private lateinit var sessionBox: LinearLayout

    private val PERM_REQ = 701
    @Volatile private var listening = false
    private var audioThread: Thread? = null
    @Volatile private var classifier: BirdNetClassifier? = null

    private class Agg(val sci: String, val common: String, var max: Float, var count: Int)
    private val session = LinkedHashMap<String, Agg>()

    // ── inregistrare in timpul ascultarii (scriem WAV din ACELASI flux PCM) ──
    private val wavLock = Any()
    @Volatile private var recording = false
    private var wavRaf: RandomAccessFile? = null
    private var wavBytes = 0L
    private var wavFile: File? = null

    private val SR = 48000
    private val WIN = 144000     // 3.0 s
    private val HOP = 72000      // 1.5 s -> actualizare ~ la 1.5 s
    private val CONFIRM = 0.20f  // prag adaugare in sesiune (permisiv: BirdNET da des 0.2-0.4 pt pasari reale)

    private val modelDir get() = File(filesDir, "birdnet")
    private val modelFile get() = File(modelDir, "model.tflite")
    private val labelsFile get() = File(modelDir, "labels.txt")
    private val allowedFile get() = File(modelDir, "allowed_ro.txt")   // filtru specii Romania

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "🎤 Ascultă live (păsări)"

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Ține telefonul spre sursa sunetului. Specii limitate la fauna din România. Identificare aproximativă, pe telefon (ca Merlin) — confirm-o mereu."
            setTextColor(0xFFB0BEC5.toInt()); textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        tvStatus = TextView(this).apply {
            text = "⚪ Oprit"; setTextColor(Color.WHITE); textSize = 16f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(tvStatus)

        btnToggle = Button(this).apply {
            text = "🎤  Ascultă live"; textSize = 17f; setTextColor(Color.WHITE)
            setBackgroundColor(0xFF43A047.toInt())
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        root.addView(btnToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        btnRec = Button(this).apply {
            text = "⏺  Înregistrează"; textSize = 15f; setTextColor(Color.WHITE)
            setBackgroundColor(0xFF455A64.toInt())
            isEnabled = false   // activ doar cât ascultăm
            setOnClickListener { if (recording) stopRecording() else startRecording() }
        }
        root.addView(btnRec, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(8)
        })

        root.addView(TextView(this).apply {
            text = "Acum aud:"; setTextColor(0xFF90CAF9.toInt()); textSize = 13f
            setPadding(0, dp(14), 0, dp(4)); setTypeface(typeface, Typeface.BOLD)
        })
        currentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(currentBox)

        tvSessionHdr = TextView(this).apply {
            text = "Specii în sesiune (0):"; setTextColor(0xFF90CAF9.toInt()); textSize = 13f
            setPadding(0, dp(16), 0, dp(4)); setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(tvSessionHdr)

        sessionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(this).apply { addView(sessionBox) }
        root.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = "Model: BirdNET GLOBAL 6K v2.4 — Cornell Lab / TU Chemnitz (CC BY-NC-SA 4.0). Rulează local."
            setTextColor(0xFF555555.toInt()); textSize = 10f; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        setContentView(root)
        showHint("Apasă butonul de sus ca să începi.")
    }

    override fun onPause() {
        super.onPause()
        if (listening) stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        listening = false
        try { classifier?.close() } catch (_: Exception) {}
        classifier = null
    }

    // ── pornire / oprire ────────────────────────────────────────────────────

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERM_REQ)
            return
        }
        ensureModel { beginAudio() }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == PERM_REQ) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startListening()
            else Toast.makeText(this, "Fără acces la microfon nu pot asculta.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopListening() {
        if (recording) stopRecording()
        listening = false
        try { audioThread?.join(1500) } catch (_: Exception) {}
        audioThread = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnToggle.text = "🎤  Ascultă live"
        btnToggle.setBackgroundColor(0xFF43A047.toInt())
        btnRec.isEnabled = false
        tvStatus.text = "⚪ Oprit"
    }

    private fun beginAudio() {
        if (listening || isFinishing || isDestroyed) return
        listening = true
        // ține ecranul aprins cât ascultăm (fără screensaver care oprea sesiunea)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnToggle.text = "⏹  Oprește"
        btnToggle.setBackgroundColor(0xFFE53935.toInt())
        btnRec.isEnabled = true
        tvStatus.text = "🔴 Ascult… (se pregătește modelul)"
        currentBox.removeAllViews()
        audioThread = Thread { audioLoop() }.also { it.start() }
    }

    private fun audioLoop() {
        // 1) modelul (mapare + init interpreter) — pe acest thread, nu pe UI
        if (classifier == null) {
            try {
                classifier = BirdNetClassifier.load(modelFile, labelsFile, allowedFile, threads = 2)
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "⚠ Nu pot încărca modelul: ${e.message?.take(80)}"
                    stopListening()
                }
                return
            }
        }
        val cls = classifier ?: return

        // 2) microfon @ 48 kHz mono
        val chMask = AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(SR, chMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { runOnUiThread { tvStatus.text = "⚠ 48 kHz nesuportat pe acest telefon."; stopListening() }; return }
        val src = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        var ar: AudioRecord? = try {
            AudioRecord(src, SR, chMask, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, WIN * 2))
        } catch (e: Exception) { null }
        if (ar == null || ar!!.state != AudioRecord.STATE_INITIALIZED) {
            try { ar?.release() } catch (_: Exception) {}
            ar = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, SR, chMask,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, WIN * 2))
            } catch (e: Exception) { null }
        }
        val rec = ar
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runOnUiThread { tvStatus.text = "⚠ Nu pot porni microfonul."; stopListening() }
            try { rec?.release() } catch (_: Exception) {}
            return
        }

        val ring = FloatArray(WIN)
        var pos = 0
        var total = 0L
        var since = 0
        val pcm = ShortArray(4800)   // ~0.1 s
        try {
            rec.startRecording()
            runOnUiThread { if (listening) tvStatus.text = "🔴 Ascult…" }
            while (listening) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                if (recording) writePcmToWav(pcm, n)
                for (i in 0 until n) {
                    ring[pos] = pcm[i].toFloat() / 32768f
                    pos = (pos + 1) % WIN
                    total++; since++
                }
                if (total >= WIN && since >= HOP) {
                    since = 0
                    val w = FloatArray(WIN)
                    for (k in 0 until WIN) w[k] = ring[(pos + k) % WIN]   // pos = cel mai vechi (ring plin)
                    val preds = try { cls.classify(w, 5, 0.10f) } catch (e: Exception) { emptyList() }
                    runOnUiThread { renderCurrent(preds); mergeSession(preds) }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { tvStatus.text = "⚠ Eroare audio: ${e.message?.take(80)}" }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
        }
    }

    // ── randare rezultate ─────────────────────────────────────────────────────

    private fun colorFor(score: Float): Int =
        if (score >= 0.7f) 0xFF66BB6A.toInt() else if (score >= 0.4f) 0xFFFFD54F.toInt() else 0xFF90A4AE.toInt()

    private fun renderCurrent(preds: List<BirdNetClassifier.Pred>) {
        currentBox.removeAllViews()
        if (preds.isEmpty()) { showHint("… ascult, niciun cântec clar acum …"); return }
        for (p in preds) {
            val name = if (p.common.isNotBlank()) p.common else p.sci
            val tv = TextView(this).apply {
                text = "🐦  $name  —  ${(p.score * 100).toInt()}%\n      ${p.sci}"
                setTextColor(colorFor(p.score)); textSize = 15f
                setPadding(0, dp(4), 0, dp(4)); setLineSpacing(0f, 1.05f)
            }
            currentBox.addView(tv)
        }
    }

    private fun showHint(msg: String) {
        currentBox.removeAllViews()
        currentBox.addView(TextView(this).apply {
            text = msg; setTextColor(0xFF607D8B.toInt()); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun mergeSession(preds: List<BirdNetClassifier.Pred>) {
        var changed = false
        for (p in preds) {
            if (p.score < CONFIRM) continue
            val key = p.sci.ifBlank { p.common }
            val a = session[key]
            if (a == null) {
                session[key] = Agg(p.sci, p.common, p.score, 1); changed = true
            } else {
                a.count++
                if (p.score > a.max) a.max = p.score
                changed = true
            }
        }
        if (changed) renderSession()
    }

    private fun renderSession() {
        tvSessionHdr.text = "Specii în sesiune (${session.size}):"
        sessionBox.removeAllViews()
        val sorted = session.values.sortedByDescending { it.max }
        for (a in sorted) {
            val name = if (a.common.isNotBlank()) a.common else a.sci
            val row = TextView(this).apply {
                text = "• $name  (${(a.max * 100).toInt()}%, ×${a.count})\n     ${a.sci}"
                setTextColor(colorFor(a.max)); textSize = 14f
                setPadding(0, dp(3), 0, dp(3)); setLineSpacing(0f, 1.05f)
            }
            sessionBox.addView(row)
        }
    }

    // ── inregistrare WAV in timpul ascultarii ─────────────────────────────────

    private fun startRecording() {
        if (!listening) { Toast.makeText(this, "Pornește mai întâi ascultarea.", Toast.LENGTH_SHORT).show(); return }
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(Storage.baseDir(this), "session_live_$ts").apply { mkdirs() }
            val f = File(dir, "audio_live_$ts.wav")
            val raf = RandomAccessFile(f, "rw")
            writeWavHeader(raf, 0)
            synchronized(wavLock) { wavRaf = raf; wavBytes = 0L; wavFile = f; recording = true }
            btnRec.text = "⏹  Oprește înregistrarea ●"
            btnRec.setBackgroundColor(0xFFE53935.toInt())
            Toast.makeText(this, "Înregistrez audio în ${dir.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Nu pot înregistra: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        val raf: RandomAccessFile?; val nbytes: Long
        synchronized(wavLock) { recording = false; raf = wavRaf; nbytes = wavBytes; wavRaf = null }
        if (raf != null) {
            try {
                raf.seek(4);  writeIntLE(raf, (36 + nbytes).toInt())
                raf.seek(40); writeIntLE(raf, nbytes.toInt())
                raf.close()
            } catch (_: Exception) {}
        }
        runOnUiThread {
            btnRec.text = "⏺  Înregistrează"
            btnRec.setBackgroundColor(0xFF455A64.toInt())
            val secs = nbytes / (SR.toLong() * 2)
            if (nbytes > 0) Toast.makeText(this, "Salvat: ${wavFile?.name} (${secs}s) — în Înregistrări", Toast.LENGTH_LONG).show()
        }
    }

    /** Scrie un bloc PCM (mono 16-bit) in fisierul WAV curent. Apelat din thread-ul audio. */
    private fun writePcmToWav(buf: ShortArray, n: Int) {
        val raf = wavRaf ?: return
        try {
            val b = ByteArray(n * 2)
            for (i in 0 until n) {
                val s = buf[i].toInt()
                b[i * 2] = (s and 0xFF).toByte()
                b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            synchronized(wavLock) {
                if (recording && wavRaf === raf) { raf.write(b); wavBytes += b.size }
            }
        } catch (_: Exception) {}
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Int) {
        raf.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()))
    }

    private fun writeShortLE(raf: RandomAccessFile, v: Int) {
        raf.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }

    /** Header WAV PCM 16-bit mono @ SR. dataLen=0 la start; corectat la stop. */
    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int) {
        val ch = 1; val bits = 16
        val byteRate = SR * ch * bits / 8
        raf.seek(0)
        raf.writeBytes("RIFF"); writeIntLE(raf, 36 + dataLen); raf.writeBytes("WAVE")
        raf.writeBytes("fmt "); writeIntLE(raf, 16); writeShortLE(raf, 1); writeShortLE(raf, ch)
        writeIntLE(raf, SR); writeIntLE(raf, byteRate); writeShortLE(raf, ch * bits / 8); writeShortLE(raf, bits)
        raf.writeBytes("data"); writeIntLE(raf, dataLen)
    }

    // ── descarcare model + etichete (o data) ──────────────────────────────────

    private val MODEL_BYTES = 25932528L

    private fun modelReady(): Boolean =
        modelFile.exists() && modelFile.length() == MODEL_BYTES && labelsFile.exists() && labelsFile.length() > 1000L

    private fun ensureModel(onReady: () -> Unit) {
        val base = BuildConfig.SERVER_URL
        if (modelReady() && allowedFile.exists()) { onReady(); return }
        modelDir.mkdirs()
        // modelul e deja descarcat (versiune veche) -> ia doar lista RO (mica), apoi continua
        if (modelReady() && !allowedFile.exists()) {
            Thread {
                try { downloadTo("$base/static/birdnet/allowed_ro.txt", allowedFile) { _, _ -> } } catch (_: Exception) {}
                runOnUiThread { if (!isFinishing && !isDestroyed) onReady() }
            }.start()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), 0)
        }
        val msg = TextView(this).apply {
            text = "Se descarcă modelul BirdNET (~26 MB), o singură dată…"; textSize = 14f
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; max = 100; setPadding(0, dp(12), 0, 0)
        }
        box.addView(msg); box.addView(bar)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Pregătire identificare live")
            .setView(box).setCancelable(false).create()
        dlg.show()

        Thread {
            try {
                downloadTo("$base/static/birdnet/labels.txt", labelsFile) { _, _ -> }
                downloadTo("$base/static/birdnet/model.tflite", modelFile) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    runOnUiThread {
                        bar.progress = pct
                        msg.text = "Se descarcă modelul BirdNET… $pct%  (${done / 1048576} / ${total / 1048576} MB)"
                    }
                }
                try { downloadTo("$base/static/birdnet/allowed_ro.txt", allowedFile) { _, _ -> } } catch (_: Exception) {}
                if (!modelReady()) throw IllegalStateException("descărcare incompletă")
                runOnUiThread {
                    try { dlg.dismiss() } catch (_: Exception) {}
                    if (!isFinishing && !isDestroyed) onReady()
                }
            } catch (e: Exception) {
                try { modelFile.delete() } catch (_: Exception) {}
                runOnUiThread {
                    try { dlg.dismiss() } catch (_: Exception) {}
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this)
                            .setTitle("Descărcare eșuată")
                            .setMessage("Nu am putut descărca modelul de identificare.\n${e.message?.take(120)}\n\nAi nevoie de internet la prima utilizare.")
                            .setPositiveButton("OK", null).show()
                        tvStatus.text = "⚪ Oprit"
                    }
                }
            }
        }.start()
    }

    private fun downloadTo(urlStr: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r); done += r
                        onProgress(done, total)
                    }
                    out.flush()
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
            try { if (tmp.exists()) tmp.delete() } catch (_: Exception) {}
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

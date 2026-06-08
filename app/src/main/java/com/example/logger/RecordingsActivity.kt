package com.example.logger

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Pagina Inregistrari (v1.8.0): multi-select (upload / sterge in lot), bara de progres pe MB,
 * sesiunile deja urcate colorate verde ("✓ urcat"), partial = portocaliu. Upload cu wakelock
 * (nu se mai intrerupe la screen-saver) + reluare (dedup pe server sare fisierele deja urcate).
 */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var uploadManager: UploadManager
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var tvProg: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnUpload: Button
    private lateinit var btnDelete: Button

    private var sessions: List<File> = emptyList()
    private val selected = HashSet<String>()
    private var mediaPlayer: MediaPlayer? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uploadManager = UploadManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20, 28, 20, 20)
            setBackgroundColor(0xFF121212.toInt())
        }
        root.addView(TextView(this).apply {
            text = "🗂 Înregistrări"; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, 0, 6)
        })
        tvStatus = TextView(this).apply { setTextColor(0xFF90CAF9.toInt()); textSize = 12f; setPadding(0, 0, 0, 8) }
        root.addView(tvStatus)

        // bara select tot + actiuni lot
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnAll = Button(this).apply {
            text = "☑ Tot"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
            setOnClickListener { toggleAll() }
        }
        btnUpload = Button(this).apply {
            text = "⬆ Upload"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginEnd = 6 }
            setOnClickListener { uploadSelected() }
        }
        btnDelete = Button(this).apply {
            text = "🗑 Șterge"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            setOnClickListener { deleteSelected() }
        }
        top.addView(btnAll); top.addView(btnUpload); top.addView(btnDelete)
        root.addView(top)

        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(0xFF1E1E1E.toInt()); divider = null
        }
        root.addView(listView)

        // zona progres (ascunsa pana la upload)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        tvProg = TextView(this).apply { setTextColor(0xFFB0BEC5.toInt()); textSize = 12f; visibility = View.GONE; setPadding(0, 6, 0, 0) }
        root.addView(bar); root.addView(tvProg)

        setContentView(root)
        refresh()
    }

    private fun label(dir: File): String {
        val raw = dir.name.removePrefix("session_")
        return try {
            // session_yyyyMMdd_HHmmss
            val d = raw.substringBefore("_"); val t = raw.substringAfter("_")
            "${d.substring(6,8)}.${d.substring(4,6)}.${d.substring(0,4)} ${t.substring(0,2)}:${t.substring(2,4)}"
        } catch (_: Exception) { raw }
    }

    private fun refresh() {
        sessions = uploadManager.getLocalSessions()
        selected.retainAll(sessions.map { it.name }.toSet())
        val total = sessions.sumOf { Storage.dirSize(it) }
        tvStatus.text = "${sessions.size} sesiuni · ${Storage.humanSize(total)} · selectate: ${selected.size}"
        listView.adapter = object : ArrayAdapter<File>(this, 0, sessions) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val dir = sessions[position]
                val row = LinearLayout(this@RecordingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(16, 18, 16, 18)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val cb = CheckBox(this@RecordingsActivity).apply {
                    isChecked = selected.contains(dir.name)
                    setOnClickListener { toggleSel(dir.name) }
                }
                val nFiles = dir.listFiles()?.count { it.name.endsWith(".m4a") || it.name.endsWith(".wav") || it.name.endsWith(".flac") } ?: 0
                val state = UploadManager.sessionState(dir)
                val badge = when (state) { "done" -> "  ✓ urcat"; "partial" -> "  ⚠ parțial"; else -> "" }
                val color = when (state) { "done" -> 0xFF66BB6A.toInt(); "partial" -> 0xFFFFB74D.toInt(); else -> 0xFFE0E0E0.toInt() }
                val tv = TextView(this@RecordingsActivity).apply {
                    text = "${label(dir)}$badge\n$nFiles fișiere · ${Storage.humanSize(Storage.dirSize(dir))}"
                    setTextColor(color); textSize = 13f; setPadding(14, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(cb); row.addView(tv)
                // tap pe rand = meniu actiuni (Asculta/Harta/Upload/Sterge); bifa = selectie pt lot
                row.setOnClickListener { singleActions(dir) }
                row.setOnLongClickListener { singleActions(dir); true }
                return row
            }
        }
    }

    private fun toggleSel(name: String) {
        if (selected.contains(name)) selected.remove(name) else selected.add(name)
        val total = sessions.sumOf { Storage.dirSize(it) }
        tvStatus.text = "${sessions.size} sesiuni · ${Storage.humanSize(total)} · selectate: ${selected.size}"
    }

    private fun toggleAll() {
        if (selected.size == sessions.size) selected.clear()
        else { selected.clear(); selected.addAll(sessions.map { it.name }) }
        refresh()
    }

    // ── Upload in lot ──
    private fun uploadSelected() {
        if (busy) { toast("Upload în curs…"); return }
        val dirs = sessions.filter { selected.contains(it.name) }
        if (dirs.isEmpty()) { toast("Bifează cel puțin o sesiune"); return }
        if (uploadManager.jwtToken.isNullOrBlank()) { toast("⚠ Neautentificat — scanează QR pe pagina principală"); return }
        if (!isWifi()) {
            AlertDialog.Builder(this).setTitle("⚠ Nu ești pe WiFi")
                .setMessage("Upload pe date mobile poate consuma volum mare. Continui?")
                .setPositiveButton("Da") { _, _ -> startQueue(dirs) }
                .setNegativeButton("Anulează", null).show()
        } else startQueue(dirs)
    }

    private fun startQueue(dirs: List<File>) {
        busy = true
        bar.visibility = View.VISIBLE; tvProg.visibility = View.VISIBLE
        btnUpload.isEnabled = false; btnDelete.isEnabled = false
        uploadQueue(dirs, 0, 0, 0)
    }

    private fun uploadQueue(dirs: List<File>, i: Int, okTot: Int, dupTot: Int) {
        if (i >= dirs.size) {
            runOnUiThread {
                bar.visibility = View.GONE; tvProg.visibility = View.GONE
                btnUpload.isEnabled = true; btnDelete.isEnabled = true
                busy = false
                toast("✓ Gata: $okTot urcate · $dupTot deja existau")
                refresh()
            }
            return
        }
        val dir = dirs[i]
        uploadManager.uploadSessionAsync(dir, object : UploadManager.ProgressCallback {
            override fun onProgress(fileIndex: Int, fileCount: Int, fileName: String, bytesDone: Long, bytesTotal: Long) {
                runOnUiThread {
                    val pct = if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt() else 0
                    bar.progress = pct
                    tvProg.text = "Sesiune ${i + 1}/${dirs.size} · fișier $fileIndex/$fileCount · " +
                        "${mb(bytesDone)}/${mb(bytesTotal)} MB ($pct%)"
                }
            }
            override fun onDone(result: UploadManager.UploadResult) {
                runOnUiThread { refresh() }
                uploadQueue(dirs, i + 1, okTot + result.success, dupTot + result.skipped)
            }
            override fun onError(message: String) {
                runOnUiThread { toast("⚠ ${label(dir)}: $message") }
                uploadQueue(dirs, i + 1, okTot, dupTot)
            }
        })
    }

    private fun mb(b: Long) = "%.1f".format(b / 1_000_000.0)

    // ── Sterge in lot ──
    private fun deleteSelected() {
        if (busy) return
        val dirs = sessions.filter { selected.contains(it.name) }
        if (dirs.isEmpty()) { toast("Bifează cel puțin o sesiune"); return }
        val sz = Storage.humanSize(dirs.sumOf { Storage.dirSize(it) })
        AlertDialog.Builder(this).setTitle("Șterge ${dirs.size} sesiuni?")
            .setMessage("$sz se șterg definitiv de pe telefon.")
            .setPositiveButton("🗑 Șterge") { _, _ ->
                var n = 0; dirs.forEach { if (it.deleteRecursively()) n++ }
                selected.clear(); toast("$n sesiuni șterse"); refresh()
            }
            .setNegativeButton("Anulează", null).show()
    }

    // ── Actiuni pe o sesiune (long-press) ──
    private fun singleActions(dir: File) {
        val hasLoc = sessionLatLon(dir) != null
        AlertDialog.Builder(this).setTitle(label(dir))
            .setItems(arrayOf(
                if (hasLoc) "🗺 Unde s-a înregistrat" else "🗺 (fără GPS)",
                "▶ Ascultă", "⬆ Upload doar asta", "🗑 Șterge")) { _, w ->
                when (w) {
                    0 -> showOnMap(dir)
                    1 -> startActivity(Intent(this, PlaybackActivity::class.java)
                            .putExtra("session_path", dir.absolutePath))
                    2 -> { selected.clear(); selected.add(dir.name); uploadSelected() }
                    3 -> { selected.clear(); selected.add(dir.name); deleteSelected() }
                }
            }.setNegativeButton("Înapoi", null).show()
    }

    private fun play(dir: File) {
        val audio = dir.listFiles()?.filter { it.name.endsWith(".m4a") || it.name.endsWith(".wav") || it.name.endsWith(".flac") }
            ?.sortedBy { it.name }?.firstOrNull() ?: run { toast("Niciun audio"); return }
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audio.absolutePath)
                setOnCompletionListener { it.release(); if (mediaPlayer === it) mediaPlayer = null }
                prepare(); start()
            }
            toast("▶ ${audio.name}")
        } catch (e: Exception) { toast("Nu pot reda: ${e.message?.take(50)}") }
    }

    private fun sessionLatLon(dir: File): Pair<Double, Double>? = try {
        val gpx = dir.listFiles()?.firstOrNull { it.name.endsWith(".gpx") } ?: return null
        val m = Regex("<trkpt[^>]*lat=\"([-\\d.]+)\"[^>]*lon=\"([-\\d.]+)\"").find(gpx.readText())
        if (m == null) null else {
            val la = m.groupValues[1].toDouble(); val lo = m.groupValues[2].toDouble()
            if (la == 0.0 && lo == 0.0) null else Pair(la, lo)
        }
    } catch (_: Exception) { null }

    private fun showOnMap(dir: File) {
        val ll = sessionLatLon(dir) ?: run { toast("Fără coordonate GPS"); return }
        startActivity(Intent(this, MapActivity::class.java).apply {
            putExtra("lat", ll.first); putExtra("lon", ll.second); putExtra("name", "📍 ${label(dir)}")
        })
    }

    private fun isWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { super.onDestroy(); try { mediaPlayer?.release() } catch (_: Exception) {} }
}

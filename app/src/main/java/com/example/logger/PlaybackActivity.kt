package com.example.logger

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Redare sesiune cu mini-player VIZIBIL (play/pauză/stop + bară progres + timp) PESTE o hartă,
 * cu un marker care se mișcă pe traseul GPX pe măsură ce „se aud punctele" (la transect).
 * La senzor fix (un singur punct) = marker static + player simplu.
 */
class PlaybackActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var seek: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var tvFile: TextView
    private lateinit var btnPlay: Button

    private val handler = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var segments: List<File> = emptyList()
    private var track: List<GeoPoint> = emptyList()
    private var marker: Marker? = null
    private var curIdx = 0
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        val dir = intent.getStringExtra("session_path")?.let { File(it) }
        if (dir == null || !dir.isDirectory) { Toast.makeText(this, "Sesiune lipsă", Toast.LENGTH_SHORT).show(); finish(); return }
        segments = (dir.listFiles()?.filter {
            it.name.endsWith(".wav") || it.name.endsWith(".flac") || it.name.endsWith(".m4a")
        }?.sortedBy { it.name }) ?: emptyList()
        if (segments.isEmpty()) { Toast.makeText(this, "Niciun audio în sesiune", Toast.LENGTH_SHORT).show(); finish(); return }
        track = parseGpxTrack(dir)

        // ── UI: hartă (sus) + player (jos) ──
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF121212.toInt()) }
        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(mapView)

        val ctl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 18) }
        tvFile = TextView(this).apply { setTextColor(0xFFE0E0E0.toInt()); textSize = 13f }
        ctl.addView(tvFile)
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) tvTime.text = fmtTime(p, seek.max) }
                override fun onStartTrackingTouch(s: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(s: SeekBar?) { try { mp?.seekTo(seek.progress) } catch (_: Exception) {}; userSeeking = false }
            })
        }
        ctl.addView(seek)
        tvTime = TextView(this).apply { setTextColor(0xFF9E9E9E.toInt()); textSize = 12f }
        ctl.addView(tvTime)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
        row.addView(Button(this).apply { text = "⏮"; setOnClickListener { loadSegment(curIdx - 1, true) } })
        btnPlay = Button(this).apply { text = "⏸"; setOnClickListener { togglePlay() } }
        row.addView(btnPlay)
        row.addView(Button(this).apply { text = "⏹"; setOnClickListener { stopAll() } })
        row.addView(Button(this).apply { text = "⏭"; setOnClickListener { loadSegment(curIdx + 1, true) } })
        ctl.addView(row)
        root.addView(ctl)
        setContentView(root)

        // ── Hartă: traseu + marker ──
        if (track.isNotEmpty()) {
            val line = Polyline(mapView).apply { setPoints(track); outlinePaint.color = 0xFF3FA66A.toInt(); outlinePaint.strokeWidth = 7f }
            mapView.overlays.add(line)
            marker = Marker(mapView).apply {
                position = track.first(); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "▶"
            }
            mapView.overlays.add(marker)
            mapView.post {
                try {
                    if (track.size > 1) mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(track), false, 80)
                    else { mapView.controller.setZoom(16.0); mapView.controller.setCenter(track.first()) }
                } catch (_: Exception) {}
            }
        } else {
            mapView.controller.setZoom(5.0)  // fără traseu (senzor fix fără GPS) — doar player
        }

        loadSegment(0, true)
        handler.post(tick)
    }

    private fun loadSegment(i: Int, play: Boolean) {
        if (i !in segments.indices) return
        curIdx = i
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        tvFile.text = "${i + 1}/${segments.size}  ·  ${segments[i].name}"
        try {
            val player = MediaPlayer()
            player.setDataSource(segments[i].absolutePath)
            player.setOnCompletionListener {
                if (curIdx + 1 < segments.size) loadSegment(curIdx + 1, true)
                else { btnPlay.text = "▶"; updateMarker(1.0) }
            }
            player.prepare()
            mp = player
            seek.max = player.duration.coerceAtLeast(1)
            if (play) { player.start(); btnPlay.text = "⏸" }
        } catch (e: Exception) {
            tvFile.text = "⚠ nu pot reda ${segments[i].name}"
        }
    }

    private fun togglePlay() {
        val p = mp ?: return
        try {
            if (p.isPlaying) { p.pause(); btnPlay.text = "▶" }
            else { p.start(); btnPlay.text = "⏸" }
        } catch (_: Exception) {}
    }

    private fun stopAll() {
        try { mp?.pause(); mp?.seekTo(0) } catch (_: Exception) {}
        btnPlay.text = "▶"; seek.progress = 0
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && !userSeeking) {
                try {
                    val pos = p.currentPosition; val dur = p.duration.coerceAtLeast(1)
                    seek.progress = pos
                    tvTime.text = fmtTime(pos, dur)
                    // progres global pe toate segmentele -> pozitie pe traseu
                    val overall = (curIdx + pos.toDouble() / dur) / segments.size
                    updateMarker(overall)
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 250)
        }
    }

    private fun updateMarker(overall: Double) {
        if (track.size < 2) return
        val idx = (overall.coerceIn(0.0, 1.0) * (track.size - 1)).toInt().coerceIn(0, track.size - 1)
        marker?.position = track[idx]
        mapView.invalidate()
    }

    private fun fmtTime(pos: Int, dur: Int): String {
        fun s(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
        return "${s(pos)} / ${s(dur)}"
    }

    /** Trackpoint-uri (lat/lon) din primul .gpx al sesiunii. */
    private fun parseGpxTrack(dir: File): List<GeoPoint> {
        val gpx = dir.listFiles()?.firstOrNull { it.name.endsWith(".gpx") } ?: return emptyList()
        val pts = mutableListOf<GeoPoint>()
        try {
            val txt = gpx.readText()
            for (m in Regex("""<trkpt\b([^>]*)>""").findAll(txt)) {
                val a = m.groupValues[1]
                val lat = Regex("""lat="([-0-9.]+)"""").find(a)?.groupValues?.get(1)?.toDoubleOrNull()
                val lon = Regex("""lon="([-0-9.]+)"""").find(a)?.groupValues?.get(1)?.toDoubleOrNull()
                if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) pts.add(GeoPoint(lat, lon))
            }
        } catch (_: Exception) {}
        return pts
    }

    override fun onPause() { super.onPause(); try { mp?.pause() } catch (_: Exception) {}; btnPlay.text = "▶"; mapView.onPause() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { mp?.release() } catch (_: Exception) {}; mp = null
    }
}

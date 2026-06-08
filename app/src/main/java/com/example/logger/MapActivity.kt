package com.example.logger

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.overlay.Polyline
import java.io.File
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.views.overlay.TilesOverlay
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvDistance: TextView
    private lateinit var btnCompassFromMap: Button
    private lateinit var btnPrecache: Button
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var esriTileSource: OnlineTileSourceBase

    private var destLat = 0.0
    private var destLon = 0.0
    private var destName = "Destinație"
    private var lastLocation: Location? = null
    private var pickMode = false
    private var trackMode = false
    private var trackLine: Polyline? = null
    private val trackHandler = Handler(Looper.getMainLooper())
    private val trackRefresh = object : Runnable {
        override fun run() {
            if (trackMode) { drawTrack(); trackHandler.postDelayed(this, 4000) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName   // osmdroid cere user-agent
        setContentView(R.layout.activity_map)

        destLat = intent.getDoubleExtra("lat", 0.0)
        destLon = intent.getDoubleExtra("lon", 0.0)
        destName = intent.getStringExtra("name") ?: "Destinație"
        pickMode = intent.getBooleanExtra("pick_mode", false)
        trackMode = intent.getBooleanExtra("track_mode", false)

        mapView = findViewById(R.id.mapView)
        tvDistance = findViewById(R.id.tvDistance)
        btnCompassFromMap = findViewById(R.id.btnCompassFromMap)
        btnPrecache = findViewById(R.id.btnPrecache)

        setupMap()
        setupLocationOverlay()

        if (pickMode) {
            findViewById<View>(R.id.pickCrosshair).visibility = View.VISIBLE
            findViewById<View>(R.id.pickHint).visibility = View.VISIBLE
            btnCompassFromMap.text = "✓ Confirmă"
            btnPrecache.text = "✎ Manual"
            btnCompassFromMap.setOnClickListener { confirmPick() }
            btnPrecache.setOnClickListener { returnManual() }
            mapView.addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean { updateCenterLabel(); return false }
                override fun onZoom(event: ZoomEvent?): Boolean { updateCenterLabel(); return false }
            })
            updateCenterLabel()
        } else if (trackMode) {
            btnCompassFromMap.text = "📍 Eu"
            btnPrecache.text = "🛰 Cache 1km"
            btnCompassFromMap.setOnClickListener {
                (myLocationOverlay.myLocation ?: lastLocation?.let { GeoPoint(it.latitude, it.longitude) })
                    ?.let { mapView.controller.animateTo(it) }
            }
            btnPrecache.setOnClickListener { precache5km() }
            drawTrack()
            trackHandler.postDelayed(trackRefresh, 4000)
        } else {
            addDestinationMarker()
            loadAllPois()
            btnCompassFromMap.setOnClickListener {
                startActivity(Intent(this, CompassActivity::class.java).apply {
                    putExtra("lat", destLat); putExtra("lon", destLon); putExtra("name", destName)
                })
            }
            btnPrecache.setOnClickListener { precacheTiles() }
        }
    }

    /** Citeste GPX-ul sesiunii curente si deseneaza traseul (linie). Reapelat la refresh. */
    private fun drawTrack() {
      try {
        val path = RecordingService.currentGpxPath ?: run {
            tvDistance.text = "🚶 Traseu — fara sesiune activa"; return
        }
        val pts = parseGpx(path)
        trackLine?.let { mapView.overlays.remove(it) }
        if (pts.isNotEmpty()) {
            val line = Polyline(mapView).apply {
                setPoints(pts)
                outlinePaint.color = Color.parseColor("#FF1744")
                outlinePaint.strokeWidth = 8f
            }
            mapView.overlays.add(line)
            trackLine = line
            var len = 0.0
            for (i in 1 until pts.size) {
                val r = FloatArray(1)
                Location.distanceBetween(pts[i-1].latitude, pts[i-1].longitude,
                    pts[i].latitude, pts[i].longitude, r)
                len += r[0]
            }
            val lenStr = if (len >= 1000) "%.2f km".format(len / 1000) else "%.0f m".format(len)
            tvDistance.text = "🚶 Traseu: $lenStr · ${pts.size} pct"
        }
        mapView.invalidate()
      } catch (e: Exception) { /* nu lasa harta sa crape din desenarea traseului */ }
    }

    private fun parseGpx(path: String): List<GeoPoint> {
        val pts = mutableListOf<GeoPoint>()
        try {
            val txt = File(path).readText()
            for (m in Regex("""<trkpt\b([^>]*)>""").findAll(txt)) {
                val a = m.groupValues[1]
                val lat = Regex("""lat="([-0-9.]+)"""").find(a)?.groupValues?.get(1)?.toDoubleOrNull()
                val lon = Regex("""lon="([-0-9.]+)"""").find(a)?.groupValues?.get(1)?.toDoubleOrNull()
                if (lat != null && lon != null) pts.add(GeoPoint(lat, lon))
            }
        } catch (e: Exception) {}
        return pts
    }

    @Suppress("DEPRECATION")
    private fun precache5km() {
      try {
        val loc = myLocationOverlay.myLocation
            ?: lastLocation?.let { GeoPoint(it.latitude, it.longitude) }
        if (loc == null) { Toast.makeText(this, "Aștept locația GPS...", Toast.LENGTH_SHORT).show(); return }
        val delta = 0.005   // ~0.55 km -> casuta ~1x1 km (suficient, se incarca rapid)
        val box = BoundingBox(loc.latitude + delta, loc.longitude + delta,
            loc.latitude - delta, loc.longitude - delta)
        val cm = CacheManager(mapView)
        val total = cm.possibleTilesInArea(box, 13, 16)
        Toast.makeText(this, "Cache satelit 1×1km ($total tiles)...", Toast.LENGTH_SHORT).show()
        cm.downloadAreaAsync(this, box, 13, 16, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                runOnUiThread { Toast.makeText(this@MapActivity, "✓ Cache 1km salvat offline", Toast.LENGTH_SHORT).show() }
            }
            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
            override fun downloadStarted() {}
            override fun setPossibleTilesInArea(total: Int) {}
            override fun onTaskFailed(errors: Int) {
                runOnUiThread { Toast.makeText(this@MapActivity, "⚠ $errors erori cache", Toast.LENGTH_SHORT).show() }
            }
        })
      } catch (e: Exception) {
        Toast.makeText(this, "Cache satelit indisponibil acum", Toast.LENGTH_SHORT).show()
      }
    }

    /** Mod pick: confirma centrul hartii (crucea) ca punct ales. */
    private fun confirmPick() {
        val c = mapView.mapCenter
        setResult(RESULT_OK, Intent().putExtra("lat", c.latitude).putExtra("lon", c.longitude))
        finish()
    }

    /** Mod pick: cere introducere manuala (cand harta nu e utila — fara net + fara cache). */
    private fun returnManual() {
        setResult(RESULT_OK, Intent().putExtra("manual", true))
        finish()
    }

    private fun updateCenterLabel() {
        val c = mapView.mapCenter
        tvDistance.text = "📍 %.5f, %.5f".format(c.latitude, c.longitude)
    }

    private fun setupMap() {
        esriTileSource = object : OnlineTileSourceBase(
            "EsriWorldImagery", 0, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
            "Esri World Imagery",
            TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_PREVENTIVE or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED or TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL)
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        }

        mapView.setTileSource(esriTileSource)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(16.0)
        mapView.controller.setCenter(GeoPoint(destLat, destLon))

        // strat de etichete (denumiri locuri/drumuri) peste satelit — pentru orientare
        val labels = object : OnlineTileSourceBase(
            "EsriRefLabels", 0, 19, 256, ".png",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/"),
            "Esri Reference",
            TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_PREVENTIVE or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED or TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL)
        ) {
            override fun getTileURLString(i: Long): String {
                val z = MapTileIndex.getZoom(i); val x = MapTileIndex.getX(i); val y = MapTileIndex.getY(i)
                return "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/$z/$y/$x"
            }
        }
        val labelsOverlay = TilesOverlay(MapTileProviderBasic(applicationContext, labels), applicationContext).apply {
            loadingBackgroundColor = Color.TRANSPARENT
            loadingLineColor = Color.TRANSPARENT
        }
        mapView.overlays.add(labelsOverlay)
    }

    @Suppress("DEPRECATION")
    private fun precacheTiles() {
        // ~2km radius around destination, zoom 13–18
        val delta = 0.018  // ~2km in degrees lat/lon at 45°N
        val box = BoundingBox(destLat + delta, destLon + delta, destLat - delta, destLon - delta)
        val cacheManager = CacheManager(mapView)
        val total = cacheManager.possibleTilesInArea(box, 13, 18)

        val dialog = ProgressDialog(this).apply {
            setTitle("Pre-încărcare tiles satelit")
            setMessage("0 / $total tiles...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = total
            setCancelable(false)
            show()
        }

        btnPrecache.isEnabled = false

        cacheManager.downloadAreaAsync(this, box, 13, 18, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                runOnUiThread {
                    dialog.dismiss()
                    btnPrecache.isEnabled = true
                    Toast.makeText(this@MapActivity, "✓ $total tiles salvate offline", Toast.LENGTH_LONG).show()
                }
            }

            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                runOnUiThread {
                    dialog.progress = progress
                    dialog.setMessage("Zoom $currentZoomLevel/$zoomMax · $progress / $total tiles")
                }
            }

            override fun downloadStarted() {}
            override fun setPossibleTilesInArea(total: Int) {}

            override fun onTaskFailed(errors: Int) {
                runOnUiThread {
                    dialog.dismiss()
                    btnPrecache.isEnabled = true
                    Toast.makeText(this@MapActivity, "⚠ $errors erori la descărcare", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun setupLocationOverlay() {
        val locationProvider = GpsMyLocationProvider(this)
        myLocationOverlay = MyLocationNewOverlay(locationProvider, mapView)
        myLocationOverlay.enableMyLocation()
        myLocationOverlay.enableFollowLocation()
        myLocationOverlay.runOnFirstFix {
            runOnUiThread {
                myLocationOverlay.disableFollowLocation()
                val ctr = if (pickMode || trackMode) (myLocationOverlay.myLocation ?: GeoPoint(destLat, destLon))
                          else GeoPoint(destLat, destLon)
                mapView.controller.setCenter(ctr)
                if (pickMode) updateCenterLabel()
            }
        }

        // Listen for location updates to show distance
        locationProvider.startLocationProvider(object : IMyLocationConsumer {
            override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
                location ?: return
                lastLocation = location
                val dest = Location("dest").apply {
                    latitude = destLat
                    longitude = destLon
                }
                val distM = location.distanceTo(dest)
                val distStr = if (distM >= 1000)
                    "%.2f km".format(distM / 1000f)
                else
                    "%.0f m".format(distM)
                runOnUiThread {
                    if (!pickMode && !trackMode) tvDistance.text = "🎯 $destName — $distStr"
                }
            }
        })

        mapView.overlays.add(myLocationOverlay)
    }

    private fun addDestinationMarker() {
        val marker = Marker(mapView).apply {
            position = GeoPoint(destLat, destLon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = destName
            snippet = "%.6f, %.6f".format(destLat, destLon)
        }
        mapView.overlays.add(marker)
        marker.showInfoWindow()   // numele destinației vizibil permanent
        mapView.invalidate()
    }

    /** Încarcă toate POI-urile userului și le afișează ca etichete cu nume (orientare pe hartă). */
    private fun loadAllPois() {
        val token = getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("jwt_token", null) ?: return
        Thread {
            try {
                val conn = (URL("${UploadManager.SERVER}/api/pois/mobile").openConnection() as HttpURLConnection)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                if (conn.responseCode != 200) return@Thread
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                val markers = ArrayList<Marker>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val lat = o.getDouble("lat"); val lon = o.getDouble("lon")
                    if (Math.abs(lat - destLat) < 1e-6 && Math.abs(lon - destLon) < 1e-6) continue  // sare peste destinație
                    val nm = o.optString("name", "POI")
                    markers.add(Marker(mapView).apply {
                        position = GeoPoint(lat, lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = nm
                        snippet = "%.6f, %.6f".format(lat, lon)
                        setTextIcon(nm)   // etichetă cu numele, vizibilă
                    })
                }
                runOnUiThread {
                    markers.forEach { mapView.overlays.add(it) }
                    mapView.invalidate()
                }
            } catch (_: Exception) {}
        }.start()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        myLocationOverlay.enableMyLocation()
        if (trackMode) {
            trackHandler.removeCallbacks(trackRefresh)
            trackHandler.post(trackRefresh)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        myLocationOverlay.disableMyLocation()
        trackHandler.removeCallbacks(trackRefresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        trackHandler.removeCallbacks(trackRefresh)
        mapView.onDetach()
    }
}

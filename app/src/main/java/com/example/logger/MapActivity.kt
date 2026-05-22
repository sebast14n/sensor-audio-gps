package com.example.logger

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        setContentView(R.layout.activity_map)

        destLat = intent.getDoubleExtra("lat", 0.0)
        destLon = intent.getDoubleExtra("lon", 0.0)
        destName = intent.getStringExtra("name") ?: "Destinație"

        mapView = findViewById(R.id.mapView)
        tvDistance = findViewById(R.id.tvDistance)
        btnCompassFromMap = findViewById(R.id.btnCompassFromMap)
        btnPrecache = findViewById(R.id.btnPrecache)

        setupMap()
        setupLocationOverlay()
        addDestinationMarker()

        btnCompassFromMap.setOnClickListener {
            startActivity(Intent(this, CompassActivity::class.java).apply {
                putExtra("lat", destLat)
                putExtra("lon", destLon)
                putExtra("name", destName)
            })
        }

        btnPrecache.setOnClickListener { precacheTiles() }
    }

    private fun setupMap() {
        esriTileSource = object : OnlineTileSourceBase(
            "EsriWorldImagery", 0, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
            "Esri World Imagery",
            TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED or TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL)
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
                mapView.controller.setCenter(GeoPoint(destLat, destLon))
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
                    tvDistance.text = "🎯 $destName — $distStr"
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
        mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        myLocationOverlay.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        myLocationOverlay.disableMyLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
    }
}

package com.example.logger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Lista POI-urilor utilizatorului (sync din /api/pois/mobile).
 * Click pe un POI -> CompassActivity. Buton "Adaugă manual" pt destinație ad-hoc.
 */
class PoisListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvStatus: TextView
    private lateinit var btnAddManual: Button
    private lateinit var btnRefresh: Button

    private var currentLocation: Location? = null
    private var pois: MutableList<JSONObject> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programmatic layout (no XML — keep simple)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(0xFF121212.toInt())
        }
        val title = TextView(this).apply {
            text = "📍 Puncte de interes"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 12)
        }
        tvStatus = TextView(this).apply {
            text = "Se încarcă..."
            setTextColor(0xFF90CAF9.toInt())
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(0xFF1E1E1E.toInt())
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        btnRefresh = Button(this).apply {
            text = "↻ Refresh"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener { loadPois() }
        }
        btnAddManual = Button(this).apply {
            text = "➕ Adaugă punct"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openMapPicker() }
        }
        btnRow.addView(btnRefresh)
        btnRow.addView(btnAddManual)

        val btnFind = Button(this).apply {
            text = "🔍 Găsește senzorul prin BLE"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
            setOnClickListener { startActivity(Intent(this@PoisListActivity, FindSensorActivity::class.java)) }
        }

        root.addView(title)
        root.addView(tvStatus)
        root.addView(listView)
        root.addView(btnRow)
        root.addView(btnFind)
        setContentView(root)

        startLocationUpdates()
        loadPois()
    }

    override fun onResume() {
        super.onResume()
        syncPendingPois()    // incearca sa trimita punctele salvate offline cand revii (poate ai net acum)
    }

    // ── Coada OFFLINE de POI-uri: daca nu ai net la adaugare, se salveaza local si se ──
    // ── sincronizeaza automat cand telefonul are din nou acces la server. ──
    private fun pendingPois(): JSONArray = try {
        JSONArray(getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("pending_pois", "[]"))
    } catch (_: Exception) { JSONArray() }

    private fun setPending(arr: JSONArray) {
        getSharedPreferences("bioecho_prefs", MODE_PRIVATE).edit()
            .putString("pending_pois", arr.toString()).apply()
    }

    private fun enqueuePoi(name: String, lat: Double, lon: Double) {
        val arr = pendingPois()
        arr.put(JSONObject().apply { put("name", name); put("lat", lat); put("lon", lon) })
        setPending(arr)
    }

    private fun postPoi(p: JSONObject, jwt: String): Boolean {
        val conn = (URL(BuildConfig.SERVER_URL + "/api/pois").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10000; readTimeout = 15000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $jwt")
        }
        conn.outputStream.use {
            it.write(JSONObject().apply {
                put("name", p.getString("name")); put("lat", p.getDouble("lat")); put("lon", p.getDouble("lon"))
            }.toString().toByteArray())
        }
        val ok = conn.responseCode in 200..299
        try { conn.inputStream.use { it.readBytes() } } catch (_: Exception) {}
        conn.disconnect()
        return ok
    }

    private fun syncPendingPois() {
        val arr = pendingPois()
        if (arr.length() == 0) return
        val jwt = getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("jwt_token", null)
        if (jwt.isNullOrBlank()) return
        Thread {
            val remain = JSONArray(); var sent = 0
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val ok = try { postPoi(p, jwt) } catch (_: Exception) { false }
                if (ok) sent++ else remain.put(p)
            }
            setPending(remain)
            if (sent > 0) runOnUiThread {
                Toast.makeText(this, "✓ $sent punct(e) sincronizate", Toast.LENGTH_SHORT).show()
                loadPois()
            }
        }.start()
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Last known location pentru distanță inițială
        try {
            currentLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { /* ignore */ }

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f,
                object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        currentLocation = loc
                        renderList()
                    }
                }, Looper.getMainLooper())
        } catch (e: SecurityException) { /* ignore */ }
    }

    private fun loadPois() {
        tvStatus.text = "Se sincronizează..."
        val prefs = getSharedPreferences("bioecho_prefs", MODE_PRIVATE)
        val jwt = prefs.getString("jwt_token", null)
        if (jwt.isNullOrBlank()) {
            tvStatus.text = "⚠ Nu ești autentificat. Scanează QR pe pagina principală."
            return
        }
        Thread {
            try {
                val url = URL(BuildConfig.SERVER_URL + "/api/pois/mobile")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $jwt")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val arr = JSONArray(body)
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                    runOnUiThread {
                        pois = list
                        val pend = pendingPois().length()
                        tvStatus.text = "${list.size} puncte sincronizate" +
                            (if (pend > 0) "  ·  ⏳ $pend în așteptare" else "")
                        renderList()
                        precacheSatellite(list)   // pre-incarca harta satelit offline (auto, pe WiFi)
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "⚠ Eroare server: HTTP $code"
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "⚠ Eroare rețea: ${e.message}" }
            }
        }.start()
    }

    /** Pre-incarca tiles satelit (Esri) in jurul POI-urilor ca harta sa mearga OFFLINE in teren.
     *  Automat, DOAR pe WiFi (sa nu consume date mobile fara stire). Cache comun cu MapActivity. */
    private fun precacheSatellite(list: List<JSONObject>) {
        if (list.isEmpty() || !isWifi()) return
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        var any = false
        for (p in list) {
            val lat = p.optDouble("lat", Double.NaN); val lon = p.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            any = true
            minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
            minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon)
        }
        if (!any) return
        val buf = 0.012   // ~1.3 km tampon in jurul POI-urilor
        val box = BoundingBox(maxLat + buf, maxLon + buf, minLat - buf, minLon - buf)
        val cm = CacheManager(SatelliteTiles.esri(), SqlTileWriter(), 13, 16)
        val total = try { cm.possibleTilesInArea(box, 13, 16) } catch (e: Exception) { 0 }
        if (total <= 0) return
        if (total > 4000) {
            Toast.makeText(this, "Zona POI prea mare pt cache satelit ($total tiles) — sărit", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Pre-încarc harta satelit offline ($total tiles)...", Toast.LENGTH_SHORT).show()
        cm.downloadAreaAsync(this, box, 13, 16, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                runOnUiThread { Toast.makeText(this@PoisListActivity, "✓ Hartă satelit salvată offline", Toast.LENGTH_SHORT).show() }
            }
            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
            override fun downloadStarted() {}
            override fun setPossibleTilesInArea(t: Int) {}
            override fun onTaskFailed(errors: Int) {
                runOnUiThread { Toast.makeText(this@PoisListActivity, "⚠ $errors erori la cache satelit", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun isWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun renderList() {
        val items = pois.map { p ->
            val lat = p.getDouble("lat")
            val lon = p.getDouble("lon")
            val name = p.getString("name")
            val tags = p.optJSONArray("tags")
            val tagStr = (0 until (tags?.length() ?: 0)).joinToString(", ") { tags!!.getString(it) }

            val distStr = currentLocation?.let { loc ->
                val results = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, results)
                val m = results[0]
                if (m < 1000) "${m.roundToInt()} m"
                else "%.1f km".format(m / 1000)
            } ?: "—"

            "📍  $name\n     $distStr · ${lat.format(5)}, ${lon.format(5)}" +
                (if (tagStr.isNotEmpty()) "\n     $tagStr" else "")
        }
        listView.adapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as TextView).setTextColor(0xFFE0E0E0.toInt())
                v.textSize = 13f
                return v
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val p = pois[position]
            startActivity(Intent(this, CompassActivity::class.java).apply {
                putExtra("lat", p.getDouble("lat"))
                putExtra("lon", p.getDouble("lon"))
                putExtra("name", p.getString("name"))
            })
        }
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)

    /** Adaugare punct: metoda PRINCIPALA = selectie pe harta (satelit, offline din cache). */
    private fun openMapPicker() {
        startActivityForResult(
            Intent(this, MapActivity::class.java).putExtra("pick_mode", true), 400)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 400 && resultCode == RESULT_OK && data != null) {
            if (data.getBooleanExtra("manual", false)) {
                showManualDialog()                       // rezerva: fara harta -> coordonate manuale
            } else {
                val lat = data.getDoubleExtra("lat", Double.NaN)
                val lon = data.getDoubleExtra("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) askNameAndNavigate(lat, lon)
            }
        }
    }

    private fun askNameAndNavigate(lat: Double, lon: Double) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val etName = EditText(this).apply { hint = "Nume (ex: Cabană, P3)" }
        layout.addView(etName)
        AlertDialog.Builder(this)
            .setTitle("Punct selectat pe hartă")
            .setMessage("📍 %.5f, %.5f".format(lat, lon))
            .setView(layout)
            // Principal: salveaza in zona privata (sync cu harta web) + navigheaza
            .setPositiveButton("Salvează + navighează") { _, _ ->
                val name = etName.text.toString().ifBlank { "Punct" }
                savePoi(name, lat, lon)
                startActivity(Intent(this, CompassActivity::class.java).apply {
                    putExtra("lat", lat); putExtra("lon", lon); putExtra("name", name)
                })
            }
            // Doar pentru navigare ad-hoc, fara a salva
            .setNeutralButton("Doar navighează") { _, _ ->
                startActivity(Intent(this, CompassActivity::class.java).apply {
                    putExtra("lat", lat); putExtra("lon", lon)
                    putExtra("name", etName.text.toString().ifBlank { "Destinație" })
                })
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    /** Salveaza POI in zona privata. Daca ai net -> POST direct. Daca NU (offline / eroare) ->
     *  se salveaza in coada locala si se sincronizeaza automat cand telefonul are din nou net. */
    private fun savePoi(name: String, lat: Double, lon: Double) {
        val jwt = getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("jwt_token", null)
        if (jwt.isNullOrBlank()) {
            enqueuePoi(name, lat, lon)
            Toast.makeText(this, "💾 Salvat local: $name (neautentificat — se trimite după login)", Toast.LENGTH_LONG).show()
            tvStatus.text = "⏳ ${pendingPois().length()} punct(e) în așteptare (offline)"
            return
        }
        Thread {
            val ok = try {
                postPoi(JSONObject().apply { put("name", name); put("lat", lat); put("lon", lon) }, jwt)
            } catch (_: Exception) { false }
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "✓ Punct salvat: $name", Toast.LENGTH_SHORT).show()
                    loadPois()
                } else {
                    enqueuePoi(name, lat, lon)
                    Toast.makeText(this, "📡 Fără net — salvat local: $name. Se sincronizează automat când ai internet.", Toast.LENGTH_LONG).show()
                    tvStatus.text = "⏳ ${pendingPois().length()} punct(e) în așteptare (offline)"
                }
            }
        }.start()
    }

    private fun showManualDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etName = EditText(this).apply { hint = "Nume (ex: Cabană, P3)" }
        val etLat = EditText(this).apply {
            hint = "Latitudine (ex: 45.831)"
            inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val etLon = EditText(this).apply {
            hint = "Longitudine (ex: 24.121)"
            inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        layout.addView(etName); layout.addView(etLat); layout.addView(etLon)

        AlertDialog.Builder(this)
            .setTitle("🧭 Destinație ad-hoc (nu se salvează)")
            .setView(layout)
            .setPositiveButton("Navighează") { _, _ ->
                val lat = etLat.text.toString().toDoubleOrNull()
                val lon = etLon.text.toString().toDoubleOrNull()
                if (lat != null && lon != null) {
                    startActivity(Intent(this, CompassActivity::class.java).apply {
                        putExtra("lat", lat); putExtra("lon", lon)
                        putExtra("name", etName.text.toString().ifBlank { "Destinație" })
                    })
                } else {
                    Toast.makeText(this, "Coordonate invalide", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }
}

package com.example.logger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
            text = "+ Manual"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showManualDialog() }
        }
        btnRow.addView(btnRefresh)
        btnRow.addView(btnAddManual)

        root.addView(title)
        root.addView(tvStatus)
        root.addView(listView)
        root.addView(btnRow)
        setContentView(root)

        startLocationUpdates()
        loadPois()
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
                val url = URL("https://echo.noze.ro/api/pois/mobile")
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
                        tvStatus.text = "${list.size} puncte sincronizate"
                        renderList()
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

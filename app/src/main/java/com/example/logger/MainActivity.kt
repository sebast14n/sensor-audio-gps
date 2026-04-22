package com.example.logger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPath: TextView
    private val PERMISSIONS_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartStop = findViewById(R.id.btnStartStop)
        tvStatus     = findViewById(R.id.tvStatus)
        tvPath       = findViewById(R.id.tvPath)

        val saveDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        tvPath.text = "Fișierele se salvează în:\n$saveDir"

        updateUI(RecordingService.isRunning)

        btnStartStop.setOnClickListener {
            if (RecordingService.isRunning) stopRecording()
            else checkPermissionsAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI(RecordingService.isRunning)
    }

    private fun updateUI(recording: Boolean) {
        if (recording) {
            btnStartStop.text = "⏹  STOP"
            btnStartStop.setBackgroundColor(0xFFE53935.toInt())
            tvStatus.text = "🔴  Înregistrare activă"
        } else {
            btnStartStop.text = "▶  START"
            btnStartStop.setBackgroundColor(0xFF43A047.toInt())
            tvStatus.text = "⚪  Oprit"
        }
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) checkGpsAndStart()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == PERMISSIONS_REQUEST) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) checkGpsAndStart()
            else tvStatus.text = "⚠️  Permisiuni refuzate — verifică Setări"
        }
    }

    private fun isGpsEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun checkGpsAndStart() {
        if (isGpsEnabled()) {
            startRecording(null, null)
            return
        }

        AlertDialog.Builder(this)
            .setTitle("GPS dezactivat")
            .setMessage("Nu am acces la locație. Ce vrei să faci?")
            .setPositiveButton("Activează GPS") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNeutralButton("Coordonate manuale") { _, _ ->
                showManualLocationDialog()
            }
            .setNegativeButton("Continuă fără GPS") { _, _ ->
                startRecording(null, null)
            }
            .show()
    }

    private fun showManualLocationDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
        }
        val etLat = EditText(this).apply { hint = "Latitudine  (ex: 44.4268)" }
        val etLon = EditText(this).apply { hint = "Longitudine (ex: 26.1025)" }
        layout.addView(etLat)
        layout.addView(etLon)

        AlertDialog.Builder(this)
            .setTitle("Locație statică")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val lat = etLat.text.toString().toDoubleOrNull()
                val lon = etLon.text.toString().toDoubleOrNull()
                if (lat != null && lon != null) startRecording(lat, lon)
                else {
                    Toast.makeText(this, "Coordonate invalide", Toast.LENGTH_SHORT).show()
                    startRecording(null, null)
                }
            }
            .setNegativeButton("Continuă fără GPS") { _, _ ->
                startRecording(null, null)
            }
            .show()
    }

    private fun startRecording(staticLat: Double?, staticLon: Double?) {
        val i = Intent(this, RecordingService::class.java).apply {
            if (staticLat != null && staticLon != null) {
                putExtra("static_lat", staticLat)
                putExtra("static_lon", staticLon)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        updateUI(true)
    }

    private fun stopRecording() {
        stopService(Intent(this, RecordingService::class.java))
        updateUI(false)
    }
}

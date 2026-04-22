package com.example.logger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
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
        tvStatus    = findViewById(R.id.tvStatus)
        tvPath      = findViewById(R.id.tvPath)

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

        if (missing.isEmpty()) startRecording()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == PERMISSIONS_REQUEST) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) startRecording()
            else { tvStatus.text = "⚠️  Permisiuni refuzate — verifică Setări" }
        }
    }

    private fun startRecording() {
        val i = Intent(this, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        updateUI(true)
    }

    private fun stopRecording() {
        stopService(Intent(this, RecordingService::class.java))
        updateUI(false)
    }
}

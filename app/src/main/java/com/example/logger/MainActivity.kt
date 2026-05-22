package com.example.logger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var btnUpload: Button
    private lateinit var btnSetToken: Button
    private lateinit var btnQrAuth: Button
    private lateinit var btnCompass: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvUploadStatus: TextView
    private lateinit var tvPath: TextView

    private lateinit var uploadManager: UploadManager
    private val PERMISSIONS_REQUEST = 100
    private var isFixedPoint = true  // default: senzor fix (GPS oprit dupa primul fix)

    private val PREFS = "bioecho_prefs"
    private val KEY_MODE_SET = "session_mode_set"
    private val KEY_MODE_FIXED = "session_mode_fixed"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartStop  = findViewById(R.id.btnStartStop)
        btnUpload     = findViewById(R.id.btnUpload)
        btnSetToken   = findViewById(R.id.btnSetToken)
        btnQrAuth     = findViewById(R.id.btnQrAuth)
        btnCompass    = findViewById(R.id.btnCompass)
        tvStatus      = findViewById(R.id.tvStatus)
        tvUploadStatus = findViewById(R.id.tvUploadStatus)
        tvPath        = findViewById(R.id.tvPath)

        uploadManager = UploadManager(this)

        val saveDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        tvPath.text = "Fișierele se salvează în:\n$saveDir"

        // Load saved mode preference
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val modeSet = prefs.getBoolean(KEY_MODE_SET, false)
        if (modeSet) {
            isFixedPoint = prefs.getBoolean(KEY_MODE_FIXED, true)
        }

        updateUI(RecordingService.isRunning)

        btnStartStop.setOnClickListener {
            if (RecordingService.isRunning) stopRecording()
            else {
                val p = getSharedPreferences(PREFS, MODE_PRIVATE)
                if (p.getBoolean(KEY_MODE_SET, false)) {
                    isFixedPoint = p.getBoolean(KEY_MODE_FIXED, true)
                    if (isFixedPoint) suggestAirplaneMode() else checkPermissionsAndStart()
                } else {
                    showSessionTypeDialog()
                }
            }
        }
        btnStartStop.setOnLongClickListener {
            resetModeChoice()
            true
        }

        btnUpload.setOnClickListener { showSessionPicker() }
        btnSetToken.setOnClickListener { showTokenDialog() }
        btnQrAuth.setOnClickListener { startActivityForResult(Intent(this, QrScanActivity::class.java), 300) }
        btnCompass.setOnClickListener {
            // Deschide lista de POI-uri (sincronizate de pe BioEcho web).
            // Permite si destinatie manuala ad-hoc din lista.
            startActivity(Intent(this, PoisListActivity::class.java))
        }

        // Solicita exceptare de la battery optimization la prima rulare
        requestBatteryOptimizationExemption()

        // Verifica daca exista o versiune mai noua (silentios la esec)
        UpdateChecker.checkAsync(this)
    }

    override fun onResume() {
        super.onResume()
        updateUI(RecordingService.isRunning)
        verifyMobileAuth()
    }

    /**
     * Verifica JWT-ul stocat in SharedPreferences contra serverului.
     * Daca e valid: afiseaza email-ul autentificat in UI.
     * Daca e expirat / invalid / lipseste: prompt sa scaneze QR.
     */
    private fun verifyMobileAuth() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val token = prefs.getString("jwt_token", null)
        if (token.isNullOrBlank()) {
            tvUploadStatus.text = "🔒 Nu ești autentificat. Apasă SCANEAZĂ QR."
            return
        }
        Thread {
            try {
                val url = java.net.URL("${UploadManager.SERVER}/api/auth/mobile-me")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(body)
                    val email = json.optString("email", "?")
                    runOnUiThread { tvUploadStatus.text = "✓ Autentificat: $email" }
                } else if (code == 401) {
                    // Token invalid/expirat — sterge si cere QR nou
                    prefs.edit().remove("jwt_token").remove("user_email").apply()
                    runOnUiThread {
                        tvUploadStatus.text = "🔒 Token expirat. Apasă SCANEAZĂ QR pentru reconectare."
                    }
                } else {
                    runOnUiThread { tvUploadStatus.text = "⚠ Verificare auth: HTTP $code" }
                }
            } catch (e: Exception) {
                runOnUiThread { tvUploadStatus.text = "⚠ Offline (auth necheck): ${e.message}" }
            }
        }.start()
    }

    private fun updateUI(recording: Boolean) {
        if (recording) {
            btnStartStop.text = "⏹  STOP"
            btnStartStop.setBackgroundColor(0xFFE53935.toInt())
            tvStatus.text = "🔴  Înregistrare activă"
            btnUpload.isEnabled = false
        } else {
            btnStartStop.text = "▶  START"
            btnStartStop.setBackgroundColor(0xFF43A047.toInt())
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MODE_SET, false)) {
                val fixed = prefs.getBoolean(KEY_MODE_FIXED, true)
                tvStatus.text = if (fixed) "📍 Mod: Senzor fix" else "🚶 Mod: Transect"
                btnCompass.visibility = if (fixed) android.view.View.GONE else android.view.View.VISIBLE
            } else {
                tvStatus.text = "⚪  Oprit"
                btnCompass.visibility = android.view.View.VISIBLE
            }
            btnUpload.isEnabled = true
        }
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    private fun showSessionPicker() {
        val sessions = uploadManager.getLocalSessions()
        if (sessions.isEmpty()) {
            tvUploadStatus.text = "Nicio sesiune locală găsită."
            return
        }

        val names = sessions.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Selectează sesiunea de uplodat")
            .setItems(names) { _, idx -> doUpload(sessions[idx]) }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun doUpload(sessionDir: File) {
        if (!isWifiConnected()) {
            tvUploadStatus.text = "⚠ Upload disponibil doar pe WiFi"
            return
        }
        if (uploadManager.jwtToken.isNullOrBlank()) {
            showTokenDialog { doUpload(sessionDir) }
            return
        }

        btnUpload.isEnabled = false
        tvUploadStatus.text = "Se pregătește upload-ul..."

        uploadManager.uploadSessionAsync(sessionDir, object : UploadManager.ProgressCallback {
            override fun onProgress(current: Int, total: Int, fileName: String) {
                runOnUiThread {
                    tvUploadStatus.text = "Se încarcă $current/$total: $fileName"
                }
            }

            override fun onDone(result: UploadManager.UploadResult) {
                runOnUiThread {
                    btnUpload.isEnabled = !RecordingService.isRunning
                    val msg = buildString {
                        append("✓ ${result.success} încărcate")
                        if (result.skipped > 0) append(", ${result.skipped} deja existente")
                        if (result.failed.isNotEmpty()) {
                            append("\n⚠ ${result.failed.size} erori: ")
                            append(result.failed.joinToString(", ") { it.substringAfterLast('/') })
                        }
                    }
                    tvUploadStatus.text = msg
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    btnUpload.isEnabled = !RecordingService.isRunning
                    tvUploadStatus.text = "⚠ $message"
                }
            }
        })
    }

    private fun showTokenDialog(onSuccess: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Lipește token-ul JWT"
            setPadding(48, 32, 48, 0)
            maxLines = 3
        }
        val current = uploadManager.jwtToken
        if (!current.isNullOrBlank()) input.setText(current)

        AlertDialog.Builder(this)
            .setTitle("Token autentificare")
            .setMessage("Copiază token-ul din browser:\nSettings → Profil → Copiaza token\nsau din cookie-ul bioecho_token")
            .setView(input)
            .setPositiveButton("Salvează") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotBlank()) {
                    uploadManager.jwtToken = t
                    tvUploadStatus.text = "Token salvat ✓"
                    onSuccess?.invoke()
                } else {
                    tvUploadStatus.text = "Token invalid"
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Battery optimization — esential pentru functionare in background pe Android 12+
    // -------------------------------------------------------------------------

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle("Optimizare baterie")
            .setMessage(
                "Pentru ca înregistrarea să continue când ecranul este închis, " +
                "aplicația trebuie exceptată de la optimizarea bateriei.\n\n" +
                "Pe ecranul următor, selectează \"Nu optimiza\"."
            )
            .setPositiveButton("Configurează") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Mai târziu", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Înregistrare
    // -------------------------------------------------------------------------

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
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
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

    private fun showSessionTypeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tip sesiune")
            .setMessage(
                "📍 Senzor fix — GPS oprit după primul fix, baterie maximă\n\n" +
                "🚶 Transect — GPS activ tot timpul, traseu înregistrat\n\n" +
                "Alegerea se va memora. Apasă lung pe START pentru a reseta."
            )
            .setPositiveButton("📍 Senzor fix") { _, _ ->
                isFixedPoint = true
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_MODE_SET, true)
                    .putBoolean(KEY_MODE_FIXED, true)
                    .apply()
                updateUI(false)
                suggestAirplaneMode()
            }
            .setNegativeButton("🚶 Transect") { _, _ ->
                isFixedPoint = false
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_MODE_SET, true)
                    .putBoolean(KEY_MODE_FIXED, false)
                    .apply()
                updateUI(false)
                checkPermissionsAndStart()
            }
            .show()
    }

    private fun resetModeChoice() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .remove(KEY_MODE_SET)
            .remove(KEY_MODE_FIXED)
            .apply()
        Toast.makeText(this, "Modul a fost resetat. La START vei alege din nou.", Toast.LENGTH_SHORT).show()
        updateUI(RecordingService.isRunning)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 300 && resultCode == RESULT_OK) {
            val email = getSharedPreferences("bioecho_prefs", MODE_PRIVATE)
                .getString("user_email", "") ?: ""
            tvUploadStatus.text = "✓ Autentificat: $email"
        }
    }

    private fun showCompassDestinationDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
        }
        val etName = EditText(this).apply { hint = "Nume punct (ex: P3 Cășolț)" }
        val etLat  = EditText(this).apply {
            hint = "Latitudine (ex: 45.8312)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val etLon  = EditText(this).apply {
            hint = "Longitudine (ex: 24.1205)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        layout.addView(etName)
        layout.addView(etLat)
        layout.addView(etLon)

        AlertDialog.Builder(this)
            .setTitle("🧭 Navigare la punct")
            .setView(layout)
            .setPositiveButton("Navighează") { _, _ ->
                val lat = etLat.text.toString().toDoubleOrNull()
                val lon = etLon.text.toString().toDoubleOrNull()
                if (lat != null && lon != null) {
                    startActivity(Intent(this, CompassActivity::class.java).apply {
                        putExtra("lat", lat)
                        putExtra("lon", lon)
                        putExtra("name", etName.text.toString().ifBlank { "Destinație" })
                    })
                } else {
                    Toast.makeText(this, "Coordonate invalide", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun suggestAirplaneMode() {
        AlertDialog.Builder(this)
            .setTitle("✈ Economisire baterie")
            .setMessage(
                "Dacă nu există semnal mobil în zonă, rețeaua consumă baterie inutil.\n\n" +
                "Recomandare: activează Mod avion înainte de a lăsa telefonul în teren. " +
                "Upload-ul se va face manual când recuperezi telefonul pe WiFi."
            )
            .setPositiveButton("Activează Mod avion") { _, _ ->
                startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            }
            .setNegativeButton("Continuă fără") { _, _ ->
                checkPermissionsAndStart()
            }
            .show()
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun startRecording(staticLat: Double?, staticLon: Double?) {
        val i = Intent(this, RecordingService::class.java).apply {
            if (staticLat != null && staticLon != null) {
                putExtra("static_lat", staticLat)
                putExtra("static_lon", staticLon)
            }
            putExtra("fixed_point", isFixedPoint)
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

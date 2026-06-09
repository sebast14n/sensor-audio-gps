package com.example.logger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var btnTrackMap: Button
    private lateinit var btnTransect: Button
    private lateinit var btnFixedSensor: Button
    private lateinit var btnLiveListen: Button
    private lateinit var startButtonsRow: View
    private lateinit var btnAuth: Button
    private lateinit var btnCompass: Button
    private lateinit var btnRecordings: Button
    private lateinit var btnFindSensor: Button
    private lateinit var btnDiagnostic: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvUploadStatus: TextView
    private lateinit var tvPath: TextView

    private lateinit var uploadManager: UploadManager
    private val PERMISSIONS_REQUEST = 100
    private var isFixedPoint = true  // default: senzor fix (GPS oprit dupa primul fix)
    private var pendingFixed = true  // ce mod a initiat cererea de permisiuni
    private var mediaPlayer: android.media.MediaPlayer? = null

    private val PREFS = "bioecho_prefs"
    private val KEY_MODE_SET = "session_mode_set"
    private val KEY_MODE_FIXED = "session_mode_fixed"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        setContentView(R.layout.activity_main)

        btnStartStop     = findViewById(R.id.btnStartStop)
        btnTrackMap      = findViewById(R.id.btnTrackMap)
        btnTransect      = findViewById(R.id.btnTransect)
        btnFixedSensor   = findViewById(R.id.btnFixedSensor)
        btnLiveListen    = findViewById(R.id.btnLiveListen)
        startButtonsRow  = findViewById(R.id.startButtonsRow)
        btnAuth          = findViewById(R.id.btnAuth)
        btnCompass       = findViewById(R.id.btnCompass)
        btnRecordings    = findViewById(R.id.btnRecordings)
        btnFindSensor    = findViewById(R.id.btnFindSensor)
        btnDiagnostic    = findViewById(R.id.btnDiagnostic)
        tvStatus      = findViewById(R.id.tvStatus)
        tvUploadStatus = findViewById(R.id.tvUploadStatus)
        tvPath        = findViewById(R.id.tvPath)

        uploadManager = UploadManager(this)

        val saveDir = Storage.baseDir(this).absolutePath
        tvPath.text = "Fișierele se salvează în:\n$saveDir" +
            (if (Storage.canUsePublic()) "\n✓ păstrate și după dezinstalare" else "\n⚠ doar privat (acordă „Access all files”)") +
            "\nBioEcho v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        // Load saved mode preference
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val modeSet = prefs.getBoolean(KEY_MODE_SET, false)
        if (modeSet) {
            isFixedPoint = prefs.getBoolean(KEY_MODE_FIXED, true)
        }

        updateUI(RecordingService.isRunning)

        // STOP (vizibil doar in timpul inregistrarii)
        btnStartStop.setOnClickListener { if (RecordingService.isRunning) stopRecording() }
        btnTrackMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java).putExtra("track_mode", true))
        }

        // Cele doua moduri de pornire
        btnTransect.setOnClickListener { beginSession(fixed = false) }
        btnFixedSensor.setOnClickListener { beginSession(fixed = true) }

        // Identificare pasari live, pe telefon (tip Merlin) — ecran separat
        btnLiveListen.setOnClickListener { startActivity(Intent(this, LiveListenActivity::class.java)) }

        btnAuth.setOnClickListener { showAuthMenu() }
        btnCompass.setOnClickListener {
            // Deschide lista de POI-uri (sincronizate de pe BioEcho web).
            // Permite si destinatie manuala ad-hoc din lista.
            startActivity(Intent(this, PoisListActivity::class.java))
        }
        btnRecordings.setOnClickListener { startActivity(Intent(this, RecordingsActivity::class.java)) }
        btnFindSensor.setOnClickListener { startActivity(Intent(this, FindSensorActivity::class.java)) }
        btnDiagnostic.setOnClickListener { startActivity(Intent(this, DiagnosticActivity::class.java)) }

        // Solicita exceptare de la battery optimization la prima rulare
        requestBatteryOptimizationExemption()
        // Solicita "All files access" o singura data (inregistrari in folder public)
        requestStorageOnce()

        // Verifica update DOAR la pornire (cold start onCreate) si DOAR daca exista internet
        // (silentios la esec). Push real -> in viitor prin C&C-ul de control la distanta, v2.0.
        if (hasNetwork()) UpdateChecker.checkAsync(this, verbose = false)
    }

    /** Exista conexiune la internet acum? (pt verificarea update + flush alerte). */
    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onResume() {
        super.onResume()
        updateUI(RecordingService.isRunning)
        verifyMobileAuth()
        updateAuthButton()
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.release()   // opreste redarea cand pleci din ecran
        mediaPlayer = null
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

    // -------------------------------------------------------------------------
    // Autentificare consolidata (un singur buton -> meniu cu cele 3 variante)
    // -------------------------------------------------------------------------

    private fun isLoggedIn(): Boolean =
        !getSharedPreferences("bioecho_prefs", MODE_PRIVATE).getString("jwt_token", null).isNullOrBlank()

    private fun updateAuthButton() {
        btnAuth.text = if (isLoggedIn()) "👤 CONT" else "🔑 AUTENTIFICARE"
    }

    /** Scrie orice crash necontrolat intr-un fisier in /BioEcho/crash_log.txt (depanare pe teren). */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                File(Storage.baseDir(this), "crash_log.txt")
                    .appendText("\n\n=== ${java.util.Date()} thread=${t.name} ===\n$sw")
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun showAuthMenu() {
        val prefs = getSharedPreferences("bioecho_prefs", MODE_PRIVATE)
        if (isLoggedIn()) {
            val email = prefs.getString("user_email", "") ?: ""
            AlertDialog.Builder(this)
                .setTitle("Cont")
                .setMessage(if (email.isNotBlank()) "Autentificat ca:\n$email" else "Ești autentificat.")
                .setPositiveButton("🚪 Deconectare") { _, _ ->
                    prefs.edit().remove("jwt_token").remove("user_email").apply()
                    Toast.makeText(this, "Deconectat.", Toast.LENGTH_SHORT).show()
                    updateAuthButton(); verifyMobileAuth()
                }
                .setNegativeButton("Închide", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Autentificare")
                .setItems(arrayOf("🅖  Cu Google", "📷  Cu cod QR", "🔑  Cu token (avansat)",
                                  "📟  Provizionează ca senzor (scan QR)")) { _, which ->
                    when (which) {
                        0 -> doGoogleLogin()
                        1 -> startActivityForResult(Intent(this, QrScanActivity::class.java), 300)
                        2 -> showTokenDialog()
                        3 -> startActivity(Intent(this, QrScanActivity::class.java))  // QR senzor -> device_token
                    }
                }
                .setNegativeButton("Anulează", null)
                .show()
        }
    }

    private fun doGoogleLogin() {
        tvUploadStatus.text = "Se conectează la Google..."
        GoogleLogin.signIn(this,
            onSuccess = { email, name ->
                tvUploadStatus.text = "✓ Logat ca $email" + (if (name.isNotBlank()) " ($name)" else "")
                Toast.makeText(this, "Bine ai venit, ${name.ifBlank { email }}!", Toast.LENGTH_LONG).show()
                updateAuthButton()
            },
            onError = { msg ->
                tvUploadStatus.text = "⚠ Login Google eșuat: $msg"
                Toast.makeText(this, "Login Google: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun updateUI(recording: Boolean) {
        if (recording) {
            btnStartStop.visibility = View.VISIBLE
            btnStartStop.setBackgroundColor(0xFFE53935.toInt())
            startButtonsRow.visibility = View.GONE
            tvStatus.text = if (isFixedPoint) "🔴  Senzor fix — activ" else "🔴  Transect — activ"
            btnCompass.visibility = if (isFixedPoint) View.GONE else View.VISIBLE
            btnTrackMap.visibility = if (!isFixedPoint) View.VISIBLE else View.GONE  // doar la transect
        } else {
            btnStartStop.visibility = View.GONE
            startButtonsRow.visibility = View.VISIBLE
            tvStatus.text = "⚪  Alege modul de operare"
            btnCompass.visibility = View.VISIBLE
            btnTrackMap.visibility = View.GONE
        }
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    private fun showSessionPicker() {
        val sessions = uploadManager.getLocalSessions()
        if (sessions.isEmpty()) {
            Toast.makeText(this, "Nicio sesiune locală găsită — pornește o înregistrare mai întâi", Toast.LENGTH_LONG).show()
            tvUploadStatus.text = "Nicio sesiune locală."
            return
        }

        // Format: nume + data + nr fișiere + size MB
        val labels = sessions.map { dir ->
            val ts = dir.name.removePrefix("session_")
            val pretty = formatTimestamp(ts)
            val files = dir.listFiles()?.filter { it.name.endsWith(".m4a") || it.name.endsWith(".wav") || it.name.endsWith(".flac") } ?: emptyList()
            val totalMb = files.sumOf { it.length() } / 1024.0 / 1024.0
            "$pretty\n${files.size} fișiere · %.1f MB".format(totalMb)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Sesiuni locale (${sessions.size})")
            .setItems(labels) { _, idx -> doUpload(sessions[idx]) }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun formatTimestamp(ts: String): String {
        // session_2026-05-22_14-30-00 -> "22 mai 2026, 14:30"
        return try {
            val parts = ts.split("_")
            if (parts.size >= 2) {
                val date = parts[0]
                val time = parts[1].replace("-", ":")
                "$date  $time"
            } else ts
        } catch (e: Exception) { ts }
    }

    // -------------------------------------------------------------------------
    // Storage public + management inregistrari
    // -------------------------------------------------------------------------

    private fun requestStorageOnce() {
        if (Storage.canUsePublic()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean("asked_all_files", false)) return
        prefs.edit().putBoolean("asked_all_files", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Păstrarea înregistrărilor")
            .setMessage("Ca înregistrările să rămână pe telefon și după dezinstalarea aplicației, " +
                "se salvează în folderul public BioEcho.\n\n" +
                "Acordă permisiunea „Access all files” pe ecranul următor (o singură dată).")
            .setPositiveButton("Acordă") { _, _ -> Storage.requestAllFilesAccess(this) }
            .setNegativeButton("Mai târziu", null)
            .show()
    }

    private fun showRecordingsManager() {
        val sessions = Storage.sessions(this)
        if (sessions.isEmpty()) {
            val b = AlertDialog.Builder(this)
                .setTitle("🗂 Înregistrări")
                .setMessage("Nicio sesiune salvată încă." +
                    (if (!Storage.canUsePublic()) "\n\n⚠ Pentru păstrare după dezinstalare, acordă „Access all files”." else ""))
                .setPositiveButton("OK", null)
            if (!Storage.canUsePublic())
                b.setNeutralButton("Acordă acces") { _, _ -> Storage.requestAllFilesAccess(this) }
            b.show()
            return
        }
        val total = sessions.sumOf { Storage.dirSize(it) }
        val labels = sessions.map { dir ->
            val n = dir.listFiles()?.count { it.name.endsWith(".m4a") || it.name.endsWith(".wav") || it.name.endsWith(".flac") } ?: 0
            "${formatTimestamp(dir.name.removePrefix("session_"))}\n$n fișiere · ${Storage.humanSize(Storage.dirSize(dir))}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🗂 Înregistrări (${sessions.size}) — total ${Storage.humanSize(total)}")
            .setItems(labels) { _, idx -> showSessionActions(sessions[idx]) }
            .setNegativeButton("Închide", null)
            .show()
    }

    /** Actiuni per inregistrare: Harta / Asculta / Upload / Sterge. */
    private fun showSessionActions(dir: File) {
        val hasLoc = sessionLatLon(dir) != null
        val mapLabel = if (hasLoc) "🗺  Unde s-a înregistrat" else "🗺  Unde s-a înregistrat (fără GPS)"
        AlertDialog.Builder(this)
            .setTitle(formatTimestamp(dir.name.removePrefix("session_")))
            .setItems(arrayOf(mapLabel, "▶  Ascultă", "⬆  Upload", "🗑  Șterge")) { _, which ->
                when (which) {
                    0 -> showSessionOnMap(dir)
                    1 -> startActivity(Intent(this, PlaybackActivity::class.java)
                            .putExtra("session_path", dir.absolutePath))
                    2 -> doUpload(dir)
                    3 -> confirmDeleteSession(dir)
                }
            }
            .setNegativeButton("Înapoi") { _, _ -> showRecordingsManager() }
            .show()
    }

    /** Primul punct GPS (lat,lon) al sesiunii, citit din track_*.gpx. null daca nu exista. */
    private fun sessionLatLon(dir: File): Pair<Double, Double>? {
        return try {
            val gpx = dir.listFiles()?.firstOrNull { it.name.endsWith(".gpx") } ?: return null
            val m = Regex("<trkpt[^>]*lat=\"([-\\d.]+)\"[^>]*lon=\"([-\\d.]+)\"")
                .find(gpx.readText()) ?: return null
            val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
            if (lat == 0.0 && lon == 0.0) null else Pair(lat, lon)
        } catch (_: Exception) { null }
    }

    /** Deschide harta (satelit + cache offline) centrata pe locul inregistrarii. */
    private fun showSessionOnMap(dir: File) {
        val ll = sessionLatLon(dir)
        if (ll == null) {
            Toast.makeText(this, "Sesiunea nu are coordonate GPS salvate.", Toast.LENGTH_SHORT).show()
            showSessionActions(dir); return
        }
        startActivity(Intent(this, MapActivity::class.java).apply {
            putExtra("lat", ll.first); putExtra("lon", ll.second)
            putExtra("name", "📍 Înregistrare ${formatTimestamp(dir.name.removePrefix("session_"))}")
        })
    }

    /** Reda primul fisier audio din sesiune (verificare rapida ca s-a captat). */
    private fun playSession(dir: File) {
        val audio = dir.listFiles()
            ?.filter { it.name.endsWith(".m4a") || it.name.endsWith(".wav") || it.name.endsWith(".flac") }
            ?.sortedBy { it.name }?.firstOrNull()
        if (audio == null) { Toast.makeText(this, "Nicio înregistrare audio în sesiune", Toast.LENGTH_SHORT).show(); return }
        try {
            mediaPlayer?.release()
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(audio.absolutePath)
                setOnCompletionListener { it.release(); if (mediaPlayer === it) mediaPlayer = null }
                prepare(); start()
            }
            Toast.makeText(this, "▶ Redau ${audio.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Nu pot reda: ${e.message?.take(60)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSession(dir: File) {
        AlertDialog.Builder(this)
            .setTitle("Șterge sesiunea?")
            .setMessage("${formatTimestamp(dir.name.removePrefix("session_"))}\n" +
                "${Storage.humanSize(Storage.dirSize(dir))}\n\nSe șterge definitiv de pe telefon.")
            .setPositiveButton("🗑 Șterge") { _, _ ->
                val ok = dir.deleteRecursively()
                Toast.makeText(this, if (ok) "Sesiune ștearsă." else "Nu am putut șterge.", Toast.LENGTH_SHORT).show()
                showRecordingsManager()
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun doUpload(sessionDir: File) {
        if (!isWifiConnected()) {
            // Permite cu confirmare pe cellular (poate fi mare consum date)
            AlertDialog.Builder(this)
                .setTitle("⚠ Nu ești pe WiFi")
                .setMessage("Upload pe date mobile poate consuma volum mare. Continui oricum?")
                .setPositiveButton("Da, upload pe mobile") { _, _ -> performUpload(sessionDir) }
                .setNegativeButton("Anulează", null)
                .show()
            return
        }
        performUpload(sessionDir)
    }

    private fun performUpload(sessionDir: File) {
        if (uploadManager.jwtToken.isNullOrBlank()) {
            showTokenDialog { performUpload(sessionDir) }
            return
        }

        tvUploadStatus.text = "Se pregătește upload-ul..."

        uploadManager.uploadSessionAsync(sessionDir, object : UploadManager.ProgressCallback {
            override fun onProgress(fileIndex: Int, fileCount: Int, fileName: String, bytesDone: Long, bytesTotal: Long) {
                runOnUiThread {
                    tvUploadStatus.text = "Se încarcă $fileIndex/$fileCount: $fileName"
                }
            }

            override fun onDone(result: UploadManager.UploadResult) {
                runOnUiThread {
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

    /** Initiaza o sesiune intr-un mod dat (transect/senzor fix): salveaza modul,
     *  cere permisiunile, apoi continua prin proceedSession(). */
    private fun beginSession(fixed: Boolean) {
        if (RecordingService.isRunning) return
        isFixedPoint = fixed
        pendingFixed = fixed
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_MODE_SET, true)
            .putBoolean(KEY_MODE_FIXED, fixed)
            .apply()
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        // Senzor fix -> anti-intruziune cu scanare BLE (Android 12+ cere BLUETOOTH_SCAN la runtime)
        if (fixed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) proceedSession()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST)
    }

    private fun proceedSession() {
        if (pendingFixed) prepareFixedSensor()   // locatie -> program -> baterie -> start
        else checkGpsAndStart()                  // transect: GPS necesar pentru traseu
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == PERMISSIONS_REQUEST) {
            // Esentiale: audio + locatie. BLUETOOTH_SCAN / notificari sunt optionale
            // (anti-intruziunea BLE merge fara, doar nu scaneaza) -> nu blocam sesiunea.
            val essential = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
            val essentialOk = perms.indices.none { i ->
                perms[i] in essential && results[i] != PackageManager.PERMISSION_GRANTED
            }
            if (essentialOk) proceedSession()
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

    // -------------------------------------------------------------------------
    // SENZOR FIX: locatie -> program nocturn (offline) -> pregatire baterie -> start
    // -------------------------------------------------------------------------

    private fun prepareFixedSensor() {
        tvStatus.text = "📍 Obțin locația..."
        getQuickLocation { loc ->
            if (loc != null) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putFloat("fixed_lat", loc.latitude.toFloat())
                    .putFloat("fixed_lon", loc.longitude.toFloat())
                    .apply()
                showFixedSensorPrep(loc.latitude, loc.longitude)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Fără locație")
                    .setMessage("Nu am obținut locația GPS. Activează GPS-ul sau introdu coordonate manual.")
                    .setPositiveButton("Activează GPS") { _, _ ->
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); updateUI(false)
                    }
                    .setNeutralButton("Coordonate manuale") { _, _ -> showManualFixedLocation() }
                    .setNegativeButton("Continuă fără") { _, _ -> showFixedSensorPrep(null, null) }
                    .show()
            }
        }
    }

    private fun showManualFixedLocation() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 32, 64, 0) }
        val etLat = EditText(this).apply { hint = "Latitudine (ex: 45.8312)" }
        val etLon = EditText(this).apply { hint = "Longitudine (ex: 24.1205)" }
        layout.addView(etLat); layout.addView(etLon)
        AlertDialog.Builder(this)
            .setTitle("Locație senzor")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                showFixedSensorPrep(etLat.text.toString().toDoubleOrNull(), etLon.text.toString().toDoubleOrNull())
            }
            .setNegativeButton("Anulează") { _, _ -> updateUI(false) }
            .show()
    }

    /** Locatie rapida: last-known (instant, offline) apoi un singur fix GPS cu timeout 15s. */
    @Suppress("MissingPermission")
    private fun getQuickLocation(cb: (Location?) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) { cb(null); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            } catch (_: Exception) {}
        }
        if (best != null && System.currentTimeMillis() - best!!.time < 5 * 60 * 1000) { cb(best); return }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { cb(best); return }
        var done = false
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return; done = true; lm.removeUpdates(this); cb(location)
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("deprecated") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            Handler(Looper.getMainLooper()).postDelayed({
                if (!done) { done = true; lm.removeUpdates(listener); cb(best) }
            }, 15000)
        } catch (e: Exception) { cb(best) }
    }

    private fun showFixedSensorPrep(lat: Double?, lon: Double?) {
        val locTxt = if (lat != null && lon != null) "📍 Locație: %.5f, %.5f".format(lat, lon)
                     else "📍 Locație: necunoscută"
        val msg = buildString {
            append(locTxt).append("\n\n")
            append("🌙 Program propus (offline, după soare):\n").append(scheduleSuggestion(lat, lon)).append("\n\n")
            append("🔋 Baterie acum:\n").append(batteryReport()).append("\n\n")
            append("✅ Pentru autonomie maximă în pădure:\n")
            append("• Activează Mod avion (dacă nu e semnal) — economie majoră\n")
            append("• Închide aplicațiile din fundal (Setări → Aplicații)\n")
            append("• Luminozitate la minim, lasă ecranul să se stingă\n")
            append("• Dezactivează Bluetooth/WiFi dacă nu le folosești aici\n")
            append("• Pleacă cu bateria încărcată / powerbank dacă stă mult\n\n")
            append("Înregistrează doar în fereastra nocturnă (restul timpului doarme → economie).")
        }
        AlertDialog.Builder(this)
            .setTitle("📍 Pregătire senzor fix")
            .setMessage(msg)
            .setPositiveButton("▶ Pornește") { _, _ -> startRecording(lat, lon) }
            .setNeutralButton("✈ Mod avion") { _, _ ->
                startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); updateUI(false)
            }
            .setNegativeButton("Anulează") { _, _ -> updateUI(false) }
            .setCancelable(false)
            .show()
    }

    /** Program nocturn din rasarit/apus — calcul LOCAL (RecordWindow), merge FARA internet. */
    private fun scheduleSuggestion(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null) return "Fără locație → fereastră fixă 19:00 → 07:00."
        val ss = RecordWindow.sunriseSunsetLocalMin(Calendar.getInstance(), lat, lon)
            ?: return "Calcul indisponibil → fereastră fixă 19:00 → 07:00."
        val (sunrise, sunset) = ss
        val start = (sunset - 30 + 1440) % 1440
        val end = (sunrise + 30) % 1440
        return "Apus ~${fmtMin(sunset)}, răsărit ~${fmtMin(sunrise)}\n" +
               "→ înregistrează ${fmtMin(start)} → ${fmtMin(end)} (toată noaptea)"
    }

    private fun fmtMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    private fun batteryReport(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.let { if (it > 0) it / 10.0 else null }
        val sb = StringBuilder()
        sb.append("• Nivel: ").append(if (level >= 0) "$level%" else "?")
        if (charging) sb.append(" (se încarcă)")
        if (tempC != null) sb.append("\n• Temperatură: %.0f°C".format(tempC))
        if (level in 0..49 && !charging) sb.append("\n⚠ Sub 50% — încarcă înainte de a lăsa în teren")
        return sb.toString()
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

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun startRecording(staticLat: Double?, staticLon: Double?) {
        // Mod programat (inregistrare doar in fereastra nocturna + anti-furt) implicit
        // pentru senzor lasat nesupravegheat (punct fix). Transect = continuu.
        // Se poate dezactiva cu pref "schedule_enabled".
        val scheduled = isFixedPoint &&
            getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("schedule_enabled", true)
        val i = Intent(this, RecordingService::class.java).apply {
            if (staticLat != null && staticLon != null) {
                putExtra("static_lat", staticLat)
                putExtra("static_lon", staticLon)
            }
            putExtra("fixed_point", isFixedPoint)
            putExtra("scheduled", scheduled)
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

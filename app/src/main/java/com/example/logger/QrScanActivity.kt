package com.example.logger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvHint: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var scanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        previewView = findViewById(R.id.previewView)
        tvHint = findViewById(R.id.tvHint)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 200)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrAnalyzer { code -> handleCode(code) })
                }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleCode(code: String) {
        if (scanned) return
        when {
            code.startsWith("bioecho://navigate?") -> {
                scanned = true
                val uri = Uri.parse(code)
                val lat = uri.getQueryParameter("lat")?.toDoubleOrNull()
                val lon = uri.getQueryParameter("lon")?.toDoubleOrNull()
                val name = uri.getQueryParameter("name") ?: "Destinație"
                if (lat != null && lon != null) {
                    runOnUiThread {
                        startActivity(Intent(this, MapActivity::class.java).apply {
                            putExtra("lat", lat)
                            putExtra("lon", lon)
                            putExtra("name", name)
                        })
                        finish()
                    }
                } else {
                    runOnUiThread {
                        tvHint.text = "QR navigare invalid: lipsesc coordonate."
                        scanned = false
                    }
                }
            }
            code.startsWith("bioecho://auth/") -> {
                scanned = true
                val authCode = code.removePrefix("bioecho://auth/")
                runOnUiThread { tvHint.text = "Se verifică codul..." }
                Thread { verifyCode(authCode) }.start()
            }
            code.startsWith("bioecho://device/") -> {
                // Provizionare senzor: tokenul "nzdev_..." e identitatea locala (fara Google)
                scanned = true
                val token = code.removePrefix("bioecho://device/").trim()
                if (token.startsWith("nzdev_") && token.length > 12) {
                    getSharedPreferences("bioecho_prefs", MODE_PRIVATE).edit()
                        .putString("device_token", token).apply()
                    runOnUiThread {
                        Toast.makeText(this, "✓ Telefon provizionat ca senzor.", Toast.LENGTH_LONG).show()
                        setResult(RESULT_OK); finish()
                    }
                } else {
                    runOnUiThread { tvHint.text = "QR senzor invalid."; scanned = false }
                }
            }
            else -> return
        }
    }

    private fun verifyCode(code: String) {
        try {
            val url = URL("${UploadManager.SERVER}/api/auth/mobile-verify")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.bufferedWriter().use { it.write("""{"code":"$code"}""") }
            val responseCode = conn.responseCode
            val body = if (responseCode in 200..299)
                conn.inputStream.bufferedReader().readText()
            else
                conn.errorStream?.bufferedReader()?.readText() ?: ""

            if (responseCode == 200) {
                val json = JSONObject(body)
                val token = json.getString("token")
                val email = json.optString("email", "")
                val prefs = getSharedPreferences("bioecho_prefs", MODE_PRIVATE)
                prefs.edit().putString("jwt_token", token).putString("user_email", email).apply()
                runOnUiThread {
                    Toast.makeText(this, "✓ Autentificat: $email", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } else {
                runOnUiThread {
                    tvHint.text = "Cod invalid sau expirat. Regenerează din platformă."
                    scanned = false
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                tvHint.text = "Eroare conexiune: ${e.message}"
                scanned = false
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 200 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else { Toast.makeText(this, "Camera necesară pentru scanare QR", Toast.LENGTH_SHORT).show(); finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private inner class QrAnalyzer(val onDetected: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val scanner = BarcodeScanning.getClient()

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                        ?.rawValue?.let { onDetected(it) }
                }
                .addOnCompleteListener { imageProxy.close() }
        }
    }
}

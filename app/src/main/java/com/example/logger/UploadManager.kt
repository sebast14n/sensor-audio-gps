package com.example.logger

import android.content.Context
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UploadManager(private val context: Context) {

    companion object {
        const val SERVER = "https://echo.noze.ro"
        private const val MAX_RETRIES = 3
    }

    private val prefs = context.getSharedPreferences("bioecho_prefs", Context.MODE_PRIVATE)

    var jwtToken: String?
        get() = prefs.getString("jwt_token", null)
        set(v) = prefs.edit().putString("jwt_token", v).apply()

    data class UploadResult(
        val success: Int,
        val skipped: Int,    // 409 — deja uploadat
        val failed: List<String>
    )

    interface ProgressCallback {
        fun onProgress(current: Int, total: Int, fileName: String)
        fun onDone(result: UploadResult)
        fun onError(message: String)
    }

    fun getLocalSessions(): List<File> {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return (base.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("session_") }
            .sortedByDescending { it.name }
    }

    fun uploadSessionAsync(sessionDir: File, callback: ProgressCallback) {
        Thread {
            val token = jwtToken
            if (token.isNullOrBlank()) {
                callback.onError("Token JWT neconfigurat. Apasă SET TOKEN.")
                return@Thread
            }

            val gpxFile = sessionDir.listFiles()?.firstOrNull { it.name.endsWith(".gpx") }
            if (gpxFile == null) {
                callback.onError("Fișier GPX lipsă în sesiunea ${sessionDir.name}")
                return@Thread
            }

            val audioFiles = (sessionDir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".m4a") }
                .sortedBy { it.name }

            if (audioFiles.isEmpty()) {
                callback.onError("Nicio înregistrare audio în sesiunea ${sessionDir.name}")
                return@Thread
            }

            val surveyId = uploadGpx(gpxFile, sessionDir.name, token)
            if (surveyId == null) {
                callback.onError("Upload GPX eșuat (verifică conexiunea și token-ul)")
                return@Thread
            }

            var success = 0
            var skipped = 0
            val failed = mutableListOf<String>()

            audioFiles.forEachIndexed { idx, file ->
                callback.onProgress(idx + 1, audioFiles.size, file.name)
                when (uploadAudio(file, surveyId, token)) {
                    UploadStatus.SUCCESS -> success++
                    UploadStatus.SKIPPED -> skipped++
                    UploadStatus.FAILED  -> failed.add(file.name)
                }
            }

            callback.onDone(UploadResult(success, skipped, failed))
        }.start()
    }

    // Returneaza survey_id sau null daca esueaza
    private fun uploadGpx(file: File, sessionName: String, token: String): String? {
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val code: Int
                val body: String
                val boundary = "BioEcho${System.currentTimeMillis()}"
                val conn = openConn("$SERVER/api/surveys/upload-gpx", token, boundary, 30_000, 30_000)
                DataOutputStream(conn.outputStream).use { out ->
                    writeFilePart(out, boundary, "file", file.name, "application/gpx+xml", file)
                    writeFieldPart(out, boundary, "name", sessionName)
                    out.writeBytes("--$boundary--\r\n")
                }
                code = conn.responseCode
                body = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                       else conn.errorStream?.bufferedReader()?.readText() ?: ""

                when {
                    code == 201 -> {
                        val m = Regex(""""survey_id"\s*:\s*"([^"]+)"""").find(body)
                        return m?.groupValues?.get(1)
                    }
                    code in 400..499 -> return null  // client error, no retry
                    attempt < MAX_RETRIES - 1 -> Thread.sleep(2000L * (attempt + 1))
                    else -> return null
                }
            } catch (e: Exception) {
                if (attempt == MAX_RETRIES - 1) return null
                Thread.sleep(2000L * (attempt + 1))
            }
        }
        return null
    }

    private enum class UploadStatus { SUCCESS, SKIPPED, FAILED }

    private fun uploadAudio(file: File, surveyId: String, token: String): UploadStatus {
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val boundary = "BioEcho${System.currentTimeMillis()}"
                val conn = openConn("$SERVER/api/recordings/upload", token, boundary, 60_000, 120_000)
                DataOutputStream(conn.outputStream).use { out ->
                    writeFilePart(out, boundary, "file", file.name, "audio/mp4", file)
                    writeFieldPart(out, boundary, "survey_id", surveyId)
                    out.writeBytes("--$boundary--\r\n")
                }
                val code = conn.responseCode

                return when {
                    code == 201            -> UploadStatus.SUCCESS
                    code == 409            -> UploadStatus.SKIPPED   // deja exista, nu e eroare
                    code in 400..499       -> UploadStatus.FAILED     // eroare client, nu retry
                    attempt < MAX_RETRIES - 1 -> {
                        Thread.sleep(2000L * (attempt + 1))
                        continue
                    }
                    else -> UploadStatus.FAILED
                }
            } catch (e: Exception) {
                if (attempt == MAX_RETRIES - 1) return UploadStatus.FAILED
                Thread.sleep(2000L * (attempt + 1))
            }
        }
        return UploadStatus.FAILED
    }

    private fun openConn(
        urlStr: String, token: String, boundary: String,
        connectMs: Int, readMs: Int
    ): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = connectMs
            readTimeout = readMs
        }
    }

    private fun writeFilePart(
        out: DataOutputStream, boundary: String,
        fieldName: String, fileName: String, mimeType: String, file: File
    ) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
        out.writeBytes("Content-Type: $mimeType\r\n\r\n")
        file.inputStream().use { it.copyTo(out) }
        out.writeBytes("\r\n")
    }

    private fun writeFieldPart(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.writeBytes("$value\r\n")
    }
}

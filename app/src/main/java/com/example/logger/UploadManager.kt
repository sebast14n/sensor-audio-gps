package com.example.logger

import android.content.Context
import android.os.PowerManager
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UploadManager(private val context: Context) {

    companion object {
        val SERVER = BuildConfig.SERVER_URL
        private const val MAX_RETRIES = 3
        const val MARK_DONE = ".uploaded"      // toate fisierele urcate
        const val MARK_PARTIAL = ".partial"    // unele urcate, altele esuate

        /** Stare upload a unei sesiuni: "done" | "partial" | "none". */
        fun sessionState(dir: File): String = when {
            File(dir, MARK_DONE).exists() -> "done"
            File(dir, MARK_PARTIAL).exists() -> "partial"
            else -> "none"
        }
    }

    private val prefs = context.getSharedPreferences("bioecho_prefs", Context.MODE_PRIVATE)

    var jwtToken: String?
        get() = prefs.getString("jwt_token", null)
        set(v) = prefs.edit().putString("jwt_token", v).apply()

    data class UploadResult(val success: Int, val skipped: Int, val failed: List<String>)

    interface ProgressCallback {
        // bytesDone/bytesTotal = octeti la nivel de SESIUNE (pt bara de progres MB/total)
        fun onProgress(fileIndex: Int, fileCount: Int, fileName: String, bytesDone: Long, bytesTotal: Long)
        fun onDone(result: UploadResult)
        fun onError(message: String)
    }

    fun getLocalSessions(): List<File> = Storage.sessions(context)

    fun uploadSessionAsync(sessionDir: File, callback: ProgressCallback) {
        Thread {
            // WAKELOCK: tine CPU pornit pe durata upload-ului — ecranul se poate stinge / screen-saver
            // poate intra, transferul NU se mai intrerupe.
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BioEcho:upload")
            try {
                wl.acquire(30 * 60 * 1000L)   // max 30 min siguranta
                runUpload(sessionDir, callback)
            } finally {
                if (wl.isHeld) try { wl.release() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun runUpload(sessionDir: File, callback: ProgressCallback) {
        val token = jwtToken
        if (token.isNullOrBlank()) { callback.onError("Neautentificat. Scanează QR pe pagina principală."); return }

        val gpxFile = sessionDir.listFiles()?.firstOrNull { it.name.endsWith(".gpx") }
        if (gpxFile == null) { callback.onError("Fișier GPX lipsă în ${sessionDir.name}"); return }

        val audioFiles = (sessionDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".m4a") || it.name.endsWith(".wav") || it.name.endsWith(".flac") }
            .sortedBy { it.name }
        if (audioFiles.isEmpty()) { callback.onError("Nicio înregistrare audio în ${sessionDir.name}"); return }

        val surveyId = uploadGpx(gpxFile, sessionDir.name, token)
        if (surveyId == null) { callback.onError("Upload traseu eșuat (verifică conexiunea)"); return }

        val bytesTotal = audioFiles.sumOf { it.length() }
        var bytesDone = 0L
        var success = 0; var skipped = 0
        val failed = mutableListOf<String>()

        audioFiles.forEachIndexed { idx, file ->
            val base = bytesDone
            callback.onProgress(idx + 1, audioFiles.size, file.name, base, bytesTotal)
            val status = uploadAudio(file, surveyId, token) { written ->
                callback.onProgress(idx + 1, audioFiles.size, file.name, base + written, bytesTotal)
            }
            when (status) {
                UploadStatus.SUCCESS -> success++
                UploadStatus.SKIPPED -> skipped++
                UploadStatus.FAILED  -> failed.add(file.name)
            }
            bytesDone = base + file.length()
        }

        // marker de stare (pt colorare in lista + reluare)
        File(sessionDir, MARK_DONE).delete()
        File(sessionDir, MARK_PARTIAL).delete()
        try {
            if (failed.isEmpty()) File(sessionDir, MARK_DONE).createNewFile()
            else File(sessionDir, MARK_PARTIAL).createNewFile()
        } catch (_: Exception) {}

        callback.onDone(UploadResult(success, skipped, failed))
    }

    private fun uploadGpx(file: File, sessionName: String, token: String): String? {
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val boundary = "BioEcho${System.currentTimeMillis()}"
                val conn = openConn("$SERVER/api/surveys/upload-gpx", token, boundary, 30_000, 60_000)
                DataOutputStream(conn.outputStream).use { out ->
                    writeFilePart(out, boundary, "file", file.name, "application/gpx+xml", file, null)
                    writeFieldPart(out, boundary, "name", sessionName)
                    out.writeBytes("--$boundary--\r\n")
                }
                val code = conn.responseCode
                val body = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                           else conn.errorStream?.bufferedReader()?.readText() ?: ""
                when {
                    code in 200..299 -> {
                        val m = Regex(""""survey_id"\s*:\s*"([^"]+)"""").find(body)
                        return m?.groupValues?.get(1)
                    }
                    code in 400..499 -> return null
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

    private fun uploadAudio(file: File, surveyId: String, token: String, onBytes: (Long) -> Unit): UploadStatus {
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val boundary = "BioEcho${System.currentTimeMillis()}"
                val mime = when {
                    file.name.endsWith(".wav") -> "audio/wav"
                    file.name.endsWith(".flac") -> "audio/flac"
                    else -> "audio/mp4"
                }
                // timeout generos: fisiere mari pe uplink lent (a fost cauza esecului lui 003)
                val conn = openConn("$SERVER/api/recordings/upload", token, boundary, 60_000, 600_000)
                DataOutputStream(conn.outputStream).use { out ->
                    writeFilePart(out, boundary, "file", file.name, mime, file, onBytes)
                    writeFieldPart(out, boundary, "survey_id", surveyId)
                    out.writeBytes("--$boundary--\r\n")
                }
                val code = conn.responseCode
                return when {
                    code in 200..299 -> UploadStatus.SUCCESS
                    code == 409      -> UploadStatus.SKIPPED
                    code in 400..499 -> UploadStatus.FAILED
                    attempt < MAX_RETRIES - 1 -> { Thread.sleep(2000L * (attempt + 1)); continue }
                    else -> UploadStatus.FAILED
                }
            } catch (e: Exception) {
                if (attempt == MAX_RETRIES - 1) return UploadStatus.FAILED
                Thread.sleep(2000L * (attempt + 1))
            }
        }
        return UploadStatus.FAILED
    }

    private fun openConn(urlStr: String, token: String, boundary: String, connectMs: Int, readMs: Int): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(256 * 1024)   // streaming, nu buffer in RAM tot fisierul
            connectTimeout = connectMs; readTimeout = readMs
        }
    }

    private fun writeFilePart(
        out: DataOutputStream, boundary: String, fieldName: String,
        fileName: String, mimeType: String, file: File, onBytes: ((Long) -> Unit)?
    ) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
        out.writeBytes("Content-Type: $mimeType\r\n\r\n")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            var total = 0L; var lastReport = 0L
            while (true) {
                val r = input.read(buf); if (r < 0) break
                out.write(buf, 0, r)
                total += r
                if (onBytes != null && total - lastReport >= 256 * 1024) { onBytes(total); lastReport = total }
            }
            onBytes?.invoke(total)
        }
        out.writeBytes("\r\n")
    }

    private fun writeFieldPart(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.writeBytes("$value\r\n")
    }
}

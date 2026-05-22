package com.example.logger

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifica daca exista o versiune mai noua disponibila.
 * Endpoint: https://echo.noze.ro/api/app/latest
 *
 * Returneaza: {latest_version, apk_url, release_notes, mandatory}
 * Compara cu BuildConfig.VERSION_NAME. Daca newer -> dialog cu Download.
 */
object UpdateChecker {

    private const val ENDPOINT = "https://echo.noze.ro/api/app/latest"
    private const val PREFS = "bioecho_prefs"
    private const val KEY_DISMISSED_VERSION = "update_dismissed_version"

    /**
     * Verifică în background. Dacă apare versiune nouă, arată dialog non-intrusive.
     * Apelează din MainActivity.onResume() sau onCreate() — silentios la eșec.
     */
    fun checkAsync(activity: AppCompatActivity) {
        Thread {
            try {
                val url = URL(ENDPOINT)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) { conn.disconnect(); return@Thread }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val data = JSONObject(body)
                val latest = data.optString("latest_version")
                val apkUrl = data.optString("apk_url")
                val notes = data.optString("release_notes", "")
                val mandatory = data.optBoolean("mandatory", false)
                val sizeMb = data.optDouble("apk_size_mb", 0.0)
                val htmlUrl = data.optString("html_url", "")

                if (latest.isBlank() || apkUrl.isBlank()) return@Thread

                val current = BuildConfig.VERSION_NAME
                if (!isNewerVersion(latest, current)) return@Thread

                // Check dacă user-ul a dismiss-uit această versiune (skip update non-mandatory)
                val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val dismissed = prefs.getString(KEY_DISMISSED_VERSION, "")
                if (!mandatory && dismissed == latest) return@Thread

                activity.runOnUiThread {
                    showUpdateDialog(activity, current, latest, sizeMb, apkUrl, notes, htmlUrl, mandatory)
                }
            } catch (e: Exception) {
                // silentios — verificarea e best-effort
            }
        }.start()
    }

    /** Compară versiuni semantice tip "1.2.3" — returnează true dacă latest > current */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        try {
            val lat = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val cur = current.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(lat.size, cur.size)) {
                val l = lat.getOrNull(i) ?: 0
                val c = cur.getOrNull(i) ?: 0
                if (l > c) return true
                if (l < c) return false
            }
        } catch (e: Exception) {
            // fallback la compara string
            return latest != current
        }
        return false
    }

    private fun showUpdateDialog(
        ctx: Context, current: String, latest: String, sizeMb: Double,
        apkUrl: String, notes: String, htmlUrl: String, mandatory: Boolean
    ) {
        val builder = AlertDialog.Builder(ctx)
            .setTitle("📲 Versiune nouă disponibilă")
            .setMessage(buildString {
                append("Versiunea curentă: ").append(current).append("\n")
                append("Versiunea nouă: ").append(latest)
                if (sizeMb > 0) append(" (").append(sizeMb).append(" MB)")
                if (notes.isNotBlank()) {
                    append("\n\nNotă:\n")
                    append(notes.take(300))
                }
                if (mandatory) append("\n\n⚠ Actualizare obligatorie.")
            })
            .setPositiveButton("Descarcă") { _, _ ->
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
            }
            .setNegativeButton(if (mandatory) "Ieși" else "Mai târziu") { _, _ ->
                if (mandatory && ctx is AppCompatActivity) ctx.finishAffinity()
            }

        if (!mandatory) {
            builder.setNeutralButton("Nu mai afișa pentru această versiune") { _, _ ->
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_DISMISSED_VERSION, latest).apply()
            }
        }
        if (htmlUrl.isNotBlank()) {
            // Note: AlertDialog doesn't support 4 buttons; release notes link e in message text
        }
        builder.setCancelable(!mandatory).show()
    }
}

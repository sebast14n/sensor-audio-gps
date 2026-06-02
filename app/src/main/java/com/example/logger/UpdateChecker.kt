package com.example.logger

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifica daca exista o versiune mai noua disponibila.
 * Endpoint: https://noze.ro/api/app/latest
 *
 * Returneaza: {latest_version, apk_url, release_notes, mandatory}
 * Compara cu BuildConfig.VERSION_NAME. Daca newer -> dialog cu Download.
 */
object UpdateChecker {

    private val ENDPOINT = BuildConfig.SERVER_URL + "/api/app/latest"
    private const val PREFS = "bioecho_prefs"
    private const val KEY_DISMISSED_VERSION = "update_dismissed_version"

    /**
     * Verifică în background. Daca apare versiune noua, arata dialog. Verbose=true -> arata Toast cu rezultatul.
     */
    fun checkAsync(activity: AppCompatActivity, verbose: Boolean = true) {
        Thread {
            val current = BuildConfig.VERSION_NAME
            try {
                val url = URL(ENDPOINT)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) {
                    val code = conn.responseCode
                    conn.disconnect()
                    if (verbose) activity.runOnUiThread {
                        Toast.makeText(activity, "⚠ Verificare versiune: HTTP $code", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val data = JSONObject(body)
                val latest = data.optString("latest_version")
                val apkUrl = data.optString("apk_url")
                val notes = data.optString("release_notes", "")
                val mandatory = data.optBoolean("mandatory", false)
                val sizeMb = data.optDouble("apk_size_mb", 0.0)
                val htmlUrl = data.optString("html_url", "")

                if (latest.isBlank() || apkUrl.isBlank()) {
                    if (verbose) activity.runOnUiThread {
                        Toast.makeText(activity, "Verificare versiune: răspuns gol", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                if (!isNewerVersion(latest, current)) {
                    if (verbose) activity.runOnUiThread {
                        Toast.makeText(activity, "✓ Versiunea curentă $current este la zi (latest: $latest)", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // Check dacă user-ul a dismiss-uit această versiune (skip update non-mandatory)
                val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val dismissed = prefs.getString(KEY_DISMISSED_VERSION, "")
                if (!mandatory && dismissed == latest) {
                    if (verbose) activity.runOnUiThread {
                        Toast.makeText(activity, "Versiune $latest disponibila (dismiss-uită)", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                activity.runOnUiThread {
                    showUpdateDialog(activity, current, latest, sizeMb, apkUrl, notes, htmlUrl, mandatory)
                }
            } catch (e: Exception) {
                if (verbose) activity.runOnUiThread {
                    Toast.makeText(activity, "⚠ Verificare versiune eșuată: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                }
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
            .setPositiveButton(if (ctx.let { AppUpdater.isDeviceOwner(it) }) "Actualizează automat" else "Actualizează") { _, _ ->
                val act = ctx as? AppCompatActivity
                if (act != null) AppUpdater.downloadAndInstall(act, apkUrl)
                else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))  // fallback
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

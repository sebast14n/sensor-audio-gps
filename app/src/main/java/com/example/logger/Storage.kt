package com.example.logger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Stocare centralizata. Inregistrarile merg intr-un folder PUBLIC (/storage/emulated/0/BioEcho/)
 * care SUPRAVIETUIESTE dezinstalarii + rebrand-ului (nu e legat de package). Fallback pe
 * folderul app-specific daca nu avem permisiunea "All files access".
 */
object Storage {

    private const val PUBLIC_DIR = "BioEcho"

    /** True daca putem scrie in folderul public (Android <11 sau "All files access" acordat). */
    fun canUsePublic(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Folderul de baza pentru sesiuni. Public daca se poate, altfel app-specific. */
    fun baseDir(context: Context): File {
        if (canUsePublic()) {
            val pub = File(Environment.getExternalStorageDirectory(), PUBLIC_DIR)
            if (pub.exists() || pub.mkdirs()) {
                migrateLegacy(context, pub)
                return pub
            }
        }
        return appDir(context)
    }

    private fun appDir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /** Muta o singura data sesiunile vechi din app-specific in folderul public (fara pierdere). */
    private fun migrateLegacy(context: Context, pub: File) {
        try {
            val old = appDir(context)
            if (old.absolutePath == pub.absolutePath) return
            old.listFiles()?.filter { it.isDirectory && it.name.startsWith("session_") }?.forEach { src ->
                val dst = File(pub, src.name)
                if (!dst.exists()) src.renameTo(dst)
            }
        } catch (_: Exception) {}
    }

    /** Cere permisiunea "All files access" (Android 11+). No-op daca e deja acordata / Android <11. */
    fun requestAllFilesAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                activity.startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${activity.packageName}")))
            } catch (e: Exception) {
                try { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) {}
            }
        }
    }

    /** Sesiunile (foldere session_*), cele mai noi primele. */
    fun sessions(context: Context): List<File> =
        (baseDir(context).listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("session_") }
            .sortedByDescending { it.name }

    /** Marimea totala (bytes) a unui folder. */
    fun dirSize(dir: File): Long =
        try { dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() } catch (_: Exception) { 0L }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000     -> "%.0f MB".format(bytes / 1e6)
        bytes >= 1_000         -> "%.0f KB".format(bytes / 1e3)
        else                   -> "$bytes B"
    }
}

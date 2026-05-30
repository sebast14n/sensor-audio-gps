package com.example.logger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Primeste rezultatul instalarii prin PackageInstaller.
 * - Daca app-ul NU e device-owner -> sistemul cere confirmarea utilizatorului
 *   (STATUS_PENDING_USER_ACTION) -> lansam dialogul de instalare (un-tap).
 * - Daca app-ul E device-owner -> instalarea e silentioasa, primim direct SUCCESS.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "✓ Actualizare instalata", Toast.LENGTH_LONG).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "⚠ Instalare esuata: ${msg ?: "necunoscut"}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

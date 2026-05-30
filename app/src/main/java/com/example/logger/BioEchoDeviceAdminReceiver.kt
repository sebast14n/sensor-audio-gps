package com.example.logger

import android.app.admin.DeviceAdminReceiver

/**
 * Necesar pentru a putea seta aplicatia ca DEVICE OWNER (provisioning).
 * Ca device-owner, app-ul poate instala APK-uri SILENTIOS (auto-update fara prompt)
 * + poate impiedica dezinstalarea + alte controale de flota.
 *
 * Provisioning (o singura data per telefon, pe un telefon resetat din fabrica, FARA
 * conturi adaugate):
 *   adb shell dpm set-device-owner com.example.logger/.BioEchoDeviceAdminReceiver
 */
class BioEchoDeviceAdminReceiver : DeviceAdminReceiver()

package com.example.logger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anti-furt pentru telefon lasat nesupravegheat in teren.
 *
 * - Senzor SIGNIFICANT_MOTION (very low power, one-shot) -> telefonul a fost
 *   mutat din pozitie -> trimite alerta cu locatie la server.
 * - Baterie scazuta (ACTION_BATTERY_LOW) -> alerta cu locatie + nivel.
 * - In fiecare alerta raporteaza si starea SIM (ABSENT = posibil scos).
 *
 * NB: nu poate comuta modul avion / reaprinde radio-ul (restrictie Android pentru
 * aplicatii normale). Trimite alerta DOAR daca reteaua e disponibila (date pornite).
 * Esecurile sunt non-fatale pentru inregistrare.
 */
class AntiTheftMonitor(
    private val ctx: Context,
    private var locationLat: () -> Double?,
) {
    var locationLon: () -> Double? = { null }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val motion: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var batteryReceiver: BroadcastReceiver? = null
    private var lastSimState: Int = simState()

    private val prefs = ctx.getSharedPreferences("bioecho_prefs", Context.MODE_PRIVATE)
    private val server = "https://echo.noze.ro"

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            sendAlert("moved", mapOf("note" to "telefon mutat din pozitie"))
            // re-armeaza (senzorul e one-shot)
            motion?.let { sm?.requestTriggerSensor(this, it) }
        }
    }

    fun start() {
        motion?.let { sm?.requestTriggerSensor(triggerListener, it) }
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == Intent.ACTION_BATTERY_LOW) {
                    sendAlert("battery_low", mapOf("battery" to batteryPct()))
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_LOW),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun stop() {
        motion?.let { sm?.cancelTriggerSensor(triggerListener, it) }
        try { batteryReceiver?.let { ctx.unregisterReceiver(it) } } catch (_: Exception) {}
    }

    private fun batteryPct(): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun simState(): Int = try {
        (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simState
            ?: TelephonyManager.SIM_STATE_UNKNOWN
    } catch (_: Exception) { TelephonyManager.SIM_STATE_UNKNOWN }

    private fun sendAlert(reason: String, extra: Map<String, Any?>) {
        val token = prefs.getString("jwt_token", null) ?: return  // fara token, fara alerta
        Thread {
            try {
                val sim = simState()
                val body = JSONObject().apply {
                    put("reason", reason)
                    put("lat", locationLat())
                    put("lon", locationLon())
                    put("battery", batteryPct())
                    put("sim_state", sim)            // 1=ABSENT, 5=READY
                    put("sim_removed", sim == TelephonyManager.SIM_STATE_ABSENT)
                    put("device", android.os.Build.MODEL)
                    extra.forEach { (k, v) -> put(k, v) }
                }
                val conn = (URL("$server/api/auth/mobile-alert").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000; readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (_: Exception) {
                // retea indisponibila / eroare -> ignora (non-fatal)
            }
        }.start()
    }
}

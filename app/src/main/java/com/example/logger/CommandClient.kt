package com.example.logger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Client C&C (command & control) pentru senzorul fix.
 *
 * Telefonul e in spatele NAT -> nu poate fi "sunat"; el iese spre server (poll) cat are net.
 * La fiecare ciclu: POST /api/device/heartbeat (stare) -> raspunsul contine comenzile in asteptare
 * -> le executa RecordingService -> POST .../ack. Autentificare: Bearer JWT (acelasi ca la upload).
 */
object CommandClient {
    private const val PREFS = "bioecho_prefs"

    /** ID stabil per-instalare (UUID), generat o data si pastrat in prefs. */
    fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = p.getString("device_id", null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            p.edit().putString("device_id", id).apply()
        }
        return id
    }

    /** Token de auth: identitatea de SENZOR (device_token, fara Google) daca a fost provizionat,
     *  altfel JWT-ul contului (legacy). */
    private fun authToken(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("device_token", null) ?: p.getString("jwt_token", null)
    }

    /** POST heartbeat -> comenzile in asteptare (JSONArray), sau null daca nu e net/autentificat. */
    fun postHeartbeat(ctx: Context, payload: JSONObject): JSONArray? {
        val token = authToken(ctx) ?: return null
        return try {
            val c = URL("${BuildConfig.SERVER_URL}/api/device/heartbeat").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000; c.readTimeout = 20000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) { c.disconnect(); return null }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            JSONObject(body).optJSONArray("commands") ?: JSONArray()
        } catch (_: Exception) { null }
    }

    /** Confirma executia unei comenzi. */
    fun ack(ctx: Context, id: String, status: String, result: String) {
        val token = authToken(ctx) ?: return
        try {
            val c = URL("${BuildConfig.SERVER_URL}/api/device/commands/$id/ack").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000; c.readTimeout = 15000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $token")
            val b = JSONObject().put("status", status).put("result", result.take(1000))
            c.outputStream.use { it.write(b.toString().toByteArray(Charsets.UTF_8)) }
            c.responseCode
            c.disconnect()
        } catch (_: Exception) {}
    }
}

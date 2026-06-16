package com.example.logger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Program de inregistrare PERSONALIZAT (multi-fereastra + duty-cycle), OPTIONAL.
 *
 * Daca NU exista un program salvat (sau e invalid) -> [recordingNowOrNull] intoarce null si
 * serviciul cade pe logica implicita [RecordWindow] (fereastra nocturna). => ZERO regresie cat timp
 * userul nu seteaza explicit un program.
 *
 * Fiecare fereastra: start/end relativ la apus/rasarit (sau ora fixa) + duty-cycle
 * (inregistreaza dutyOn min, pauza dutyOff min; 0 = continuu in fereastra).
 */
object Schedule {
    private const val PREFS = "bioecho_prefs"
    private const val KEY = "record_schedule_json"

    /** ref: "sunset" | "sunrise" | "abs"; off = minute (la "abs": minut dupa miezul noptii). */
    data class Win(
        val refStart: String, val offStart: Int,
        val refEnd: String, val offEnd: Int,
        val dutyOn: Int = 0, val dutyOff: Int = 0
    )

    fun load(ctx: Context): List<Win>? {
        val js = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val arr = JSONArray(js)
            if (arr.length() == 0) return null
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Win(o.optString("rs", "sunset"), o.optInt("os", 0),
                    o.optString("re", "sunrise"), o.optInt("oe", 0),
                    o.optInt("don", 0), o.optInt("doff", 0))
            }
        } catch (_: Exception) { null }
    }

    fun save(ctx: Context, wins: List<Win>) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (wins.isEmpty()) { p.edit().remove(KEY).apply(); return }
        val arr = JSONArray()
        wins.forEach {
            arr.put(JSONObject()
                .put("rs", it.refStart).put("os", it.offStart)
                .put("re", it.refEnd).put("oe", it.offEnd)
                .put("don", it.dutyOn).put("doff", it.dutyOff))
        }
        p.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()

    fun hasCustom(ctx: Context): Boolean = load(ctx) != null

    /** minut (0..1439) rezolvat din ref+offset; fara GPS: apus≈19:00, rasarit≈07:00. */
    private fun resolve(ref: String, off: Int, srMin: Int?, ssMin: Int?): Int {
        val base = when (ref) {
            "sunset" -> ssMin ?: (RecordWindow.FALLBACK_START_HOUR * 60)
            "sunrise" -> srMin ?: (RecordWindow.FALLBACK_END_HOUR * 60)
            else -> 0   // "abs": off = minut absolut dupa miezul noptii
        }
        return ((base + off) % 1440 + 1440) % 1440
    }

    /**
     * true/false daca exista un program personalizat valid (inregistreaza acum sau nu);
     * null daca NU exista -> caller foloseste [RecordWindow] (comportamentul implicit).
     */
    fun recordingNowOrNull(ctx: Context, cal: Calendar, lat: Double?, lon: Double?): Boolean? {
        val wins = load(ctx) ?: return null
        val ss = if (lat != null && lon != null) RecordWindow.sunriseSunsetLocalMin(cal, lat, lon) else null
        val srMin = ss?.first    // rasarit
        val ssMin = ss?.second   // apus
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        for (w in wins) {
            val start = resolve(w.refStart, w.offStart, srMin, ssMin)
            val end = resolve(w.refEnd, w.offEnd, srMin, ssMin)
            val inWin = when {
                start == end -> true                              // 24h
                start < end -> nowMin in start until end
                else -> nowMin >= start || nowMin < end           // peste miezul noptii
            }
            if (!inWin) continue
            if (w.dutyOff <= 0 || w.dutyOn <= 0) return true       // continuu in fereastra
            val sinceStart = (nowMin - start + 1440) % 1440
            if (sinceStart % (w.dutyOn + w.dutyOff) < w.dutyOn) return true
        }
        return false
    }

    /** Descriere umana scurta a unei ferestre (pt UI). */
    fun describe(w: Win): String {
        fun ref(r: String, o: Int): String {
            val sign = if (o >= 0) "+$o" else "$o"
            return when (r) {
                "sunset" -> "apus" + (if (o != 0) " $sign min" else "")
                "sunrise" -> "răsărit" + (if (o != 0) " $sign min" else "")
                else -> "%02d:%02d".format((o / 60) % 24, ((o % 60) + 60) % 60)
            }
        }
        val duty = if (w.dutyOn > 0 && w.dutyOff > 0) " · ${w.dutyOn}/${w.dutyOff} min (rec/pauză)" else " · continuu"
        return ref(w.refStart, w.offStart) + " → " + ref(w.refEnd, w.offEnd) + duty
    }

    /** Presetari uzuale (folosite de editor). */
    fun preset(name: String): List<Win> = when (name) {
        "noapte"      -> listOf(Win("sunset", -30, "sunrise", 30))                                  // lilieci: noapte intreaga
        "zori_seara"  -> listOf(Win("sunrise", -30, "sunrise", 180), Win("sunset", -90, "sunset", 30)) // cor de zori + seara
        "noapte_eco"  -> listOf(Win("sunset", -30, "sunrise", 30, 5, 30))                           // economie: 5 rec / 30 pauza
        "nonstop"     -> listOf(Win("abs", 0, "abs", 0))                                            // 24h continuu
        else          -> emptyList()
    }
}

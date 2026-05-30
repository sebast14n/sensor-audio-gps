package com.example.logger

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/**
 * Decide daca momentul curent e in fereastra de inregistrare.
 *
 * Pentru lilieci: activitate nocturna -> inregistram de la ~30 min INAINTE de apus
 * pana la ~30 min DUPA rasarit. In afara ferestrei serviciul pune pe pauza audio +
 * elibereaza wakelock-ul (economie majora de baterie pentru un telefon lasat in teren).
 *
 * Daca nu avem coordonate (lat/lon null) -> fallback la ore fixe (configurabile).
 */
object RecordWindow {

    const val DEFAULT_BUFFER_MIN = 30          // minute inainte de apus / dupa rasarit
    const val FALLBACK_START_HOUR = 19         // fallback fara GPS: 19:00
    const val FALLBACK_END_HOUR = 7            // ... pana la 07:00

    /** Minute dupa miezul noptii (ora locala) pentru rasarit si apus, sau null daca nu se calculeaza. */
    fun sunriseSunsetLocalMin(cal: Calendar, lat: Double, lon: Double): Pair<Int, Int>? {
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val tzOffsetMin = cal.timeZone.getOffset(cal.timeInMillis) / 60000.0

        // Algoritm solar simplificat (NOAA), suficient ca precizie pentru ferestre +/- buffer.
        val zenith = 90.833                     // apus/rasarit oficial (refractie inclusa)
        fun calc(rising: Boolean): Double? {
            val lngHour = lon / 15.0
            val t = if (rising) dayOfYear + ((6 - lngHour) / 24.0)
            else dayOfYear + ((18 - lngHour) / 24.0)
            val m = 0.9856 * t - 3.289
            var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
            l = (l + 360) % 360
            var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
            ra = (ra + 360) % 360
            val lQuad = floor(l / 90.0) * 90.0
            val raQuad = floor(ra / 90.0) * 90.0
            ra = (ra + (lQuad - raQuad)) / 15.0
            val sinDec = 0.39782 * sin(Math.toRadians(l))
            val cosDec = cos(asin(sinDec))
            val cosH = (cos(Math.toRadians(zenith)) - sinDec * sin(Math.toRadians(lat))) /
                    (cosDec * cos(Math.toRadians(lat)))
            if (cosH > 1 || cosH < -1) return null   // soare care nu rasare/apune (latitudini extreme)
            val h = if (rising) 360 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))
            val hHour = h / 15.0
            val localMeanT = hHour + ra - 0.06571 * t - 6.622
            var ut = (localMeanT - lngHour) % 24.0
            if (ut < 0) ut += 24.0
            return ut * 60.0 + tzOffsetMin       // minute UT -> minute ora locala
        }

        val sr = calc(true) ?: return null
        val ss = calc(false) ?: return null
        fun norm(x: Double) = ((x.roundToInt() % 1440) + 1440) % 1440
        return Pair(norm(sr), norm(ss))
    }

    /**
     * true daca [cal] e in fereastra de inregistrare nocturna pentru (lat, lon).
     * Fereastra = [apus - buffer, rasarit + buffer], peste miezul noptii.
     */
    fun isActive(cal: Calendar, lat: Double?, lon: Double?, bufferMin: Int = DEFAULT_BUFFER_MIN): Boolean {
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (lat == null || lon == null) {
            // fallback ore fixe (fereastra peste miezul noptii)
            val s = FALLBACK_START_HOUR * 60
            val e = FALLBACK_END_HOUR * 60
            return nowMin >= s || nowMin < e
        }
        val ss = sunriseSunsetLocalMin(cal, lat, lon) ?: return nowMin >= FALLBACK_START_HOUR * 60 || nowMin < FALLBACK_END_HOUR * 60
        val (sunrise, sunset) = ss
        val startMin = (sunset - bufferMin + 1440) % 1440      // putin inainte de apus
        val endMin = (sunrise + bufferMin) % 1440              // putin dupa rasarit
        // fereastra trece peste miezul noptii (start seara, end dimineata)
        return if (startMin > endMin) nowMin >= startMin || nowMin < endMin
        else nowMin in startMin until endMin
    }

    fun isActiveNow(lat: Double?, lon: Double?): Boolean =
        isActive(Calendar.getInstance(), lat, lon)
}

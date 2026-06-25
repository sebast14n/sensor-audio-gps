package com.example.logger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Spectrograma live rulanta (ce „aude" microfonul) + vumetru + avertizare clipping.
 * Apeleaza push(samples, n) din thread-ul audio; deseneaza coloana cea mai noua in dreapta si
 * deruleaza la stanga. FFT radix-2 N=512. Afiseaza 0..~12 kHz (unde-s majoritatea pasarilor).
 */
class SpectrogramView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var bmp: Bitmap? = null
    private var bc: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val N = 512
    private val half = N / 2
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val hann = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * Math.PI * it / (N - 1))).toFloat() }
    private val buf = FloatArray(N)          // ultimele N esantioane
    private var filled = 0

    @Volatile var peak = 0f                   // 0..1 (pt vumetru)
    @Volatile var clipping = false
    private val SR = 48000
    private val MAXHZ = 12000
    private val maxBin = (MAXHZ.toLong() * N / SR).toInt().coerceAtMost(half)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w > 0 && h > 0) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bc = Canvas(bmp!!)
            bc!!.drawColor(0xFF0A1628.toInt())
        }
    }

    /** culoare magma-ish pentru o valoare 0..1 */
    private fun magma(v: Float): Int {
        val x = v.coerceIn(0f, 1f)
        val r = (255 * x.coerceIn(0f, 1f)).toInt()
        val g = (255 * (x * x)).toInt()
        val b = (255 * (0.3f + 0.7f * x) * (1f - x)).toInt().coerceIn(0, 255)
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b)
    }

    fun push(samples: ShortArray, n: Int) {
        // peak + clipping din blocul brut
        var pk = 0f
        var clip = false
        for (i in 0 until n) {
            val a = abs(samples[i].toInt())
            if (a > pk) pk = a.toFloat()
            if (a >= 32000) clip = true
        }
        peak = pk / 32768f
        clipping = clip

        // tine ultimele N esantioane intr-un buffer glisant
        if (n >= N) {
            for (k in 0 until N) buf[k] = samples[n - N + k].toFloat() / 32768f
            filled = N
        } else {
            System.arraycopy(buf, n, buf, 0, N - n)
            for (k in 0 until n) buf[N - n + k] = samples[k].toFloat() / 32768f
            filled = minOf(N, filled + n)
        }
        if (filled < N) return

        for (k in 0 until N) { re[k] = buf[k] * hann[k]; im[k] = 0f }
        fft()

        val b = bmp ?: return
        val c = bc ?: return
        val w = b.width; val h = b.height
        if (w <= 1 || h <= 1) return
        // deruleaza la stanga cu 2 px
        val step = 2
        c.drawBitmap(b, Rect(step, 0, w, h), Rect(0, 0, w - step, h), null)
        // coloana noua in dreapta
        for (y in 0 until h) {
            val bin = (maxBin.toFloat() * (h - 1 - y) / (h - 1)).toInt().coerceIn(1, maxBin)
            val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            val dbv = (ln((mag + 1e-6f).toDouble()).toFloat() + 9f) / 11f   // ~normalizare log
            paint.color = magma(dbv)
            c.drawRect((w - step).toFloat(), y.toFloat(), w.toFloat(), (y + 1).toFloat(), paint)
        }
        postInvalidate()
    }

    private fun fft() {
        // bit-reversal
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= N) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat(); val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun onDraw(c: Canvas) {
        bmp?.let { c.drawBitmap(it, 0f, 0f, null) }
        val w = width; val h = height
        // vumetru jos
        val barH = (h * 0.06f).coerceAtLeast(6f)
        paint.color = 0x66000000
        c.drawRect(0f, h - barH, w.toFloat(), h.toFloat(), paint)
        val lv = peak.coerceIn(0f, 1f)
        paint.color = when {
            clipping -> 0xFFE53935.toInt()
            lv > 0.7f -> 0xFFFFB300.toInt()
            lv < 0.03f -> 0xFF455A64.toInt()
            else -> 0xFF43A047.toInt()
        }
        c.drawRect(0f, h - barH, w * lv, h.toFloat(), paint)
        if (clipping) {
            paint.color = Color.WHITE; paint.textSize = barH * 0.9f
            c.drawText("CLIPPING — prea tare / prea aproape", 8f, h - barH * 0.15f, paint)
        } else if (lv < 0.03f) {
            paint.color = 0xFFB0BEC5.toInt(); paint.textSize = barH * 0.9f
            c.drawText("semnal slab", 8f, h - barH * 0.15f, paint)
        }
    }
}

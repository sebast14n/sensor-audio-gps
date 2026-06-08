package com.example.logger

import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Identificare păsări LOCAL (pe telefon), tip „Merlin Sound ID", cu modelul BirdNET GLOBAL 6K V2.4.
 *
 * - Intrare model: 144000 esantioane float32 (3.0 s @ 48 kHz), mono, amplitudine [-1, 1].
 * - Iesire: ~6522 logit-uri (cate unul per clasa) -> sigmoid -> scor [0,1].
 * - Etichete: linii „Scientific_Common" (folosim varianta RO de pe noze.ro), index = clasa.
 *
 * Modelul (CC BY-NC-SA 4.0, necomercial) si etichetele se descarca o data de pe noze.ro
 * (vezi LiveListenActivity) si raman in stocarea privata a aplicatiei.
 */
class BirdNetClassifier private constructor(
    private val interpreter: Interpreter,
    val labels: List<String>,
    val outSize: Int,
) {
    val inputSamples = 144000   // 3.0 s @ 48 kHz

    /** Daca e setat (lungime = outSize), restrange clasificarea la speciile permise
     *  (filtru geografic, ex. fauna din Romania). null = toate cele 6522 specii. */
    @Volatile var allowedMask: BooleanArray? = null

    private val inBuf: ByteBuffer =
        ByteBuffer.allocateDirect(4 * inputSamples).order(ByteOrder.nativeOrder())
    private val outBuf = Array(1) { FloatArray(outSize) }

    data class Pred(val index: Int, val sci: String, val common: String, val score: Float)

    /** Aplica sigmoid-ul BirdNET (clip [-15,15] ca in BirdNET-Analyzer). */
    private fun sigmoid(x: Float): Float {
        val c = if (x < -15f) -15f else if (x > 15f) 15f else x
        return 1f / (1f + exp(-c))
    }

    /**
     * Clasifica o fereastra de esantioane float32 [-1,1]. Daca e mai scurta decat 3 s,
     * se completeaza cu zero (padding). Intoarce primele [topK] specii cu scor >= [minScore].
     */
    @Synchronized
    fun classify(samples: FloatArray, topK: Int = 5, minScore: Float = 0.10f): List<Pred> {
        inBuf.rewind()
        val n = if (samples.size < inputSamples) samples.size else inputSamples
        var i = 0
        while (i < n) { inBuf.putFloat(samples[i]); i++ }
        while (i < inputSamples) { inBuf.putFloat(0f); i++ }
        inBuf.rewind()
        interpreter.run(inBuf, outBuf)
        val logits = outBuf[0]

        // top-K fara sortarea intregului vector (minim de garbage)
        val k = if (topK < 1) 1 else topK
        val bestIdx = IntArray(k) { -1 }
        val bestScore = FloatArray(k) { Float.NEGATIVE_INFINITY }
        val mask = allowedMask
        for (j in logits.indices) {
            if (mask != null && j < mask.size && !mask[j]) continue   // filtru geografic
            val s = sigmoid(logits[j])
            if (s > bestScore[k - 1]) {
                var p = k - 1
                while (p > 0 && bestScore[p - 1] < s) {
                    bestScore[p] = bestScore[p - 1]; bestIdx[p] = bestIdx[p - 1]; p--
                }
                bestScore[p] = s; bestIdx[p] = j
            }
        }

        val out = ArrayList<Pred>(k)
        for (r in 0 until k) {
            val idx = bestIdx[r]
            if (idx < 0 || bestScore[r] < minScore) break
            val lab = if (idx < labels.size) labels[idx] else "clasa_$idx"
            val us = lab.indexOf('_')
            val sci = if (us >= 0) lab.substring(0, us) else lab
            val com = if (us >= 0) lab.substring(us + 1) else ""
            out.add(Pred(idx, sci.trim(), com.trim(), bestScore[r]))
        }
        return out
    }

    fun close() { try { interpreter.close() } catch (_: Exception) {} }

    companion object {
        /** Incarca modelul (.tflite, memory-mapped) + etichetele (o linie/clasa, ordine = index).
         *  allowedFile (optional) = indici de clase permise (un int/linie) -> filtru geografic. */
        fun load(modelFile: File, labelsFile: File, allowedFile: File? = null, threads: Int = 2): BirdNetClassifier {
            val opts = Interpreter.Options().apply { setNumThreads(threads) }
            val mapped = FileInputStream(modelFile).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
            }
            val interp = Interpreter(mapped, opts)
            val outShape = interp.getOutputTensor(0).shape()          // [1, N]
            val outN = if (outShape.size >= 2) outShape[outShape.size - 1] else outShape[0]
            // pastram TOATE liniile in ordine (index-ul de clasa = numarul liniei); fara filtrare interna
            val raw = labelsFile.readText().split("\n")
            val lines = ArrayList<String>(raw.size)
            for (l in raw) lines.add(l)
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            val c = BirdNetClassifier(interp, lines, outN)
            if (allowedFile != null && allowedFile.exists()) {
                try {
                    val mask = BooleanArray(outN)
                    for (ln in allowedFile.readText().split("\n")) {
                        val i = ln.trim().toIntOrNull() ?: continue
                        if (i in 0 until outN) mask[i] = true
                    }
                    if (mask.any { it }) c.allowedMask = mask
                } catch (_: Exception) {}
            }
            return c
        }
    }
}

package com.example.logger

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Captura unui segment audio LOSSLESS din microfon via AudioRecord (PCM 16-bit, sursa UNPROCESSED).
 *
 * - WAV (implicit): scriere directa a PCM + header WAV — 100% fiabil, identic cu Song Meter.
 * - FLAC (experimental): PCM -> encoder MediaCodec "audio/flac" -> fisier .flac (header din CSD).
 *   Daca encoderul FLAC nu poate fi pornit pe dispozitiv, cade automat pe WAV (nu se pierde nimic).
 *
 * O instanta = un segment. start() porneste captura intr-un thread; stop() finalizeaza fisierul.
 */
class AudioSegmentRecorder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    private val preferFlac: Boolean = false,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var ar: AudioRecord? = null
    var outFile: File? = null; private set

    /** Porneste captura. Intoarce fisierul efectiv scris (.flac sau .wav), sau null la esec. */
    fun start(baseNoExt: File): File? {
        val chMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val rec = try {
            AudioRecord(source, sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) { null }
        // fallback la MIC daca UNPROCESSED nu se initializeaza
        ar = if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            try { rec?.release() } catch (_: Exception) {}
            try {
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, chMask,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            } catch (e: Exception) { null }
        }
        val a = ar ?: return null
        if (a.state != AudioRecord.STATE_INITIALIZED) { try { a.release() } catch (_: Exception) {}; ar = null; return null }

        // pregateste encoderul FLAC daca e cerut; altfel WAV
        val codec = if (preferFlac) tryCreateFlac() else null
        val readChunk = 8192
        running = true
        try { a.startRecording() } catch (e: Exception) { stop(); return null }

        if (codec != null) {
            outFile = File(baseNoExt.parentFile, baseNoExt.name + ".flac")
            thread = Thread { runFlac(a, codec, outFile!!, readChunk) }.also { it.start() }
        } else {
            outFile = File(baseNoExt.parentFile, baseNoExt.name + ".wav")
            thread = Thread { runWav(a, outFile!!, readChunk) }.also { it.start() }
        }
        return outFile
    }

    fun stop() {
        running = false
        try { thread?.join(3000) } catch (_: Exception) {}
        thread = null
        try { ar?.stop() } catch (_: Exception) {}
        try { ar?.release() } catch (_: Exception) {}
        ar = null
    }

    // ── WAV ──
    private fun runWav(a: AudioRecord, file: File, readChunk: Int) {
        val raf = try { RandomAccessFile(file, "rw") } catch (e: Exception) { return }
        try {
            writeWavHeader(raf, 0)         // placeholder, patch la final
            val buf = ByteArray(readChunk)
            var dataLen = 0L
            while (running) {
                val n = a.read(buf, 0, buf.size)
                if (n > 0) { raf.write(buf, 0, n); dataLen += n }
            }
            // patch dimensiuni
            raf.seek(4);  writeIntLE(raf, (36 + dataLen).toInt())
            raf.seek(40); writeIntLE(raf, dataLen.toInt())
        } catch (_: Exception) {
        } finally { try { raf.close() } catch (_: Exception) {} }
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int) {
        val byteRate = sampleRate * channels * 2
        raf.write("RIFF".toByteArray()); writeIntLE(raf, 36 + dataLen)
        raf.write("WAVE".toByteArray()); raf.write("fmt ".toByteArray())
        writeIntLE(raf, 16); writeShortLE(raf, 1); writeShortLE(raf, channels)
        writeIntLE(raf, sampleRate); writeIntLE(raf, byteRate)
        writeShortLE(raf, channels * 2); writeShortLE(raf, 16)
        raf.write("data".toByteArray()); writeIntLE(raf, dataLen)
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Int) {
        raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))
    }
    private fun writeShortLE(raf: RandomAccessFile, v: Int) {
        raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
    }

    // ── FLAC ──
    private fun tryCreateFlac(): MediaCodec? {
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
            fmt.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            c
        } catch (e: Exception) { null }
    }

    private fun runFlac(a: AudioRecord, codec: MediaCodec, file: File, readChunk: Int) {
        val fos = try { FileOutputStream(file) } catch (e: Exception) { return }
        val info = MediaCodec.BufferInfo()
        var headerWritten = false
        var totalSamples = 0L
        var eosSent = false
        val pcm = ByteArray(readChunk)
        try {
            var sawEos = false
            while (!sawEos) {
                if (running) {
                    val n = a.read(pcm, 0, pcm.size)
                    if (n > 0) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val ib = codec.getInputBuffer(inIdx)
                            ib?.clear()
                            val take = if (ib != null) minOf(n, ib.remaining()) else n
                            ib?.put(pcm, 0, take)
                            val ptsUs = totalSamples * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inIdx, 0, take, ptsUs, 0)
                            totalSamples += take / (2L * channels)
                        }
                    }
                } else if (!eosSent) {
                    // semnaleaza EOS o SINGURA data, apoi doar dreneaza pana la EOS pe iesire
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0,
                            totalSamples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    }
                }
                // dreneaza iesirea
                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx < 0) break
                    val ob = codec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        ob.position(info.offset); ob.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (!headerWritten) { fos.write(flacHeader(bytes)); headerWritten = true }
                        } else {
                            fos.write(bytes)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { sawEos = true; break }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { fos.flush(); fos.close() } catch (_: Exception) {}
        }
    }

    /** Construieste header-ul de stream FLAC din CSD. Defensiv: daca CSD incepe deja cu "fLaC",
     *  e gata; altfel adauga marcajul + antetul metadata-block STREAMINFO (34 octeti). */
    private fun flacHeader(csd: ByteArray): ByteArray {
        val marker = byteArrayOf(0x66.toByte(), 0x4C.toByte(), 0x61.toByte(), 0x43.toByte()) // "fLaC"
        if (csd.size >= 4 && csd[0] == marker[0] && csd[1] == marker[1] &&
            csd[2] == marker[2] && csd[3] == marker[3]) return csd
        // STREAMINFO: ultim bloc metadata (0x80) | tip 0; lungime 34 = 0x000022
        val blockHdr = byteArrayOf(0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x22.toByte())
        return marker + blockHdr + csd
    }
}

package com.example.pulseplayer.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Все настройки DSP одним неизменяемым объектом (безопасно читать из аудио-потока). */
data class DspParams(
    val enabled: Boolean = false,
    val gains: List<Float> = List(BAND_FREQS.size) { 0f },
    val preamp: Float = 0f,
    val bass: Float = 0f,
    val treble: Float = 0f,
    val width: Float = 1f,
    val balance: Float = 0f,
    val autoPreamp: Boolean = true,
    val limiter: Boolean = true,
    val mono: Boolean = false,
    val swap: Boolean = false,
) {
    companion object {
        /** 12 полос графического эквалайзера, Гц */
        val BAND_FREQS = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 6000, 8000, 12000, 16000)
        const val MAX_DB = 12f
    }
}

/**
 * Собственный DSP: 12-полосный эквалайзер (biquad peaking), полки баса/высоких, предусиление,
 * стерео-база, баланс, моно, обмен каналов, мягкий лимитер. Работает с PCM 16 bit и float.
 */
@OptIn(UnstableApi::class)
class DspProcessor : BaseAudioProcessor() {

    @Volatile var params: DspParams = DspParams()

    private var applied: DspParams? = null
    private var channels = 2
    private var sampleRate = 44100
    private var isFloat = false

    private val maxStages = DspParams.BAND_FREQS.size + 2
    private val coef = DoubleArray(maxStages * 5)
    private var stages = 0
    private val states = Array(8) { DoubleArray(maxStages * 2) }
    private var preGain = 1.0
    private val frame = FloatArray(8)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) ||
            inputAudioFormat.channelCount !in 1..8
        ) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        isFloat = enc == C.ENCODING_PCM_FLOAT
        states.forEach { it.fill(0.0) }
        applied = null
        return inputAudioFormat
    }

    @Volatile private var failed = false

    /** Последний выданный нами буфер: если он же приходит на вход, его нельзя очищать/копировать сам в себя. */
    private var lastOut: ByteBuffer? = null

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Пустой вход (в т.ч. AudioProcessor.EMPTY_BUFFER): обрабатывать нечего. Без этого
        // replaceOutputBuffer(0) возвращает тот же EMPTY_BUFFER, и put() копирует буфер сам в себя.
        if (!inputBuffer.hasRemaining()) return
        if (inputBuffer === lastOut) {
            // Вход — наш собственный выходной буфер (повторный проход): данные уже обработаны.
            // Копируем во временный буфер, пока replaceOutputBuffer не переиспользовал память.
            val copy = ByteBuffer.allocate(inputBuffer.remaining()).order(inputBuffer.order())
            copy.put(inputBuffer).flip()
            passthrough(copy)
            return
        }
        val p = params
        if (!p.enabled || failed) {
            passthrough(inputBuffer)
            return
        }
        val start = inputBuffer.position()
        try {
            process(inputBuffer, p)
        } catch (t: Throwable) {
            // Любой сбой в DSP не должен ронять воспроизведение: обходим обработку.
            Log.e("PulseDsp", "DSP failed, bypassing", t)
            failed = true
            inputBuffer.position(start)
            passthrough(inputBuffer)
        }
    }

    private fun passthrough(input: ByteBuffer) {
        val out = replaceOutputBuffer(input.remaining())
        out.put(input).flip()
        lastOut = out
    }

    private fun process(inputBuffer: ByteBuffer, p: DspParams) {
        refresh(p)

        val bps = if (isFloat) 4 else 2
        val frames = inputBuffer.remaining() / (bps * channels)
        val out = replaceOutputBuffer(frames * bps * channels)
        var pos = inputBuffer.position()

        val gl = if (p.balance > 0f) 1f - p.balance else 1f
        val gr = if (p.balance < 0f) 1f + p.balance else 1f
        val stereo = channels == 2

        repeat(frames) {
            for (c in 0 until channels) {
                frame[c] = if (isFloat) inputBuffer.getFloat(pos) else inputBuffer.getShort(pos) / 32768f
                pos += bps
            }
            for (c in 0 until channels) {
                var x = frame[c].toDouble()
                val st = states[c]
                var k = 0
                while (k < stages) {
                    val o = k * 5
                    val y = coef[o] * x + st[k * 2]
                    st[k * 2] = coef[o + 1] * x - coef[o + 3] * y + st[k * 2 + 1]
                    st[k * 2 + 1] = coef[o + 2] * x - coef[o + 4] * y
                    x = y
                    k++
                }
                frame[c] = (x * preGain).toFloat()
            }
            if (stereo) {
                var l = frame[0]
                var r = frame[1]
                if (p.swap) { val t = l; l = r; r = t }
                val mid = (l + r) * 0.5f
                val side = if (p.mono) 0f else (l - r) * 0.5f * p.width
                frame[0] = (mid + side) * gl
                frame[1] = (mid - side) * gr
            }
            for (c in 0 until channels) {
                val y = limit(frame[c], p.limiter)
                if (isFloat) out.putFloat(y)
                else out.putShort((y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(inputBuffer.position() + frames * bps * channels)
        out.flip()
        lastOut = out
    }

    private fun limit(x: Float, soft: Boolean): Float {
        if (!soft) return x.coerceIn(-1f, 1f)
        val a = abs(x)
        if (a <= 0.7f) return x
        val y = 0.7f + 0.3f * tanh((a - 0.7f) / 0.3f)
        return if (x < 0f) -y else y
    }

    private fun refresh(p: DspParams) {
        if (applied === p) return
        applied = p
        val fs = sampleRate.toDouble()
        var n = 0
        fun put(b: DoubleArray) { b.copyInto(coef, n * 5); n++ }

        DspParams.BAND_FREQS.forEachIndexed { i, f ->
            val g = p.gains.getOrElse(i) { 0f }
            if (abs(g) > 0.05f && f < fs * 0.45) put(peaking(f.toDouble(), g.toDouble(), 1.4, fs))
        }
        if (abs(p.bass) > 0.05f) put(lowShelf(100.0, p.bass.toDouble(), fs))
        if (abs(p.treble) > 0.05f && 10000.0 < fs * 0.45) put(highShelf(10000.0, p.treble.toDouble(), fs))
        stages = n

        var db = p.preamp
        if (p.autoPreamp) {
            val boost = max(p.gains.maxOrNull() ?: 0f, max(p.bass, p.treble)).coerceAtLeast(0f)
            db -= boost * 0.7f
        }
        preGain = 10.0.pow(db / 20.0)
    }

    private fun peaking(f: Double, gainDb: Double, q: Double, fs: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha / a
        return doubleArrayOf(
            (1 + alpha * a) / a0, (-2 * cw) / a0, (1 - alpha * a) / a0,
            (-2 * cw) / a0, (1 - alpha / a) / a0,
        )
    }

    private fun lowShelf(f: Double, gainDb: Double, fs: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f / fs
        val cw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val t = 2 * sqrt(a) * alpha
        val b0 = a * ((a + 1) - (a - 1) * cw + t)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cw)
        val b2 = a * ((a + 1) - (a - 1) * cw - t)
        val a0 = (a + 1) + (a - 1) * cw + t
        val a1 = -2 * ((a - 1) + (a + 1) * cw)
        val a2 = (a + 1) + (a - 1) * cw - t
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun highShelf(f: Double, gainDb: Double, fs: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f / fs
        val cw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val t = 2 * sqrt(a) * alpha
        val b0 = a * ((a + 1) + (a - 1) * cw + t)
        val b1 = -2 * a * ((a - 1) + (a + 1) * cw)
        val b2 = a * ((a + 1) + (a - 1) * cw - t)
        val a0 = (a + 1) - (a - 1) * cw + t
        val a1 = 2 * ((a - 1) - (a + 1) * cw)
        val a2 = (a + 1) - (a - 1) * cw - t
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

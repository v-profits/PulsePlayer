package com.example.pulseplayer.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Управление звуком: собственный DSP ([DspProcessor], 12 полос + крутилки + переключатели)
 * и системные эффекты Virtualizer / LoudnessEnhancer. Живёт в одном процессе с PlaybackService.
 */
object EqualizerManager {

    val dsp = DspProcessor()

    /** Пресеты для 12 полос: 32, 64, 125, 250, 500, 1k, 2k, 4k, 6k, 8k, 12k, 16k (дБ). Порядок = R.array.eq_preset_names */
    val PRESETS: List<FloatArray> = listOf(
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        floatArrayOf(6f, 5.5f, 4.5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 3.5f, 4.5f, 5f, 5.5f),
        floatArrayOf(4.5f, 3.5f, 2.5f, 1f, -1f, -1.5f, 0f, 1.5f, 2.5f, 3.5f, 4f, 4f),
        floatArrayOf(-1.5f, -1f, 0f, 1.5f, 3f, 3.5f, 2.5f, 1f, 0f, -0.5f, -1f, -1.5f),
        floatArrayOf(3f, 2.5f, 1.5f, 2f, -1.5f, -1.5f, 0f, 1f, 2f, 2.5f, 3f, 3.5f),
        floatArrayOf(4f, 3.5f, 3f, 2.5f, -1f, -1f, 0f, 1.5f, 2.5f, 3f, 3.5f, 4f),
        floatArrayOf(-3f, -2.5f, -1.5f, 0.5f, 2.5f, 3.5f, 3.5f, 2.5f, 1f, 0f, -1f, -2f),
        floatArrayOf(5f, 4.5f, 2f, 0f, -2f, -1f, 1f, 0f, 2f, 3.5f, 4.5f, 5f),
        floatArrayOf(5.5f, 5f, 3f, 3.5f, -1f, -1f, 1f, -0.5f, 1.5f, 2f, 2.5f, 3f),
        floatArrayOf(3f, 3f, 2.5f, 1.5f, 2f, 2f, 2.5f, 2.5f, 2.5f, 2f, 2.5f, 2f),
        floatArrayOf(6f, 5f, 3f, 0f, -1f, -1f, -1f, 0f, 1.5f, 4f, 5f, 5.5f),
    )

    data class State(
        val params: DspParams = DspParams(),
        val preset: Int = -1,
        val virtualizer: Int = 0,   // 0..1000
        val loudness: Int = 0,      // 0..1000 мБ
        val virtSupported: Boolean = false,
        val loudSupported: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var vz: Virtualizer? = null
    private var le: LoudnessEnhancer? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("dsp", Context.MODE_PRIVATE)
        prefs = p
        val n = DspParams.BAND_FREQS.size
        val saved = p.getString("gains", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        val params = DspParams(
            enabled = p.getBoolean("enabled", false),
            gains = if (saved != null && saved.size == n) saved else List(n) { 0f },
            preamp = p.getFloat("preamp", 0f),
            bass = p.getFloat("bass", 0f),
            treble = p.getFloat("treble", 0f),
            width = p.getFloat("width", 1f),
            balance = p.getFloat("balance", 0f),
            autoPreamp = p.getBoolean("auto", true),
            limiter = p.getBoolean("limiter", true),
            mono = p.getBoolean("mono", false),
            swap = p.getBoolean("swap", false),
        )
        dsp.params = params
        _state.value = State(
            params = params, preset = p.getInt("preset", -1),
            virtualizer = p.getInt("virt", 0), loudness = p.getInt("loud", 0),
        )
    }

    @Synchronized
    fun attach(sessionId: Int) {
        release()
        if (sessionId <= 0) return
        val s = _state.value
        var virtOk = false
        var loudOk = false
        runCatching {
            val v = Virtualizer(0, sessionId)
            vz = v
            if (v.strengthSupported) {
                v.setStrength(s.virtualizer.toShort()); v.enabled = s.virtualizer > 0; virtOk = true
            }
        }
        runCatching {
            val l = LoudnessEnhancer(sessionId)
            le = l
            l.setTargetGain(s.loudness); l.enabled = s.loudness > 0; loudOk = true
        }
        _state.value = _state.value.copy(virtSupported = virtOk, loudSupported = loudOk)
    }

    @Synchronized
    fun release() {
        runCatching { vz?.release() }; vz = null
        runCatching { le?.release() }; le = null
    }

    // ---------- изменение параметров ----------
    private fun edit(preset: Int? = null, f: (DspParams) -> DspParams) {
        val old = _state.value
        val np = f(old.params)
        dsp.params = np
        val pr = preset ?: old.preset
        _state.value = old.copy(params = np, preset = pr)
        save(np, pr)
    }

    fun setEnabled(on: Boolean) = edit { it.copy(enabled = on) }

    fun setBand(i: Int, db: Float) = edit(preset = -1) { p ->
        if (i !in p.gains.indices) p
        else p.copy(gains = p.gains.toMutableList().also { it[i] = db.coerceIn(-DspParams.MAX_DB, DspParams.MAX_DB) })
    }

    fun setPreset(index: Int) {
        val g = PRESETS.getOrNull(index) ?: return
        edit(preset = index) { it.copy(gains = g.toList()) }
    }

    fun reset() = edit(preset = 0) {
        it.copy(gains = List(DspParams.BAND_FREQS.size) { 0f }, preamp = 0f, bass = 0f, treble = 0f, width = 1f, balance = 0f)
    }

    fun setPreamp(v: Float) = edit { it.copy(preamp = v) }
    fun setBass(v: Float) = edit { it.copy(bass = v) }
    fun setTreble(v: Float) = edit { it.copy(treble = v) }
    fun setWidth(v: Float) = edit { it.copy(width = v) }
    fun setBalance(v: Float) = edit { it.copy(balance = v) }
    fun setAutoPreamp(v: Boolean) = edit { it.copy(autoPreamp = v) }
    fun setLimiter(v: Boolean) = edit { it.copy(limiter = v) }
    fun setMono(v: Boolean) = edit { it.copy(mono = v) }
    fun setSwap(v: Boolean) = edit { it.copy(swap = v) }

    fun setVirtualizer(v: Int) {
        runCatching { vz?.setStrength(v.toShort()); vz?.enabled = v > 0 }
        prefs?.edit()?.putInt("virt", v)?.apply()
        _state.value = _state.value.copy(virtualizer = v)
    }

    fun setLoudness(mB: Int) {
        runCatching { le?.setTargetGain(mB); le?.enabled = mB > 0 }
        prefs?.edit()?.putInt("loud", mB)?.apply()
        _state.value = _state.value.copy(loudness = mB)
    }

    private fun save(p: DspParams, preset: Int) {
        prefs?.edit()
            ?.putBoolean("enabled", p.enabled)
            ?.putString("gains", p.gains.joinToString(","))
            ?.putFloat("preamp", p.preamp)?.putFloat("bass", p.bass)?.putFloat("treble", p.treble)
            ?.putFloat("width", p.width)?.putFloat("balance", p.balance)
            ?.putBoolean("auto", p.autoPreamp)?.putBoolean("limiter", p.limiter)
            ?.putBoolean("mono", p.mono)?.putBoolean("swap", p.swap)
            ?.putInt("preset", preset)
            ?.apply()
    }
}

package com.example.pulseplayer.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Эквалайзер + бас + виртуализатор + усиление громкости. Живёт в одном процессе с PlaybackService. */
object EqualizerManager {

    data class State(
        val available: Boolean = false,
        val enabled: Boolean = false,
        val centerFreqsHz: List<Int> = emptyList(),
        val minLevel: Int = -1500,
        val maxLevel: Int = 1500,
        val levels: List<Int> = emptyList(),
        val presets: List<String> = emptyList(),
        val preset: Int = -1,
        val bass: Int = 0,
        val virtualizer: Int = 0,
        val loudness: Int = 0,
        val bassSupported: Boolean = false,
        val virtSupported: Boolean = false,
        val loudSupported: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var eq: Equalizer? = null
    private var bb: BassBoost? = null
    private var vz: Virtualizer? = null
    private var le: LoudnessEnhancer? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun attach(sessionId: Int) {
        release()
        val p = prefs ?: return
        if (sessionId <= 0) return
        var s = State()

        runCatching {
            val e = Equalizer(0, sessionId)
            eq = e
            val n = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            val saved = p.getString("levels", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
            val levels = if (saved != null && saved.size == n) saved else List(n) { 0 }
            e.enabled = p.getBoolean("enabled", false)
            levels.forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
            s = s.copy(
                available = true,
                enabled = e.enabled,
                centerFreqsHz = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 },
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                levels = levels,
                presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) },
                preset = p.getInt("preset", -1),
            )
        }
        runCatching {
            val b = BassBoost(0, sessionId)
            bb = b
            val v = p.getInt("bass", 0)
            if (b.strengthSupported) {
                b.setStrength(v.toShort()); b.enabled = v > 0
                s = s.copy(bassSupported = true, bass = v)
            }
        }
        runCatching {
            val v = Virtualizer(0, sessionId)
            vz = v
            val x = p.getInt("virt", 0)
            if (v.strengthSupported) {
                v.setStrength(x.toShort()); v.enabled = x > 0
                s = s.copy(virtSupported = true, virtualizer = x)
            }
        }
        runCatching {
            val l = LoudnessEnhancer(sessionId)
            le = l
            val g = p.getInt("loud", 0)
            l.setTargetGain(g); l.enabled = g > 0
            s = s.copy(loudSupported = true, loudness = g)
        }
        _state.value = s
    }

    @Synchronized
    fun release() {
        runCatching { eq?.release() }; eq = null
        runCatching { bb?.release() }; bb = null
        runCatching { vz?.release() }; vz = null
        runCatching { le?.release() }; le = null
    }

    fun setEnabled(on: Boolean) {
        runCatching { eq?.enabled = on }
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
        _state.update { it.copy(enabled = on) }
    }

    fun setBand(index: Int, level: Int) {
        val s = _state.value
        if (index !in s.levels.indices) return
        val l = level.coerceIn(s.minLevel, s.maxLevel)
        runCatching { eq?.setBandLevel(index.toShort(), l.toShort()) }
        val nl = s.levels.toMutableList().also { it[index] = l }
        saveLevels(nl, -1)
        _state.update { it.copy(levels = nl, preset = -1) }
    }

    fun usePreset(index: Int) {
        val e = eq ?: return
        runCatching {
            e.usePreset(index.toShort())
            val nl = (0 until e.numberOfBands.toInt()).map { e.getBandLevel(it.toShort()).toInt() }
            saveLevels(nl, index)
            _state.update { it.copy(levels = nl, preset = index) }
        }
    }

    fun reset() {
        val n = _state.value.levels.size
        val zeros = List(n) { 0 }
        zeros.forEachIndexed { i, l -> runCatching { eq?.setBandLevel(i.toShort(), l.toShort()) } }
        saveLevels(zeros, -1)
        _state.update { it.copy(levels = zeros, preset = -1) }
    }

    fun setBass(v: Int) {
        runCatching { bb?.setStrength(v.toShort()); bb?.enabled = v > 0 }
        prefs?.edit()?.putInt("bass", v)?.apply()
        _state.update { it.copy(bass = v) }
    }

    fun setVirtualizer(v: Int) {
        runCatching { vz?.setStrength(v.toShort()); vz?.enabled = v > 0 }
        prefs?.edit()?.putInt("virt", v)?.apply()
        _state.update { it.copy(virtualizer = v) }
    }

    fun setLoudness(mB: Int) {
        runCatching { le?.setTargetGain(mB); le?.enabled = mB > 0 }
        prefs?.edit()?.putInt("loud", mB)?.apply()
        _state.update { it.copy(loudness = mB) }
    }

    private fun saveLevels(levels: List<Int>, preset: Int) {
        prefs?.edit()?.putString("levels", levels.joinToString(","))?.putInt("preset", preset)?.apply()
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.pulseplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pulseplayer.R
import com.example.pulseplayer.audio.DspParams
import com.example.pulseplayer.audio.EqualizerManager
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val s by EqualizerManager.state.collectAsStateWithLifecycle()
    val p = s.params
    val names = stringArrayResource(R.array.eq_preset_names)
    val half = DspParams.BAND_FREQS.size / 2

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.equalizer)) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                TextButton(onClick = { EqualizerManager.reset() }) { Text(stringResource(R.string.eq_reset)) }
                Switch(
                    checked = p.enabled, onCheckedChange = { EqualizerManager.setEnabled(it) },
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.eq_presets), style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.preset == -1) item {
                    FilterChip(selected = true, onClick = {}, label = { Text(stringResource(R.string.eq_custom)) })
                }
                itemsIndexed(names.toList()) { i, name ->
                    FilterChip(selected = s.preset == i, onClick = { EqualizerManager.setPreset(i) }, label = { Text(name) })
                }
            }

            // Полосы: два ряда по 6 — крупные цели для пальца
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp).alpha(if (p.enabled) 1f else 0.55f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EqCurve(p.gains, Modifier.fillMaxWidth().height(90.dp))
                    BandRow(0 until half, p.gains)
                    HorizontalDivider()
                    BandRow(half until DspParams.BAND_FREQS.size, p.gains)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp).alpha(if (p.enabled) 1f else 0.55f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.eq_knobs), style = MaterialTheme.typography.titleSmall)
                    ParamSlider(stringResource(R.string.eq_preamp), dbLabel(p.preamp) + " dB", p.preamp, -12f..12f, 0f) {
                        EqualizerManager.setPreamp(it.half())
                    }
                    ParamSlider(stringResource(R.string.eq_bass_knob), dbLabel(p.bass) + " dB", p.bass, -12f..12f, 0f) {
                        EqualizerManager.setBass(it.half())
                    }
                    ParamSlider(stringResource(R.string.eq_treble), dbLabel(p.treble) + " dB", p.treble, -12f..12f, 0f) {
                        EqualizerManager.setTreble(it.half())
                    }
                    ParamSlider(stringResource(R.string.eq_width), "${(p.width * 100).roundToInt()}%", p.width, 0f..2f, 1f) {
                        EqualizerManager.setWidth((it * 20).roundToInt() / 20f)
                    }
                    ParamSlider(stringResource(R.string.eq_balance), balanceLabel(p.balance), p.balance, -1f..1f, 0f) {
                        EqualizerManager.setBalance((it * 20).roundToInt() / 20f)
                    }
                    if (s.virtSupported) ParamSlider(
                        stringResource(R.string.eq_surround), "${s.virtualizer / 10}%", s.virtualizer / 10f, 0f..100f, 0f,
                    ) { EqualizerManager.setVirtualizer((it * 10).roundToInt()) }
                    if (s.loudSupported) ParamSlider(
                        stringResource(R.string.eq_loud_knob), "+%.1f dB".format(s.loudness / 100f), s.loudness / 100f, 0f..10f, 0f,
                    ) { EqualizerManager.setLoudness((it * 100).roundToInt()) }
                    Text(stringResource(R.string.eq_hint), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SwitchRow(R.string.eq_auto_preamp, p.autoPreamp) { EqualizerManager.setAutoPreamp(it) }
                    SwitchRow(R.string.eq_limiter, p.limiter) { EqualizerManager.setLimiter(it) }
                    SwitchRow(R.string.eq_mono, p.mono) { EqualizerManager.setMono(it) }
                    SwitchRow(R.string.eq_swap, p.swap) { EqualizerManager.setSwap(it) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun Float.half(): Float = (this * 2).roundToInt() / 2f

private fun dbLabel(v: Float): String = "%+.1f".format(v).replace(".0", "")
private fun balanceLabel(v: Float): String = when {
    v < -0.005f -> "L ${(-v * 100).roundToInt()}"
    v > 0.005f -> "R ${(v * 100).roundToInt()}"
    else -> "C"
}
private fun freqLabel(hz: Int): String =
    if (hz >= 1000) "%.1fk".format(hz / 1000f).replace(".0k", "k") else "$hz"

@Composable
private fun BandRow(indices: IntRange, gains: List<Float>) {
    Row(Modifier.fillMaxWidth()) {
        for (i in indices) {
            val g = gains.getOrElse(i) { 0f }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dbLabel(g), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                BandSlider(g, { EqualizerManager.setBand(i, it) }, Modifier.fillMaxWidth().height(160.dp))
                Text(freqLabel(DspParams.BAND_FREQS[i]), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String, display: String, value: Float, range: ClosedFloatingPointRange<Float>,
    default: Float, onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { onChange(default) }) { Text(display) }
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun EqCurve(gains: List<Float>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val max = DspParams.MAX_DB
    Canvas(modifier) {
        if (gains.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        fun x(i: Int) = w * (i + 0.5f) / gains.size
        fun y(g: Float) = h * (1f - (g + max) / (2 * max))
        val path = Path().apply {
            moveTo(0f, y(gains.first()))
            lineTo(x(0), y(gains[0]))
            for (i in 1 until gains.size) {
                val mx = (x(i - 1) + x(i)) / 2f
                cubicTo(mx, y(gains[i - 1]), mx, y(gains[i]), x(i), y(gains[i]))
            }
            lineTo(w, y(gains.last()))
        }
        val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
        drawLine(color.copy(alpha = 0.25f), Offset(0f, y(0f)), Offset(w, y(0f)), strokeWidth = 1.dp.toPx())
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Вертикальный бегунок полосы: ±12 дБ, шаг 0.5 дБ, двойное касание — в ноль. */
@Composable
private fun BandSlider(gain: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val max = DspParams.MAX_DB
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.outline
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentOnChange(0f) }) }
            .pointerInput(Unit) {
                val pad = 14.dp.toPx()
                fun toGain(y: Float): Float {
                    val f = 1f - ((y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                    return (((-max + f * 2 * max) * 2).roundToInt() / 2f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentOnChange(toGain(down.position.y))
                    drag(down.id) { ch ->
                        currentOnChange(toGain(ch.position.y))
                        ch.consume()
                    }
                }
            }
    ) {
        val pad = 14.dp.toPx()
        val usable = size.height - 2 * pad
        val cx = size.width / 2
        fun yOf(g: Float) = pad + usable * (1f - (g + max) / (2 * max))
        val sw = 6.dp.toPx()
        drawLine(trackColor, Offset(cx, pad), Offset(cx, pad + usable), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tickColor, Offset(cx - 9.dp.toPx(), yOf(0f)), Offset(cx + 9.dp.toPx(), yOf(0f)), strokeWidth = 1.5.dp.toPx())
        drawLine(activeColor, Offset(cx, yOf(0f)), Offset(cx, yOf(gain)), strokeWidth = sw, cap = StrokeCap.Round)
        drawCircle(activeColor, 12.dp.toPx(), Offset(cx, yOf(gain)))
    }
}

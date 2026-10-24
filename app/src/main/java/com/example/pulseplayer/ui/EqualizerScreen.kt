@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.pulseplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pulseplayer.R
import com.example.pulseplayer.audio.EqualizerManager
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val s by EqualizerManager.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.equalizer)) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (s.available) Switch(
                    checked = s.enabled, onCheckedChange = { EqualizerManager.setEnabled(it) },
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
        )
        if (!s.available) {
            EmptyState(Icons.Rounded.Tune, stringResource(R.string.eq_unavailable))
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)
                .alpha(if (s.enabled) 1f else 0.55f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (s.presets.isNotEmpty()) {
                Text(stringResource(R.string.eq_presets), style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = s.preset == -1, onClick = {}, label = { Text(stringResource(R.string.eq_custom)) })
                    }
                    itemsIndexed(s.presets) { i, name ->
                        FilterChip(selected = s.preset == i, onClick = { EqualizerManager.usePreset(i) }, label = { Text(name) })
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.eq_bands), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { EqualizerManager.reset() }) { Text(stringResource(R.string.eq_reset)) }
            }

            EqCurve(s.levels, s.minLevel, s.maxLevel, MaterialTheme.colorScheme.primary,
                Modifier.fillMaxWidth().height(110.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                s.levels.forEachIndexed { i, level ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%+.0f".format(level / 100f), style = MaterialTheme.typography.labelSmall)
                        BandSlider(level, s.minLevel, s.maxLevel, s.enabled, { EqualizerManager.setBand(i, it) },
                            Modifier.height(200.dp))
                        Text(freqLabel(s.centerFreqsHz.getOrElse(i) { 0 }), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (s.bassSupported) EffectSlider(R.string.eq_bass, s.bass / 10f, "${s.bass / 10}%", s.enabled) {
                EqualizerManager.setBass((it * 10).roundToInt())
            }
            if (s.virtSupported) EffectSlider(R.string.eq_virtualizer, s.virtualizer / 10f, "${s.virtualizer / 10}%", s.enabled) {
                EqualizerManager.setVirtualizer((it * 10).roundToInt())
            }
            if (s.loudSupported) EffectSlider(R.string.eq_loudness, s.loudness / 10f, "+%.1f dB".format(s.loudness / 100f), s.enabled) {
                EqualizerManager.setLoudness((it * 10).roundToInt())
            }
        }
    }
}

private fun freqLabel(hz: Int): String =
    if (hz >= 1000) "%.1fk".format(hz / 1000f).replace(".0k", "k") else "$hz"

@Composable
private fun EffectSlider(label: Int, value: Float, display: String, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..100f, enabled = enabled)
    }
}

@Composable
private fun EqCurve(levels: List<Int>, min: Int, max: Int, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (levels.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        fun x(i: Int) = w * (i + 0.5f) / levels.size
        fun y(l: Int) = h * (1f - (l - min).toFloat() / (max - min))
        val path = Path().apply {
            moveTo(0f, y(levels.first()))
            lineTo(x(0), y(levels[0]))
            for (i in 1 until levels.size) {
                val mx = (x(i - 1) + x(i)) / 2f
                cubicTo(mx, y(levels[i - 1]), mx, y(levels[i]), x(i), y(levels[i]))
            }
            lineTo(w, y(levels.last()))
        }
        val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
        drawLine(color.copy(alpha = 0.25f), Offset(0f, y(0)), Offset(w, y(0)), strokeWidth = 1.dp.toPx())
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun BandSlider(
    level: Int, min: Int, max: Int, enabled: Boolean,
    onChange: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        modifier.width(44.dp).pointerInput(min, max, enabled) {
            if (!enabled) return@pointerInput
            val pad = 14.dp.toPx()
            fun toLevel(y: Float): Int {
                val usable = size.height - 2 * pad
                val f = 1f - ((y - pad) / usable).coerceIn(0f, 1f)
                return (min + f * (max - min)).roundToInt()
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                currentOnChange(toLevel(down.position.y))
                drag(down.id) { ch ->
                    currentOnChange(toLevel(ch.position.y))
                    ch.consume()
                }
            }
        }
    ) {
        val pad = 14.dp.toPx()
        val usable = size.height - 2 * pad
        val cx = size.width / 2
        fun yOf(l: Int) = pad + usable * (1f - (l - min).toFloat() / (max - min))
        val sw = 6.dp.toPx()
        drawLine(trackColor, Offset(cx, pad), Offset(cx, pad + usable), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(activeColor, Offset(cx, yOf(0)), Offset(cx, yOf(level)), strokeWidth = sw, cap = StrokeCap.Round)
        drawCircle(activeColor, 11.dp.toPx(), Offset(cx, yOf(level)))
    }
}

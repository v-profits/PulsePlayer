@file:OptIn(ExperimentalMaterial3Api::class)

package com.semenenko.pulseplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.semenenko.pulseplayer.R
import com.semenenko.pulseplayer.audio.DspParams
import com.semenenko.pulseplayer.audio.EqualizerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val BAND_PAD = 14.dp
private val SCALE_DB = listOf(12, 6, 0, -6, -12)

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val s by EqualizerManager.state.collectAsStateWithLifecycle()
    val p = s.params
    val names = stringArrayResource(R.array.eq_preset_names)
    val half = DspParams.BAND_FREQS.size / 2
    var selected by rememberSaveable { mutableIntStateOf(-1) }   // -1 — ни одна полоса не выбрана
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

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
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .pointerInput(Unit) { detectTapGestures(onTap = { selected = -1 }) }   // тап вне бегунков снимает выделение
                .padding(16.dp),
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

            // Полосы: кривая, точная подстройка выбранной полосы и два ряда по 6 бегунков со шкалой дБ
            Card(colors = cardColors) {
                Column(Modifier.padding(16.dp).alpha(if (p.enabled) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EqCurve(p.gains, Modifier.fillMaxWidth().height(110.dp))
                    FineTunePanel(selected, p.gains.getOrElse(selected) { 0f })
                    BandRow(0 until half, p.gains, selected) { selected = it }
                    HorizontalDivider()
                    BandRow(half until DspParams.BAND_FREQS.size, p.gains, selected) { selected = it }
                }
            }

            Card(colors = cardColors) {
                Column(Modifier.padding(16.dp).alpha(if (p.enabled) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.eq_knobs), style = MaterialTheme.typography.titleSmall)
                    ParamSlider(stringResource(R.string.eq_preamp), "${fmt1(p.preamp)} dB", p.preamp, -12f..12f, 0f, 0.5f,
                        { signed(it) }) { EqualizerManager.setPreamp(it) }
                    ParamSlider(stringResource(R.string.eq_bass_knob), "${fmt1(p.bass)} dB", p.bass, -12f..12f, 0f, 0.5f,
                        { signed(it) }) { EqualizerManager.setBass(it) }
                    ParamSlider(stringResource(R.string.eq_treble), "${fmt1(p.treble)} dB", p.treble, -12f..12f, 0f, 0.5f,
                        { signed(it) }) { EqualizerManager.setTreble(it) }
                    ParamSlider(stringResource(R.string.eq_width), "${(p.width * 100).roundToInt()}%", p.width, 0f..2f, 1f, 0.05f,
                        { "${(it * 100).roundToInt()}%" }) { EqualizerManager.setWidth(it) }
                    ParamSlider(stringResource(R.string.eq_balance), balanceLabel(p.balance), p.balance, -1f..1f, 0f, 0.05f,
                        { when { it < -0.01f -> "L"; it > 0.01f -> "R"; else -> "C" } }) { EqualizerManager.setBalance(it) }
                    if (s.virtSupported) ParamSlider(
                        stringResource(R.string.eq_surround), "${s.virtualizer / 10}%", s.virtualizer / 10f, 0f..100f, 0f, 1f,
                        { "${it.roundToInt()}%" },
                    ) { EqualizerManager.setVirtualizer((it * 10).roundToInt()) }
                    if (s.loudSupported) ParamSlider(
                        stringResource(R.string.eq_loud_knob), "+${fmt1(s.loudness / 100f)} dB", s.loudness / 100f, 0f..10f, 0f, 0.5f,
                        { "${it.roundToInt()}" },
                    ) { EqualizerManager.setLoudness((it * 100).roundToInt()) }
                    Text(stringResource(R.string.eq_hint), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(colors = cardColors) {
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

// ---------- форматирование (без привязки к разделителю локали) ----------

private fun fmt1(v: Float): String = String.format(Locale.getDefault(), "%+.1f", v)
private fun signed(v: Float): String = if (v > 0.01f) "+${v.roundToInt()}" else if (v < -0.01f) "−${(-v).roundToInt()}" else "0"
private fun balanceLabel(v: Float): String = when {
    v < -0.005f -> "L ${(-v * 100).roundToInt()}"
    v > 0.005f -> "R ${(v * 100).roundToInt()}"
    else -> "C"
}
private fun freqShort(hz: Int): String =
    if (hz >= 1000) String.format(Locale.US, "%.1fk", hz / 1000f).replace(".0k", "k") else "$hz"
private fun freqFull(hz: Int): String =
    if (hz >= 1000) String.format(Locale.US, "%.1f", hz / 1000f).removeSuffix(".0") + " kHz" else "$hz Hz"

// ---------- полосы ----------

@Composable
private fun FineTunePanel(index: Int, gain: Float) {
    if (index < 0) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.eq_select_band), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val hz = DspParams.BAND_FREQS.getOrElse(index) { 1000 }
    fun set(v: Float) = EqualizerManager.setBand(index, ((v.coerceIn(-DspParams.MAX_DB, DspParams.MAX_DB)) * 10).roundToInt() / 10f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton(Icons.Rounded.Remove) { fast -> set(gain - if (fast) 0.5f else 0.1f) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(freqFull(hz), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${fmt1(gain)} dB", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
        }
        StepButton(Icons.Rounded.Add) { fast -> set(gain + if (fast) 0.5f else 0.1f) }
        TextButton(onClick = { set(0f) }) { Text("0") }
    }
}

@Composable
private fun BandRow(indices: IntRange, gains: List<Float>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(200.dp)) {
        Column(Modifier.width(30.dp).fillMaxHeight()) {
            Spacer(Modifier.height(22.dp))
            DbScale(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(22.dp))
        }
        for (i in indices) {
            val g = gains.getOrElse(i) { 0f }
            val isSel = i == selected
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                    Text(signed(g), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                BandSlider(g, isSel, { onSelect(i) }, { EqualizerManager.setBand(i, it) }, Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                    Text(freqShort(DspParams.BAND_FREQS[i]), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DbScale(modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    val style = TextStyle(fontSize = 10.sp)
    val max = DspParams.MAX_DB
    Canvas(modifier) {
        val pad = BAND_PAD.toPx()
        val usable = size.height - 2 * pad
        for (g in SCALE_DB) {
            val y = pad + usable * (1f - (g + max) / (2 * max))
            val txt = if (g > 0) "+$g" else if (g < 0) "−${-g}" else "0"
            val layout = tm.measure(txt, style)
            drawText(layout, color, Offset(size.width - layout.size.width - 2.dp.toPx(), y - layout.size.height / 2f))
        }
    }
}

/** Вертикальный бегунок: шкала делений каждые 3 дБ, ±12 дБ, шаг 0.5 дБ, двойное касание — в ноль. */
@Composable
private fun BandSlider(
    gain: Float, selected: Boolean, onTouch: () -> Unit, onChange: (Float) -> Unit, modifier: Modifier = Modifier,
) {
    val max = DspParams.MAX_DB
    val cs = MaterialTheme.colorScheme
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnTouch by rememberUpdatedState(onTouch)
    val currentGain by rememberUpdatedState(gain)
    Canvas(
        modifier
            // тап по полосе — выбрать её, двойной — в ноль (прокрутка экрана пальцем по полосе ничего не меняет)
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnTouch() }, onDoubleTap = { currentOnTouch(); currentOnChange(0f) }) }
            // значение меняется только если взяться за сам ползунок
            .pointerInput(Unit) {
                val pad = BAND_PAD.toPx()
                val grab = 28.dp.toPx()
                fun yOf(g: Float) = pad + (size.height - 2 * pad) * (1f - (g + max) / (2 * max))
                fun toGain(y: Float): Float {
                    val f = 1f - ((y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                    return ((-max + f * 2 * max) * 2).roundToInt() / 2f
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val cx = size.width / 2f
                    if (abs(down.position.x - cx) > grab || abs(down.position.y - yOf(currentGain)) > grab) return@awaitEachGesture
                    currentOnTouch()
                    down.consume()
                    drag(down.id) { ch ->
                        currentOnChange(toGain(ch.position.y))
                        ch.consume()
                    }
                }
            }
    ) {
        val pad = BAND_PAD.toPx()
        val usable = size.height - 2 * pad
        val cx = size.width / 2
        fun yOf(g: Float) = pad + usable * (1f - (g + max) / (2 * max))
        val tw = 8.dp.toPx()

        if (selected) {
            drawRoundRect(cs.surfaceContainerHighest.copy(alpha = 0.8f), Offset(1.dp.toPx(), 0f),
                Size(size.width - 2.dp.toPx(), size.height), CornerRadius(14.dp.toPx()))
        }
        drawRoundRect(cs.outlineVariant.copy(alpha = 0.7f), Offset(cx - tw / 2, pad), Size(tw, usable), CornerRadius(tw / 2))
        for (k in -4..4) {
            val g = k * 3f
            val len = when { k == 0 -> 14.dp.toPx(); k % 2 == 0 -> 10.dp.toPx(); else -> 6.dp.toPx() }
            val c = cs.outline.copy(alpha = if (k == 0) 0.95f else 0.55f)
            val y = yOf(g)
            val gap = tw / 2 + 3.dp.toPx()
            drawLine(c, Offset(cx - gap - len, y), Offset(cx - gap, y), 1.dp.toPx())
            drawLine(c, Offset(cx + gap, y), Offset(cx + gap + len, y), 1.dp.toPx())
        }
        val y0 = yOf(0f)
        val yg = yOf(gain)
        drawRoundRect(cs.primary, Offset(cx - tw / 2, min(y0, yg)), Size(tw, abs(y0 - yg).coerceAtLeast(0.1f)), CornerRadius(tw / 2))
        val thumbW = 30.dp.toPx()
        val thumbH = 18.dp.toPx()
        drawRoundRect(cs.primary, Offset(cx - thumbW / 2, yg - thumbH / 2), Size(thumbW, thumbH), CornerRadius(7.dp.toPx()))
        drawRoundRect(cs.onPrimary.copy(alpha = 0.85f), Offset(cx - 6.dp.toPx(), yg - 1.dp.toPx()),
            Size(12.dp.toPx(), 2.dp.toPx()), CornerRadius(1.dp.toPx()))
    }
}

// ---------- кривая отклика ----------

@Composable
private fun EqCurve(gains: List<Float>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = DspParams.MAX_DB
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun y(g: Float) = h * (1f - (g + max) / (2 * max))
        for (g in listOf(-12f, -6f, 0f, 6f, 12f)) {
            drawLine(grid.copy(alpha = if (g == 0f) 0.9f else 0.45f), Offset(0f, y(g)), Offset(w, y(g)),
                strokeWidth = if (g == 0f) 1.5.dp.toPx() else 1.dp.toPx())
        }
        if (gains.size < 2) return@Canvas
        fun x(i: Int) = w * (i + 0.5f) / gains.size
        val path = Path().apply {
            moveTo(0f, y(gains.first()))
            lineTo(x(0), y(gains[0]))
            for (i in 1 until gains.size) {
                val mx = (x(i - 1) + x(i)) / 2f
                cubicTo(mx, y(gains[i - 1]), mx, y(gains[i]), x(i), y(gains[i]))
            }
            lineTo(w, y(gains.last()))
        }
        val fill = Path().apply { addPath(path); lineTo(w, y(0f)); lineTo(0f, y(0f)); close() }
        drawPath(fill, color.copy(alpha = 0.22f))
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ---------- горизонтальные ползунки со шкалой и кнопками − / + ----------

@Composable
private fun ParamSlider(
    label: String, display: String, value: Float, range: ClosedFloatingPointRange<Float>,
    default: Float, step: Float, tickLabel: (Float) -> String, onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { onChange(default) }) { Text(display) }
        }
        FineSlider(value, range, default, step, tickLabel, onChange)
    }
}

@Composable
private fun FineSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, default: Float, step: Float,
    tickLabel: (Float) -> String, onChange: (Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val currentOnChange by rememberUpdatedState(onChange)
    val lo = range.start
    val hi = range.endInclusive
    fun snap(v: Float) = ((v / step).roundToInt() * step).coerceIn(lo, hi)
    val currentValue by rememberUpdatedState(value)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StepButton(Icons.Rounded.Remove) { fast -> onChange(snap(value - if (fast) step * 5 else step)) }
        Canvas(
            Modifier.weight(1f).height(54.dp)
                // тап по шкале — перейти в эту точку
                .pointerInput(lo, hi, step) {
                    val padX = 14.dp.toPx()
                    detectTapGestures(onTap = { off ->
                        val f = ((off.x - padX) / (size.width - 2 * padX)).coerceIn(0f, 1f)
                        currentOnChange(snap(lo + f * (hi - lo)))
                    })
                }
                // перетаскивание — только за сам ползунок, чтобы не задевать его при прокрутке экрана
                .pointerInput(lo, hi, step) {
                    val padX = 14.dp.toPx()
                    val grab = 30.dp.toPx()
                    fun frac(x: Float) = ((x - padX) / (size.width - 2 * padX)).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val xv = padX + (size.width - 2 * padX) * ((currentValue - lo) / (hi - lo)).coerceIn(0f, 1f)
                        if (abs(down.position.x - xv) > grab) return@awaitEachGesture
                        down.consume()
                        drag(down.id) { ch ->
                            currentOnChange(snap(lo + frac(ch.position.x) * (hi - lo)))
                            ch.consume()
                        }
                    }
                }
        ) {
            val padX = 14.dp.toPx()
            val usable = size.width - 2 * padX
            fun xOf(v: Float) = padX + usable * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            val ty = 18.dp.toPx()
            val th = 10.dp.toPx()
            drawRoundRect(cs.outlineVariant.copy(alpha = 0.7f), Offset(padX, ty - th / 2), Size(usable, th), CornerRadius(th / 2))
            // активная часть: от «нулевой» отметки до текущего значения
            val x0 = xOf(default)
            val xv = xOf(value)
            drawRoundRect(cs.primary, Offset(min(x0, xv), ty - th / 2), Size(abs(xv - x0).coerceAtLeast(0.1f), th), CornerRadius(th / 2))
            // деления
            for (k in 0..8) {
                val x = padX + usable * k / 8f
                val len = if (k % 4 == 0) 9.dp.toPx() else 5.dp.toPx()
                drawLine(cs.outline.copy(alpha = 0.6f), Offset(x, ty + th / 2 + 4.dp.toPx()), Offset(x, ty + th / 2 + 4.dp.toPx() + len), 1.dp.toPx())
            }
            val style = TextStyle(fontSize = 10.sp)
            val labelColor = cs.onSurfaceVariant.copy(alpha = 0.85f)
            for (k in intArrayOf(0, 4, 8)) {
                val t = lo + (hi - lo) * k / 8f
                val layout = tm.measure(tickLabel(t), style)
                val lx = (padX + usable * k / 8f - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, labelColor, Offset(lx, ty + th / 2 + 4.dp.toPx() + 11.dp.toPx()))
            }
            // ползунок
            val tw = 16.dp.toPx()
            val tH = 30.dp.toPx()
            drawRoundRect(cs.primary, Offset(xv - tw / 2, ty - tH / 2), Size(tw, tH), CornerRadius(7.dp.toPx()))
            drawRoundRect(cs.onPrimary.copy(alpha = 0.85f), Offset(xv - 1.dp.toPx(), ty - 6.dp.toPx()), Size(2.dp.toPx(), 12.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
        StepButton(Icons.Rounded.Add) { fast -> onChange(snap(value + if (fast) step * 5 else step)) }
    }
}

/** Кнопка «−/+»: касание — один шаг, удержание — повтор (после паузы ускоряется). */
@Composable
private fun StepButton(icon: ImageVector, onStep: (fast: Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val latest by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(40.dp).clip(IconShape).background(cs.secondaryContainer)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    latest(false)
                    val job = scope.launch {
                        delay(450)
                        var n = 0
                        while (true) { latest(n > 10); n++; delay(60) }
                    }
                    tryAwaitRelease()
                    job.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = cs.onSecondaryContainer) }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

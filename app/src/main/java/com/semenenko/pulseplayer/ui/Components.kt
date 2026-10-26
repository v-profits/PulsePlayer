@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.semenenko.pulseplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.media3.common.Player
import com.semenenko.pulseplayer.audio.EqualizerManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import com.semenenko.pulseplayer.R
import com.semenenko.pulseplayer.data.Track

@Composable
fun quantityString(id: Int, n: Int): String = LocalContext.current.resources.getQuantityString(id, n, n)

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Единая форма иконок-кнопок: скруглённый квадрат. */
val IconShape = RoundedCornerShape(12.dp)

class SelectionState {
    val uris = mutableStateListOf<String>()
    val active: Boolean get() = uris.isNotEmpty()
    fun toggle(uri: String) { if (!uris.remove(uri)) uris.add(uri) }
    fun clear() = uris.clear()
}

enum class TrackAction { PLAY_NEXT, ADD_TO_QUEUE, ADD_TO_PLAYLIST, FAVORITE, INFO, REMOVE }

@Composable
fun Artwork(uri: String?, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.MusicNote, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxSize(0.5f),
        )
        if (uri != null) {
            AsyncImage(
                model = uri, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Вставка распознанной речи: добавляется к тексту; с заглавной в начале и после . ? ! */
fun appendSpoken(current: String, spoken: String): String {
    val s = spoken.trim()
    if (s.isEmpty()) return current
    val base = current.trimEnd()
    val capitalize = base.isEmpty() || base.last() in ".?!"
    var add = if (capitalize) s.replaceFirstChar { it.uppercase() } else s
    add = Regex("([.!?]\\s+)(\\p{L})").replace(add) { it.groupValues[1] + it.groupValues[2].uppercase() }
    return if (base.isEmpty()) add else "$base $add"
}

/** Кнопка-значок с понятным состоянием: включена — залитый круг и яркий значок, выключена — приглушённый, недоступна — бледный. */
@Composable
fun StateIconButton(
    icon: ImageVector,
    desc: String,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> cs.onSurface.copy(alpha = 0.28f)
        active -> cs.onPrimaryContainer
        else -> cs.onSurfaceVariant
    }
    Box(
        modifier.size(size).clip(IconShape)
            .background(if (active && enabled) cs.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, Modifier.size(iconSize), tint = tint) }
}

/** Кружок выбора: заполненный с галочкой — выбрано, пустое кольцо — не выбрано. */
@Composable
fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
            .background(if (selected) cs.primary else cs.surface.copy(alpha = 0.8f))
            .border(2.dp, if (selected) cs.primary else cs.outline, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = cs.onPrimary, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
    }
}

@Composable
fun TrackRow(
    track: Track,
    current: Boolean,
    playing: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    favorite: Boolean,
    inPlaylist: Boolean,
    canRemove: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (TrackAction) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) cs.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            Artwork(track.artUri, Modifier.fillMaxSize())
            if (current) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, null, tint = Color.White) }
            }
            if (selectionMode) SelectionBadge(selected, Modifier.align(Alignment.BottomEnd).offset(5.dp, 5.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = when { selected -> cs.onSecondaryContainer; current -> cs.primary; else -> cs.onSurface },
            )
            val artist = track.artist.ifBlank { stringResource(R.string.unknown_artist) }
            Text(
                listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.durationMs.takeIf { it > 0 }?.let { formatTime(it) })
                    .joinToString(" · ").ifBlank { artist },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) cs.onSecondaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
            )
        }
        StateIconButton(
            if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add), favorite,
        ) { onAction(TrackAction.FAVORITE) }
        Spacer(Modifier.width(4.dp))
        StateIconButton(
            if (inPlaylist) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
            stringResource(R.string.add_to_playlist), inPlaylist,
        ) { onAction(TrackAction.ADD_TO_PLAYLIST) }
        Box {
            IconButton(onClick = { menu = true }, Modifier.size(40.dp)) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                fun item(textRes: Int, icon: ImageVector, action: TrackAction) = @Composable {
                    DropdownMenuItem(
                        text = { Text(stringResource(textRes)) },
                        leadingIcon = { Icon(icon, null) },
                        onClick = { menu = false; onAction(action) },
                    )
                }
                item(R.string.play_next, Icons.Rounded.SkipNext, TrackAction.PLAY_NEXT)()
                item(R.string.add_to_queue, Icons.AutoMirrored.Rounded.PlaylistAdd, TrackAction.ADD_TO_QUEUE)()
                item(R.string.add_to_playlist, Icons.Rounded.LibraryAdd, TrackAction.ADD_TO_PLAYLIST)()
                item(
                    if (favorite) R.string.favorite_remove else R.string.favorite_add,
                    if (favorite) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite,
                    TrackAction.FAVORITE,
                )()
                item(R.string.track_info, Icons.Rounded.Info, TrackAction.INFO)()
                if (canRemove) item(R.string.remove, Icons.Rounded.Delete, TrackAction.REMOVE)()
            }
        }
    }
}

@Composable
fun TrackList(
    tracks: List<Track>,
    vm: MainViewModel,
    selection: SelectionState,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    onRemove: ((Track) -> Unit)? = null,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val inPlaylists by vm.playlistUris.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxSize()) {
        if (header != null) item(key = "header") { header() }
        itemsIndexed(tracks, key = { i, t -> "$i|${t.uri}" }) { index, t ->
            TrackRow(
                track = t,
                current = ui.current?.uri == t.uri,
                playing = ui.isPlaying,
                selected = t.uri in selection.uris,
                selectionMode = selection.active,
                favorite = t.uri in favs,
                inPlaylist = t.uri in inPlaylists,
                canRemove = onRemove != null,
                onClick = { if (selection.active) selection.toggle(t.uri) else vm.playTracks(tracks, index) },
                onLongClick = { selection.toggle(t.uri) },
                onAction = {
                    when (it) {
                        TrackAction.PLAY_NEXT -> vm.playNext(listOf(t))
                        TrackAction.ADD_TO_QUEUE -> vm.addToQueue(listOf(t))
                        TrackAction.ADD_TO_PLAYLIST -> vm.pickPlaylistFor(listOf(t))
                        TrackAction.FAVORITE -> vm.toggleFavorite(t)
                        TrackAction.INFO -> vm.showTrackInfo(t)
                        TrackAction.REMOVE -> onRemove?.invoke(t)
                    }
                },
            )
        }
    }
}

@Composable
fun PlayShuffleRow(tracks: List<Track>, vm: MainViewModel, trailing: @Composable RowScope.() -> Unit = {}) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(onClick = { vm.playTracks(tracks, 0) }, shape = IconShape) {
            Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play))
        }
        StateIconButton(
            Icons.Rounded.Shuffle, stringResource(R.string.shuffle), ui.shuffle,
            size = 48.dp, iconSize = 24.dp,
        ) { vm.shuffleList(tracks) }
        StateIconButton(
            if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            stringResource(R.string.repeat), ui.repeat != Player.REPEAT_MODE_OFF,
            size = 48.dp, iconSize = 24.dp,
        ) { vm.cycleRepeat() }
        Spacer(Modifier.weight(1f))
        Text(
            quantityString(R.plurals.tracks_count, tracks.size),
            style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing()
    }
}

@Composable
fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onPlaylist: () -> Unit,
    extra: List<Pair<Int, () -> Unit>> = emptyList(),
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.selected_count, count)) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) } },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all)) }
            IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play)) }
            IconButton(onClick = onPlaylist) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_playlist)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.play_next)) }, onClick = { menu = false; onPlayNext() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = { menu = false; onQueue() })
                    extra.forEach { (res, action) ->
                        DropdownMenuItem(text = { Text(stringResource(res)) }, onClick = { menu = false; action() })
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, desc: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (desc != null) {
            Spacer(Modifier.height(8.dp))
            Text(desc, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * Поле ввода с кнопками «очистить» (крестик, когда есть текст) и «голосовой ввод» (системное распознавание речи).
 */
@Composable
fun VoiceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    outlined: Boolean = true,
    colors: TextFieldColors? = null,
) {
    val ctx = LocalContext.current
    val prompt = stringResource(R.string.voice_prompt)
    val unavailable = stringResource(R.string.voice_unavailable)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentValue by rememberUpdatedState(value)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { currentOnChange(appendSpoken(currentValue, it)) }
        }
    }
    val trailing: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.clear_text))
                }
            }
            IconButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, ctx.resources.configuration.locales[0].toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                }
                try { launcher.launch(intent) } catch (e: ActivityNotFoundException) {
                    Toast.makeText(ctx, unavailable, Toast.LENGTH_SHORT).show()
                }
            }) { Icon(Icons.Rounded.Mic, stringResource(R.string.voice_input)) }
        }
    }
    val labelSlot: (@Composable () -> Unit)? = label?.let { { Text(it) } }
    val placeholderSlot: (@Composable () -> Unit)? = placeholder?.let { { Text(it) } }
    if (outlined) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, modifier = modifier, singleLine = true,
            label = labelSlot, placeholder = placeholderSlot, trailingIcon = trailing,
        )
    } else {
        TextField(
            value = value, onValueChange = onValueChange, modifier = modifier, singleLine = true,
            label = labelSlot, placeholder = placeholderSlot, trailingIcon = trailing,
            colors = colors ?: TextFieldDefaults.colors(),
        )
    }
}

@Composable
fun NameDialog(titleRes: Int, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            VoiceTextField(
                value = text, onValueChange = { text = it },
                label = stringResource(R.string.playlist_name),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun AddToPlaylistDialog(tracks: List<Track>, vm: MainViewModel, onDismiss: () -> Unit) {
    val lists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog(R.string.new_playlist, "", { creating = false }) {
            vm.createPlaylist(it, tracks); onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.new_playlist)) },
                        leadingContent = { Icon(Icons.Rounded.Add, null) },
                        modifier = Modifier.clickable { creating = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                itemsIndexed(lists.filter { !it.playlist.isSystem }, key = { _, p -> p.playlist.id }) { _, p ->
                    ListItem(
                        headlineContent = {
                            Text(if (p.playlist.isSystem) stringResource(R.string.favorites) else p.playlist.name)
                        },
                        supportingContent = { Text(quantityString(R.plurals.tracks_count, p.itemCount)) },
                        leadingContent = {
                            Icon(if (p.playlist.isSystem) Icons.Rounded.Favorite else Icons.Rounded.QueueMusic, null)
                        },
                        modifier = Modifier.clickable { vm.addToPlaylist(p.playlist.id, tracks); onDismiss() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Полоса воспроизведения: касание и перетаскивание пальцем меняют позицию.
 * [onDragging] сообщает позицию (0..1) во время жеста и null после него.
 */
@Composable
fun SeekBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 8.dp,
    onDragging: (Float?) -> Unit = {},
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = MaterialTheme.colorScheme.primary
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val dragging by rememberUpdatedState(onDragging)
    val shown = (dragFrac ?: progress).coerceIn(0f, 1f)

    Canvas(
        modifier.pointerInput(Unit) {
            val r = thumbRadius.toPx()
            fun frac(x: Float) = ((x - r) / (size.width - 2 * r).coerceAtLeast(1f)).coerceIn(0f, 1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                dragFrac = frac(down.position.x)
                dragging(dragFrac)
                drag(down.id) { ch ->
                    dragFrac = frac(ch.position.x)
                    dragging(dragFrac)
                    ch.consume()
                }
                dragFrac?.let { seek(it) }
                dragFrac = null
                dragging(null)
            }
        }
    ) {
        val r = thumbRadius.toPx()
        val h = trackHeight.toPx()
        val cy = size.height / 2
        val x0 = r
        val x1 = size.width - r
        val xv = x0 + (x1 - x0) * shown
        drawLine(trackColor, Offset(x0, cy), Offset(x1, cy), strokeWidth = h, cap = StrokeCap.Round)
        drawLine(activeColor, Offset(x0, cy), Offset(xv, cy), strokeWidth = h, cap = StrokeCap.Round)
        drawCircle(activeColor, if (dragFrac != null) r * 1.25f else r, Offset(xv, cy))
    }
}

/** Эквалайзер (с индикацией включения) и настройки — доступны на любом экране, в том числе внутри папки или плейлиста. */
@Composable
fun GlobalActions(onOpen: (Dest) -> Unit) {
    val eq by EqualizerManager.state.collectAsStateWithLifecycle()
    StateIconButton(
        Icons.Rounded.Tune, stringResource(R.string.equalizer), eq.params.enabled,
        size = 48.dp, iconSize = 24.dp,
    ) { onOpen(Dest.Equalizer) }
    IconButton(onClick = { onOpen(Dest.Settings) }) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings)) }
}

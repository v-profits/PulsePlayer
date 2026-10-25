@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.pulseplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.compose.ui.platform.LocalView
import com.example.pulseplayer.R
import com.example.pulseplayer.audio.EqualizerManager
import com.example.pulseplayer.data.AppSettings

@Composable
fun MiniPlayer(ui: PlayerUi, vm: MainViewModel, onOpen: () -> Unit) {
    val pos by vm.position.collectAsStateWithLifecycle()
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val inPlaylists by vm.playlistUris.collectAsStateWithLifecycle()
    val t = ui.current
    val has = t != null
    val fav = t != null && t.uri in favs
    val inPl = t != null && t.uri in inPlaylists
    Column {
        SeekBar(
            progress = if (has && ui.durationMs > 0) pos.toFloat() / ui.durationMs else 0f,
            onSeek = { if (has) vm.seekTo((it * ui.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 4.dp),
            trackHeight = 3.dp, thumbRadius = 7.dp,
        )
        Row(
            Modifier.clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(t?.artUri, Modifier.size(46.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t?.title ?: stringResource(R.string.stopped), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    if (t != null) t.artist.ifBlank { stringResource(R.string.unknown_artist) }
                    else stringResource(R.string.stopped_hint),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpen) { Icon(Icons.Rounded.OpenInFull, stringResource(R.string.open_fullscreen)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateIconButton(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), ui.shuffle) { vm.toggleShuffle() }
            StateIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.previous), enabled = has) { vm.previous() }
            FilledIconButton(onClick = { vm.togglePlay() }, Modifier.size(44.dp)) {
                Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (ui.isPlaying) R.string.pause else R.string.play))
            }
            StateIconButton(Icons.Rounded.Stop, stringResource(R.string.stop), enabled = has) { vm.stopPlayback() }
            StateIconButton(Icons.Rounded.SkipNext, stringResource(R.string.next), enabled = has) { vm.next() }
            StateIconButton(
                if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                stringResource(R.string.repeat), ui.repeat != Player.REPEAT_MODE_OFF,
            ) { vm.cycleRepeat() }
            StateIconButton(
                if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                stringResource(if (fav) R.string.favorite_remove else R.string.favorite_add), fav, enabled = has,
            ) { t?.let { vm.toggleFavorite(it) } }
            StateIconButton(
                if (inPl) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
                stringResource(R.string.add_to_playlist), inPl, enabled = has,
            ) { t?.let { vm.pickPlaylistFor(listOf(it)) } }
        }
    }
}

@Composable
fun NowPlayingScreen(vm: MainViewModel, onClose: () -> Unit, onOpenEq: () -> Unit) {
    val keepOn by AppSettings.keepScreenOn.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pos by vm.position.collectAsStateWithLifecycle()
    val favs by vm.favoriteUris.collectAsStateWithLifecycle()
    val inPlaylists by vm.playlistUris.collectAsStateWithLifecycle()
    val eqState by EqualizerManager.state.collectAsStateWithLifecycle()
    val sleep by vm.sleepRemaining.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val cs = MaterialTheme.colorScheme
    val t = ui.current
    val scale by animateFloatAsState(if (ui.isPlaying) 1f else 0.88f, spring(dampingRatio = 0.7f), label = "art")

    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(cs.primaryContainer.copy(alpha = 0.6f), cs.surface)))
        ) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.close), Modifier.size(32.dp)) }
                    Text(stringResource(R.string.now_playing), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { showQueue = true }) { Icon(Icons.Rounded.QueueMusic, stringResource(R.string.queue)) }
                }

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight)
                    Artwork(
                        t?.artUri,
                        Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }
                            .shadow(20.dp, RoundedCornerShape(28.dp)),
                        corner = 28.dp,
                    )
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t?.title ?: stringResource(R.string.nothing_playing), maxLines = 1,
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.basicMarquee())
                        Text(t?.artist?.ifBlank { null } ?: stringResource(R.string.unknown_artist), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                            color = cs.onSurfaceVariant)
                        if (!t?.album.isNullOrBlank()) {
                            Text(t?.album.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    if (t != null) {
                        val fav = t.uri in favs
                        StateIconButton(
                            if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            stringResource(if (fav) R.string.favorite_remove else R.string.favorite_add), fav,
                            size = 46.dp, iconSize = 24.dp,
                        ) { vm.toggleFavorite(t) }
                        val inPl = t.uri in inPlaylists
                        StateIconButton(
                            if (inPl) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
                            stringResource(R.string.add_to_playlist), inPl,
                            size = 46.dp, iconSize = 24.dp,
                        ) { vm.pickPlaylistFor(listOf(t)) }
                    }
                }

                val dur = ui.durationMs.coerceAtLeast(1L)
                SeekBar(
                    progress = (pos.toFloat() / dur).coerceIn(0f, 1f),
                    onSeek = { vm.seekTo((it * dur).toLong()) },
                    onDragging = { drag = it },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    trackHeight = 6.dp, thumbRadius = 10.dp,
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(formatTime(((drag ?: (pos.toFloat() / dur)) * dur).toLong()), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(ui.durationMs), style = MaterialTheme.typography.labelMedium)
                }

                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    StateIconButton(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), ui.shuffle,
                        size = 48.dp, iconSize = 26.dp) { vm.toggleShuffle() }
                    IconButton(onClick = { vm.previous() }, Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp))
                    }
                    FilledIconButton(onClick = { vm.togglePlay() }, Modifier.size(76.dp)) {
                        Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (ui.isPlaying) R.string.pause else R.string.play), Modifier.size(44.dp))
                    }
                    IconButton(onClick = { vm.next() }, Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, stringResource(R.string.next), Modifier.size(36.dp))
                    }
                    StateIconButton(
                        if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        stringResource(R.string.repeat), ui.repeat != Player.REPEAT_MODE_OFF,
                        size = 48.dp, iconSize = 26.dp,
                    ) { vm.cycleRepeat() }
                }

                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showSpeed = true }) {
                        Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        Text("${"%.2f".format(ui.speed).trimEnd('0').trimEnd('.', ',')}x")
                    }
                    TextButton(onClick = { showSleep = true }) {
                        Icon(Icons.Rounded.Bedtime, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        Text(sleep?.let { formatTime(it * 1000) } ?: stringResource(R.string.sleep_timer), maxLines = 1)
                    }
                    IconButton(onClick = { vm.stopPlayback() }) { Icon(Icons.Rounded.Stop, stringResource(R.string.stop)) }
                    StateIconButton(Icons.Rounded.Tune, stringResource(R.string.equalizer), eqState.params.enabled,
                        size = 48.dp, iconSize = 24.dp) { onOpenEq() }
                }
            }
        }
    }

    if (showSpeed) {
        ChoiceDialog(
            title = stringResource(R.string.speed),
            options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { "${it}x" to it },
            selected = ui.speed,
            onPick = { vm.setSpeed(it); showSpeed = false },
            onDismiss = { showSpeed = false },
        )
    }
    if (showSleep) {
        val off = stringResource(R.string.sleep_off)
        val minuteOptions = listOf(5, 10, 15, 30, 45, 60, 90).map { stringResource(R.string.minutes_fmt, it) to it }
        ChoiceDialog(
            title = stringResource(R.string.sleep_timer),
            options = listOf(off to 0) + minuteOptions,
            selected = if (sleep == null) 0 else -1,
            onPick = { vm.setSleepTimer(if (it == 0) null else it); showSleep = false },
            onDismiss = { showSleep = false },
        )
    }
    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) { QueueSheet(ui, vm) }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String, options: List<Pair<String, T>>, selected: T,
    onPick: (T) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == selected, onClick = { onPick(value) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun QueueSheet(ui: PlayerUi, vm: MainViewModel) {
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { if (ui.index > 0) state.scrollToItem((ui.index - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${stringResource(R.string.queue)} · ${quantityString(R.plurals.tracks_count, ui.queue.size)}",
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.clearQueue() }) { Text(stringResource(R.string.clear)) }
        }
        LazyColumn(state = state) {
            itemsIndexed(ui.queue, key = { i, t -> "$i|${t.uri}" }) { i, t ->
                var menu by remember { mutableStateOf(false) }
                val current = i == ui.index
                ListItem(
                    headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    supportingContent = { Text(t.artist.ifBlank { stringResource(R.string.unknown_artist) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Artwork(t.artUri, Modifier.size(44.dp)) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) },
                                    leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
                                    onClick = { menu = false; vm.moveInQueue(i, i - 1) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) },
                                    leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                                    onClick = { menu = false; vm.moveInQueue(i, i + 1) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.remove)) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { menu = false; vm.removeFromQueue(i) })
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { vm.skipToQueue(i) },
                )
            }
        }
    }
}

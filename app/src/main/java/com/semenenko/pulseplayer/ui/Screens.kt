@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.semenenko.pulseplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.semenenko.pulseplayer.R
import com.semenenko.pulseplayer.data.NaturalOrder
import com.semenenko.pulseplayer.data.Track
import com.semenenko.pulseplayer.data.toTrack

sealed interface Dest {
    data class Tracks(val title: String, val tracks: List<Track>) : Dest
    data class Playlist(val id: Long, val name: String) : Dest
    data class Browse(val uri: String, val name: String) : Dest
    data object Picked : Dest
    data object Equalizer : Dest
    data object Settings : Dest
    data object Help : Dest
}

private enum class LocalSort(val label: Int) {
    DEFAULT(R.string.sort_default),
    FILENAME(R.string.sort_filename),
    TITLE(R.string.sort_title),
    ARTIST(R.string.sort_artist),
    DURATION(R.string.sort_duration),
}

@Composable
fun TrackListScreen(
    title: String,
    tracks: List<Track>,
    vm: MainViewModel,
    onBack: () -> Unit,
    onRemove: ((Track) -> Unit)? = null,
    emptyText: String? = null,
    onClear: (() -> Unit)? = null,
    isTop: Boolean = true,
    onOpen: (Dest) -> Unit = {},
) {
    val selection = remember { SelectionState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LocalSort.DEFAULT) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Один общий ключ для всех экранов-списков: «играет этот список» определяется по содержимому,
    // поэтому папка/альбом/исполнитель/плейлист узнаётся и после выхода и повторного открытия
    val listKey = "list"

    val shown = remember(tracks, query, sort) {
        val f = if (query.isBlank()) tracks else tracks.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
        when (sort) {
            LocalSort.DEFAULT -> f
            LocalSort.FILENAME -> f.sortedWith(FileOrder)
            LocalSort.TITLE -> f.sortedWith(Comparator { a, b -> NaturalOrder.compare(a.title, b.title) })
            LocalSort.ARTIST -> f.sortedWith(Comparator { a, b ->
                NaturalOrder.compare(a.artist, b.artist).takeIf { it != 0 } ?: NaturalOrder.compare(a.title, b.title)
            })
            LocalSort.DURATION -> f.sortedByDescending { it.durationMs }
        }
    }
    if (isTop) LaunchedEffect(shown) { vm.contextTracks.value = shown }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
    BackHandler(enabled = searching && !selection.active) { searching = false; query = "" }

    if (confirmClear && onClear != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_list)) },
            text = { Text(stringResource(R.string.clear_confirm)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val selected = shown.filter { it.uri in selection.uris }
    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionBar(
                count = selection.uris.size,
                onClose = { selection.clear() },
                onSelectAll = { shown.forEach { if (it.uri !in selection.uris) selection.uris.add(it.uri) } },
                onPlay = { vm.playTracks(selected); selection.clear() },
                onPlayNext = { vm.playNext(selected); selection.clear() },
                onQueue = { vm.addToQueue(selected); selection.clear() },
                onPlaylist = { vm.pickPlaylistFor(selected); selection.clear() },
                extra = listOf(
                    R.string.fav_tracks_add to { vm.addToFavorites(selected); selection.clear() },
                    R.string.play_selected_repeat to { vm.playTracksRepeat(selected); selection.clear() },
                ),
            )
        } else {
            TopAppBar(
                title = {
                    if (searching) {
                        VoiceTextField(
                            value = query, onValueChange = { query = it },
                            placeholder = stringResource(R.string.search_hint),
                            outlined = false,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                quantityString(R.plurals.tracks_count, tracks.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { if (searching) query = ""; searching = !searching }) {
                        Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, stringResource(R.string.search))
                    }
                    if (!searching) {
                        GlobalActions(onOpen)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                            DropdownMenu(menu, { menu = false }) {
                                LocalSort.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(m.label)) },
                                        leadingIcon = { if (m == sort) Icon(Icons.Rounded.Check, null) },
                                        onClick = { sort = m; menu = false },
                                    )
                                }
                                if (onClear != null) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.clear_list)) },
                                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                                        onClick = { menu = false; confirmClear = true },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
        if (shown.isNotEmpty()) PlayShuffleRow(shown, vm, listKey)
        if (shown.isEmpty()) {
            EmptyState(
                Icons.Rounded.QueueMusic,
                if (query.isNotBlank()) stringResource(R.string.no_results) else emptyText ?: stringResource(R.string.library_empty),
            )
        } else {
            TrackList(shown, vm, selection, onRemove = onRemove, sourceKey = listKey)
        }
    }
}

@Composable
fun PlaylistScreen(
    id: Long, name: String, vm: MainViewModel, onBack: () -> Unit,
    isTop: Boolean = true, onOpen: (Dest) -> Unit = {},
) {
    val flow = remember(id) { vm.playlistItems(id) }
    val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val tracks = remember(items) { items.map { it.toTrack() } }
    TrackListScreen(
        title = name,
        tracks = tracks,
        vm = vm,
        onBack = onBack,
        onRemove = { vm.removeFromPlaylist(id, it) },
        onClear = { vm.clearPlaylist(id) },
        isTop = isTop,
        onOpen = onOpen,
        emptyText = stringResource(R.string.empty_playlist),
    )
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.pulseplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pulseplayer.R
import com.example.pulseplayer.data.Track
import com.example.pulseplayer.data.toTrack

sealed interface Dest {
    data class Tracks(val title: String, val tracks: List<Track>) : Dest
    data class Playlist(val id: Long, val name: String) : Dest
    data class Browse(val uri: String, val name: String) : Dest
    data object Picked : Dest
    data object Equalizer : Dest
    data object Settings : Dest
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
) {
    val selection = remember { SelectionState() }
    if (isTop) LaunchedEffect(tracks) { vm.contextTracks.value = tracks }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear && onClear != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_list)) },
            text = { Text(stringResource(R.string.clear_confirm)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val selected = tracks.filter { it.uri in selection.uris }
    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionBar(
                count = selection.uris.size,
                onClose = { selection.clear() },
                onSelectAll = { tracks.forEach { if (it.uri !in selection.uris) selection.uris.add(it.uri) } },
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
            TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = { BackButton(onBack) })
        }
        if (tracks.isEmpty()) {
            EmptyState(Icons.Rounded.QueueMusic, emptyText ?: stringResource(R.string.library_empty))
        } else {
            TrackList(
                tracks, vm, selection,
                header = {
                    PlayShuffleRow(tracks, vm) {
                        if (onClear != null) IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.clear_list))
                        }
                    }
                },
                onRemove = onRemove,
            )
        }
    }
}

@Composable
fun PlaylistScreen(id: Long, name: String, vm: MainViewModel, onBack: () -> Unit, isTop: Boolean = true) {
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
        emptyText = stringResource(R.string.empty_playlist),
    )
}

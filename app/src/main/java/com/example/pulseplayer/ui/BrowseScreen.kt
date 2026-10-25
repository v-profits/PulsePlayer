@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.pulseplayer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pulseplayer.R
import com.example.pulseplayer.data.DocEntry

/** Обзор папки (например, на SD-карте): подпапки и аудиофайлы, мультивыбор, плейлисты, избранное. */
@Composable
fun BrowseScreen(vm: MainViewModel, dest: Dest.Browse, onBack: () -> Unit, onOpen: (Dest) -> Unit) {
    val folderUri = remember(dest.uri) { Uri.parse(dest.uri) }
    var entries by remember(dest.uri) { mutableStateOf<List<DocEntry>?>(null) }
    LaunchedEffect(dest.uri) { entries = vm.listFolder(folderUri) }

    val list = entries
    val favUris by vm.favFolderUris.collectAsStateWithLifecycle()
    val busy by vm.importing.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val selection = remember { SelectionState() }
    val files = remember(list) { list.orEmpty().filter { !it.isDir } }
    val selected = list.orEmpty().filter { it.uri.toString() in selection.uris }
    val isFav = dest.uri in favUris
    val self = remember(dest.uri) { DocEntry(folderUri, dest.name, true) }

    BackHandler(selection.active) { selection.clear() }

    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionBar(
                count = selection.uris.size,
                onClose = { selection.clear() },
                onSelectAll = { list.orEmpty().forEach { if (it.uri.toString() !in selection.uris) selection.uris.add(it.uri.toString()) } },
                onPlay = { vm.browserAction(selected, dest.name, BrowserAction.PLAY); selection.clear() },
                onPlayNext = { vm.browserAction(selected, dest.name, BrowserAction.PLAY_NEXT); selection.clear() },
                onQueue = { vm.browserAction(selected, dest.name, BrowserAction.QUEUE); selection.clear() },
                onPlaylist = { vm.browserAction(selected, dest.name, BrowserAction.PLAYLIST); selection.clear() },
                extra = listOf(
                    R.string.fav_folders_add to { vm.browserAction(selected, dest.name, BrowserAction.FAV_FOLDERS); selection.clear() },
                    R.string.fav_tracks_add to { vm.browserAction(selected, dest.name, BrowserAction.FAV_TRACKS); selection.clear() },
                ),
            )
        } else {
            TopAppBar(
                title = { Text(dest.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { vm.toggleFavoriteFolder(dest.uri, dest.name) }) {
                        Icon(
                            if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            stringResource(if (isFav) R.string.favorite_remove else R.string.favorite_add),
                            tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Rounded.Folder, stringResource(R.string.empty_folder))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "header") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { vm.browserAction(listOf(self), dest.name, BrowserAction.PLAY) }) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.play))
                        }
                        FilledTonalButton(onClick = { vm.browserAction(listOf(self), dest.name, BrowserAction.SHUFFLE) }) {
                            Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.shuffle))
                        }
                    }
                }
                items(list, key = { it.uri.toString() }) { e ->
                    val key = e.uri.toString()
                    val sel = key in selection.uris
                    val current = !e.isDir && ui.current?.uri == key
                    ListItem(
                        headlineContent = {
                            Text(
                                if (e.isDir) e.name else e.name.substringBeforeLast('.'),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        leadingContent = {
                            when {
                                sel -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                e.isDir -> Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                current -> Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                                else -> Icon(Icons.Rounded.MusicNote, null)
                            }
                        },
                        trailingContent = {
                            if (e.isDir && !selection.active) {
                                IconButton(onClick = { vm.browserAction(listOf(e), e.name, BrowserAction.PLAY) }) {
                                    Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play_folder))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ),
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                when {
                                    selection.active -> selection.toggle(key)
                                    e.isDir -> onOpen(Dest.Browse(key, e.name))
                                    else -> vm.playFiles(files, files.indexOf(e), dest.name)
                                }
                            },
                            onLongClick = { selection.toggle(key) },
                        ),
                    )
                }
            }
        }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.pulseplayer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pulseplayer.R
import kotlinx.coroutines.launch

enum class HomeTab(val label: Int) {
    TRACKS(R.string.tab_tracks),
    ALBUMS(R.string.tab_albums),
    ARTISTS(R.string.tab_artists),
    FOLDERS(R.string.tab_folders),
    PLAYLISTS(R.string.tab_playlists),
    FILES(R.string.tab_files),
}

private val AUDIO_MIME = arrayOf("audio/*", "application/ogg", "application/x-flac", "application/flac")

@Composable
fun HomeScreen(vm: MainViewModel, granted: Boolean, onGrant: () -> Unit, onOpen: (Dest) -> Unit) {
    val tabs = HomeTab.entries
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val selection = remember { SelectionState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    val page = pager.currentPage
    val selectable = if (tabs[page] == HomeTab.FILES) picked else tracks
    val selectedTracks = selectable.filter { it.uri in selection.uris }

    LaunchedEffect(page) { selection.clear() }
    BackHandler(selection.active) { selection.clear() }
    BackHandler(searching && !selection.active) { searching = false; vm.query.value = "" }

    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionBar(
                count = selection.uris.size,
                onClose = { selection.clear() },
                onSelectAll = { selectable.forEach { if (it.uri !in selection.uris) selection.uris.add(it.uri) } },
                onPlay = { vm.playTracks(selectedTracks); selection.clear() },
                onPlayNext = { vm.playNext(selectedTracks); selection.clear() },
                onQueue = { vm.addToQueue(selectedTracks); selection.clear() },
                onPlaylist = { vm.pickPlaylistFor(selectedTracks); selection.clear() },
            )
        } else {
            var sortMenu by remember { mutableStateOf(false) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(searching) { if (searching) focus.requestFocus() }
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { vm.query.value = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) vm.query.value = ""
                        searching = !searching
                    }) {
                        Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, stringResource(R.string.search))
                    }
                    if (!searching) {
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort))
                            }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                SortMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(m.label)) },
                                        leadingIcon = { if (m == sort) Icon(Icons.Rounded.Check, null) },
                                        onClick = { vm.sort.value = m; sortMenu = false },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { vm.refreshLibrary() }) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.rescan))
                        }
                        IconButton(onClick = { onOpen(Dest.Equalizer) }) {
                            Icon(Icons.Rounded.Tune, stringResource(R.string.equalizer))
                        }
                    }
                },
            )
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        ScrollableTabRow(selectedTabIndex = page, edgePadding = 12.dp, divider = {}) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = page == i,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    text = { Text(stringResource(t.label), maxLines = 1) },
                )
            }
        }

        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 0) { p ->
            when (tabs[p]) {
                HomeTab.TRACKS -> LibraryGate(granted, onGrant) {
                    if (tracks.isEmpty()) {
                        EmptyState(
                            Icons.Rounded.LibraryMusic,
                            stringResource(if (query.isNotBlank()) R.string.no_results else R.string.library_empty),
                        )
                    } else {
                        TrackList(tracks, vm, selection, header = { PlayShuffleRow(tracks, vm) })
                    }
                }
                HomeTab.ALBUMS -> LibraryGate(granted, onGrant) {
                    val albums by vm.albums.collectAsStateWithLifecycle()
                    GroupGrid(albums, R.string.unknown_album) { onOpen(Dest.Tracks(it.title, it.tracks)) }
                }
                HomeTab.ARTISTS -> LibraryGate(granted, onGrant) {
                    val artists by vm.artists.collectAsStateWithLifecycle()
                    val unknown = stringResource(R.string.unknown_artist)
                    GroupList(artists, R.string.unknown_artist, Icons.Rounded.Person) {
                        onOpen(Dest.Tracks(it.title.ifBlank { unknown }, it.tracks))
                    }
                }
                HomeTab.FOLDERS -> LibraryGate(granted, onGrant) {
                    val folders by vm.folders.collectAsStateWithLifecycle()
                    GroupList(folders, R.string.unknown_folder, Icons.Rounded.Folder) {
                        onOpen(Dest.Tracks(it.title, it.tracks))
                    }
                }
                HomeTab.PLAYLISTS -> PlaylistsTab(vm, onOpen)
                HomeTab.FILES -> FilesTab(vm, selection)
            }
        }
    }
}

@Composable
private fun LibraryGate(granted: Boolean, onGrant: () -> Unit, content: @Composable () -> Unit) {
    if (granted) content() else {
        EmptyState(
            Icons.Rounded.LibraryMusic,
            stringResource(R.string.permission_title),
            stringResource(R.string.permission_desc),
        ) { Button(onClick = onGrant) { Text(stringResource(R.string.grant_access)) } }
    }
}

@Composable
private fun GroupGrid(groups: List<Group>, blankLabel: Int, onClick: (Group) -> Unit) {
    if (groups.isEmpty()) { EmptyState(Icons.Rounded.Album, stringResource(R.string.library_empty)); return }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(groups, key = { it.key }) { g ->
            Column(Modifier.clickable { onClick(g) }) {
                Artwork(g.artUri, Modifier.fillMaxWidth().aspectRatio(1f), corner = 18.dp)
                Spacer(Modifier.height(8.dp))
                Text(g.title.ifBlank { stringResource(blankLabel) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                val artist = g.subtitle.ifBlank { stringResource(R.string.unknown_artist) }
                Text("$artist · ${quantityString(R.plurals.tracks_count, g.tracks.size)}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GroupList(
    groups: List<Group>, blankLabel: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (Group) -> Unit,
) {
    if (groups.isEmpty()) { EmptyState(icon, stringResource(R.string.library_empty)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(groups, key = { it.key }) { g ->
            ListItem(
                headlineContent = { Text(g.title.ifBlank { stringResource(blankLabel) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val n = quantityString(R.plurals.tracks_count, g.tracks.size)
                    Text(if (g.subtitle.isBlank()) n else "${g.subtitle} · $n", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = {
                    Box(Modifier.size(52.dp)) {
                        Artwork(g.artUri, Modifier.fillMaxSize(), corner = 26.dp.takeIf { icon == Icons.Rounded.Person } ?: 10.dp)
                    }
                },
                modifier = Modifier.clickable { onClick(g) },
            )
        }
    }
}

@Composable
private fun PlaylistsTab(vm: MainViewModel, onOpen: (Dest) -> Unit) {
    val lists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    val favLabel = stringResource(R.string.favorites)

    if (creating) NameDialog(R.string.new_playlist, "", { creating = false }) { vm.createPlaylist(it); creating = false }
    renaming?.let { (id, name) ->
        NameDialog(R.string.rename, name, { renaming = null }) { vm.renamePlaylist(id, it); renaming = null }
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_playlist_title)) },
            confirmButton = { TextButton(onClick = { vm.deletePlaylist(id); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.new_playlist), color = MaterialTheme.colorScheme.primary) },
                leadingContent = { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { creating = true },
            )
        }
        items(lists, key = { it.playlist.id }) { p ->
            var menu by remember { mutableStateOf(false) }
            val name = if (p.playlist.isSystem) favLabel else p.playlist.name
            ListItem(
                headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(quantityString(R.plurals.tracks_count, p.itemCount)) },
                leadingContent = {
                    Icon(if (p.playlist.isSystem) Icons.Rounded.Favorite else Icons.Rounded.QueueMusic, null,
                        tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    if (!p.playlist.isSystem) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) },
                                onClick = { menu = false; renaming = p.playlist.id to p.playlist.name })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) },
                                onClick = { menu = false; deleting = p.playlist.id })
                        }
                    }
                },
                modifier = Modifier.clickable { onOpen(Dest.Playlist(p.playlist.id, name)) },
            )
        }
    }
}

@Composable
private fun FilesTab(vm: MainViewModel, selection: SelectionState) {
    val picked by vm.picked.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importDocuments(it) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::importTree) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = { filesLauncher.launch(AUDIO_MIME) }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.AudioFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.files_pick_files), maxLines = 1)
            }
            OutlinedButton(onClick = { folderLauncher.launch(null) }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.files_pick_folder), maxLines = 1)
            }
        }
        if (importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.importing), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (picked.isEmpty()) {
            if (!importing) EmptyState(Icons.Rounded.AudioFile, stringResource(R.string.files_empty_title), stringResource(R.string.files_empty_desc))
        } else {
            TrackList(
                picked, vm, selection,
                header = {
                    PlayShuffleRow(picked, vm) {
                        IconButton(onClick = { vm.clearPicked() }) { Icon(Icons.Rounded.Delete, stringResource(R.string.clear_list)) }
                    }
                },
                onRemove = { vm.removePicked(it) },
            )
        }
    }
}

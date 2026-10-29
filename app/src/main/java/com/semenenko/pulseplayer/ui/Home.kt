@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.semenenko.pulseplayer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import com.semenenko.pulseplayer.R
import com.semenenko.pulseplayer.data.AppSettings
import com.semenenko.pulseplayer.audio.EqualizerManager
import com.semenenko.pulseplayer.data.DocEntry
import com.semenenko.pulseplayer.data.PlaylistWithCount
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
fun HomeScreen(vm: MainViewModel, granted: Boolean, onGrant: () -> Unit, isTop: Boolean, onOpen: (Dest) -> Unit) {
    val tabs = HomeTab.entries
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val selection = remember { SelectionState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val eqState by EqualizerManager.state.collectAsStateWithLifecycle()
    val contentFilter by AppSettings.contentFilter.collectAsStateWithLifecycle()

    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val favFolders by vm.favFolders.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playSource by vm.playSource.collectAsStateWithLifecycle()
    val plSort by vm.playlistSort.collectAsStateWithLifecycle()
    val groupSel = remember { SelectionState() }

    val page = pager.currentPage
    val tab = tabs[page]
    val isFolderTab = tab == HomeTab.FILES
    val isGroupTab = tab == HomeTab.ALBUMS || tab == HomeTab.ARTISTS || tab == HomeTab.FOLDERS
    val groups = when (tab) {
        HomeTab.ALBUMS -> albums
        HomeTab.ARTISTS -> artists
        HomeTab.FOLDERS -> folders
        else -> emptyList()
    }
    // Папки, из которых сейчас играет очередь (все их треки в очереди); для вкладок-списков целиком не отмечаем
    val queueUris = remember(ui.queue) { ui.queue.mapTo(HashSet()) { it.uri } }
    val playingFolderKeys = remember(folders, queueUris, ui.current, playSource) {
        val src = playSource
        val fromOtherTab = src != null && src.startsWith("tab:") && !src.startsWith("tab:FOLDERS")
        if (ui.current == null || queueUris.isEmpty() || fromOtherTab) emptySet()
        else folders.filter { g -> g.tracks.isNotEmpty() && g.tracks.all { it.uri in queueUris } }.map { it.key }.toSet()
    }
    val folderEntries = (favFolders + sources).distinctBy { it.uri }
    val selectedFolders = folderEntries.filter { it.uri in groupSel.uris }.map { DocEntry(Uri.parse(it.uri), it.name, true) }
    val selectedTracks = if (groupSel.active) {
        groups.filter { it.key in groupSel.uris }.flatMap { it.tracks }.distinctBy { it.uri }
    } else tracks.filter { it.uri in selection.uris }
    val anySelection = selection.active || groupSel.active
    val contextList = remember(tab, tracks, groups) {
        when {
            tab == HomeTab.TRACKS -> tracks
            isGroupTab -> groups.flatMap { it.tracks }.distinctBy { it.uri }
            else -> emptyList()
        }
    }
    if (isTop) LaunchedEffect(contextList) { vm.contextTracks.value = contextList }
    val clearAll = { selection.clear(); groupSel.clear() }

    LaunchedEffect(page) { clearAll() }
    BackHandler(anySelection) { clearAll() }
    BackHandler(searching && !anySelection) { searching = false; vm.query.value = "" }

    Column(Modifier.fillMaxSize()) {
        if (anySelection) {
            SelectionBar(
                count = if (groupSel.active) groupSel.uris.size else selection.uris.size,
                onClose = clearAll,
                onSelectAll = {
                    when {
                        isFolderTab -> folderEntries.forEach { if (it.uri !in groupSel.uris) groupSel.uris.add(it.uri) }
                        isGroupTab -> groups.forEach { if (it.key !in groupSel.uris) groupSel.uris.add(it.key) }
                        else -> tracks.forEach { if (it.uri !in selection.uris) selection.uris.add(it.uri) }
                    }
                },
                onPlay = {
                    if (isFolderTab) vm.browserAction(selectedFolders, "", BrowserAction.PLAY) else vm.playTracks(selectedTracks)
                    clearAll()
                },
                onPlayNext = {
                    if (isFolderTab) vm.browserAction(selectedFolders, "", BrowserAction.PLAY_NEXT) else vm.playNext(selectedTracks)
                    clearAll()
                },
                onQueue = {
                    if (isFolderTab) vm.browserAction(selectedFolders, "", BrowserAction.QUEUE) else vm.addToQueue(selectedTracks)
                    clearAll()
                },
                onPlaylist = {
                    if (isFolderTab) vm.browserAction(selectedFolders, "", BrowserAction.PLAYLIST) else vm.pickPlaylistFor(selectedTracks)
                    clearAll()
                },
                extra = if (isFolderTab) listOf(
                    R.string.fav_folders_add to { vm.browserAction(selectedFolders, "", BrowserAction.FAV_FOLDERS); clearAll() },
                    R.string.fav_tracks_add to { vm.browserAction(selectedFolders, "", BrowserAction.FAV_TRACKS); clearAll() },
                ) else listOf(
                    R.string.fav_tracks_add to { vm.addToFavorites(selectedTracks); clearAll() },
                    R.string.play_selected_repeat to { vm.playTracksRepeat(selectedTracks); clearAll() },
                ),
            )
        } else {
            var sortMenu by remember { mutableStateOf(false) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(searching) { if (searching) focus.requestFocus() }
            TopAppBar(
                title = {
                    if (searching) {
                        VoiceTextField(
                            value = query, onValueChange = { vm.query.value = it },
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
                                if (tab == HomeTab.PLAYLISTS) {
                                    PlaylistSort.entries.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(m.label)) },
                                            leadingIcon = { if (m == plSort) Icon(Icons.Rounded.Check, null) },
                                            onClick = { vm.playlistSort.value = m; sortMenu = false },
                                        )
                                    }
                                } else SortMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(m.label)) },
                                        leadingIcon = { if (m == sort) Icon(Icons.Rounded.Check, null) },
                                        onClick = { vm.sort.value = m; sortMenu = false },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { onOpen(Dest.Settings) }) {
                            Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
                        }
                        StateIconButton(Icons.Rounded.Tune, stringResource(R.string.equalizer), eqState.params.enabled,
                            modifier = Modifier.padding(end = 8.dp), size = 48.dp, iconSize = 24.dp) { onOpen(Dest.Equalizer) }
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

        if (tab == HomeTab.TRACKS || isGroupTab) {
            ContentFilterRow(contentFilter) { AppSettings.setContentFilter(it) }
            PlayShuffleRow(contextList, vm, "tab:${tab.name}")
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
                        TrackList(tracks, vm, selection, sourceKey = "tab:TRACKS")
                    }
                }
                HomeTab.ALBUMS -> LibraryGate(granted, onGrant) {
                    GroupGrid(albums, R.string.unknown_album, groupSel) { onOpen(Dest.Tracks(it.title, it.tracks)) }
                }
                HomeTab.ARTISTS -> LibraryGate(granted, onGrant) {
                    val unknown = stringResource(R.string.unknown_artist)
                    GroupList(artists, R.string.unknown_artist, Icons.Rounded.Person, groupSel) {
                        onOpen(Dest.Tracks(it.title.ifBlank { unknown }, it.tracks))
                    }
                }
                HomeTab.FOLDERS -> LibraryGate(granted, onGrant) {
                    val root = stringResource(R.string.unknown_folder)
                    GroupList(folders, R.string.unknown_folder, Icons.Rounded.Folder, groupSel,
                        playingKeys = playingFolderKeys, isPlaying = ui.isPlaying) {
                        onOpen(Dest.Tracks(it.title.ifBlank { root }, it.tracks))
                    }
                }
                HomeTab.PLAYLISTS -> PlaylistsTab(vm, onOpen)
                HomeTab.FILES -> FilesTab(vm, onOpen, groupSel)
            }
        }
    }
}

/** Разделение музыки и аудиокниг: «Все / Музыка / Книги» + количество треков. */
@Composable
private fun ContentFilterRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(R.string.filter_all, R.string.filter_music, R.string.filter_books).forEachIndexed { i, res ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(stringResource(res)) })
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

/** Альбомы/исполнители/папки: долгое нажатие — выбор нескольких без открытия. */
@Composable
private fun GroupGrid(groups: List<Group>, blankLabel: Int, selection: SelectionState, onOpen: (Group) -> Unit) {
    if (groups.isEmpty()) { EmptyState(Icons.Rounded.Album, stringResource(R.string.library_empty)); return }
    val cs = MaterialTheme.colorScheme
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.key }) { g ->
            val selected = g.key in selection.uris
            CompositionLocalProvider(
                LocalContentColor provides if (selected) cs.onSecondaryContainer else LocalContentColor.current
            ) {
                Column(
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .background(if (selected) cs.secondaryContainer else Color.Transparent)
                        .combinedClickable(
                            onClick = { if (selection.active) selection.toggle(g.key) else onOpen(g) },
                            onLongClick = { selection.toggle(g.key) },
                        )
                        .padding(8.dp)
                ) {
                    Box {
                        Artwork(g.artUri, Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp)
                        if (selection.active) SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(g.title.ifBlank { stringResource(blankLabel) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    val artist = g.subtitle.ifBlank { stringResource(R.string.unknown_artist) }
                    Text("$artist · ${quantityString(R.plurals.tracks_count, g.tracks.size)}",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) cs.onSecondaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun GroupList(
    groups: List<Group>, blankLabel: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selection: SelectionState,
    playingKeys: Set<String> = emptySet(),
    isPlaying: Boolean = false,
    onOpen: (Group) -> Unit,
) {
    if (groups.isEmpty()) { EmptyState(icon, stringResource(R.string.library_empty)); return }
    val cs = MaterialTheme.colorScheme
    val corner = 10.dp
    LazyColumn(Modifier.fillMaxSize()) {
        items(groups, key = { it.key }) { g ->
            val selected = g.key in selection.uris
            val playing = g.key in playingKeys
            val playingMark: (@Composable () -> Unit)? = if (playing && !selection.active) ({
                Icon(if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, null, tint = cs.primary)
            }) else null
            ListItem(
                headlineContent = { Text(g.title.ifBlank { stringResource(blankLabel) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val n = quantityString(R.plurals.tracks_count, g.tracks.size)
                    Text(if (g.subtitle.isBlank()) n else "${g.subtitle} · $n", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = {
                    Box(Modifier.size(52.dp)) {
                        Artwork(g.artUri, Modifier.fillMaxSize(), corner = corner)
                        if (playing) Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, null, tint = Color.White)
                        }
                        if (selection.active) SelectionBadge(selected, Modifier.align(Alignment.BottomEnd).offset(5.dp, 5.dp))
                    }
                },
                trailingContent = playingMark,
                colors = ListItemDefaults.colors(
                    containerColor = if (selected) cs.secondaryContainer else Color.Transparent,
                    headlineColor = if (selected) cs.onSecondaryContainer else if (playing) cs.primary else cs.onSurface,
                    supportingColor = if (selected) cs.onSecondaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
                ),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = { if (selection.active) selection.toggle(g.key) else onOpen(g) },
                        onLongClick = { selection.toggle(g.key) },
                    ),
            )
        }
    }
}

@Composable
private fun PlaylistsTab(vm: MainViewModel, onOpen: (Dest) -> Unit) {
    val lists by vm.playlistsSorted.collectAsStateWithLifecycle()
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

    @Composable
    fun PlaylistRow(p: PlaylistWithCount) {
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

    val favorites = lists.firstOrNull { it.playlist.isSystem }
    Column(Modifier.fillMaxSize()) {
        // «Избранное» закреплено в верхней строке и не прокручивается вместе с остальными плейлистами
        if (favorites != null) {
            PlaylistRow(favorites)
            HorizontalDivider()
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.new_playlist), color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { creating = true },
                )
            }
            items(lists.filter { !it.playlist.isSystem }, key = { it.playlist.id }) { PlaylistRow(it) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun FilesTab(vm: MainViewModel, onOpen: (Dest) -> Unit, selection: SelectionState) {
    val picked by vm.picked.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val favs by vm.favFolders.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playSource by vm.playSource.collectAsStateWithLifecycle()
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importDocuments(it) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addSourceFolder) }

    @Composable
    fun FolderRow(f: com.semenenko.pulseplayer.data.FolderEntity, removable: Boolean) {
        var menu by remember { mutableStateOf(false) }
        val sel = f.uri in selection.uris
        val playing = ui.current != null && playSource == dirSource(f.uri)
        ListItem(
            headlineContent = {
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) MaterialTheme.colorScheme.primary else Color.Unspecified)
            },
            leadingContent = {
                Icon(
                    when {
                        sel -> Icons.Rounded.CheckCircle
                        playing -> if (ui.isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause
                        f.isFavorite -> Icons.Rounded.Star
                        else -> Icons.Rounded.Folder
                    }, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!selection.active) IconButton(onClick = { if (playing) vm.onMiniPlay() else vm.playFolder(f.uri, f.name) }) {
                        Icon(
                            if (playing && ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(R.string.play_folder),
                            tint = if (playing) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    if (removable && !selection.active) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.remove_folder)) },
                                onClick = { menu = false; vm.removeSource(f) })
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
            modifier = Modifier.combinedClickable(
                onClick = { if (selection.active) selection.toggle(f.uri) else onOpen(Dest.Browse(f.uri, f.name)) },
                onLongClick = { selection.toggle(f.uri) },
            ),
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { folderLauncher.launch(null) }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.files_pick_folder), maxLines = 1)
                }
                OutlinedButton(onClick = { filesLauncher.launch(AUDIO_MIME) }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.AudioFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.files_pick_files), maxLines = 1)
                }
            }
            if (importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.importing), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (picked.isNotEmpty()) item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.selected_files)) },
                supportingContent = { Text(quantityString(R.plurals.tracks_count, picked.size)) },
                leadingContent = { Icon(Icons.Rounded.AudioFile, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onOpen(Dest.Picked) },
            )
        }
        if (favs.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.folders_favorite)) }
            items(favs, key = { "fav" + it.uri }) { FolderRow(it, removable = false) }
        }
        if (sources.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.folders_sources)) }
            items(sources, key = { "src" + it.uri }) { FolderRow(it, removable = true) }
        }
        if (sources.isEmpty() && favs.isEmpty() && picked.isEmpty() && !importing) {
            item {
                Text(stringResource(R.string.folders_hint), Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

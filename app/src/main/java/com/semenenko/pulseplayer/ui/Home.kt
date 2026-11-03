@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.semenenko.pulseplayer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
    val groupSorts by vm.groupSorts.collectAsStateWithLifecycle()
    val playlists by vm.playlistsSorted.collectAsStateWithLifecycle()
    val groupSel = remember { SelectionState() }

    val page = pager.currentPage
    val tab = tabs[page]
    val isFolderTab = tab == HomeTab.FILES
    val isPlaylistTab = tab == HomeTab.PLAYLISTS
    val isGroupTab = tab == HomeTab.ALBUMS || tab == HomeTab.ARTISTS || tab == HomeTab.FOLDERS
    val groups = when (tab) {
        HomeTab.ALBUMS -> albums
        HomeTab.ARTISTS -> artists
        HomeTab.FOLDERS -> folders
        else -> emptyList()
    }
    // Отметки «играет»: какие альбомы, исполнители, папки и плейлисты запущены и какой из них содержит играющий трек
    val membership by vm.membership.collectAsStateWithLifecycle()
    val playGroups by vm.playGroups.collectAsStateWithLifecycle()
    val curUri = ui.current?.uri
    val albumMarks = remember(albums, playGroups, curUri) { groupMarks(albums, playGroups, curUri, "ALBUMS") }
    val artistMarks = remember(artists, playGroups, curUri) { groupMarks(artists, playGroups, curUri, "ARTISTS") }
    val folderMarks = remember(folders, playGroups, curUri) { groupMarks(folders, playGroups, curUri, "FOLDERS") }
    val playlistMarks = remember(playlists, membership, playGroups, curUri) {
        buildPlaylistMarks(playlists, membership, playGroups, curUri)
    }
    val folderEntries = (favFolders + sources).distinctBy { it.uri }
    val selectedFolders = folderEntries.filter { it.uri in groupSel.uris }.map { DocEntry(Uri.parse(it.uri), it.name, true) }
    val selectedTracks = if (groupSel.active) {
        groups.filter { it.key in groupSel.uris }.flatMap { it.tracks }.distinctBy { it.uri }
    } else tracks.filter { it.uri in selection.uris }
    val selectedPlaylistIds = playlists.filter { it.playlist.id.toString() in groupSel.uris }.map { it.playlist.id }
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
            val allSelected = when {
                isFolderTab -> folderEntries.isNotEmpty() && folderEntries.all { it.uri in groupSel.uris }
                isPlaylistTab -> playlists.isNotEmpty() && playlists.all { it.playlist.id.toString() in groupSel.uris }
                isGroupTab -> groups.isNotEmpty() && groups.all { it.key in groupSel.uris }
                else -> tracks.isNotEmpty() && tracks.all { it.uri in selection.uris }
            }
            SelectionBar(
                count = if (groupSel.active) groupSel.uris.size else selection.uris.size,
                onClose = clearAll,
                onSelectAll = {
                    if (allSelected) clearAll()   // повторное нажатие снимает выделение
                    else when {
                        isFolderTab -> folderEntries.forEach { if (it.uri !in groupSel.uris) groupSel.uris.add(it.uri) }
                        isPlaylistTab -> playlists.forEach { groupSel.uris.add(it.playlist.id.toString()) }
                        isGroupTab -> groups.forEach { if (it.key !in groupSel.uris) groupSel.uris.add(it.key) }
                        else -> tracks.forEach { if (it.uri !in selection.uris) selection.uris.add(it.uri) }
                    }
                },
                allSelected = allSelected,
                onPlayNext = {
                    when {
                        isFolderTab -> vm.browserAction(selectedFolders, "", BrowserAction.PLAY_NEXT)
                        isPlaylistTab -> vm.withPlaylistTracks(selectedPlaylistIds) { vm.playNext(it) }
                        else -> vm.playNext(selectedTracks)
                    }
                    clearAll()
                },
                onQueue = {
                    when {
                        isFolderTab -> vm.browserAction(selectedFolders, "", BrowserAction.QUEUE)
                        isPlaylistTab -> vm.withPlaylistTracks(selectedPlaylistIds) { vm.addToQueue(it) }
                        else -> vm.addToQueue(selectedTracks)
                    }
                    clearAll()
                },
                onPlaylist = {
                    when {
                        isFolderTab -> vm.browserAction(selectedFolders, "", BrowserAction.PLAYLIST)
                        isPlaylistTab -> vm.withPlaylistTracks(selectedPlaylistIds) { vm.pickPlaylistFor(it) }
                        else -> vm.pickPlaylistFor(selectedTracks)
                    }
                    clearAll()
                },
                extra = when {
                    isFolderTab -> listOf(
                        R.string.fav_folders_add to { vm.browserAction(selectedFolders, "", BrowserAction.FAV_FOLDERS); clearAll() },
                        R.string.fav_tracks_add to { vm.browserAction(selectedFolders, "", BrowserAction.FAV_TRACKS); clearAll() },
                    )
                    isPlaylistTab -> listOf(
                        R.string.fav_tracks_add to { vm.withPlaylistTracks(selectedPlaylistIds) { vm.addToFavorites(it) }; clearAll() },
                    )
                    else -> listOf(
                        R.string.fav_tracks_add to { vm.addToFavorites(selectedTracks); clearAll() },
                    )
                },
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
                        // на вкладке «Файлы» сортировать нечего — значок скрыт
                        if (tab != HomeTab.FILES) Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort))
                            }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                when {
                                    tab == HomeTab.PLAYLISTS -> PlaylistSort.entries.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(m.label)) },
                                            leadingIcon = { if (m == plSort) Icon(Icons.Rounded.Check, null) },
                                            onClick = { vm.setPlaylistSort(m); sortMenu = false },
                                        )
                                    }
                                    isGroupTab -> GroupSort.entries.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(m.label)) },
                                            leadingIcon = { if (m == (groupSorts[tab.name] ?: GroupSort.NAME)) Icon(Icons.Rounded.Check, null) },
                                            onClick = { vm.setGroupSort(tab.name, m); sortMenu = false },
                                        )
                                    }
                                    else -> SortMode.entries.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(m.label)) },
                                            leadingIcon = { if (m == sort) Icon(Icons.Rounded.Check, null) },
                                            onClick = { vm.setSort(m); sortMenu = false },
                                        )
                                    }
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
        }
        // Единственная кнопка «Играть»: без выделения играет всю вкладку, с выделением — только выделенное
        val barCount = when {
            isPlaylistTab -> if (groupSel.active) playlists.filter { it.playlist.id.toString() in groupSel.uris }.sumOf { it.itemCount }
                             else playlists.sumOf { it.itemCount }
            isFolderTab -> if (groupSel.active) selectedFolders.size else folderEntries.size
            anySelection -> selectedTracks.size
            else -> contextList.size
        }
        if ((tab == HomeTab.TRACKS || isGroupTab || isPlaylistTab || isFolderTab) && barCount > 0) {
            ListPlayBar(barCount, if (isFolderTab) R.plurals.folders_count else R.plurals.tracks_count) {
                when {
                    isFolderTab -> vm.browserAction(
                        if (groupSel.active) selectedFolders else folderEntries.map { DocEntry(Uri.parse(it.uri), it.name, true) },
                        "", BrowserAction.PLAY,
                    )
                    isPlaylistTab -> {
                        val ids = if (groupSel.active) selectedPlaylistIds else playlists.map { it.playlist.id }
                        val groups = PlayGroups("PLAYLISTS", ids.mapTo(HashSet()) { it.toString() })   // копия до сброса выделения
                        vm.withPlaylistTracks(ids) { vm.playFromBar(it, "sel:PLAYLISTS", groups) }
                    }
                    anySelection -> vm.playFromBar(
                        selectedTracks,
                        if (isGroupTab) "sel:${tab.name}" else null,
                        if (isGroupTab) PlayGroups(tab.name, groupSel.uris.toSet()) else null,
                    )
                    else -> vm.playFromBar(
                        contextList, listSig("tab:${tab.name}", contextList),
                        if (isGroupTab) PlayGroups(tab.name, groups.mapTo(HashSet()) { it.key }) else null,
                    )
                }
                clearAll()
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
                        TrackList(tracks, vm, selection, sourceKey = "tab:TRACKS")
                    }
                }
                HomeTab.ALBUMS -> LibraryGate(granted, onGrant) {
                    GroupGrid(albums, R.string.unknown_album, groupSel, albumMarks, ui.isPlaying, vm) { onOpen(Dest.Tracks(it.title, it.tracks, PlayGroups("ALBUMS", setOf(it.key)))) }
                }
                HomeTab.ARTISTS -> LibraryGate(granted, onGrant) {
                    val unknown = stringResource(R.string.unknown_artist)
                    GroupList(artists, R.string.unknown_artist, Icons.Rounded.Person, groupSel,
                        marks = artistMarks, isPlaying = ui.isPlaying, vm = vm) {
                        onOpen(Dest.Tracks(it.title.ifBlank { unknown }, it.tracks, PlayGroups("ARTISTS", setOf(it.key))))
                    }
                }
                HomeTab.FOLDERS -> LibraryGate(granted, onGrant) {
                    val root = stringResource(R.string.unknown_folder)
                    GroupList(folders, R.string.unknown_folder, Icons.Rounded.Folder, groupSel,
                        marks = folderMarks, isPlaying = ui.isPlaying, vm = vm) {
                        onOpen(Dest.Tracks(it.title.ifBlank { root }, it.tracks, PlayGroups("FOLDERS", setOf(it.key))))
                    }
                }
                HomeTab.PLAYLISTS -> PlaylistsTab(vm, onOpen, groupSel, playlistMarks, ui.isPlaying)
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

/** Какие группы сейчас в очереди ([queued]) и какая из них содержит играющий трек ([current]). */
private class PlayMarks(val queued: Set<String>, val current: String?) {
    companion object { val None = PlayMarks(emptySet(), null) }
}

private fun groupMarks(groups: List<Group>, pg: PlayGroups?, current: String?, tab: String): PlayMarks {
    if (pg == null || pg.tab != tab || current == null) return PlayMarks.None
    return PlayMarks(
        pg.keys,
        groups.firstOrNull { g -> g.key in pg.keys && g.tracks.any { it.uri == current } }?.key,
    )
}

private fun buildPlaylistMarks(
    lists: List<PlaylistWithCount>, membership: Map<Long, Set<String>>, pg: PlayGroups?, current: String?,
): PlayMarks {
    if (pg == null || pg.tab != "PLAYLISTS" || current == null) return PlayMarks.None
    // «Избранное» содержит много чего, поэтому текущим по возможности считаем обычный плейлист
    val cur = lists.sortedBy { it.playlist.isSystem }
        .firstOrNull { it.playlist.id.toString() in pg.keys && current in membership[it.playlist.id].orEmpty() }
    return PlayMarks(pg.keys, cur?.playlist?.id?.toString())
}

/** Индекс группы, к которой надо прокрутить: среди играющих, иначе просто содержащей трек. */
private fun revealIndex(groups: List<Group>, marks: PlayMarks, uri: String): Int =
    groups.indexOfFirst { g -> g.key in marks.queued && g.tracks.any { it.uri == uri } }
        .takeIf { it >= 0 } ?: groups.indexOfFirst { g -> g.tracks.any { it.uri == uri } }

/** Альбомы/исполнители/папки: долгое нажатие — выбор нескольких без открытия. */
@Composable
private fun GroupGrid(
    groups: List<Group>, blankLabel: Int, selection: SelectionState,
    marks: PlayMarks, isPlaying: Boolean, vm: MainViewModel,
    onOpen: (Group) -> Unit,
) {
    if (groups.isEmpty()) { EmptyState(Icons.Rounded.Album, stringResource(R.string.library_empty)); return }
    val cs = MaterialTheme.colorScheme
    val gridState = rememberLazyGridState()
    val currentGroups by rememberUpdatedState(groups)
    val currentMarks by rememberUpdatedState(marks)
    LaunchedEffect(Unit) {
        vm.reveal.collectLatest { uri ->
            delay(80)   // отметки обновляются после запуска воспроизведения
            gridState.revealItem(revealIndex(currentGroups, currentMarks, uri))
        }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.key }) { g ->
            val selected = g.key in selection.uris
            val queued = g.key in marks.queued
            val playing = g.key == marks.current
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
                        if (playing && !selection.active) Box(
                            Modifier.matchParentSize().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, null,
                                Modifier.size(36.dp), tint = Color.White)
                        } else if (queued && !selection.active) Box(
                            Modifier.align(Alignment.BottomStart).padding(6.dp).size(26.dp)
                                .clip(RoundedCornerShape(50)).background(cs.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.QueueMusic, null, Modifier.size(16.dp), tint = cs.onPrimary)
                        }
                        if (selection.active) SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(g.title.ifBlank { stringResource(blankLabel) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (queued && !selected) cs.primary else Color.Unspecified)
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
    marks: PlayMarks = PlayMarks.None,
    isPlaying: Boolean = false,
    vm: MainViewModel,
    onOpen: (Group) -> Unit,
) {
    if (groups.isEmpty()) { EmptyState(icon, stringResource(R.string.library_empty)); return }
    val cs = MaterialTheme.colorScheme
    val corner = 10.dp
    val listState = rememberLazyListState()
    val currentGroups by rememberUpdatedState(groups)
    val currentMarks by rememberUpdatedState(marks)
    LaunchedEffect(Unit) {
        // после ручного переключения трека показываем папку (исполнителя), в которой он лежит
        vm.reveal.collectLatest { uri ->
            delay(80)
            listState.revealItem(revealIndex(currentGroups, currentMarks, uri))
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(groups, key = { it.key }) { g ->
            val selected = g.key in selection.uris
            val queued = g.key in marks.queued
            val playing = g.key == marks.current
            val playingMark: (@Composable () -> Unit)? = if (queued && !selection.active) ({
                Icon(
                    when { playing && isPlaying -> Icons.Rounded.GraphicEq; playing -> Icons.Rounded.Pause; else -> Icons.Rounded.QueueMusic },
                    null, tint = cs.primary,
                )
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
                    headlineColor = if (selected) cs.onSecondaryContainer else if (queued) cs.primary else cs.onSurface,
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
private fun PlaylistsTab(
    vm: MainViewModel, onOpen: (Dest) -> Unit, selection: SelectionState,
    marks: PlayMarks, isPlaying: Boolean,
) {
    val lists by vm.playlistsSorted.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    val favLabel = stringResource(R.string.favorites)
    val membership by vm.membership.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val currentLists by rememberUpdatedState(lists)
    val currentMarks by rememberUpdatedState(marks)
    val currentMembership by rememberUpdatedState(membership)
    LaunchedEffect(Unit) {
        // после ручного переключения трека показываем плейлист, из которого он играет (+1 — строка «Новый плейлист»)
        vm.reveal.collectLatest { uri ->
            delay(80)
            val i = currentLists.filter { !it.playlist.isSystem }.indexOfFirst { p ->
                p.playlist.id.toString() in currentMarks.queued && uri in currentMembership[p.playlist.id].orEmpty()
            }
            if (i >= 0) listState.revealItem(i + 1)
        }
    }

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
        val key = p.playlist.id.toString()
        val sel = key in selection.uris
        val queued = key in marks.queued
        val playing = key == marks.current
        ListItem(
            headlineContent = {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (queued && !sel) MaterialTheme.colorScheme.primary else Color.Unspecified)
            },
            supportingContent = { Text(quantityString(R.plurals.tracks_count, p.itemCount)) },
            leadingContent = {
                Icon(
                    when {
                        sel -> Icons.Rounded.CheckCircle
                        playing -> if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause
                        p.playlist.isSystem -> Icons.Rounded.Favorite
                        else -> Icons.Rounded.QueueMusic
                    }, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (queued && !selection.active) Icon(
                        when { playing && isPlaying -> Icons.Rounded.GraphicEq; playing -> Icons.Rounded.Pause; else -> Icons.Rounded.QueueMusic },
                        null, tint = MaterialTheme.colorScheme.primary,
                    )
                    if (!p.playlist.isSystem && !selection.active) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) },
                                onClick = { menu = false; renaming = p.playlist.id to p.playlist.name })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) },
                                onClick = { menu = false; deleting = p.playlist.id })
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
            modifier = Modifier.combinedClickable(
                onClick = { if (selection.active) selection.toggle(key) else onOpen(Dest.Playlist(p.playlist.id, name)) },
                onLongClick = { selection.toggle(key) },
            ),
        )
    }

    val favorites = lists.firstOrNull { it.playlist.isSystem }
    Column(Modifier.fillMaxSize()) {
        // «Избранное» закреплено в верхней строке и не прокручивается вместе с остальными плейлистами
        if (favorites != null) {
            PlaylistRow(favorites)
            HorizontalDivider()
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
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

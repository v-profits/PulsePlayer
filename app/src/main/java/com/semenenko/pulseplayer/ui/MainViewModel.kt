package com.semenenko.pulseplayer.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.semenenko.pulseplayer.PlaybackService
import com.semenenko.pulseplayer.R
import com.semenenko.pulseplayer.data.AppDb
import com.semenenko.pulseplayer.data.AppSettings
import com.semenenko.pulseplayer.data.DocEntry
import com.semenenko.pulseplayer.data.FAVORITES_ID
import com.semenenko.pulseplayer.data.AudioFormats
import com.semenenko.pulseplayer.data.FolderEntity
import com.semenenko.pulseplayer.data.isUnsupportedFormat
import com.semenenko.pulseplayer.data.loadSort
import com.semenenko.pulseplayer.data.MediaScanner
import com.semenenko.pulseplayer.data.NaturalOrder
import com.semenenko.pulseplayer.data.PlaylistEntity
import com.semenenko.pulseplayer.data.PlaylistItem
import com.semenenko.pulseplayer.data.PlaylistWithCount
import com.semenenko.pulseplayer.data.Track
import com.semenenko.pulseplayer.data.TrackInfo
import com.semenenko.pulseplayer.data.toItem
import com.semenenko.pulseplayer.data.toMediaItem
import com.semenenko.pulseplayer.data.toTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortMode(@StringRes val label: Int) {
    TITLE(R.string.sort_title),
    TITLE_DESC(R.string.sort_title_desc),
    ARTIST(R.string.sort_artist),
    ALBUM(R.string.sort_album),
    FOLDER(R.string.sort_folder),
    FILENAME(R.string.sort_filename),
    RECENT(R.string.sort_recent),
    OLDEST(R.string.sort_oldest),
    DURATION(R.string.sort_duration),
    SHORTEST(R.string.sort_shortest),
}

/** Сортировка вкладок «Альбомы», «Исполнители» и «Папки». */
enum class GroupSort(@StringRes val label: Int) {
    NAME(R.string.sort_title),
    NAME_DESC(R.string.sort_title_desc),
    COUNT(R.string.sort_pl_count),
    COUNT_ASC(R.string.sort_count_asc),
}

/** Сортировка вкладки «Плейлисты» («Избранное» всегда закреплено сверху и в сортировке не участвует). */
enum class PlaylistSort(@StringRes val label: Int) {
    CREATED(R.string.sort_pl_created),
    CREATED_ASC(R.string.sort_pl_created_asc),
    NAME(R.string.sort_title),
    NAME_DESC(R.string.sort_title_desc),
    COUNT(R.string.sort_pl_count),
    COUNT_ASC(R.string.sort_count_asc),
}

private val TitleOrder = Comparator<Track> { a, b -> NaturalOrder.compare(a.title, b.title) }

private fun List<Group>.sortedGroups(mode: GroupSort): List<Group> {
    val byName = Comparator<Group> { a, b -> NaturalOrder.compare(a.title, b.title) }
    return when (mode) {
        GroupSort.NAME -> sortedWith(byName)
        GroupSort.NAME_DESC -> sortedWith(byName.reversed())
        GroupSort.COUNT -> sortedWith(compareByDescending<Group> { it.tracks.size }.then(byName))
        GroupSort.COUNT_ASC -> sortedWith(compareBy<Group> { it.tracks.size }.then(byName))
    }
}

/**
 * Какие элементы вкладки ([tab]: ALBUMS / ARTISTS / FOLDERS / PLAYLISTS) запущены на воспроизведение: ключи выбранных
 * или все, если играли вкладку целиком. Отметки «играет» строятся по этому, а не по очереди плеера: контроллер из-за
 * ограничения на размер передаваемых данных отдаёт только часть большой очереди.
 */
data class PlayGroups(val tab: String, val keys: Set<String>)

enum class BrowserAction { PLAY, SHUFFLE, PLAY_NEXT, QUEUE, PLAYLIST, FAV_TRACKS, FAV_FOLDERS }

/** Источник воспроизведения «папка из файловой системы» (вкладка «Файлы»). */
fun dirSource(uri: String) = "dir:$uri"

/** Порядок по имени файла с учётом чисел: 1, 2, 3 … 10, 11, 12. */
val FileOrder = Comparator<Track> { a, b ->
    NaturalOrder.compare(a.fileName.ifBlank { a.title }, b.fileName.ifBlank { b.title })
}

data class Group(
    val key: String,
    val title: String,
    val subtitle: String,
    val artUri: String?,
    val tracks: List<Track>,
)

data class PlayerUi(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = MediaScanner(app)
    private val dao = AppDb.get(app).dao()

    // ---------- Библиотека ----------
    private val _library = MutableStateFlow<List<Track>>(emptyList())
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val query = MutableStateFlow("")
    // Все варианты сортировки запоминаются и восстанавливаются при следующем запуске
    val sort = MutableStateFlow(loadSort("tracks", SortMode.TITLE))
    fun setSort(m: SortMode) { sort.value = m; AppSettings.saveSort("tracks", m) }

    /** Сортировка вкладок-групп по имени вкладки: ALBUMS / ARTISTS / FOLDERS. */
    val groupSorts = MutableStateFlow(
        listOf("ALBUMS", "ARTISTS", "FOLDERS").associateWith { loadSort("group_$it", GroupSort.NAME) }
    )
    fun setGroupSort(tab: String, m: GroupSort) {
        groupSorts.update { it + (tab to m) }
        AppSettings.saveSort("group_$tab", m)
    }

    /** Библиотека после фильтров настроек: скрытие коротких и разделение «музыка / книги». */
    private val filteredLibrary = combine(
        _library, AppSettings.minLength, AppSettings.contentFilter, AppSettings.bookMinutes,
    ) { all, minLen, mode, bookMin ->
        all.filter { t ->
            val isBook = t.isBook || t.durationMs >= bookMin * 60_000L
            (minLen <= 0 || t.durationMs >= minLen * 1000L) && when (mode) {
                1 -> !isBook
                2 -> isBook
                else -> true
            }
        }
    }

    /** Треки библиотеки в заведомо неподдерживаемых форматах: uri → расширение (WMA, APE и т. д.); нужны и для плейлистов, где расширения нет. */
    val unsupportedFormats: StateFlow<Map<String, String>> = _library.map { all ->
        all.filter { it.isUnsupportedFormat() }.associate { it.uri to it.extension.uppercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun isUnsupported(t: Track) = t.isUnsupportedFormat() || t.uri in unsupportedFormats.value
    private fun formatOf(t: Track) = unsupportedFormats.value[t.uri] ?: t.extension.uppercase().ifBlank { "?" }

    /** Оставляет только треки, которые плеер может воспроизвести; о пропущенных сообщает. */
    private fun playable(list: List<Track>): List<Track> {
        val ok = list.filterNot(::isUnsupported)
        val skipped = list.size - ok.size
        if (skipped > 0) {
            val app = getApplication<Application>()
            _errors.tryEmit(
                if (ok.isEmpty()) app.getString(R.string.msg_no_supported)
                else app.getString(R.string.msg_skipped_unsupported, skipped)
            )
        }
        return ok
    }

    val tracks: StateFlow<List<Track>> = combine(filteredLibrary, query, sort) { lib, q, s ->
        val f = if (q.isBlank()) lib else lib.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
        when (s) {
            SortMode.TITLE -> f.sortedWith(TitleOrder)
            SortMode.TITLE_DESC -> f.sortedWith(TitleOrder.reversed())
            SortMode.ALBUM -> f.sortedWith(Comparator { a, b ->
                NaturalOrder.compare(a.album, b.album).takeIf { it != 0 } ?: FileOrder.compare(a, b)
            })
            SortMode.FOLDER -> f.sortedWith(Comparator { a, b ->
                NaturalOrder.compare(a.folder, b.folder).takeIf { it != 0 } ?: FileOrder.compare(a, b)
            })
            SortMode.FILENAME -> f.sortedWith(FileOrder)
            SortMode.ARTIST -> f.sortedWith(Comparator { a, b ->
                NaturalOrder.compare(a.artist, b.artist).takeIf { it != 0 } ?: NaturalOrder.compare(a.title, b.title)
            })
            SortMode.RECENT -> f.sortedByDescending { it.dateAdded }
            SortMode.OLDEST -> f.sortedBy { it.dateAdded }
            SortMode.DURATION -> f.sortedByDescending { it.durationMs }
            SortMode.SHORTEST -> f.sortedBy { it.durationMs }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Group>> = combine(tracks, groupSorts) { list, gs ->
        list.filter { it.album.isNotBlank() }.groupBy { it.album }
            .map { (k, v) -> Group("a:$k", k, v.first().artist, v.firstNotNullOfOrNull { it.artUri }, v.sortedWith(FileOrder)) }
            .sortedGroups(gs["ALBUMS"] ?: GroupSort.NAME)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val artists: StateFlow<List<Group>> = combine(tracks, groupSorts) { list, gs ->
        list.groupBy { it.artist }
            .map { (k, v) -> Group("r:$k", k, "", v.firstNotNullOfOrNull { it.artUri }, v) }
            .sortedGroups(gs["ARTISTS"] ?: GroupSort.NAME)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folders: StateFlow<List<Group>> = combine(tracks, groupSorts) { list, gs ->
        list.groupBy { it.folder }
            .map { (k, v) -> Group("f:$k", k.substringAfterLast('/'), k, v.firstNotNullOfOrNull { it.artUri }, v.sortedWith(FileOrder)) }
            .sortedGroups(gs["FOLDERS"] ?: GroupSort.NAME)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun rescan() {
        _messages.tryEmit(R.string.msg_rescan)
        refreshLibrary()
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            _loading.value = true
            runCatching { scanner.queryLibrary() }.onSuccess { _library.value = it }
            _loading.value = false
        }
    }

    // ---------- Файлы (системный выбор) ----------
    private val _picked = MutableStateFlow<List<Track>>(emptyList())
    val picked: StateFlow<List<Track>> = _picked.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun importDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            val cr = getApplication<Application>().contentResolver
            val list = uris.map { u ->
                runCatching { cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                scanner.readDocument(u)
            }
            mergePicked(list)
            _importing.value = false
        }
    }

    fun importTree(tree: Uri) {
        viewModelScope.launch {
            _importing.value = true
            val cr = getApplication<Application>().contentResolver
            runCatching { cr.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val list = scanner.scanTree(tree).map { scanner.readDocument(it) }
            mergePicked(list)
            _importing.value = false
        }
    }

    private fun mergePicked(new: List<Track>) = _picked.update { (it + new).distinctBy { t -> t.uri } }
    fun clearPicked() { _picked.value = emptyList() }
    fun removePicked(t: Track) = _picked.update { l -> l.filter { it.uri != t.uri } }

    fun playExternal(uri: Uri) {
        viewModelScope.launch {
            val t = scanner.readDocument(uri)
            playTracks(listOf(t))
        }
    }

    // ---------- Плейлисты ----------
    val playlists = dao.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlistSort = MutableStateFlow(loadSort("playlists", PlaylistSort.CREATED))
    fun setPlaylistSort(m: PlaylistSort) { playlistSort.value = m; AppSettings.saveSort("playlists", m) }
    /** Плейлисты: «Избранное» первым, остальные — в выбранном порядке. */
    val playlistsSorted: StateFlow<List<PlaylistWithCount>> = combine(playlists, playlistSort) { all, mode ->
        val fav = all.filter { it.playlist.isSystem }
        val rest = all.filterNot { it.playlist.isSystem }
        fav + when (mode) {
            PlaylistSort.CREATED -> rest.sortedByDescending { it.playlist.createdAt }
            PlaylistSort.CREATED_ASC -> rest.sortedBy { it.playlist.createdAt }
            PlaylistSort.NAME -> rest.sortedWith(Comparator { a, b -> NaturalOrder.compare(a.playlist.name, b.playlist.name) })
            PlaylistSort.NAME_DESC -> rest.sortedWith(Comparator { a, b -> NaturalOrder.compare(b.playlist.name, a.playlist.name) })
            PlaylistSort.COUNT -> rest.sortedByDescending { it.itemCount }
            PlaylistSort.COUNT_ASC -> rest.sortedBy { it.itemCount }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favoriteUris: StateFlow<Set<String>> = dao.observeFavoriteUris().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val playlistUris: StateFlow<Set<String>> = dao.observePlaylistUris().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    /** id плейлиста → множество uri его треков (включая «Избранное»). */
    val membership: StateFlow<Map<Long, Set<String>>> = dao.observeMembership()
        .map { rows -> rows.groupBy({ it.playlistId }, { it.uri }).mapValues { it.value.toSet() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val playlistPicker = MutableStateFlow<List<Track>?>(null)

    fun removeTracksFromPlaylist(id: Long, list: List<Track>) =
        viewModelScope.launch { list.forEach { dao.deleteByUri(id, it.uri) } }

    fun playlistItems(id: Long): Flow<List<PlaylistItem>> = dao.observeItems(id)

    fun pickPlaylistFor(list: List<Track>) { playlistPicker.value = list }

    fun createPlaylist(name: String, thenAdd: List<Track> = emptyList()) {
        viewModelScope.launch {
            val id = dao.insertPlaylist(PlaylistEntity(name = name.trim().ifBlank { "Playlist" }))
            if (thenAdd.isNotEmpty()) addToPlaylistInternal(id, thenAdd, R.string.msg_added_playlist)
        }
    }

    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { dao.renamePlaylist(id, name.trim()) }
    fun deletePlaylist(id: Long) = viewModelScope.launch { dao.deletePlaylist(id) }
    fun addToPlaylist(id: Long, list: List<Track>) =
        viewModelScope.launch { addToPlaylistInternal(id, list, R.string.msg_added_playlist) }

    fun addToFavorites(list: List<Track>) =
        viewModelScope.launch { addToPlaylistInternal(FAVORITES_ID, list, R.string.msg_added_favorites) }
    fun removeFromPlaylist(id: Long, t: Track) = viewModelScope.launch { dao.deleteByUri(id, t.uri) }

    private suspend fun addToPlaylistInternal(id: Long, list: List<Track>, message: Int) {
        val existing = dao.uris(id).toSet()
        var pos = dao.maxPosition(id)
        val items = list.distinctBy { it.uri }.filter { it.uri !in existing }.map { it.toItem(id, ++pos) }
        if (items.isNotEmpty()) dao.insertItems(items)
        _messages.tryEmit(if (items.isEmpty()) R.string.msg_already_added else message)
    }

    fun toggleFavorite(t: Track) {
        viewModelScope.launch {
            if (t.uri in favoriteUris.value) dao.deleteByUri(FAVORITES_ID, t.uri)
            else dao.insertItems(listOf(t.toItem(FAVORITES_ID, dao.maxPosition(FAVORITES_ID) + 1)))
        }
    }

    // ---------- Папки (SAF): обзор, источники, избранные папки ----------
    val sources = dao.observeSources().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favFolders = dao.observeFavFolders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favFolderUris: StateFlow<Set<String>> = favFolders.map { l -> l.map { it.uri }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    suspend fun listFolder(uri: Uri): List<DocEntry> = scanner.listChildren(uri)

    fun addSourceFolder(tree: Uri) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val key = tree.toString()
            val ex = dao.folder(key)
            dao.upsertFolder((ex ?: FolderEntity(key, scanner.nameOf(tree))).copy(isSource = true))
        }
    }

    fun removeSource(f: FolderEntity) {
        viewModelScope.launch {
            if (f.isFavorite) dao.upsertFolder(f.copy(isSource = false)) else dao.deleteFolder(f.uri)
        }
    }

    fun toggleFavoriteFolder(uri: String, name: String) {
        viewModelScope.launch {
            val ex = dao.folder(uri)
            when {
                ex == null -> dao.upsertFolder(FolderEntity(uri, name, isFavorite = true))
                ex.isFavorite -> if (ex.isSource) dao.upsertFolder(ex.copy(isFavorite = false)) else dao.deleteFolder(uri)
                else -> dao.upsertFolder(ex.copy(isFavorite = true))
            }
        }
    }

    fun playFolder(uri: String, name: String) =
        browserAction(listOf(DocEntry(Uri.parse(uri), name, true)), name, BrowserAction.PLAY)

    fun playFiles(files: List<DocEntry>, index: Int, folderName: String) {
        playTracks(files.map { scanner.entryToTrack(it, folderName) }, index)
    }

    /** Действие над выбранными папками/файлами: папки раскрываются рекурсивно в список треков. */
    fun browserAction(entries: List<DocEntry>, parentName: String, action: BrowserAction) {
        viewModelScope.launch {
            if (action == BrowserAction.FAV_FOLDERS) {
                entries.filter { it.isDir }.forEach { e ->
                    val ex = dao.folder(e.uri.toString())
                    if (ex == null) dao.upsertFolder(FolderEntity(e.uri.toString(), e.name, isFavorite = true))
                    else if (!ex.isFavorite) dao.upsertFolder(ex.copy(isFavorite = true))
                }
                return@launch
            }
            _importing.value = true
            val tracks = ArrayList<Track>()
            for (e in entries) {
                if (e.isDir) scanner.collectAudio(e.uri).forEach { tracks += scanner.entryToTrack(it, e.name) }
                else tracks += scanner.entryToTrack(e, parentName)
            }
            _importing.value = false
            val list = tracks.distinctBy { it.uri }
            if (list.isEmpty()) return@launch
            // запоминаем папку-источник, чтобы в списке папок показать, что в ней идёт воспроизведение
            val src = entries.singleOrNull()?.takeIf { it.isDir }?.let { dirSource(it.uri.toString()) }
            when (action) {
                BrowserAction.PLAY -> playList(list, src)   // при включённом «перемешать» старт со случайного трека
                BrowserAction.SHUFFLE -> playTracks(list, shuffle = true, source = src)
                BrowserAction.PLAY_NEXT -> playNext(list)
                BrowserAction.QUEUE -> addToQueue(list)
                BrowserAction.PLAYLIST -> pickPlaylistFor(list)
                BrowserAction.FAV_TRACKS -> addToPlaylistInternal(FAVORITES_ID, list, R.string.msg_added_favorites)
                BrowserAction.FAV_FOLDERS -> Unit
            }
        }
    }

    // ---------- Плеер ----------
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: Flow<Int> = _messages

    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: Flow<String> = _errors
    private var skipStreak = 0

    // После ручного переключения (следующий/предыдущий, выбор из очереди, кнопки уведомления и гарнитуры)
    // списки прокручиваются к играющему элементу. Автопереход к следующему треку список не трогает.
    private var revealPending = false
    private val _reveal = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val reveal: SharedFlow<String> = _reveal.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) revealPending = true
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) skipStreak = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            val app = getApplication<Application>()
            val item = controller?.currentMediaItem
            val title = item?.mediaMetadata?.title?.toString().orEmpty()
            val ext = item?.mediaId?.let { id ->
                unsupportedFormats.value[id] ?: _library.value.firstOrNull { it.uri == id }?.extension?.uppercase()
            }.orEmpty()
            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                // Формат контейнера не поддерживается (например WMA/APE): понятное сообщение вместо технического
                val mime = item?.mediaId?.let { id -> runCatching { app.contentResolver.getType(Uri.parse(id)) }.getOrNull() }
                _errors.tryEmit(app.getString(R.string.unsupported_format, title, ext.ifBlank { mime?.ifBlank { null } ?: "?" }))
            } else if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
            ) {
                // Контейнер прочитан, но на этом телефоне нет подходящего декодера (ALAC, AC3, DTS и т. п.)
                _errors.tryEmit(app.getString(R.string.unsupported_device, title, ext.ifBlank { "?" }))
            } else {
                val why = error.cause?.let { " · ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
                _errors.tryEmit(app.getString(R.string.playback_error, (error.errorCodeName + why).take(160)))
            }
            val c = controller ?: return
            // нечитаемый/битый файл — пропускаем к следующему (не более 5 подряд)
            if (error.errorCode in 2000..4999 && skipStreak < 5 && c.hasNextMediaItem()) {
                skipStreak++
                c.seekToNextMediaItem(); c.prepare(); c.play()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            sync(player, events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
    }

    init {
        viewModelScope.launch {
            dao.insertPlaylistIgnore(PlaylistEntity(id = FAVORITES_ID, name = "Favorites", isSystem = true))
        }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            sync(c, true)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            while (true) {
                controller?.let { if (it.isPlaying) _position.value = it.currentPosition }
                delay(300)
            }
        }
    }

    private fun sync(p: Player, rebuildQueue: Boolean) {
        if (p.mediaItemCount > 0) _stopped.value = false
        val old = _ui.value
        val queue = if (rebuildQueue) List(p.mediaItemCount) { p.getMediaItemAt(it).toTrack() } else old.queue
        val base = p.currentMediaItem?.toTrack()
        val md = p.mediaMetadata
        // теги из самого файла (для треков из папок без предварительного чтения тегов)
        val current = base?.copy(
            title = md.title?.toString()?.takeIf { it.isNotBlank() } ?: base.title,
            artist = md.artist?.toString()?.takeIf { it.isNotBlank() } ?: base.artist,
            album = md.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: base.album,
        )
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: current?.durationMs ?: 0L
        _ui.value = PlayerUi(
            current = current,
            isPlaying = p.isPlaying,
            durationMs = dur,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            speed = p.playbackParameters.speed,
            queue = queue,
            index = p.currentMediaItemIndex,
        )
        _position.value = p.currentPosition
        if (revealPending) {
            revealPending = false
            current?.let { _reveal.tryEmit(it.uri) }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending += block
    }

    fun playTracks(
        list: List<Track>, start: Int = 0, shuffle: Boolean? = null, source: String? = null,
        groups: PlayGroups? = null,
    ) {
        if (list.isEmpty()) return
        if (list.any(::isUnsupported)) {
            val tapped = if (shuffle == true) null else list.getOrNull(start)
            if (tapped != null && isUnsupported(tapped)) {
                // нажали на файл, который плеер не умеет играть — понятное сообщение вместо молчаливого пропуска
                _errors.tryEmit(getApplication<Application>().getString(R.string.msg_unsupported_tap, formatOf(tapped)))
                return
            }
            val ok = playable(list)
            if (ok.isEmpty()) return
            playTracks(ok, tapped?.let { ok.indexOf(it) }?.coerceAtLeast(0) ?: 0, shuffle, source, groups)
            return
        }
        withController { c ->
            playSource.value = source
            playGroups.value = groups
            if (shuffle != null) c.shuffleModeEnabled = shuffle
            val startIndex = if (shuffle == true) list.indices.random() else start.coerceIn(list.indices)
            c.setMediaItems(list.map { it.toMediaItem() }, startIndex, 0L)
            c.prepare()
            c.play()
            // первый играющий трек (или элемент списка) должен быть виден на экране, даже если он далеко за границей
            _reveal.tryEmit(list[startIndex].uri)
        }
    }

    fun playNext(all: List<Track>) {
        val list = playable(all)
        if (list.isEmpty()) return
        withController { c ->
            val items = list.map { it.toMediaItem() }
            if (c.mediaItemCount == 0) {
                c.setMediaItems(items); c.prepare(); c.play()
            } else {
                c.addMediaItems(c.currentMediaItemIndex + 1, items)
                _messages.tryEmit(R.string.msg_play_next)
            }
        }
    }

    fun addToQueue(all: List<Track>) {
        val list = playable(all)
        if (list.isEmpty()) return
        withController { c ->
            val items = list.map { it.toMediaItem() }
            if (c.mediaItemCount == 0) {
                c.setMediaItems(items); c.prepare(); c.play()
            } else {
                c.addMediaItems(items)
                _messages.tryEmit(R.string.msg_added_queue)
            }
        }
    }

    /** Список открытой вкладки/экрана: с его первого трека стартует «Плей» после полной остановки. */
    val trackInfo = MutableStateFlow<TrackInfo?>(null)
    fun showTrackInfo(t: Track) { viewModelScope.launch { trackInfo.value = scanner.trackInfo(t) } }

    /** Переключатель «перемешать» для списка: включает и запускает список вперемешку, повторное нажатие — выключает. */
    fun shuffleList(list: List<Track>) {
        withController { c ->
            if (c.shuffleModeEnabled) c.shuffleModeEnabled = false
            else playTracks(list, shuffle = true)
        }
    }

    /** Подпись списка, с которого запущено воспроизведение (для вида кнопки Play над списком). */
    private val playerPrefs = app.getSharedPreferences("player_ui_state", Context.MODE_PRIVATE)
    val playSource = MutableStateFlow<String?>(playerPrefs.getString("play_source", null))
    // источник тоже переживает перезапуск, чтобы после возобновления папка/вкладка по-прежнему отмечалась как играющая
    init { viewModelScope.launch { playSource.collect { playerPrefs.edit().putString("play_source", it).apply() } } }

    /** Какие альбомы / исполнители / папки / плейлисты сейчас играют (для отметок в списках); переживает перезапуск. */
    val playGroups = MutableStateFlow(
        playerPrefs.getString("play_groups_tab", null)?.let { t ->
            PlayGroups(t, playerPrefs.getStringSet("play_groups_keys", emptySet()).orEmpty().toSet())
        }
    )
    init {
        viewModelScope.launch {
            playGroups.collect { g ->
                playerPrefs.edit().putString("play_groups_tab", g?.tab).putStringSet("play_groups_keys", g?.keys).apply()
            }
        }
    }

    val contextTracks = MutableStateFlow<List<Track>>(emptyList())
    private val _stopped = MutableStateFlow(false)
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    /** Полный стоп: остановка, очередь очищается, позиция сбрасывается. */
    fun stopPlayback() {
        _stopped.value = true
        playSource.value = null
        playGroups.value = null
        withController { it.pause(); it.stop(); it.clearMediaItems() }
    }

    /** Событие «открыть полный экран плеера» (если включено в настройках). */
    val openPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Играет переданный список с начала; при включённом «перемешать» — со случайного трека. */
    fun playList(all: List<Track>, source: String? = null, groups: PlayGroups? = null) {
        if (all.isEmpty()) return
        val list = playable(all)
        if (list.isEmpty()) return
        _stopped.value = false
        withController { c ->
            val start = if (c.shuffleModeEnabled) list.indices.random() else 0
            playTracks(list, start, source = source, groups = groups)
        }
    }

    /**
     * Кнопка «Играть» над списком: играет выбранное (или весь открытый список, если ничего не выбрано).
     * Перемешивание и повтор берутся из состояния кнопок плеера.
     */
    fun playFromBar(list: List<Track>, source: String?, groups: PlayGroups? = null) {
        if (list.isEmpty()) return
        playList(list, source, groups)
        if (AppSettings.openPlayerOnPlay.value) openPlayer.tryEmit(Unit)
    }

    /** Треки выбранных плейлистов (без дублей) — для воспроизведения и действий над выделенными плейлистами. */
    fun withPlaylistTracks(ids: Collection<Long>, block: (List<Track>) -> Unit) {
        viewModelScope.launch {
            val list = ids.flatMap { id -> dao.observeItems(id).first().map { it.toTrack() } }.distinctBy { it.uri }
            block(list)
        }
    }

    /** «Play» над списком: играет открытый на экране список. */
    fun playFromTop(list: List<Track>, sig: String) {
        if (list.isEmpty()) return
        if (playSource.value == sig && _ui.value.current != null) {
            onMiniPlay()   // этот же список уже запущен: пауза / продолжение
            return
        }
        playList(list, sig)
        if (AppSettings.openPlayerOnPlay.value) openPlayer.tryEmit(Unit)
    }

    /** «Play» в мини-плеере: пауза/продолжение текущей очереди; при запуске может открыть полный экран. */
    fun onMiniPlay() {
        val wasPlaying = _ui.value.isPlaying
        togglePlay()
        if (!wasPlaying && AppSettings.openPlayerOnPlay.value) openPlayer.tryEmit(Unit)
    }

    fun playContext() {
        val list = contextTracks.value.ifEmpty { tracks.value }
        if (list.isEmpty()) return
        _stopped.value = false
        // при включённом «перемешать» старт сразу со случайного трека, а не с первого
        withController { c ->
            val start = if (c.shuffleModeEnabled) list.indices.random() else 0
            playTracks(list, start)
        }
    }

    fun playTracksRepeat(list: List<Track>) {
        playList(list)
        withController { it.repeatMode = Player.REPEAT_MODE_ALL }
    }

    fun clearPlaylist(id: Long) = viewModelScope.launch { dao.clearPlaylist(id) }

    fun togglePlay() = withController {
        if (_ui.value.current == null) { playContext(); return@withController }
        if (it.isPlaying) it.pause() else {
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
            it.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); _position.value = ms }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setSpeed(s: Float) = withController { it.setPlaybackSpeed(s) }
    fun skipToQueue(i: Int) = withController { it.seekTo(i, 0L); it.play() }
    fun removeFromQueue(i: Int) = withController { it.removeMediaItem(i) }
    fun moveInQueue(from: Int, to: Int) = withController {
        if (to in 0 until it.mediaItemCount) it.moveMediaItem(from, to)
    }
    fun clearQueue() = withController { it.clearMediaItems() }

    // ---------- Таймер сна ----------
    private var sleepJob: Job? = null
    private val _sleep = MutableStateFlow<Long?>(null)
    val sleepRemaining: StateFlow<Long?> = _sleep.asStateFlow()

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _sleep.value = null; return }
        sleepJob = viewModelScope.launch {
            var left = minutes * 60L
            while (left > 0) {
                _sleep.value = left
                delay(1000)
                left--
            }
            _sleep.value = null
            controller?.pause()
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        super.onCleared()
    }
}

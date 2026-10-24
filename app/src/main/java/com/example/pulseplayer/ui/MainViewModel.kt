package com.example.pulseplayer.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.pulseplayer.PlaybackService
import com.example.pulseplayer.R
import com.example.pulseplayer.data.AppDb
import com.example.pulseplayer.data.FAVORITES_ID
import com.example.pulseplayer.data.MediaScanner
import com.example.pulseplayer.data.PlaylistEntity
import com.example.pulseplayer.data.PlaylistItem
import com.example.pulseplayer.data.Track
import com.example.pulseplayer.data.toItem
import com.example.pulseplayer.data.toMediaItem
import com.example.pulseplayer.data.toTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortMode(@StringRes val label: Int) {
    TITLE(R.string.sort_title),
    ARTIST(R.string.sort_artist),
    RECENT(R.string.sort_recent),
    DURATION(R.string.sort_duration),
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
    val sort = MutableStateFlow(SortMode.TITLE)

    val tracks: StateFlow<List<Track>> = combine(_library, query, sort) { lib, q, s ->
        val f = if (q.isBlank()) lib else lib.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
        when (s) {
            SortMode.TITLE -> f.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> f.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            SortMode.RECENT -> f.sortedByDescending { it.dateAdded }
            SortMode.DURATION -> f.sortedByDescending { it.durationMs }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Group>> = tracks.map { list ->
        list.filter { it.album.isNotBlank() }.groupBy { it.album }
            .map { (k, v) -> Group("a:$k", k, v.first().artist, v.firstNotNullOfOrNull { it.artUri }, v) }
            .sortedBy { it.title.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val artists: StateFlow<List<Group>> = tracks.map { list ->
        list.groupBy { it.artist }
            .map { (k, v) -> Group("r:$k", k, "", v.firstNotNullOfOrNull { it.artUri }, v) }
            .sortedBy { it.title.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folders: StateFlow<List<Group>> = tracks.map { list ->
        list.groupBy { it.folder }
            .map { (k, v) -> Group("f:$k", k.substringAfterLast('/'), k, v.firstNotNullOfOrNull { it.artUri }, v) }
            .sortedBy { it.title.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    val favoriteUris: StateFlow<Set<String>> = dao.observeFavoriteUris().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val playlistPicker = MutableStateFlow<List<Track>?>(null)

    fun playlistItems(id: Long): Flow<List<PlaylistItem>> = dao.observeItems(id)

    fun pickPlaylistFor(list: List<Track>) { playlistPicker.value = list }

    fun createPlaylist(name: String, thenAdd: List<Track> = emptyList()) {
        viewModelScope.launch {
            val id = dao.insertPlaylist(PlaylistEntity(name = name.trim().ifBlank { "Playlist" }))
            if (thenAdd.isNotEmpty()) addToPlaylistInternal(id, thenAdd)
        }
    }

    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { dao.renamePlaylist(id, name.trim()) }
    fun deletePlaylist(id: Long) = viewModelScope.launch { dao.deletePlaylist(id) }
    fun addToPlaylist(id: Long, list: List<Track>) = viewModelScope.launch { addToPlaylistInternal(id, list) }
    fun removeFromPlaylist(id: Long, t: Track) = viewModelScope.launch { dao.deleteByUri(id, t.uri) }

    private suspend fun addToPlaylistInternal(id: Long, list: List<Track>) {
        val existing = dao.uris(id).toSet()
        var pos = dao.maxPosition(id)
        val items = list.distinctBy { it.uri }.filter { it.uri !in existing }.map { it.toItem(id, ++pos) }
        if (items.isNotEmpty()) dao.insertItems(items)
        _messages.tryEmit(R.string.msg_added_playlist)
    }

    fun toggleFavorite(t: Track) {
        viewModelScope.launch {
            if (t.uri in favoriteUris.value) dao.deleteByUri(FAVORITES_ID, t.uri)
            else dao.insertItems(listOf(t.toItem(FAVORITES_ID, dao.maxPosition(FAVORITES_ID) + 1)))
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

    private val listener = object : Player.Listener {
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
        val old = _ui.value
        val queue = if (rebuildQueue) List(p.mediaItemCount) { p.getMediaItemAt(it).toTrack() } else old.queue
        val current = p.currentMediaItem?.toTrack()
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
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending += block
    }

    fun playTracks(list: List<Track>, start: Int = 0, shuffle: Boolean? = null) {
        if (list.isEmpty()) return
        withController { c ->
            if (shuffle != null) c.shuffleModeEnabled = shuffle
            val startIndex = if (shuffle == true) list.indices.random() else start.coerceIn(list.indices)
            c.setMediaItems(list.map { it.toMediaItem() }, startIndex, 0L)
            c.prepare()
            c.play()
        }
    }

    fun playNext(list: List<Track>) {
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

    fun addToQueue(list: List<Track>) {
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

    fun togglePlay() = withController {
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

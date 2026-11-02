package com.semenenko.pulseplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.semenenko.pulseplayer.data.Track
import com.semenenko.pulseplayer.data.toMediaItem
import com.semenenko.pulseplayer.data.toTrack
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Сохраняет состояние плеера между запусками: очередь, текущий трек и позицию, режимы «перемешать» / «повтор»,
 * скорость и сам порядок перемешивания (чтобы после перезапуска треки шли в той же последовательности).
 *
 * Кнопка «Стоп» очищает очередь, поэтому после неё сохранённое состояние удаляется. Пауза состояние сохраняет.
 * Работает внутри [PlaybackService], так что сохранение не зависит от того, открыт ли экран приложения.
 */
@OptIn(UnstableApi::class)
class PlayerStateStore(context: Context) {

    private class Saved(
        val items: List<MediaItem>,
        val order: IntArray,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeat: Int,
        val speed: Float,
    )

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val file = File(app.filesDir, "queue.json")
    private val io = Executors.newSingleThreadExecutor()   // один поток: записи и удаление идут строго по порядку
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var destroyed = false

    private val queueTask = Runnable { writeQueueSnapshot(async = true) }

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            val p = player ?: return
            if (p.mediaItemCount == 0) {            // «Стоп» или очистка очереди — забываем всё
                main.removeCallbacks(queueTask)
                clear()
            } else {
                scheduleQueue()
                saveState()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { scheduleQueue(); saveState() }
        override fun onRepeatModeChanged(repeatMode: Int) = saveState()
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = saveState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = saveState()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
        ) = saveState()

        // пауза / продолжение: позиция фиксируется в момент паузы, без периодической записи по таймеру
        override fun onIsPlayingChanged(isPlaying: Boolean) = saveState()
    }

    /** Подключает хранилище к плееру (при замене плеера старый отключается). */
    fun attach(p: Player) {
        player?.removeListener(listener)
        player = p
        p.addListener(listener)
    }

    /** Если плеер пуст — поднимает сохранённую очередь на паузе с той же позиции, в тех же режимах и порядке. */
    fun restore() {
        io.execute {
            val saved = runCatching { load() }.onFailure { Log.w(TAG, "load state failed", it) }.getOrNull()
                ?: return@execute
            main.post { player?.let { apply(it, saved) } }
        }
    }

    /** Последнее сохранение перед остановкой сервиса. */
    fun flush() {
        destroyed = true
        main.removeCallbacks(queueTask)
        // Пустой плеер (восстановление ещё не завершилось) ничего не стирает: очередь удаляется только при «Стоп»
        if ((player?.mediaItemCount ?: 0) > 0) writeQueueSnapshot(async = true)
        saveState()
        io.shutdown()
        runCatching { io.awaitTermination(2, TimeUnit.SECONDS) }
    }

    // ---------- сохранение ----------

    private fun scheduleQueue() {
        main.removeCallbacks(queueTask)
        main.postDelayed(queueTask, QUEUE_SAVE_DELAY_MS)
    }

    private fun writeQueueSnapshot(async: Boolean) {
        val p = player ?: return
        val n = p.mediaItemCount
        if (n == 0) { clear(); return }
        val items = List(n) { p.getMediaItemAt(it) }
        val order = shuffleOrder(p)
        val job = Runnable { runCatching { writeQueue(items, order) }.onFailure { Log.w(TAG, "save queue failed", it) } }
        if (async) io.execute(job) else job.run()
    }

    /** Малое состояние (индекс, позиция, режимы) — в настройках; вызывается часто и стоит дёшево. */
    private fun saveState() {
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        val pos = if (p.playbackState == Player.STATE_ENDED) 0L else p.currentPosition.coerceAtLeast(0L)
        prefs.edit()
            .putBoolean(K_HAS, true)
            .putInt(K_INDEX, p.currentMediaItemIndex)
            .putLong(K_POS, pos)
            .putBoolean(K_SHUFFLE, p.shuffleModeEnabled)
            .putInt(K_REPEAT, p.repeatMode)
            .putFloat(K_SPEED, p.playbackParameters.speed)
            .apply()
    }

    private fun clear() {
        prefs.edit().clear().apply()
        io.execute { runCatching { file.delete() } }
    }

    /** Порядок воспроизведения при включённом перемешивании: индексы очереди в порядке проигрывания. */
    private fun shuffleOrder(p: Player): IntArray {
        val t = p.currentTimeline
        val n = t.windowCount
        if (n == 0) return IntArray(0)
        val out = IntArray(n)
        var i = t.getFirstWindowIndex(true)
        var k = 0
        while (i != C.INDEX_UNSET && k < n) {
            out[k++] = i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return if (k == n) out else IntArray(0)
    }

    private fun writeQueue(items: List<MediaItem>, order: IntArray) {
        val tmp = File(file.parentFile, "queue.json.tmp")
        JsonWriter(tmp.bufferedWriter()).use { w ->
            w.beginObject()
            w.name("order").beginArray()
            order.forEach { w.value(it.toLong()) }
            w.endArray()
            w.name("items").beginArray()
            for (m in items) {
                val t = m.toTrack()
                w.beginObject()
                w.name("i").value(t.uri)
                w.name("t").value(t.title)
                w.name("a").value(t.artist)
                w.name("b").value(t.album)
                w.name("d").value(t.durationMs)
                w.name("u").value(t.artUri)
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    // ---------- восстановление ----------

    private fun load(): Saved? {
        if (!prefs.getBoolean(K_HAS, false) || !file.exists()) return null
        val items = ArrayList<MediaItem>()
        var order = IntArray(0)
        JsonReader(file.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "order" -> {
                        val l = ArrayList<Int>()
                        r.beginArray(); while (r.hasNext()) l.add(r.nextInt()); r.endArray()
                        order = l.toIntArray()
                    }
                    "items" -> { r.beginArray(); while (r.hasNext()) items.add(readItem(r)); r.endArray() }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        if (items.isEmpty()) return null
        return Saved(
            items = items,
            order = if (isPermutation(order, items.size)) order else IntArray(0),
            index = prefs.getInt(K_INDEX, 0).coerceIn(0, items.lastIndex),
            positionMs = prefs.getLong(K_POS, 0L).coerceAtLeast(0L),
            shuffle = prefs.getBoolean(K_SHUFFLE, false),
            repeat = prefs.getInt(K_REPEAT, Player.REPEAT_MODE_OFF),
            speed = prefs.getFloat(K_SPEED, 1f),
        )
    }

    private fun readItem(r: JsonReader): MediaItem {
        var id = ""; var title = ""; var artist = ""; var album = ""; var dur = 0L; var art: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "i" -> id = r.nextString()
                "t" -> title = r.nextString()
                "a" -> artist = r.nextString()
                "b" -> album = r.nextString()
                "d" -> dur = r.nextLong()
                "u" -> if (r.peek() == JsonToken.NULL) r.nextNull() else art = r.nextString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Track(id, title, artist, album, dur, art).toMediaItem()
    }

    private fun isPermutation(a: IntArray, n: Int): Boolean {
        if (a.size != n) return false
        val seen = BooleanArray(n)
        for (v in a) { if (v !in 0 until n || seen[v]) return false; seen[v] = true }
        return true
    }

    private fun apply(p: Player, s: Saved) {
        if (destroyed || p.mediaItemCount != 0) return   // пользователь уже успел запустить что-то другое
        p.shuffleModeEnabled = s.shuffle
        p.repeatMode = s.repeat
        p.setPlaybackSpeed(s.speed)
        p.setMediaItems(s.items, s.index, s.positionMs)
        // тот же порядок перемешивания, что был до закрытия приложения
        if (p is ExoPlayer && s.order.isNotEmpty()) p.setShuffleOrder(DefaultShuffleOrder(s.order, System.currentTimeMillis()))
        p.prepare()
        p.playWhenReady = false             // восстанавливаем на паузе: «Play» продолжит с того же места
    }

    private companion object {
        const val TAG = "PulsePlayer"
        const val PREFS = "player_state"
        const val K_HAS = "has"
        const val K_INDEX = "index"
        const val K_POS = "pos"
        const val K_SHUFFLE = "shuffle"
        const val K_REPEAT = "repeat"
        const val K_SPEED = "speed"
        const val QUEUE_SAVE_DELAY_MS = 700L
    }
}

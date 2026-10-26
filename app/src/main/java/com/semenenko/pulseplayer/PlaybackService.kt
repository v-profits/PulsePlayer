package com.semenenko.pulseplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.semenenko.pulseplayer.audio.EqualizerManager

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    /** true — обычный аудиотракт без собственного DSP (аварийный режим, если DSP-цепочка сломалась). */
    private var safeMode = false

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            EqualizerManager.attach(audioSessionId)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: ${error.errorCodeName}", error)
            // Ошибки не файла, а самого аудиотракта/обработки (1xxx, 5xxx) — переключаемся на безопасный плеер.
            val pipeline = error.errorCode in 1000..1999 || error.errorCode in 5001..5999
            if (pipeline && !safeMode) Handler(Looper.getMainLooper()).post { switchToSafePlayer() }
        }
    }

    private fun buildPlayer(safe: Boolean): ExoPlayer {
        val factory: DefaultRenderersFactory =
            if (safe) DefaultRenderersFactory(this)
            else object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(EqualizerManager.dsp))
                    .build()
            }
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        if (!safe) {
            factory.setEnableAudioFloatOutput(true)          // hi-res без понижения до 16 бит
                .setEnableAudioTrackPlaybackParams(true)     // плавная смена скорости
        }
        return ExoPlayer.Builder(this, factory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // автоматический аудиофокус
            )
            .setHandleAudioBecomingNoisy(true) // пауза при отключении наушников
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { it.addListener(listener) }
    }

    /** Пересоздаёт плеер без DSP, сохраняя очередь, позицию и режимы — музыка не должна «слетать». */
    private fun switchToSafePlayer() {
        val s = session ?: return
        if (safeMode) return
        safeMode = true
        val old = s.player
        val items = List(old.mediaItemCount) { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val pos = old.currentPosition
        val shuffle = old.shuffleModeEnabled
        val repeat = old.repeatMode
        val speed = old.playbackParameters.speed

        val np = buildPlayer(true)
        s.setPlayer(np)
        EqualizerManager.attach(np.audioSessionId)
        np.shuffleModeEnabled = shuffle
        np.repeatMode = repeat
        np.setPlaybackSpeed(speed)
        if (items.isNotEmpty()) {
            np.setMediaItems(items, index.coerceAtLeast(0), pos)
            np.prepare()
            np.playWhenReady = true
        }
        old.release()
    }

    override fun onCreate() {
        super.onCreate()
        EqualizerManager.init(this)

        val player = buildPlayer(false)
        EqualizerManager.attach(player.audioSessionId)

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(pi).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_stat_music) }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        EqualizerManager.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PulsePlayer"
    }
}

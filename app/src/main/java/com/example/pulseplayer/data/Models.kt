package com.example.pulseplayer.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: String? = null,
    val dateAdded: Long = 0L,
    val folder: String = "",
)

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(uri)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDurationMs(durationMs)
            .setArtworkUri(artUri?.let { Uri.parse(it) })
            .setIsPlayable(true)
            .build()
    )
    .build()

fun MediaItem.toTrack(): Track = Track(
    uri = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    album = mediaMetadata.albumTitle?.toString().orEmpty(),
    durationMs = mediaMetadata.durationMs ?: 0L,
    artUri = mediaMetadata.artworkUri?.toString(),
)

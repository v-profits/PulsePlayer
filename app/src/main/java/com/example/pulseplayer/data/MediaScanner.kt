package com.example.pulseplayer.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaScanner(private val ctx: Context) {

    @Suppress("DEPRECATION")
    suspend fun queryLibrary(): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val artBase = Uri.parse("content://media/external/audio/albumart")
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 1000"
        ctx.contentResolver.query(
            base, projection, selection, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val albumId = c.getLong(4)
                out += Track(
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    title = c.getString(1).orEmpty(),
                    artist = c.getString(2).clean(),
                    album = c.getString(3).clean(),
                    durationMs = c.getLong(5),
                    artUri = if (albumId > 0) ContentUris.withAppendedId(artBase, albumId).toString() else null,
                    dateAdded = c.getLong(6),
                    folder = c.getString(7)?.substringBeforeLast('/', "").orEmpty(),
                )
            }
        }
        out
    }

    private fun String?.clean(): String = if (this == null || this == "<unknown>") "" else this

    /** Читает теги файла, выбранного через системный диалог (SAF). */
    suspend fun readDocument(uri: Uri): Track = withContext(Dispatchers.IO) {
        val name = displayName(uri).substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            fun meta(key: Int) = r.extractMetadata(key)?.takeIf { it.isNotBlank() }
            Track(
                uri = uri.toString(),
                title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name,
                artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty(),
                album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                artUri = r.embeddedPicture?.let { saveArt(uri, it) },
                dateAdded = System.currentTimeMillis() / 1000,
            )
        } catch (e: Exception) {
            Track(uri.toString(), name, "", "", 0L)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun saveArt(uri: Uri, bytes: ByteArray): String? = runCatching {
        val dir = File(ctx.filesDir, "art").apply { mkdirs() }
        val f = File(dir, "${uri.toString().hashCode()}.jpg")
        if (!f.exists()) f.writeBytes(bytes)
        Uri.fromFile(f).toString()
    }.getOrNull()

    private fun displayName(uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0).orEmpty()
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    /** Рекурсивно собирает все аудиофайлы выбранной папки. */
    suspend fun scanTree(tree: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return@withContext emptyList()
        val out = ArrayList<Uri>()
        fun walk(d: DocumentFile) {
            for (f in d.listFiles()) {
                if (f.isDirectory) walk(f)
                else if (f.type?.startsWith("audio/") == true || isAudioName(f.name)) out += f.uri
            }
        }
        walk(root)
        out
    }

    private fun isAudioName(name: String?): Boolean =
        name?.substringAfterLast('.', "")?.lowercase() in AUDIO_EXT

    companion object {
        private val AUDIO_EXT = setOf(
            "mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "aac", "alac", "aif", "aiff",
            "mka", "amr", "awb", "mid", "midi", "wma", "ape", "wv", "ac3", "eac3", "dts", "mp2", "3gp"
        )
    }
}

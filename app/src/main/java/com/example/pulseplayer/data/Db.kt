package com.example.pulseplayer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

const val FAVORITES_ID = 1L

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isSystem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_items",
    indices = [Index("playlistId")],
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: String?,
    val position: Int,
)

/** Папки из файловой системы (SAF): добавленные источники и избранные папки. */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val isSource: Boolean = false,
    val isFavorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val itemCount: Int,
)

fun PlaylistItem.toTrack() = Track(uri, title, artist, album, durationMs, artUri)
fun Track.toItem(playlistId: Long, position: Int) =
    PlaylistItem(playlistId = playlistId, uri = uri, title = title, artist = artist, album = album,
        durationMs = durationMs, artUri = artUri, position = position)

@Dao
interface PlaylistDao {
    @Query("SELECT p.*, (SELECT COUNT(*) FROM playlist_items WHERE playlistId = p.id) AS itemCount FROM playlists p ORDER BY p.isSystem DESC, p.createdAt DESC")
    fun observePlaylists(): Flow<List<PlaylistWithCount>>

    @Insert
    suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistIgnore(p: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id AND isSystem = 0")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position, id")
    fun observeItems(id: Long): Flow<List<PlaylistItem>>

    @Insert
    suspend fun insertItems(items: List<PlaylistItem>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Query("SELECT uri FROM playlist_items WHERE playlistId = :id")
    suspend fun uris(id: Long): List<String>

    @Query("DELETE FROM playlist_items WHERE playlistId = :pid AND uri = :uri")
    suspend fun deleteByUri(pid: Long, uri: String)

    @Query("SELECT uri FROM playlist_items WHERE playlistId = 1")
    fun observeFavoriteUris(): Flow<List<String>>

    @Query("SELECT * FROM folders WHERE isSource = 1 ORDER BY addedAt")
    fun observeSources(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE isFavorite = 1 ORDER BY addedAt")
    fun observeFavFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE uri = :uri")
    suspend fun folder(uri: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(f: FolderEntity)

    @Query("DELETE FROM folders WHERE uri = :uri")
    suspend fun deleteFolder(uri: String)
}

@Database(entities = [PlaylistEntity::class, PlaylistItem::class, FolderEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): PlaylistDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` (`uri` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`isSource` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uri`))"
                )
            }
        }

        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "pulse.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { inst = it }
        }
    }
}

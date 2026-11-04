package com.garado.amp.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.OnConflictStrategy
import androidx.room.Insert
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.garado.amp.data.Album
import com.garado.amp.data.Track
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumId: String?,
    val coverArtId: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val playCount: Int,
    val lastPlayedMs: Long,
    val liked: Boolean,
    /** Rating 1-100, 0 when unrated. */
    val rating: Int = 0,
    val genre: String = "",
    val composer: String = "",
    /** Plex hands out a file path for original-quality playback; see [Track]. */
    val streamPath: String = "",
    /** Loudness-normalisation gain in dB from the server; see [Track.gainDb]. */
    val gainDb: Float? = null,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val coverArtId: String?,
    val durationMs: Long,
    val songCount: Int,
    val year: Int?,
    val releaseDate: Long,
    val createdMs: Long,
    /**
     * The server's own last-changed stamp; see [Album.updatedMs]. Rows
     * written before this column existed read 0, which the sync filter
     * treats as "no stamp" and re-fetches once.
     */
    @ColumnInfo(defaultValue = "0")
    val updatedMs: Long = 0,
    val playCount: Int,
    val lastPlayedMs: Long,
    val liked: Boolean,
    /** Rating 1-100, 0 when unrated. */
    val rating: Int = 0,
    val genre: String = "",
    /**
     * Which of the server's libraries this album came from (a Subsonic music
     * folder), or null when the server has only one. Recorded so switching
     * library filters the cache instead of requiring a fetch; see
     * LibraryRepository.allAlbums.
     */
    val libraryId: String? = null,
)

/**
 * Liked artists, stored by name, since the Artists list is derived from
 * track tags rather than fetched and has no stable id to join on otherwise.
 */
@Entity(tableName = "liked_artists")
data class LikedArtistEntity(@PrimaryKey val name: String)

/**
 * One entry of an artist's popular-songs list, as the server ranked it.
 * Stores track ids only; a popular song missing from the cached library is
 * dropped on read rather than written in. See LibraryRepository.topSongsForArtist.
 */
@Entity(tableName = "top_songs", primaryKeys = ["artist", "position"])
data class TopSongEntity(
    val artist: String,
    val position: Int,
    val trackId: String,
    val fetchedAtMs: Long,
)

/**
 * One downloaded track. The audio lives on disk under the tool's files dir;
 * this row indexes it (format, size, and any lyrics captured for offline
 * playback).
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val albumId: String?,
    val fileName: String,
    val format: String,
    val bytes: Long,
    val lyrics: String?,
    val downloadedAtMs: Long,
)

/** A downloaded file, without the lyrics blob; see [LibraryDao.observeDownloads]. */
data class DownloadFile(
    val trackId: String,
    val albumId: String?,
    val fileName: String,
    val format: String,
    val bytes: Long,
    val downloadedAtMs: Long,
)

/** A download row whose track no longer exists in the library. */
data class OrphanedDownload(val trackId: String, val fileName: String)

/** Cached track count for one album. */
data class AlbumTrackCount(val albumId: String, val tracks: Int)

/** Just enough of a download row to decide whether an album is complete. */
@Dao
interface LibraryDao {

    /**
     * The whole table. Carries [Transaction] because a result this size
     * doesn't fit one CursorWindow: without a read transaction, a write
     * landing between fills throws "Couldn't read row N from CursorWindow".
     */
    @Transaction
    @Query("SELECT * FROM tracks")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Transaction
    @Query("SELECT * FROM albums")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Transaction
    @Query("SELECT * FROM albums")
    suspend fun allAlbumsSnapshot(): List<AlbumEntity>

    /**
     * The whole table, once, for the download top-up, which reads every
     * source's tables rather than only the one being observed.
     */
    @Transaction
    @Query("SELECT * FROM tracks")
    suspend fun allTracksSnapshot(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId")
    suspend fun tracksForAlbum(albumId: String): List<TrackEntity>

    @Query("DELETE FROM tracks WHERE albumId = :albumId")
    suspend fun deleteTracksForAlbum(albumId: String)

    /** Tracks belonging to no album this library holds. */
    @Query("SELECT COUNT(*) FROM tracks WHERE albumId IS NOT NULL AND albumId NOT IN (SELECT id FROM albums)")
    suspend fun orphanedTrackCount(): Int

    @Query("DELETE FROM tracks WHERE albumId IS NOT NULL AND albumId NOT IN (SELECT id FROM albums)")
    suspend fun deleteOrphanedTracks()

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun tracksByIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    /**
     * Replace the tracks of several albums in one transaction. Batches the
     * delete-and-reinsert so a large sync doesn't invalidate (and re-run)
     * every observer's `SELECT * FROM tracks` once per album.
     */
    @Transaction
    suspend fun replaceAlbumTracks(albumIds: List<String>, tracks: List<TrackEntity>) {
        albumIds.forEach { deleteTracksForAlbum(it) }
        upsertTracks(tracks)
    }

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums WHERE id IN (:ids)")
    suspend fun deleteAlbums(ids: List<String>)

    /**
     * Swap the whole set of liked tracks in one transaction, so observers
     * never see a moment where nothing is liked.
     */
    @Transaction
    suspend fun replaceTrackLikes(ids: List<String>) {
        clearTrackLikes()
        if (ids.isNotEmpty()) likeTracks(ids)
    }

    /** The same for artists. */
    @Transaction
    suspend fun replaceLikedArtists(artists: List<LikedArtistEntity>) {
        clearLikedArtists()
        if (artists.isNotEmpty()) likeArtists(artists)
    }

    @Query("UPDATE tracks SET liked = 0")
    suspend fun clearTrackLikes()

    @Query("UPDATE tracks SET liked = 1 WHERE id IN (:ids)")
    suspend fun likeTracks(ids: List<String>)

    @Query("UPDATE tracks SET liked = :liked WHERE id = :id")
    suspend fun setTrackLiked(id: String, liked: Boolean)

    @Query("UPDATE albums SET liked = :liked WHERE id = :id")
    suspend fun setAlbumLiked(id: String, liked: Boolean)

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    // --- liked artists -------------------------------------------------------

    @Query("SELECT name FROM liked_artists")
    fun observeLikedArtists(): Flow<List<String>>

    /** The same, once; see [allTracksSnapshot]. */
    @Query("SELECT name FROM liked_artists")
    suspend fun likedArtistNames(): List<String>

    @Upsert
    suspend fun likeArtists(artists: List<LikedArtistEntity>)

    @Query("DELETE FROM liked_artists WHERE name = :name")
    suspend fun unlikeArtist(name: String)

    @Query("DELETE FROM liked_artists")
    suspend fun clearLikedArtists()

    // --- popular songs -------------------------------------------------------

    @Query("SELECT * FROM top_songs WHERE artist = :artist ORDER BY position")
    suspend fun topSongs(artist: String): List<TopSongEntity>

    @Query("DELETE FROM top_songs WHERE artist = :artist")
    suspend fun clearTopSongs(artist: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopSongs(rows: List<TopSongEntity>)

    /** The server's list for one artist, replacing whatever was held. */
    @Transaction
    suspend fun replaceTopSongs(artist: String, rows: List<TopSongEntity>) {
        clearTopSongs(artist)
        if (rows.isNotEmpty()) insertTopSongs(rows)
    }

    @Query("DELETE FROM top_songs")
    suspend fun clearAllTopSongs()

    // --- downloads -----------------------------------------------------------

    /** Ids already downloaded, so a bulk enqueue can filter in one query. */
    @Query("SELECT trackId FROM downloads")
    suspend fun downloadedIds(): List<String>

    /** Every cover this source's cache names, so Log Out can drop them from disk. */
    @Query(
        "SELECT DISTINCT coverArtId FROM albums WHERE coverArtId IS NOT NULL " +
            "UNION SELECT DISTINCT coverArtId FROM tracks WHERE coverArtId IS NOT NULL",
    )
    suspend fun allCoverArtIds(): List<String>

    /**
     * Point every track at its album's cover, collapsing per-track cover ids
     * (Navidrome's `mf-` ids, Jellyfin's per-track images) into one shared
     * copy. Idempotent; runs at each launch, see App.boot.
     */
    @Query(
        "UPDATE tracks SET coverArtId = (SELECT a.coverArtId FROM albums a WHERE a.id = tracks.albumId) " +
            "WHERE albumId IS NOT NULL " +
            "AND (SELECT a.coverArtId FROM albums a WHERE a.id = tracks.albumId) IS NOT NULL " +
            "AND coverArtId IS NOT (SELECT a.coverArtId FROM albums a WHERE a.id = tracks.albumId)",
    )
    suspend fun collapseTrackCovers(): Int

    /** The covers of downloaded songs, needed by an offline library. */
    @Query(
        "SELECT DISTINCT t.coverArtId FROM tracks t JOIN downloads d ON d.trackId = t.id " +
            "WHERE t.coverArtId IS NOT NULL",
    )
    suspend fun downloadedCoverArtIds(): List<String>

    /** Albums with no library recorded; see LibraryRepository's retag pass. */
    @Query("SELECT COUNT(*) FROM albums WHERE libraryId IS NULL")
    suspend fun untaggedAlbumCount(): Int

    @Query("SELECT id FROM albums WHERE libraryId IS NULL")
    suspend fun untaggedAlbumIds(): List<String>

    /** Stamp these albums as belonging to [libraryId]; answers how many rows took it. */
    @Query("UPDATE albums SET libraryId = :libraryId WHERE id IN (:ids)")
    suspend fun tagAlbums(libraryId: String, ids: List<String>): Int

    /** How many tracks are actually cached per album, for sync reconciliation. */
    @Query("SELECT albumId AS albumId, COUNT(*) AS tracks FROM tracks WHERE albumId IS NOT NULL GROUP BY albumId")
    suspend fun trackCountsByAlbum(): List<AlbumTrackCount>

    /**
     * The one observed read of this table; see [LibraryRepository.downloadFiles].
     * Projects out the `lyrics` column deliberately: selecting it for every
     * row risks exceeding the 2MB CursorWindow. [download] fetches lyrics one
     * row at a time instead.
     */
    @Transaction
    @Query("SELECT trackId, albumId, fileName, format, bytes, downloadedAtMs FROM downloads")
    fun observeDownloads(): Flow<List<DownloadFile>>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    suspend fun download(trackId: String): DownloadEntity?

    /** Downloads with no words stored; see [Downloader.refillMissingLyrics]. Ids only, same reason as above. */
    @Query("SELECT trackId FROM downloads WHERE lyrics IS NULL LIMIT :limit")
    suspend fun downloadsMissingLyrics(limit: Int): List<String>

    @Query("SELECT IFNULL(SUM(bytes), 0) FROM downloads")
    suspend fun downloadedBytes(): Long

    @Upsert
    suspend fun upsertDownload(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun deleteDownload(trackId: String)

    @Query("SELECT trackId, fileName FROM downloads WHERE trackId NOT IN (SELECT id FROM tracks)")
    suspend fun orphanedDownloads(): List<OrphanedDownload>

    @Query("DELETE FROM downloads")
    suspend fun clearDownloads()

    /**
     * Give downloads back the album they belong to. Files reindexed from disk
     * after a schema bump carry only a track id, so this backfills albumId
     * from the library once one exists.
     */
    @Query(
        "UPDATE downloads SET albumId = " +
            "(SELECT albumId FROM tracks WHERE tracks.id = downloads.trackId) " +
            "WHERE albumId IS NULL",
    )
    suspend fun backfillDownloadAlbums()

    @Query("UPDATE tracks SET rating = :stars WHERE id = :id")
    suspend fun setTrackRating(id: String, stars: Int)

    @Query("UPDATE albums SET rating = :stars WHERE id = :id")
    suspend fun setAlbumRating(id: String, stars: Int)

    /** Record a play locally, so Recently Played and Most Played update immediately. */
    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedMs = :atMs WHERE id = :id")
    suspend fun markPlayed(id: String, atMs: Long)
}

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        LikedArtistEntity::class,
        DownloadEntity::class,
        TopSongEntity::class,
    ],
    version = 12,
    exportSchema = true,
    // Every schema change should add a migration step here, generated from
    // the committed schemas in tool/schemas, rather than rely on the SDK's
    // drop-everything fallback.
    autoMigrations = [
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
    ],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

// --- entity <-> domain ------------------------------------------------------

fun TrackEntity.toTrack(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    albumId = albumId,
    coverArtId = coverArtId,
    durationMs = durationMs,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    playCount = playCount,
    lastPlayedMs = lastPlayedMs,
    liked = liked,
    rating = rating,
    genre = genre,
    composer = composer,
    streamPath = streamPath,
    gainDb = gainDb,
)

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    albumId = albumId,
    coverArtId = coverArtId,
    durationMs = durationMs,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    playCount = playCount,
    lastPlayedMs = lastPlayedMs,
    liked = liked,
    rating = rating,
    genre = genre,
    composer = composer,
    streamPath = streamPath,
    gainDb = gainDb,
)

fun AlbumEntity.toAlbum(): Album = Album(
    id = id,
    title = title,
    artist = artist,
    coverArtId = coverArtId,
    durationMs = durationMs,
    songCount = songCount,
    year = year,
    releaseDate = releaseDate,
    createdMs = createdMs,
    updatedMs = updatedMs,
    playCount = playCount,
    lastPlayedMs = lastPlayedMs,
    liked = liked,
    rating = rating,
    genre = genre,
)

fun Album.toEntity(libraryId: String? = null): AlbumEntity = AlbumEntity(
    libraryId = libraryId,
    id = id,
    title = title,
    artist = artist,
    coverArtId = coverArtId,
    durationMs = durationMs,
    songCount = songCount,
    year = year,
    releaseDate = releaseDate,
    createdMs = createdMs,
    updatedMs = updatedMs,
    playCount = playCount,
    lastPlayedMs = lastPlayedMs,
    liked = liked,
    rating = rating,
    genre = genre,
)

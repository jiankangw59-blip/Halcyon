package com.ella.music.player

import android.app.Application
import com.ella.music.data.model.Song
import java.io.StringReader
import java.io.StringWriter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackQueuePersistenceTest {
    private val snapshot = PlaybackStateSnapshot(1, 12_000L, 0, false, 1f, 1f, false)
    private val songs = listOf(
        onlineSong(
            id = 1522917000L,
            title = "\u6674\u5929",
            onlineId = "0039MnYb0qxYhV",
            path = "https://car-er.kuwo.cn/saved/F000000bYDlc2XxKLs.flac"
        ),
        onlineSong(id = 958053757L, title = "\u6401\u6d45", onlineId = "001Bbywq2gicae"),
        onlineSong(
            id = "lx_tx_0027oMO61wWi55".hashCode().toLong(),
            title = "\u53d1\u5982\u96ea",
            onlineId = "0027oMO61wWi55"
        )
    )

    @Test
    fun jsonRoundTripRetainsOnlineQueueOrderAndSelectedSong() {
        val json = JSONObject(savedQueue(songs))
        val items = json.getJSONArray("songs")
        val restored = List(items.length()) { items.getJSONObject(it).toPlaybackQueueSongOrNull() }

        assertEquals(songs, restored)
        assertEquals(1, json.getInt("index"))
        assertEquals(songs[1], restored[json.getInt("index")])
    }

    @Test
    fun streamingRoundTripRetainsOnlineQueueOrderAndSelectedSong() {
        val restored = requireNotNull(parseSavedQueue(StringReader(savedQueue(songs)), null))

        assertEquals(songs, restored.songs)
        assertEquals(1, restored.index)
        assertEquals(songs[1], restored.songs[restored.index])
        assertEquals(snapshot.positionMs, restored.positionMs)
    }

    @Test
    fun streamingRestoreHonorsStateIndexForAnUnresolvedOnlineSong() {
        val rawQueue = savedQueue(songs, snapshot.copy(index = 0))
        val restored = requireNotNull(parseSavedQueue(rawQueue, snapshot.toJson().toString()))

        assertEquals(songs, restored.songs)
        assertEquals(1, restored.index)
        assertEquals(songs[1], restored.songs[restored.index])
    }

    @Test
    fun jsonReaderRejectsPathlessSongsWithoutCompleteOnlineIdentity() {
        invalidSongs().forEach { song ->
            assertNull(song.toPlaybackQueueJson().toPlaybackQueueSongOrNull())
        }
    }

    @Test
    fun streamingReaderRejectsInvalidSongsAndRetainsResolvableSongs() {
        val invalid = invalidSongs()
        val restored = requireNotNull(parseSavedQueue(savedQueue(songs + invalid), null))

        assertEquals(songs, restored.songs)
        assertEquals(1, restored.index)
        assertEquals(songs[1], restored.songs[restored.index])
        assertNull(parseSavedQueue(savedQueue(invalid), null))
    }

    @Test
    fun bothReadersRetainLocalSongsWithoutOnlineIdentity() {
        val local = songs[0].copy(path = "/music/local.flac", onlineSource = "", onlineId = "")

        assertEquals(local, local.toPlaybackQueueJson().toPlaybackQueueSongOrNull())
        val restored = requireNotNull(parseSavedQueue(savedQueue(listOf(local), snapshot.copy(index = 0)), null))
        assertEquals(listOf(local), restored.songs)
        assertEquals(0, restored.index)
    }

    private fun invalidSongs(): List<Song> = listOf(
        songs[1].copy(onlineSource = "", onlineId = ""),
        songs[1].copy(onlineSource = ""),
        songs[1].copy(onlineId = ""),
        songs[1].copy(path = " ", onlineSource = " ", onlineId = "\t")
    )

    private fun savedQueue(
        songs: List<Song>,
        state: PlaybackStateSnapshot = snapshot
    ): String = StringWriter().also { writePlaybackQueue(it, state, songs) }.toString()

    private fun onlineSong(
        id: Long,
        title: String,
        onlineId: String,
        path: String = ""
    ): Song = Song(
        id = id,
        title = title,
        artist = "\u5468\u6770\u4f26",
        album = "Album",
        albumId = 0L,
        duration = 240_000L,
        path = path,
        fileName = "$title.flac",
        onlineSource = "tx",
        onlineId = onlineId,
        playbackSourceKey = "playlist:zhou"
    )
}

package com.ella.music.player

import android.app.Application
import android.net.Uri
import com.ella.music.data.model.Song
import com.ella.music.data.musicfree.MusicFreeStreamHeaders
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnlinePlaybackUriTest {
    @Test
    fun savedLxLinksAndUnresolvedSongsRetainTheirOnlineIdentity() {
        for (source in listOf("kw", "wy", "tx", "kg", "mg", "qs", "sd")) {
            for (path in listOf("", OLD_HTTP_URL, OLD_HTTPS_URL)) {
                val song = song(path = path, source = source)
                val uri = song.playbackUri()

                assertEquals("source=$source path=$path", "ella-online", uri.scheme)
                assertEquals(source, uri.host)
                assertEquals(listOf(song.onlineId), uri.pathSegments)
                assertEquals(song.onlineId, uri.lastPathSegment)
                assertEquals(path.takeIf { it.isNotBlank() }, uri.getQueryParameter("fallback"))
            }
        }
    }

    @Test
    fun songIdIsEncodedAsOnePathSegmentAndFallbackSurvivesItsOwnQueryAndFragment() {
        val onlineId = "\u5468\u6770\u4f26 /?#&=+%25"
        for (path in listOf("", OLD_HTTPS_URL)) {
            val uri = song(path = path, onlineId = onlineId).playbackUri()

            assertEquals("ella-online", uri.scheme)
            assertEquals("tx", uri.host)
            assertEquals(listOf(onlineId), uri.pathSegments)
            assertEquals(onlineId, uri.lastPathSegment)
            assertNull(uri.fragment)
            assertEquals(path.takeIf { it.isNotBlank() }, uri.getQueryParameter("fallback"))
        }
    }

    @Test
    fun missingOnlineIdDoesNotCreateAnUnresolvablePlaceholder() {
        for (onlineId in listOf("", " ")) {
            for (path in listOf("", OLD_HTTP_URL, OLD_HTTPS_URL)) {
                assertEquals(Uri.parse(path), song(path = path, onlineId = onlineId).playbackUri())
            }
        }
    }

    @Test
    fun ordinaryUrlsAndNonLxSourcesKeepTheirOriginalUri() {
        for (source in listOf("", "unknown-provider", "musicfree:fixture", "netease")) {
            for (path in listOf(OLD_HTTP_URL, OLD_HTTPS_URL)) {
                assertEquals(Uri.parse(path), song(path = path, source = source).playbackUri())
            }
        }
    }

    @Test
    fun unresolvedUnknownSourceKeepsItsLegacyPlaceholder() {
        val song = song(path = "", source = "unknown-provider")
        val uri = song.playbackUri()

        assertEquals("ella-online", uri.scheme)
        assertEquals(song.onlineSource, uri.host)
        assertEquals(song.onlineId, uri.lastPathSegment)
        assertNull(uri.getQueryParameter("fallback"))
    }

    @Test
    fun neteaseStreamUriKeepsItsExistingRoute() {
        val path = "halcyon-netease://song/42"
        assertEquals(Uri.parse(path), song(path = path, source = "netease", onlineId = "42").playbackUri())
    }

    @Test
    fun downloadedLxSongKeepsItsLocalFileUri() {
        val path = "/storage/emulated/0/Music/downloaded track.flac"
        assertEquals(Uri.fromFile(File(path)), song(path = path).playbackUri())
    }

    @Test
    fun localAndRemoteFileUrisAreNotRoutedThroughTheOnlineResolver() {
        val paths = listOf(
            "content://media/external/audio/media/42",
            "file:///storage/emulated/0/Music/downloaded%20track.flac",
            "smb://files.test/music/track.flac",
            "ftp://files.test/music/track.flac",
            "ftps://files.test/music/track.flac",
            "sftp://files.test/music/track.flac"
        )
        for (path in paths) {
            assertEquals(Uri.parse(path), song(path = path).playbackUri())
        }
    }

    @Test
    fun musicFreeMarkedHttpLinksKeepTheirHeaderScopeEvenWithAnLxSourceTag() {
        val streamHeaders = MusicFreeStreamHeaders()
        val result = JSONObject().put("headers", JSONObject().put("Referer", "https://fixture.test/"))
        for (path in listOf(OLD_HTTP_URL, OLD_HTTPS_URL)) {
            val marked = streamHeaders.register(path, result)
            for (source in listOf("tx", "musicfree:fixture")) {
                assertEquals(Uri.parse(marked), song(path = marked, source = source).playbackUri())
            }
        }
    }

    private fun song(
        path: String,
        source: String = "tx",
        onlineId: String = "0039MnYb0qxYhV"
    ) = Song(
        id = 1522917000L,
        title = "Fixture track",
        artist = "Fixture artist",
        album = "Fixture album",
        albumId = 0L,
        duration = 180000L,
        path = path,
        fileName = "track.flac",
        onlineSource = source,
        onlineId = onlineId
    )

    private companion object {
        const val OLD_HTTP_URL = "http://cdn.example.test/old.flac?expires=1&name=a%20b#original"
        const val OLD_HTTPS_URL = "https://cdn.example.test/old.flac?expires=1&name=a%20b#original"
    }
}

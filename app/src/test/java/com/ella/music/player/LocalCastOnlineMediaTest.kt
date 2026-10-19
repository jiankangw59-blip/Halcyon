package com.ella.music.player

import android.app.Application
import android.net.Uri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalCastOnlineMediaTest {
    @Test
    fun savedOnlineStreamsKeepTheirExistingCastUrlAndRoundTripIdentity() = withServer { server ->
        val url = "https://cdn.example.test/song.flac?token=a%20b&expires=123"
        val item = onlineItem(url)

        assertEquals(url, server.urlFor(item))
        assertSame(item, server.originalFor(url, item.mediaId))
    }

    @Test
    fun sameUrlQueueEntriesKeepDistinctIdentitiesThroughCastRoundTrip() = withServer { server ->
        val url = "https://cdn.example.test/song.flac?token=shared"
        val first = onlineItem(url, mediaId = "queue:track-id:0")
        val second = onlineItem(url, mediaId = "queue:track-id:1")
        val converter = HalcyonCastMediaItemConverter(server)

        val firstCastItem = converter.toMediaQueueItem(first)
        val secondCastItem = converter.toMediaQueueItem(second)

        val delegate = DefaultMediaItemConverter()
        assertEquals(first.mediaId, delegate.toMediaItem(firstCastItem).mediaId)
        assertEquals(second.mediaId, delegate.toMediaItem(secondCastItem).mediaId)
        assertEquals(url, firstCastItem.media?.contentUrl)
        assertEquals(url, secondCastItem.media?.contentUrl)
        assertSame(first, converter.toMediaItem(firstCastItem))
        assertSame(second, converter.toMediaItem(secondCastItem))
    }

    @Test
    fun ordinaryHttpItemDoesNotRestoreAnotherOnlineEntryWithTheSameUrl() = withServer { server ->
        val url = "https://cdn.example.test/song.flac"
        val online = onlineItem(url)
        val direct = MediaItem.Builder()
            .setMediaId("queue:direct:1")
            .setUri(url)
            .setMimeType("audio/flac")
            .build()
        val converter = HalcyonCastMediaItemConverter(server)
        val onlineCastItem = converter.toMediaQueueItem(online)
        val directCastItem = converter.toMediaQueueItem(direct)

        assertNull(server.originalFor(url, direct.mediaId))
        val restored = converter.toMediaItem(directCastItem)
        assertEquals(direct.mediaId, restored.mediaId)
        assertEquals(direct.localConfiguration?.uri, restored.localConfiguration?.uri)
        assertSame(online, converter.toMediaItem(onlineCastItem))
    }

    @Test
    fun unresolvedOrInvalidOnlineUrisAreNeverServedAsLocalFiles() = withServer { server ->
        for (fallback in listOf(null, "", "file:///music/song.flac", "content://media/audio/1", "https:///song")) {
            assertNull(server.urlFor(onlineItem(fallback)))
        }
    }

    @Test
    fun ordinaryHttpStreamsRemainUnchanged() = withServer { server ->
        val url = "https://cdn.example.test/direct.flac"
        val item = MediaItem.fromUri(url)

        assertEquals(url, server.urlFor(item))
        assertNull(server.originalFor(url, item.mediaId))
    }

    private fun onlineItem(fallback: String?, mediaId: String = "queue:track-id:0"): MediaItem {
        val uri = Uri.Builder().scheme(ONLINE_PLAYBACK_SCHEME).authority("tx").appendPath("track-id")
        fallback?.let { uri.appendQueryParameter("fallback", it) }
        return MediaItem.Builder().setMediaId(mediaId).setUri(uri.build()).setMimeType("audio/flac").build()
    }

    private fun withServer(block: (LocalCastMediaServer) -> Unit) {
        val server = LocalCastMediaServer(RuntimeEnvironment.getApplication())
        try {
            block(server)
        } finally {
            server.release()
        }
    }
}

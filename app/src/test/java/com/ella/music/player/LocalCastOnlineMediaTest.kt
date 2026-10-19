package com.ella.music.player

import android.app.Application
import android.net.Uri
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
        assertSame(item, server.originalFor(url))
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
        assertNull(server.originalFor(url))
    }

    private fun onlineItem(fallback: String?): MediaItem {
        val uri = Uri.Builder().scheme(ONLINE_PLAYBACK_SCHEME).authority("tx").appendPath("track-id")
        fallback?.let { uri.appendQueryParameter("fallback", it) }
        return MediaItem.Builder().setMediaId("queue:track-id:0").setUri(uri.build()).build()
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

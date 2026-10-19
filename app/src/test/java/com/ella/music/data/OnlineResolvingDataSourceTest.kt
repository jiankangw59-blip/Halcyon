package com.ella.music.data

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnlineResolvingDataSourceTest {
    @Before
    @After
    fun clearSettingsSingletonBetweenApplications() {
        SettingsManager::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.set(null, null)
    }

    @Test
    fun realResolverUsesSavedHttpUrlWhenNoSourceIsSelected() = runBlocking {
        val context = emptySettingsContext()
        for (fallback in listOf(OLD_URL, "http://cdn.example.test/old.mp3?name=a%20b&expires=1")) {
            assertEquals(
                fallback,
                OnlineResolvingDataSource.resolveLx(context, onlineUri(fallback = fallback))
            )
        }
    }

    @Test
    fun realResolverRejectsMissingBlankAndLocalFallbackWhenNoSourceIsSelected() = runBlocking {
        val context = emptySettingsContext()
        val invalidFallbacks = listOf(
            null,
            "",
            " ",
            "/storage/emulated/0/Music/track.flac",
            "file:///storage/emulated/0/Music/track.flac",
            "content://media/external/audio/media/42",
            "http:///missing-host.flac"
        )
        for (fallback in invalidFallbacks) {
            val failure = runCatching {
                OnlineResolvingDataSource.resolveLx(context, onlineUri(fallback = fallback))
            }.exceptionOrNull()

            assertTrue("Expected IOException for fallback=$fallback, got $failure", failure is IOException)
        }
    }

    @Test
    fun selectedSourceNeverUsesTheSavedFallback() {
        for (fallback in listOf(null, OLD_URL, "file:///storage/emulated/0/Music/track.flac")) {
            assertNull(
                OnlineResolvingDataSource.resolveLxFallback(
                    onlineUri(fallback = fallback),
                    sourceScript = "fixture-source-script"
                )
            )
        }
    }

    @Test
    fun resolvedUrlReplacesOnlyTheUriAndPreservesHeadersRangeAndCacheKey() {
        val upstream = RecordingDataSource()
        val uri = onlineUri(fallback = OLD_URL)
        val requests = mutableListOf<Uri>()
        val source = OnlineResolvingDataSource(upstream) { requested ->
            requests += requested
            FRESH_URL
        }
        val original = DataSpec.Builder()
            .setUri(uri)
            .setPosition(4096L)
            .setLength(1024L)
            .setKey("tx:001Bbywq2gicae")
            .setHttpRequestHeaders(
                mapOf("Referer" to "https://fixture.test/", "Range" to "bytes=4096-5119")
            )
            .setFlags(DataSpec.FLAG_ALLOW_GZIP)
            .build()

        try {
            assertEquals(RecordingDataSource.OPEN_LENGTH, source.open(original))
            assertEquals(listOf(uri), requests)
            assertEquals(OLD_URL, requests.single().getQueryParameter("fallback"))
            val opened = upstream.opened.single()
            assertEquals(Uri.parse(FRESH_URL), opened.uri)
            assertEquals(original.position, opened.position)
            assertEquals(original.length, opened.length)
            assertEquals(original.key, opened.key)
            assertEquals(original.httpRequestHeaders, opened.httpRequestHeaders)
            assertEquals(original.httpMethod, opened.httpMethod)
            assertEquals(original.flags, opened.flags)
            assertEquals(uri, original.uri)
            assertEquals(Uri.parse(FRESH_URL), source.getUri())
        } finally {
            source.close()
        }
    }

    @Test
    fun reopeningTheSameOnlineItemResolvesAFreshUrlEveryTime() {
        val upstream = RecordingDataSource()
        var resolutions = 0
        val source = OnlineResolvingDataSource(upstream) {
            "https://cdn.example.test/track.flac?generation=${++resolutions}"
        }
        val request = DataSpec.Builder().setUri(onlineUri(fallback = OLD_URL)).build()

        repeat(2) {
            try {
                source.open(request)
            } finally {
                source.close()
            }
        }

        assertEquals(2, resolutions)
        assertEquals(
            listOf(
                "https://cdn.example.test/track.flac?generation=1",
                "https://cdn.example.test/track.flac?generation=2"
            ),
            upstream.opened.map { it.uri.toString() }
        )
        assertEquals(2, upstream.closes)
    }

    @Test
    fun resolverReceivesSpecialCharactersAsOneDecodedSongId() {
        val upstream = RecordingDataSource()
        val onlineId = "\u5468\u6770\u4f26 /?#&=+%25"
        val source = OnlineResolvingDataSource(upstream) { uri ->
            assertEquals("tx", uri.host)
            assertEquals(listOf(onlineId), uri.pathSegments)
            assertEquals(onlineId, uri.lastPathSegment)
            assertEquals(OLD_URL, uri.getQueryParameter("fallback"))
            FRESH_URL
        }

        try {
            source.open(DataSpec.Builder().setUri(onlineUri(onlineId, OLD_URL)).build())
            assertEquals(Uri.parse(FRESH_URL), upstream.opened.single().uri)
        } finally {
            source.close()
        }
    }

    @Test
    fun resolverFailurePreservesItsCauseAndDoesNotOpenTheFallback() {
        val upstream = RecordingDataSource()
        val cause = IllegalStateException("Fixture source rejected the track")
        val source = OnlineResolvingDataSource(upstream) { throw cause }
        val request = DataSpec.Builder().setUri(onlineUri(fallback = OLD_URL)).build()

        val failure = assertThrows(IOException::class.java) { source.open(request) }

        assertSame(cause, failure.cause)
        assertTrue(upstream.opened.isEmpty())
    }

    @Test
    fun ioFailureKeepsItsOriginalCauseAndDoesNotOpenTheFallback() {
        val upstream = RecordingDataSource()
        val cause = IOException("Fixture source unavailable")
        val source = OnlineResolvingDataSource(upstream) { throw cause }

        val failure = assertThrows(IOException::class.java) {
            source.open(DataSpec.Builder().setUri(onlineUri(fallback = OLD_URL)).build())
        }

        assertTrue(failure === cause || failure.cause === cause)
        assertTrue(upstream.opened.isEmpty())
    }

    @Test
    fun missingResolvedUrlFailsWithoutOpeningAnEmptyUriOrTheFallback() {
        for (result in listOf(null, "", " ")) {
            val upstream = RecordingDataSource()
            val source = OnlineResolvingDataSource(upstream) { result }

            assertThrows(IOException::class.java) {
                source.open(DataSpec.Builder().setUri(onlineUri(fallback = OLD_URL)).build())
            }
            assertTrue(upstream.opened.isEmpty())
        }
    }

    @Test
    fun cancelledResolutionDoesNotOpenTheFallback() {
        val upstream = RecordingDataSource()
        val cancellation = CancellationException("Fixture playback cancelled")
        val source = OnlineResolvingDataSource(upstream) { throw cancellation }

        val failure = assertThrows(CancellationException::class.java) {
            source.open(DataSpec.Builder().setUri(onlineUri(fallback = OLD_URL)).build())
        }

        assertEquals(cancellation.message, failure.message)
        assertTrue(upstream.opened.isEmpty())
    }

    @Test
    fun otherSchemesPassTheOriginalRequestToUpstreamWithoutResolving() {
        val paths = listOf(
            OLD_URL,
            "http://cdn.example.test/direct.mp3",
            "content://media/external/audio/media/42",
            "file:///storage/emulated/0/Music/track.flac",
            "smb://files.test/music/track.flac",
            "ftp://files.test/music/track.flac",
            "ftps://files.test/music/track.flac",
            "sftp://files.test/music/track.flac",
            "halcyon-netease://song/42"
        )
        for (path in paths) {
            val upstream = RecordingDataSource()
            val source = OnlineResolvingDataSource(upstream) { error("Unexpected resolution of $it") }
            val request = DataSpec.Builder().setUri(path).setPosition(128L)
                .setLength(256L).setKey("unchanged")
                .setHttpRequestHeaders(mapOf("X-Fixture" to "unchanged")).build()

            try {
                assertEquals(RecordingDataSource.OPEN_LENGTH, source.open(request))
                assertSame(request, upstream.opened.single())
                assertEquals(Uri.parse(path), source.getUri())
            } finally {
                source.close()
            }
        }
    }

    private suspend fun emptySettingsContext(): Application {
        val context = RuntimeEnvironment.getApplication()
        context.dataStore.edit { it.clear() }
        assertNull(SettingsManager.getInstance(context).selectedLxSource.first())
        return context
    }

    private fun onlineUri(onlineId: String = "001Bbywq2gicae", fallback: String? = null): Uri =
        Uri.Builder().scheme("ella-online").authority("tx").appendPath(onlineId)
            .apply { fallback?.let { appendQueryParameter("fallback", it) } }
            .build()

    private class RecordingDataSource : DataSource {
        val opened = mutableListOf<DataSpec>()
        var closes = 0
            private set
        private var openedUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            openedUri = dataSpec.uri
            return OPEN_LENGTH
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            if (length == 0) 0 else C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = openedUri

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() {
            closes++
            openedUri = null
        }

        companion object {
            const val OPEN_LENGTH = 1024L
        }
    }

    private companion object {
        const val OLD_URL = "https://cdn.example.test/old.flac?expires=1&name=a%20b#original"
        const val FRESH_URL = "https://cdn.example.test/fresh.flac?expires=2"
    }
}

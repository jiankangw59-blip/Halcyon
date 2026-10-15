package com.ella.music.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.ella.music.data.lx.LxOnlineService
import com.ella.music.data.lx.LxOnlineSong
import com.ella.music.data.model.Song
import com.ella.music.player.ONLINE_PLAYBACK_SCHEME
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Resolves [ONLINE_PLAYBACK_SCHEME] URIs to a playable https URL when the item is opened.
 *
 * Songs imported from external players (LX Music, MusicFree, ...) carry an `onlineSource` /
 * `onlineId` pair but no playback URL, because those players resolve URLs lazily. Previously such
 * an entry produced an empty Uri, which ExoPlayer opened as a local file:
 *
 *   java.io.FileNotFoundException: : open failed: ENOENT
 *   PlaybackException code=ERROR_CODE_IO_FILE_NOT_FOUND uri=
 *
 * Resolving inside [open] keeps the URL fresh on every playback, so an expired CDN link is simply
 * re-resolved instead of failing. Everything that is not [ONLINE_PLAYBACK_SCHEME] is passed through
 * untouched, so local files, SMB/FTP entries and NetEase streams are unaffected.
 *
 * Structure mirrors [com.ella.music.data.netease.NeteaseStreamCache.SchemeRoutingDataSource].
 */
@UnstableApi
internal class OnlineResolvingDataSource(
    private val upstream: DataSource,
    private val context: Context
) : DataSource {

    private var active: DataSource = upstream

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != ONLINE_PLAYBACK_SCHEME) {
            active = upstream
            return upstream.open(dataSpec)
        }

        val source = uri.host.orEmpty()
        val songmid = uri.lastPathSegment.orEmpty()
        // IOException is what DataSource.open() is allowed to throw; ExoPlayer turns it into a
        // PlaybackException just like any other source failure.
        val resolved = resolve(source, songmid)
            ?: throw java.io.IOException("no playable url for $source:$songmid")

        active = upstream
        return upstream.open(dataSpec.buildUpon().setUri(Uri.parse(resolved)).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active.read(buffer, offset, length)

    override fun getUri(): Uri? = active.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

    override fun close() {
        active.close()
    }

    /**
     * Blocking on purpose: media3 calls [open] on its loader thread, so waiting here is expected.
     * Returns null when no LX source is configured or the source cannot resolve the track.
     */
    private fun resolve(source: String, songmid: String): String? = runBlocking {
        runCatching {
            val script = SettingsManager.getInstance(context)
                .selectedLxSource.first()?.script.orEmpty()
            if (script.isBlank()) return@runCatching null
            // Only onlineSource/onlineId matter: resolvePlayableSong() derives everything else
            // from the source response and copies the rest of the stub through unchanged.
            val stub = Song(
                id = 0L,
                title = "",
                artist = "",
                album = "",
                albumId = 0L,
                duration = 0L,
                path = "",
                fileName = "",
                onlineSource = source,
                onlineId = songmid
            )
            LxOnlineService(context)
                .resolvePlayableSong(
                    LxOnlineSong(
                        song = stub,
                        source = source,
                        songmid = songmid,
                        quality = ""
                    ),
                    script
                )
                .path
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    companion object {
        /** Wraps [upstream] so that [ONLINE_PLAYBACK_SCHEME] entries resolve at open time. */
        fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory =
            DataSource.Factory { OnlineResolvingDataSource(upstream.createDataSource(), context) }
    }
}

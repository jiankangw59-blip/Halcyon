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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Resolves [ONLINE_PLAYBACK_SCHEME] URIs to a playable HTTP(S) URL when the item is opened.
 *
 * LX songs retain their `onlineSource` / `onlineId` pair when a playback URL is absent or stale.
 *
 * With an LX source configured, [open] resolves a fresh URL on every playback. Without a source,
 * an HTTP(S) fallback query parameter may preserve an existing direct URL. Everything that is not
 * [ONLINE_PLAYBACK_SCHEME] is passed through unchanged.
 *
 * Structure mirrors [com.ella.music.data.netease.NeteaseStreamCache.SchemeRoutingDataSource].
 */
@UnstableApi
internal class OnlineResolvingDataSource(
    private val upstream: DataSource,
    private val resolver: suspend (uri: Uri) -> String?
) : DataSource {

    constructor(upstream: DataSource, context: Context) : this(
        upstream,
        { uri -> resolveLx(context, uri) }
    )

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

        val resolved = resolve(uri)

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

    /** Media3 calls [open] on its loader thread, so resolving here intentionally blocks. */
    private fun resolve(uri: Uri): String {
        val source = uri.host.orEmpty()
        val songmid = uri.lastPathSegment.orEmpty()
        val resolved = try {
            runBlocking { resolver(uri) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted resolving $source:$songmid").apply {
                initCause(error)
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("failed to resolve playable url for $source:$songmid", error)
        }
        return resolved?.takeIf { it.isNotBlank() }
            ?: throw IOException("no playable url for $source:$songmid")
    }

    companion object {
        /** Returns a direct URL only when no LX script is configured. */
        internal fun resolveLxFallback(uri: Uri, sourceScript: String): String? {
            if (sourceScript.isNotBlank()) return null
            val source = uri.host.orEmpty()
            val songmid = uri.lastPathSegment.orEmpty()
            val fallback = uri.getQueryParameter("fallback")
                ?: throw IOException("no LX source configured for $source:$songmid")
            val fallbackUri = Uri.parse(fallback)
            if ((!fallbackUri.scheme.equals("http", ignoreCase = true) &&
                !fallbackUri.scheme.equals("https", ignoreCase = true)) || fallbackUri.host.isNullOrBlank()
            ) {
                throw IOException("invalid fallback url for $source:$songmid")
            }
            return fallback
        }

        internal suspend fun resolveLx(context: Context, uri: Uri): String {
            val source = uri.host.orEmpty()
            val songmid = uri.lastPathSegment.orEmpty()
            val script = SettingsManager.getInstance(context)
                .selectedLxSource.first()?.script.orEmpty()
            resolveLxFallback(uri, script)?.let { return it }
            // Keep the existing source/id payload for persisted playlist entries.
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
            return LxOnlineService(context)
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
        }

        /** Wraps [upstream] so that [ONLINE_PLAYBACK_SCHEME] entries resolve at open time. */
        fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory =
            DataSource.Factory { OnlineResolvingDataSource(upstream.createDataSource(), context) }
    }
}

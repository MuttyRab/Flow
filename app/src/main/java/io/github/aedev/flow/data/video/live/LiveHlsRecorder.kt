package io.github.aedev.flow.data.video.live

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import io.github.aedev.flow.network.ProxyAwareClient
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Records new MPEG-TS media segments of an unencrypted live HLS playlist.
 * Unlike the VOD RangeDownloader, a live stream has no finite Content-Length.
 * Segments are appended only after a complete validated download.
 * Encrypted, fMP4/CMAF and discontinuous streams fail closed.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LiveHlsRecorder(
    private val videoId: String,
    private val workDir: File,
    private val stopRequested: () -> Boolean,
    private val onProgress: (Long, Long) -> Unit,
) {
    private val proxyClient =
        ProxyAwareClient {
            connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
        }
    private val client: OkHttpClient get() = proxyClient.get()

    suspend fun record(): File = withContext(Dispatchers.IO) {
        require(workDir.isDirectory || workDir.mkdirs()) { "Cannot create recording folder" }
        val output = File(workDir, "capture.ts")
        val part = File(workDir, "segment.part")
        var manifestUrl = freshManifest()
        var sequence: Long? = null
        var discontinuity: Int? = null
        var durationUs = 0L
        var errors = 0
        var roundsWithoutNew = 0
        var segmentCount = 0
        try {
            while (!stopRequested() && output.length() < MAX_BYTES && durationUs < MAX_DURATION_US) {
                try {
                    val (playlistUrl, playlist) = loadMediaPlaylist(manifestUrl)
                    val next = sequence ?: LiveSegmentPlan.startingSequence(playlist.mediaSequence, playlist.segments.size)
                    val newIndices = LiveSegmentPlan.nextIndices(playlist.mediaSequence, playlist.segments.size, next)
                    var nextSequence = next
                    var recorded = false
                    for (index in newIndices) {
                        if (stopRequested()) break
                        val segment = playlist.segments[index]
                        val sequenceNumber = playlist.mediaSequence + index
                        if (sequenceNumber != nextSequence) throw IOException("Missing HLS segment in live window")
                        if (segment.hasGapTag) throw IOException("Live stream contains an unavailable segment")
                        if (segment.drmInitData != null || segment.fullSegmentEncryptionKeyUri != null ||
                            segment.encryptionIV != null || segment.initializationSegment != null
                        ) throw UnsupportedStream("Encrypted or fragmented-MP4 HLS is not supported")
                        val segmentDiscontinuity = playlist.discontinuitySequence + segment.relativeDiscontinuitySequence
                        if (discontinuity != null && discontinuity != segmentDiscontinuity) {
                            throw UnsupportedStream("HLS encoding changed during recording")
                        }
                        fetchToFile(UriUtil.resolve(playlistUrl, segment.url), segment.byteRangeOffset, segment.byteRangeLength, part)
                        if (!isTransportStream(part)) throw UnsupportedStream("This livestream is not MPEG-TS HLS")
                        if (output.length() + part.length() > MAX_BYTES) break
                        java.io.FileOutputStream(output, true).use { out -> part.inputStream().use { it.copyTo(out) } }
                        part.delete()
                        discontinuity = segmentDiscontinuity
                        durationUs += segment.durationUs
                        segmentCount++
                        nextSequence = sequenceNumber + 1
                        sequence = nextSequence
                        recorded = true
                        onProgress(durationUs / 1_000_000L, output.length())
                    }
                    errors = 0
                    roundsWithoutNew = if (recorded) 0 else roundsWithoutNew + 1
                    if (playlist.hasEndTag) break
                    if (roundsWithoutNew > MAX_IDLE_POLLS) throw IOException("Livestream stopped producing segments")
                    delay((playlist.targetDurationUs / 2_000L).coerceIn(1_000L, 7_000L))
                } catch (e: UnsupportedStream) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (stopRequested()) break
                    errors++
                    if (errors >= MAX_RETRIES) {
                        if (segmentCount == 0) throw IOException("Cannot retrieve live HLS segments", e)
                        break // save the playable portion recorded before network failure
                    }
                    delay((errors * 1_500L).coerceAtMost(8_000L))
                    manifestUrl = runCatching { freshManifest() }.getOrDefault(manifestUrl) // refresh expired signed URLs
                }
            }
        } finally {
            part.delete()
        }
        if (segmentCount == 0 || output.length() == 0L) throw IOException("No playable live segments captured")
        output
    }

    private suspend fun freshManifest(): String =
        InnerTubeVideoStreamExtractor.extract(videoId)
            ?.takeIf { it.isLive }
            ?.liveHlsUrl
            ?.takeIf { it.startsWith("https://") }
            ?: throw UnsupportedStream("An HLS livestream URL is not available for this video")

    private fun loadMediaPlaylist(url: String): Pair<String, HlsMediaPlaylist> {
        var current = url
        repeat(3) {
            val data = downloadBytes(current)
            val playlist = HlsPlaylistParser().parse(Uri.parse(current), data.inputStream())
            when (playlist) {
                is HlsMediaPlaylist -> return current to playlist
                is HlsMultivariantPlaylist -> {
                    val best = playlist.variants.maxWithOrNull(compareBy({ it.format.height }, { it.format.bitrate }))
                        ?: throw UnsupportedStream("HLS master playlist has no variants")
                    current = UriUtil.resolve(playlist.baseUri, best.url.toString())
                }
                else -> throw UnsupportedStream("Unsupported HLS playlist")
            }
        }
        throw UnsupportedStream("Too many nested HLS playlists")
    }

    private fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HLS playlist HTTP ${response.code}")
            val body = response.body.bytes()
            if (body.size > MAX_PLAYLIST_BYTES) throw IOException("HLS playlist is too large")
            return body
        }
    }

    private fun fetchToFile(url: String, offset: Long, length: Long, destination: File) {
        val request = Request.Builder().url(url).apply {
            if (length >= 0) header("Range", "bytes=$offset-${offset + length - 1}")
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || (length >= 0 && response.code != 206)) {
                throw IOException("Live segment HTTP ${response.code}")
            }
            destination.outputStream().use { file ->
                val input = response.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > MAX_SEGMENT_BYTES) throw IOException("Live segment exceeds safety limit")
                    file.write(buffer, 0, read)
                }
                if (length >= 0 && count != length) throw IOException("Short HLS byte-range response")
            }
        }
    }

    private fun isTransportStream(file: File): Boolean {
        if (file.length() < 188) return false
        return file.inputStream().use { stream ->
            val sample = ByteArray(189)
            val n = stream.read(sample)
            sample[0] == 0x47.toByte() && (n < 189 || sample[188] == 0x47.toByte())
        }
    }

    internal class UnsupportedStream(message: String) : IOException(message)

    private companion object {
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
        const val MAX_SEGMENT_BYTES = 50L * 1024 * 1024
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_DURATION_US = 4L * 60 * 60 * 1_000_000
        const val MAX_RETRIES = 8
        const val MAX_IDLE_POLLS = 18
    }
}

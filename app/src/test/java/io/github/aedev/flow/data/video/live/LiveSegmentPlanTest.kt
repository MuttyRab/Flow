package io.github.aedev.flow.data.video.live

import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveSegmentPlanTest {
    @Test
    fun startAtLiveEdgeNotBeginningOfDvrWindow() {
        assertThat(LiveSegmentPlan.startingSequence(100L, 12)).isEqualTo(110L)
        assertThat(LiveSegmentPlan.startingSequence(42L, 1)).isEqualTo(42L)
        assertThat(LiveSegmentPlan.startingSequence(42L, 0)).isEqualTo(42L)
    }

    @Test
    fun slidingWindowNeverReplaysRecordedSegments() {
        assertThat(LiveSegmentPlan.nextIndices(100L, 4, 102L)).containsExactly(2, 3).inOrder()
        assertThat(LiveSegmentPlan.nextIndices(102L, 4, 104L)).containsExactly(2, 3).inOrder()
        assertThat(LiveSegmentPlan.nextIndices(105L, 2, 107L)).isEmpty()
    }

    @Test
    fun missedWindowFailsRatherThanSplicingAVisibleGap() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveSegmentPlan.nextIndices(200L, 3, 199L)
        }
    }

    @Test
    fun media3ParsesLiveTransportStreamSequence() {
        val fixture =
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:900
            #EXTINF:4.0,
            first.ts
            #EXTINF:4.0,
            second.ts
            #EXTINF:4.0,
            third.ts
            """.trimIndent()
        val playlist =
            HlsPlaylistParser().parse(
                Uri.parse("https://example.org/live/index.m3u8"),
                fixture.byteInputStream(),
            ) as HlsMediaPlaylist
        assertThat(playlist.mediaSequence).isEqualTo(900L)
        assertThat(playlist.segments.map { it.url }).containsExactly("first.ts", "second.ts", "third.ts").inOrder()
        assertThat(LiveSegmentPlan.nextIndices(playlist.mediaSequence, playlist.segments.size, 901L))
            .containsExactly(1, 2)
            .inOrder()
    }

    @Test
    fun media3SeparatesMultivariantFromMediaPlaylist() {
        val fixture =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
            high.m3u8
            """.trimIndent()
        val result = HlsPlaylistParser().parse(Uri.parse("https://example.org/live/master.m3u8"), fixture.byteInputStream())
        assertThat(result).isInstanceOf(HlsMultivariantPlaylist::class.java)
        assertThat((result as HlsMultivariantPlaylist).variants).hasSize(2)
    }
}

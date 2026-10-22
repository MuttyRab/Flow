package io.github.aedev.flow.data.video.live

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class LiveHlsPlaylistInstrumentedTest {
    @Test
    fun parsesLiveMediaPlaylist() {
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
            """.trimIndent()
        val playlist =
            HlsPlaylistParser().parse(
                Uri.parse("https://example.org/live/index.m3u8"),
                fixture.byteInputStream(),
            ) as HlsMediaPlaylist
        assertThat(playlist.mediaSequence).isEqualTo(900L)
        assertThat(playlist.segments.map { it.url }).containsExactly("first.ts", "second.ts").inOrder()
    }

    @Test
    fun parsesMasterPlaylistVariants() {
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

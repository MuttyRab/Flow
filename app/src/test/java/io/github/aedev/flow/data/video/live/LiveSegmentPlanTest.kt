package io.github.aedev.flow.data.video.live

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

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
}

package io.github.aedev.flow.data.video.live

/** A live playlist is a sliding window: never replay a segment already committed to disk. */
internal object LiveSegmentPlan {
    /** Start close to the live edge, not at the beginning of an hours-long DVR window. */
    fun startingSequence(mediaSequence: Long, segmentCount: Int): Long =
        mediaSequence + (segmentCount - 2).coerceAtLeast(0)

    /** A gap means the recording was interrupted too long to make a continuous file. */
    fun nextIndices(mediaSequence: Long, segmentCount: Int, nextSequence: Long): List<Int> {
        require(segmentCount >= 0)
        require(nextSequence >= mediaSequence) { "Live playlist advanced beyond the last saved segment" }
        return (0 until segmentCount).filter { mediaSequence + it >= nextSequence }
    }
}

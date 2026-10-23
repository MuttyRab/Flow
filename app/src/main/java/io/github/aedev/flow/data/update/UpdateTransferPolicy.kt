package io.github.aedev.flow.data.update

/** Validates resumable transfers so a CDN cannot append the wrong bytes to an existing APK. */
internal object UpdateTransferPolicy {
    private val contentRange = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

    fun resumeOffset(
        partialBytes: Long,
        expectedBytes: Long,
    ): Long =
        if (partialBytes < 0L || (expectedBytes > 0L && partialBytes > expectedBytes)) 0L else partialBytes

    fun acceptsPartialResponse(
        contentRangeHeader: String?,
        startByte: Long,
        expectedBytes: Long,
    ): Boolean {
        val groups = contentRange.matchEntire(contentRangeHeader?.trim().orEmpty())?.groupValues ?: return false
        val start = groups[1].toLongOrNull() ?: return false
        val end = groups[2].toLongOrNull() ?: return false
        val remoteTotal = groups[3].takeUnless { it == "*" }?.toLongOrNull()
        return start == startByte &&
            end >= start &&
            (remoteTotal == null || end < remoteTotal) &&
            (expectedBytes <= 0L || remoteTotal == expectedBytes) &&
            (expectedBytes <= 0L || end < expectedBytes)
    }
}

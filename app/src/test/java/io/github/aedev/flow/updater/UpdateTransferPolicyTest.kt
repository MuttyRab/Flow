package io.github.aedev.flow.updater

import io.github.aedev.flow.data.update.UpdateTransferPolicy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateTransferPolicyTest {
    @Test
    fun `partial download resumes from existing file size`() {
        assertThat(UpdateTransferPolicy.resumeOffset(19_000_000L, 29_175_152L)).isEqualTo(19_000_000L)
        assertThat(UpdateTransferPolicy.resumeOffset(29_175_152L, 29_175_152L)).isEqualTo(29_175_152L)
    }

    @Test
    fun `oversized or invalid partial download restarts`() {
        assertThat(UpdateTransferPolicy.resumeOffset(30_000_000L, 29_175_152L)).isEqualTo(0L)
        assertThat(UpdateTransferPolicy.resumeOffset(-1L, 29_175_152L)).isEqualTo(0L)
    }

    @Test
    fun `partial response must start at the requested byte and match published size`() {
        assertThat(
            UpdateTransferPolicy.acceptsPartialResponse(
                "bytes 19000000-29175151/29175152",
                19_000_000L,
                29_175_152L,
            ),
        ).isTrue()
        assertThat(
            UpdateTransferPolicy.acceptsPartialResponse(
                "bytes 0-29175151/29175152",
                19_000_000L,
                29_175_152L,
            ),
        ).isFalse()
        assertThat(
            UpdateTransferPolicy.acceptsPartialResponse(
                "bytes 19000000-29175151/29175153",
                19_000_000L,
                29_175_152L,
            ),
        ).isFalse()
    }

    @Test
    fun `invalid or missing content range is not safe for appending`() {
        assertThat(UpdateTransferPolicy.acceptsPartialResponse(null, 5, 20)).isFalse()
        assertThat(UpdateTransferPolicy.acceptsPartialResponse("bytes 5-4/20", 5, 20)).isFalse()
        assertThat(UpdateTransferPolicy.acceptsPartialResponse("bytes 5-20/20", 5, 20)).isFalse()
        assertThat(UpdateTransferPolicy.acceptsPartialResponse("bytes 5-19/*", 5, 20)).isFalse()
    }
}

package io.github.aedev.flow.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateApiUrlsTest {
    @Test
    fun `personal stable updates come from the fork`() {
        assertThat(UpdateApiUrls.forChannel("MuttyRab/Flow", nightly = false))
            .isEqualTo("https://api.github.com/repos/MuttyRab/Flow/releases/latest")
    }

    @Test
    fun `personal nightlies use the rolling release in the fork`() {
        assertThat(UpdateApiUrls.forChannel("MuttyRab/Flow", nightly = true))
            .isEqualTo("https://api.github.com/repos/MuttyRab/Flow/releases/tags/nightly")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a repo value cannot include URL query parameters`() {
        UpdateApiUrls.forChannel("MuttyRab/Flow?bad=1", nightly = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a repo value cannot redirect updates to another host`() {
        UpdateApiUrls.forChannel("evil.example/../../other", nightly = true)
    }
}

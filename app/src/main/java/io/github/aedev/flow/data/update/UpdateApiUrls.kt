package io.github.aedev.flow.data.update

/**
 * The update channel is part of the app's build identity: fork-signed APKs must only use
 * releases published by the same fork, never the upstream app's release/signing key.
 */
internal object UpdateApiUrls {
    private val validRepository = Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""")

    fun forChannel(
        repository: String,
        nightly: Boolean,
    ): String {
        require(validRepository.matches(repository)) { "Invalid GitHub update repository" }
        val path = if (nightly) "releases/tags/nightly" else "releases/latest"
        return "https://api.github.com/repos/$repository/$path"
    }
}

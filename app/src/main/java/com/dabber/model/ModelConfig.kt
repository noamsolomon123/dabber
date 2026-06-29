package com.dabber.model

/**
 * Identifies the on-device Hebrew model. The actual ggml file is downloaded on first run
 * (or sideloaded) into filesDir/models/[FILE_NAME]. [URL]/[SHA256] are filled in once the
 * benchmark's winning model is published to the GitHub release.
 */
object ModelConfig {
    const val FILE_NAME = "dabber-he.bin"
    const val LANG = "he"

    /** Download source for the model (GitHub release asset). */
    const val URL = "https://github.com/noamsolomon123/dabber/releases/download/v0.1.0/dabber-he.bin"

    /** Expected SHA-256 of the q5_0 model file (lowercase hex). */
    const val SHA256 = "6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572"

    val hasRemoteSource: Boolean get() = URL.isNotBlank() && SHA256.isNotBlank()
}

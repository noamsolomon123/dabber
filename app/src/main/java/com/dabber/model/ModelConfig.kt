package com.dabber.model

/**
 * Identifies the on-device Hebrew model. The actual ggml file is downloaded on first run
 * (or sideloaded) into filesDir/models/[FILE_NAME]. [URL]/[SHA256] are filled in once the
 * benchmark's winning model is published to the GitHub release.
 */
object ModelConfig {
    const val FILE_NAME = "dabber-he.bin"
    const val LANG = "he"

    /** Download source for the model (GitHub release asset). Empty until the release is cut. */
    const val URL = ""

    /** Expected SHA-256 of the model file (lowercase hex). Empty until the release is cut. */
    const val SHA256 = ""

    val hasRemoteSource: Boolean get() = URL.isNotBlank() && SHA256.isNotBlank()
}

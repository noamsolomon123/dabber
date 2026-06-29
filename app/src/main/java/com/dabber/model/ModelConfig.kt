package com.dabber.model

/**
 * Identifies the on-device Hebrew model. The actual ggml file is downloaded on first run
 * (or sideloaded) into filesDir/models/[FILE_NAME]. [URL]/[SHA256] are filled in once the
 * benchmark's winning model is published to the GitHub release.
 */
object ModelConfig {
    const val FILE_NAME = "dabber-he.bin"
    const val LANG = "he"

    /** Download source for the model (GitHub release asset). q8_0 = fastest CPU NEON path. */
    const val URL = "https://github.com/noamsolomon123/dabber/releases/download/v0.2.0/dabber-he-q8_0.bin"

    /** Expected SHA-256 of the q8_0 model file (lowercase hex). */
    const val SHA256 = "123a936e686b06d45b52dc1790251a1418841352ca94079ae643d57893ffc9a6"

    val hasRemoteSource: Boolean get() = URL.isNotBlank() && SHA256.isNotBlank()
}

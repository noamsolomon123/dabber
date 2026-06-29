package com.dabber.bench

/**
 * One downloadable Hebrew Whisper model quantization that can be benchmarked.
 *
 * @property id       short, stable identifier (also used as the table row label).
 * @property label    user-facing Hebrew description including the on-disk size.
 * @property url      direct download URL of the ggml `.bin` model.
 * @property sha256   expected SHA-256 digest (verified by [com.dabber.model.ModelDownloader]).
 * @property fileName destination file name inside `filesDir/models/`.
 * @property sizeMb   approximate download size in megabytes.
 */
data class Variant(
    val id: String,
    val label: String,
    val url: String,
    val sha256: String,
    val fileName: String,
    val sizeMb: Int,
)

/**
 * Registry of the three quantizations published on the `v0.2.0` GitHub release.
 *
 * Quantization trades size/speed for accuracy: `q4_0` is the smallest and fastest,
 * `q8_0` is the largest and most accurate. The benchmark lets the user measure that
 * trade-off on their own hardware.
 */
object ModelVariants {

    val ALL: List<Variant> = listOf(
        Variant(
            id = "q4_0",
            label = "q4_0 — קטן ומהיר (453MB)",
            url = "https://github.com/noamsolomon123/dabber/releases/download/v0.2.0/dabber-he-q4_0.bin",
            sha256 = "e049b13550e5d67d44de3e30354710eaebd67569699d6b91ffe3103dbdc77975",
            fileName = "dabber-he-q4_0.bin",
            sizeMb = 453,
        ),
        Variant(
            id = "q5_0",
            label = "q5_0 — בינוני (548MB)",
            url = "https://github.com/noamsolomon123/dabber/releases/download/v0.2.0/dabber-he-q5_0.bin",
            sha256 = "6c1da92e8e41dd64b8cc402eee7eb7a433d2152567e1a4d9cf181fefcc67a572",
            fileName = "dabber-he-q5_0.bin",
            sizeMb = 548,
        ),
        Variant(
            id = "q8_0",
            label = "q8_0 — הכי מדויק (834MB)",
            url = "https://github.com/noamsolomon123/dabber/releases/download/v0.2.0/dabber-he-q8_0.bin",
            sha256 = "123a936e686b06d45b52dc1790251a1418841352ca94079ae643d57893ffc9a6",
            fileName = "dabber-he-q8_0.bin",
            sizeMb = 834,
        ),
    )

    /** Returns the variant with the given [id], or `null` if none matches. */
    fun byId(id: String): Variant? = ALL.firstOrNull { it.id == id }
}

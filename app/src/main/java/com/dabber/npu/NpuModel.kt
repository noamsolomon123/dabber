package com.dabber.npu

import android.content.Context
import com.dabber.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and verifies the four Qualcomm QNN (Hexagon NPU) Whisper model files that
 * [QnnWhisperEngine] loads from `filesDir/npu-qnn/`.
 *
 * Unlike [com.dabber.model.ModelDownloader] (whose cache directory is hard-wired to
 * `filesDir/models/`), these files **must** live in `filesDir/npu-qnn/` because each tiny
 * `*.onnx` EPContext graph references its large `*_qairt_context.bin` by *relative* name, so
 * the two halves of a model have to sit side by side in that exact directory. This object is
 * therefore a small, self-contained [HttpURLConnection] + SHA-256 downloader that targets
 * `npu-qnn/` and skips any file that is already present and digest-matching.
 *
 * Total payload is ~2.1 GB; on the first run every file is streamed and verified, on later
 * runs the SHA check short-circuits the network entirely.
 *
 * **Blocking — performs network and disk I/O; call off the main thread.**
 */
object NpuModel {

    /** Subdirectory (relative to [Context.getFilesDir]) the QNN engine reads from. */
    private const val NPU_DIR = "npu-qnn"

    /** GitHub release that hosts the chip's NPU binaries. */
    private const val BASE_URL =
        "https://github.com/noamsolomon123/dabber/releases/download/v0.3.0-npu/"

    private const val PART_SUFFIX = ".part"
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_REDIRECTS = 5

    /** One downloadable model file: its release name, expected SHA-256 and approx size (MB). */
    private data class NpuFile(val name: String, val sha256: String, val sizeMb: Int)

    /** Encoder/decoder EPContext wrappers (~1 MB) + their HTP context binaries (~2.1 GB total). */
    private val FILES = listOf(
        NpuFile(
            "HfWhisperEncoder.onnx",
            "5b8259a3bf22a100d34ce25b869e5092b2073c4c7d6326efa428a8a1be9a92b9",
            1,
        ),
        NpuFile(
            "HfWhisperEncoder_qairt_context.bin",
            "24a8e3229beb14ffe746573446047c55a4720b6994bb9b5f39a76da2be17547b",
            1672,
        ),
        NpuFile(
            "HfWhisperDecoder.onnx",
            "1c8f71af2e43c6ff8b9264662d284a847bca76b7b556dcfd92780fae06c19b61",
            1,
        ),
        NpuFile(
            "HfWhisperDecoder_qairt_context.bin",
            "e12f2ccb75744bb3cd012de64ce6844d0b5367a99a3d317f25f8a32f100d14ee",
            432,
        ),
    )

    /**
     * Ensures all four NPU model files are present and verified under `filesDir/npu-qnn/`,
     * downloading any that are missing or corrupt. Each file's progress is reported via
     * [onProgress] as the localized string `"מוריד <name>… X%"`; an already-verified file is
     * reported once at `100%` and skipped without any network access.
     *
     * @return the `npu-qnn` directory (ready to hand to [QnnWhisperEngine.load]).
     * @throws IOException if any file cannot be downloaded or fails its SHA-256 check.
     */
    @Throws(IOException::class)
    fun ensureAll(context: Context, onProgress: (String) -> Unit): File {
        val dir = File(context.filesDir, NPU_DIR)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Unable to create NPU directory: ${dir.absolutePath}")
        }
        for (file in FILES) {
            ensureOne(context, dir, file, onProgress)
        }
        return dir
    }

    /** Downloads + verifies a single [file] into [dir] (skips it when already present & matching). */
    @Throws(IOException::class)
    private fun ensureOne(
        context: Context,
        dir: File,
        file: NpuFile,
        onProgress: (String) -> Unit,
    ) {
        val finalFile = File(dir, file.name)
        val expected = file.sha256.trim().lowercase()

        // Fast path: a valid, already-verified file is reused without any network access.
        if (finalFile.exists() && computeSha256(finalFile).equals(expected, ignoreCase = true)) {
            onProgress(context.getString(R.string.bench_downloading, file.name, 100))
            return
        }

        val partFile = File(dir, file.name + PART_SUFFIX)
        // Remove any stale partial download from a previous interrupted attempt.
        partFile.delete()

        try {
            val actual = download(BASE_URL + file.name, partFile) { percent ->
                onProgress(context.getString(R.string.bench_downloading, file.name, percent))
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IOException("SHA-256 mismatch for ${file.name}: expected $expected, got $actual")
            }
            // Replace any existing (stale/corrupt) final file before the atomic rename.
            finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                throw IOException("Failed to move ${file.name} into place: ${finalFile.absolutePath}")
            }
            onProgress(context.getString(R.string.bench_downloading, file.name, 100))
        } catch (t: Throwable) {
            // Never leave a partial file behind on failure.
            partFile.delete()
            throw t
        }
    }

    /**
     * Streams [url] into [destination], following redirects manually, while computing the
     * SHA-256 digest of the body and reporting integer percent (0..100) via [onProgress].
     *
     * @return the lower-case hex SHA-256 digest of the downloaded bytes.
     */
    @Throws(IOException::class)
    private fun download(url: String, destination: File, onProgress: (Int) -> Unit): String {
        val connection = openWithRedirects(url)
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected HTTP $status while downloading $url")
            }

            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastReported = -1

            onProgress(0)
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read

                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
            return digest.digest().toHex()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens an [HttpURLConnection] for [url], following HTTP 3xx redirects (including the
     * cross-protocol GitHub release -> object-store hop the platform won't follow on its own)
     * up to [MAX_REDIRECTS] hops.
     */
    @Throws(IOException::class)
    private fun openWithRedirects(url: String): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
                requestMethod = "GET"
            }
            val status = connection.responseCode
            val isRedirect = status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 ||
                status == 308
            if (isRedirect) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirect with no Location header from $current")
                }
                if (++redirects > MAX_REDIRECTS) {
                    throw IOException("Too many redirects while downloading $url")
                }
                // Resolve relative redirect targets against the current URL.
                current = URL(URL(current), location).toString()
                continue
            }
            return connection
        }
    }

    /** Computes the lower-case hex SHA-256 digest of [file]'s contents. */
    @Throws(IOException::class)
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /** Encodes this byte array as a lower-case hexadecimal string. */
    private fun ByteArray.toHex(): String {
        val hex = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            hex.append(HEX_CHARS[v ushr 4])
            hex.append(HEX_CHARS[v and 0x0F])
        }
        return hex.toString()
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}

package com.dabber.model

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and verifies on-device ASR model files.
 *
 * The downloader is intentionally dependency-free: it relies solely on
 * [HttpURLConnection] and [MessageDigest] so the app keeps a minimal footprint.
 * Files are cached under `filesDir/models/` and validated against an expected
 * SHA-256 digest so a model is downloaded at most once.
 */
object ModelDownloader {

    /** Subdirectory (relative to [Context.getFilesDir]) where models are cached. */
    private const val MODELS_DIR = "models"

    /** Suffix used for the partial (in-progress) download file. */
    private const val PART_SUFFIX = ".part"

    /** Read/write buffer size in bytes. */
    private const val BUFFER_SIZE = 64 * 1024

    /** Maximum number of HTTP redirects to follow manually. */
    private const val MAX_REDIRECTS = 5

    /**
     * Ensures the model identified by [fileName] is present and verified locally,
     * downloading it from [url] if necessary.
     *
     * The final file lives at `File(context.filesDir, "models/<fileName>")`.
     * If that file already exists and its SHA-256 digest matches [sha256]
     * (case-insensitive hex), it is returned immediately and [onProgress] is
     * invoked once with `100`.
     *
     * Otherwise the file is streamed into a temporary `<fileName>.part`, its
     * digest is computed on the fly, integer progress in the range `0..100`
     * (derived from `Content-Length`) is reported via [onProgress], the digest
     * is verified, and the temporary file is atomically renamed to the final
     * path.
     *
     * **This method blocks and performs network and disk I/O; it MUST be called
     * off the main thread** (e.g. from a coroutine on `Dispatchers.IO` or a
     * background executor).
     *
     * @param context used to resolve the app-private files directory.
     * @param url the source URL of the model (HTTP/HTTPS, redirects followed).
     * @param sha256 the expected SHA-256 digest as a hex string (case-insensitive).
     * @param fileName the destination file name within the models directory.
     * @param onProgress callback receiving download progress as an integer percent.
     * @return the verified [File] on the local filesystem.
     * @throws IOException if the download fails or the digest does not match.
     */
    @Throws(IOException::class)
    fun ensure(
        context: Context,
        url: String,
        sha256: String,
        fileName: String,
        onProgress: (Int) -> Unit,
    ): File {
        val modelsDir = File(context.filesDir, MODELS_DIR)
        val finalFile = File(modelsDir, fileName)
        val expected = sha256.trim().lowercase()

        // Fast path: a valid, already-verified file is reused without any network access.
        if (finalFile.exists() && computeSha256(finalFile).equals(expected, ignoreCase = true)) {
            onProgress(100)
            return finalFile
        }

        if (!modelsDir.exists() && !modelsDir.mkdirs() && !modelsDir.isDirectory) {
            throw IOException("Unable to create models directory: ${modelsDir.absolutePath}")
        }

        val partFile = File(modelsDir, fileName + PART_SUFFIX)
        // Remove any stale partial download from a previous interrupted attempt.
        partFile.delete()

        try {
            val actual = download(url, partFile, onProgress)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IOException("SHA-256 mismatch for $fileName: expected $expected, got $actual")
            }
            // Replace any existing (stale/corrupt) final file before the atomic rename.
            finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                throw IOException("Failed to move downloaded model into place: ${finalFile.absolutePath}")
            }
            onProgress(100)
            return finalFile
        } catch (t: Throwable) {
            // Never leave a partial file behind on failure.
            partFile.delete()
            throw t
        }
    }

    /**
     * Streams [url] into [destination], following redirects manually, while
     * computing the SHA-256 digest of the body and reporting progress.
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
     * Opens an [HttpURLConnection] for [url], following HTTP 3xx redirects
     * (including cross-protocol http<->https redirects that the platform does
     * not follow automatically) up to [MAX_REDIRECTS] hops.
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

    /**
     * Computes the lower-case hex SHA-256 digest of [file]'s contents.
     */
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

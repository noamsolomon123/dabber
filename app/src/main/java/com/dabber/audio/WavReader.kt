package com.dabber.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reader for 16-bit PCM **mono** files at 16 kHz — the format produced
 * by the benchmark harness. Used by the debug transcription hook to validate the
 * on-device engine against a known clip without needing live microphone input.
 */
object WavReader {

    fun readPcm16Mono(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size < 44) return FloatArray(0)

        // Walk RIFF chunks to find "data".
        var i = 12
        var dataOffset = -1
        var dataLen = 0
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val sz = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataOffset = i + 8
                dataLen = sz
                break
            }
            i += 8 + sz + (sz and 1) // chunks are word-aligned
        }
        if (dataOffset < 0) return FloatArray(0)

        val end = minOf(dataOffset + dataLen, bytes.size)
        val n = (end - dataOffset) / 2
        val out = FloatArray(n)
        val bb = ByteBuffer.wrap(bytes, dataOffset, end - dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) out[k] = bb.short / 32768f
        return out
    }
}

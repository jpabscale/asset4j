// Copyright (c) 2026 jpabscale — original code (compression seam for the port)
package com.github.jpabscale.asset4j.bundle

import net.jpountz.lz4.LZ4Factory
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Compression seam for UnityFS blocks. Mirrors the C# `SevenZipHelper`/`Lz4DecoderStream`
 * behavior, but using open-source JVM libraries (plan §11): LZ4 via lz4-java (raw block
 * format, matches Unity's block-compressed data) and LZMA via xz-java (LZMA-alone format
 * with 5-byte properties + 8-byte decompressed size header, matching UnityPy's
 * `decompress_lzma(..., read_decompressed_size=True)` and the C# `StreamDecompress`).
 */
object UnityCompression {
    private val lz4Factory = LZ4Factory.fastestInstance()

    /**
     * Decompress an LZMA block. UnityFS LZMA blocks carry the 5-byte LZMA properties header
     * (as written by SevenZipHelper), and — per UnityPy — an 8-byte little-endian
     * decompressed size when [readDecompressedSize] is true (the block-info section),
     * otherwise the size comes from the caller ([expectedSize]).
     */
    fun decompressLzma(compressed: ByteArray, expectedSize: Long, readDecompressedSize: Boolean): ByteArray {
        if (compressed.size < 5) throw IllegalArgumentException("input .lzma is too short")
        val props = compressed[0].toInt() and 0xFF
        val dictSize = readInt32Le(compressed, 1)
        val dataOffset = if (readDecompressedSize) 13 else 5
        val expected = if (readDecompressedSize && compressed.size >= 13) readInt64Le(compressed, 5) else expectedSize
        val inStream = ByteArrayInputStream(compressed, dataOffset, compressed.size - dataOffset)
        val out = ByteArrayOutputStream()
        LZMAInputStream(inStream, expected, props.toByte(), dictSize).use { decoder ->
            decoder.enableRelaxedEndCondition()
            val buf = ByteArray(65536)
            var n: Int
            while (decoder.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** Decompress a raw LZ4 block (Unity's block format, `lz4.block.decompress(data, size)`). */
    fun decompressLz4(compressed: ByteArray, uncompressedSize: Int): ByteArray {
        val out = ByteArray(uncompressedSize)
        lz4Factory.fastDecompressor().decompress(compressed, 0, out, 0, uncompressedSize)
        return out
    }

    /** Compress a raw LZ4 block (Unity's block format, high-compression to match LZ4HC). */
    fun compressLz4(data: ByteArray): ByteArray {
        val compressor = lz4Factory.highCompressor()
        val maxLen = compressor.maxCompressedLength(data.size)
        val compressed = ByteArray(maxLen)
        val len = compressor.compress(data, 0, data.size, compressed, 0, maxLen)
        return compressed.copyOf(len)
    }

    /** Compress a raw LZ4 block with the fast compressor (Unity's LZ4Fast, LZ4Codec.Encode32). */
    fun compressLz4Fast(data: ByteArray): ByteArray {
        val compressor = lz4Factory.fastCompressor()
        val maxLen = compressor.maxCompressedLength(data.size)
        val compressed = ByteArray(maxLen)
        val len = compressor.compress(data, 0, data.size, compressed, 0, maxLen)
        return compressed.copyOf(len)
    }

    /** Compress an LZMA stream with Unity-compatible properties; header is 5 bytes (props+dict). */
    fun compressLzma(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val opts = org.tukaani.xz.LZMA2Options(1 shl 21)
        opts.setLcLp(3, 0)
        LZMAOutputStream(baos, opts, data.size.toLong()).use { encoder ->
            encoder.write(data)
        }
        val full = baos.toByteArray()
        // xz-java writes props(1)+dict(4)+size(8)+data; Unity stores props(1)+dict(4)+data
        val out = ByteArray(full.size - 8)
        full.copyInto(out, 0, 0, 5)
        full.copyInto(out, 5, 13)
        return out
    }

    private fun readInt32Le(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            (b[off + 3].toInt() shl 24)
    }

    private fun readInt64Le(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFFL) shl (8 * i))
        return v
    }
}

// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/IO/AssetsFileWriter.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.io

/**
 * Growable-byte-array binary writer mirroring AssetsTools.NET's [System.IO.BinaryWriter]
 * subclass with a [BigEndian] toggle and alignment helpers.
 */
open class AssetsFileWriter {
    private var data = ByteArray(4096)
    var position: Int = 0
        set(value) {
            if (value < 0) throw IndexOutOfBoundsException("negative position $value")
            field = value
        }
    var bigEndian: Boolean = false

    fun toByteArray(): ByteArray = data.copyOf(position)

    private fun ensure(n: Int) {
        if (position + n > data.size) {
            data = data.copyOf(maxOf(data.size * 2, position + n))
        }
    }

    fun writeByte(value: Int) {
        ensure(1)
        data[position++] = value.toByte()
    }

    fun writeBoolean(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    fun writeBytes(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(data, position)
        position += bytes.size
    }

    fun writeInt16(value: Short) {
        val v = if (bigEndian) reverseShort(value.toInt() and 0xFFFF) else value.toInt() and 0xFFFF
        ensure(2)
        data[position] = (v and 0xFF).toByte()
        data[position + 1] = ((v ushr 8) and 0xFF).toByte()
        position += 2
    }

    fun writeUInt16(value: Int) {
        val v = if (bigEndian) reverseShort(value and 0xFFFF) else value and 0xFFFF
        ensure(2)
        data[position] = (v and 0xFF).toByte()
        data[position + 1] = ((v ushr 8) and 0xFF).toByte()
        position += 2
    }

    fun writeInt32(value: Int) {
        val v = if (bigEndian) reverseInt(value) else value
        ensure(4)
        data[position] = (v and 0xFF).toByte()
        data[position + 1] = ((v ushr 8) and 0xFF).toByte()
        data[position + 2] = ((v ushr 16) and 0xFF).toByte()
        data[position + 3] = ((v ushr 24) and 0xFF).toByte()
        position += 4
    }

    fun writeUInt32(value: Long) {
        writeInt32(value.toInt())
    }

    fun writeInt64(value: Long) {
        val v = if (bigEndian) reverseLong(value) else value
        ensure(8)
        for (i in 0 until 8) data[position + i] = ((v ushr (8 * i)) and 0xFF).toByte()
        position += 8
    }

    fun writeUInt64(value: Long) = writeInt64(value)

    fun writeSingle(value: Float) = writeInt32(value.toRawBits())

    fun writeDouble(value: Double) = writeInt64(value.toRawBits())

    fun writeRawString(value: String) {
        writeBytes(value.toByteArray(Charsets.UTF_8))
    }

    fun writeUInt24(value: Int) {
        ensure(3)
        if (bigEndian) {
            val b = byteArrayOf(
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
            writeBytes(b)
        } else {
            val b = byteArrayOf(
                (value and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte()
            )
            writeBytes(b)
        }
    }

    fun writeInt24(value: Int) {
        ensure(3)
        if (bigEndian) {
            val b = byteArrayOf(
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
            writeBytes(b)
        } else {
            val b = byteArrayOf(
                (value and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte()
            )
            writeBytes(b)
        }
    }

    fun reverseShort(value: Int): Int {
        return ((value and 0xFF00) shr 8) or ((value and 0x00FF) shl 8)
    }

    fun reverseInt(value: Int): Int {
        var v = value
        v = (v ushr 16) or (v shl 16)
        return ((v and 0xFF00FF00.toInt()) ushr 8) or ((v and 0x00FF00FF.toInt()) shl 8)
    }

    fun reverseLong(value: Long): Long {
        var v = value
        v = (v ushr 32) or (v shl 32)
        v = ((v and -281470681808896L) ushr 16) or ((v and 0x0000FFFF0000FFFFL) shl 16)
        return ((v and -71777214294589696L) ushr 8) or ((v and 0x00FF00FF00FF00FFL) shl 8)
    }

    fun align() {
        while (position % 4 != 0) writeByte(0x00)
    }

    fun align8() {
        while (position % 8 != 0) writeByte(0x00)
    }

    fun align16() {
        while (position % 16 != 0) writeByte(0x00)
    }

    fun writeNullTerminated(text: String) {
        writeRawString(text)
        writeByte(0x00)
    }

    fun writeCountString(text: String) {
        //@parity:on EXC-003
        if (text.toByteArray(Charsets.UTF_8).size > 0xFF)
            throw IllegalArgumentException("String is longer than 255! Use the Int32 variant instead!")
        //@parity:off EXC-003
        writeByte(text.toByteArray(Charsets.UTF_8).size)
        writeRawString(text)
    }

    fun writeCountStringInt16(text: String) {
        //@parity:on EXC-003
        if (text.toByteArray(Charsets.UTF_8).size > 0xFFFF)
            throw IllegalArgumentException("String is longer than 65535! Use the Int32 variant instead!")
        //@parity:off EXC-003
        writeUInt16(text.toByteArray(Charsets.UTF_8).size)
        writeRawString(text)
    }

    fun writeCountStringInt32(text: String) {
        writeInt32(text.toByteArray(Charsets.UTF_8).size)
        writeRawString(text)
    }

    fun writeInt32At(offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}

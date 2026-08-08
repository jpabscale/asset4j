// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/IO/AssetsFileReader.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.io

import java.io.EOFException

/**
 * ByteArray-backed binary reader mirroring AssetsTools.NET's [System.IO.BinaryReader]
 * subclass with a [BigEndian] toggle and alignment helpers.
 */
open class AssetsFileReader(protected val data: ByteArray) {
    var position: Int = 0
        set(value) {
            if (value < 0) throw IndexOutOfBoundsException("negative position $value")
            field = value
        }
    var bigEndian: Boolean = false

    val length: Int get() = data.size

    fun toByteArray(): ByteArray = data

    protected fun require(n: Int) {
        if (position + n > data.size) {
            throw EOFException("Unexpected end of stream at $position (need $n bytes)")
        }
    }

    fun readByte(): Int {
        require(1)
        return data[position++].toInt() and 0xFF
    }

    fun readBoolean(): Boolean {
        return readByte() != 0
    }

    fun readSByte(): Int {
        require(1)
        return data[position++].toInt()
    }

    fun readBytes(n: Int): ByteArray {
        if (n < 0) throw IndexOutOfBoundsException("n cannot be negative")
        if (n == 0) return ByteArray(0)
        require(n)
        val out = data.copyOfRange(position, position + n)
        position += n
        return out
    }

    fun readInt16(): Short {
        val v = readUInt16()
        return v.toShort()
    }

    fun readUInt16(): Int {
        require(2)
        val a = data[position].toInt() and 0xFF
        val b = data[position + 1].toInt() and 0xFF
        position += 2
        val v = if (bigEndian) reverseShort((b shl 8) or a) else (a or (b shl 8))
        return v
    }

    fun readInt24(): Int {
        val b = readBytes(3)
        val arr = ByteArray(4)
        b.copyInto(arr, 0)
        val v = arr.toInt32Le()
        return if (bigEndian) reverseInt(v) else v
    }

    fun readUInt24(): Long {
        val b = readBytes(3)
        val arr = ByteArray(4)
        if (bigEndian) {
            b.copyInto(arr, 1)
            val v = arr.toUInt32Le()
            return reverseInt(v.toInt()).toLong() and 0xFFFFFFFFL
        } else {
            b.copyInto(arr, 0)
            return arr.toUInt32Le()
        }
    }

    fun readInt32(): Int {
        require(4)
        val a = data[position].toInt() and 0xFF
        val b = data[position + 1].toInt() and 0xFF
        val c = data[position + 2].toInt() and 0xFF
        val d = data[position + 3].toInt() and 0xFF
        position += 4
        val v = (a or (b shl 8) or (c shl 16) or (d shl 24))
        return if (bigEndian) reverseInt(v) else v
    }

    fun readUInt32(): Long {
        return readInt32().toLong() and 0xFFFFFFFFL
    }

    fun readInt64(): Long {
        require(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((data[position + i].toLong() and 0xFFL) shl (8 * i))
        position += 8
        return if (bigEndian) reverseLong(v) else v
    }

    fun readUInt64(): Long = readInt64()

    fun readSingle(): Float = Float.fromBits(readInt32())

    fun readDouble(): Double = Double.fromBits(readInt64())

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
        val pad = 4 - (position % 4)
        if (pad != 4) position += pad
    }

    fun align8() {
        val pad = 8 - (position % 8)
        if (pad != 8) position += pad
    }

    fun align16() {
        val pad = 16 - (position % 16)
        if (pad != 16) position += pad
    }

    fun readStringLength(len: Int): String {
        return String(readBytes(len), Charsets.UTF_8)
    }

    fun readNullTerminated(): String {
        val ms = ArrayList<Byte>(16)
        var curByte: Int
        while (readByte().also { curByte = it } != 0) {
            ms.add(curByte.toByte())
        }
        return String(ms.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        fun readNullTerminatedArray(bytes: ByteArray, pos: Int): String {
            val output = StringBuilder()
            var i = pos
            var curChar: Char
            while (bytes[i].toInt().also { curChar = it.toChar() } != 0x00) {
                output.append(curChar)
                i++
            }
            return output.toString()
        }
    }

    fun readCountString(): String {
        val length = readByte()
        return readStringLength(length)
    }

    fun readCountStringInt16(): String {
        val length = readUInt16()
        return readStringLength(length)
    }

    fun readCountStringInt32(): String {
        val length = readInt32()
        return readStringLength(length)
    }

    private fun ByteArray.toUInt32Le(): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((this[i].toLong() and 0xFFL) shl (8 * i))
        return v
    }

    private fun ByteArray.toInt32Le(): Int {
        return (this[0].toInt() and 0xFF) or
            ((this[1].toInt() and 0xFF) shl 8) or
            ((this[2].toInt() and 0xFF) shl 16) or
            (this[3].toInt() shl 24)
    }
}

// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/GUID128.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.io

/**
 * 128-bit GUID as four uint32 words, read/written via the [AssetsFileReader]/[AssetsFileWriter].
 */
class GUID128 {
    var data0: Long = 0
    var data1: Long = 0
    var data2: Long = 0
    var data3: Long = 0

    val isEmpty: Boolean get() = data0 == 0L && data1 == 0L && data2 == 0L && data3 == 0L

    constructor()

    constructor(reader: AssetsFileReader) {
        read(reader)
    }

    fun read(reader: AssetsFileReader) {
        data0 = reader.readUInt32()
        data1 = reader.readUInt32()
        data2 = reader.readUInt32()
        data3 = reader.readUInt32()
    }

    fun write(writer: AssetsFileWriter) {
        writer.writeUInt32(data0)
        writer.writeUInt32(data1)
        writer.writeUInt32(data2)
        writer.writeUInt32(data3)
    }

    operator fun get(i: Int): Long {
        return when (i) {
            0 -> data0
            1 -> data1
            2 -> data2
            3 -> data3
            else -> throw IndexOutOfBoundsException()
        }
    }

    operator fun set(i: Int, value: Long) {
        when (i) {
            0 -> data0 = value
            1 -> data1 = value
            2 -> data2 = value
            3 -> data3 = value
            else -> throw IndexOutOfBoundsException()
        }
    }

    override fun toString(): String {
        val stringBuilder = StringBuilder(32)
        for (i in 3 downTo 0) {
            for (j in 7 downTo 0) {
                var cur = this[i]
                cur = cur shr (j * 4)
                cur = cur and 0xF
                stringBuilder.insert(0, HexToLiteral[cur.toInt()])
            }
        }
        return stringBuilder.toString()
    }

    companion object {
        private const val HexToLiteral = "0123456789abcdef"

        fun tryParse(str: String): GUID128? {
            val guid = GUID128()
            if (str.length != 32) {
                return null
            }
            for (i in 0 until 4) {
                var cur = 0L
                for (j in 7 downTo 0) {
                    val curHex = literalToHex(str[i * 8 + j])
                    if (curHex == 0xFFFFFFFFL) {
                        return null
                    }
                    cur = cur or (curHex shl (j * 4))
                }
                guid[i] = cur
            }
            return guid
        }

        private fun literalToHex(c: Char): Long {
            return when (c) {
                '0' -> 0L; '1' -> 1L; '2' -> 2L; '3' -> 3L; '4' -> 4L; '5' -> 5L; '6' -> 6L; '7' -> 7L
                '8' -> 8L; '9' -> 9L; 'a' -> 10L; 'b' -> 11L; 'c' -> 12L; 'd' -> 13L; 'e' -> 14L; 'f' -> 15L
                else -> 0xFFFFFFFFL
            }
        }
    }
}

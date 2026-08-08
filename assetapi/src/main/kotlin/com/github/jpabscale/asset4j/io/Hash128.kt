// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/Hash128.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.io

/**
 * 16-byte hash value used in Unity SerializedFiles. Ported struct semantics: [data]
 * holds exactly 16 bytes when valid.
 */
data class Hash128(val data: ByteArray) {
    fun isZero(): Boolean {
        if (data.size != 16)
            return false
        for (i in 0 until data.size) {
            if (data[i] != 0.toByte())
                return false
        }
        return true
    }

    override fun toString(): String {
        val hex = StringBuilder(data.size * 2)
        for (b in data) {
            hex.append("%02x".format(b.toInt() and 0xFF))
        }
        return hex.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Hash128)
            return false
        val a = other.data
        val b = data
        if (a.size != 16 || b.size != 16)
            return false
        for (i in 0 until 16 step 4) {
            if (a[i + 0] != b[i + 0] ||
                a[i + 1] != b[i + 1] ||
                a[i + 2] != b[i + 2] ||
                a[i + 3] != b[i + 3]
            ) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        if (data.size != 16)
            return 0
        val hashA = (data[0].toInt() shl 24) or (data[1].toInt() shl 16) or (data[2].toInt() shl 8) or data[3].toInt()
        val hashB = (data[4].toInt() shl 24) or (data[5].toInt() shl 16) or (data[6].toInt() shl 8) or data[7].toInt()
        val hashC = (data[8].toInt() shl 24) or (data[9].toInt() shl 16) or (data[10].toInt() shl 8) or data[11].toInt()
        val hashD = (data[12].toInt() shl 24) or (data[13].toInt() shl 16) or (data[14].toInt() shl 8) or data[15].toInt()
        return hashA xor hashB xor hashC xor hashD
    }

    companion object {
        fun tryParse(str: String): Hash128? {
            if (str.length != 32) return null
            val data = tryParseHexString(str) ?: return null
            return Hash128(data)
        }

        fun newBlankHash(): Hash128 = Hash128(ByteArray(16))
    }
}

private fun tryParseHexString(str: String): ByteArray? {
    if ((str.length % 2) != 0)
        return null
    val data = ByteArray(str.length / 2)
    var srcIdx = 0
    var dstIdx = 0
    while (srcIdx < str.length) {
        val charA = hexCharacterValue(str[srcIdx])
        val charB = hexCharacterValue(str[srcIdx + 1])
        if (charA == -1L || charB == -1L)
            return null
        data[dstIdx] = ((charA shl 4) or charB).toByte()
        srcIdx += 2
        dstIdx++
    }
    return data
}

private fun hexCharacterValue(c: Char): Long {
    return when (c) {
        '0' -> 0; '1' -> 1; '2' -> 2; '3' -> 3; '4' -> 4; '5' -> 5; '6' -> 6; '7' -> 7
        '8' -> 8; '9' -> 9; 'a' -> 10; 'b' -> 11; 'c' -> 12; 'd' -> 13; 'e' -> 14; 'f' -> 15
        else -> -1L
    }
}

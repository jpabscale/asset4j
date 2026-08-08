// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsBundleFileFormat/AssetBundleHeader.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.bundle

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter

/**
 * Bundle header. Reads big-endian per the UnityFS format.
 */
class AssetBundleHeader {
    var signature: String = ""
    var version: Long = 0
    var generationVersion: String = ""
    var engineVersion: String = ""
    var fileStreamHeader: AssetBundleFSHeader = AssetBundleFSHeader()

    fun read(reader: AssetsFileReader) {
        reader.bigEndian = true
        signature = reader.readNullTerminated()
        version = reader.readUInt32()
        generationVersion = reader.readNullTerminated()
        engineVersion = reader.readNullTerminated()
        if (signature == "UnityFS") {
            fileStreamHeader = AssetBundleFSHeader()
            fileStreamHeader.read(reader)
        } else {
            throw NotSupportedException("$signature signature not supported!")
        }
    }

    fun write(writer: AssetsFileWriter) {
        writer.bigEndian = true
        writer.writeNullTerminated(signature)
        writer.writeUInt32(version)
        writer.writeNullTerminated(generationVersion)
        writer.writeNullTerminated(engineVersion)
        if (signature == "UnityFS") {
            fileStreamHeader.write(writer)
        } else {
            throw NotSupportedException("$signature signature not supported!")
        }
    }

    fun getBundleInfoOffset(): Long {
        if (signature != "UnityFS")
            throw NotSupportedException("$signature signature not supported!")

        val flags = fileStreamHeader.flags
        val totalFileSize = fileStreamHeader.totalFileSize
        val compressedSize = fileStreamHeader.compressedSize

        if ((flags and AssetBundleFSHeaderFlags.BLOCK_AND_DIR_AT_END) != 0L) {
            if (totalFileSize == 0L)
                return -1
            return totalFileSize - compressedSize
        } else {
            var ret = (generationVersion.length + engineVersion.length + 0x1a).toLong()
            if (version >= 7) {
                if ((flags and AssetBundleFSHeaderFlags.OLD_WEB_PLUGIN_COMPATIBILITY) != 0L)
                    return ((ret + 0x0a) + 15) and 15L.inv()
                else
                    return ((ret + signature.length + 1) + 15) and 15L.inv()
            } else {
                if ((flags and AssetBundleFSHeaderFlags.OLD_WEB_PLUGIN_COMPATIBILITY) != 0L)
                    return (ret + 0x0a)
                else
                    return (ret + signature.length + 1)
            }
        }
    }

    fun getFileDataOffset(): Long {
        if (signature != "UnityFS")
            throw NotSupportedException("$signature signature not supported!")

        val flags = fileStreamHeader.flags
        val compressedSize = fileStreamHeader.compressedSize

        var ret = (generationVersion.length + engineVersion.length + 0x1a).toLong()
        if ((flags and AssetBundleFSHeaderFlags.OLD_WEB_PLUGIN_COMPATIBILITY) != 0L)
            ret += 0x0a
        else
            ret += signature.length + 1

        if (version >= 7)
            ret = (ret + 15) and 15L.inv()
        if ((flags and AssetBundleFSHeaderFlags.BLOCK_AND_DIR_AT_END) == 0L)
            ret += compressedSize
        if ((flags and AssetBundleFSHeaderFlags.BLOCK_INFO_NEED_PADDING_AT_START) != 0L)
            ret = (ret + 15) and 15L.inv()

        return ret
    }

    fun getCompressionType(): Int {
        if (signature != "UnityFS")
            throw NotSupportedException("$signature signature not supported!")

        return (fileStreamHeader.flags and AssetBundleFSHeaderFlags.COMPRESSION_MASK).toInt()
    }
}

class NotSupportedException(message: String) : Exception(message)

// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsBundleFileFormat/AssetBundleBlockAndDirInfo.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.bundle

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import com.github.jpabscale.asset4j.io.Hash128

/**
 * Block and directory info: hash, block list, and file entries.
 */
class AssetBundleBlockAndDirInfo {
    var hash: Hash128 = Hash128.newBlankHash()
    var blockInfos: Array<AssetBundleBlockInfo> = emptyArray()
    var directoryInfos: MutableList<AssetBundleDirectoryInfo> = mutableListOf()

    fun read(reader: AssetsFileReader) {
        hash = Hash128(reader.readBytes(16))

        val blockCount = reader.readInt32()
        blockInfos = Array(blockCount) {
            val b = AssetBundleBlockInfo()
            b.decompressedSize = reader.readUInt32()
            b.compressedSize = reader.readUInt32()
            b.flags = reader.readUInt16()
            b
        }

        val directoryCount = reader.readInt32()
        directoryInfos = ArrayList(directoryCount)
        for (i in 0 until directoryCount) {
            val dirInfo = AssetBundleDirectoryInfo()
            dirInfo.offset = reader.readInt64()
            dirInfo.decompressedSize = reader.readInt64()
            dirInfo.flags = reader.readUInt32()
            dirInfo.name = reader.readNullTerminated()
            directoryInfos.add(dirInfo)
        }
    }

    fun write(writer: AssetsFileWriter) {
        //@parity:on EXC-004
        if (hash.data.isEmpty()) {
            writer.writeUInt64(0)
            writer.writeUInt64(0)
        } else {
            writer.writeBytes(hash.data)
        }
        //@parity:off EXC-004

        val blockCount = blockInfos.size
        writer.writeInt32(blockCount)
        for (i in 0 until blockCount) {
            writer.writeUInt32(blockInfos[i].decompressedSize)
            writer.writeUInt32(blockInfos[i].compressedSize)
            writer.writeUInt16(blockInfos[i].flags)
        }

        val directoryCount = directoryInfos.size
        writer.writeInt32(directoryCount)
        for (i in 0 until directoryCount) {
            writer.writeInt64(directoryInfos[i].offset)
            writer.writeInt64(directoryInfos[i].decompressedSize)
            writer.writeUInt32(directoryInfos[i].flags)
            writer.writeNullTerminated(directoryInfos[i].name)
        }
    }
}

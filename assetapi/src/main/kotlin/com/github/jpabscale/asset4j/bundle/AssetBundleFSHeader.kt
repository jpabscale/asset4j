// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsBundleFileFormat/AssetBundleFSHeader.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.bundle

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter

/**
 * UnityFS file stream header (after the bundle's signature/version/engine fields).
 */
class AssetBundleFSHeader {
    var totalFileSize: Long = 0
    var compressedSize: Long = 0
    var decompressedSize: Long = 0
    var flags: Long = 0

    fun read(reader: AssetsFileReader) {
        totalFileSize = reader.readInt64()
        compressedSize = reader.readUInt32()
        decompressedSize = reader.readUInt32()
        flags = reader.readUInt32()
    }

    fun write(writer: AssetsFileWriter) {
        writer.writeInt64(totalFileSize)
        writer.writeUInt32(compressedSize)
        writer.writeUInt32(decompressedSize)
        writer.writeUInt32(flags)
    }
}

// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/AssetsFileHeader.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.serializedfile

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter

/**
 * SerializedFile header. Read big-endian; [endianness] switches the reader for the metadata.
 */
class AssetsFileHeader {
    var metadataSize: Long = 0
    var fileSize: Long = 0
    var version: Long = 0
    var dataOffset: Long = 0
    var endianness: Boolean = false

    fun read(reader: AssetsFileReader) {
        reader.bigEndian = true
        metadataSize = reader.readUInt32()
        fileSize = reader.readUInt32()
        version = reader.readUInt32()
        dataOffset = reader.readUInt32()
        endianness = reader.readBoolean()
        reader.position += 3

        if (version >= 22) {
            metadataSize = reader.readUInt32()
            fileSize = reader.readInt64()
            dataOffset = reader.readInt64()
            reader.position += 8
        }

        reader.bigEndian = endianness
    }

    fun write(writer: AssetsFileWriter) {
        writer.bigEndian = true
        if (version >= 22) {
            writer.writeInt32(0)
            writer.writeInt32(0)
            writer.writeUInt32(version)
            writer.writeInt32(0)
        } else {
            writer.writeUInt32(metadataSize)
            writer.writeUInt32(fileSize)
            writer.writeUInt32(version)
            writer.writeUInt32(dataOffset)
        }

        writer.writeBoolean(endianness)
        writer.writeBytes(ByteArray(3))

        if (version >= 22) {
            writer.writeUInt32(metadataSize)
            writer.writeInt64(fileSize)
            writer.writeInt64(dataOffset)
            writer.writeBytes(ByteArray(8))
        }

        writer.bigEndian = endianness
    }

    fun getSize(): Long {
        var size: Long = 20
        if (version >= 22)
            size += 28
        return size
    }
}

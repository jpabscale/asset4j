// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/AssetsFile.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.serializedfile

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter

/**
 * A Unity SerializedFile (`.assets` / `globalgamemanagers` / bundle-contained file).
 * Read path ports the C# `Read`/`IsAssetsFile`.
 */
class AssetsFile {
    var header: AssetsFileHeader = AssetsFileHeader()
    var metadata: AssetsFileMetadata = AssetsFileMetadata()
    var reader: AssetsFileReader = AssetsFileReader(ByteArray(0))

    fun read(reader: AssetsFileReader) {
        this.reader = reader

        header = AssetsFileHeader()
        header.read(reader)

        metadata = AssetsFileMetadata()
        metadata.read(reader, header.version)
    }

    /**
     * Returns the raw bytes of the object at [pathId] (absolute file position read).
     */
    fun getObjectData(pathId: Long): ByteArray {
        val info = metadata.getAssetInfo(pathId) ?: return ByteArray(0)
        reader.position = info.getAbsoluteByteOffset(header.dataOffset).toInt()
        return reader.readBytes(info.byteSize.toInt())
    }

    /**
     * Write the file (port of the C# `Write`). Objects are sorted by PathId, the metadata is
     * written after the header, padded to 0x1000 (or 16-aligned past it), and the header is
     * rewritten with corrected sizes/offsets.
     */
    fun write(writer: AssetsFileWriter, filePos: Long = 0) {
        var writeStart = filePos
        if (filePos == -1L)
            writeStart = writer.position.toLong()
        else
            writer.position = filePos.toInt()

        header.write(writer)

        val newAssetInfos = mutableListOf<AssetFileInfo>()

        val infoCount = metadata.assetInfos.size
        for (i in 0 until infoCount) {
            val assetInfo = metadata.assetInfos[i]
            val replacerType = assetInfo.replacer?.replacerType ?: ContentReplacerType.None

            if (replacerType == ContentReplacerType.Remove)
                continue

            if (replacerType == ContentReplacerType.AddOrModify) {
                if (assetInfo.replacer == null) {
                    throw Exception("replacer must be non-null when status is Modified!")
                }
            }

            val newInfo = AssetFileInfo()
            newInfo.pathId = assetInfo.pathId
            newInfo.byteOffset = assetInfo.byteOffset
            newInfo.byteSize = assetInfo.byteSize
            newInfo.typeIdOrIndex = assetInfo.typeIdOrIndex
            newInfo.oldTypeId = assetInfo.oldTypeId
            newInfo.scriptTypeIndex = assetInfo.scriptTypeIndex
            newInfo.stripped = assetInfo.stripped
            newInfo.replacer = assetInfo.replacer
            newAssetInfos.add(newInfo)
        }

        newAssetInfos.sortBy { it.pathId }

        val newMetadata = AssetsFileMetadata()
        newMetadata.unityVersion = metadata.unityVersion
        newMetadata.targetPlatform = metadata.targetPlatform
        newMetadata.typeTreeEnabled = metadata.typeTreeEnabled
        newMetadata.typeTreeTypes = metadata.typeTreeTypes
        newMetadata.assetInfos = newAssetInfos
        newMetadata.scriptTypes = metadata.scriptTypes
        newMetadata.externals = metadata.externals
        newMetadata.refTypes = metadata.refTypes
        newMetadata.userInformation = metadata.userInformation

        val newMetadataStart = writer.position
        newMetadata.write(writer, header.version)
        val newMetadataSize = writer.position - newMetadataStart

        if (writer.position < 0x1000) {
            while (writer.position < 0x1000) {
                writer.writeByte(0x00)
            }
        } else {
            if (writer.position % 16 == 0)
                writer.position += 16
            else
                writer.align16()
        }

        val newFirstFileOffset = writer.position

        for (i in newAssetInfos.indices) {
            val assetInfo = newAssetInfos[i]
            val startPosition = writer.position
            val newByteStart = (startPosition - newFirstFileOffset).toLong()

            val replacerType = assetInfo.replacer?.replacerType ?: ContentReplacerType.None
            if (replacerType == ContentReplacerType.AddOrModify) {
                assetInfo.replacer!!.write(writer)
            } else {
                reader.position = assetInfo.getAbsoluteByteOffset(header.dataOffset).toInt()
                writer.writeBytes(reader.readBytes(assetInfo.byteSize.toInt()))
            }

            assetInfo.byteOffset = newByteStart
            assetInfo.byteSize = (writer.position - startPosition).toLong()

            if (i != newAssetInfos.size - 1)
                writer.align8()
        }

        val newFileSize = writer.position - writeStart

        val newHeader = AssetsFileHeader()
        newHeader.metadataSize = newMetadataSize.toLong()
        newHeader.fileSize = newFileSize
        newHeader.version = header.version
        newHeader.dataOffset = newFirstFileOffset.toLong()
        newHeader.endianness = header.endianness

        writer.position = writeStart.toInt()
        newHeader.write(writer)

        writer.position = newMetadataStart
        newMetadata.write(writer, header.version)

        writer.position = (writeStart + newFileSize).toInt()
    }

    companion object {
        fun isAssetsFile(reader: AssetsFileReader, offset: Long, length: Long): Boolean {
            reader.bigEndian = true

            if (length < 0x30)
                return false

            reader.position = offset.toInt()
            val possibleBundleHeader = reader.readStringLength(5)
            if (possibleBundleHeader == "Unity")
                return false

            reader.position = (offset + 0x08).toInt()
            val possibleFormat = reader.readInt32()
            if (possibleFormat > 99)
                return false

            reader.position = (offset + 0x14).toInt()

            if (possibleFormat >= 0x16) {
                reader.position += 0x1c
            }

            var possibleVersion = ""
            while (reader.position < length) {
                val curChar = reader.readByte().toChar()
                if (curChar == '\u0000') break
                possibleVersion += curChar
                if (possibleVersion.length > 0xFF) {
                    return false
                }
            }

            val emptyVersion = possibleVersion.replace(Regex("[a-zA-Z0-9.\\n-]"), "")
            val fullVersion = possibleVersion.replace(Regex("[^a-zA-Z0-9.\\n-]"), "")
            return emptyVersion == "" && fullVersion.isNotEmpty()
        }
    }
}

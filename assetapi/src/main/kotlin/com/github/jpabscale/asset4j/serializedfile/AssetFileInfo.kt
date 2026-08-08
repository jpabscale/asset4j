// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/AssetFileInfo.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.serializedfile

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import com.github.jpabscale.asset4j.typetree.TypeTreeType

/**
 * Metadata for one object in the file (path id, byte offset/size, type index).
 */
class AssetFileInfo {
    var pathId: Long = 0
    var byteOffset: Long = 0
    var byteSize: Long = 0
    var typeIdOrIndex: Int = 0
    var oldTypeId: Int = 0
    var scriptTypeIndex: Int = 0
    var stripped: Int = 0
    var typeId: Int = 0
    var replacer: ContentReplacer? = null

    fun read(reader: AssetsFileReader, version: Long) {
        reader.align()
        if (version >= 14) {
            pathId = reader.readInt64()
        } else {
            pathId = reader.readUInt32()
        }
        if (version >= 22) {
            byteOffset = reader.readInt64()
        } else {
            byteOffset = reader.readUInt32()
        }
        byteSize = reader.readUInt32()
        typeIdOrIndex = reader.readInt32()
        if (version <= 15) {
            oldTypeId = reader.readUInt16()
        }
        if (version <= 16) {
            scriptTypeIndex = reader.readUInt16()
        }
        if (15 <= version && version <= 16) {
            stripped = reader.readByte()
        }
        //@parity:on EXC-006
        //@parity:off EXC-006
    }

    fun write(writer: AssetsFileWriter, version: Long) {
        writer.align()
        if (version >= 14) {
            writer.writeInt64(pathId)
        } else {
            writer.writeUInt32(pathId)
        }
        if (version >= 22) {
            writer.writeInt64(byteOffset)
        } else {
            writer.writeUInt32(byteOffset)
        }
        writer.writeUInt32(byteSize)
        writer.writeInt32(typeIdOrIndex)
        if (version <= 15) {
            writer.writeUInt16(oldTypeId)
        }
        if (version <= 16) {
            writer.writeUInt16(scriptTypeIndex)
        }
        if (15 <= version && version <= 16) {
            writer.writeByte(stripped)
        }
    }

    fun getTypeId(typeTreeTypes: List<TypeTreeType>, version: Long): Int {
        if (version < 16) {
            return typeIdOrIndex
        } else {
            if (typeIdOrIndex >= typeTreeTypes.size) {
                throw IndexOutOfBoundsException("TypeIndex is larger than type tree count!")
            }
            return typeTreeTypes[typeIdOrIndex].typeId
        }
    }

    fun getScriptIndex(typeTreeTypes: List<TypeTreeType>, version: Long): Int {
        if (version < 16) {
            return scriptTypeIndex
        } else {
            if (typeIdOrIndex >= typeTreeTypes.size) {
                throw IndexOutOfBoundsException("TypeIndex is larger than type tree count!")
            }
            return typeTreeTypes[typeIdOrIndex].scriptTypeIndex
        }
    }

    fun getAbsoluteByteOffset(dataOffset: Long): Long {
        return dataOffset + byteOffset
    }

    companion object {
        fun getSize(version: Long): Long {
            var size: Long = 0
            if (version >= 14)
                size += 8
            else
                size += 4

            if (version >= 22)
                size += 8
            else
                size += 4

            size += 4
            size += 4
            if (version <= 15)
                size += 2
            if (version <= 16)
                size += 2
            if (15 <= version && version <= 16)
                size += 1

            size = (size + 3) and 3.inv()
            return size
        }
    }
}

// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/TypeTreeNode.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.typetree

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import java.nio.charset.StandardCharsets

/**
 * A node in a Unity type tree. [typeStrOffset] is an offset into the type's string table, or
 * into the common string table when the 0x80000000 flag bit is set.
 */
class TypeTreeNode {
    var version: Int = 0
    var level: Int = 0
    var typeFlags: Int = 0
    var typeStrOffset: Long = 0
    var nameStrOffset: Long = 0
    var byteSize: Int = 0
    var index: Long = 0
    var metaFlags: Long = 0
    var refTypeHash: Long = 0

    fun read(reader: AssetsFileReader, version: Long) {
        this.version = reader.readUInt16()
        level = reader.readByte()
        typeFlags = reader.readByte()
        typeStrOffset = reader.readUInt32()
        nameStrOffset = reader.readUInt32()
        byteSize = reader.readInt32()
        index = reader.readUInt32()
        metaFlags = reader.readUInt32()
        if (version >= 18) {
            refTypeHash = reader.readUInt64()
        }
    }

    fun write(writer: AssetsFileWriter, version: Long) {
        writer.writeUInt16(this.version)
        writer.writeByte(level)
        writer.writeByte(typeFlags)
        writer.writeUInt32(typeStrOffset)
        writer.writeUInt32(nameStrOffset)
        writer.writeInt32(byteSize)
        writer.writeUInt32(index)
        writer.writeUInt32(metaFlags)
        if (version >= 18) {
            writer.writeUInt64(refTypeHash)
        }
    }

    fun getTypeString(stringTable: ByteArray, commonStringTable: ByteArray = CommonString.TABLE): String {
        return readStringTableString(stringTable, commonStringTable, typeStrOffset)
    }

    fun getNameString(stringTable: ByteArray, commonStringTable: ByteArray = CommonString.TABLE): String {
        return readStringTableString(stringTable, commonStringTable, nameStrOffset)
    }

    private fun readStringTableString(stringTable: ByteArray, commonStringTable: ByteArray, offset: Long): String {
        var off = offset
        var table = stringTable
        if ((off and 0x80000000L) != 0L) {
            off = off and 0x7FFFFFFFL
            table = commonStringTable
        }
        var endIdx = off.toInt()
        //@parity:on EXC-002
        while (endIdx < table.size && table[endIdx] != 0.toByte()) {
            endIdx++
        }
        //@parity:off EXC-002
        return String(table, off.toInt(), endIdx - off.toInt(), StandardCharsets.UTF_8)
    }

    companion object {
        fun getSize(version: Long): Long {
            var size: Long = 24
            if (version >= 0x12)
                size += 8
            return size
        }
    }
}

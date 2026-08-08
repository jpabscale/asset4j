// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/TypeTreeBlob.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.typetree

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter

/**
 * The external type tree blob (`tthm`). Only reachable from `ttmapgen` (ttmap generation) —
 * never from `AssetService` at runtime (plan §2.7, §4.1).
 */
class TypeTreeBlob {
    var nodes: MutableList<TypeTreeNode> = mutableListOf()
    var stringBufferBytes: ByteArray = ByteArray(0)

    fun read(reader: AssetsFileReader, version: Long) {
        var v = version
        if (v >= 23 || v == 0xFFFFFFFFL) {
            val extTypeTreeMagic = reader.readUInt32()
            if (extTypeTreeMagic != TYPE_TREE_HEADER_MAGIC) {
                throw Exception("Expected tthm in extended type tree type")
            }
            val extTypeTreeVer = reader.readUInt32()
            if (v != 0xFFFFFFFFL) {
                if (extTypeTreeVer != v) {
                    throw Exception("Expected version $v in extended type tree type, found $extTypeTreeVer")
                }
            } else {
                v = extTypeTreeVer
            }
        }

        val typeTreeNodeCount = reader.readInt32()
        val stringBufferLen = reader.readInt32()

        nodes = ArrayList(typeTreeNodeCount)
        for (i in 0 until typeTreeNodeCount) {
            val typeField = TypeTreeNode()
            typeField.read(reader, v)
            nodes.add(typeField)
        }

        stringBufferBytes = reader.readBytes(stringBufferLen)
    }

    fun write(writer: AssetsFileWriter, version: Long) {
        if (version >= 23) {
            writer.writeUInt32(TYPE_TREE_HEADER_MAGIC)
            writer.writeUInt32(version)
        }

        writer.writeInt32(nodes.size)
        writer.writeInt32(stringBufferBytes.size)

        for (i in 0 until nodes.size) {
            nodes[i].write(writer, version)
        }

        writer.writeBytes(stringBufferBytes)
    }

    companion object {
        const val TYPE_TREE_HEADER_MAGIC = 0x7474686dL
    }
}

// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/TypeTreeType.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.typetree

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import com.github.jpabscale.asset4j.io.Hash128
import com.github.jpabscale.asset4j.serializedfile.AssetTypeReference

/**
 * A type entry in the SerializedFile metadata. Carries the type tree (definition) or an
 * external reference to a ttmap entry (format >= 23, [typeBlobIsDefinition] == false).
 */
class TypeTreeType {
    var typeId: Int = 0
    var isStrippedType: Boolean = false
    var scriptTypeIndex: Int = 0
    var scriptIdHash: Hash128 = Hash128.newBlankHash()
    var typeHash: Hash128 = Hash128.newBlankHash()
    var extTypeHash: Hash128 = Hash128.newBlankHash()
    var typeBlobIsDefinition: Boolean = false
    var typeBlob: TypeTreeBlob = TypeTreeBlob()
    var isRefType: Boolean = false
    var typeDependencies: IntArray = IntArray(0)
    var typeReference: AssetTypeReference? = null

    var nodes: MutableList<TypeTreeNode>
        get() = typeBlob.nodes
        set(value) { typeBlob.nodes = value }

    var stringBufferBytes: ByteArray
        get() = typeBlob.stringBufferBytes
        set(value) { typeBlob.stringBufferBytes = value }

    var stringBuffer: String?
        get() = String(typeBlob.stringBufferBytes, Charsets.UTF_8)
        set(value) { typeBlob.stringBufferBytes = value!!.toByteArray(Charsets.UTF_8) }
    fun read(reader: AssetsFileReader, version: Long, hasTypeTree: Boolean, isRefType: Boolean) {
        typeId = reader.readInt32()
        if (version >= 16) {
            isStrippedType = reader.readBoolean()
        }

        if (version >= 17) {
            scriptTypeIndex = reader.readUInt16()
        } else {
            scriptTypeIndex = 0xffff
        }

        if ((version < 17 && typeId < 0) ||
            (version >= 17 && typeId == AssetClassID.MonoBehaviour.id) ||
            (isRefType && scriptTypeIndex != 0xffff)
        ) {
            scriptIdHash = Hash128(reader.readBytes(16))
        }

        typeHash = Hash128(reader.readBytes(16))
        this.isRefType = isRefType

        if (hasTypeTree) {
            var typeTreeSize: Int
            var shouldReadNodes: Boolean
            if (version >= 23) {
                extTypeHash = Hash128(reader.readBytes(16))
                typeTreeSize = reader.readInt32()

                typeBlobIsDefinition = typeTreeSize != 0
                shouldReadNodes = typeBlobIsDefinition
            } else {
                extTypeHash = Hash128.newBlankHash()
                typeBlobIsDefinition = true
                typeTreeSize = 0
                shouldReadNodes = true
            }

            if (shouldReadNodes) {
                typeBlob = TypeTreeBlob()
                typeBlob.read(reader, version)
            } else {
                typeBlob = TypeTreeBlob()
                typeBlob.nodes = mutableListOf()
                typeBlob.stringBufferBytes = ByteArray(0)
            }

            if (version >= 21) {
                if (!isRefType) {
                    val dependenciesCount = reader.readInt32()
                    typeDependencies = IntArray(dependenciesCount)
                    for (i in 0 until dependenciesCount) {
                        typeDependencies[i] = reader.readInt32()
                    }
                } else {
                    typeReference = AssetTypeReference()
                    typeReference!!.readMetadata(reader)
                }
            }
        }
    }

    fun write(writer: AssetsFileWriter, version: Long, hasTypeTree: Boolean) {
        writer.writeInt32(typeId)
        if (version >= 16)
            writer.writeBoolean(isStrippedType)

        if (version >= 17)
            writer.writeUInt16(scriptTypeIndex)

        if ((version < 17 && typeId < 0) ||
            (version >= 17 && typeId == AssetClassID.MonoBehaviour.id) ||
            (isRefType && scriptTypeIndex != 0xffff)
        ) {
            writer.writeBytes(scriptIdHash.data)
        }

        writer.writeBytes(typeHash.data)

        if (hasTypeTree) {
            var shouldWriteNodes: Boolean
            var typeTreeDataStartPos = 0
            if (version >= 23) {
                writer.writeBytes(extTypeHash.data)

                writer.writeInt32(0)
                typeTreeDataStartPos = writer.position

                shouldWriteNodes = typeBlobIsDefinition
            } else {
                shouldWriteNodes = true
            }

            if (shouldWriteNodes) {
                typeBlob.write(writer, version)

                if (version >= 23) {
                    val curPos = writer.position
                    val typeTreeDataLen = (writer.position - typeTreeDataStartPos).toInt()

                    writer.position = (typeTreeDataStartPos - 4).toInt()
                    writer.writeInt32(typeTreeDataLen)
                    writer.position = curPos.toInt()
                }
            }

            if (version >= 21) {
                if (!isRefType) {
                    writer.writeInt32(typeDependencies.size)
                    for (i in 0 until typeDependencies.size) {
                        writer.writeInt32(typeDependencies[i])
                    }
                } else {
                    typeReference!!.writeMetadata(writer)
                }
            }
        }
    }

    fun getSize(version: Long, hasTypeTree: Boolean): Long {
        var size: Long = 0
        size += 4
        if (version >= 16)
            size += 1

        if (version >= 17)
            size += 2

        if ((version < 17 && typeId < 0) ||
            (version >= 17 && typeId == AssetClassID.MonoBehaviour.id) ||
            (isRefType && scriptTypeIndex != 0xffff)
        ) {
            size += 16
        }

        size += 16

        if (hasTypeTree) {
            size += 4
            size += 4
            size += TypeTreeNode.getSize(version) * nodes.size
            size += stringBufferBytes.size
            if (version >= 21) {
                if (!isRefType) {
                    size += 4
                    size += typeDependencies.size * 4
                } else {
                    size += typeReference!!.className.length + 1
                    size += typeReference!!.nameSpace.length + 1
                    size += typeReference!!.asmName.length + 1
                }
            }
        }

        return size
    }
}

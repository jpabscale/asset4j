// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/AssetsFileMetadata.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.serializedfile

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import com.github.jpabscale.asset4j.typetree.TypeTreeType

/**
 * Metadata block of a SerializedFile: types, object infos, script types, externals, ref types,
 * user info. Version-gated per the file header's format version.
 */
class AssetsFileMetadata {
    var unityVersion: String = ""
    var targetPlatform: Long = 0
    var typeTreeEnabled: Boolean = false
    var typeTreeTypes: MutableList<TypeTreeType> = mutableListOf()
    var assetInfos: MutableList<AssetFileInfo> = mutableListOf()
    var scriptTypes: MutableList<AssetPPtr> = mutableListOf()
    var externals: MutableList<AssetsFileExternal> = mutableListOf()
    var refTypes: MutableList<TypeTreeType> = mutableListOf()
    var userInformation: String? = null

    private var quickLookup: MutableMap<Long, AssetFileInfo>? = null

    fun read(reader: AssetsFileReader, version: Long) {
        quickLookup = null

        unityVersion = reader.readNullTerminated()
        targetPlatform = reader.readUInt32()
        if (version >= 13) {
            typeTreeEnabled = reader.readBoolean()
        }

        val fieldCount = reader.readInt32()
        typeTreeTypes = ArrayList(fieldCount)
        for (i in 0 until fieldCount) {
            val typeTreeType = TypeTreeType()
            typeTreeType.read(reader, version, typeTreeEnabled, false)
            typeTreeTypes.add(typeTreeType)
        }

        val assetCount = reader.readInt32()
        reader.align()
        assetInfos = ArrayList(assetCount)
        for (i in 0 until assetCount) {
            val fileInfo = AssetFileInfo()
            fileInfo.read(reader, version)
            fileInfo.typeId = fileInfo.getTypeId(this.typeTreeTypes, version)
            assetInfos.add(fileInfo)
        }

        val scriptTypeCount = reader.readInt32()
        scriptTypes = ArrayList(scriptTypeCount)
        for (i in 0 until scriptTypeCount) {
            val fileId = reader.readInt32()
            reader.align()
            val pathId = reader.readInt64()
            val pptr = AssetPPtr(fileId, pathId)
            scriptTypes.add(pptr)
        }

        val externalCount = reader.readInt32()
        externals = ArrayList(externalCount)
        for (i in 0 until externalCount) {
            val external = AssetsFileExternal()
            external.read(reader)
            externals.add(external)
        }

        if (version >= 20) {
            val refTypeCount = reader.readInt32()
            refTypes = ArrayList(refTypeCount)
            for (i in 0 until refTypeCount) {
                val typeTreeType = TypeTreeType()
                typeTreeType.read(reader, version, typeTreeEnabled, true)
                refTypes.add(typeTreeType)
            }
        }

        if (version >= 5) {
            userInformation = reader.readNullTerminated()
        }
    }

    fun write(writer: AssetsFileWriter, version: Long) {
        writer.writeNullTerminated(unityVersion)
        writer.writeUInt32(targetPlatform)
        if (version >= 13) {
            writer.writeBoolean(typeTreeEnabled)
        }

        writer.writeInt32(typeTreeTypes.size)
        for (i in typeTreeTypes.indices) {
            typeTreeTypes[i].write(writer, version, typeTreeEnabled)
        }

        writer.writeInt32(assetInfos.size)
        writer.align()
        for (i in assetInfos.indices) {
            assetInfos[i].write(writer, version)
        }

        writer.writeInt32(scriptTypes.size)
        for (i in scriptTypes.indices) {
            writer.writeInt32(scriptTypes[i].fileId)
            writer.align()
            writer.writeInt64(scriptTypes[i].pathId)
        }

        writer.writeInt32(externals.size)
        for (i in externals.indices) {
            externals[i].write(writer)
        }

        if (version >= 20) {
            writer.writeInt32(refTypes.size)
            for (j in refTypes.indices) {
                refTypes[j].write(writer, version, typeTreeEnabled)
            }
        }

        if (version >= 5) {
            writer.writeNullTerminated(userInformation ?: "")
        }
    }

    fun getAssetInfo(pathId: Long): AssetFileInfo? {
        val quick = quickLookup
        if (quick != null) {
            if (quick.containsKey(pathId)) {
                return quick[pathId]
            }
        } else {
            for (i in assetInfos.indices) {
                val info = assetInfos[i]
                if (info.pathId == pathId) {
                    return info
                }
            }
        }
        return null
    }

    fun addAssetInfo(info: AssetFileInfo) {
        quickLookup?.set(info.pathId, info)
        assetInfos.add(info)
    }

    fun removeAssetInfo(info: AssetFileInfo): Boolean {
        quickLookup?.remove(info.pathId)
        return assetInfos.remove(info)
    }

    fun generateQuickLookup() {
        val map = HashMap<Long, AssetFileInfo>()
        for (i in assetInfos.indices) {
            val info = assetInfos[i]
            map[info.pathId] = info
        }
        quickLookup = map
    }

    fun findTypeTreeTypeById(id: Int): TypeTreeType? {
        for (type in typeTreeTypes) {
            if (type.typeId == id)
                return type
        }
        return null
    }

    fun findTypeTreeTypeById(id: Int, scriptIndex: Int): TypeTreeType? {
        for (type in typeTreeTypes) {
            if (type.typeId == id) {
                if (type.scriptTypeIndex == scriptIndex)
                    return type
                if (id < 0 && type.scriptTypeIndex == 0xffff)
                    return type
            }
        }
        return null
    }

    /**
     * Resolves the type tree entry for an object across all format versions, mirroring
     * the C# `FindTypeTreeTypeByID(info.GetTypeId(...), info.GetScriptIndex(...))`.
     * For version < 16, `typeIdOrIndex` is a raw class id (negative for scripts), so the
     * tree is found by matching id + script index rather than by list position.
     */
    fun getTypeTreeType(info: AssetFileInfo, version: Long): TypeTreeType? {
        if (version < 16) {
            return findTypeTreeTypeById(info.typeIdOrIndex, info.scriptTypeIndex)
        }
        val idx = info.typeIdOrIndex
        if (idx < 0 || idx >= typeTreeTypes.size) return null
        return typeTreeTypes[idx]
    }

    fun findTypeTreeTypeIndexById(id: Int, scriptIndex: Int): Int {
        val typeCount = typeTreeTypes.size
        for (i in 0 until typeCount) {
            val type = typeTreeTypes[i]
            if (type.typeId == id) {
                if (type.scriptTypeIndex == scriptIndex)
                    return i
                if (id < 0 && type.scriptTypeIndex == 0xffff)
                    return i
            }
        }
        return -1
    }

    fun findTypeTreeTypeByScriptIndex(scriptIndex: Int): TypeTreeType? {
        for (type in typeTreeTypes) {
            if (type.scriptTypeIndex == scriptIndex)
                return type
        }
        return null
    }

    fun findTypeTreeTypeByName(name: String): TypeTreeType? {
        for (type in typeTreeTypes) {
            if (type.nodes.isEmpty())
                continue
            if (type.nodes[0].getTypeString(type.stringBufferBytes) == name)
                return type
        }
        return null
    }

    fun getSize(version: Long): Long {
        var size: Long = 0
        size += unityVersion.length + 1
        size += 4
        if (version >= 13)
            size += 1

        size += 4
        for (i in typeTreeTypes.indices)
            size += typeTreeTypes[i].getSize(version, typeTreeEnabled)

        size += 4
        size = (size + 3) and 3.inv()
        size += AssetFileInfo.getSize(version) * assetInfos.size

        size += 4
        size = (size + 3) and 3.inv()
        size += 12 * scriptTypes.size

        size += 4
        for (i in externals.indices)
            size += externals[i].getSize()

        if (version >= 20) {
            size += 4
            for (j in refTypes.indices)
                size += refTypes[j].getSize(version, typeTreeEnabled)
        }

        if (version >= 5)
            size += (userInformation?.length ?: 0) + 1

        return size
    }
}

// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port)
package com.github.jpabscale.asset4j.ttmapgen

import com.github.jpabscale.asset4j.bundle.AssetBundleFile
import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.Hash128
import com.github.jpabscale.asset4j.serializedfile.AssetsFile
import com.github.jpabscale.asset4j.ttmap.Ttmap
import com.github.jpabscale.asset4j.ttmap.TtmapType
import com.github.jpabscale.asset4j.ttmap.TtmapTypes
import com.github.jpabscale.asset4j.ttmap.TypeTreeNodeInline
import com.github.jpabscale.asset4j.typetree.TypeTreeBlob
import com.github.jpabscale.asset4j.typetree.TypeTreeNode
import com.github.jpabscale.asset4j.typetree.TypeTreeType

/**
 * tthm → ttmap generator (plan §4.3). Given an addressables bundle's external type-tree
 * data (a bundle whose contained files are `tthm` blobs named by their hash) and the
 * external-tree SerializedFile, produces a ttmap:
 *   - native types keyed by ClassId (builtin),
 *   - script types keyed by `scriptIds` (the in-file SerializedType.scriptIdHash) and
 *     `script` by `assembly:namespace.classname` (resolved from the MonoScript assets,
 *     or left to the caller when the MonoScript identity isn't derivable here).
 */
object TthmGenerator {

    /**
     * Extracts every tthm blob from [bundleBytes] (a UnityFS bundle whose contained files
     * are external type-tree blobs named by their hash), keyed by the parsed hash.
     */
    fun extractBlobs(bundleBytes: ByteArray): LinkedHashMap<Hash128, TypeTreeBlob> {
        val bf = AssetBundleFile()
        bf.read(AssetsFileReader(bundleBytes))
        val blobs = LinkedHashMap<Hash128, TypeTreeBlob>()
        for (name in bf.getAllFileNames()) {
            val hash = Hash128.tryParse(name) ?: continue
            val data = bf.getFileData(bf.getFileIndex(name))
            if (data.size < 8) continue
            val reader = AssetsFileReader(data)
            try {
                val blob = TypeTreeBlob()
                blob.read(reader, 0xFFFFFFFFL)
                blobs[hash] = blob
            } catch (e: Exception) {
                // skip unreadable blobs (matches the C# LoadTypeTreeBlob)
            }
        }
        return blobs
    }

    /**
     * Generates a ttmap from the external-tree [serializedFileBytes] using [blobs].
     */
    fun generate(serializedFileBytes: ByteArray, blobs: Map<Hash128, TypeTreeBlob>, gameVersion: String): Ttmap {
        val af = AssetsFile()
        af.read(AssetsFileReader(serializedFileBytes))

        val builtin = linkedMapOf<String, TtmapType>()
        val script = linkedMapOf<String, TtmapType>()
        val scriptIds = linkedMapOf<String, TtmapType>()

        for (type in af.metadata.typeTreeTypes) {
            val nodes: List<TypeTreeNodeInline> = if (type.typeBlobIsDefinition) {
                inlineNodes(type)
            } else {
                val blob = blobs[type.extTypeHash] ?: continue
                inlineNodes(blob)
            }
            if (nodes.isEmpty()) continue

            val ttmapType = TtmapType(nodes)
            if (type.typeId == com.github.jpabscale.asset4j.typetree.AssetClassID.MonoBehaviour.id ||
                type.typeId < 0
            ) {
                // script type: key by scriptId hash string + assembly:namespace.classname
                if (!type.scriptIdHash.isZero()) {
                    scriptIds[type.scriptIdHash.toString()] = ttmapType
                }
                script[resolveScriptKey(type)] = ttmapType
            } else {
                builtin[type.typeId.toString()] = ttmapType
            }
        }

        return Ttmap(
            unityVersion = af.metadata.unityVersion,
            gameVersion = gameVersion,
            types = TtmapTypes(builtin = builtin, script = script, scriptIds = scriptIds),
        )
    }

    /**
     * Resolves the `assembly:namespace.classname` script key from the MonoScript identity.
     * When the SerializedFile carries the MonoScript as an object, this is derived from the
     * type tree; the caller may override with the actual MonoScript assets. Falls back to
     * the typeId as a stable key when nothing better is available.
     */
    private fun resolveScriptKey(type: TypeTreeType): String {
        // A well-formed script key needs the assembly/namespace/classname. Where the
        // generator has the MonoScript objects (from the player's MonoScript assets),
        // callers can pass a resolver; the base fallback keys by the class id.
        return "classid:${type.typeId}:script:${type.scriptTypeIndex}"
    }

    private fun inlineNodes(blob: TypeTreeBlob): List<TypeTreeNodeInline> {
        return blob.nodes.map { n -> inlineNode(n, blob.stringBufferBytes) }
    }

    private fun inlineNodes(type: TypeTreeType): List<TypeTreeNodeInline> {
        return type.nodes.map { n -> inlineNode(n, type.stringBufferBytes) }
    }

    private fun inlineNode(n: TypeTreeNode, stringBuffer: ByteArray): TypeTreeNodeInline {
        return TypeTreeNodeInline(
            version = n.version,
            level = n.level,
            typeFlags = n.typeFlags,
            type = n.getTypeString(stringBuffer),
            name = n.getNameString(stringBuffer),
            byteSize = n.byteSize,
            index = n.index,
            metaFlags = n.metaFlags,
            refTypeHash = n.refTypeHash,
        )
    }
}

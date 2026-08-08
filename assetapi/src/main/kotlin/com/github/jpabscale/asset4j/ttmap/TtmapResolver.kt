// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port)
package com.github.jpabscale.asset4j.ttmap

import com.github.jpabscale.asset4j.serializedfile.AssetsFileMetadata
import com.github.jpabscale.asset4j.typetree.TypeTreeBlob
import com.github.jpabscale.asset4j.typetree.TypeTreeNode
import com.github.jpabscale.asset4j.typetree.TypeTreeType
/**
 * Resolves external type trees (format >= 23, `TypeBlobIsDefinition == false`) from a
 * [Ttmap], mirroring the dual-key lookup: builtin by ClassId, then MonoBehaviour by
 * `scriptIds` (Hash128 string) falling back to `script` by name (plan §2.7).
 */
class TtmapResolver(
    private val ttmap: Ttmap?,
    /**
     * Resolves the MonoScript identity for a MonoBehaviour [TypeTreeType] (whose own
     * ScriptId hash is not enough for IL2CPP name-only ttmaps). Receives the type's
     * `scriptTypeIndex`; returns `assembly:namespace.classname` or null when unknown.
     */
    private val scriptNameLookup: ((TypeTreeType) -> String?)? = null,
) {

    /**
     * Resolves [typeTreeType]'s nodes from the ttmap when the type carries no embedded tree.
     * Returns a [TypeTreeBlob] with inline-resolved nodes and an empty string buffer (nodes
     * carry resolved strings in ttmap entries), or null when the type is embedded or the
     * ttmap has no matching entry.
     */
    fun resolve(typeTreeType: TypeTreeType): TypeTreeBlob? {
        if (ttmap == null) return null
        if (typeTreeType.typeBlobIsDefinition) return null
        if (typeTreeType.nodes.isNotEmpty()) return null

        val entry = lookup(typeTreeType)
            ?: return null

        return toBlob(entry)
    }

    /**
     * Resolves an external type through the ttmap by identity (used when the type's own
     * `scriptId`/class-id isn't enough — e.g. MonoBehaviour script entries).
     */
    fun resolveByClassId(classId: Int): TtmapType? {
        if (ttmap == null) return null
        return ttmap.types.builtin[classId.toString()]
    }

    companion object {
        /**
         * Converts a [TtmapType] entry into a [TypeTreeBlob] with inline-resolved nodes
         * (strings at sequential buffer offsets; type at 2*i, name at 2*i+1).
         */
        fun toBlob(entry: TtmapType): TypeTreeBlob? {
            if (entry.nodes.isEmpty()) return null
            val blob = TypeTreeBlob()
            blob.nodes = entry.nodes.map { inline ->
                TypeTreeNode().apply {
                    version = inline.version
                    level = inline.level
                    typeFlags = inline.typeFlags
                    byteSize = inline.byteSize
                    index = inline.index
                    metaFlags = inline.metaFlags
                    refTypeHash = inline.refTypeHash
                }
            }.toMutableList()
            val sb = StringBuilder()
            for (n in entry.nodes) {
                sb.append(n.type).append('\u0000').append(n.name).append('\u0000')
            }
            blob.stringBufferBytes = sb.toString().toByteArray(Charsets.UTF_8)
            var off = 0
            for ((i, inline) in entry.nodes.withIndex()) {
                blob.nodes[i].typeStrOffset = off.toLong()
                off += inline.type.length + 1
                blob.nodes[i].nameStrOffset = off.toLong()
                off += inline.name.length + 1
            }
            return blob
        }
    }

    private fun lookup(typeTreeType: TypeTreeType): TtmapType? {
        val t = ttmap ?: return null
        // native types by class id
        if (typeTreeType.typeId >= 0) {
            t.types.builtin[typeTreeType.typeId.toString()]?.let { return it }
        }
        // MonoBehaviour: scriptIds first, then script by name
        val scriptId = typeTreeType.scriptIdHash
        if (!scriptId.isZero()) {
            t.types.scriptIds[scriptId.toString()]?.let { return it }
        }
        if (scriptNameLookup != null) {
            val name = scriptNameLookup(typeTreeType) ?: return null
            t.types.script[name]?.let { return it }
        }
        return null
    }
}

/**
 * Metadata-level resolution helper: resolves every external type in [metadata]'s type list
 * and returns a map type-index -> resolved blob, or null when nothing external needs it.
 */
fun resolveMetadataTypeBlobs(metadata: AssetsFileMetadata, ttmap: Ttmap?): Map<Int, TypeTreeBlob>? {
    if (ttmap == null) return null
    val resolver = TtmapResolver(ttmap)
    var any = false
    val out = HashMap<Int, TypeTreeBlob>()
    for (i in metadata.typeTreeTypes.indices) {
        val type = metadata.typeTreeTypes[i]
        if (type.typeBlobIsDefinition) continue
        val blob = resolver.resolve(type) ?: continue
        out[i] = blob
        any = true
    }
    return if (any) out else null
}

// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port)
// ttmap: the Unity analog of UE's USMap — plan §2.7. Kotlin data classes with default
// Jackson binding (no $type, no custom serializers); gzip-wrapped JSON on disk.
package com.github.jpabscale.asset4j.ttmap

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A per-game-version schema artifact mapping type identity to a type tree's nodes.
 * Maps are `Map` (LinkedHashMap-backed) to preserve declaration order.
 */
data class Ttmap(
    val unityVersion: String,
    val gameVersion: String,
    val types: TtmapTypes,
) {
    companion object {
        val mapper: ObjectMapper = jacksonObjectMapper()

        /** Loads a gzip-wrapped ttmap from [input]. */
        fun read(input: InputStream): Ttmap {
            val decompressed = GZIPInputStream(input)
            return mapper.readValue(decompressed, Ttmap::class.java)
        }

        /** Writes [this] as gzip-wrapped JSON to [output]. */
        fun write(ttmap: Ttmap, output: OutputStream) {
            val gz = GZIPOutputStream(output)
            mapper.writeValue(gz, ttmap)
            gz.finish()
        }

        /** Loads a gzip-wrapped ttmap from raw gzip bytes. */
        fun fromBytes(bytes: ByteArray): Ttmap = read(ByteArrayInputStream(bytes))

        /** Serializes to gzip-wrapped JSON bytes. */
        fun toBytes(ttmap: Ttmap): ByteArray {
            val baos = ByteArrayOutputStream()
            write(ttmap, baos)
            return baos.toByteArray()
        }
    }
}

/**
 * Type entries keyed three ways (dual keying, plan §2.7): `builtin` by ClassId string,
 * `script` by `assembly:namespace.classname`, `scriptIds` by the MonoScript Hash128 string.
 */
data class TtmapTypes(
    val builtin: Map<String, TtmapType>,
    val script: Map<String, TtmapType>,
    val scriptIds: Map<String, TtmapType>,
)

/**
 * A single type's tree: inline resolved nodes (type/name strings carry no offsets).
 */
data class TtmapType(
    val nodes: List<TypeTreeNodeInline>,
)

/**
 * A type tree node with resolved strings (no offsets). Mirrors `TypeTreeNode`'s fields;
 * [type]/[name] are the resolved strings, not string-buffer offsets.
 */
data class TypeTreeNodeInline(
    val version: Int,
    val level: Int,
    val typeFlags: Int,
    val type: String,
    val name: String,
    val byteSize: Int,
    val index: Long,
    val metaFlags: Long,
    val refTypeHash: Long,
)

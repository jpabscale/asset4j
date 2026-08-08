// Copyright (c) 2026 jpabscale — original code (JSON layer, not part of the port)
package com.github.jpabscale.asset4j.api

import com.github.jpabscale.asset4j.serializedfile.AssetTypeReference

/**
 * Cross-file MonoScript identity registry (plan §2.7 IL2CPP name resolution). A
 * MonoBehaviour type is identified only by its `ScriptTypeIndex` -> `ScriptTypes[idx]`
 * PPtr (which MonoScript it references); the MonoScript object (often in another file
 * of the same bundle) carries the `assembly:namespace.classname` identity that keys
 * the ttmap `script` map. [AssetService] builds this by scanning every SerializedFile
 * in a bundle for MonoScript (ClassId 115) objects, decoding each with the ttmap's
 * builtin MonoScript tree, and recording `(bundleFileName, pathId) -> reference`.
 *
 * A PPtr's `fileId` is relative to the referencing file's externals (0 = this file,
 * N >= 1 = externals[N-1]); the externals' `Path` matches the target bundle file name,
 * so lookup resolves `(fileId, pathId)` via the current file's externals + its own name.
 */
class MonoScriptRegistry {
    private val byFile = LinkedHashMap<String, HashMap<Long, AssetTypeReference>>()

    fun put(fileName: String, pathId: Long, ref: AssetTypeReference) {
        byFile.getOrPut(fileName) { HashMap() }[pathId] = ref
    }

    /** Resolves the MonoScript identity in bundle file [fileName] at [pathId]. */
    fun resolve(fileName: String, pathId: Long): AssetTypeReference? =
        byFile[fileName]?.get(pathId)

    fun isEmpty(): Boolean = byFile.isEmpty()

    val size: Int get() = byFile.values.sumOf { it.size }

    /** Snapshot of all `(fileName, pathId) -> reference` entries, for cache reuse. */
    fun snapshot(): Map<String, Map<Long, AssetTypeReference>> =
        byFile.entries.associate { (k, v) -> k to HashMap(v) }
}

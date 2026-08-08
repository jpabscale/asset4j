// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port)
package com.github.jpabscale.asset4j.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.jpabscale.asset4j.bundle.AssetBundleFile
import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import com.github.jpabscale.asset4j.serializedfile.AssetsFile
import com.github.jpabscale.asset4j.ttmap.Ttmap
import java.nio.file.Files
import java.nio.file.Path

/**
 * Public entry point mirroring uasset4j's [com.github.jpabscale.uasset4j.api.UAssetService]
 * surface: [toJson]/[toJsonNode]/[fromJson]/[fromJsonNode]/[roundTrip]/[load], plus
 * [detect]. The external-schema parameter [ttmapName] mirrors uasset4j's `mappingsName`
 * (plan §2.5). Anything that matches no SerializedFile signature passes through as opaque
 * bytes with its path intact.
 */
object AssetService {
    private val mapper: ObjectMapper = AssetJson.mapper

    // Addressables dependency index: per addressables root directory (the dir whose child
    // contains `catalog.bin`), maps each `CAB-<hash>` to the sibling `.bundle` file whose
    // inner files include it. Built lazily on first need and reused across all patch calls
    // for that game, so a mod that patches many scene bundles scans each bundle's info
    // block once instead of per call (mirrors ATN's pre-loaded FileLookup without requiring
    // the caller to load dependencies first).
    private val cabIndexCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Path>>()

    // Per (addressables root, ttmap) decoded MonoScript registry from the mono bundles,
    // so the 1000+ MonoScript objects aren't re-decoded for every patched scene bundle.
    private val monoRegistryCache =
        java.util.concurrent.ConcurrentHashMap<String, Map<String, Map<Long, com.github.jpabscale.asset4j.serializedfile.AssetTypeReference>>>()

    // Per `<ClassName>@<file>` decode cache (the "json caching" unit): once a file's matching
    // objects for a class are decoded, reuse them across patch calls for the same file bytes.
    // Keyed by the file's content hash so a modified file (e.g. after a prior patch) misses.
    private val classJsonCache =
        java.util.concurrent.ConcurrentHashMap<String, ClassJsonCacheEntry>()

    private class ClassJsonCacheEntry(
        val byIndex: Map<Int, String>,
        val decoded: List<Pair<com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, DecodedObject>>,
    )

    @JvmStatic
    @JvmOverloads
    fun toJson(src: Path, ttmapName: String? = null): String {
        val node = toJsonNode(src, ttmapName)
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
    }

    @JvmStatic
    @JvmOverloads
    fun toJsonNode(src: Path, ttmapName: String? = null, externalBase: Path? = null): ObjectNode {
        val bytes = Files.readAllBytes(src)
        return toJsonNodeBytes(bytes, src, ttmapName, externalBase)
    }

    /** Decodes in-memory [bytes] to a JSON node, using [src] only for sibling-external
     *  MonoScript resolution (standalone SerializedFiles). Enables byte-threading between
     *  patches without temp files. [externalBase] (default: [src]) is the directory to
     *  resolve sibling externals from — pass the pristine game file when decoding a copy
     *  that lives elsewhere (e.g. a staged temp in automod's own tree). */
    @JvmStatic
    @JvmOverloads
    fun toJsonNodeBytes(bytes: ByteArray, src: Path, ttmapName: String? = null, externalBase: Path? = null): ObjectNode {
        val ttmap = loadTtmap(ttmapName)
        val root = mapper.createObjectNode()
        when (detect(bytes)) {
            DetectResult.UnityFSBundle -> {
                root.put("\$type", "asset4j.AssetBundle")
                val bf = AssetBundleFile()
                bf.read(AssetsFileReader(bytes))
                // preserve the bundle header so the re-encode round-trips faithfully
                root.put("BundleVersion", bf.header.version)
                root.put("BundleGenerationVersion", bf.header.generationVersion)
                root.put("BundleEngineVersion", bf.header.engineVersion)
                root.put("BundleFlags", bf.header.fileStreamHeader.flags)
                val names = bf.getAllFileNames()
                // first pass: decode every MonoScript (ClassId 115) object in the bundle
                // so MonoBehaviour types can resolve their script name via the ttmap
                val monoScripts = buildMonoScriptRegistry(bf, names, ttmap, src)
                val files = mapper.createArrayNode()
                for (name in names) {
                    val fileNode = mapper.createObjectNode()
                    fileNode.put("Name", name)
                    val data = bf.getFileData(bf.getFileIndex(name))
                    if (AssetsFile.isAssetsFile(AssetsFileReader(data), 0, data.size.toLong())) {
                        val inner = AssetsFile()
                        inner.read(AssetsFileReader(data))
                        fileNode.set<JsonNode>("Asset", AssetFileJson.toNode(inner, ttmap, monoScripts, name))
                    } else {
                        fileNode.put("Data", java.util.Base64.getEncoder().encodeToString(data))
                    }
                    files.add(fileNode)
                }
                root.set<JsonNode>("Files", files)
            }
            DetectResult.SerializedFile -> {
                val af = AssetsFile()
                af.read(AssetsFileReader(bytes))
                // MonoBehaviours in a standalone file resolve their script types from
                // MonoScript objects in sibling externals (e.g. globalgamemanagers.assets)
                val monoScripts = buildStandaloneMonoScriptRegistry(src, af, ttmap, externalBase)
                root.setAll(AssetFileJson.toNode(af, ttmap, monoScripts, src.fileName.toString()))
            }
            else -> {
                root.put("\$type", "asset4j.Opaque")
                root.put("Data", java.util.Base64.getEncoder().encodeToString(bytes))
            }
        }
        return root
    }

    @JvmStatic
    @JvmOverloads
    fun fromJson(json: String, ttmapName: String? = null): ByteArray {
        val node = mapper.readTree(json)
        return fromJsonNode(node, ttmapName)
    }

    @JvmStatic
    @JvmOverloads
    fun fromJsonNode(node: JsonNode, ttmapName: String? = null): ByteArray {
        val ttmap = loadTtmap(ttmapName)
        val type = node.get("\$type")?.asText()
        return when (type) {
            "asset4j.AssetBundle" -> {
                val bf = AssetBundleFile()
                val source = node.get("Files")
                val writer = AssetsFileWriter()
                // decode each file into bytes; rebuild bundle via write path
                val rebuilt = com.github.jpabscale.asset4j.bundle.AssetBundleFile()
                val dirInfos = mutableListOf<com.github.jpabscale.asset4j.bundle.AssetBundleDirectoryInfo>()
                val fileBytes = mutableMapOf<String, ByteArray>()
                for (f in source) {
                    val name = f.get("Name").asText()
                    val bytes = if (f.has("Asset")) {
                        val inner = AssetsFile()
                        AssetFileJson.fromNode(f.get("Asset") as ObjectNode, inner)
                        val w = AssetsFileWriter()
                        inner.write(w)
                        w.toByteArray()
                    } else {
                        java.util.Base64.getDecoder().decode(f.get("Data").asText())
                    }
                    fileBytes[name] = bytes
                    dirInfos.add(com.github.jpabscale.asset4j.bundle.AssetBundleDirectoryInfo.create(name, true))
                }
                rebuilt.blockAndDirInfo.directoryInfos = dirInfos
                val dataWriter = AssetsFileWriter()
                val offsets = mutableListOf<Long>()
                for ((name, bytes) in fileBytes) {
                    offsets.add(dataWriter.position.toLong())
                    dataWriter.writeBytes(bytes)
                }
                rebuilt.dataReader = AssetsFileReader(dataWriter.toByteArray())
                rebuilt.dataIsCompressed = false
                var i = 0
                for (dir in dirInfos) {
                    dir.offset = offsets[i]
                    dir.decompressedSize = fileBytes[dir.name]!!.size.toLong()
                    i++
                }
                rebuilt.header.signature = "UnityFS"
                rebuilt.header.version = if (node.has("BundleVersion")) node.get("BundleVersion").asLong() else 6L
                rebuilt.header.generationVersion = if (node.has("BundleGenerationVersion")) node.get("BundleGenerationVersion").asText() else "5.x.x"
                rebuilt.header.engineVersion = if (node.has("BundleEngineVersion")) node.get("BundleEngineVersion").asText() else "2019.1.4f1"
                rebuilt.header.fileStreamHeader.flags = if (node.has("BundleFlags")) node.get("BundleFlags").asLong() else 0x40L
                rebuilt.write(writer)
                writer.toByteArray()
            }
            "asset4j.SerializedFile" -> {
                val af = AssetsFile()
                AssetFileJson.fromNode(node as ObjectNode, af)
                val writer = AssetsFileWriter()
                af.write(writer)
                writer.toByteArray()
            }
            else -> {
                java.util.Base64.getDecoder().decode(node.get("Data").asText())
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun roundTrip(src: Path, ttmapName: String? = null): ByteArray =
        fromJson(toJson(src, ttmapName), ttmapName)

    @JvmStatic
    @JvmOverloads
    fun load(src: Path, ttmapName: String? = null): ByteArray {
        return if (detect(Files.readAllBytes(src)) != DetectResult.Opaque) {
            roundTrip(src, ttmapName)
        } else {
            Files.readAllBytes(src)
        }
    }

    @JvmStatic
    fun write(bytes: ByteArray, dst: Path) {
        Files.write(dst, bytes)
    }

    /**
     * Metadata-only probe: returns the first [typeTreeTypes[i].scriptTypeIndex] in [src]
     * whose resolved MonoScript class name starts with [className], or -1. Fast even for
     * huge files because no object data is decoded.
     */
    @JvmStatic
    fun probeScriptTypeIndex(src: Path, ttmapName: String?, className: String): Int {
        val bytes = Files.readAllBytes(src)
        val ttmap = loadTtmap(ttmapName)
        if (AssetsFile.isAssetsFile(AssetsFileReader(bytes), 0, bytes.size.toLong())) {
            val af = AssetsFile()
            af.read(AssetsFileReader(bytes))
            val monoScripts = MonoScriptRegistry()
            val byIndex = probeFileIndexMap(af, src, ttmap, monoScripts)
            for ((idx, name) in byIndex) {
                if (nameMatchesClass(name, className)) {
                    println("scriptTypeIndex $idx -> $name")
                    return idx
                }
            }
            for ((i, n) in byIndex.toSortedMap()) println("index $i -> $n")
            return -1
        }
        // UnityFS bundle: each inner SerializedFile has its own scriptTypes list, so report
        // the first file that contains the class (the per-file index is only meaningful
        // within that file — callers should use patchObjectsJsonByScriptName for bundles).
        val bf = AssetBundleFile()
        bf.read(AssetsFileReader(bytes))
        val names = bf.getAllFileNames()
        val monoScripts = buildMonoScriptRegistry(bf, names, ttmap, src)
        for (name in names) {
            val data = bf.getFileData(bf.getFileIndex(name))
            if (!AssetsFile.isAssetsFile(AssetsFileReader(data), 0, data.size.toLong())) continue
            val innerSrc = src.parent?.resolve(name) ?: src
            val af = AssetsFile()
            af.read(AssetsFileReader(data))
            val byIndex = probeFileIndexMap(af, innerSrc, ttmap, monoScripts)
            for ((idx, n) in byIndex) {
                if (nameMatchesClass(n, className)) {
                    println("$name: scriptTypeIndex $idx -> $n")
                    return idx
                }
            }
        }
        return -1
    }

    /** Builds `scriptTypeIndex -> resolved name` for a single SerializedFile. */
    private fun probeFileIndexMap(
        af: AssetsFile,
        src: Path,
        ttmap: Ttmap?,
        monoScripts: MonoScriptRegistry,
        externalBase: Path? = null,
    ): Map<Int, String> {
        val byIndex = mutableMapOf<Int, String>()
        val scriptIndexByTypeId = if (af.header.version < 16) {
            af.metadata.assetInfos
                .filter { it.typeIdOrIndex < 0 && it.scriptTypeIndex != 0xffff }
                .associate { it.typeIdOrIndex to it.scriptTypeIndex }
        } else {
            emptyMap()
        }
        for (info in af.metadata.assetInfos) {
            val typeTree = af.metadata.getTypeTreeType(info, af.header.version) ?: continue
            val idx = when {
                typeTree.typeId == com.github.jpabscale.asset4j.typetree.AssetClassID.MonoBehaviour.id -> typeTree.scriptTypeIndex
                typeTree.typeId < 0 -> scriptIndexByTypeId[typeTree.typeId]
                else -> -1
            }
            if (idx == null || idx < 0 || byIndex.containsKey(idx)) continue
            // MonoBehaviours: resolve the class name from the MonoScript registry by script
            // index (works for embedded-tree files, where the ttmap resolver has nothing to
            // look up, and for external-tree files via the registry the ttmap was built from).
            var name: String? = null
            if (typeTree.typeId == com.github.jpabscale.asset4j.typetree.AssetClassID.MonoBehaviour.id ||
                typeTree.typeId < 0
            ) {
                name = resolveScriptName(af, src, idx, monoScripts, ttmap, externalBase)
            }
            if (name == null) {
                val resolver = com.github.jpabscale.asset4j.ttmap.TtmapResolver(ttmap) { typeTreeType ->
                    val si = when {
                        typeTreeType.typeId == com.github.jpabscale.asset4j.typetree.AssetClassID.MonoBehaviour.id -> typeTreeType.scriptTypeIndex
                        typeTreeType.typeId < 0 -> scriptIndexByTypeId[typeTreeType.typeId]
                        else -> -1
                    }
                    if (si == null || si < 0) null
                    else resolveScriptName(af, src, si, monoScripts, ttmap, externalBase)
                }
                val resolved = resolver.resolve(typeTree)
                name = resolved?.let { resolveNameFromBlob(it) }
            }
            byIndex[idx] = name ?: "opaque"
        }
        return byIndex
    }

    private fun resolveNameFromBlob(blob: com.github.jpabscale.asset4j.typetree.TypeTreeBlob): String? {
        val nodes = blob.nodes
        if (nodes.isEmpty()) return null
        val first = nodes[0]
        val type = first.getTypeString(blob.stringBufferBytes)
        val name = first.getNameString(blob.stringBufferBytes)
        return if (name.isNotEmpty() && name != type) "$type:$name" else type
    }

    /**
     * Whether a resolved script key ([name], e.g. `Assembly-CSharp:.HealthManager` or
     * `HealthManager:Base`) matches [className]. Matches against the full key, the bare
     * class name suffix, or (for `Class:Base` keys) the class name before the colon —
     * so `HealthManager` finds `Assembly-CSharp:.HealthManager` and `HealthManager:Base`.
     */
    private fun nameMatchesClass(name: String, className: String): Boolean {
        if (name.startsWith(className)) return true
        if (name.endsWith(".$className")) return true
        if (name.endsWith(".$className:")) return true
        if (name.endsWith("$className:")) return true
        return false
    }

    /**
     * Surgical patch (read-modify-write): decodes a SerializedFile's metadata once, then
     * decodes **only the objects [shouldPatch] selects** (via the ttmap), hands each one's
     * decoded [com.github.jpabscale.asset4j.value.AssetTypeValueField] to [transform], and
     * sets an AddOrModify replacer on the targets. Every other object is byte-copied
     * unchanged by [AssetsFile.write] (no re-encode), so this is fast even for multi-hundred
     * MB files. Returns the patched file bytes.
     *
     * [transform] returns the re-encoded bytes for the object, or null to leave it untouched.
     */
    @JvmStatic
    fun patchObjects(
        src: Path,
        ttmapName: String?,
        shouldPatch: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo) -> Boolean,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, com.github.jpabscale.asset4j.value.AssetTypeValueField) -> ByteArray?,
    ): ByteArray {
        return patchObjectsCore(src, ttmapName, shouldPatch, { info, d ->
            transform(info, d.vf)
        })
    }

    /**
     * JSON-based surgical patch: like [patchObjects], but each selected object's decoded
     * value is handed to [transform] as a Jackson [com.fasterxml.jackson.databind.JsonNode]
     * (the same node shape produced by [toJsonNode]'s `Data`), which returns a modified
     * node or null to leave the object untouched. Re-encoding uses the same type tree the
     * object was decoded with (ttmap-resolved when available), so round-tripping is lossless
     * for any object the transform leaves structurally intact.
     */
    @JvmStatic
    fun patchObjectsJson(
        src: Path,
        ttmapName: String?,
        shouldPatch: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo) -> Boolean,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, com.fasterxml.jackson.databind.JsonNode) -> com.fasterxml.jackson.databind.JsonNode?,
    ): ByteArray {
        return patchObjectsCore(src, ttmapName, shouldPatch) { info, d ->
            val node = com.github.jpabscale.asset4j.value.ValueFieldJson.toNode(d.vf)
            val newNode = transform(info, node) ?: return@patchObjectsCore null
            val template = com.github.jpabscale.asset4j.value.AssetTypeTemplateField()
            if (d.ttmapBlob != null) {
                template.fromTypeBlob(d.ttmapBlob)
            } else {
                template.fromTypeTree(d.typeTree)
            }
            val refMan = com.github.jpabscale.asset4j.value.RefTypeManager()
            refMan.fromTypeTree(d.af.metadata)
            com.github.jpabscale.asset4j.value.TypeTreeHelper.write(
                com.github.jpabscale.asset4j.value.ValueFieldJson.fromNode(newNode, template, refMan))
        }
    }

    /**
     * Convenience surgical patch: probes [src] for the script type whose class name starts
     * with [className] (e.g. "MonsterSetting"), then patches exactly the objects of that
     * script type via [transform] (see [patchObjectsJson]). Works on both standalone
     * SerializedFiles and UnityFS bundles (for bundles, each inner SerializedFile has its
     * own scriptTypes list, so the class is resolved per file).
     */
    @JvmStatic
    fun patchObjectsJsonByScriptName(
        src: Path,
        ttmapName: String?,
        className: String,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, com.fasterxml.jackson.databind.JsonNode) -> com.fasterxml.jackson.databind.JsonNode?,
        externalBase: Path? = null,
    ): ByteArray {
        val bytes = Files.readAllBytes(src)
        val ttmap = loadTtmap(ttmapName)
        return if (AssetsFile.isAssetsFile(AssetsFileReader(bytes), 0, bytes.size.toLong())) {
            patchObjectsFileByScriptName(bytes, src, ttmap, className, transform, preserveStructure = true, externalBase = externalBase)
        } else {
            patchObjectsBundleByScriptName(bytes, src, ttmap, className, transform, preserveStructure = true, externalBase = externalBase)
        }
    }

    /** Like [patchObjectsJsonByScriptName] but rebuilds the file via [AssetsFile.write] instead
     *  of splicing, so edits that change an object's serialized size (e.g. array add/remove)
     *  are supported. Only the target objects are decoded (fast); non-target data is
     *  byte-copied and the object table's sizes/offsets are recomputed. */
    @JvmStatic
    @JvmOverloads
    fun patchObjectsJsonByScriptNameRoundTrip(
        src: Path,
        ttmapName: String?,
        className: String,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, com.fasterxml.jackson.databind.JsonNode) -> com.fasterxml.jackson.databind.JsonNode?,
        externalBase: Path? = null,
    ): ByteArray {
        val bytes = Files.readAllBytes(src)
        val ttmap = loadTtmap(ttmapName)
        return if (AssetsFile.isAssetsFile(AssetsFileReader(bytes), 0, bytes.size.toLong())) {
            patchObjectsFileByScriptName(bytes, src, ttmap, className, transform, preserveStructure = false, externalBase = externalBase)
        } else {
            patchObjectsBundleByScriptName(bytes, src, ttmap, className, transform, preserveStructure = false, externalBase = externalBase)
        }
    }

    /** Declarative field-set patch (the automod `MonsterSetting@resources.toml` form): every
     *  object of script class [className] in [src] gets each field in [fields] set to exactly
     *  that value — no arithmetic, mirroring UE's value-table model. Uses the targeted
     *  round-trip rebuild and the per-class JSON decode cache. */
    @JvmStatic
    @JvmOverloads
    fun patchObjectsSetFieldsByScriptName(
        src: Path,
        ttmapName: String?,
        className: String,
        fields: kotlin.collections.Map<String, com.fasterxml.jackson.databind.JsonNode>,
    ): ByteArray {
        return patchObjectsJsonByScriptNameRoundTrip(src, ttmapName, className, transform = { _, node ->
            val d = node as com.fasterxml.jackson.databind.node.ObjectNode
            d.setAll(fields) as com.fasterxml.jackson.databind.node.ObjectNode
            d
        })
    }

    private fun patchObjectsFileByScriptName(
        bytes: ByteArray,
        src: Path,
        ttmap: Ttmap?,
        className: String,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, com.fasterxml.jackson.databind.JsonNode) -> com.fasterxml.jackson.databind.JsonNode?,
        preserveStructure: Boolean,
        externalBase: Path? = null,
    ): ByteArray {
        val af = AssetsFile()
        af.read(AssetsFileReader(bytes))
        val (byIndex, decodedObjs) = loadClassDecode(bytes, af, src, ttmap, className, externalBase)
        val replacements = mutableMapOf<Long, ByteArray>()
        for ((info, d) in decodedObjs) {
            val newBytes = jsonTransform(info, d, transform) ?: continue
            replacements[info.pathId] = newBytes
        }
        if (preserveStructure) {
            // Structure-preserving patch: splice only the changed object data into a copy of the
            // original bytes, leaving the header/metadata/type-tree sections byte-identical. This
            // keeps the file indistinguishable from the original for Unity's loader (same sizes,
            // same layout), so a same-width value edit cannot be rejected structurally.
            return spliceObjects(bytes, af, replacements)
        }
        // Round-trip rebuild: only the target objects were decoded/edited (fast), then the
        // file is re-serialized via AssetsFile.write — so size-changing edits (e.g. array
        // element add/remove) are allowed because the metadata sizes/offsets are recomputed.
        // The type trees come from the file itself (unchanged), so only the edited object data
        // and the recomputed object table differ from the original.
        for ((pathId, newData) in replacements) {
            val info = af.metadata.getAssetInfo(pathId) ?: continue
            info.replacer = com.github.jpabscale.asset4j.serializedfile.ContentReplacerFromBuffer(newData)
        }
        val writer = AssetsFileWriter()
        af.write(writer)
        return writer.toByteArray()
    }

    /** Decodes ONLY the [className] objects in [src] and returns each object's Data as a JSON
     *  node (pathId -> Data). Targeted — the 950K-object metadata scan and object decode are
     *  cached per `<ClassName>@<file>` so the declarative TOML flow never decodes the whole
     *  file. */
    @JvmStatic
    @JvmOverloads
    fun decodeMatchingObjectsByScriptName(
        src: Path,
        ttmapName: String?,
        className: String,
        externalBase: Path? = null,
    ): Map<Long, com.fasterxml.jackson.databind.node.ObjectNode> {
        val bytes = Files.readAllBytes(src)
        val ttmap = loadTtmap(ttmapName)
        val af = AssetsFile()
        af.read(AssetsFileReader(bytes))
        val (_, decodedObjs) = loadClassDecode(bytes, af, src, ttmap, className, externalBase)
        val out = HashMap<Long, com.fasterxml.jackson.databind.node.ObjectNode>()
        for ((info, d) in decodedObjs) {
            val n = com.github.jpabscale.asset4j.value.ValueFieldJson.toNode(d.vf)
            if (n is com.fasterxml.jackson.databind.node.ObjectNode) out[info.pathId] = n
        }
        return out
    }

    /** Re-encodes [edited] object Data (from [decodeMatchingObjectsByScriptName]) back into
     *  [src] via the targeted round-trip rebuild. Objects without an edit keep their data. */
    @JvmStatic
    @JvmOverloads
    fun patchObjectsFromDataByScriptName(
        src: Path,
        ttmapName: String?,
        className: String,
        edited: Map<Long, com.fasterxml.jackson.databind.node.ObjectNode>,
        externalBase: Path? = null,
    ): ByteArray {
        return patchObjectsJsonByScriptNameRoundTrip(src, ttmapName, className, externalBase = externalBase, transform = { info, node ->
            edited[info.pathId] ?: node
        })
    }

    private fun loadClassDecode(
        bytes: ByteArray,
        af: AssetsFile,
        src: Path,
        ttmap: Ttmap?,
        className: String,
        externalBase: Path? = null,
    ): Pair<Map<Int, String>, List<Pair<com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, DecodedObject>>> {
        // Per `<ClassName>@<file>` cache: skip the (expensive for huge files) script-index
        // probe + object decode when the same file bytes were already decoded for this class.
        val cacheKey = classJsonCacheKey(bytes, src, className, ttmap)
        val cached = classJsonCache.get(cacheKey)
        if (cached != null) return cached.byIndex to cached.decoded
        val monoScripts = MonoScriptRegistry()
        val byIndex = probeFileIndexMap(af, src, ttmap, monoScripts, externalBase)
        val matching = byIndex.filterValues { nameMatchesClass(it, className) }
        if (matching.isEmpty()) throw IllegalArgumentException("No script type '$className' found in $src")
        val decodedObjs = af.metadata.assetInfos
            .filter { matchesIndex(af, it, matching.keys) }
            .mapNotNull { info -> decodeObject(af, info, src, ttmap, monoScripts, externalBase)?.let { d -> info to d } }
        classJsonCache[cacheKey] = ClassJsonCacheEntry(byIndex, decodedObjs)
        return byIndex to decodedObjs
    }

    private fun classJsonCacheKey(bytes: ByteArray, src: Path, className: String, ttmap: Ttmap?): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = digest.take(12).joinToString("") { "%02x".format(it) }
        return "${src.toAbsolutePath()}|$hash|$className|${System.identityHashCode(ttmap)}"
    }

    /** Copies [bytes] and overwrites the data region of each [replacements] object in place.
     *  Every replacement must be byte-for-byte the same length as the original object data
     *  (the callers only make same-width value edits), otherwise the layout would shift. */
    private fun spliceObjects(
        bytes: ByteArray,
        af: AssetsFile,
        replacements: Map<Long, ByteArray>,
    ): ByteArray {
        val out = bytes.copyOf()
        val dataOffset = af.header.dataOffset
        for ((pathId, newData) in replacements) {
            val info = af.metadata.getAssetInfo(pathId) ?: continue
            val origLen = info.byteSize.toInt()
            if (newData.size != origLen) {
                throw IllegalArgumentException(
                    "object $pathId data size changed ${origLen} -> ${newData.size}; " +
                    "structure-preserving patch requires same-width edits")
            }
            val off = info.getAbsoluteByteOffset(dataOffset).toInt()
            System.arraycopy(newData, 0, out, off, newData.size)
        }
        return out
    }

    private fun patchObjectsBundleByScriptName(
        bytes: ByteArray,
        src: Path,
        ttmap: Ttmap?,
        className: String,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, com.fasterxml.jackson.databind.JsonNode) -> com.fasterxml.jackson.databind.JsonNode?,
        preserveStructure: Boolean,
        externalBase: Path? = null,
    ): ByteArray {
        val bf = AssetBundleFile()
        bf.read(AssetsFileReader(bytes))
        val names = bf.getAllFileNames()
        val monoScripts = buildMonoScriptRegistry(bf, names, ttmap, src)

        val patchedNames = mutableSetOf<String>()
        val patchedData = mutableMapOf<String, ByteArray>()
        for (name in names) {
            val data = bf.getFileData(bf.getFileIndex(name))
            if (!AssetsFile.isAssetsFile(AssetsFileReader(data), 0, data.size.toLong())) continue
            val innerSrc = src.parent?.resolve(name) ?: src
            val af = AssetsFile()
            af.read(AssetsFileReader(data))
            val matching = probeFileIndexMap(af, innerSrc, ttmap, monoScripts, externalBase)
                .filterValues { nameMatchesClass(it, className) }
            if (matching.isEmpty()) continue
            var changed = false
            val replacements = mutableMapOf<Long, ByteArray>()
            for (info in af.metadata.assetInfos) {
                if (!matchesIndex(af, info, matching.keys)) continue
                val d = decodeObject(af, info, innerSrc, ttmap, monoScripts, externalBase) ?: continue
                val newBytes = jsonTransform(info, d, transform) ?: continue
                replacements[info.pathId] = newBytes
                changed = true
            }
            if (changed) {
                if (preserveStructure) {
                    patchedData[name] = spliceObjects(data, af, replacements)
                } else {
                    for ((pathId, newData) in replacements) {
                        val info = af.metadata.getAssetInfo(pathId) ?: continue
                        info.replacer = com.github.jpabscale.asset4j.serializedfile.ContentReplacerFromBuffer(newData)
                    }
                    val w = AssetsFileWriter()
                    af.write(w)
                    patchedData[name] = w.toByteArray()
                }
                patchedNames.add(name)
            }
        }

        if (patchedNames.isEmpty()) {
            throw IllegalArgumentException("No script type '$className' found in $src")
        }

        // rebuild the bundle, byte-copying unpatched inner files
        val rebuilt = com.github.jpabscale.asset4j.bundle.AssetBundleFile()
        val dirInfos = mutableListOf<com.github.jpabscale.asset4j.bundle.AssetBundleDirectoryInfo>()
        val fileBytes = mutableMapOf<String, ByteArray>()
        for (name in names) {
            fileBytes[name] = if (patchedData.containsKey(name)) patchedData[name]!!
                else bf.getFileData(bf.getFileIndex(name))
            dirInfos.add(com.github.jpabscale.asset4j.bundle.AssetBundleDirectoryInfo.create(name, true))
        }
        rebuilt.blockAndDirInfo.directoryInfos = dirInfos
        val dataWriter = AssetsFileWriter()
        val offsets = mutableListOf<Long>()
        for ((name, fb) in fileBytes) {
            offsets.add(dataWriter.position.toLong())
            dataWriter.writeBytes(fb)
        }
        rebuilt.dataReader = AssetsFileReader(dataWriter.toByteArray())
        rebuilt.dataIsCompressed = false
        var i = 0
        for (dir in dirInfos) {
            dir.offset = offsets[i]
            dir.decompressedSize = fileBytes[dir.name]!!.size.toLong()
            i++
        }
        rebuilt.header.signature = "UnityFS"
        rebuilt.header.version = 6
        rebuilt.header.generationVersion = "5.x.x"
        rebuilt.header.engineVersion = "2019.1.4f1"
        rebuilt.header.fileStreamHeader.flags = 0x40
        val writer = AssetsFileWriter()
        rebuilt.write(writer)
        return writer.toByteArray()
    }

    /** Whether [info]'s script index is one of [indices] (version-aware). */
    private fun matchesIndex(
        af: AssetsFile,
        info: com.github.jpabscale.asset4j.serializedfile.AssetFileInfo,
        indices: Set<Int>,
    ): Boolean {
        if (af.header.version < 16) return info.scriptTypeIndex in indices
        val typeTree = af.metadata.typeTreeTypes.getOrNull(info.typeIdOrIndex) ?: return false
        return typeTree.scriptTypeIndex in indices
    }

    private fun jsonTransform(
        info: com.github.jpabscale.asset4j.serializedfile.AssetFileInfo,
        d: DecodedObject,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, com.fasterxml.jackson.databind.JsonNode) -> com.fasterxml.jackson.databind.JsonNode?,
    ): ByteArray? {
        val node = com.github.jpabscale.asset4j.value.ValueFieldJson.toNode(d.vf)
        val newNode = transform(info, node) ?: return null
        val template = com.github.jpabscale.asset4j.value.AssetTypeTemplateField()
        if (d.ttmapBlob != null) {
            template.fromTypeBlob(d.ttmapBlob)
        } else {
            template.fromTypeTree(d.typeTree)
        }
        val refMan = com.github.jpabscale.asset4j.value.RefTypeManager()
        refMan.fromTypeTree(d.af.metadata)
        val written = com.github.jpabscale.asset4j.value.TypeTreeHelper.write(
            com.github.jpabscale.asset4j.value.ValueFieldJson.fromNode(newNode, template, refMan))
        // Round-trip verification: re-decode the written bytes with the same template and
        // require the result to equal the intended JSON. The splice below preserves the file
        // structure and sizes, but it cannot detect a lossy JSON<->bytes serializer — that
        // would silently write a same-size-but-wrong object. Fail loudly instead.
        val backReader = com.github.jpabscale.asset4j.io.AssetsFileReader(written)
        backReader.bigEndian = false
        val backNode = com.github.jpabscale.asset4j.value.ValueFieldJson.toNode(
            template.makeValue(backReader, refMan))
        if (!jsonSemanticallyEqual(backNode, newNode)) {
            throw IllegalStateException(
                "round-trip check failed for object ${info.pathId}: re-encoded data decodes to a " +
                "different value tree than intended (a lossy serializer would corrupt the patch). " +
                "intended=${newNode} decoded=${backNode}")
        }
        return written
    }

    /** Compares two JSON nodes by value rather than by node type, so a float field that the
     *  transform set as a `DoubleNode` still matches the `FloatNode` the type tree re-decodes
     *  it as (numeric width is not content). All other nodes compare structurally. */
    private fun jsonSemanticallyEqual(
        a: com.fasterxml.jackson.databind.JsonNode,
        b: com.fasterxml.jackson.databind.JsonNode,
    ): Boolean {
        if (a.isNumber && b.isNumber) {
            if (a.isFloatingPointNumber || b.isFloatingPointNumber) {
                return a.asDouble() == b.asDouble()
            }
            return a.asLong() == b.asLong()
        }
        if (a is com.fasterxml.jackson.databind.node.ObjectNode && b is com.fasterxml.jackson.databind.node.ObjectNode) {
            if (a.size() != b.size()) return false
            val it = a.fieldNames()
            while (it.hasNext()) {
                val k = it.next()
                if (!b.has(k)) return false
                if (!jsonSemanticallyEqual(a.get(k), b.get(k))) return false
            }
            return true
        }
        if (a.isArray && b.isArray) {
            if (a.size() != b.size()) return false
            for (i in 0 until a.size()) {
                if (!jsonSemanticallyEqual(a.get(i), b.get(i))) return false
            }
            return true
        }
        return a == b
    }

    private fun patchObjectsCore(
        src: Path,
        ttmapName: String?,
        shouldPatch: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo) -> Boolean,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, DecodedObject) -> ByteArray?,
    ): ByteArray {
        val bytes = Files.readAllBytes(src)
        val ttmap = loadTtmap(ttmapName)
        return if (AssetsFile.isAssetsFile(AssetsFileReader(bytes), 0, bytes.size.toLong())) {
            patchObjectsFile(bytes, src, ttmap, shouldPatch, transform)
        } else {
            // UnityFS bundle: surgically patch each inner SerializedFile that has targets
            patchObjectsBundle(bytes, src, ttmap, shouldPatch, transform)
        }
    }

    private fun patchObjectsFile(
        bytes: ByteArray,
        src: Path,
        ttmap: Ttmap?,
        shouldPatch: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo) -> Boolean,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, DecodedObject) -> ByteArray?,
    ): ByteArray {
        val af = AssetsFile()
        af.read(AssetsFileReader(bytes))

        // On-demand MonoScript resolution (reads only the MonoScript objects referenced by
        // the selected objects' scriptTypeIndex) — no full external scan, so this stays fast
        // even when externals are hundreds of MB.
        val monoScripts = MonoScriptRegistry()

        for (info in af.metadata.assetInfos) {
            if (!shouldPatch(info)) continue
            val d = decodeObject(af, info, src, ttmap, monoScripts) ?: continue
            val newBytes = transform(info, d) ?: continue
            info.replacer = com.github.jpabscale.asset4j.serializedfile.ContentReplacerFromBuffer(newBytes)
        }

        val writer = AssetsFileWriter()
        af.write(writer)
        return writer.toByteArray()
    }

    private fun patchObjectsBundle(
        bytes: ByteArray,
        src: Path,
        ttmap: Ttmap?,
        shouldPatch: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo) -> Boolean,
        transform: (com.github.jpabscale.asset4j.serializedfile.AssetFileInfo, DecodedObject) -> ByteArray?,
    ): ByteArray {
        val bf = AssetBundleFile()
        bf.read(AssetsFileReader(bytes))
        val names = bf.getAllFileNames()

        // build the cross-file MonoScript registry from the whole bundle once
        val monoScripts = buildMonoScriptRegistry(bf, names, ttmap, src)

        // surgically patch each inner SerializedFile that contains a target
        val patchedNames = mutableSetOf<String>()
        val patchedData = mutableMapOf<String, ByteArray>()
        for (name in names) {
            val data = bf.getFileData(bf.getFileIndex(name))
            if (!AssetsFile.isAssetsFile(AssetsFileReader(data), 0, data.size.toLong())) continue
            val innerSrc = src.parent?.resolve(name) ?: src
            val af = AssetsFile()
            af.read(AssetsFileReader(data))
            var changed = false
            for (info in af.metadata.assetInfos) {
                if (!shouldPatch(info)) continue
                val d = decodeObject(af, info, innerSrc, ttmap, monoScripts) ?: continue
                val newBytes = transform(info, d) ?: continue
                info.replacer = com.github.jpabscale.asset4j.serializedfile.ContentReplacerFromBuffer(newBytes)
                changed = true
            }
            if (changed) {
                val w = AssetsFileWriter()
                af.write(w)
                patchedData[name] = w.toByteArray()
                patchedNames.add(name)
            }
        }

        if (patchedNames.isEmpty()) {
            return bytes
        }

        // rebuild the bundle, byte-copying unpatched inner files
        val rebuilt = com.github.jpabscale.asset4j.bundle.AssetBundleFile()
        val dirInfos = mutableListOf<com.github.jpabscale.asset4j.bundle.AssetBundleDirectoryInfo>()
        val fileBytes = mutableMapOf<String, ByteArray>()
        for (name in names) {
            fileBytes[name] = if (patchedData.containsKey(name)) patchedData[name]!!
                else bf.getFileData(bf.getFileIndex(name))
            dirInfos.add(com.github.jpabscale.asset4j.bundle.AssetBundleDirectoryInfo.create(name, true))
        }
        rebuilt.blockAndDirInfo.directoryInfos = dirInfos
        val dataWriter = AssetsFileWriter()
        val offsets = mutableListOf<Long>()
        for ((name, fb) in fileBytes) {
            offsets.add(dataWriter.position.toLong())
            dataWriter.writeBytes(fb)
        }
        rebuilt.dataReader = AssetsFileReader(dataWriter.toByteArray())
        rebuilt.dataIsCompressed = false
        var i = 0
        for (dir in dirInfos) {
            dir.offset = offsets[i]
            dir.decompressedSize = fileBytes[dir.name]!!.size.toLong()
            i++
        }
        rebuilt.header.signature = "UnityFS"
        rebuilt.header.version = 6
        rebuilt.header.generationVersion = "5.x.x"
        rebuilt.header.engineVersion = "2019.1.4f1"
        rebuilt.header.fileStreamHeader.flags = 0x40
        val writer = AssetsFileWriter()
        rebuilt.write(writer)
        return writer.toByteArray()
    }

    private fun decodeObject(
        af: AssetsFile,
        info: com.github.jpabscale.asset4j.serializedfile.AssetFileInfo,
        src: Path,
        ttmap: Ttmap?,
        monoScripts: MonoScriptRegistry,
        externalBase: Path? = null,
    ): DecodedObject? {
        val typeTree = af.metadata.getTypeTreeType(info, af.header.version) ?: return null
        val scriptIndexByTypeId = if (af.header.version < 16) {
            af.metadata.assetInfos
                .filter { it.typeIdOrIndex < 0 && it.scriptTypeIndex != 0xffff }
                .associate { it.typeIdOrIndex to it.scriptTypeIndex }
        } else {
            emptyMap()
        }
        val resolver = com.github.jpabscale.asset4j.ttmap.TtmapResolver(ttmap) { typeTreeType ->
            val si = when {
                typeTreeType.typeId == com.github.jpabscale.asset4j.typetree.AssetClassID.MonoBehaviour.id ->
                    typeTreeType.scriptTypeIndex
                typeTreeType.typeId < 0 -> scriptIndexByTypeId[typeTreeType.typeId]
                else -> -1
            }
            if (si == null || si < 0) null
            else resolveScriptName(af, src, si, monoScripts, ttmap, externalBase)
        }
        val resolved = resolver.resolve(typeTree)
        return try {
            if (resolved != null) {
                DecodedObject(
                    com.github.jpabscale.asset4j.value.TypeTreeHelper.readBytes(af.getObjectData(info.pathId), resolved, null),
                    resolved,
                    typeTree,
                    af,
                )
            } else {
                DecodedObject(
                    com.github.jpabscale.asset4j.value.TypeTreeHelper.readBytes(
                        af.getObjectData(info.pathId), typeTree, af.metadata),
                    null,
                    typeTree,
                    af,
                )
            }
        } catch (e: Exception) {
            null // tree doesn't match; leave untouched
        }
    }

    /** Resolves a scriptTypeIndex to its MonoScript class name, reading only the needed
     *  MonoScript object (in the current file or one of its externals) on demand. */
    private fun resolveScriptName(
        af: AssetsFile,
        src: Path,
        scriptTypeIndex: Int,
        registry: MonoScriptRegistry,
        ttmap: Ttmap?,
        externalBase: Path? = null,
    ): String? {
        if (scriptTypeIndex < 0 || scriptTypeIndex >= af.metadata.scriptTypes.size) return null
        val pptr = af.metadata.scriptTypes[scriptTypeIndex]
        if (pptr.fileId == 0) {
            val ref = registry.resolve(src.fileName.toString(), pptr.pathId)
            if (ref != null) return "${ref.asmName}:${ref.nameSpace}.${ref.className}"
            return readMonoScriptOnDemand(src, pptr.pathId, ttmap)
        }
        val ext = af.metadata.externals.getOrNull(pptr.fileId - 1)?.pathName ?: return null
        val extPath = (externalBase ?: src).toAbsolutePath().parent?.resolve(ext) ?: return null
        // Addressables externals use `archive:/CAB-<hash>/CAB-<hash>`; mirror AssetsTools.NET's
        // GetDependency normalization (strip to the inner CAB name) so the registry lookup
        // works whether it was keyed by the full path or the stripped name.
        var ref = registry.resolve(ext, pptr.pathId)
        if (ref == null && ext.startsWith("archive:/")) {
            val slash = ext.indexOf('/', "archive:/".length)
            val inner = if (slash >= 0) ext.substring(slash + 1) else ext
            ref = registry.resolve(inner, pptr.pathId)
        }
        if (ref != null) return "${ref.asmName}:${ref.nameSpace}.${ref.className}"
        return readMonoScriptOnDemand(extPath, pptr.pathId, ttmap)
    }

    private fun readMonoScriptOnDemand(
        path: Path,
        pathId: Long,
        ttmap: Ttmap?,
    ): String? {
        val blob = monoScriptBlob(ttmap) ?: return null
        if (!java.nio.file.Files.isRegularFile(path)) return null
        val data = runCatching { java.nio.file.Files.readAllBytes(path) }.getOrElse { return null }
        if (!AssetsFile.isAssetsFile(AssetsFileReader(data), 0, data.size.toLong())) return null
        val other = AssetsFile()
        other.read(AssetsFileReader(data))
        val info = other.metadata.assetInfos.firstOrNull {
            it.pathId == pathId &&
            it.getTypeId(other.metadata.typeTreeTypes, other.header.version) ==
                com.github.jpabscale.asset4j.typetree.AssetClassID.MonoScript.id
        } ?: return null
        return try {
            val vf = com.github.jpabscale.asset4j.value.TypeTreeHelper.readBytes(
                other.getObjectData(info.pathId), blob, null)
            val className = vf["m_ClassName"].asString
            if (className.isEmpty()) null
            else {
                val nameSpace = vf["m_Namespace"].asString
                var asmName = vf["m_AssemblyName"].asString
                if (asmName.endsWith(".dll")) asmName = asmName.dropLast(4)
                "$asmName:$nameSpace.$className"
            }
        } catch (e: Exception) {
            null
        }
    }

    private class DecodedObject(
        val vf: com.github.jpabscale.asset4j.value.AssetTypeValueField,
        val ttmapBlob: com.github.jpabscale.asset4j.typetree.TypeTreeBlob?,
        val typeTree: com.github.jpabscale.asset4j.typetree.TypeTreeType,
        val af: AssetsFile,
    )

    enum class DetectResult {
        UnityFSBundle,
        SerializedFile,
        YAMLText,
        JsonText,
        Opaque,
    }

    @JvmStatic
    fun detect(fileBytes: ByteArray): DetectResult {
        if (fileBytes.size >= 8 && fileBytes[0] == 'U'.code.toByte() && fileBytes[1] == 'n'.code.toByte() &&
            fileBytes[2] == 'i'.code.toByte() && fileBytes[3] == 't'.code.toByte() &&
            fileBytes[4] == 'y'.code.toByte() && fileBytes[5] == 'F'.code.toByte() &&
            fileBytes[6] == 'S'.code.toByte() && fileBytes[7] == 0.toByte()
        ) {
            return DetectResult.UnityFSBundle
        }
        if (fileBytes.size >= 4) {
            val b0 = fileBytes[0].toInt() and 0xFF
            val b1 = fileBytes[1].toInt() and 0xFF
            val b2 = fileBytes[2].toInt() and 0xFF
            val b3 = fileBytes[3].toInt() and 0xFF
            // SerializedFile: use the AssetsFile.isAssetsFile check (handles the format
            // version at the right offset, incl. format >= 22).
            if (AssetsFile.isAssetsFile(AssetsFileReader(fileBytes), 0, fileBytes.size.toLong())) {
                return DetectResult.SerializedFile
            }
            if (b0 == '%'.code && b1 == 'Y'.code && b2 == 'A'.code && b3 == 'M'.code) {
                return DetectResult.YAMLText
            }
            if (b0 == '{'.code) {
                return DetectResult.JsonText
            }
        }
        return DetectResult.Opaque
    }

    private fun loadTtmap(ttmapName: String?): Ttmap? {
        if (ttmapName == null) return null
        val path = Path.of(ttmapName)
        if (!Files.isRegularFile(path)) {
            throw IllegalArgumentException("Ttmap path does not exist: $ttmapName")
        }
        return try {
            Files.newInputStream(path).use { Ttmap.read(it) }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to load ttmap from $ttmapName: ${e.message}", e)
        }
    }

    /**
     * Builds the cross-file [MonoScriptRegistry] for a bundle: scans every SerializedFile
     * for MonoScript (ClassId 115) objects, decodes each with the ttmap's builtin MonoScript
     * tree, and records `(bundleFileName, pathId) -> assembly:namespace.classname`. Returns
     * an empty registry when the ttmap has no MonoScript tree or no MonoScripts exist.
     *
     * For addressables bundles (scenes whose MonoScripts live in a sibling `*_monoscripts`
     * bundle, referenced by `archive:/CAB-<hash>/CAB-<hash>` externals), the sibling bundle
     * is located by matching the CAB hash against sibling bundles' directory infos and its
     * MonoScripts are registered under the external path, so PPtr resolution finds them.
     * The CAB->bundle index and the decoded registry are cached per addressables root
     * directory, so patching many scene bundles scans each bundle's info block only once.
     */
    private fun buildMonoScriptRegistry(
        bf: AssetBundleFile,
        names: List<String>,
        ttmap: Ttmap?,
        src: Path? = null,
    ): MonoScriptRegistry {
        val registry = MonoScriptRegistry()
        val monoScriptBlob = monoScriptBlob(ttmap) ?: return registry

        for (name in names) {
            val data = bf.getFileData(bf.getFileIndex(name))
            if (!AssetsFile.isAssetsFile(AssetsFileReader(data), 0, data.size.toLong())) continue
            val af = AssetsFile()
            af.read(AssetsFileReader(data))
            registerMonoScripts(registry, af, name, monoScriptBlob)
        }

        // Addressables: the referenced MonoScripts live in sibling bundles. Match each
        // `archive:/CAB-<hash>/...` external against the cached CAB->bundle index.
        if (src != null) {
            val cabHashes = mutableSetOf<String>()
            for (name in names) {
                val data = bf.getFileData(bf.getFileIndex(name))
                if (!AssetsFile.isAssetsFile(AssetsFileReader(data), 0, data.size.toLong())) continue
                val af = AssetsFile()
                af.read(AssetsFileReader(data))
                for (e in af.metadata.externals) {
                    val m = Regex("archive:/CAB-([0-9a-fA-F]{32})/").find(e.pathName)
                    if (m != null) cabHashes.add(m.groupValues[1])
                }
            }
            if (cabHashes.isNotEmpty()) {
                val root = addressablesRoot(src)
                val cabToBundle = if (root != null) {
                    cabIndexCache.computeIfAbsent(root) { buildCabIndex(root) }
                } else {
                    // no catalog in tree: fall back to a per-call walk
                    buildCabIndexWalk(src, cabHashes)
                }
                if (root != null) {
                    val shared = monoRegistryCache.computeIfAbsent(root + "|" + (monoScriptBlob.hashCode())) {
                        buildMonoRegistryFromIndex(cabToBundle, cabHashes, ttmap)
                    }
                    for ((hash, perPathId) in shared) {
                        for ((pathId, ref) in perPathId) {
                            registry.put("archive:/CAB-$hash/CAB-$hash", pathId, ref)
                            registry.put("CAB-$hash", pathId, ref)
                        }
                    }
                } else {
                    for (hash in cabHashes) {
                        val sib = cabToBundle[hash] ?: continue
                        registerBundleMonoScripts(registry, sib, hash, monoScriptBlob)
                    }
                }
            }
        }
        return registry
    }

    /**
     * Finds the addressables root for [src]: the top-most ancestor directory that directly
     * contains a `catalog.bin` (Unity Addressables marker). Walking up from the bundle's
     * directory, returns the first dir whose *parent* has no `catalog.bin` but whose child
     * does — i.e. the platform folder inside `aa/` (which holds the catalog's bundles).
     * Returns null when no catalog is found (fall back to a per-call walk).
     */
    private fun addressablesRoot(src: Path): String? {
        var dir = src.toAbsolutePath().parent
        while (dir != null && dir.parent != null) {
            if (Files.isRegularFile(dir.resolve("catalog.bin")) ||
                Files.isRegularFile(dir.resolve("catalog.json"))) {
                return dir.toString()
            }
            dir = dir.parent
        }
        return null
    }

    /** Builds the full CAB->bundle index for an addressables root (scans every sibling
     *  `.bundle` in the root tree via info-block-only reads, once, cached). */
    private fun buildCabIndex(root: String): Map<String, Path> {
        val out = HashMap<String, Path>()
        val rootPath = Path.of(root)
        Files.walk(rootPath).use { stream ->
            for (p in stream) {
                if (!Files.isRegularFile(p) || !p.fileName.toString().endsWith(".bundle")) continue
                try {
                    val sbf = AssetBundleFile()
                    sbf.readFileNames(p)
                    for (inner in sbf.blockAndDirInfo.directoryInfos) {
                        val m = Regex("CAB-([0-9a-fA-F]{32})").find(inner.name)
                        if (m != null) out.putIfAbsent(m.groupValues[1], p)
                    }
                } catch (e: Exception) {
                    // not a bundle / unreadable; skip
                }
            }
        }
        return out
    }

    /** Decodes and registers MonoScripts from every referenced CAB bundle, once, cached. */
    private fun buildMonoRegistryFromIndex(
        cabToBundle: Map<String, Path>,
        cabHashes: Set<String>,
        ttmap: Ttmap?,
    ): Map<String, Map<Long, com.github.jpabscale.asset4j.serializedfile.AssetTypeReference>> {
        val monoScriptBlob = monoScriptBlob(ttmap) ?: return emptyMap()
        val out = HashMap<String, Map<Long, com.github.jpabscale.asset4j.serializedfile.AssetTypeReference>>()
        for (hash in cabHashes) {
            val sib = cabToBundle[hash] ?: continue
            val reg = MonoScriptRegistry()
            registerBundleMonoScripts(reg, sib, hash, monoScriptBlob)
            if (reg.size > 0) {
                val flat = HashMap<Long, com.github.jpabscale.asset4j.serializedfile.AssetTypeReference>()
                for ((_, perPathId) in reg.snapshot()) {
                    flat.putAll(perPathId)
                }
                out[hash] = flat
            }
        }
        return out
    }

    /** Registers every MonoScript object in bundle [sib] under both the `archive:/CAB-<hash>`
     *  path and the stripped `CAB-<hash>` filename (ATN-style lookup). */
    private fun registerBundleMonoScripts(
        registry: MonoScriptRegistry,
        sib: Path,
        hash: String,
        monoScriptBlob: com.github.jpabscale.asset4j.typetree.TypeTreeBlob,
    ) {
        try {
            val sbf = AssetBundleFile()
            sbf.read(AssetsFileReader(Files.readAllBytes(sib)))
            for (inner in sbf.getAllFileNames()) {
                val innerData = sbf.getFileData(sbf.getFileIndex(inner))
                if (!AssetsFile.isAssetsFile(AssetsFileReader(innerData), 0, innerData.size.toLong())) continue
                val af = AssetsFile()
                af.read(AssetsFileReader(innerData))
                registerMonoScripts(registry, af, "archive:/CAB-$hash/CAB-$hash", monoScriptBlob)
                registerMonoScripts(registry, af, "CAB-$hash", monoScriptBlob)
            }
        } catch (e: Exception) {
            // skip unreadable sibling
        }
    }

    /** Per-call fallback (no addressables catalog found): walk up from [src] scanning
     *  sibling bundles until every referenced CAB is located. */
    private fun buildCabIndexWalk(src: Path, cabHashes: Set<String>): Map<String, Path> {
        val cabToBundle = HashMap<String, Path>()
        val needed = cabHashes.toMutableSet()
        var searchDir = src.toAbsolutePath().parent
        while (searchDir != null && needed.isNotEmpty() && searchDir.parent != null) {
            if (Files.isDirectory(searchDir)) {
                Files.list(searchDir).use { stream ->
                    for (sib in stream) {
                        if (needed.isEmpty()) break
                        val p = sib
                        if (Files.isRegularFile(p) && p.fileName.toString().endsWith(".bundle")) {
                            try {
                                val sbf = AssetBundleFile()
                                sbf.readFileNames(p)
                                for (inner in sbf.blockAndDirInfo.directoryInfos) {
                                    val m = Regex("CAB-([0-9a-fA-F]{32})").find(inner.name)
                                    if (m != null) {
                                        cabToBundle.putIfAbsent(m.groupValues[1], p)
                                        needed.remove(m.groupValues[1])
                                    }
                                }
                            } catch (e: Exception) {
                                // not a bundle / unreadable; skip
                            }
                        }
                    }
                }
            }
            searchDir = searchDir.parent
        }
        return cabToBundle
    }

    /**
     * Builds the [MonoScriptRegistry] for a standalone SerializedFile by scanning its
     * external dependency files (resolved relative to [src]'s directory). This lets
     * MonoBehaviours in a single `.assets` file resolve their script types when the
     * MonoScript objects live in sibling files (e.g. globalgamemanagers.assets).
     */
    private fun buildStandaloneMonoScriptRegistry(src: Path, af: AssetsFile, ttmap: Ttmap?, externalBase: Path? = null): MonoScriptRegistry {
        val registry = MonoScriptRegistry()
        val monoScriptBlob = monoScriptBlob(ttmap) ?: return registry
        val dir = (externalBase ?: src).toAbsolutePath().parent
        val files = mutableSetOf<Pair<String, Path>>()
        // the file itself (fileId 0) plus externals (fileId N -> externals[N-1].path)
        files.add(src.fileName.toString() to src)
        for (e in af.metadata.externals) {
            val p = dir?.resolve(e.pathName)
            if (p != null && Files.isRegularFile(p)) {
                files.add(e.pathName to p)
            }
        }
        for ((name, path) in files) {
            val data = runCatching { Files.readAllBytes(path) }.getOrElse { continue }
            if (!AssetsFile.isAssetsFile(AssetsFileReader(data), 0, data.size.toLong())) continue
            val other = AssetsFile()
            other.read(AssetsFileReader(data))
            registerMonoScripts(registry, other, name, monoScriptBlob)
        }
        return registry
    }

    private fun monoScriptBlob(ttmap: Ttmap?): com.github.jpabscale.asset4j.typetree.TypeTreeBlob? {
        if (ttmap == null) return null
        val tree = ttmap.types.builtin["115"] ?: return null
        return com.github.jpabscale.asset4j.ttmap.TtmapResolver.toBlob(tree)
    }

    private fun registerMonoScripts(
        registry: MonoScriptRegistry,
        af: AssetsFile,
        fileName: String,
        monoScriptBlob: com.github.jpabscale.asset4j.typetree.TypeTreeBlob,
    ) {
        for (info in af.metadata.assetInfos) {
            if (info.getTypeId(af.metadata.typeTreeTypes, af.header.version) !=
                com.github.jpabscale.asset4j.typetree.AssetClassID.MonoScript.id
            ) {
                continue
            }
            try {
                val vf = com.github.jpabscale.asset4j.value.TypeTreeHelper.readBytes(
                    af.getObjectData(info.pathId), monoScriptBlob, null,
                )
                val className = vf["m_ClassName"].asString
                if (className.isEmpty()) continue
                val nameSpace = vf["m_Namespace"].asString
                var asmName = vf["m_AssemblyName"].asString
                if (asmName.endsWith(".dll")) asmName = asmName.dropLast(4)
                registry.put(
                    fileName,
                    info.pathId,
                    com.github.jpabscale.asset4j.serializedfile.AssetTypeReference(className, nameSpace, asmName),
                )
            } catch (e: Exception) {
                // skip MonoScript objects that don't decode (mismatched tree/version)
            }
        }
    }
}

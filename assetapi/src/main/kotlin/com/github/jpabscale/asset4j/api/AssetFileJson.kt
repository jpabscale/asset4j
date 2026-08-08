// Copyright (c) 2026 jpabscale — original code (JSON layer, not part of the port)
package com.github.jpabscale.asset4j.api

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.jpabscale.asset4j.io.Hash128
import com.github.jpabscale.asset4j.serializedfile.AssetFileInfo
import com.github.jpabscale.asset4j.serializedfile.AssetsFile
import com.github.jpabscale.asset4j.ttmap.Ttmap
import com.github.jpabscale.asset4j.value.TypeTreeHelper
import com.github.jpabscale.asset4j.value.ValueFieldJson

/**
 * Serializes an [AssetsFile] to/from the §2.5 JSON shape (Jackson ObjectNode, order-preserving).
 * The ttmap (when supplied) resolves external type trees for both directions.
 */
object AssetFileJson {

    fun toNode(
        af: AssetsFile,
        ttmap: Ttmap?,
        monoScripts: MonoScriptRegistry? = null,
        selfFileName: String? = null,
    ): com.fasterxml.jackson.databind.node.ObjectNode {
        val factory = JsonNodeFactory.instance
        val root = factory.objectNode()
        root.put("\$type", "asset4j.SerializedFile")
        root.put("Version", af.header.version)
        root.put("UnityVersion", af.metadata.unityVersion)
        root.put("TargetPlatform", af.metadata.targetPlatform)
        root.put("TypeTreeEnabled", af.metadata.typeTreeEnabled)
        root.put("UserInformation", af.metadata.userInformation ?: "")

        // format < 16: a MonoBehaviour's real script index lives in the AssetFileInfo
        // (the TypeTreeType's own scriptTypeIndex is 0xffff), so build typeId -> script
        // index from the objects before resolving script names.
        val scriptIndexByTypeId = if (af.header.version < 16) {
            af.metadata.assetInfos
                .filter { it.typeIdOrIndex < 0 && it.scriptTypeIndex != 0xffff }
                .associate { it.typeIdOrIndex to it.scriptTypeIndex }
        } else {
            emptyMap()
        }

        fun resolveScriptName(typeTreeType: com.github.jpabscale.asset4j.typetree.TypeTreeType): String? {
            val idx = when {
                typeTreeType.typeId == com.github.jpabscale.asset4j.typetree.AssetClassID.MonoBehaviour.id ->
                    typeTreeType.scriptTypeIndex
                // format < 16 MonoBehaviours carry a negative typeId and no script index
                typeTreeType.typeId < 0 -> scriptIndexByTypeId[typeTreeType.typeId]
                else -> -1
            }
            if (idx == null || idx < 0 || idx >= af.metadata.scriptTypes.size) return null
            val pptr = af.metadata.scriptTypes[idx]
            val targetFile = if (pptr.fileId == 0) {
                selfFileName
            } else {
                af.metadata.externals.getOrNull(pptr.fileId - 1)?.pathName
            }
            val ref = targetFile?.let { monoScripts?.resolve(it, pptr.pathId) }
            return ref?.let { "${it.asmName}:${it.nameSpace}.${it.className}" }
        }

        val resolver = com.github.jpabscale.asset4j.ttmap.TtmapResolver(ttmap) { typeTreeType ->
            // MonoBehaviour: resolve the script name through the MonoScript registry
            // (ScriptTypeIndex -> ScriptTypes PPtr -> MonoScript object identity)
            if (monoScripts == null) {
                null
            } else {
                resolveScriptName(typeTreeType)
            }
        }

        val types = factory.arrayNode()
        for (t in af.metadata.typeTreeTypes) {
            val tn = factory.objectNode()
            tn.put("ClassId", t.typeId)
            tn.put("IsStrippedType", t.isStrippedType)
            tn.put("ScriptTypeIndex", t.scriptTypeIndex)
            tn.put("ScriptId", t.scriptIdHash.toString())
            tn.put("OldTypeHash", t.typeHash.toString())
            tn.put("RefTypeHash", if (t.extTypeHash.isZero()) null else t.extTypeHash.toString())
            tn.put("TypeBlobIsDefinition", t.typeBlobIsDefinition)
            if (t.typeDependencies.isNotEmpty()) {
                val deps = factory.arrayNode()
                for (d in t.typeDependencies) deps.add(d)
                tn.set<com.fasterxml.jackson.databind.JsonNode>("TypeDependencies", deps)
            }
            val nodes = factory.arrayNode()
            for (n in t.nodes) {
                val nn = factory.objectNode()
                nn.put("version", n.version)
                nn.put("level", n.level)
                nn.put("typeFlags", n.typeFlags)
                nn.put("typeStrOffset", n.typeStrOffset)
                nn.put("nameStrOffset", n.nameStrOffset)
                nn.put("byteSize", n.byteSize)
                nn.put("index", n.index)
                nn.put("metaFlags", n.metaFlags)
                nn.put("refTypeHash", n.refTypeHash)
                nodes.add(nn)
            }
            tn.set<com.fasterxml.jackson.databind.JsonNode>("nodes", nodes)
            if (t.stringBufferBytes.isNotEmpty()) {
                tn.put("stringBuffer", java.util.Base64.getEncoder().encodeToString(t.stringBufferBytes))
            }
            // for external-tree types, write the ttmap-resolved nodes so the JSON is
            // self-contained (fromNode needs no ttmap); preserve the external reference too
            val resolved = resolver.resolve(t)
            if (resolved != null) {
                val rn = factory.arrayNode()
                for (n in resolved.nodes) {
                    val nn = factory.objectNode()
                    nn.put("version", n.version)
                    nn.put("level", n.level)
                    nn.put("typeFlags", n.typeFlags)
                    nn.put("typeStrOffset", n.typeStrOffset)
                    nn.put("nameStrOffset", n.nameStrOffset)
                    nn.put("byteSize", n.byteSize)
                    nn.put("index", n.index)
                    nn.put("metaFlags", n.metaFlags)
                    nn.put("refTypeHash", n.refTypeHash)
                    rn.add(nn)
                }
                tn.set<com.fasterxml.jackson.databind.JsonNode>("resolvedNodes", rn)
                tn.put("resolvedStringBuffer", java.util.Base64.getEncoder().encodeToString(resolved.stringBufferBytes))
            }
            types.add(tn)
        }
        root.set<com.fasterxml.jackson.databind.JsonNode>("types", types)

        val objects = factory.arrayNode()
        val canDecode = af.metadata.typeTreeEnabled || ttmap != null
        for (info in af.metadata.assetInfos) {
            val on = factory.objectNode()
            on.put("PathId", info.pathId)
            on.put("TypeId", info.typeIdOrIndex)
            on.put("ClassId", info.typeId)
            on.put("ScriptIndex", info.scriptTypeIndex)
            // resolved MonoScript class name (e.g. `Assembly-CSharp:.MonsterSetting`), so
            // declarative `.@` patches can filter objects by class without knowing PathIds.
            val typeTree0 = af.metadata.getTypeTreeType(info, af.header.version)
            if (typeTree0 != null) {
                val cls = resolveScriptName(typeTree0)
                if (cls != null) {
                    on.put("Script", cls)
                    // bare class name (last `ns.class` segment) for declarative filters like
                    // `MonsterSetting@resources.toml` (which scope by class, not namespace).
                    on.put("ClassName", cls.substringAfterLast('.', cls))
                }
            }
            val raw = af.getObjectData(info.pathId)
            val typeTree = af.metadata.getTypeTreeType(info, af.header.version)
            val resolved = if (typeTree != null) resolver.resolve(typeTree) else null
            if (canDecode && typeTree != null && (typeTree.nodes.isNotEmpty() || resolved != null)) {
                try {
                    val vf = if (resolved != null) {
                        TypeTreeHelper.readBytes(raw, resolved, null)
                    } else {
                        TypeTreeHelper.readBytes(raw, typeTree, af.metadata)
                    }
                    on.set<com.fasterxml.jackson.databind.JsonNode>("Data", ValueFieldJson.toNode(vf))
                } catch (e: Exception) {
                    // tree doesn't match the object bytes (e.g. an IL2CPP-approximated
                    // script tree): fall back to opaque, mirroring C# "skip unreadable"
                    on.put("Data", java.util.Base64.getEncoder().encodeToString(raw))
                }
            } else {
                // no embedded type tree, no ttmap entry: keep the object bytes opaque
                on.put("Data", java.util.Base64.getEncoder().encodeToString(raw))
            }
            objects.add(on)
        }
        root.set<com.fasterxml.jackson.databind.JsonNode>("Objects", objects)

        val scripts = factory.arrayNode()
        for (s in af.metadata.scriptTypes) {
            val sn = factory.objectNode()
            sn.put("LocalSerializedFileIndex", s.fileId)
            sn.put("LocalIdentifierInFile", s.pathId)
            scripts.add(sn)
        }
        root.set<com.fasterxml.jackson.databind.JsonNode>("ScriptTypes", scripts)

        val externals = factory.arrayNode()
        for (e in af.metadata.externals) {
            val en = factory.objectNode()
            en.put("Path", e.pathName)
            if (e.originalPathName.isNotEmpty()) en.put("OriginalPath", e.originalPathName)
            en.put("Guid", e.guid.toString())
            en.put("Type", e.type.ordinal)
            en.put("TempEmpty", "")
            externals.add(en)
        }
        root.set<com.fasterxml.jackson.databind.JsonNode>("Externals", externals)

        // ref types (needed to decode ManagedReferencesRegistry objects)
        val refTypes = factory.arrayNode()
        for (r in af.metadata.refTypes) {
            val rn = factory.objectNode()
            rn.put("ClassId", r.typeId)
            rn.put("ScriptTypeIndex", r.scriptTypeIndex)
            rn.put("ClassName", r.typeReference?.className ?: "")
            rn.put("Namespace", r.typeReference?.nameSpace ?: "")
            rn.put("AsmName", r.typeReference?.asmName ?: "")
            val nodes = factory.arrayNode()
            for (n in r.nodes) {
                val nn = factory.objectNode()
                nn.put("version", n.version)
                nn.put("level", n.level)
                nn.put("typeFlags", n.typeFlags)
                nn.put("typeStrOffset", n.typeStrOffset)
                nn.put("nameStrOffset", n.nameStrOffset)
                nn.put("byteSize", n.byteSize)
                nn.put("index", n.index)
                nn.put("metaFlags", n.metaFlags)
                nn.put("refTypeHash", n.refTypeHash)
                nodes.add(nn)
            }
            rn.set<com.fasterxml.jackson.databind.JsonNode>("nodes", nodes)
            if (r.stringBufferBytes.isNotEmpty()) {
                rn.put("stringBuffer", java.util.Base64.getEncoder().encodeToString(r.stringBufferBytes))
            }
            refTypes.add(rn)
        }
        root.set<com.fasterxml.jackson.databind.JsonNode>("RefTypes", refTypes)

        return root
    }

    /**
     * Builds an [AssetsFile] from the JSON [root] (a SerializedFile node), writing the
     * objects through the type trees. The header/data layout mirrors the read path so the
     * result is a valid file; caller writes it with [AssetsFile.write].
     */
    fun fromNode(root: com.fasterxml.jackson.databind.node.ObjectNode, af: AssetsFile) {
        // header fields
        af.header.version = root.get("Version").asLong()
        af.header.endianness = false
        af.metadata.unityVersion = root.get("UnityVersion").asText()
        af.metadata.targetPlatform = root.get("TargetPlatform").asLong()
        af.metadata.typeTreeEnabled = root.get("TypeTreeEnabled").asBoolean()
        af.metadata.userInformation = root.get("UserInformation")?.asText("")

        // types
        af.metadata.typeTreeTypes = mutableListOf()
        val types = root.get("types")
        for (tn in types) {
            val t = com.github.jpabscale.asset4j.typetree.TypeTreeType()
            t.typeId = tn.get("ClassId").asInt()
            t.isStrippedType = tn.get("IsStrippedType").asBoolean()
            t.scriptTypeIndex = tn.get("ScriptTypeIndex").asInt()
            t.scriptIdHash = Hash128.tryParse(tn.get("ScriptId").asText()) ?: Hash128.newBlankHash()
            t.typeHash = Hash128.tryParse(tn.get("OldTypeHash").asText()) ?: Hash128.newBlankHash()
            t.typeBlobIsDefinition = tn.get("TypeBlobIsDefinition").asBoolean()
            val depsNode = tn.get("TypeDependencies")
            if (depsNode != null) {
                t.typeDependencies = IntArray(depsNode.size()) { depsNode.get(it).asInt() }
            }
            t.typeBlob = com.github.jpabscale.asset4j.typetree.TypeTreeBlob()
            val nodes = tn.get("nodes")
            val nodeList = mutableListOf<com.github.jpabscale.asset4j.typetree.TypeTreeNode>()
            for (nn in nodes) {
                val n = com.github.jpabscale.asset4j.typetree.TypeTreeNode()
                n.version = nn.get("version").asInt()
                n.level = nn.get("level").asInt()
                n.typeFlags = nn.get("typeFlags").asInt()
                n.typeStrOffset = nn.get("typeStrOffset").asLong()
                n.nameStrOffset = nn.get("nameStrOffset").asLong()
                n.byteSize = nn.get("byteSize").asInt()
                n.index = nn.get("index").asLong()
                n.metaFlags = nn.get("metaFlags").asLong()
                n.refTypeHash = nn.get("refTypeHash").asLong()
                nodeList.add(n)
            }
            t.typeBlob.nodes = nodeList
            val sb = tn.get("stringBuffer")
            if (sb != null) {
                t.typeBlob.stringBufferBytes = java.util.Base64.getDecoder().decode(sb.asText())
            }
            // restore ttmap-resolved nodes for external-tree types (self-contained JSON)
            val resolved = tn.get("resolvedNodes")
            if (resolved != null) {
                val rnodes = mutableListOf<com.github.jpabscale.asset4j.typetree.TypeTreeNode>()
                for (nn in resolved) {
                    val n = com.github.jpabscale.asset4j.typetree.TypeTreeNode()
                    n.version = nn.get("version").asInt()
                    n.level = nn.get("level").asInt()
                    n.typeFlags = nn.get("typeFlags").asInt()
                    n.typeStrOffset = nn.get("typeStrOffset").asLong()
                    n.nameStrOffset = nn.get("nameStrOffset").asLong()
                    n.byteSize = nn.get("byteSize").asInt()
                    n.index = nn.get("index").asLong()
                    n.metaFlags = nn.get("metaFlags").asLong()
                    n.refTypeHash = nn.get("refTypeHash").asLong()
                    rnodes.add(n)
                }
                t.typeBlob.nodes = rnodes
                t.typeBlobIsDefinition = true
                val rsb = tn.get("resolvedStringBuffer")
                if (rsb != null) {
                    t.typeBlob.stringBufferBytes = java.util.Base64.getDecoder().decode(rsb.asText())
                }
            }
            af.metadata.typeTreeTypes.add(t)
        }

        // ref types
        af.metadata.refTypes = mutableListOf()
        val refTypes = root.get("RefTypes")
        for (rn in refTypes) {
            val r = com.github.jpabscale.asset4j.typetree.TypeTreeType()
            r.typeId = rn.get("ClassId").asInt()
            r.scriptTypeIndex = rn.get("ScriptTypeIndex").asInt()
            r.isRefType = true
            r.typeBlobIsDefinition = true
            val tref = com.github.jpabscale.asset4j.serializedfile.AssetTypeReference(
                rn.get("ClassName").asText(),
                rn.get("Namespace").asText(),
                rn.get("AsmName").asText(),
            )
            r.typeReference = tref
            r.typeBlob = com.github.jpabscale.asset4j.typetree.TypeTreeBlob()
            val nodes = mutableListOf<com.github.jpabscale.asset4j.typetree.TypeTreeNode>()
            for (nn in rn.get("nodes")) {
                val n = com.github.jpabscale.asset4j.typetree.TypeTreeNode()
                n.version = nn.get("version").asInt()
                n.level = nn.get("level").asInt()
                n.typeFlags = nn.get("typeFlags").asInt()
                n.typeStrOffset = nn.get("typeStrOffset").asLong()
                n.nameStrOffset = nn.get("nameStrOffset").asLong()
                n.byteSize = nn.get("byteSize").asInt()
                n.index = nn.get("index").asLong()
                n.metaFlags = nn.get("metaFlags").asLong()
                n.refTypeHash = nn.get("refTypeHash").asLong()
                nodes.add(n)
            }
            r.typeBlob.nodes = nodes
            val sb = rn.get("stringBuffer")
            if (sb != null) {
                r.typeBlob.stringBufferBytes = java.util.Base64.getDecoder().decode(sb.asText())
            }
            af.metadata.refTypes.add(r)
        }

        // objects: decode Data back to bytes via the type tree (or opaque base64 when no tree)
        val objects = root.get("Objects")
        af.metadata.assetInfos = mutableListOf()
        val refMan = com.github.jpabscale.asset4j.value.RefTypeManager()
        refMan.fromTypeTree(af.metadata)
        for (on in objects) {
            val info = AssetFileInfo()
            info.pathId = on.get("PathId").asLong()
            info.typeIdOrIndex = on.get("TypeId").asInt()
            info.typeId = on.get("ClassId").asInt()
            info.scriptTypeIndex = on.get("ScriptIndex").asInt()
            val dataNode = on.get("Data")
            // decode JSON-tree Data back to bytes via its type tree whenever the Data node
            // is an object (toNode decoded it); opaque base64 strings pass through verbatim.
            // Not gated on typeTreeEnabled: external-tree files (format >= 23) carry the
            // ttmap-resolved nodes in the JSON even when the file itself has no tree.
            if (dataNode != null && !dataNode.isTextual) {
                val typeTree = af.metadata.getTypeTreeType(info, af.header.version)
                    ?: throw IllegalStateException("no type tree for object ${info.pathId} (typeId ${info.typeIdOrIndex})")
                val template = com.github.jpabscale.asset4j.value.AssetTypeTemplateField()
                // Mirror toNode's decode (fromTypeBlob for ttmap-resolved blobs) so the
                // kAlign metaFlags computed by computeAligned survive the re-encode; the
                // raw type-tree metaFlags for ttmap-derived blobs lack the bool/int8/int16
                // padding that Unity's serializer emits (e.g. MonoBehaviour m_Enabled).
                template.fromTypeBlob(typeTree.typeBlob)
                val vf = ValueFieldJson.fromNode(dataNode, template, refMan)
                info.replacer = com.github.jpabscale.asset4j.serializedfile.ContentReplacerFromBuffer(
                    TypeTreeHelper.write(vf)
                )
            } else {
                info.replacer = com.github.jpabscale.asset4j.serializedfile.ContentReplacerFromBuffer(
                    java.util.Base64.getDecoder().decode(dataNode.asText())
                )
            }
            af.metadata.assetInfos.add(info)
        }

        // scripts + externals
        af.metadata.scriptTypes = mutableListOf()
        val scripts = root.get("ScriptTypes")
        for (sn in scripts) {
            val p = com.github.jpabscale.asset4j.serializedfile.AssetPPtr(
                sn.get("LocalSerializedFileIndex").asInt(),
                sn.get("LocalIdentifierInFile").asLong(),
            )
            af.metadata.scriptTypes.add(p)
        }
        af.metadata.externals = mutableListOf()
        val externals = root.get("Externals")
        for (en in externals) {
            val e = com.github.jpabscale.asset4j.serializedfile.AssetsFileExternal()
            e.pathName = en.get("Path").asText()
            e.originalPathName = if (en.hasNonNull("OriginalPath")) en.get("OriginalPath").asText() else e.pathName
            e.guid = com.github.jpabscale.asset4j.io.GUID128.tryParse(en.get("Guid").asText()) ?: com.github.jpabscale.asset4j.io.GUID128()
            e.type = com.github.jpabscale.asset4j.serializedfile.AssetsFileExternalType.entries[en.get("Type").asInt()]
            af.metadata.externals.add(e)
        }
    }
}

// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MonoScriptRegistryTest {

    private fun mpRoot(): Path? = System.getenv("MELONPLAYGROUND_DIR")?.let { Path.of(it) }

    private fun ttmap(): com.github.jpabscale.asset4j.ttmap.Ttmap {
        val root = mpRoot()
        val ttmapFile = if (root != null) {
            root.resolve("asset4j.ttmap")
        } else {
            Path.of(System.getenv("MELONPLAYGROUND_TTMAP") ?: "/tmp/opencode/mp.ttmap")
        }
        if (!Files.isRegularFile(ttmapFile)) {
            throw IllegalStateException("ttmap not found at $ttmapFile (set MELONPLAYGROUND_DIR with a generated ttmap)")
        }
        return Files.newInputStream(ttmapFile).use { com.github.jpabscale.asset4j.ttmap.Ttmap.read(it) }
    }

    @Test
    fun monoScriptBuiltinTreeDecodesNameFields() {
        val root = mpRoot()
        if (root == null || !Files.isRegularFile(root.resolve("asset4j.ttmap"))) {
            return // MelonPlayground fixtures not present; offline/CI skip
        }
        val t = ttmap()
        val tree = t.types.builtin["115"]
        assertTrue(tree != null, "ttmap has builtin MonoScript (115) tree")

        // the MonoScript tree must carry the identity fields
        val names = tree!!.nodes.map { it.name }
        assertTrue("m_ClassName" in names, "m_ClassName present")
        assertTrue("m_Namespace" in names, "m_Namespace present")
        assertTrue("m_AssemblyName" in names, "m_AssemblyName present")

        // decode a real MelonPlayground MonoScript object (pathId 77) with the builtin
        // tree. Byte order verified against UnityPy + the tthm reference:
        //   m_Name, m_ExecutionOrder, m_PropertiesHash, m_ClassName, m_Namespace, m_AssemblyName
        val data = ByteArray(136).apply {
            val bb = java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            fun putString(offset: Int, s: String): Int {
                val b = s.toByteArray()
                bb.putInt(offset, b.size)
                System.arraycopy(b, 0, this, offset + 4, b.size)
                return offset + 4 + b.size
            }
            var off = putString(0, "MobileAdsEventExecutor")
            off = (off + 3) / 4 * 4
            bb.putInt(off, 0) // m_ExecutionOrder
            off += 4
            // m_PropertiesHash: 16 zero bytes
            off += 16
            off = putString(off, "MobileAdsEventExecutor") // m_ClassName
            off = (off + 3) / 4 * 4
            off = putString(off, "GoogleMobileAds.Common") // m_Namespace
            off = (off + 3) / 4 * 4
            putString(off, "GoogleMobileAds.Common.dll") // m_AssemblyName
        }
        val blob = com.github.jpabscale.asset4j.ttmap.TtmapResolver.toBlob(tree)

        assertTrue(blob != null)
        val vf = com.github.jpabscale.asset4j.value.TypeTreeHelper.readBytes(data, blob!!, null)
        assertEquals("GoogleMobileAds.Common", vf["m_Namespace"].asString)
        assertEquals("GoogleMobileAds.Common.dll", vf["m_AssemblyName"].asString)
        assertEquals("MobileAdsEventExecutor", vf["m_ClassName"].asString)
    }

    @Test
    fun registryResolvesByNameKey() {
        val registry = MonoScriptRegistry()
        registry.put(
            "globalgamemanagers.assets", 77L,
            com.github.jpabscale.asset4j.serializedfile.AssetTypeReference(
                "Player", "MyGame", "Assembly-CSharp"),
        )
        val ref = registry.resolve("globalgamemanagers.assets", 77L)
        assertEquals("Player", ref!!.className)
        assertEquals("Assembly-CSharp:MyGame.Player", "${ref.asmName}:${ref.nameSpace}.${ref.className}")
    }

    /**
     * End-to-end IL2CPP decode (plan §2.7 name resolution): with the generated ttmap,
     * `AssetService` decodes the game's MonoBehaviours (which carry no embedded tree and
     * no scriptIds) by resolving each via its MonoScript object's assembly:namespace.class,
     * and the JSON round-trips value-identically. Gated on MELONPLAYGROUND_DIR.
     */
    @Test
    fun bundleMonoBehavioursDecodeAndRoundTrip() {
        val root = mpRoot() ?: return
        val data = root.resolve("data.unity3d")
        val ttmapFile = root.resolve("asset4j.ttmap")
        if (!Files.isRegularFile(data) || !Files.isRegularFile(ttmapFile)) return

        val ttmapName = ttmapFile.toString()
        val node = AssetService.toJsonNode(data, ttmapName)
        assertEquals("asset4j.AssetBundle", node.get("\$type").asText())

        // the largest serialized file in the bundle has MonoBehaviours; some must decode
        var decodedMono = 0
        var totalMono = 0
        for (f in node.get("Files")) {
            val asset = f.get("Asset") ?: continue
            for (o in asset.get("Objects")) {
                if (o.get("ClassId").asInt() != com.github.jpabscale.asset4j.typetree.AssetClassID.MonoBehaviour.id) continue
                totalMono++
                if (!o.get("Data").isTextual) decodedMono++
            }
        }
        assertTrue(totalMono > 0, "bundle contains MonoBehaviours")
        assertTrue(decodedMono > 0, "MonoBehaviours decoded via MonoScript name resolution ($decodedMono/$totalMono)")
        assertTrue(decodedMono.toDouble() / totalMono > 0.5, "majority decoded: $decodedMono/$totalMono")

        // round-trip: re-encode then re-decode, values must be identical
        val rebuilt = AssetService.fromJsonNode(node, ttmapName)
        val tmp = java.nio.file.Files.createTempFile("rt", ".unity3d")
        java.nio.file.Files.write(tmp, rebuilt)
        val rebuiltNode = AssetService.toJsonNode(tmp, ttmapName)
        val origFiles = node.get("Files")
        val rebuiltFiles = rebuiltNode.get("Files")
        assertEquals(origFiles.size(), rebuiltFiles.size())
        for (i in 0 until origFiles.size()) {
            val oa = origFiles.get(i).get("Asset")
            val ob = rebuiltFiles.get(i).get("Asset")
            if (oa == null || ob == null) continue
            val oaObjects = oa.get("Objects")
            val obObjects = ob.get("Objects")
            assertEquals(oaObjects.size(), obObjects.size(), "file $i object count")
            for (j in 0 until oaObjects.size()) {
                assertEquals(oaObjects.get(j), obObjects.get(j), "file $i object $j")
            }
        }
    }

    /**
     * Standalone SerializedFile (no bundle) resolution: a stripped Mono game's level file
     * has MonoBehaviours whose MonoScripts live in sibling externals
     * (globalgamemanagers.assets). AssetService must build the registry from those
     * siblings so the MonoBehaviours decode. Gated on the WarmSnow game directory.
     */
    @Test
    fun standaloneFileMonoBehavioursDecodeViaSiblingExternals() {
        val gameDir = System.getenv("WARMSNOW_DIR") ?: return
        val level = Path.of(gameDir).resolve("level0")
        val ggm = Path.of(gameDir).resolve("globalgamemanagers.assets")
        val ttmap = Path.of(gameDir).resolve("warmsnow.ttmap")
        if (!Files.isRegularFile(level) || !Files.isRegularFile(ggm) || !Files.isRegularFile(ttmap)) return

        val node = AssetService.toJsonNode(level, ttmap.toString())
        assertEquals("asset4j.SerializedFile", node.get("\$type").asText())
        val objs = node.get("Objects")
        var total = 0
        var decoded = 0
        for (o in objs) {
            if (o.get("ClassId").asInt() != com.github.jpabscale.asset4j.typetree.AssetClassID.MonoBehaviour.id) continue
            total++
            if (!o.get("Data").isTextual) decoded++
        }
        assertTrue(total > 0, "level contains MonoBehaviours")
        assertTrue(decoded > 0, "MonoBehaviours decoded via sibling externals ($decoded/$total)")
        assertTrue(decoded.toDouble() / total > 0.5, "majority decoded: $decoded/$total")

        // round-trip: binary output must preserve the MonoBehaviour bytes
        val rebuilt = AssetService.fromJsonNode(node, ttmap.toString())
        val tmp = java.nio.file.Files.createTempFile("wsrt", ".assets")
        java.nio.file.Files.write(tmp, rebuilt)
        val origNo = AssetService.toJsonNode(level, null).get("Objects")
        val rebuiltNo = AssetService.toJsonNode(tmp, null).get("Objects")
        val da = mutableMapOf<Long, com.fasterxml.jackson.databind.JsonNode>()
        for (o in origNo) da[o.get("PathId").asLong()] = o
        val db = mutableMapOf<Long, com.fasterxml.jackson.databind.JsonNode>()
        for (o in rebuiltNo) db[o.get("PathId").asLong()] = o
        var rawDiffs = 0
        for ((pid, o) in da) {
            if (o.get("ClassId").asInt() != com.github.jpabscale.asset4j.typetree.AssetClassID.MonoBehaviour.id) continue
            if (db[pid]?.get("Data")?.asText() != o.get("Data").asText()) rawDiffs++
        }
        assertEquals(0, rawDiffs, "MonoBehaviour raw bytes preserved on round-trip")
    }
}

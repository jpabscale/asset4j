// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.ttmapgen

import com.github.jpabscale.asset4j.ttmap.Ttmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TthmGeneratorTest {

    private fun corpusDir(): Path {
        val sysProp = System.getProperty("testassets.dir")
        return if (sysProp != null) {
            Path.of(sysProp)
        } else {
            Path.of("src/test/resources/testassets").toAbsolutePath()
        }
    }

    @Test
    fun generateFromV23Extracted() {
        val base = corpusDir().resolve("TestCommon/Data/AssetBundleTypeTreeVariations/v23_extracted")
        val typetreeBundle = base.resolve("AssetBundle.typetreedata")
        val serializedFile = base.resolve("monoscriptbundle.serializedfile")
        if (!Files.isRegularFile(typetreeBundle) || !Files.isRegularFile(serializedFile)) return

        val blobs = TthmGenerator.extractBlobs(Files.readAllBytes(typetreeBundle))
        assertTrue(blobs.size >= 2, "expected multiple tthm blobs, got ${blobs.size}")

        val ttmap = TthmGenerator.generate(
            Files.readAllBytes(serializedFile),
            blobs,
            "1.0.0",
        )
        // two types in the file: MonoBehaviour (115) and another native type
        assertEquals("6000.6.0a1", ttmap.unityVersion)
        assertTrue(ttmap.types.builtin.isNotEmpty() || ttmap.types.script.isNotEmpty(), "ttmap has entries")

        // ttmap round-trips (gzip + Jackson)
        val bytes = Ttmap.toBytes(ttmap)
        val reloaded = Ttmap.fromBytes(bytes)
        assertEquals(ttmap, reloaded)

        // every entry's nodes carry resolved inline strings (no offsets)
        for (entry in ttmap.types.builtin.values + ttmap.types.script.values + ttmap.types.scriptIds.values) {
            assertTrue(entry.nodes.isNotEmpty(), "entry has nodes")
            assertTrue(entry.nodes[0].type.isNotEmpty(), "type string resolved")
        }
    }

    @Test
    fun ttmapResolvesExternalFileRoundTrip() {
        val base = corpusDir().resolve("TestCommon/Data/AssetBundleTypeTreeVariations/v23_extracted")
        val typetreeBundle = base.resolve("AssetBundle.typetreedata")
        val serializedFile = base.resolve("monoscriptbundle.serializedfile")
        if (!Files.isRegularFile(typetreeBundle) || !Files.isRegularFile(serializedFile)) return

        val blobs = TthmGenerator.extractBlobs(Files.readAllBytes(typetreeBundle))
        val ttmap = TthmGenerator.generate(Files.readAllBytes(serializedFile), blobs, "1.0.0")

        // external-tree file decodes through the ttmap and round-trips value-identically
        val node = com.github.jpabscale.asset4j.api.AssetService.toJsonNode(serializedFile, writeTtmap(ttmap))
        assertEquals("asset4j.SerializedFile", node.get("\$type").asText())
        val rebuilt = com.github.jpabscale.asset4j.api.AssetService.fromJsonNode(node)
        val rebuiltNode = com.github.jpabscale.asset4j.api.AssetService.toJsonNode(
            java.nio.file.Path.of(java.io.File.createTempFile("rt", ".assets").absolutePath).also {
                java.nio.file.Files.write(it, rebuilt)
            },
            writeTtmap(ttmap),
        )
        val a = node.get("Objects")
        val b = rebuiltNode.get("Objects")
        assertEquals(a.size(), b.size())
        for (i in 0 until a.size()) {
            assertEquals(a.get(i).get("Data"), b.get(i).get("Data"), "object $i data")
        }
    }

    private fun writeTtmap(ttmap: Ttmap): String {
        val tmp = java.nio.file.Files.createTempFile("ttmap", ".json.gz")
        java.nio.file.Files.newOutputStream(tmp).use { Ttmap.write(ttmap, it) }
        return tmp.toString()
    }
}

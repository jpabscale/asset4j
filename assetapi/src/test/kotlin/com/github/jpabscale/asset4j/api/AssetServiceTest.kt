// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.api

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import com.github.jpabscale.asset4j.serializedfile.AssetsFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AssetServiceTest {

    private fun corpusDir(): Path {
        val sysProp = System.getProperty("testassets.dir")
        return if (sysProp != null) {
            Path.of(sysProp)
        } else {
            Path.of("src/test/resources/testassets").toAbsolutePath()
        }
    }

    @Test
    fun detectFormats() {
        val ggm = corpusDir().resolve("UnityRoyale/GameBuild/Unity Royale_Data/globalgamemanagers.assets")
        assertEquals(AssetService.DetectResult.SerializedFile, AssetService.detect(Files.readAllBytes(ggm)))

        val bundle = corpusDir().resolve("TestCommon/Data/AssetBundleTypeTreeVariations/v22/packedassets_assets_all.bundle")
        if (Files.isRegularFile(bundle)) {
            assertEquals(AssetService.DetectResult.UnityFSBundle, AssetService.detect(Files.readAllBytes(bundle)))
        }
    }

    @Test
    fun serializedFileJsonRoundTrip() {
        val f = corpusDir().resolve("TestCommon/Data/PlayerWithTypeTrees/sharedassets0.assets")
        if (!Files.isRegularFile(f)) return

        val bytes = AssetService.roundTrip(f)
        // the round-tripped file re-parses with the same object inventory
        val af = AssetsFile()
        af.read(AssetsFileReader(bytes))
        assertEquals(6, af.metadata.assetInfos.size)

        // and the JSON shape is correct
        val node = AssetService.toJsonNode(f)
        assertEquals("asset4j.SerializedFile", node.get("\$type").asText())
        assertEquals(6, node.get("Objects").size())
        assertEquals(5, node.get("types").size())
    }

    @Test
    fun serializedFileJsonNodeRoundTripValues() {
        val f = corpusDir().resolve("TestCommon/Data/PlayerWithTypeTrees/sharedassets0.assets")
        if (!Files.isRegularFile(f)) return

        val af = AssetsFile()
        af.read(AssetsFileReader(Files.readAllBytes(f)))

        // decode -> JSON node -> re-encode bytes for each object, must be byte-identical
        val node = AssetService.toJsonNode(f)
        val rebuilt = AssetService.fromJsonNode(node)
        val af2 = AssetsFile()
        af2.read(AssetsFileReader(rebuilt))

        assertEquals(af.metadata.assetInfos.size, af2.metadata.assetInfos.size)
        for (i in af.metadata.assetInfos.indices) {
            val orig = af.metadata.assetInfos[i]
            val rep = af2.metadata.assetInfos[i]
            assertEquals(orig.pathId, rep.pathId, "pathId $i")
            val tt = af.metadata.typeTreeTypes[orig.typeIdOrIndex]
            val tt2 = af2.metadata.typeTreeTypes[rep.typeIdOrIndex]
            val raw1 = af.getObjectData(orig.pathId)
            val raw2 = af2.getObjectData(rep.pathId)
            assertTrue(
                com.github.jpabscale.asset4j.value.TypeTreeHelper.readBytes(raw1, tt).writeToByteArray()
                    .contentEquals(com.github.jpabscale.asset4j.value.TypeTreeHelper.readBytes(raw2, tt2).writeToByteArray()),
                "object values equal for pathId ${orig.pathId}",
            )
        }
    }

    @Test
    fun opaquePassthrough() {
        val text = "plain text file, not a unity asset"
        assertEquals(AssetService.DetectResult.Opaque, AssetService.detect(text.toByteArray()))
        // opaque -> toJson -> fromJson round-trips the bytes unchanged
        val tmp = Files.createTempFile("asset4j-opaque", ".txt")
        try {
            Files.writeString(tmp, text)
            val node = AssetService.toJsonNode(tmp)
            assertEquals("asset4j.Opaque", node.get("\$type").asText())
            val back = AssetService.fromJsonNode(node)
            assertTrue(String(back, Charsets.UTF_8) == text)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun bundleJsonRoundTrip() {
        val bundle = corpusDir().resolve("TestCommon/Data/PlayerDataCompressed/data.unity3d")
        if (!Files.isRegularFile(bundle)) return

        val node = AssetService.toJsonNode(bundle)
        assertEquals("asset4j.AssetBundle", node.get("\$type").asText())
        assertEquals(5, node.get("Files").size())

        val rebuilt = AssetService.fromJsonNode(node)
        val bf = com.github.jpabscale.asset4j.bundle.AssetBundleFile()
        bf.read(AssetsFileReader(rebuilt))
        val names = bf.getAllFileNames()
        assertEquals(
            listOf("globalgamemanagers", "Resources/unity_builtin_extra", "globalgamemanagers.assets", "sharedassets0.assets", "level0"),
            names,
        )
        // inner files extract non-empty
        for (name in names) {
            assertTrue(bf.getFileData(bf.getFileIndex(name)).isNotEmpty(), "inner file $name")
        }
    }
}

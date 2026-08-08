// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.serializedfile

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AssetsFileTest {

    private fun corpusDir(): Path {
        val sysProp = System.getProperty("testassets.dir")
        return if (sysProp != null) {
            Path.of(sysProp)
        } else {
            Path.of("src/test/resources/testassets").toAbsolutePath()
        }
    }

    private fun readAssetsFile(rel: String): AssetsFile {
        val f = corpusDir().resolve(rel)
        val af = AssetsFile()
        af.read(AssetsFileReader(Files.readAllBytes(f)))
        return af
    }

    @Test
    fun parseGlobalGameManagers() {
        val af = readAssetsFile("UnityRoyale/GameBuild/Unity Royale_Data/globalgamemanagers.assets")
        assertEquals("2019.1.4f1", af.metadata.unityVersion)
        assertEquals(19L, af.metadata.targetPlatform)
        assertEquals(false, af.metadata.typeTreeEnabled)
        assertEquals(341, af.metadata.assetInfos.size)
        // first object (pathId 1) -> type index 1 -> type tree type id 150
        val first = af.metadata.assetInfos[0]
        assertEquals(1L, first.pathId)
        assertEquals(1, first.typeIdOrIndex)
        assertEquals(150, first.typeId)
        // externals present (references to other .assets)
        assertTrue(af.metadata.externals.isNotEmpty())
    }

    @Test
    fun parseSharedAssetsTypeTree() {
        // sharedassets0.assets has an embedded type tree (TypeTreeEnabled)
        val af = readAssetsFile("UnityRoyale/GameBuild/Unity Royale_Data/sharedassets0.assets")
        assertTrue(af.metadata.assetInfos.isNotEmpty())
        // all object data readable at their absolute offsets
        for (info in af.metadata.assetInfos.take(20)) {
            val data = af.getObjectData(info.pathId)
            assertEquals(info.byteSize.toInt(), data.size, "size for pathId ${info.pathId}")
        }
    }

    @Test
    fun isAssetsFileDetection() {
        val ggm = corpusDir().resolve("UnityRoyale/GameBuild/Unity Royale_Data/globalgamemanagers.assets")
        val bytes = Files.readAllBytes(ggm)
        val r = AssetsFileReader(bytes)
        assertTrue(AssetsFile.isAssetsFile(r, 0, bytes.size.toLong()))

        val bundle = corpusDir().resolve("TestCommon/Data/AssetBundleTypeTreeVariations/v22/packedassets_assets_all.bundle")
        if (Files.isRegularFile(bundle)) {
            val b = Files.readAllBytes(bundle)
            val r2 = AssetsFileReader(b)
            assertEquals(false, AssetsFile.isAssetsFile(r2, 0, b.size.toLong()))
        }
    }

    @Test
    fun saveIsIdempotent() {
        // load a file with embedded type trees, save it, re-load, save again -> identical bytes
        val f = corpusDir().resolve("TestCommon/Data/PlayerWithTypeTrees/sharedassets0.assets")
        if (!Files.isRegularFile(f)) return

        val original = Files.readAllBytes(f)
        val af = AssetsFile()
        af.read(AssetsFileReader(original))
        assertEquals(6, af.metadata.assetInfos.size)

        val w1 = AssetsFileWriter()
        af.write(w1)
        val firstSave = w1.toByteArray()

        // re-parse the first save -> values identical
        val af2 = AssetsFile()
        af2.read(AssetsFileReader(firstSave))
        assertEquals(af.metadata.assetInfos.size, af2.metadata.assetInfos.size)
        for (i in af.metadata.assetInfos.indices) {
            assertEquals(af.metadata.assetInfos[i].pathId, af2.metadata.assetInfos[i].pathId)
            assertEquals(af.metadata.assetInfos[i].typeId, af2.metadata.assetInfos[i].typeId)
        }

        // second save must be byte-identical (idempotence)
        val w2 = AssetsFileWriter()
        af2.write(w2)
        val secondSave = w2.toByteArray()
        assertTrue(secondSave.contentEquals(firstSave), "second save must be byte-identical")
    }

    @Test
    fun saveLoadRoundTripPreservesObjects() {
        val f = corpusDir().resolve("TestCommon/Data/PlayerWithTypeTrees/sharedassets0.assets")
        if (!Files.isRegularFile(f)) return

        val af = AssetsFile()
        af.read(AssetsFileReader(Files.readAllBytes(f)))

        val w = AssetsFileWriter()
        af.write(w)
        val saved = w.toByteArray()

        val af2 = AssetsFile()
        af2.read(AssetsFileReader(saved))
        assertEquals(af.metadata.assetInfos.size, af2.metadata.assetInfos.size)
        // every object decodes from both files
        for (info in af2.metadata.assetInfos) {
            val tt = af2.metadata.typeTreeTypes[info.typeIdOrIndex]
            val raw = af2.getObjectData(info.pathId)
            assertTrue(raw.isNotEmpty(), "object data for pathId ${info.pathId}")
            val vf = com.github.jpabscale.asset4j.value.TypeTreeHelper.readBytes(raw, tt)
            assertTrue(vf.templateField != null, "template for pathId ${info.pathId}")
        }
    }
}

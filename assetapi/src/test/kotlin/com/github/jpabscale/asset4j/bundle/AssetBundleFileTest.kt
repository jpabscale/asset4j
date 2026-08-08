// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.bundle

import com.github.jpabscale.asset4j.io.AssetsFileReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class AssetBundleFileTest {

    private fun corpusDir(): Path {
        val sysProp = System.getProperty("testassets.dir")
        return if (sysProp != null) {
            Path.of(sysProp)
        } else {
            Path.of("src/test/resources/testassets").toAbsolutePath()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun listBundles(): List<Path> {
        val root = corpusDir()
        if (!Files.isDirectory(root)) {
            return emptyList()
        }
        return Files.walk(root).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString()
                n.endsWith(".bundle") || n.endsWith(".unity3d") || n.endsWith(".ab")
            }.toList()
        }
    }

    private fun manifestKey(bundle: Path): String {
        // Oracle manifest keys are relative to the repo root: assetapi/src/test/resources/testassets/...
        return "assetapi/src/test/resources/testassets/" + bundle.toString().substringAfter("testassets/")
    }

    @Test
    fun extractDataUnity3dFiles() {
        val root = corpusDir()
        val bundle = root.resolve("TestCommon/Data/PlayerDataCompressed/data.unity3d")
        if (!Files.isRegularFile(bundle)) return // corpus not fetched

        val bytes = Files.readAllBytes(bundle)
        val bf = AssetBundleFile()
        bf.read(AssetsFileReader(bytes))

        assertEquals(AssetBundleCompressionType.LZ4, bf.getCompressionType())
        val names = bf.getAllFileNames()
        assertEquals(
            listOf(
                "globalgamemanagers",
                "Resources/unity_builtin_extra",
                "globalgamemanagers.assets",
                "sharedassets0.assets",
                "level0",
            ),
            names,
        )

        // every file extracts to a valid SerializedFile signature
        for (name in names) {
            val idx = bf.getFileIndex(name)
            val (offset, length) = bf.getFileRange(idx)
            assertTrue(offset >= 0, "offset for $name")
            assertEquals(1, bf.getFileName(idx)?.let { 1 } ?: -1, "name lookup $name")
            val data = bf.getFileData(idx)
            assertEquals(length.toInt(), data.size, "size $name")
            assertTrue(data.size >= 21, "file too small $name")
        }
    }

    @Test
    fun repackRoundTripDataUnity3d() {
        val root = corpusDir()
        val bundle = root.resolve("TestCommon/Data/PlayerDataCompressed/data.unity3d")
        if (!Files.isRegularFile(bundle)) return

        val bf = AssetBundleFile()
        bf.read(AssetsFileReader(Files.readAllBytes(bundle)))
        val originalFiles = bf.getAllFileNames()
        val originalData = mutableMapOf<String, ByteArray>()
        for (name in originalFiles) {
            originalData[name] = bf.getFileData(bf.getFileIndex(name))
        }

        // repack (write) -> re-read -> extract again, must be byte-identical
        val w = com.github.jpabscale.asset4j.io.AssetsFileWriter()
        bf.write(w)
        val repacked = w.toByteArray()

        val bf2 = AssetBundleFile()
        bf2.read(AssetsFileReader(repacked))
        val files2 = bf2.getAllFileNames()
        assertEquals(originalFiles, files2)
        for (name in originalFiles) {
            val data2 = bf2.getFileData(bf2.getFileIndex(name))
            assertEquals(originalData[name]!!.size, data2.size, "size $name")
            assertTrue(originalData[name]!!.contentEquals(data2), "bytes $name")
        }
    }

    @Test
    fun packLz4RoundTripDataUnity3d() {
        val root = corpusDir()
        val bundle = root.resolve("TestCommon/Data/PlayerDataCompressed/data.unity3d")
        if (!Files.isRegularFile(bundle)) return

        val bf = AssetBundleFile()
        bf.read(AssetsFileReader(Files.readAllBytes(bundle)))
        val originalData = mutableMapOf<String, ByteArray>()
        for (name in bf.getAllFileNames()) {
            originalData[name] = bf.getFileData(bf.getFileIndex(name))
        }

        // pack with LZ4 -> re-read -> extract again, must be byte-identical
        val w = com.github.jpabscale.asset4j.io.AssetsFileWriter()
        bf.pack(w, AssetBundleCompressionType.LZ4, blockDirAtEnd = true)
        val packed = w.toByteArray()

        val bf2 = AssetBundleFile()
        bf2.read(AssetsFileReader(packed))
        assertEquals(originalData.keys, bf2.getAllFileNames().toSet())
        for (name in originalData.keys) {
            val data2 = bf2.getFileData(bf2.getFileIndex(name))
            assertTrue(originalData[name]!!.contentEquals(data2), "bytes $name after pack")
        }
    }
}

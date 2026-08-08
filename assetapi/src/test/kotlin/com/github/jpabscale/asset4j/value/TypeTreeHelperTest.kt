// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.value

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.serializedfile.AssetsFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TypeTreeHelperTest {

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
    fun decodeAndRoundTripSharedAssets0() {
        val af = readAssetsFile("TestCommon/Data/PlayerWithTypeTrees/sharedassets0.assets")
        assertTrue(af.metadata.typeTreeEnabled)
        assertEquals(6, af.metadata.assetInfos.size)

        var decoded = 0
        for (info in af.metadata.assetInfos) {
            val typeTreeType = af.metadata.typeTreeTypes[info.typeIdOrIndex]
            val raw = af.getObjectData(info.pathId)

            val valueField = TypeTreeHelper.readBytes(raw, typeTreeType)
            assertTrue(valueField.templateField != null, "template for pathId ${info.pathId}")

            // byte-identical re-encode
            val reencoded = TypeTreeHelper.write(valueField)
            assertTrue(reencoded.contentEquals(raw), "byte-identity for pathId ${info.pathId}")
            decoded++
        }
        assertEquals(6, decoded)
    }

    @Test
    fun decodeMaterialFields() {
        val af = readAssetsFile("TestCommon/Data/PlayerWithTypeTrees/sharedassets0.assets")
        // pathId 2 is a Material (type 1)
        val info = af.metadata.assetInfos.first { it.pathId == 2L }
        val typeTreeType = af.metadata.typeTreeTypes[info.typeIdOrIndex]
        val valueField = TypeTreeHelper.readBytes(af.getObjectData(info.pathId), typeTreeType)

        // Material has m_Name and m_SavedProperties
        val name = valueField["m_Name"]
        assertEquals("Default-Skybox", name.asString)
        val savedProps = valueField["m_SavedProperties"]
        assertTrue(savedProps.children.isNotEmpty())
    }

    @Test
    fun decodePrimitiveTypes() {
        // Build a tiny hand-crafted TypeTreeType + bytes to exercise each primitive.
        val typeTreeType = com.github.jpabscale.asset4j.typetree.TypeTreeType()
        val blob = com.github.jpabscale.asset4j.typetree.TypeTreeBlob()
        val strBuf = buildString {
            append("root\u0000")
            append("int\u0000")
            append("float\u0000")
            append("string\u0000")
            append("Array\u0000")
            append("char\u0000")
            append("size\u0000")
            append("data\u0000")
            append("bool\u0000")
        }
        blob.stringBufferBytes = strBuf.toByteArray()
        // actual offsets into the buffer
        val offsets = mapOf(
            "root" to 0,
            "int" to 5,
            "float" to 9,
            "string" to 15,
            "Array" to 22,
            "char" to 28,
            "size" to 33,
            "data" to 38,
            "bool" to 43,
        )
        blob.nodes = mutableListOf(
            node(0, 0, "root", "root", offsets),
            node(1, 1, "int", "int", offsets),
            node(2, 1, "float", "float", offsets),
            node(3, 1, "string", "string", offsets),
            node(4, 2, "Array", "data", offsets).apply { typeFlags = 1 }, // Array flag
            node(5, 3, "int", "size", offsets),
            node(6, 3, "char", "data", offsets),
            node(7, 1, "bool", "bool", offsets),
        )
        typeTreeType.typeBlob = blob
        typeTreeType.typeBlobIsDefinition = true

        val bytes = byteArrayOf(
            0x2A, 0x00, 0x00, 0x00, // int 42
            0x00, 0x00, 0xC0.toByte(), 0x3F, // float 1.5
            0x05, 0x00, 0x00, 0x00, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), 0, 0, 0,
            0x01, // bool true
        )
        val vf = TypeTreeHelper.readBytes(bytes, typeTreeType)
        assertEquals(42, vf["int"].asInt)
        assertEquals(1.5f, vf["float"].asFloat)
        assertEquals("hello", vf["string"].asString)
        assertEquals(true, vf["bool"].asBool)
        // round-trip byte-identity (alignment padding for string -> 4-byte align)
        val out = TypeTreeHelper.write(vf)
        assertTrue(out.contentEquals(bytes), "round-trip byte-identity")
    }

    private fun node(index: Long, level: Int, type: String, name: String, offsets: Map<String, Int>): com.github.jpabscale.asset4j.typetree.TypeTreeNode {
        return com.github.jpabscale.asset4j.typetree.TypeTreeNode().apply {
            this.index = index
            this.level = level
            this.typeStrOffset = offsets[type]!!.toLong()
            this.nameStrOffset = offsets[name]!!.toLong()
        }
    }
}

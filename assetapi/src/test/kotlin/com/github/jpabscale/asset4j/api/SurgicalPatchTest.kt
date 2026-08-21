// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.api
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SurgicalPatchTest {

    /** SerializedFile surgical patch: halves a float field on one MonoBehaviour, fast. */
    @Test
    fun patchOneMonoBehaviourInFile() {
        val game = System.getenv("WARMSNOW_DIR") ?: return
        val level = Path.of(game).resolve("level0")
        val ttmap = Path.of(game).resolve("warmsnow.ttmap")
        if (!Files.isRegularFile(level) || !Files.isRegularFile(ttmap)) return

        val start = System.nanoTime()
        val out = AssetService.patchObjects(level, ttmap.toString(),
            { info -> info.pathId == 190L },
            { _, vf ->
                val stay = vf["stayTime"]
                if (stay.templateField?.valueType == com.github.jpabscale.asset4j.value.AssetValueType.Float) {
                    stay.value = com.github.jpabscale.asset4j.value.AssetTypeValue(stay.asFloat * 2f)
                }
                vf.writeToByteArray(bigEndian = false)
            })
        val ms = (System.nanoTime() - start) / 1_000_000
        println("file surgical patch took ${ms}ms, output ${out.size} bytes")

        // verify: read back object 190's stayTime from the patched file via patchObjects
        // (decode-only transform: return null so nothing is re-encoded)
        val tmp2 = Files.createTempFile("surg2", ".assets")
        Files.write(tmp2, out)
        val tmp = Files.createTempDirectory("surg")
        val tmpFile = tmp.resolve("level0")
        Files.write(tmpFile, out)
        // level0's MonoBehaviours resolve via sibling externals; co-locate them
        for (ext in listOf("globalgamemanagers.assets", "globalgamemanagers", "sharedassets0.assets")) {
            val p = Path.of(game).resolve(ext)
            if (Files.isRegularFile(p)) Files.copy(p, tmp.resolve(ext))
        }
        var stayVal = -1.0
        AssetService.patchObjects(tmpFile, ttmap.toString(),
            { info -> info.pathId == 190L },
            { _, vf2 ->
                if (stayVal < 0) stayVal = vf2["stayTime"].asDouble
                null // no re-encode; just read
            })
        println("stayTime after = " + stayVal + " (expect 10.0, orig 5.0)")
        org.junit.jupiter.api.Assertions.assertEquals(10.0, stayVal, 0.001)
    }

    /** UnityFS bundle surgical patch: edits a TextAsset's embedded JSON, byte-copies the rest. */
    @Test
    fun patchTextAssetInBundle() {
        val game = System.getenv("BLADED_DIR") ?: return
        val config = Path.of(game, "chopghost_Data/StreamingAssets/config")
        if (!Files.isRegularFile(config)) return

        val start = System.nanoTime()
        val out = AssetService.patchObjects(config, null,
            { info -> true }, // apply to every object; transform only touches MonsterData
            { _, vf ->
                val name = vf["m_Name"]
                if (name.asString == "MonsterData") {
                    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                    val jsonRoot: com.fasterxml.jackson.databind.JsonNode = mapper.readTree(vf["m_Script"].asString)
                    val json = jsonRoot as com.fasterxml.jackson.databind.node.ObjectNode
                    val mon: com.fasterxml.jackson.databind.node.ObjectNode =
                        json.get("10001") as com.fasterxml.jackson.databind.node.ObjectNode
                    mon.replace("AttackParam", com.fasterxml.jackson.databind.node.DoubleNode.valueOf(0.25))
                    vf["m_Script"].value = com.github.jpabscale.asset4j.value.AssetTypeValue(
                        mapper.writeValueAsString(json).toByteArray(), true)
                }
                vf.writeToByteArray(bigEndian = false)
            })
        val ms = (System.nanoTime() - start) / 1_000_000
        println("bundle surgical patch took ${ms}ms, output ${out.size} bytes")

        // verify: re-decode bundle, MonsterData AttackParam for 10001 halved again (0.25)
        val tmp = Files.createTempFile("surgb", ".bundle")
        Files.write(tmp, out)
        val node = AssetService.toJsonNode(tmp, null)
        var found = false
        for (f in node.get("Files")) {
            val asset = f.get("Asset") ?: continue
            for (o in asset.get("Objects")) {
                val data = o.get("Data")
                if (data != null && data.get("m_Name") != null && data.get("m_Name").asText() == "MonsterData") {
                    found = true
                    val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(data.get("m_Script").asText())
                    println("MonsterData 10001 AttackParam = " + parsed.get("10001").get("AttackParam") + " (expect 0.25)")
                    org.junit.jupiter.api.Assertions.assertEquals(0.25, parsed.get("10001").get("AttackParam").asDouble(), 0.001)
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(found, "MonsterData found")
    }
}

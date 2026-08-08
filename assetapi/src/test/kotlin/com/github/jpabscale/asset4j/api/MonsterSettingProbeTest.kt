// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.api
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MonsterSettingProbeTest {
    /** Find the scriptTypeIndex of MonsterSetting in resources.assets (metadata-only, fast). */
    @Test
    fun probeMonsterSettingScriptIndex() {
        val game = System.getenv("WARMSNOW_DIR") ?: return
        val res = Path.of(game).resolve("resources.assets")
        val ttmap = Path.of(game).resolve("warmsnow.ttmap")
        if (!Files.isRegularFile(res) || !Files.isRegularFile(ttmap)) return
        val idx = com.github.jpabscale.asset4j.api.AssetService.probeScriptTypeIndex(res, ttmap.toString(), "MonsterSetting")
        println("MonsterSetting scriptTypeIndex = " + idx)
        org.junit.jupiter.api.Assertions.assertTrue(idx >= 0, "found MonsterSetting index")
        org.junit.jupiter.api.Assertions.assertTrue(idx < 10000, "sane index")
    }

    /**
     * End-to-end surgical patch regression: the alignment computed for DLL-derived (ttmap)
     * script trees must let a MonoBehaviour whose header pads (bool m_Enabled before the
     * PPtr m_Script) decode correctly. We surgically halve MonsterSetting entries and
     * re-decode to confirm the edit landed — this is exactly what the Warm Snow easy mode
     * .sc does against the 452MB resources.assets.
     */
    @Test
    fun surgicalPatchMonsterSetting() {
        val game = System.getenv("WARMSNOW_DIR") ?: return
        val res = Path.of(game).resolve("resources.assets")
        val ttmap = Path.of(game).resolve("warmsnow.ttmap")
        if (!Files.isRegularFile(res) || !Files.isRegularFile(ttmap)) return

        val tmp = Files.createTempDirectory("mspatch")
        val patchedFile = tmp.resolve("resources.assets")
        Files.copy(res, patchedFile)
        // co-locate the external so the MonoScript for scriptTypes[503] resolves
        val gg = Path.of(game).resolve("globalgamemanagers.assets")
        if (Files.isRegularFile(gg)) Files.copy(gg, tmp.resolve(gg.fileName.toString()))

        val out = com.github.jpabscale.asset4j.api.AssetService.patchObjectsJsonByScriptName(
            patchedFile, ttmap.toString(), "MonsterSetting",
            { _, node ->
                val obj = node as com.fasterxml.jackson.databind.node.ObjectNode
                val listNode = obj.get("list")
                if (listNode != null && listNode.get("Array") != null) {
                    val arr = listNode.get("Array")
                    var i = 0
                    while (i < arr.size()) {
                        val m = arr.get(i) as com.fasterxml.jackson.databind.node.ObjectNode
                        if (m.get("MonsterHP") != null) {
                            m.put("MonsterHP", com.fasterxml.jackson.databind.node.DoubleNode.valueOf(m.get("MonsterHP").asDouble() * 0.5))
                        }
                        i++
                    }
                }
                obj
            })
        Files.write(patchedFile, out)

        // verify: MonsterSetting HP values are halved vs the original
        var origFirst = -1.0
        var patchedFirst = -1.0
        val idx = com.github.jpabscale.asset4j.api.AssetService.probeScriptTypeIndex(res, ttmap.toString(), "MonsterSetting")
        val readFirst = { src: Path ->
            var v = -1.0
            com.github.jpabscale.asset4j.api.AssetService.patchObjectsJsonByScriptName(
                src, ttmap.toString(), "MonsterSetting",
                { _, node ->
                    if (v < 0) {
                        val listNode = node.get("list")
                        if (listNode != null && listNode.get("Array") != null && listNode.get("Array").size() > 0) {
                            v = listNode.get("Array").get(0).get("MonsterHP").asDouble()
                        }
                    }
                    null
                })
            v
        }
        origFirst = readFirst(res)
        patchedFirst = readFirst(patchedFile)
        println("MonsterSetting[0].MonsterHP: orig=" + origFirst + " patched=" + patchedFirst)
        org.junit.jupiter.api.Assertions.assertTrue(origFirst > 0, "original decoded")
        org.junit.jupiter.api.Assertions.assertTrue(patchedFirst > 0, "patched decoded")
        org.junit.jupiter.api.Assertions.assertEquals(origFirst * 0.5, patchedFirst, 0.001)
    }
}

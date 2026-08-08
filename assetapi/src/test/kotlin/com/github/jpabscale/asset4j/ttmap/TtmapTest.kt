// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.ttmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TtmapTest {

    @Test
    fun roundTripByteStable() {
        val ttmap = sampleTtmap()
        val bytes1 = Ttmap.toBytes(ttmap)
        val reloaded = Ttmap.fromBytes(bytes1)
        assertEquals(ttmap, reloaded)
        val bytes2 = Ttmap.toBytes(reloaded)
        assertTrue(bytes1.contentEquals(bytes2), "save -> load -> save must be byte-stable")
    }

    @Test
    fun lookupsHitUnderBothKeys() {
        val ttmap = sampleTtmap()
        // builtin by class id
        val builtin = ttmap.types.builtin["114"]!!
        assertEquals("MonoBehaviour", builtin.nodes.first().type)

        // script by assembly:namespace.classname
        val script = ttmap.types.script["Assembly-CSharp:MyGame.Player"]!!
        assertEquals(2, script.nodes.size)

        // scriptIds by hash128 string
        val byId = ttmap.types.scriptIds["0123456789abcdef0123456789abcdef"]!!
        assertEquals(script, byId)
    }

    @Test
    fun gzipHeaderPresent() {
        val bytes = Ttmap.toBytes(sampleTtmap())
        // gzip magic 1f 8b
        assertEquals(0x1f, bytes[0].toInt() and 0xFF)
        assertEquals(0x8b, bytes[1].toInt() and 0xFF)
    }

    private fun sampleTtmap(): Ttmap {
        return Ttmap(
            unityVersion = "2022.3.10f1",
            gameVersion = "1.0.4",
            types = TtmapTypes(
                builtin = linkedMapOf(
                    "114" to TtmapType(
                        listOf(
                            TypeTreeNodeInline(1, 0, 0, "MonoBehaviour", "Base", -1, 0, 0, 0),
                            TypeTreeNodeInline(1, 1, 0, "PPtr<MonoScript>", "m_Script", 8, 1, 0, 0),
                        )
                    ),
                ),
                script = linkedMapOf(
                    "Assembly-CSharp:MyGame.Player" to TtmapType(
                        listOf(
                            TypeTreeNodeInline(1, 0, 0, "MyGame.Player", "Base", -1, 0, 0, 0),
                            TypeTreeNodeInline(1, 1, 0, "string", "m_Name", -1, 1, 0, 0),
                        )
                    ),
                ),
                scriptIds = linkedMapOf(
                    "0123456789abcdef0123456789abcdef" to TtmapType(
                        listOf(
                            TypeTreeNodeInline(1, 0, 0, "MyGame.Player", "Base", -1, 0, 0, 0),
                            TypeTreeNodeInline(1, 1, 0, "string", "m_Name", -1, 1, 0, 0),
                        )
                    ),
                ),
            ),
        )
    }
}

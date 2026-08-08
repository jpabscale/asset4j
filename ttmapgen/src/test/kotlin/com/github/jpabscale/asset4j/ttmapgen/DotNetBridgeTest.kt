// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.ttmapgen

import com.github.jpabscale.asset4j.ttmap.Ttmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DotNetBridgeTest {

    /** dotnet binary: PATH first (GitHub Actions setup-dotnet), then ~/.dotnet/dotnet. */
    private fun dotnetBin(): Path? {
        System.getenv("PATH").split(java.io.File.pathSeparator).forEach { dir ->
            val cand = Path.of(dir, if (System.getenv("COMSPEC") != null) "dotnet.exe" else "dotnet")
            if (Files.isExecutable(cand)) return cand
        }
        val home = Path.of(System.getProperty("user.home"), ".dotnet", "dotnet")
        return if (Files.isExecutable(home)) home else null
    }

    private fun harnessDll(): Path {
        // test cwd is the module dir; repo root is the parent
        val module = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val repo = if (module.fileName.toString() == "ttmapgen") module.parent else module
        for (c in listOf(
            repo.resolve("ttmapgen/dotnet-harness/bin/Debug/net10.0/ttmapgen-harness.dll"),
            repo.resolve("ttmapgen/dotnet-harness/bin/Release/net10.0/ttmapgen-harness.dll"),
        )) {
            if (Files.isRegularFile(c)) return c
        }
        throw IllegalStateException("harness not built")
    }

    private fun testManagedDir(): Path {
        // compile the toy DLL from source under the harness dir so the test is self-contained
        val module = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val repo = if (module.fileName.toString() == "ttmapgen") module.parent else module
        val src = repo.resolve("ttmapgen/dotnet-harness/testdll")
        val out = src.resolve("bin/Release/netstandard2.0")
        if (Files.isDirectory(out) && Files.isRegularFile(out.resolve("Assembly-CSharp.dll"))) {
            return out
        }
        val dotnet = dotnetBin() ?: throw IllegalStateException("dotnet not on PATH")
        val proc = ProcessBuilder(
            dotnet.toString(), "build", src.resolve("TestLib.csproj").toString(), "-c", "Release",
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.readBytes()
        val exit = proc.waitFor()
        if (exit != 0) {
            throw IllegalStateException("test DLL build failed: ${String(log, Charsets.UTF_8)}")
        }
        return out
    }

    @Test
    fun dllToTtmapViaHarness() {
        val dotnet = dotnetBin()
        if (dotnet == null) return // .NET SDK not available (CI/act); offline test
        val managedDir = testManagedDir()
        if (!Files.isDirectory(managedDir)) return // test DLL not built

        val request = DotNetBridge.mapper.createObjectNode()
        request.put("mode", "dll")
        request.put("managedPath", managedDir.toString())
        request.put("unityVersion", "2019.1.4f1")
        val arr = request.putArray("classes")
        arr.addObject().put("assembly", "Assembly-CSharp").put("namespace", "MyGame").put("className", "Player")
        arr.addObject().put("assembly", "Assembly-CSharp").put("namespace", "MyGame").put("className", "Weapon")

        val node = DotNetBridge.run(harnessDll(), request)
        val ttmap = DotNetBridge.toTtmap(node, "test")

        assertEquals("2019.1.4f1", ttmap.unityVersion)
        assertTrue(ttmap.types.script.containsKey("Assembly-CSharp:MyGame.Player"), "Player in script map")
        assertTrue(ttmap.types.script.containsKey("Assembly-CSharp:MyGame.Weapon"), "Weapon in script map")

        val player = ttmap.types.script["Assembly-CSharp:MyGame.Player"]!!
        val fieldNames = player.nodes.map { it.name }
        assertTrue("m_Name" in fieldNames, "m_Name field synthesized")
        assertTrue("m_Level" in fieldNames, "m_Level field synthesized")
        assertTrue("m_Speed" in fieldNames, "m_Speed field synthesized")
        assertTrue("m_IsActive" in fieldNames, "m_IsActive field synthesized")
        // MonoBehaviour base fields must be prepended (C# reference: CLDB base + script fields)
        assertTrue("m_GameObject" in fieldNames, "m_GameObject base field")
        assertTrue("m_Script" in fieldNames, "m_Script base field")

        // ttmap round-trips
        val bytes = Ttmap.toBytes(ttmap)
        assertEquals(ttmap, Ttmap.fromBytes(bytes))
    }

    /**
     * Auto-enumeration (plan §4.4): with an empty class list, the harness must discover
     * MonoBehaviour/ScriptableObject subclasses itself (walking base chains) plus every
     * [Serializable] class reachable transitively from their fields.
     */
    @Test
    fun dllAutoEnumerateViaHarness() {
        val dotnet = dotnetBin()
        if (dotnet == null) return // .NET SDK not available (CI/act); offline test
        val managedDir = testManagedDir()
        if (!Files.isDirectory(managedDir)) return // test DLL not built

        val request = DotNetBridge.mapper.createObjectNode()
        request.put("mode", "dll")
        request.put("managedPath", managedDir.toString())
        request.put("unityVersion", "2019.1.4f1")
        request.putArray("classes") // empty -> harness auto-enumerates

        val node = DotNetBridge.run(harnessDll(), request)
        val ttmap = DotNetBridge.toTtmap(node, "test")

        // MonoBehaviour + ScriptableObject subclasses found via base-chain walk
        assertTrue(ttmap.types.script.containsKey("Assembly-CSharp:MyGame.Player"), "Player (ScriptableObject) auto-enumerated")
        assertTrue(ttmap.types.script.containsKey("Assembly-CSharp:MyGame.Weapon"), "Weapon (MonoBehaviour) auto-enumerated")
        // [Serializable] data class reached transitively from Player.m_Data
        assertTrue(ttmap.types.script.containsKey("Assembly-CSharp:MyGame.PlayerData"), "PlayerData reached transitively")

        // every tree carries the MonoBehaviour base (matching on-disk serialization)
        val player = ttmap.types.script["Assembly-CSharp:MyGame.Player"]!!
        val names = player.nodes.map { it.name }
        assertTrue("m_GameObject" in names, "m_GameObject base on auto-enumerated tree")
        assertTrue("m_Name" in names, "m_Name base on auto-enumerated tree")
        assertTrue("m_Level" in names, "m_Level field synthesized")
    }

    /**
     * IL2CPP (plan §4.5): drives the harness against a real game's global-metadata.dat +
     * libil2cpp.so and validates the synthesized script trees. Gated on the game files
     * being present (they are not part of the CI corpus; see docs). Set
     * MELONPLAYGROUND_DIR to the extracted APK root containing
     * assets/bin/Data/Managed/Metadata/global-metadata.dat and lib/arm64-v8a/libil2cpp.so.
     */
    @Test
    fun il2cppToTtmapViaHarness() {
        val dotnet = dotnetBin()
        if (dotnet == null) return // .NET SDK not available (CI/act); offline test

        val root = System.getenv("MELONPLAYGROUND_DIR")
            ?: return // real game files not present; offline/CI skip
        val metadata = Path.of(root, "assets/bin/Data/Managed/Metadata/global-metadata.dat")
        val gameAsm = Path.of(root, "lib/arm64-v8a/libil2cpp.so")
        if (!Files.isRegularFile(metadata) || !Files.isRegularFile(gameAsm)) return

        val request = DotNetBridge.mapper.createObjectNode()
        request.put("mode", "il2cpp")
        request.put("globalMetadata", metadata.toString())
        request.put("gameAssembly", gameAsm.toString())
        request.put("unityVersion", "2022.3.10f1")
        request.put("gameVersion", "13.1")
        request.putArray("classes")

        val node = DotNetBridge.run(harnessDll(), request)
        val ttmap = DotNetBridge.toTtmap(node, "13.1")

        assertTrue(ttmap.types.script.size >= 100, "expected a large script set, got ${ttmap.types.script.size}")
        // the ttmap must carry the builtin MonoScript tree (runtime name resolution)
        assertTrue(ttmap.types.builtin.containsKey("115"), "builtin MonoScript (115) tree present")
        // every script tree must carry the MonoBehaviour/ScriptableObject base fields
        val anyWithoutBase = ttmap.types.script.values.any { t ->
            val names = t.nodes.map { it.name }
            "m_Script" !in names
        }
        assertTrue(!anyWithoutBase, "all script trees must include the m_Script base field")

        // a known MelonPlayground class decodes with its own fields
        val bullets = ttmap.types.script.keys.filter { it.endsWith(".Bullet") }
        assertTrue(bullets.isNotEmpty(), "expected .Bullet script classes in ttmap, got: ${ttmap.types.script.keys}")

        // ttmap round-trips
        val bytes = Ttmap.toBytes(ttmap)
        assertEquals(ttmap, Ttmap.fromBytes(bytes))
    }
}

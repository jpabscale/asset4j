// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port)
package com.github.jpabscale.asset4j.ttmapgen

import com.github.jpabscale.asset4j.ttmap.Ttmap
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        when (args[0].lowercase()) {
            // tthm <typetreedata.bundle> <serializedfile> <output.ttmap> [gameVersion]
            "tthm" -> {
                if (args.size < 4) {
                    printUsage()
                    return
                }
                val gameVersion = if (args.size >= 5) args[4] else "unknown"
                val blobs = TthmGenerator.extractBlobs(Files.readAllBytes(Path.of(args[1])))
                val ttmap = TthmGenerator.generate(
                    Files.readAllBytes(Path.of(args[2])),
                    blobs,
                    gameVersion,
                )
                Files.newOutputStream(suffixedOutput(args[3], gameVersion)).use { Ttmap.write(ttmap, it) }
                println("wrote ${suffixedOutput(args[3], gameVersion)} (${blobs.size} blobs, ${ttmap.types.builtin.size} builtin, " +
                    "${ttmap.types.script.size} script, ${ttmap.types.scriptIds.size} scriptIds)")
                return
            }
            // dll <managedDir> <output.ttmap> <unityVersion> <gameVersion> [harnessDll]
            "dll" -> {
                if (args.size < 5) {
                    printUsage()
                    return
                }
                val managedDir = Path.of(args[1])
                val unityVersion = args[3]
                val gameVersion = args[4]
                val harnessDll = harnessPath(if (args.size >= 6) args[5] else null)
                val classes = discoverScriptClasses(managedDir)
                val request = DotNetBridge.mapper.createObjectNode()
                request.put("mode", "dll")
                request.put("managedPath", managedDir.toString())
                request.put("unityVersion", unityVersion)
                request.put("gameVersion", gameVersion)
                val arr = request.putArray("classes")
                for (c in classes) {
                    arr.addObject()
                        .put("assembly", c.first)
                        .put("namespace", c.second)
                        .put("className", c.third)
                }
                val node = DotNetBridge.run(harnessDll, request)
                val ttmap = DotNetBridge.toTtmap(node, gameVersion)
                Files.newOutputStream(suffixedOutput(args[2], gameVersion)).use { Ttmap.write(ttmap, it) }
                println("wrote ${suffixedOutput(args[2], gameVersion)} (${ttmap.types.script.size} script types, unity $unityVersion, game $gameVersion)")
                return
            }
            // il2cpp <gameAssembly> <globalMetadata> <output.ttmap> <unityVersion> <gameVersion> [harnessDll]
            "il2cpp" -> {
                if (args.size < 6) {
                    printUsage()
                    return
                }
                val unityVersion = args[4]
                val gameVersion = args[5]
                val harnessDll = harnessPath(if (args.size >= 7) args[6] else null)
                val request = DotNetBridge.mapper.createObjectNode()
                request.put("mode", "il2cpp")
                request.put("globalMetadata", args[2])
                request.put("gameAssembly", args[1])
                request.put("unityVersion", unityVersion)
                request.put("gameVersion", gameVersion)
                request.putArray("classes")
                val node = DotNetBridge.run(harnessDll, request)
                val ttmap = DotNetBridge.toTtmap(node, gameVersion)
                Files.newOutputStream(suffixedOutput(args[3], gameVersion)).use { Ttmap.write(ttmap, it) }
                println("wrote ${suffixedOutput(args[3], gameVersion)} (${ttmap.types.script.size} script types, unity $unityVersion, game $gameVersion)")
                return
            }
        }
    }

    printUsage()
}

private fun harnessPath(explicit: String?): Path {
    if (explicit != null) return Path.of(explicit)
    val repo = Path.of(System.getProperty("user.dir"))
    val candidates = listOf(
        repo.resolve("ttmapgen/dotnet-harness/bin/Debug/net10.0/ttmapgen-harness.dll"),
        repo.resolve("ttmapgen/dotnet-harness/bin/Release/net10.0/ttmapgen-harness.dll"),
    )
    for (c in candidates) {
        if (Files.isRegularFile(c)) return c
    }
    throw IllegalStateException(
        "ttmapgen-harness.dll not found. Build it: cd ttmapgen/dotnet-harness && dotnet build " +
            "(expects the pinned AssetsTools.NET source at /tmp/automod/AssetsTools.NET, see docs/port-tracker.md)"
    )
}

/**
 * Discovers the script classes for the generator. Reads `classes.json` from the managed
 * dir (a JSON array of {"assembly","namespace","className"}), or falls back to a
 * well-known class list for the corpus game when present.
 */
private fun discoverScriptClasses(managedDir: Path): List<Triple<String, String, String>> {
    val classesFile = managedDir.resolve("classes.json")
    if (Files.isRegularFile(classesFile)) {
        val arr = DotNetBridge.mapper.readTree(Files.readAllBytes(classesFile))
        val out = mutableListOf<Triple<String, String, String>>()
        arr.forEach { c ->
            out.add(
                Triple(
                    c.get("assembly")?.asText("Assembly-CSharp") ?: "Assembly-CSharp",
                    c.get("namespace")?.asText("") ?: "",
                    c.get("className")?.asText("") ?: "",
                )
            )
        }
        return out
    }
    return emptyList()
}

/** The output path suffixed with the game version: `name.ttmap` -> `name_<gameVersion>.ttmap`.
 *  Returns [output] unchanged when the game version is empty or "unknown". */
private fun suffixedOutput(output: String, gameVersion: String): Path {
    val p = Path.of(output)
    if (gameVersion.isEmpty() || gameVersion == "unknown") return p
    val name = p.fileName.toString()
    val base = if (name.endsWith(".ttmap")) name.removeSuffix(".ttmap") else name
    return p.resolveSibling("${base}_${gameVersion}.ttmap")
}

private fun printUsage() {
    println(
        """
        ttmapgen — ttmap generator (tthm/DLL/IL2CPP -> ttmap)
        Usage: ttmapgen <command> [args]
          tthm   <typetreedata.bundle> <serializedfile> <output.ttmap> [gameVersion]
          dll    <managedDir> <output.ttmap> <unityVersion> <gameVersion> [harnessDll]
          il2cpp <gameAssembly> <globalMetadata> <output.ttmap> <unityVersion> <gameVersion> [harnessDll]
        """.trimIndent()
    )
}

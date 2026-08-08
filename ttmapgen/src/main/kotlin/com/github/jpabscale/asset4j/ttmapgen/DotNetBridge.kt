// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port)
package com.github.jpabscale.asset4j.ttmapgen

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.jpabscale.asset4j.ttmap.Ttmap
import com.github.jpabscale.asset4j.ttmap.TtmapType
import com.github.jpabscale.asset4j.ttmap.TtmapTypes
import com.github.jpabscale.asset4j.ttmap.TypeTreeNodeInline
import java.nio.file.Files
import java.nio.file.Path

/**
 * DLL/IL2CPP → ttmap via the .NET subprocess bridge (plan §4.4/4.5). The Kotlin side
 * discovers the script classes (from the game's MonoScript assets, or a supplied list),
 * writes a request JSON, runs the C# harness (which synthesizes type trees from the game's
 * managed DLLs via MonoCecil, or from GameAssembly.dll + global-metadata.dat via Cpp2IL),
 * and parses the emitted ttmap JSON.
 */
object DotNetBridge {

    val mapper: ObjectMapper = ObjectMapper()

    /**
     * Runs the harness with the given request JSON and returns the parsed ttmap-shaped
     * JsonNode. [harnessDll] is the built `ttmapgen-harness.dll`; [dotnet] is the dotnet
     * binary (defaults to `dotnet` on PATH, or `~/.dotnet/dotnet`).
     */
    fun run(harnessDll: Path, request: ObjectNode, dotnet: String? = null): JsonNode {
        val dotnetBin = dotnet ?: System.getenv("DOTNET") ?: runCatching {
            val home = System.getProperty("user.home")
            if (Files.isExecutable(Path.of(home, ".dotnet", "dotnet"))) {
                Path.of(home, ".dotnet", "dotnet").toString()
            } else {
                "dotnet"
            }
        }.getOrNull() ?: "dotnet"

        val requestFile = Files.createTempFile("ttmapgen-harness", ".json")
        Files.writeString(requestFile, mapper.writeValueAsString(request))
        val proc = ProcessBuilder(dotnetBin, harnessDll.toString(), requestFile.toString())
            .redirectErrorStream(false)
            .start()
        val stdout = proc.inputStream.readBytes()
        val stderr = proc.errorStream.readBytes()
        val exit = proc.waitFor()
        if (exit != 0) {
            throw IllegalStateException("harness failed (exit $exit): ${String(stderr, Charsets.UTF_8)}")
        }
        return mapper.readTree(stdout)
    }

    /**
     * Converts a harness ttmap JsonNode into a [Ttmap] (adds the missing `scriptIds` map
     * and gameVersion when absent).
     */
    fun toTtmap(node: JsonNode, gameVersion: String): Ttmap {
        val builtin = linkedMapOf<String, TtmapType>()
        val script = linkedMapOf<String, TtmapType>()
        val scriptIds = linkedMapOf<String, TtmapType>()

        node.get("types")?.get("builtin")?.properties()?.forEach { (k, v) ->
            builtin[k] = parseType(v)
        }
        node.get("types")?.get("script")?.properties()?.forEach { (k, v) ->
            script[k] = parseType(v)
        }
        node.get("types")?.get("scriptIds")?.properties()?.forEach { (k, v) ->
            scriptIds[k] = parseType(v)
        }

        return Ttmap(
            unityVersion = node.get("unityVersion")?.asText() ?: "",
            gameVersion = node.get("gameVersion")?.asText() ?: gameVersion,
            types = TtmapTypes(builtin = builtin, script = script, scriptIds = scriptIds),
        )
    }

    private fun parseType(node: JsonNode): TtmapType {
        val nodes = mutableListOf<TypeTreeNodeInline>()
        node.get("nodes")?.forEach { n ->
            nodes.add(
                TypeTreeNodeInline(
                    version = n.get("version")?.asInt() ?: 0,
                    level = n.get("level")?.asInt() ?: 0,
                    typeFlags = n.get("typeFlags")?.asInt() ?: 0,
                    type = n.get("type")?.asText("") ?: "",
                    name = n.get("name")?.asText("") ?: "",
                    byteSize = n.get("byteSize")?.asInt() ?: -1,
                    index = n.get("index")?.asLong() ?: 0,
                    metaFlags = n.get("metaFlags")?.asLong() ?: 0,
                    refTypeHash = n.get("refTypeHash")?.asLong() ?: 0,
                )
            )
        }
        return TtmapType(nodes)
    }
}

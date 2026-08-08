// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port)
package com.github.jpabscale.asset4j.assetcli

import com.github.jpabscale.asset4j.api.AssetService
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        when (args[0].lowercase()) {
            // tojson <source> <destination> [ttmap]
            "tojson" -> {
                if (args.size < 3) {
                    printUsage()
                    return
                }
                val ttmapName = if (args.size >= 4) args[3] else null
                val json = AssetService.toJson(Path.of(args[1]), ttmapName)
                Files.writeString(Path.of(args[2]), json)
                return
            }
            // fromjson <source> <destination> [ttmap]
            "fromjson" -> {
                if (args.size < 3) {
                    printUsage()
                    return
                }
                val ttmapName = if (args.size >= 4) args[3] else null
                val json = Files.readString(Path.of(args[1]))
                val bytes = AssetService.fromJson(json, ttmapName)
                Files.write(Path.of(args[2]), bytes)
                return
            }
            // roundtrip <source> [destination] [ttmap]
            "roundtrip" -> {
                if (args.size < 2) {
                    printUsage()
                    return
                }
                val ttmapName = if (args.size >= 4) args[3] else null
                val bytes = AssetService.roundTrip(Path.of(args[1]), ttmapName)
                if (args.size >= 3) {
                    Files.write(Path.of(args[2]), bytes)
                } else {
                    print("roundtrip OK: ${bytes.size} bytes")
                }
                return
            }
            // diff <sourceA> <sourceB>
            "diff" -> {
                if (args.size < 3) {
                    printUsage()
                    return
                }
                val a = Files.readAllBytes(Path.of(args[1]))
                val b = Files.readAllBytes(Path.of(args[2]))
                println(if (a.contentEquals(b)) "identical" else "differ")
                return
            }
            // check <source> [ttmap]
            "check" -> {
                if (args.size < 2) {
                    printUsage()
                    return
                }
                val ttmapName = if (args.size >= 3) args[2] else null
                val detect = AssetService.detect(Files.readAllBytes(Path.of(args[1])))
                println("detected format: $detect")
                return
            }
        }
    }

    printUsage()
}

private fun printUsage() {
    println(
        """
        assetcli — lossless binary ⇄ JSON round-tripper for Unity SerializedFile assets
        Usage: assetcli <command> [args]
          tojson    <source> <destination> [ttmap]
          fromjson  <source> <destination> [ttmap]
          roundtrip <source> [destination] [ttmap]
          diff      <sourceA> <sourceB>
          check     <source> [ttmap]
        """.trimIndent()
    )
}

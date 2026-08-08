// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/AssetsFileExternal.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.serializedfile

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import com.github.jpabscale.asset4j.io.GUID128

/**
 * A reference to another file (dependency). The lowercase `resources` → `Resources`
 * normalization mirrors the C# (kept for write-back fidelity via [originalPathName]).
 */
class AssetsFileExternal {
    var virtualAssetPathName: String = ""
    var guid: GUID128 = GUID128()
    var type: AssetsFileExternalType = AssetsFileExternalType.Normal
    var pathName: String = ""
    var originalPathName: String = ""

    fun read(reader: AssetsFileReader) {
        virtualAssetPathName = reader.readNullTerminated()
        guid = GUID128(reader)
        //@parity:on EXC-001
        type = AssetsFileExternalType.entries[reader.readInt32()]
        //@parity:off EXC-001
        pathName = reader.readNullTerminated()
        originalPathName = pathName

        if (pathName == "resources/unity_builtin_extra") {
            pathName = "Resources/unity_builtin_extra"
        } else if (pathName == "library/unity default resources" || pathName == "Library/unity default resources") {
            pathName = "Resources/unity default resources"
        } else if (pathName == "library/unity editor resources" || pathName == "Library/unity editor resources") {
            pathName = "Resources/unity editor resources"
        }
    }

    fun write(writer: AssetsFileWriter) {
        writer.writeNullTerminated(virtualAssetPathName)
        guid.write(writer)
        writer.writeInt32(type.ordinal)
        var assetPathTemp = pathName
        if ((pathName == "Resources/unity_builtin_extra" ||
                pathName == "Resources/unity default resources" ||
                pathName == "Resources/unity editor resources") &&
            originalPathName.isNotEmpty()
        ) {
            assetPathTemp = originalPathName
        }
        writer.writeNullTerminated(assetPathTemp)
    }

    fun getSize(): Long {
        var size: Long = 0
        size += virtualAssetPathName.length + 1
        size += 16
        size += 4

        if ((pathName == "Resources/unity_builtin_extra" ||
                pathName == "Resources/unity default resources" ||
                pathName == "Resources/unity editor resources") &&
            originalPathName.isNotEmpty()
        ) {
            size += originalPathName.length + 1
        } else {
            size += pathName.length + 1
        }

        return size
    }
}

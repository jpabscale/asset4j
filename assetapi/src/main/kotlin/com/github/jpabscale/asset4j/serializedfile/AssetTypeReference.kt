// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/AssetsTypeReference.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.serializedfile

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter

/**
 * Assembly/type reference used by ref-type entries in the metadata.
 */
class AssetTypeReference {
    var className: String = ""
    var nameSpace: String = ""
    var asmName: String = ""

    constructor()

    constructor(className: String, nameSpace: String, asmName: String) {
        this.className = className
        this.nameSpace = nameSpace
        this.asmName = asmName
    }

    fun readMetadata(reader: AssetsFileReader) {
        className = reader.readNullTerminated()
        nameSpace = reader.readNullTerminated()
        asmName = reader.readNullTerminated()
    }

    fun readAsset(reader: AssetsFileReader) {
        className = reader.readCountStringInt32(); reader.align()
        nameSpace = reader.readCountStringInt32(); reader.align()
        asmName = reader.readCountStringInt32(); reader.align()
    }

    fun writeMetadata(writer: AssetsFileWriter) {
        writer.writeNullTerminated(className)
        writer.writeNullTerminated(nameSpace)
        writer.writeNullTerminated(asmName)
    }

    fun writeAsset(writer: AssetsFileWriter) {
        writer.writeCountStringInt32(className); writer.align()
        writer.writeCountStringInt32(nameSpace); writer.align()
        writer.writeCountStringInt32(asmName); writer.align()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is AssetTypeReference)
            return false
        return className == other.className &&
            nameSpace == other.nameSpace &&
            asmName == other.asmName
    }

    override fun hashCode(): Int {
        var hash = 17
        hash = hash * 23 + className.hashCode()
        hash = hash * 23 + nameSpace.hashCode()
        hash = hash * 23 + asmName.hashCode()
        return hash
    }

    companion object {
        val TERMINUS = AssetTypeReference("Terminus", "UnityEngine.DMAT", "FAKE_ASM")
    }
}

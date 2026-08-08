// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsBundleFileFormat/AssetBundleDirectoryInfo.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.bundle

/**
 * A file entry in the bundle directory.
 */
class AssetBundleDirectoryInfo {
    var offset: Long = 0
    var decompressedSize: Long = 0
    var flags: Long = 0
    var name: String = ""
    var replacer: AssetBundleReplacer? = null

    val isSerialized: Boolean get() = (flags and 4) != 0L

    companion object {
        fun create(name: String, isSerialized: Boolean): AssetBundleDirectoryInfo {
            return AssetBundleDirectoryInfo().apply {
                offset = -1
                decompressedSize = 0
                flags = if (isSerialized) 0x04L else 0x00L
                this.name = name
            }
        }
    }
}

// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsBundleFileFormat/AssetBundleBlockInfo.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.bundle

/**
 * A compression block in the bundle's data region.
 */
class AssetBundleBlockInfo {
    var decompressedSize: Long = 0
    var compressedSize: Long = 0
    var flags: Int = 0

    fun getCompressionType(): Int = flags and 0x3F
}

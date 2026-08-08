// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsBundleFileFormat/AssetBundleFSHeaderFlags.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.bundle

/**
 * UnityFS header flags (mirrors the C# `[Flags]` enum). First 6 bits: compression mode.
 */
class AssetBundleFSHeaderFlags(val value: Long) {
    companion object {
        const val NONE = 0x00L
        const val LZMA_COMPRESSED = 0x01L
        const val LZ4_COMPRESSED = 0x02L
        const val LZ4HC_COMPRESSED = 0x03L
        const val COMPRESSION_MASK = 0x3fL
        const val HAS_DIRECTORY_INFO = 0x40L
        const val BLOCK_AND_DIR_AT_END = 0x80L
        const val OLD_WEB_PLUGIN_COMPATIBILITY = 0x100L
        const val BLOCK_INFO_NEED_PADDING_AT_START = 0x200L
    }
}

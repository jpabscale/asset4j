// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port)
package com.github.jpabscale.asset4j.bundle

/**
 * A replacement for a bundle entry's bytes, set before writing. Mirrors the C#
 * `IContentReplacer` concept for the write path (ContentReplacerType).
 */
interface AssetBundleReplacer {
    fun getReplacerType(): AssetBundleReplacerType
    fun getSize(): Long
    fun write(writer: com.github.jpabscale.asset4j.io.AssetsFileWriter)
}

enum class AssetBundleReplacerType {
    None,
    Remove,
    AddOrModify,
}

// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetTypeClass/AssetTypeArrayInfo.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.value

/**
 * Array metadata (the element count) carried in an array value field.
 */
class AssetTypeArrayInfo {
    var size: Int = 0

    constructor()

    constructor(size: Int) {
        this.size = size
    }
}

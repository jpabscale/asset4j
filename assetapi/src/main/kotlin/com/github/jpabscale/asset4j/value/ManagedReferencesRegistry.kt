// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetTypeClass/ManagedReferencesRegistry.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.value

class ManagedReferencesRegistry {
    var version: Int = 0
    var references: MutableList<AssetTypeReferencedObject> = mutableListOf()
}

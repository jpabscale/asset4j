// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetTypeClass/AssetTypeReferencedObject.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.value

import com.github.jpabscale.asset4j.serializedfile.AssetTypeReference

class AssetTypeReferencedObject {
    var rid: Long = 0
    var type: AssetTypeReference = AssetTypeReference()
    var data: AssetTypeValueField? = null
}

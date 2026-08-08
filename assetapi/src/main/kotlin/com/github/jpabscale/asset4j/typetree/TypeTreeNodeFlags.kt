// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/TypeTreeNodeFlags.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.typetree

/**
 * Flags on a type tree node (mirrors the C# `[Flags]` enum).
 */
class TypeTreeNodeFlags(val value: Int) {
    fun has(flag: Int): Boolean = (value and flag) == flag

    companion object {
        const val NONE = 0
        const val ARRAY = 1
        const val REF = 2
        const val REGISTRY = 4
        const val ARRAY_OF_REFS = 8
    }
}

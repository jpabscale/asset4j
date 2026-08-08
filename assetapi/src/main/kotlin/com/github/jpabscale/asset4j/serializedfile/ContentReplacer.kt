// Copyright (c) 2026 jpabscale — original code (write-path seam, mirrors IContentReplacer)
package com.github.jpabscale.asset4j.serializedfile

import com.github.jpabscale.asset4j.io.AssetsFileWriter

/**
 * Replaces an object's bytes on file write (mirrors the C# `IContentReplacer` concept).
 */
interface ContentReplacer {
    val replacerType: ContentReplacerType
    val size: Long
    fun hasPreview(): Boolean
    fun write(writer: AssetsFileWriter)
}

enum class ContentReplacerType {
    None,
    Remove,
    AddOrModify,
}

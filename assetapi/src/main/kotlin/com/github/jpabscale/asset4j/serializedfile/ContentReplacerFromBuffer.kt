// Copyright (c) 2026 jpabscale — original code (write-path helper, mirrors ContentReplacerFromBuffer)
package com.github.jpabscale.asset4j.serializedfile

import com.github.jpabscale.asset4j.io.AssetsFileWriter

/**
 * A content replacer holding new object bytes.
 */
class ContentReplacerFromBuffer(private val bytes: ByteArray) : ContentReplacer {
    override val replacerType: ContentReplacerType get() = ContentReplacerType.AddOrModify
    override val size: Long get() = bytes.size.toLong()
    override fun hasPreview(): Boolean = true
    override fun write(writer: AssetsFileWriter) {
        writer.writeBytes(bytes)
    }
}

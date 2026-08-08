// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsFileFormat/AssetPPtr.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.serializedfile

/**
 * Pointer to an object (file id + path id). Kept as a reference, never dereferenced (plan §5.3).
 */
class AssetPPtr {
    var filePath: String = ""
    var fileId: Int = 0
    var pathId: Long = 0

    constructor() {
        filePath = ""
        fileId = 0
        pathId = 0
    }

    constructor(fileId: Int, pathId: Long) {
        filePath = ""
        this.fileId = fileId
        this.pathId = pathId
    }

    fun hasFilePath(): Boolean {
        return filePath.isNotEmpty()
    }

    fun isNull(): Boolean {
        return if (hasFilePath()) pathId == 0L else fileId == 0 && pathId == 0L
    }

    override fun equals(other: Any?): Boolean {
        if (other !is AssetPPtr) {
            return false
        }
        return if (other.hasFilePath() && hasFilePath()) {
            other.pathId == pathId && other.filePath == filePath
        } else if (!other.hasFilePath() && !hasFilePath()) {
            other.pathId == pathId && other.fileId == fileId
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        var hash = 17
        hash = hash * 23 + (if (hasFilePath()) filePath.hashCode() else fileId.hashCode())
        hash = hash * 23 + pathId.hashCode()
        return hash
    }
}

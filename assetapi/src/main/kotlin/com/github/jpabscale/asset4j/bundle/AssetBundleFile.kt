// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetsBundleFileFormat/AssetBundleFile.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.bundle

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.io.AssetsFileWriter
import com.github.jpabscale.asset4j.io.Hash128
import java.io.ByteArrayOutputStream

enum class AssetBundleCompressionType {
    None,
    LZMA,
    LZ4,
    LZ4Fast,
}

/**
 * UnityFS bundle container. Read path ports the C# `Read`/`UnpackInfoOnly`. Because the JVM
 * reader is ByteArray-backed (no lazy streams), the data region is fully decompressed during
 * [Read] and served through [dataReader] — equivalent to C#'s `SegmentStream`/`LZ4BlockStream`
 * after an eager read, matching what the C# AssetsManager produces via `UnpackBundle`.
 */
class AssetBundleFile {
    var header: AssetBundleHeader = AssetBundleHeader()
    var blockAndDirInfo: AssetBundleBlockAndDirInfo = AssetBundleBlockAndDirInfo()
    var dataReader: AssetsFileReader = AssetsFileReader(ByteArray(0))
    var dataIsCompressed: Boolean = false
    var reader: AssetsFileReader = AssetsFileReader(ByteArray(0))

    fun read(reader: AssetsFileReader) {
        this.reader = reader
        reader.position = 0
        reader.bigEndian = true

        val magic = reader.readNullTerminated()
        val version = reader.readUInt32()
        if (version >= 6 || version <= 8) {
            reader.position = 0

            header = AssetBundleHeader()
            header.read(reader)

            if (header.version >= 7) {
                reader.align16()
            }

            if (header.signature == "UnityFS") {
                unpackInfoOnly()
            } else {
                throw NotImplementedError("Non UnityFS bundles are not supported yet.")
            }
        } else {
            throw NotImplementedError("Version $version bundles are not supported yet.")
        }
    }

    /**
     * Reads only the directory info (inner file names) of a UnityFS bundle, without
     * decompressing the data region — used to map an addressables `archive:/CAB-<hash>`
     * external to the sibling bundle file that contains it. Mirrors the read path up to
     * the block/dir info parse in [unpackInfoOnly], then stops.
     */
    fun readFileNames(reader: AssetsFileReader): List<String> {
        reader.position = 0
        reader.bigEndian = true
        val magic = reader.readNullTerminated()
        val version = reader.readUInt32()
        if (version < 6 || version > 8 || magic != "UnityFS") {
            throw Exception("Not a UnityFS bundle (magic=$magic version=$version)")
        }
        reader.position = 0
        header = AssetBundleHeader()
        header.read(reader)
        if (header.version >= 7) {
            reader.align16()
        }
        if (header.signature != "UnityFS") {
            throw Exception("Not a UnityFS bundle")
        }
        reader.position = header.getBundleInfoOffset().toInt()
        blockAndDirInfo = when (header.getCompressionType()) {
            0 -> {
                val b = AssetBundleBlockAndDirInfo()
                b.read(reader)
                b
            }
            1 -> {
                val compressedSize = header.fileStreamHeader.compressedSize.toInt()
                val decompressedSize = header.fileStreamHeader.decompressedSize.toInt()
                val bytes = UnityCompression.decompressLzma(
                    reader.readBytes(compressedSize), decompressedSize.toLong(), readDecompressedSize = false)
                val memReader = AssetsFileReader(bytes)
                memReader.bigEndian = reader.bigEndian
                val b = AssetBundleBlockAndDirInfo()
                b.read(memReader)
                b
            }
            2, 3 -> {
                val compressedSize = header.fileStreamHeader.compressedSize.toInt()
                val decompressedSize = header.fileStreamHeader.decompressedSize.toInt()
                val bytes = UnityCompression.decompressLz4(
                    reader.readBytes(compressedSize), decompressedSize)
                val memReader = AssetsFileReader(bytes)
                memReader.bigEndian = reader.bigEndian
                val b = AssetBundleBlockAndDirInfo()
                b.read(memReader)
                b
            }
            else -> throw Exception("Invalid compression type in header")
        }
        return blockAndDirInfo.directoryInfos.map { it.name }
    }

    /**
     * [readFileNames] over a file path that only reads the bundle's header + compressed
     * info block (not the data region), so scanning many addressables sibling bundles is
     * cheap even when each file is tens of MB. The header is well under 256 bytes; the
     * info block is located and sized from it.
     */
    fun readFileNames(path: java.nio.file.Path): List<String> {
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { ch ->
            // read the header region (signature + version + fs header) first
            val headSize = 256
            val head = java.nio.ByteBuffer.allocate(headSize)
            var off = 0L
            while (head.hasRemaining()) {
                val n = ch.read(head, off)
                if (n < 0) break
                off += n
            }
            val headReader = AssetsFileReader(head.array().copyOf(head.position()))
            headReader.bigEndian = true
            headReader.position = 0
            val magic = headReader.readNullTerminated()
            val version = headReader.readUInt32()
            if (version < 6 || version > 8 || magic != "UnityFS") {
                throw Exception("Not a UnityFS bundle (magic=$magic version=$version)")
            }
            headReader.position = 0
            header = AssetBundleHeader()
            header.read(headReader)
            if (header.version >= 7) {
                headReader.align16()
            }
            if (header.signature != "UnityFS") {
                throw Exception("Not a UnityFS bundle")
            }
            val infoOffset = header.getBundleInfoOffset()
            val compressedSize = header.fileStreamHeader.compressedSize.toInt()
            val decompressedSize = header.fileStreamHeader.decompressedSize.toInt()

            val infoBuf = java.nio.ByteBuffer.allocate(compressedSize)
            var iOff = infoOffset
            while (infoBuf.hasRemaining()) {
                val n = ch.read(infoBuf, iOff)
                if (n < 0) break
                iOff += n
            }
            val infoReader = AssetsFileReader(infoBuf.array().copyOf(infoBuf.position()))
            infoReader.bigEndian = headReader.bigEndian
            infoReader.position = 0

            blockAndDirInfo = when (header.getCompressionType()) {
                0 -> {
                    val b = AssetBundleBlockAndDirInfo()
                    b.read(infoReader)
                    b
                }
                1 -> {
                    val bytes = UnityCompression.decompressLzma(
                        infoBuf.array().copyOf(infoBuf.position()),
                        decompressedSize.toLong(), readDecompressedSize = false)
                    val memReader = AssetsFileReader(bytes)
                    memReader.bigEndian = headReader.bigEndian
                    val b = AssetBundleBlockAndDirInfo()
                    b.read(memReader)
                    b
                }
                2, 3 -> {
                    val bytes = UnityCompression.decompressLz4(
                        infoBuf.array().copyOf(infoBuf.position()), decompressedSize)
                    val memReader = AssetsFileReader(bytes)
                    memReader.bigEndian = headReader.bigEndian
                    val b = AssetBundleBlockAndDirInfo()
                    b.read(memReader)
                    b
                }
                else -> throw Exception("Invalid compression type in header")
            }
            return blockAndDirInfo.directoryInfos.map { it.name }
        }
    }

    fun getCompressionType(): AssetBundleCompressionType {
        val blockInfos = blockAndDirInfo.blockInfos
        for (i in blockInfos.indices) {
            val compType = blockInfos[i].getCompressionType()
            if (compType == 2 || compType == 3) {
                return AssetBundleCompressionType.LZ4
            } else if (compType == 1) {
                return AssetBundleCompressionType.LZMA
            }
        }
        return AssetBundleCompressionType.None
    }

    /**
     * Write the bundle (port of the C# `Write`). Rebuilds the block/dir info, rewrites
     * the directory with new offsets, and rewrites the header. Data is written
     * uncompressed (blocks flagged 0x40); compression is a Phase 9 `Pack` option.
     */
    fun write(writer: AssetsFileWriter, filePos: Long = 0) {
        //@parity:on EXC-005
        if (header.signature.isEmpty())
            throw Exception("Header must be loaded! (Did you forget to call bundle.Read?)")

        if (header.signature != "UnityFS")
            throw NotImplementedError("Non UnityFS bundles are not supported yet.")

        if (dataIsCompressed)
            throw Exception("Bundles must be decompressed before writing.")
        //@parity:off EXC-005

        var writeStart = filePos
        if (filePos == -1L)
            writeStart = writer.position.toLong()
        else
            writer.position = filePos.toInt()

        val directoryInfos = blockAndDirInfo.directoryInfos

        header.write(writer)

        if (header.version >= 7) {
            writer.align16()
        }

        var blockDataLength = 0L
        var blockDataCount = 1
        for (dirInfo in directoryInfos) {
            if (dirInfo.replacer != null) {
                blockDataLength += dirInfo.replacer!!.getSize()
            } else {
                blockDataLength += dirInfo.decompressedSize
            }

            while (blockDataLength >= 0xFFFFFFFFL) {
                blockDataLength -= 0xFFFFFFFFL
                blockDataCount++
            }
        }

        val newBundleInf = AssetBundleBlockAndDirInfo()
        newBundleInf.hash = Hash128.newBlankHash()
        newBundleInf.blockInfos = Array(blockDataCount) {
            AssetBundleBlockInfo().apply {
                compressedSize = 0
                decompressedSize = 0
                flags = 0x40
            }
        }

        val dirInfos = mutableListOf<AssetBundleDirectoryInfo>()

        val dirCount = directoryInfos.size
        for (i in 0 until dirCount) {
            val dirInfo = directoryInfos[i]
            val replacerType = dirInfo.replacer?.getReplacerType() ?: AssetBundleReplacerType.None

            if (replacerType == AssetBundleReplacerType.Remove)
                continue

            dirInfos.add(AssetBundleDirectoryInfo().apply {
                offset = dirInfo.offset
                decompressedSize = dirInfo.decompressedSize
                flags = dirInfo.flags
                name = dirInfo.name
                replacer = dirInfo.replacer
            })
        }

        val bundleInfPos = writer.position
        newBundleInf.directoryInfos = dirInfos
        newBundleInf.write(writer)

        val assetDataPosBeforeAlign = writer.position
        if ((header.fileStreamHeader.flags and AssetBundleFSHeaderFlags.BLOCK_INFO_NEED_PADDING_AT_START) != 0L) {
            writer.align16()
        }

        val assetDataPos = writer.position

        for (i in dirInfos.indices) {
            val dirInfo = dirInfos[i]
            val startPosition = writer.position
            val newOffset = (startPosition - assetDataPos).toLong()

            val replacerType = dirInfo.replacer?.getReplacerType() ?: AssetBundleReplacerType.None
            if (replacerType == AssetBundleReplacerType.AddOrModify) {
                dirInfo.replacer!!.write(writer)
            } else {
                dataReader.position = dirInfo.offset.toInt()
                writer.writeBytes(dataReader.readBytes(dirInfo.decompressedSize.toInt()))
            }

            dirInfo.offset = newOffset
            dirInfo.decompressedSize = (writer.position - startPosition).toLong()
        }

        val finalSize = writer.position
        val assetSize = (finalSize - assetDataPos).toLong()

        var remainingAssetSize = assetSize
        for (i in newBundleInf.blockInfos.indices) {
            val blockInfo = newBundleInf.blockInfos[i]
            val take = minOf(remainingAssetSize, 0xFFFFFFFFL)
            blockInfo.decompressedSize = take
            blockInfo.compressedSize = take
            remainingAssetSize -= take
        }

        newBundleInf.directoryInfos = dirInfos

        writer.position = bundleInfPos
        newBundleInf.write(writer)

        val infoSize = (assetDataPosBeforeAlign - bundleInfPos).toLong()

        writer.position = writeStart.toInt()
        val newBundleHeader = AssetBundleHeader()
        newBundleHeader.signature = header.signature
        newBundleHeader.version = header.version
        newBundleHeader.generationVersion = header.generationVersion
        newBundleHeader.engineVersion = header.engineVersion
        newBundleHeader.fileStreamHeader = AssetBundleFSHeader().apply {
            totalFileSize = finalSize.toLong()
            compressedSize = infoSize
            decompressedSize = infoSize
            flags = header.fileStreamHeader.flags and
                AssetBundleFSHeaderFlags.BLOCK_AND_DIR_AT_END.inv() and
                AssetBundleFSHeaderFlags.COMPRESSION_MASK.inv()
        }
        newBundleHeader.write(writer)

        // restore position to the end so the ByteArray writer keeps the full file
        //@parity:on EXC-005
        writer.position = (writeStart + finalSize).toInt()
        //@parity:off EXC-005
    }

    /**
     * Unpack the bundle into an uncompressed container (port of the C# `Unpack`).
     */
    fun unpack(writer: AssetsFileWriter) {
        if (header.signature.isEmpty())
            throw Exception("Header must be loaded! (Did you forget to call bundle.Read?)")

        if (header.signature != "UnityFS")
            throw NotImplementedError("Non UnityFS bundles are not supported yet.")

        val fsHeader = header.fileStreamHeader
        val reader = dataReader

        val blockInfos = blockAndDirInfo.blockInfos
        val directoryInfos = blockAndDirInfo.directoryInfos

        val newBundleHeader = AssetBundleHeader()
        newBundleHeader.signature = header.signature
        newBundleHeader.version = header.version
        newBundleHeader.generationVersion = header.generationVersion
        newBundleHeader.engineVersion = header.engineVersion
        newBundleHeader.fileStreamHeader = AssetBundleFSHeader().apply {
            totalFileSize = 0
            compressedSize = fsHeader.decompressedSize
            decompressedSize = fsHeader.decompressedSize
            flags = AssetBundleFSHeaderFlags.HAS_DIRECTORY_INFO or
                (if ((fsHeader.flags and AssetBundleFSHeaderFlags.BLOCK_INFO_NEED_PADDING_AT_START) != 0L)
                    AssetBundleFSHeaderFlags.BLOCK_INFO_NEED_PADDING_AT_START
                else
                    AssetBundleFSHeaderFlags.NONE)
        }

        var fileSize = newBundleHeader.getFileDataOffset()
        for (i in blockInfos.indices) {
            fileSize += blockInfos[i].decompressedSize
        }
        newBundleHeader.fileStreamHeader.totalFileSize = fileSize

        val newBundleInf = AssetBundleBlockAndDirInfo()
        newBundleInf.hash = Hash128.newBlankHash()
        newBundleInf.blockInfos = Array(blockInfos.size) { AssetBundleBlockInfo() }
        newBundleInf.directoryInfos = ArrayList(directoryInfos.size)

        for (i in blockInfos.indices) {
            newBundleInf.blockInfos[i] = AssetBundleBlockInfo().apply {
                compressedSize = blockInfos[i].decompressedSize
                decompressedSize = blockInfos[i].decompressedSize
                flags = blockInfos[i].flags and (0x3f).inv()
            }
        }

        for (i in directoryInfos.indices) {
            newBundleInf.directoryInfos.add(AssetBundleDirectoryInfo().apply {
                offset = directoryInfos[i].offset
                decompressedSize = directoryInfos[i].decompressedSize
                flags = directoryInfos[i].flags
                name = directoryInfos[i].name
            })
        }

        newBundleHeader.write(writer)
        if (newBundleHeader.version >= 7) {
            writer.align16()
        }
        newBundleInf.write(writer)
        if ((newBundleHeader.fileStreamHeader.flags and AssetBundleFSHeaderFlags.BLOCK_INFO_NEED_PADDING_AT_START) != 0L) {
            writer.align16()
        }

        // data region is fully decompressed in dataReader (eager), so copy it out block by block
        var dataPos = 0
        for (i in newBundleInf.blockInfos.indices) {
            val info = blockInfos[i]
            reader.position = dataPos
            writer.writeBytes(reader.readBytes(info.decompressedSize.toInt()))
            dataPos += info.decompressedSize.toInt()
        }
    }

    /**
     * Pack the bundle with LZ4 or LZMA compression (port of the C# `Pack`). Writes the
     * block-and-dir info at the end (blockDirAtEnd) with the info LZ4-compressed, and
     * recompresses the data region. [progress] is a no-op callback seam.
     */
    fun pack(
        writer: AssetsFileWriter,
        compType: AssetBundleCompressionType,
        blockDirAtEnd: Boolean = true,
    ) {
        if (header.signature.isEmpty())
            throw Exception("Header must be loaded! (Did you forget to call bundle.Read?)")

        if (header.signature != "UnityFS")
            throw NotImplementedError("Non UnityFS bundles are not supported yet.")

        if (dataIsCompressed)
            throw Exception("Bundles must be decompressed before writing.")

        reader.position = 0
        writer.position = 0

        val newFsHeader = AssetBundleFSHeader()
        newFsHeader.totalFileSize = 0
        newFsHeader.compressedSize = 0
        newFsHeader.decompressedSize = 0
        newFsHeader.flags = AssetBundleFSHeaderFlags.LZ4HC_COMPRESSED or
            AssetBundleFSHeaderFlags.HAS_DIRECTORY_INFO or
            (if (blockDirAtEnd) AssetBundleFSHeaderFlags.BLOCK_AND_DIR_AT_END else AssetBundleFSHeaderFlags.NONE)

        val newHeader = AssetBundleHeader()
        newHeader.signature = header.signature
        newHeader.version = header.version
        newHeader.generationVersion = header.generationVersion
        newHeader.engineVersion = header.engineVersion
        newHeader.fileStreamHeader = newFsHeader

        val newBlockAndDirList = AssetBundleBlockAndDirInfo()
        newBlockAndDirList.hash = Hash128.newBlankHash()
        newBlockAndDirList.blockInfos = emptyArray()
        newBlockAndDirList.directoryInfos = blockAndDirInfo.directoryInfos

        val startPos = writer.position

        newHeader.write(writer)
        if (newHeader.version >= 7)
            writer.align16()

        val headerSize = (writer.position - startPos).toInt()

        var totalCompressedSize = 0L
        val newBlocks = mutableListOf<AssetBundleBlockInfo>()

        val bundleData = dataReader.toByteArray()
        val fileDataLength = bundleData.size

        when (compType) {
            AssetBundleCompressionType.LZMA -> {
                val compressed = UnityCompression.compressLzma(bundleData)
                writer.writeBytes(compressed)
                val blockInfo = AssetBundleBlockInfo()
                blockInfo.compressedSize = compressed.size.toLong()
                blockInfo.decompressedSize = fileDataLength.toLong()
                blockInfo.flags = 0x41
                totalCompressedSize += blockInfo.compressedSize
                newBlocks.add(blockInfo)
            }
            AssetBundleCompressionType.LZ4, AssetBundleCompressionType.LZ4Fast -> {
                val fast = compType == AssetBundleCompressionType.LZ4Fast
                var p = 0
                while (p < bundleData.size) {
                    val block = bundleData.copyOfRange(p, minOf(p + 0x20000, bundleData.size))
                    val compressed = if (fast) UnityCompression.compressLz4Fast(block) else UnityCompression.compressLz4(block)

                    if (compressed.size > block.size) {
                        writer.writeBytes(block)
                        val blockInfo = AssetBundleBlockInfo()
                        blockInfo.compressedSize = block.size.toLong()
                        blockInfo.decompressedSize = block.size.toLong()
                        blockInfo.flags = 0x00
                        totalCompressedSize += blockInfo.compressedSize
                        newBlocks.add(blockInfo)
                    } else {
                        writer.writeBytes(compressed)
                        val blockInfo = AssetBundleBlockInfo()
                        blockInfo.compressedSize = compressed.size.toLong()
                        blockInfo.decompressedSize = block.size.toLong()
                        blockInfo.flags = 0x03
                        totalCompressedSize += blockInfo.compressedSize
                        newBlocks.add(blockInfo)
                    }
                    p += 0x20000
                }
            }
            AssetBundleCompressionType.None -> {
                val blockInfo = AssetBundleBlockInfo()
                blockInfo.compressedSize = fileDataLength.toLong()
                blockInfo.decompressedSize = fileDataLength.toLong()
                blockInfo.flags = 0x00
                totalCompressedSize += blockInfo.compressedSize
                newBlocks.add(blockInfo)
                writer.writeBytes(bundleData)
            }
        }

        newBlockAndDirList.blockInfos = newBlocks.toTypedArray()

        // listing is LZ4-compressed regardless of data compression
        val infoWriter = AssetsFileWriter()
        infoWriter.bigEndian = writer.bigEndian
        newBlockAndDirList.write(infoWriter)
        val bundleInfoBytes = infoWriter.toByteArray()
        val bundleInfoBytesCom = if (compType == AssetBundleCompressionType.LZ4Fast) UnityCompression.compressLz4Fast(bundleInfoBytes) else UnityCompression.compressLz4(bundleInfoBytes)

        val totalFileSize = (headerSize + bundleInfoBytesCom.size + totalCompressedSize).toLong()
        newFsHeader.totalFileSize = totalFileSize
        newFsHeader.decompressedSize = bundleInfoBytes.size.toLong()
        newFsHeader.compressedSize = bundleInfoBytesCom.size.toLong()

        writer.writeBytes(bundleInfoBytesCom)

        writer.position = 0
        newHeader.write(writer)
        if (newHeader.version >= 7)
            writer.align16()

        writer.position = totalFileSize.toInt()
    }

    fun getFileIndex(name: String): Int {
        if (header.signature.isEmpty())
            throw Exception("Header must be loaded! (Did you forget to call bundle.Read?)")
        for (i in blockAndDirInfo.directoryInfos.indices) {
            if (blockAndDirInfo.directoryInfos[i].name == name)
                return i
        }
        return -1
    }

    fun getFileName(index: Int): String? {
        if (header.signature.isEmpty())
            throw Exception("Header must be loaded! (Did you forget to call bundle.Read?)")
        if (index < 0 || index >= blockAndDirInfo.directoryInfos.size)
            return null
        return blockAndDirInfo.directoryInfos[index].name
    }

    fun getFileRange(index: Int): Pair<Long, Long> {
        if (header.signature.isEmpty())
            throw Exception("Header must be loaded! (Did you forget to call bundle.Read?)")
        if (index < 0 || index >= blockAndDirInfo.directoryInfos.size) {
            return -1L to 0L
        }
        val entry = blockAndDirInfo.directoryInfos[index]
        return entry.offset to entry.decompressedSize
    }

    fun getAllFileNames(): List<String> {
        if (header.signature.isEmpty())
            throw Exception("Header must be loaded! (Did you forget to call bundle.Read?)")
        val names = mutableListOf<String>()
        for (dirInfo in blockAndDirInfo.directoryInfos) {
            names.add(dirInfo.name)
        }
        return names
    }

    /**
     * Extracts the bytes of the file at [index] from the decompressed data region.
     */
    fun getFileData(index: Int): ByteArray {
        val (offset, length) = getFileRange(index)
        if (offset < 0) return ByteArray(0)
        dataReader.position = offset.toInt()
        return dataReader.readBytes(length.toInt())
    }

    private fun unpackInfoOnly() {
        if (header.signature.isEmpty())
            throw Exception("Header must be loaded! (Did you forget to call bundle.Read?)")

        reader.position = header.getBundleInfoOffset().toInt()
        if (header.getCompressionType() == 0) {
            blockAndDirInfo = AssetBundleBlockAndDirInfo()
            blockAndDirInfo.read(reader)
        } else {
            val compressedSize = header.fileStreamHeader.compressedSize.toInt()
            val decompressedSize = header.fileStreamHeader.decompressedSize.toInt()

            val bytes: ByteArray = when (header.getCompressionType()) {
                1 -> {
                    UnityCompression.decompressLzma(reader.readBytes(compressedSize), decompressedSize.toLong(), readDecompressedSize = false)
                }
                2, 3 -> {
                    UnityCompression.decompressLz4(reader.readBytes(compressedSize), decompressedSize)
                }
                else -> {
                    throw Exception("Invalid compression type in header")
                }
            }

            val memReader = AssetsFileReader(bytes)
            memReader.position = 0
            memReader.bigEndian = reader.bigEndian
            blockAndDirInfo = AssetBundleBlockAndDirInfo()
            blockAndDirInfo.read(memReader)        }

        // fully decompress the data region into a ByteArray-backed reader
        val out = ByteArrayOutputStream()
        val fileDataOffset = header.getFileDataOffset()
        reader.position = fileDataOffset.toInt()
        val blockInfos = blockAndDirInfo.blockInfos
        for (i in blockInfos.indices) {
            val info = blockInfos[i]
            when (info.getCompressionType()) {
                0 -> {
                    out.write(reader.readBytes(info.compressedSize.toInt()))
                }
                1 -> {
                    out.write(UnityCompression.decompressLzma(reader.readBytes(info.compressedSize.toInt()), info.decompressedSize, readDecompressedSize = false))
                }
                2, 3 -> {
                    out.write(UnityCompression.decompressLz4(reader.readBytes(info.compressedSize.toInt()), info.decompressedSize.toInt()))
                }
            }
        }
        dataReader = AssetsFileReader(out.toByteArray())
        //@parity:on EXC-005
        dataIsCompressed = false
        //@parity:off EXC-005
    }
}

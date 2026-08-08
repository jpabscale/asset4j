// Copyright (c) 2026 jpabscale — original helper (wraps the AssetTypeTemplateField port)
package com.github.jpabscale.asset4j.value

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.serializedfile.AssetsFileMetadata
import com.github.jpabscale.asset4j.typetree.TypeTreeType

/**
 * Convenience decode entry (plan Phase 6): build the template from a [TypeTreeType] and
 * walk the object bytes into an [AssetTypeValueField] tree. The encode inverse lives in
 * the same class (plan Phase 7).
 */
object TypeTreeHelper {
    /**
     * Builds the template field from [typeTreeType] and decodes [reader] (already positioned
     * at the object data) into the value tree. When [metadata] is given, a [RefTypeManager]
     * is populated from its ref types so `ManagedReferencesRegistry` fields decode.
     */
    fun read(reader: AssetsFileReader, typeTreeType: TypeTreeType, refMan: RefTypeManager? = null): AssetTypeValueField {
        val template = AssetTypeTemplateField()
        template.fromTypeTree(typeTreeType)
        return template.makeValue(reader, refMan)
    }

    fun read(reader: AssetsFileReader, typeTreeType: TypeTreeType, metadata: AssetsFileMetadata?): AssetTypeValueField {
        val refMan = RefTypeManager()
        if (metadata != null) {
            refMan.fromTypeTree(metadata)
        }
        return read(reader, typeTreeType, refMan)
    }

    /**
     * Decodes a standalone object [data] byte array using [typeTreeType].
     */
    fun readBytes(data: ByteArray, typeTreeType: TypeTreeType, refMan: RefTypeManager? = null): AssetTypeValueField {
        val reader = AssetsFileReader(data)
        reader.bigEndian = false
        return read(reader, typeTreeType, refMan)
    }

    fun readBytes(data: ByteArray, typeTreeType: TypeTreeType, metadata: AssetsFileMetadata): AssetTypeValueField {
        val reader = AssetsFileReader(data)
        reader.bigEndian = false
        return read(reader, typeTreeType, metadata)
    }

    /**
     * Decodes using a pre-resolved type tree blob (e.g. from a ttmap for external types).
     * [templateBlob] carries the resolved nodes; the template is built from it directly.
     */
    fun readBytes(data: ByteArray, templateBlob: com.github.jpabscale.asset4j.typetree.TypeTreeBlob, refMan: RefTypeManager? = null): AssetTypeValueField {
        val reader = AssetsFileReader(data)
        reader.bigEndian = false
        val template = AssetTypeTemplateField()
        template.fromTypeBlob(templateBlob)
        return template.makeValue(reader, refMan)
    }

    /** Re-encodes a value tree back to bytes (Phase 7). */
    fun write(valueField: AssetTypeValueField): ByteArray {
        return valueField.writeToByteArray(bigEndian = false)
    }
}

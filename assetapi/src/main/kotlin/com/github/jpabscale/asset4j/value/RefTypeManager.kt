// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetTypeClass/RefTypeManager.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.value

import com.github.jpabscale.asset4j.serializedfile.AssetTypeReference
import com.github.jpabscale.asset4j.serializedfile.AssetsFileMetadata

/**
 * Resolves `AssetTypeReference` → template field, from the file's ref types. The
 * MonoBehaviour template-generator seam is deferred (v1: ref-types only); the
 * `monoTemplateGenerator` path is where `ttmapgen`/ttmap generation plugs in later.
 */
class RefTypeManager {
    private val typeTreeLookup: LinkedHashMap<AssetTypeReference, AssetTypeTemplateField> = LinkedHashMap()

    fun clear() {
        typeTreeLookup.clear()
    }

    fun fromTypeTree(metadata: AssetsFileMetadata) {
        if (!metadata.typeTreeEnabled || metadata.refTypes.isEmpty()) {
            return
        }

        for (type in metadata.refTypes) {
            if (!type.isRefType)
                continue

            val templateField: AssetTypeTemplateField
            if (type.typeBlobIsDefinition) {
                templateField = AssetTypeTemplateField()
                templateField.fromTypeTree(type)
                removeRedundantRegistry(templateField)
            } else {
                continue
            }

            typeTreeLookup[type.typeReference!!] = templateField
        }
    }

    fun getTemplateField(type: AssetTypeReference): AssetTypeTemplateField? {
        if (type.equals(AssetTypeReference.TERMINUS)) {
            return null
        }
        if (type.className.isEmpty() && type.nameSpace.isEmpty() && type.asmName.isEmpty()) {
            return null
        }

        return typeTreeLookup[type]
    }

    private fun removeRedundantRegistry(templateField: AssetTypeTemplateField) {
        if (templateField.children.isEmpty())
            return

        val lastChildIdx = templateField.children.size - 1
        val lastChild = templateField[lastChildIdx]
        if (lastChild.valueType == AssetValueType.ManagedReferencesRegistry) {
            templateField.children.removeAt(lastChildIdx)
        }
    }
}

// Copyright (c) 2026 jpabscale — original code (JSON layer, not part of the port)
package com.github.jpabscale.asset4j.value

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory

/**
 * Converts an [AssetTypeValueField] tree to/from Jackson [JsonNode] (plan §2.6 Data shape).
 * Field order is preserved (ObjectNode is ordered). Strings decode from their UTF-8 bytes.
 */
object ValueFieldJson {

    fun toNode(field: AssetTypeValueField): JsonNode {
        val tf = field.templateField ?: return JsonNodeFactory.instance.nullNode()
        if (tf.isArray) {
            if (tf.valueType == AssetValueType.ByteArray) {
                return JsonNodeFactory.instance.textNode(base64Encode(field.value?.asByteArray ?: ByteArray(0)))
            }
            val arr = JsonNodeFactory.instance.arrayNode()
            for (child in field.children) {
                arr.add(toNode(child))
            }
            return arr
        }
        return when (tf.valueType) {
            AssetValueType.None -> {
                val obj = JsonNodeFactory.instance.objectNode()
                for (child in field.children) {
                    obj.set<JsonNode>(child.templateField?.name ?: "", toNode(child))
                }
                obj
            }
            AssetValueType.Bool -> JsonNodeFactory.instance.booleanNode(field.value?.asBool ?: false)
            AssetValueType.Int8, AssetValueType.UInt8,
            AssetValueType.Int16, AssetValueType.UInt16,
            AssetValueType.Int32, AssetValueType.UInt32,
            AssetValueType.Int64, AssetValueType.UInt64,
            -> JsonNodeFactory.instance.numberNode(field.value?.asLong ?: 0L)
            AssetValueType.Float -> JsonNodeFactory.instance.numberNode(field.value?.asFloat ?: 0f)
            AssetValueType.Double -> JsonNodeFactory.instance.numberNode(field.value?.asDouble ?: 0.0)
            AssetValueType.String -> JsonNodeFactory.instance.textNode(field.value?.asString ?: "")
            AssetValueType.ManagedReferencesRegistry -> {
                val obj = JsonNodeFactory.instance.objectNode()
                val reg = field.value?.asManagedReferencesRegistry
                obj.put("version", reg?.version ?: 0)
                val refs = JsonNodeFactory.instance.arrayNode()
                reg?.references?.forEach { ref ->
                    val refNode = JsonNodeFactory.instance.objectNode()
                    refNode.put("rid", ref.rid)
                    refNode.put("className", ref.type.className)
                    refNode.put("namespace", ref.type.nameSpace)
                    refNode.put("asmName", ref.type.asmName)
                    if (ref.data != null) {
                        refNode.set<JsonNode>("data", toNode(ref.data!!))
                    }
                    refs.add(refNode)
                }
                obj.set<JsonNode>("references", refs)
                obj
            }
            else -> JsonNodeFactory.instance.nullNode()
        }
    }

    /**
     * Builds a value field tree from a JSON [node] using the template [templateField].
     * Order of children follows the template (Unity serialization is order-sensitive);
     * the JSON object's own order is ignored for encoding. [refMan] resolves the
     * ManagedReferencesRegistry referenced objects.
     */
    fun fromNode(node: JsonNode, templateField: AssetTypeTemplateField, refMan: RefTypeManager? = null): AssetTypeValueField {
        val field = AssetTypeValueField()
        field.templateField = templateField
        if (templateField.isArray) {
            if (templateField.valueType == AssetValueType.ByteArray) {
                field.value = AssetTypeValue(base64Decode(node.asText("")), false)
            } else {
                field.children = mutableListOf()
                if (node.isArray) {
                    val elemTemplate = templateField.children[1]
                    for (elem in node) {
                        field.children.add(fromNode(elem, elemTemplate, refMan))
                    }
                }
            }
        } else {
            when (templateField.valueType) {
                AssetValueType.None -> {
                    field.children = mutableListOf()
                    for (childTemplate in templateField.children) {
                        val childNode = node.get(childTemplate.name)
                        val child = fromNode(childNode ?: JsonNodeFactory.instance.nullNode(), childTemplate, refMan)
                        field.children.add(child)
                    }
                    field.value = null
                }
                AssetValueType.Bool -> field.value = AssetTypeValue(node.asBoolean())
                AssetValueType.Int8 -> field.value = AssetTypeValue(node.asInt().toByte())
                AssetValueType.UInt8 -> field.value = AssetTypeValue(node.asInt().toByte())
                AssetValueType.Int16 -> field.value = AssetTypeValue(node.asInt().toShort())
                AssetValueType.UInt16 -> field.value = AssetTypeValue(node.asInt())
                AssetValueType.Int32 -> field.value = AssetTypeValue(node.asInt())
                AssetValueType.UInt32 -> field.value = AssetTypeValue(node.asLong())
                AssetValueType.Int64 -> field.value = AssetTypeValue(node.asLong())
                AssetValueType.UInt64 -> field.value = AssetTypeValue(node.asLong())
                AssetValueType.Float -> field.value = AssetTypeValue(node.asDouble().toFloat())
                AssetValueType.Double -> field.value = AssetTypeValue(node.asDouble())
                AssetValueType.String -> field.value = AssetTypeValue(node.asText(""))
                AssetValueType.ManagedReferencesRegistry -> {
                    val reg = ManagedReferencesRegistry()
                    reg.version = node.get("version")?.asInt() ?: 0
                    reg.references = mutableListOf()
                    val refs = node.get("references")
                    if (refs != null && refs.isArray) {
                        for (refNode in refs) {
                            val refd = com.github.jpabscale.asset4j.value.AssetTypeReferencedObject()
                            refd.rid = refNode.get("rid")?.asLong() ?: 0L
                            refd.type = com.github.jpabscale.asset4j.serializedfile.AssetTypeReference(
                                refNode.get("className")?.asText("") ?: "",
                                refNode.get("namespace")?.asText("") ?: "",
                                refNode.get("asmName")?.asText("") ?: "",
                            )
                            // reconstruct the referenced object's data from the ref template
                            val refTemplate = refMan?.getTemplateField(refd.type)
                            val dataNode = refNode.get("data")
                            if (refTemplate != null && dataNode != null) {
                                refd.data = fromNode(dataNode, refTemplate, refMan)
                            }
                            reg.references.add(refd)
                        }
                    }
                    field.value = AssetTypeValue(reg)
                }
                else -> field.value = null
            }
        }
        return field
    }

    private fun base64Encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun base64Decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}

// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetTypeClass/AssetTypeTemplateField.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.value

import com.github.jpabscale.asset4j.io.AssetsFileReader
import com.github.jpabscale.asset4j.serializedfile.AssetTypeReference
import com.github.jpabscale.asset4j.typetree.TypeTreeBlob
import com.github.jpabscale.asset4j.typetree.TypeTreeType

/**
 * The schema template built from a type tree: name, type, value type, array/alignment flags,
 * children. Used to decode (ReadType) and re-encode object data.
 */
class AssetTypeTemplateField {
    var name: String = ""
    var type: String = ""
    var valueType: AssetValueType = AssetValueType.None
    var isArray: Boolean = false
    var isAligned: Boolean = false
    var hasValue: Boolean = false
    var version: Int = 0
    var children: MutableList<AssetTypeTemplateField> = mutableListOf()

    fun fromTypeTree(typeTreeType: TypeTreeType) {
        val typeTreeBlob = typeTreeType.typeBlob

        if (typeTreeBlob.nodes.isNotEmpty() && !typeTreeType.typeBlobIsDefinition)
            return

        var fieldIndex = 0
        fromTypeTree(typeTreeBlob, RefIndex(fieldIndex))
    }

    /**
     * Builds the template from a type-tree blob (e.g. a ttmap-resolved external script type).
     * Because DLL-derived trees don't carry Unity's kAlign metaFlags, alignment is computed
     * for fields whose serialized size isn't a multiple of 4 (bool/int8/int16) when they are
     * followed by a sibling field at the same level — matching how Unity pads a MonoBehaviour
     * header (e.g. the bool m_Enabled is 4-aligned before the PPtr m_Script).
     */
    fun fromTypeBlob(typeTreeBlob: TypeTreeBlob) {
        computeAligned(typeTreeBlob)
        var fieldIndex = 0
        fromTypeTree(typeTreeBlob, RefIndex(fieldIndex))
    }

    /**
     * Unity pads a field to the next 4-byte boundary when a sibling field after it requires
     * 4-byte alignment (PPtr/int/long/float/string/array/struct). Fields that are themselves
     * a multiple of 4 bytes, or whose next sibling also packs (e.g. two consecutive bools),
     * get no alignment. Sets the kAlign metaFlag (0x4000) so [fromTypeTree] picks it up.
     */
    private fun computeAligned(typeTreeBlob: TypeTreeBlob) {
        val sizeOf = HashMap<Int, Int>()
        val needsAlign = HashMap<Int, Boolean>()
        for (i in typeTreeBlob.nodes.indices) {
            val field = typeTreeBlob.nodes[i]
            val typeStr = field.getTypeString(typeTreeBlob.stringBufferBytes)
            val valueType = AssetTypeValueField.getValueTypeByTypeName(typeStr)
            needsAlign[i] = requires4Alignment(valueType, typeStr)
            sizeOf[i] = when (valueType) {
                AssetValueType.Bool, AssetValueType.Int8, AssetValueType.UInt8 -> 1
                AssetValueType.Int16, AssetValueType.UInt16 -> 2
                AssetValueType.Int32, AssetValueType.UInt32, AssetValueType.Float -> 4
                AssetValueType.Int64, AssetValueType.UInt64, AssetValueType.Double -> 8
                else -> -1
            }
        }
        var i = 0
        while (i < typeTreeBlob.nodes.size) {
            val level = typeTreeBlob.nodes[i].level
            var j = i + 1
            while (j < typeTreeBlob.nodes.size && typeTreeBlob.nodes[j].level > level) j++
            if (j < typeTreeBlob.nodes.size && typeTreeBlob.nodes[j].level == level) {
                val sz = sizeOf[i] ?: -1
                if (sz >= 0 && sz % 4 != 0 && (needsAlign[j] == true)) {
                    typeTreeBlob.nodes[i].metaFlags = typeTreeBlob.nodes[i].metaFlags or 0x4000L
                }
            }
            i++
        }
    }

    private fun requires4Alignment(valueType: AssetValueType, typeStr: String): Boolean {
        return when (valueType) {
            AssetValueType.Int32, AssetValueType.UInt32, AssetValueType.Int64, AssetValueType.UInt64,
            AssetValueType.Float, AssetValueType.Double,
            AssetValueType.String, AssetValueType.ByteArray, AssetValueType.Array,
            -> true
            // struct/class fields (PPtr, user types) require 4-byte alignment too
            AssetValueType.None -> typeStr != "bool" && !typeStr.startsWith("int") && typeStr != "SInt8"
            else -> false
        }
    }

    private class RefIndex(var value: Int)

    private fun fromTypeTree(typeTreeBlob: TypeTreeBlob, fieldIndexRef: RefIndex) {
        val field = typeTreeBlob.nodes[fieldIndexRef.value]
        name = field.getNameString(typeTreeBlob.stringBufferBytes)
        type = field.getTypeString(typeTreeBlob.stringBufferBytes)
        valueType = AssetTypeValueField.getValueTypeByTypeName(type)
        isArray = hasFlag(field.typeFlags, com.github.jpabscale.asset4j.typetree.TypeTreeNodeFlags.ARRAY)
        isAligned = (field.metaFlags and 0x4000) != 0L
        hasValue = valueType != AssetValueType.None
        version = field.version

        children = mutableListOf()

        fieldIndexRef.value++
        while (fieldIndexRef.value < typeTreeBlob.nodes.size) {
            val typeTreeField = typeTreeBlob.nodes[fieldIndexRef.value]
            if (typeTreeField.level <= field.level) {
                fieldIndexRef.value--
                break
            }

            val assetField = AssetTypeTemplateField()
            assetField.fromTypeTree(typeTreeBlob, fieldIndexRef)
            children.add(assetField)

            fieldIndexRef.value++
        }

        if (valueType == AssetValueType.String && !children[0].isArray && children[0].valueType != AssetValueType.None) {
            type = "_string"
            valueType = AssetValueType.None
        }

        if (isArray) {
            valueType = if (children[1].valueType == AssetValueType.UInt8) AssetValueType.ByteArray else AssetValueType.Array
        }
    }

    fun makeValue(reader: AssetsFileReader, refMan: RefTypeManager? = null): AssetTypeValueField {
        val valueField = AssetTypeValueField()
        valueField.templateField = this
        return readType(reader, valueField, refMan)
    }

    fun makeValue(reader: AssetsFileReader, position: Long, refMan: RefTypeManager? = null): AssetTypeValueField {
        reader.position = position.toInt()
        return makeValue(reader, refMan)
    }

    fun readType(reader: AssetsFileReader, valueField: AssetTypeValueField, refMan: RefTypeManager?): AssetTypeValueField {
        if (valueField.templateField?.isArray == true) {
            val arrayChildCount = valueField.templateField!!.children.size
            if (arrayChildCount != 2)
                throw Exception("Expected array to have two children, found $arrayChildCount instead!")

            val sizeType = valueField.templateField!!.children[0].valueType

            if (sizeType != AssetValueType.Int32 && sizeType != AssetValueType.UInt32)
                throw Exception("Expected int array size type, found $sizeType instead!")

            if (valueField.templateField!!.valueType == AssetValueType.ByteArray) {
                valueField.children = mutableListOf()

                val size = reader.readInt32()
                val data = reader.readBytes(size)

                if (valueField.templateField!!.isAligned)
                    reader.align()

                valueField.value = AssetTypeValue(AssetValueType.ByteArray, data)
            } else {
                val size = reader.readInt32()
                valueField.children = ArrayList(size)

                for (i in 0 until size) {
                    val childField = AssetTypeValueField()
                    childField.templateField = valueField.templateField!!.children[1]
                    valueField.children.add(readType(reader, childField, refMan))
                }

                if (valueField.templateField!!.isAligned)
                    reader.align()

                val arrayTypeInfo = AssetTypeArrayInfo()
                arrayTypeInfo.size = size

                valueField.value = AssetTypeValue(AssetValueType.Array, arrayTypeInfo)
            }
        } else {
            val type = valueField.templateField?.valueType ?: AssetValueType.None
            if (type == AssetValueType.None) {
                val childCount = valueField.templateField!!.children.size
                valueField.children = ArrayList(childCount)
                for (i in 0 until childCount) {
                    val childField = AssetTypeValueField()
                    childField.templateField = valueField.templateField!!.children[i]
                    valueField.children.add(readType(reader, childField, refMan))
                }
                valueField.value = null

                if (valueField.templateField!!.isAligned)
                    reader.align()
            } else {
                readPrimitiveType(reader, valueField, type, refMan)
            }
        }
        return valueField
    }

    fun readPrimitiveType(reader: AssetsFileReader, valueField: AssetTypeValueField, type: AssetValueType, refMan: RefTypeManager?) {
        if (type == AssetValueType.String) {
            valueField.children = mutableListOf()
            val length = reader.readInt32()
            valueField.value = AssetTypeValue(reader.readBytes(length), true)
            reader.align()
        } else if (type == AssetValueType.ManagedReferencesRegistry) {
            readManagedReferencesRegistryType(reader, valueField, refMan)
        } else {
            val childCount = valueField.templateField?.children?.size ?: 0
            if (childCount == 0) {
                valueField.children = mutableListOf()
                when (type) {
                    AssetValueType.Int8 -> {
                        valueField.value = AssetTypeValue(reader.readSByte().toByte())
                    }
                    AssetValueType.UInt8 -> {
                        valueField.value = AssetTypeValue(reader.readByte().toByte())
                    }
                    AssetValueType.Bool -> {
                        valueField.value = AssetTypeValue(reader.readBoolean())
                    }
                    AssetValueType.Int16 -> {
                        valueField.value = AssetTypeValue(reader.readInt16())
                    }
                    AssetValueType.UInt16 -> {
                        valueField.value = AssetTypeValue(reader.readUInt16())
                    }
                    AssetValueType.Int32 -> {
                        valueField.value = AssetTypeValue(reader.readInt32())
                    }
                    AssetValueType.UInt32 -> {
                        valueField.value = AssetTypeValue(reader.readUInt32())
                    }
                    AssetValueType.Int64 -> {
                        valueField.value = AssetTypeValue(reader.readInt64())
                    }
                    AssetValueType.UInt64 -> {
                        valueField.value = AssetTypeValue(reader.readUInt64())
                    }
                    AssetValueType.Float -> {
                        valueField.value = AssetTypeValue(reader.readSingle())
                    }
                    AssetValueType.Double -> {
                        valueField.value = AssetTypeValue(reader.readDouble())
                    }
                    else -> {}
                }

                if (valueField.templateField?.isAligned == true)
                    reader.align()
            } else if (type != AssetValueType.None) {
                throw Exception("Cannot read value of field with children!")
            }
        }
    }

    fun readManagedReferencesRegistryType(reader: AssetsFileReader, valueField: AssetTypeValueField, refMan: RefTypeManager?) {
        if (refMan == null)
            throw Exception("refMan must be non-null to deserialize objects with ref types.")

        valueField.children = mutableListOf()
        val registry = ManagedReferencesRegistry()
        valueField.value = AssetTypeValue(registry)
        val registryChildCount = valueField.templateField?.children?.size ?: 0
        if (registryChildCount != 2)
            throw Exception("Expected ManagedReferencesRegistry to have two children, found $registryChildCount instead!")

        registry.version = reader.readInt32()
        registry.references = mutableListOf()

        if (registry.version == 1) {
            while (true) {
                val refdObject = makeReferencedObject(reader, registry.version, registry.references.size, refMan)
                if (refdObject.type == AssetTypeReference.TERMINUS) {
                    break
                }
                registry.references.add(refdObject)
            }
        } else {
            val childCount = reader.readInt32()
            for (i in 0 until childCount) {
                val refdObject = makeReferencedObject(reader, registry.version, -1, refMan)
                registry.references.add(refdObject)
            }
        }
    }

    operator fun get(name: String): AssetTypeTemplateField? {
        if (name.contains(".")) {
            val splitNames = name.split(".")
            var field: AssetTypeTemplateField? = this
            for (splitName in splitNames) {
                var foundChild = false
                val f = field ?: return null
                for (child in f.children) {
                    if (child.name == splitName) {
                        foundChild = true
                        field = child
                        break
                    }
                }
                if (!foundChild) {
                    return null
                }
            }
            return field
        } else {
            for (child in children) {
                if (child.name == name) {
                    return child
                }
            }
            return null
        }
    }

    operator fun get(index: Int): AssetTypeTemplateField {
        return children[index]
    }

    fun clone(): AssetTypeTemplateField {
        val field = AssetTypeTemplateField()
        field.name = name
        field.type = type
        field.valueType = valueType
        field.isArray = isArray
        field.isAligned = isAligned
        field.hasValue = hasValue
        field.version = version
        field.children = children.map { it.clone() }.toMutableList()
        return field
    }

    private fun makeReferencedObject(
        reader: AssetsFileReader,
        registryVersion: Int,
        referenceIndex: Int,
        refMan: RefTypeManager,
    ): AssetTypeReferencedObject {
        val refdObject = AssetTypeReferencedObject()

        if (registryVersion == 1) {
            refdObject.rid = referenceIndex.toLong()
        } else {
            refdObject.rid = reader.readInt64()
        }

        val refType = AssetTypeReference()
        refType.readAsset(reader)
        refdObject.type = refType

        val objectTempField = refMan.getTemplateField(refType)
        if (objectTempField != null) {
            val tempField = AssetTypeValueField()
            tempField.templateField = objectTempField
            refdObject.data = readType(reader, tempField, refMan)
        } else {
            refdObject.data = AssetTypeValueField.DUMMY_FIELD
        }

        return refdObject
    }

    override fun toString(): String = "$type $name"

    private fun hasFlag(flags: Int, flag: Int): Boolean {
        return (flags and flag) == flag
    }
}

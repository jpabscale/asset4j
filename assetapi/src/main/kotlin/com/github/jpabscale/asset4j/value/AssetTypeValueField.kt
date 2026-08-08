// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetTypeClass/AssetTypeValueField.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.value

import com.github.jpabscale.asset4j.io.AssetsFileWriter
import com.github.jpabscale.asset4j.serializedfile.AssetTypeReference

class DummyFieldAccessException(message: String) : Exception(message)

/**
 * A decoded value field: a template (schema) + a typed value + children. This is the
 * `Data` tree carrier — the object's decoded contents (plan §2.6).
 */
class AssetTypeValueField {
    var templateField: AssetTypeTemplateField? = null
    var value: AssetTypeValue? = null
    var children: MutableList<AssetTypeValueField> = mutableListOf()
    var isDummy: Boolean = false

    fun read(value: AssetTypeValue?, templateField: AssetTypeTemplateField, children: List<AssetTypeValueField>) {
        this.value = value
        this.templateField = templateField
        this.children = children.toMutableList()
        isDummy = false
    }

    operator fun get(name: String): AssetTypeValueField {
        if (isDummy) {
            throw DummyFieldAccessException("Cannot access fields of a dummy field!")
        }

        if (name.contains(".")) {
            val splitNames = name.split(".")
            var field: AssetTypeValueField = this
            for (splitName in splitNames) {
                var foundChild = false
                for (child in field.children) {
                    if (child.templateField?.name == splitName) {
                        foundChild = true
                        field = child
                        break
                    }
                }
                if (!foundChild) {
                    return DUMMY_FIELD
                }
            }
            return field
        } else {
            for (child in children) {
                if (child.templateField?.name == name) {
                    return child
                }
            }
            return DUMMY_FIELD
        }
    }

    operator fun get(index: Int): AssetTypeValueField {
        if (isDummy) {
            throw DummyFieldAccessException("Cannot access fields of a dummy field!")
        }
        return children[index]
    }

    fun write(writer: AssetsFileWriter) {
        val tf = templateField ?: return
        if (tf.isArray) {
            if (tf.valueType == AssetValueType.ByteArray) {
                val byteArray = value?.asByteArray ?: ByteArray(0)

                writer.writeInt32(byteArray.size)
                writer.writeBytes(byteArray)

                if (tf.isAligned) {
                    writer.align()
                }
            } else {
                val arraySize = children.size

                writer.writeInt32(arraySize)
                for (i in 0 until arraySize) {
                    this[i].write(writer)
                }

                if (tf.isAligned) {
                    writer.align()
                }
            }
        } else {
            if (children.isEmpty()) {
                val v = value
                when (tf.valueType) {
                    AssetValueType.Int8 -> {
                        writer.writeByte(v?.asSByte?.toInt() ?: 0)
                        if (tf.isAligned) {
                            writer.align()
                        }
                    }
                    AssetValueType.UInt8 -> {
                        writer.writeByte(v?.asByte?.toInt() ?: 0)
                        if (tf.isAligned) {
                            writer.align()
                        }
                    }
                    AssetValueType.Bool -> {
                        writer.writeBoolean(v?.asBool ?: false)
                        if (tf.isAligned) {
                            writer.align()
                        }
                    }
                    AssetValueType.Int16 -> {
                        writer.writeInt16(v?.asShort ?: 0)
                        if (tf.isAligned) {
                            writer.align()
                        }
                    }
                    AssetValueType.UInt16 -> {
                        writer.writeUInt16(v?.asUShort ?: 0)
                        if (tf.isAligned) {
                            writer.align()
                        }
                    }
                    AssetValueType.Int32 -> {
                        writer.writeInt32(v?.asInt ?: 0)
                    }
                    AssetValueType.UInt32 -> {
                        writer.writeUInt32(v?.asUInt ?: 0)
                    }
                    AssetValueType.Int64 -> {
                        writer.writeInt64(v?.asLong ?: 0)
                    }
                    AssetValueType.UInt64 -> {
                        writer.writeUInt64(v?.asULong ?: 0)
                    }
                    AssetValueType.Float -> {
                        writer.writeSingle(v?.asFloat ?: 0f)
                    }
                    AssetValueType.Double -> {
                        writer.writeDouble(v?.asDouble ?: 0.0)
                    }
                    AssetValueType.String -> {
                        val arr = v?.asByteArray ?: ByteArray(0)
                        writer.writeInt32(arr.size)
                        writer.writeBytes(arr)
                        writer.align()
                    }
                    AssetValueType.ManagedReferencesRegistry -> {
                        val registry = v?.asManagedReferencesRegistry ?: ManagedReferencesRegistry()
                        writer.writeInt32(registry.version)
                        val childCount = registry.references.size

                        if (registry.version >= 2) {
                            writer.writeInt32(childCount)
                        }
                        for (i in 0 until childCount) {
                            val refdObject = registry.references[i]
                            if (registry.version >= 2) {
                                writer.writeInt64(refdObject.rid)
                            }
                            refdObject.type.writeAsset(writer)
                            refdObject.data?.write(writer)
                        }
                        if (registry.version == 1) {
                            AssetTypeReference.TERMINUS.writeAsset(writer)
                        }
                    }
                    else -> {}
                }
            } else {
                for (i in children.indices) {
                    this[i].write(writer)
                }

                if (tf.isAligned) {
                    writer.align()
                }
            }
        }
    }

    fun writeToByteArray(bigEndian: Boolean = false): ByteArray {
        val w = AssetsFileWriter()
        w.bigEndian = bigEndian
        write(w)
        return w.toByteArray()
    }

    fun clone(): AssetTypeValueField {
        val newChildren = ArrayList<AssetTypeValueField>(children.size)
        for (i in children.indices) {
            val child = children[i]
            newChildren.add(child.clone())
        }

        val field = AssetTypeValueField()
        field.templateField = templateField
        field.value = value?.clone()
        field.children = newChildren
        field.isDummy = isDummy
        return field
    }

    override fun toString(): String {
        return templateField?.toString() ?: ""
    }

    val asBool: Boolean get() = value!!.asBool
    val asSByte: Byte get() = value!!.asSByte
    val asByte: Byte get() = value!!.asByte
    val asShort: Short get() = value!!.asShort
    val asUShort: Int get() = value!!.asUShort
    val asInt: Int get() = value!!.asInt
    val asUInt: Long get() = value!!.asUInt
    val asLong: Long get() = value!!.asLong
    val asULong: Long get() = value!!.asULong
    val asFloat: Float get() = value!!.asFloat
    val asDouble: Double get() = value!!.asDouble
    val asString: String get() = value!!.asString
    val asObject: Any? get() = value!!.asObject
    val asArray: AssetTypeArrayInfo get() = value!!.asArray
    val asByteArray: ByteArray get() = value!!.asByteArray
    val asManagedReferencesRegistry: ManagedReferencesRegistry get() = value!!.asManagedReferencesRegistry

    val typeName: String get() = templateField!!.type
    val fieldName: String get() = templateField!!.name

    companion object {
        val DUMMY_FIELD: AssetTypeValueField = AssetTypeValueField().apply {
            templateField = AssetTypeTemplateField().apply {
                name = "DUMMY"
                hasValue = false
                isAligned = false
                isArray = false
                type = "DUMMY"
                valueType = AssetValueType.None
                children = mutableListOf()
            }
            value = null
            isDummy = true
            children = mutableListOf()
        }

        fun getValueTypeByTypeName(type: String): AssetValueType {
            return when (type) {
                "string" -> AssetValueType.String
                "SInt8", "char" -> AssetValueType.Int8
                "UInt8", "unsigned char" -> AssetValueType.UInt8
                "SInt16", "short" -> AssetValueType.Int16
                "UInt16", "unsigned short" -> AssetValueType.UInt16
                "SInt32", "int", "Type*" -> AssetValueType.Int32
                "UInt32", "unsigned int" -> AssetValueType.UInt32
                "SInt64", "long" -> AssetValueType.Int64
                "UInt64", "unsigned long long", "FileSize" -> AssetValueType.UInt64
                "float" -> AssetValueType.Float
                "double" -> AssetValueType.Double
                "bool" -> AssetValueType.Bool
                "Array" -> AssetValueType.Array
                "TypelessData" -> AssetValueType.ByteArray
                "ManagedReferencesRegistry" -> AssetValueType.ManagedReferencesRegistry
                else -> AssetValueType.None
            }
        }
    }
}

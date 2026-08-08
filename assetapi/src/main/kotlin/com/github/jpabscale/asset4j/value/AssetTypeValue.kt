// Ported from AssetsTools.NET (MIT) — Copyright (c) 2019-2026 nesrak1
// Source: AssetTools.NET/Standard/AssetTypeClass/AssetTypeValue.cs (pinned 9aa8c6e)
package com.github.jpabscale.asset4j.value

import com.github.jpabscale.asset4j.serializedfile.AssetTypeReference

/**
 * Typed scalar value stored on a value field. Strings are stored as UTF-8 bytes (mirrors
 * the C#, which keeps `byte[]` in `Value` for strings).
 */
class AssetTypeValue {
    var valueType: AssetValueType = AssetValueType.None
    var value: Any? = null

    constructor(value: Boolean) {
        valueType = AssetValueType.Bool
        this.value = value
    }

    constructor(value: Byte) {
        valueType = AssetValueType.UInt8
        this.value = value
    }

    constructor(value: Short) {
        valueType = AssetValueType.Int16
        this.value = value
    }

    constructor(value: Int) {
        valueType = AssetValueType.Int32
        this.value = value
    }

    constructor(value: Long) {
        valueType = AssetValueType.Int64
        this.value = value
    }

    constructor(value: Float) {
        valueType = AssetValueType.Float
        this.value = value
    }

    constructor(value: Double) {
        valueType = AssetValueType.Double
        this.value = value
    }

    constructor(value: String) {
        valueType = AssetValueType.String
        this.value = value.toByteArray(Charsets.UTF_8)
    }

    constructor(value: ByteArray, asString: Boolean) {
        valueType = if (asString) AssetValueType.String else AssetValueType.ByteArray
        this.value = value
    }

    constructor(value: ManagedReferencesRegistry) {
        valueType = AssetValueType.ManagedReferencesRegistry
        this.value = value
    }

    constructor(valueType: AssetValueType, value: Any? = null) {
        this.valueType = valueType
        this.value = if (value is String) value.toByteArray(Charsets.UTF_8) else value
    }

    var asBool: Boolean
        get() {
            val v = value
            if (v is Boolean) {
                return v
            }
            if (v is Byte) {
                return v.toInt() == 1
            }
            return false
        }
        set(value) { this.value = value }

    var asSByte: Byte
        get() {
            val v = value
            return if (v is Byte) v else (v as Number).toByte()
        }
        set(value) { this.value = value }

    var asByte: Byte
        get() {
            val v = value
            return if (v is Byte) v else (v as Number).toByte()
        }
        set(value) { this.value = value }

    var asShort: Short
        get() {
            val v = value
            return if (v is Short) v else (v as Number).toShort()
        }
        set(value) { this.value = value }

    var asUShort: Int
        get() {
            val v = value
            return if (v is Int) v else (v as Number).toInt()
        }
        set(value) { this.value = value }

    var asInt: Int
        get() {
            val v = value
            return if (v is Int) v else (v as Number).toInt()
        }
        set(value) { this.value = value }

    var asUInt: Long
        get() {
            val v = value
            return if (v is Long) v else (v as Number).toLong()
        }
        set(value) { this.value = value }

    var asLong: Long
        get() {
            val v = value
            return if (v is Long) v else (v as Number).toLong()
        }
        set(value) { this.value = value }

    var asULong: Long
        get() {
            val v = value
            return if (v is Long) v else (v as Number).toLong()
        }
        set(value) { this.value = value }

    var asFloat: Float
        get() {
            val v = value
            return if (v is Float) v else (v as Number).toFloat()
        }
        set(value) { this.value = value }

    var asDouble: Double
        get() {
            val v = value
            return if (v is Double) v else (v as Number).toDouble()
        }
        set(value) { this.value = value }

    var asString: String
        get() {
            return when (valueType) {
                AssetValueType.String -> String(value as ByteArray, Charsets.UTF_8)
                AssetValueType.Bool -> if (value as Boolean) "true" else "false"
                AssetValueType.ByteArray -> simpleHexDump(value as ByteArray)
                else -> value.toString()
            }
        }
        set(value) { this.value = value.toByteArray(Charsets.UTF_8) }

    var asArray: AssetTypeArrayInfo
        get() = value as AssetTypeArrayInfo
        set(value) { this.value = value }

    var asByteArray: ByteArray
        get() = value as ByteArray
        set(value) { this.value = value }

    var asManagedReferencesRegistry: ManagedReferencesRegistry
        get() = value as ManagedReferencesRegistry
        set(value) { this.value = value }

    var asObject: Any?
        get() = value
        set(value) {
            this.value = if (value is String) value.toByteArray(Charsets.UTF_8) else value
        }

    fun clone(): AssetTypeValue {
        var clonedValue: Any? = null
        when (val v = value) {
            is Boolean -> clonedValue = v
            is Byte -> clonedValue = v
            is Short -> clonedValue = v
            is Int -> clonedValue = v
            is Long -> clonedValue = v
            is Float -> clonedValue = v
            is Double -> clonedValue = v
            is ByteArray -> clonedValue = v.clone()
            is AssetTypeArrayInfo -> clonedValue = v
            is ManagedReferencesRegistry -> {
                val references = mutableListOf<AssetTypeReferencedObject>()
                for (i in 0 until v.references.size) {
                    val origReference = v.references[i]
                    val ref = AssetTypeReferencedObject()
                    ref.rid = origReference.rid
                    ref.type = AssetTypeReference(
                        origReference.type.className,
                        origReference.type.nameSpace,
                        origReference.type.asmName,
                    )
                    ref.data = origReference.data?.clone()
                    references.add(ref)
                }
                val registry = ManagedReferencesRegistry()
                registry.version = v.version
                registry.references = references
                clonedValue = registry
            }
        }
        return AssetTypeValue(valueType, clonedValue)
    }

    override fun toString(): String = asString

    private fun simpleHexDump(byteArray: ByteArray): String {
        val sb = StringBuilder()
        if (byteArray.isEmpty())
            return ""
        var i = 0
        while (i < byteArray.size - 1) {
            sb.append("%02x".format(byteArray[i].toInt() and 0xFF))
            sb.append(" ")
            i++
        }
        sb.append("%02x".format(byteArray[i].toInt() and 0xFF))
        return sb.toString()
    }
}

// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.io

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AssetsFileReaderWriterTest {

    @Test
    fun roundTripAllPrimitivesLittleEndian() {
        val w = AssetsFileWriter()
        w.writeInt16(0x1234.toShort())
        w.writeUInt16(0xABCD)
        w.writeInt32(-123456)
        w.writeUInt32(0xDEADBEEFL)
        w.writeInt64(-9876543210123456L)
        w.writeUInt64(-2401053089206453570L)
        w.writeSingle(3.5f)
        w.writeDouble(-2.25e100)
        w.writeBoolean(true)
        w.writeByte(0x7F)
        w.writeBytes(byteArrayOf(1, 2, 3))

        val r = AssetsFileReader(w.toByteArray())
        assertEquals(0x1234.toShort(), r.readInt16())
        assertEquals(0xABCD, r.readUInt16())
        assertEquals(-123456, r.readInt32())
        assertEquals(0xDEADBEEFL, r.readUInt32())
        assertEquals(-9876543210123456L, r.readInt64())
        assertEquals(-2401053089206453570L, r.readUInt64())
        assertEquals(3.5f, r.readSingle())
        assertEquals(-2.25e100, r.readDouble())
        assertEquals(true, r.readBoolean())
        assertEquals(0x7F, r.readByte())
        assertArrayEquals(byteArrayOf(1, 2, 3), r.readBytes(3))
    }

    @Test
    fun roundTripAllPrimitivesBigEndian() {
        val w = AssetsFileWriter()
        w.bigEndian = true
        w.writeInt16(0x1234.toShort())
        w.writeUInt16(0xABCD)
        w.writeInt32(0x01020304)
        w.writeUInt32(0xDEADBEEFL)
        w.writeInt64(0x0102030405060708L)
        w.writeUInt64(0x1122334455667788L)

        val r = AssetsFileReader(w.toByteArray())
        r.bigEndian = true
        assertEquals(0x1234.toShort(), r.readInt16())
        assertEquals(0xABCD, r.readUInt16())
        assertEquals(0x01020304, r.readInt32())
        assertEquals(0xDEADBEEFL, r.readUInt32())
        assertEquals(0x0102030405060708L, r.readInt64())
        assertEquals(0x1122334455667788L, r.readUInt64())
    }

    @Test
    fun bigEndianWritesReversedBytes() {
        val w = AssetsFileWriter()
        w.bigEndian = true
        w.writeInt32(0x01020304)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), w.toByteArray())
    }

    @Test
    fun alignmentAdvancesCorrectly() {
        val w = AssetsFileWriter()
        w.writeByte(1)
        w.align()
        assertEquals(4, w.position)
        w.writeByte(2)
        w.align8()
        assertEquals(8, w.position)
        w.writeByte(3)
        w.align16()
        assertEquals(16, w.position)

        val bytes = w.toByteArray()
        val r = AssetsFileReader(bytes)
        assertEquals(1, r.readByte())
        r.align()
        assertEquals(4, r.position)
        assertEquals(2, r.readByte())
        r.align8()
        assertEquals(8, r.position)
        assertEquals(3, r.readByte())
        r.align16()
        assertEquals(16, r.position)
    }

    @Test
    fun reverseIntBytes() {
        val w = AssetsFileWriter()
        assertEquals(0x78563412, w.reverseInt(0x12345678))
    }

    @Test
    fun countStringsRoundTrip() {
        val w = AssetsFileWriter()
        w.writeCountString("hello")
        w.writeCountStringInt16("world")
        w.writeCountStringInt32("wide")
        w.writeNullTerminated("null")

        val r = AssetsFileReader(w.toByteArray())
        assertEquals("hello", r.readCountString())
        assertEquals("world", r.readCountStringInt16())
        assertEquals("wide", r.readCountStringInt32())
        assertEquals("null", r.readNullTerminated())
    }

    @Test
    fun int24RoundTrip() {
        val w = AssetsFileWriter()
        w.writeUInt24(0xABCDEF)
        w.writeInt24(-12345)
        val r = AssetsFileReader(w.toByteArray())
        assertEquals(0xABCDEFL, r.readUInt24())
        assertEquals((-12345).toLong() and 0xFFFFFFL, r.readInt24().toLong() and 0xFFFFFFL)
    }
}

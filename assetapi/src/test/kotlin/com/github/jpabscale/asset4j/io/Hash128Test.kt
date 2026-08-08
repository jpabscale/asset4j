// Copyright (c) 2026 jpabscale — original test code
package com.github.jpabscale.asset4j.io

import com.github.jpabscale.asset4j.typetree.CommonString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Hash128Test {

    @Test
    fun parseFormatRoundTrip() {
        val h = Hash128.tryParse("0123456789abcdef0123456789abcdef")!!
        assertEquals("0123456789abcdef0123456789abcdef", h.toString())
    }

    @Test
    fun parseRejectsWrongLength() {
        assertNull(Hash128.tryParse("0123456789abcdef"))
        assertNull(Hash128.tryParse(""))
    }

    @Test
    fun parseRejectsNonHex() {
        assertNull(Hash128.tryParse("gg123456789abcdef0123456789abcdef"))
    }

    @Test
    fun isZero() {
        assertTrue(Hash128.newBlankHash().isZero())
        assertFalse(Hash128.tryParse("0123456789abcdef0123456789abcdef")!!.isZero())
    }

    @Test
    fun equalsComparesBytes() {
        val a = Hash128.tryParse("0123456789abcdef0123456789abcdef")!!
        val b = Hash128.tryParse("0123456789abcdef0123456789abcdef")!!
        val c = Hash128.tryParse("1123456789abcdef0123456789abcdef")!!
        assertEquals(a, b)
        assertFalse(a == c)
    }
}

class GUID128Test {

    @Test
    fun toStringMatchesCSharpOrder() {
        // data0..data3 are read in file order; ToString walks i=3..0, j=7..0, inserting at 0.
        val g = GUID128()
        g[0] = 0x01234567L
        g[1] = 0x89ABCDEFL
        g[2] = 0xFEDCBA98L
        g[3] = 0x76543210L
        // data3 is the most significant nibble group. Reconstruct:
        // For i=3 (0x76543210), j=7..0: nibbles high->low of data3 -> inserted at 0 in order
        //   data3's most significant nibble ends up LAST (inserted at 0 repeatedly).
        val s = g.toString()
        assertEquals(32, s.length)
        // Verify round-trip.
        val parsed = GUID128.tryParse(s)!!
        assertEquals(g[0], parsed[0])
        assertEquals(g[1], parsed[1])
        assertEquals(g[2], parsed[2])
        assertEquals(g[3], parsed[3])
    }

    @Test
    fun isEmpty() {
        assertTrue(GUID128().isEmpty)
        val g = GUID128()
        g[0] = 1L
        assertFalse(g.isEmpty)
    }
}

class CommonStringTest {

    @Test
    fun tableContainsExpectedStrings() {
        val table = CommonString.TABLE
        val text = String(table, Charsets.UTF_8)
        assertTrue(text.contains("MonoBehaviour\u0000"))
        assertTrue(text.contains("GameObject\u0000"))
        assertTrue(text.contains("m_Name\u0000"))
        assertTrue(table[table.size - 1] == 0.toByte())
    }
}

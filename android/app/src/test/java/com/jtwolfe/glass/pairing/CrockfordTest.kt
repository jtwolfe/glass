package com.jtwolfe.glass.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrockfordTest {
    @Test
    fun acceptsMintCodeWithZeroAndOne() {
        assertTrue(Crockford.isValidCode("F41XS71T"))
        assertTrue(Crockford.isValidCode("f41xs71t"))
    }

    @Test
    fun rejectsIloU() {
        assertFalse(Crockford.isValidCode("AI234567"))
        assertFalse(Crockford.isValidCode("AL234567"))
        assertFalse(Crockford.isValidCode("AO234567"))
        assertFalse(Crockford.isValidCode("AU234567"))
        assertFalse(Crockford.isValidCode("ai234567"))
    }

    @Test
    fun rejectsWrongLength() {
        assertFalse(Crockford.isValidCode("F41XS71"))
        assertFalse(Crockford.isValidCode("F41XS71T0"))
        assertFalse(Crockford.isValidCode(""))
    }
}

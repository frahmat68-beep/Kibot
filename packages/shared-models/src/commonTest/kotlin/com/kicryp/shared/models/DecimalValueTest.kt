package com.kicryp.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals

class DecimalValueTest {
    @Test
    fun `returns zero for malformed numeric values`() {
        assertEquals(0.0, DecimalValue("oops").toDoubleOrZero())
    }
}

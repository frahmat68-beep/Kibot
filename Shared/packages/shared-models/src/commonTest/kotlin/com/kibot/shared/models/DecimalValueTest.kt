package com.kibot.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals

class DecimalValueTest {
    @Test
    fun `returns zero for malformed numeric values`() {
        assertEquals(0.0, DecimalValue("oops").toDoubleOrZero())
    }

    @Test
    fun `parses grouped integer and mixed locale values safely`() {
        assertEquals(1296999000.0, DecimalValue("1.296.999.000").toDoubleOrZero())
        assertEquals(1296999.25, DecimalValue("1,296,999.25").toDoubleOrZero())
        assertEquals(3.224, DecimalValue("3.224").toDoubleOrZero())
        assertEquals(10020000.0, DecimalValue("1.002E7").toDoubleOrZero())
    }
}

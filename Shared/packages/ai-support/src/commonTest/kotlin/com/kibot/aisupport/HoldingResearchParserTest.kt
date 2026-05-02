package com.kibot.aisupport

import kotlin.test.Test
import kotlin.test.assertEquals

class HoldingResearchParserTest {
    @Test
    fun `explicit emergency dump is parsed correctly`() {
        assertEquals(
            HoldingResearchAction.EMERGENCY_DUMP,
            parseHoldingResearchAction("ACTION: EMERGENCY_DUMP"),
        )
    }

    @Test
    fun `hold remains safe default for plain or noisy responses`() {
        assertEquals(
            HoldingResearchAction.HOLD,
            parseHoldingResearchAction("ACTION: HOLD"),
        )
        assertEquals(
            HoldingResearchAction.HOLD,
            parseHoldingResearchAction("Sepertinya masih aman dipantau dulu."),
        )
    }
}

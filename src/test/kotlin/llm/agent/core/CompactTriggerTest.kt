package org.iotsplab.akiba.llm.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The compaction trigger has two fixed rules and takes whichever fires first:
 * the fractional threshold of the window, and the window minus the headroom
 * reserved for the summarisation prompt itself.
 */
class CompactTriggerTest {

    @Test
    fun percentageRuleWinsOnLargeWindows() {
        // 1M window: 0.75 × window = 750k is far below 1M − 32k.
        assertEquals(750_000, compactTriggerTokens(1_000_000, 0.75))
    }

    @Test
    fun headroomRuleWinsWhenTheWindowIsSmall() {
        // 100k window: 75k by percentage, but only 68k leaves the headroom.
        assertEquals(68_000, compactTriggerTokens(100_000, 0.75))
    }

    @Test
    fun bothRulesAgreeAtTheHeadroomBoundary() {
        // 128k window: 96k by percentage and 96k by headroom.
        assertEquals(96_000, compactTriggerTokens(128_000, 0.75))
    }

    @Test
    fun windowsSmallerThanTheHeadroomKeepThePercentageRule() {
        // 20k window: 20k − 32k is negative, so the headroom rule cannot apply.
        assertEquals(15_000, compactTriggerTokens(20_000, 0.75))
    }
}

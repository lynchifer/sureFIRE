package surefire

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineTest {

    /** Reference parity: original (×(1+tax)) gross-up reproduces the screenshot's $1,070,000. */
    @Test
    fun referenceFireTargetOriginalMode() {
        assertEquals(1_070_000.0, Finance.fireTarget(40_000.0, 0.04, 0.07, correctTax = false), 1.0)
    }

    /** Corrected gross-up: /(1-tax) = 1,000,000 / 0.93. */
    @Test
    fun correctTaxGrossUp() {
        assertEquals(1_075_268.82, Finance.fireTarget(40_000.0, 0.04, 0.07, correctTax = true), 0.01)
    }

    @Test
    fun referenceSavingsRate() {
        assertEquals(0.25, Finance.savingsRate(60_000.0, 45_000.0), 1e-9)
    }
}

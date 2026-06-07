package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The life-event preset modeling (formerly in the TS UI) now lives — and is tested — in the engine. */
class PresetsTest {
    @Test
    fun childIsInflatingExpenseForYearsPlusBirthCost() {
        val flows = Presets.childFlows(startAge = 30, years = 18, annualCost = 15_000.0, birthCost = 5_000.0)
        assertEquals(2, flows.size)
        assertEquals(-15_000.0, flows[0].amount)
        assertEquals(30, flows[0].startAge)
        assertEquals(47, flows[0].endAge) // 30 + 18 - 1, inclusive
        assertTrue(flows[0].inflates)
        assertEquals(-5_000.0, flows[1].amount)
        assertEquals(30, flows[1].startAge)
        assertEquals(30, flows[1].endAge)
    }

    @Test
    fun childWithoutBirthCostHasOneFlow() {
        assertEquals(1, Presets.childFlows(30, 18, 15_000.0, 0.0).size)
    }

    @Test
    fun homeDownPaymentIsPriceTimesPct() {
        val p = Presets.homeProperty(35, 400_000.0, 0.2, 0.065, 30, 0.007, 0.02, 0.07, null)
        assertEquals(80_000.0, p.downPayment)
        assertEquals(400_000.0, p.price)
        assertEquals(0.07, p.sellingCostRate)
        assertEquals(null, p.sellAge)
    }

    @Test
    fun homeSellAgeKept() {
        assertEquals(60, Presets.homeProperty(35, 400_000.0, 0.2, 0.065, 30, 0.007, 0.02, 0.07, 60).sellAge)
    }

    @Test
    fun oneTimeIncomeVsExpenseSign() {
        assertEquals(20_000.0, Presets.oneTimeFlow(40, 20_000.0, income = true).amount)
        assertEquals(-20_000.0, Presets.oneTimeFlow(40, 20_000.0, income = false).amount)
    }

    @Test
    fun customStreamSignAndInflation() {
        val f = Presets.customFlow(30, 35, 10_000.0, income = false, inflates = false)
        assertEquals(-10_000.0, f.amount)
        assertEquals(30, f.startAge)
        assertEquals(35, f.endAge)
        assertTrue(!f.inflates)
    }
}

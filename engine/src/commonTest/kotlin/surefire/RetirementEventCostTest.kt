package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Life-event costs still active at the retirement (claim) age are folded into the FIRE target. */
class RetirementEventCostTest {
    @Test
    fun noEventsLeavesTheTargetAtParity() {
        val p = projectFixed(reference())
        assertEquals(0.0, p.retirementEventCost, 1e-9)
        assertEquals(1_070_000.0, p.fireTarget, 1.0) // the reference parity target is unchanged
    }

    @Test
    fun perpetualHomeUpkeepRaisesTheTarget() {
        // All-cash home owned through retirement (never sold) ⇒ 2% upkeep active at claim age 67.
        val home = Property(500_000.0, 500_000.0, 0.0, 0, appreciationReal = 0.0, ongoingCostRate = 0.02, buyAge = 40, sellAge = null)
        val p = projectFixed(reference().copy(properties = listOf(home)))
        assertEquals(10_000.0, p.retirementEventCost, 1.0) // 2% of 500k
        assertTrue(p.fireTarget > projectFixed(reference()).fireTarget)
    }

    @Test
    fun childCostEndingBeforeRetirementDoesNotChangeTheTarget() {
        val inp = reference().copy(cashFlows = listOf(CashFlow(-15_000.0, 32, 49, inflates = true))) // over by 67
        val p = projectFixed(inp)
        assertEquals(0.0, p.retirementEventCost, 1e-9)
        assertEquals(projectFixed(reference()).fireTarget, p.fireTarget, 1.0)
    }

    @Test
    fun mortgageStillOwedAtRetirementRaisesTheTarget() {
        val home = Property(400_000.0, 80_000.0, 0.06, 30, appreciationReal = 0.0, ongoingCostRate = 0.0, buyAge = 50, sellAge = null)
        val p = projectFixed(reference().copy(properties = listOf(home))) // 30-yr loan from 50 ⇒ still owing at 67
        assertTrue(p.retirementEventCost > 0.0)
    }

    @Test
    fun oneTimeLumpAtRetirementDoesNotChangeTheTarget() {
        // A windfall/cost landing exactly on the claim age (67) is one-time, not an ongoing obligation,
        // so it must NOT be capitalized into the FIRE target (÷ wr) — only recurring flows count.
        val inp = reference().copy(cashFlows = listOf(CashFlow(-20_000.0, 67, 67, inflates = true)))
        val p = projectFixed(inp)
        assertEquals(0.0, p.retirementEventCost, 1e-9)
        assertEquals(projectFixed(reference()).fireTarget, p.fireTarget, 1.0)
    }

    @Test
    fun pensionIncomeLowersTheTarget() {
        val inp = reference().copy(cashFlows = listOf(CashFlow(20_000.0, 60, 95, inflates = true))) // income active at 67
        val p = projectFixed(inp)
        assertEquals(-20_000.0, p.retirementEventCost, 1.0)
        assertTrue(p.fireTarget < projectFixed(reference()).fireTarget)
    }
}

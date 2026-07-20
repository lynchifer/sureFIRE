package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [wealthMilestones] — round-level crossings on the steady-return liquid path. The point of the feature
 * is the compounding story: each successive milestone arrives faster, so half the money takes well over
 * half the time.
 */
class MilestonesTest {
    @Test
    fun milestonesAscendAndEndAtTheTarget() {
        val p = projectFixed(reference())
        val m = wealthMilestones(p)
        assertTrue(m.amounts.size >= 3, "the reference plan should cross several round levels")
        for (i in 1 until m.amounts.size) {
            assertTrue(m.amounts[i] > m.amounts[i - 1], "amounts must ascend")
            assertTrue(m.years[i] > m.years[i - 1], "crossing times must ascend")
        }
        assertEquals(p.fireTarget, m.amounts.last(), 1e-9) // the final milestone IS the FIRE target
        assertEquals(p.yearsToFire, m.years.last(), 1e-9)
        assertTrue(m.amounts.first() > p.liquid[0], "levels already behind you don't count")
    }

    @Test
    fun halfTheMoneyTakesMoreThanHalfTheTime() {
        // THE insight: compounding back-loads growth, so the halfway crossing lands past the midpoint.
        val p = projectFixed(reference())
        val m = wealthMilestones(p)
        assertTrue(m.halfwayYears > p.yearsToFire / 2.0, "halfway $ (${m.halfwayYears}yr) must land past half the time (${p.yearsToFire / 2.0}yr)")
    }

    @Test
    fun laterMilestonesArriveFasterPerDollar() {
        // Dollars-per-year pace strictly improves between the first and last bracket of the journey.
        val p = projectFixed(reference())
        val m = wealthMilestones(p)
        val first = (m.amounts[0] - p.liquid[0]) / m.years[0]
        val n = m.amounts.size - 1
        val last = (m.amounts[n] - m.amounts[n - 1]) / (m.years[n] - m.years[n - 1])
        assertTrue(last > first, "the last stretch ($last/yr) must accumulate faster than the first ($first/yr)")
    }

    @Test
    fun unreachableTargetDropsTheTargetMilestone() {
        // Spending exceeds income ⇒ the balance drains and FI is never reached: only levels actually
        // crossed appear, and there is no target dot.
        val inp = reference().copy(income = 40_000.0, incomeGrowth = 0.0)
        val p = projectFixed(inp)
        val m = wealthMilestones(p)
        assertTrue(p.yearsToFire.isNaN(), "premise: FI must be unreachable")
        assertTrue(m.amounts.all { it < p.fireTarget }, "no target milestone when it's never reached")
        assertTrue(m.years.all { !it.isNaN() }, "every listed milestone must actually be crossed")
    }

    @Test
    fun highBalanceSkipsTheTinyEarlyLevels() {
        val p = projectFixed(reference().copy(initialInvestments = 600_000.0))
        val m = wealthMilestones(p)
        assertTrue(m.amounts.first() > 600_000.0, "levels below today's balance are already behind you")
    }
}

package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boundary / degenerate cases — "double check the math in all cases". */
class EdgeCaseTest {

    @Test
    fun alreadyAtFireGivesZeroYears() {
        val p = projectFixed(reference().copy(initialInvestments = 2_000_000.0)) // > $1.07M target
        assertEquals(0.0, p.yearsToFire, 0.0)
        assertEquals(32, p.ageAtFire)
    }

    @Test
    fun neverReachingTargetIsNaN() {
        // No contributions and no growth -> liquid is flat, target never reached.
        val p = projectFixed(
            reference().copy(
                income = 45_000.0, spending = 45_000.0, incomeGrowth = 0.0,
                stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0,
            ),
        )
        assertTrue(p.yearsToFire.isNaN())
        assertEquals(-1, p.ageAtFire)
        assertEquals(25_000.0, p.liquid[p.liquid.size - 1], 1e-6) // genuinely flat
    }

    @Test
    fun zeroRateMortgageIsStraightLine() {
        assertEquals(8_000.0, annualMortgagePayment(240_000.0, 0.0, 30), 1e-9) // 240k / 30
    }

    @Test
    fun negativeSavingsRateWhenSpendingExceedsIncome() {
        assertEquals(-0.25, Finance.savingsRate(40_000.0, 50_000.0), 1e-12)
    }

    @Test
    fun zeroWithdrawalRateIsGuardedToAFiniteTarget() {
        // WR = 0 used to divide the target to Infinity; the engine now coerces WR to a tiny positive
        // floor so the target stays finite (just enormous) and the app doesn't render NaN/∞.
        val p = projectFixed(reference().copy(withdrawalRate = 0.0))
        assertTrue(p.fireTarget.isFinite() && p.fireTarget > 1e9) // finite but huge
        assertTrue(p.yearsToFire.isNaN()) // still never reached within the horizon
    }

    @Test
    fun dissavingDrawsLiquidDown() {
        // Spending above income with no growth -> liquid strictly decreases.
        val p = projectFixed(
            reference().copy(
                income = 40_000.0, spending = 50_000.0, incomeGrowth = 0.0,
                stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0,
            ),
        )
        assertTrue(p.liquid[1] < p.liquid[0])
        assertTrue(p.liquid[5] < p.liquid[1])
    }

    @Test
    fun threeBandDecompositionReconstructsLiquid() {
        // initial + contributions + returns == liquid, the original engaging-data stack.
        val p = projectFixed(reference().copy(maxYears = 40))
        for (k in p.ages.indices) {
            val initial = 25_000.0
            val contributions = p.saved[k] - initial
            assertEquals(p.liquid[k], initial + contributions + p.returns[k], 1e-6)
        }
    }
}

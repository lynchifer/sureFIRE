package surefire

import kotlin.math.floor
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionTest {

    /** Round-half-up to one decimal, matching how the headline number is displayed. */
    private fun round1(x: Double) = floor(x * 10.0 + 0.5) / 10.0

    // --- Reference parity (the engaging-data screenshot) ---

    @Test
    fun growthRate() {
        assertEquals(0.06912, projectFixed(reference()).growthRate, 1e-9)
    }

    @Test
    fun fireTargetOriginalParity() {
        assertEquals(1_070_000.0, projectFixed(reference()).fireTarget, 1e-6)
    }

    @Test
    fun savingsRate() {
        assertEquals(0.25, projectFixed(reference()).savingsRate, 1e-12)
    }

    @Test
    fun yearsAndAgeReproduceScreenshot() {
        val p = projectFixed(reference())
        assertEquals(21.3, round1(p.yearsToFire), 1e-9)               // displayed "21.3 years"
        assertEquals(21.255604019681794, p.yearsToFire, 1e-9)         // exact regression guard
        assertEquals(53, p.ageAtFire)                                 // "age 53"
    }

    // --- Tax gross-up modes ---

    @Test
    fun correctTaxTarget() {
        val p = projectFixed(reference().copy(correctTax = true))
        assertEquals(40_000.0 / 0.04 / (1.0 - 0.07), p.fireTarget, 1e-6) // 1,075,268.82
    }

    // --- Spending / lifestyle creep ---

    @Test
    fun lifestyleCreepDelaysFire() {
        val p0 = projectFixed(reference())
        val p1 = projectFixed(reference().copy(lifestyleCreep = 0.02))
        assertTrue(p1.yearsToFire > p0.yearsToFire, "creep should push FIRE later")
    }

    // --- Cashflow streams ---

    @Test
    fun inflatingStreamIsConstantReal() {
        val p0 = projectFixed(reference())
        val p = projectFixed(reference().copy(cashFlows = listOf(CashFlow(-1_200.0, 32, 200, inflates = true))))
        assertEquals(-1_200.0, p.annualSavings[0] - p0.annualSavings[0], 1e-6)
        assertEquals(-1_200.0, p.annualSavings[10] - p0.annualSavings[10], 1e-6)
    }

    @Test
    fun nonInflatingStreamErodesInRealTerms() {
        val p0 = projectFixed(reference())
        val p = projectFixed(reference().copy(cashFlows = listOf(CashFlow(-1_200.0, 32, 200, inflates = false))))
        assertEquals(-1_200.0, p.annualSavings[0] - p0.annualSavings[0], 1e-6)
        assertEquals(-1_200.0 / 1.025.pow(10.0), p.annualSavings[10] - p0.annualSavings[10], 1e-6)
    }

    @Test
    fun lumpSumAppliesOneYearOnly() {
        val p0 = projectFixed(reference())
        val p = projectFixed(reference().copy(cashFlows = listOf(CashFlow(50_000.0, startAge = 40, endAge = 40))))
        for (t in 0 until 60) {
            val delta = p.annualSavings[t] - p0.annualSavings[t]
            if (t == 8) assertEquals(50_000.0, delta, 1e-6) // age 40 == currentAge(32)+8
            else assertEquals(0.0, delta, 1e-6)
        }
    }

    // --- Series integrity ---

    @Test
    fun seriesDecompositionConsistent() {
        val p = projectFixed(reference())
        assertEquals(25_000.0, p.liquid[0], 1e-9)
        for (k in p.ages.indices) {
            assertEquals(p.liquid[k], p.saved[k] + p.returns[k], 1e-6) // total = saved + returns
        }
    }

    @Test
    fun fireTiers() {
        val p = projectFixed(reference().copy(leanFactor = 0.65, fatFactor = 2.5))
        assertEquals(p.fireTarget * 0.65, p.leanTarget, 1e-6) // targets scale linearly with spend
        assertEquals(p.fireTarget * 2.5, p.fatTarget, 1e-6)
        assertTrue(p.leanYears <= p.yearsToFire) // lean reached sooner
        assertTrue(p.fatYears >= p.yearsToFire) // fat reached later
        assertEquals(p.yearsToFire, crossYears(p.liquid, 32, p.fireTarget).first, 1e-9) // FIRE tier == yearsToFire
    }
}

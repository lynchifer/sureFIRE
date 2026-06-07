package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbabilisticTest {

    // --- Monte Carlo ---

    @Test
    fun zeroVolatilityCollapsesToFixed() {
        // With σ=0 and MC means equal to the fixed returns, every path is the deterministic fixed path.
        val mc = monteCarlo(reference(), mcStockReturn = 0.081, mcBondReturn = 0.024, stockSd = 0.0, bondSd = 0.0, correlation = -0.2, nu = 5, runs = 40, seed = 1)
        val fixed = projectFixed(reference())
        assertEquals(21.255604019681794, mc.medianYears, 1e-6)
        for (k in fixed.ages.indices) assertEquals(fixed.netWorth[k], mc.p50[k], 1e-6)
        assertEquals(1.0, mc.successRate, 0.0)
    }

    @Test
    fun volatilityWidensTheBands() {
        val mc = monteCarlo(reference(), 0.081, 0.024, stockSd = 0.18, bondSd = 0.08, correlation = -0.2, nu = 5, runs = 400, seed = 3)
        val mid = mc.ages.size / 2
        assertTrue(mc.p90[mid] > mc.p10[mid], "bands should spread under volatility")
        assertTrue(mc.p75[mid] >= mc.p25[mid])
    }

    @Test
    fun reproducibleWithSameSeed() {
        val a = monteCarlo(reference(), 0.081, 0.024, 0.18, 0.08, -0.2, 5, runs = 200, seed = 42)
        val b = monteCarlo(reference(), 0.081, 0.024, 0.18, 0.08, -0.2, 5, runs = 200, seed = 42)
        for (k in a.ages.indices) assertEquals(a.p50[k], b.p50[k], 0.0)
    }

    // --- Life-path drawdown success (retire at the RE age) ---

    @Test
    fun lifeSuccessRateHighWhenOverfunded() {
        // Reference plan works to the default claim age (67) ⇒ richly funded ⇒ the drawdown almost always lasts.
        val mc = monteCarlo(reference(), 0.081, 0.024, 0.18, 0.08, -0.2, 5, runs = 200, seed = 9)
        assertTrue(mc.lifeSuccessRate > 0.95, "an over-funded retirement should almost always survive")
    }

    @Test
    fun lifeSuccessRateLowWhenRetiringTooEarlyBroke() {
        // Retire immediately at 32 on a tiny pot, no Social Security, drawing the full grossed-up spend ⇒ mostly fails.
        val inp = reference().copy(retireAge = 32, socialSecurity = 0.0, initialInvestments = 50_000.0)
        val mc = monteCarlo(inp, 0.081, 0.024, 0.18, 0.08, -0.2, 5, runs = 200, seed = 9)
        assertTrue(mc.lifeSuccessRate < 0.2, "retiring immediately on a tiny pot should mostly fail")
    }

    // --- Sensitivity ---

    @Test
    fun spendingSensitivityIsMonotonic() {
        val s = spendingSensitivity(reference(), doubleArrayOf(-0.2, 0.0, 0.2))
        assertTrue(s[0].yearsToFire < s[1].yearsToFire) // spend less -> retire sooner
        assertTrue(s[2].yearsToFire > s[1].yearsToFire) // spend more -> retire later
    }

    @Test
    fun stockReturnSensitivityIsMonotonic() {
        val s = stockReturnSensitivity(reference(), doubleArrayOf(-0.6, 0.0, 0.6))
        assertTrue(s[0].yearsToFire > s[1].yearsToFire) // lower returns -> retire later
        assertTrue(s[2].yearsToFire < s[1].yearsToFire) // higher returns -> retire sooner
    }

    @Test
    fun percentileBasics() {
        val a = doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0)
        assertEquals(0.0, percentileSorted(a, 0.0), 1e-9)
        assertEquals(20.0, percentileSorted(a, 0.5), 1e-9)
        assertEquals(40.0, percentileSorted(a, 1.0), 1e-9)
    }
}

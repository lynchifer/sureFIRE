package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The life plan ([Projection.lifeLiquid]): work/accumulate until the RE age (income stops), then draw
 * down to lifeExpectancy on portfolio withdrawals plus Social Security once it begins at the claim age.
 * With no explicit RE age (sentinel -1) retirement falls back to the claim age (the original behavior).
 */
class DrawdownPathTest {
    @Test
    fun retireAgeFallsBackToTheSocialSecurityClaimAge() {
        assertEquals(67, projectFixed(reference()).retireAge) // default: no RE age set ⇒ claim age
        assertEquals(62, projectFixed(reference().copy(socialSecurityAge = 62)).retireAge)
        assertEquals(70, projectFixed(reference().copy(socialSecurityAge = 70)).retireAge)
        assertEquals(62, projectFixed(reference().copy(socialSecurityAge = 50)).retireAge) // clamped: SS earliest is 62
    }

    @Test
    fun worksUntilClaimAgeThenDrawsDown() {
        // No growth ⇒ once income stops the balance can only fall (clean, hand-checkable).
        val inp = reference().copy(socialSecurityAge = 62, stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0)
        val p = projectFixed(inp)
        val retIdx = 62 - inp.currentAge
        for (t in 0..retIdx) assertEquals(p.liquid[t], p.lifeLiquid[t], 1e-6) // identical while working
        assertTrue(p.lifeLiquid[retIdx + 1] < p.lifeLiquid[retIdx]) // then draws down
        assertTrue(p.lifeLiquid[retIdx + 1] < p.liquid[retIdx + 1]) // below the keep-working path
    }

    @Test
    fun retiresAtClaimAgeEvenIfNotFinanciallyIndependent() {
        val inp = reference().copy(socialSecurityAge = 67, stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0)
        val p = projectFixed(inp)
        val retIdx = 67 - inp.currentAge
        assertEquals(67, p.retireAge)
        assertTrue(p.liquid[retIdx] < p.fireTarget) // retire before reaching the target
        val deathIdx = inp.lifeExpectancy - inp.currentAge
        assertTrue(p.lifeLiquid[deathIdx] < p.lifeLiquid[retIdx]) // and draw down what you have
    }

    @Test
    fun socialSecurityMakesTheMoneyLastLonger() {
        val base = reference().copy(socialSecurityAge = 62, stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0, socialSecurity = 0.0)
        val withSS = base.copy(socialSecurity = 30_000.0)
        val deathIdx = base.lifeExpectancy - base.currentAge
        assertTrue(projectFixed(withSS).lifeLiquid[deathIdx] > projectFixed(base).lifeLiquid[deathIdx])
    }

    @Test
    fun overfundedPortfolioStillGrowsThroughRetirement() {
        // Strong saver + real growth ⇒ even after retiring and withdrawing, the balance keeps growing.
        val inp = reference().copy(income = 200_000.0)
        val p = projectFixed(inp)
        val deathIdx = inp.lifeExpectancy - inp.currentAge
        assertTrue(p.lifeLiquid[deathIdx] > p.lifeLiquid[p.retireAge - inp.currentAge])
    }

    @Test
    fun depletedRetirementBalanceFloorsAtZeroNotNegative() {
        // Retire early at 62 with no Social Security and no growth ⇒ the balance must run out. It should
        // floor at 0 (you've simply run out), never compound a withdrawal into a deep-negative number.
        val inp = reference().copy(socialSecurityAge = 62, socialSecurity = 0.0, stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0)
        val p = projectFixed(inp)
        val deathIdx = inp.lifeExpectancy - inp.currentAge
        for (t in 0..deathIdx) assertTrue(p.lifeLiquid[t] >= 0.0, "lifeLiquid must never be negative (age ${inp.currentAge + t})")
        assertEquals(0.0, p.lifeLiquid[deathIdx], 1e-9) // fully depleted, resting at exactly 0
    }

    @Test
    fun earlyRetirementBridgesOnPortfolioUntilSocialSecurityStarts() {
        // Retire at 50, claim SS at 67: a bridge with NO Social Security, then SS begins. Strong saver +
        // 0% growth so the portfolio survives the whole horizon and the year-over-year drops are clean.
        val inp = reference().copy(retireAge = 50, socialSecurityAge = 67, socialSecurity = 24_000.0,
            income = 120_000.0, stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0)
        val p = projectFixed(inp)
        assertEquals(50, p.retireAge) // income stops at the chosen RE age, NOT the claim age
        val bridgeIdx = 58 - inp.currentAge // a year inside the bridge (50..66)
        val ssIdx = 70 - inp.currentAge     // a year after Social Security starts (≥67)
        assertTrue(p.lifeLiquid[ssIdx + 1] > 0.0, "the portfolio must survive the window under test")
        // FIXED total spending is constant across the SS boundary...
        assertEquals(p.lifeSpending[bridgeIdx], p.lifeSpending[ssIdx], 1e-6)
        // ...but the portfolio drains by the FULL spend during the bridge and only by (spend − SS) after,
        // so the year-over-year drop falls by exactly the Social Security benefit once it kicks in.
        val bridgeDrop = p.lifeLiquid[bridgeIdx] - p.lifeLiquid[bridgeIdx + 1]
        val ssDrop = p.lifeLiquid[ssIdx] - p.lifeLiquid[ssIdx + 1]
        assertTrue(bridgeDrop > ssDrop, "the portfolio drains faster before SS kicks in")
        assertEquals(24_000.0, bridgeDrop - ssDrop, 1.0) // the difference is exactly the SS benefit
    }

    @Test
    fun recurringRetirementCostsAreTaxGrossedInTheDrawdownLikeTheTarget() {
        // A recurring cost that lands only in retirement must drain the portfolio by the GROSSED-UP amount
        // (cost / (1 − tax)) — the same way retirementEventCost folds it into the FIRE target — not the raw
        // cost. Otherwise the success/depletion path is optimistic and disagrees with the headline target.
        val tax = 0.10
        val base = reference().copy(
            correctTax = true, taxRate = tax, socialSecurity = 0.0, income = 200_000.0, // over-funded ⇒ survives the window
            stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0, // no growth ⇒ the year-over-year drop is a clean cash identity
        )
        val cost = 10_000.0
        val withCost = base.copy(cashFlows = listOf(CashFlow(-cost, 67, 95, inflates = true))) // recurring, retirement-only
        val a = projectFixed(base)
        val b = projectFixed(withCost)
        val retIdx = a.retireAge - base.currentAge // claim age 67; cost starts exactly here
        assertEquals(a.lifeLiquid[retIdx], b.lifeLiquid[retIdx], 1e-6) // same balance entering retirement (accumulation identical)
        val i = 70 - base.currentAge // a retired year both plans comfortably survive
        val extraDrain = (b.lifeLiquid[i] - b.lifeLiquid[i + 1]) - (a.lifeLiquid[i] - a.lifeLiquid[i + 1])
        assertEquals(cost / (1.0 - tax), extraDrain, 1.0) // grossed up (≈11_111), NOT the raw 10_000
    }

    @Test
    fun lifeExpectancyIsConfigurableAndDoesNotMoveTheFireDate() {
        val p80 = projectFixed(reference().copy(lifeExpectancy = 80))
        val p100 = projectFixed(reference().copy(lifeExpectancy = 100))
        assertEquals(p80.yearsToFire, p100.yearsToFire, 1e-9)
    }

    @Test
    fun horizonAlwaysReachesTheDeathAgeSoLateDepletionsAreCaught() {
        // Age 20, death 105: maxYears 80 only covers to 100 — a plan that runs dry at ~101 must still be
        // caught, not silently counted as surviving because the simulation stopped early.
        val inp = reference().copy(
            currentAge = 20, lifeExpectancy = 105, maxYears = 80, retireAge = 60, socialSecurity = 0.0,
            income = 88_800.0, stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0, incomeGrowth = 0.0,
        )
        val p = projectFixed(inp)
        assertEquals(105 - 20, p.ages.size - 1, "the series must extend to the death age")
        // 25k + 40yr × 43.8k savings = 1.777M at 60; drawing 40k × 1.07 = 42.8k/yr ⇒ dry in year 101 —
        // past the 80-year (age-100) window, so a maxYears-truncated horizon would have missed it.
        assertTrue(p.depletionAge in 101..104, "the late depletion must be detected, got ${p.depletionAge}")
    }

    // A fully-paid home (100% down, no upkeep/appreciation/mortgage) whose ONLY spending effect is the
    // rent suppression, so the retirement draw's housing-awareness is isolated cleanly.
    private fun paidOffHome(buyAge: Int, sellAge: Int?) = Presets.homeProperty(
        buyAge, 400_000.0, 1.0, 0.0, 30, 0.0, 0.0, 0.0, sellAge,
    )

    @Test
    fun rentResumesWhenAHomeIsSoldDuringRetirement() {
        // Retired at 60 tracking current spending (45k incl. 18k rent); home held since 40, sold at 75.
        // While held the draw funds spending MINUS rent; after the sale you rent again, so the draw must
        // rise back to full spending × the tax gross-up (1.07 in parity mode) — not stay suppressed forever.
        val inp = reference().copy(
            retireAge = 60, income = 200_000.0, retirementSpending = 0.0, housing = 18_000.0,
            properties = listOf(paidOffHome(buyAge = 40, sellAge = 75)),
        )
        val p = projectFixed(inp)
        assertEquals((45_000.0 - 18_000.0) * 1.07, p.lifeSpending[70 - inp.currentAge], 1.0) // owned: rent-free
        assertEquals(45_000.0 * 1.07, p.lifeSpending[80 - inp.currentAge], 1.0) // sold: paying rent again
    }

    @Test
    fun rentStopsWhenAHomeIsBoughtDuringRetirement() {
        // Retired at 60, buy at 70: before the purchase you rent (full spending); after it the rent slice
        // is replaced by the home's own carrying costs (which flow separately as property flows) — the
        // draw must NOT keep charging rent on top of them.
        val inp = reference().copy(
            retireAge = 60, income = 200_000.0, retirementSpending = 0.0, housing = 18_000.0,
            properties = listOf(paidOffHome(buyAge = 70, sellAge = null)),
        )
        val p = projectFixed(inp)
        assertEquals(45_000.0 * 1.07, p.lifeSpending[65 - inp.currentAge], 1.0) // renting before the buy
        assertEquals((45_000.0 - 18_000.0) * 1.07, p.lifeSpending[75 - inp.currentAge], 1.0) // owned after
    }
}

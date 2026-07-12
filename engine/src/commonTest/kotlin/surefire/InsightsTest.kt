package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [insights] — marginal "biggest levers", each on its honest metric: cashflow nudges as deterministic
 * Δ years-to-FI, risk-shaping nudges as Δ Monte Carlo survival at the SAME (pinned) retire age.
 */
class InsightsTest {
    @Test
    fun cashflowLeversAccelerateFi() {
        val r = insights(reference())
        assertTrue(r.spendLessFiYears > 0.0, "spending less must reach FI sooner, got ${r.spendLessFiYears}")
        assertTrue(r.earnMoreFiYears > 0.0, "earning more must reach FI sooner, got ${r.earnMoreFiYears}")
    }

    @Test
    fun retiringLaterNeverHurtsSurvival() {
        // Paired paths (same seed): retiring later dominates path-by-path, so the delta can't be negative.
        val r = insights(reference().copy(retireAge = 55, socialSecurity = 24_000.0))
        assertTrue(r.retireLaterSurvival >= 0.0, "retire-later survival delta must be ≥ 0, got ${r.retireLaterSurvival}")
    }

    @Test
    fun lifestyleCreepLeverOnlyWhenCreeping() {
        assertTrue(insights(reference()).noCreepFiYears.isNaN(), "creep already 0 ⇒ the lever doesn't apply")
        assertTrue(insights(reference().copy(lifestyleCreep = 0.02)).noCreepFiYears > 0.0, "dropping real creep must reach FI sooner")
    }

    @Test
    fun socialSecurityLeverOnlyWhenApplicable() {
        assertTrue(insights(reference()).delaySsSurvival.isNaN(), "no Social Security ⇒ the claim-age lever doesn't apply")
        assertTrue(insights(reference().copy(socialSecurity = 24_000.0, socialSecurityAge = 70)).delaySsSurvival.isNaN(),
            "already claiming at 70 ⇒ can't delay further")
        val r = insights(reference().copy(socialSecurity = 24_000.0, socialSecurityAge = 67, retireAge = 55))
        assertTrue(!r.delaySsSurvival.isNaN(), "SS at 67 ⇒ the delay lever applies")
    }

    @Test
    fun retireAgeIsPinnedSoNoLeverSmugglesInLaterRetirement() {
        // retireAge = -1 falls back to the claim age. If the claim-age lever didn't pin retirement first,
        // delaying the claim would ALSO move the retire age (strictly safer), inflating its benefit.
        // Pinned correctly, the sentinel and an explicit retire-at-claim-age plan agree on every field.
        val base = reference().copy(socialSecurity = 24_000.0, socialSecurityAge = 67)
        val a = insights(base.copy(retireAge = -1))
        val b = insights(base.copy(retireAge = 67))
        assertEquals(a.spendLessFiYears, b.spendLessFiYears)
        assertEquals(a.earnMoreFiYears, b.earnMoreFiYears)
        assertEquals(a.retireLaterSurvival, b.retireLaterSurvival)
        assertEquals(a.delaySsSurvival, b.delaySsSurvival)
        assertEquals(a.allocShiftSurvival, b.allocShiftSurvival)
        assertEquals(a.guardrailsSurvival, b.guardrailsSurvival)
    }

    @Test
    fun guardrailsLeverOnlyFromFixed() {
        assertTrue(insights(reference().copy(withdrawalStrategy = WithdrawalStrategy.VPW)).guardrailsSurvival.isNaN(),
            "already on a flexible strategy ⇒ the guardrails lever doesn't apply")
        assertTrue(!insights(reference().copy(retireAge = 55)).guardrailsSurvival.isNaN(),
            "on fixed withdrawals ⇒ the guardrails lever applies")
    }

    @Test
    fun allStockPortfolioCanOnlyShiftTowardBonds() {
        val r = insights(reference().copy(stockPct = 1.0, bondPct = 0.0, cashPct = 0.0, retireAge = 55))
        assertTrue(!r.allocShiftToStocks, "100% stocks ⇒ the only direction is toward bonds")
        assertTrue(!r.allocShiftSurvival.isNaN(), "the toward-bonds direction must be evaluated")
        // The post-shift allocation is returned ready to apply — 10 pts drained from stocks into bonds.
        assertEquals(0.9, r.allocStockPct, 1e-12)
        assertEquals(0.1, r.allocBondPct, 1e-12)
        assertEquals(0.0, r.allocCashPct, 1e-12)
    }
}

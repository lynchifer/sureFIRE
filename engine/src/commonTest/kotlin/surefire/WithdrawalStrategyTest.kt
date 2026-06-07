package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The retirement [WithdrawalStrategy] only changes the drawdown ([Projection.lifeLiquid] /
 * [Projection.lifeSpending]); the accumulation, FIRE target, and tiers are unaffected. Default is FIXED,
 * so every other test (and the parity gate) keeps the original constant-real-spending behavior.
 */
class WithdrawalStrategyTest {
    @Test
    fun fixedSpendingIsConstantInRetirement() {
        val inp = reference() // default FIXED
        val p = projectFixed(inp)
        val retIdx = p.retireAge - inp.currentAge
        val deathIdx = inp.lifeExpectancy - inp.currentAge
        assertTrue(p.lifeSpending[retIdx] > 0.0)
        for (t in (retIdx + 1) until deathIdx) assertEquals(p.lifeSpending[retIdx], p.lifeSpending[t], 1e-6) // flat in real terms
    }

    @Test
    fun vpwSpendsTheSurplusInsteadOfHoarding() {
        val base = reference().copy(income = 200_000.0) // strongly over-funded ⇒ FIXED balloons
        val fixed = projectFixed(base)
        val vpw = projectFixed(base.copy(withdrawalStrategy = WithdrawalStrategy.VPW))
        val deathIdx = base.lifeExpectancy - base.currentAge
        val mid = (fixed.retireAge - base.currentAge + deathIdx) / 2
        assertTrue(vpw.lifeLiquid[deathIdx] < fixed.lifeLiquid[deathIdx]) // VPW draws down, FIXED hoards
        assertTrue(vpw.lifeSpending[mid] > fixed.lifeSpending[mid]) // and spends much more along the way
    }

    @Test
    fun guardrailsRaiseSpendingWhenOverfunded() {
        val base = reference().copy(income = 200_000.0)
        val fixed = projectFixed(base)
        val gk = projectFixed(base.copy(withdrawalStrategy = WithdrawalStrategy.GUARDRAILS))
        val retIdx = gk.retireAge - base.currentAge
        val deathIdx = base.lifeExpectancy - base.currentAge
        assertTrue(gk.lifeLiquid[deathIdx] < fixed.lifeLiquid[deathIdx]) // prosperity rule spends the surplus
        assertTrue(gk.lifeSpending[deathIdx - 1] > gk.lifeSpending[retIdx]) // spending ratcheted up over time
    }

    @Test
    fun guardrailsCutSpendingWhenUnderfunded() {
        // Retire early at 62 with no growth ⇒ a fixed plan depletes; guardrails cut to preserve capital.
        val base = reference().copy(socialSecurityAge = 62, stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0)
        val fixed = projectFixed(base)
        val gk = projectFixed(base.copy(withdrawalStrategy = WithdrawalStrategy.GUARDRAILS))
        val deathIdx = base.lifeExpectancy - base.currentAge
        assertTrue(gk.lifeLiquid[deathIdx] >= fixed.lifeLiquid[deathIdx]) // cuts preserve at least as much
    }
}

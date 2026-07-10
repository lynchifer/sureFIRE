package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [affordability] — the most of each single lever (extra retirement spend, a home, more kids) a plan can
 * add and still clear [MonteCarloModel.RECOMMEND_SURVIVAL] (80%) Monte Carlo survival, holding the retire
 * age and the rest of the plan fixed. The point of measuring at the SAME risk-adjusted bar as the
 * recommended age (rather than a deterministic steady-return test) is that the headroom stays honest — so
 * the load-bearing test is that the reported extra-spend sits right on the 80% survival boundary.
 */
class AffordabilityTest {
    // The home/child terms a UI seeds new events with (jsMain EventDefaults isn't visible here — mirror it).
    private fun afford(inp: FixedInputs) = affordability(
        inp,
        homeDownPct = 0.2, homeMortgageRate = 0.065, homeTermYears = 30,
        homeAppreciation = 0.007, homeOngoingPct = 0.02, homeSellPct = 0.07,
        childYears = 18, childAnnualCost = 15_000.0, childBirthCost = 5_000.0, childCollegeCost = 100_000.0,
    )

    private fun survival(inp: FixedInputs, runs: Int = MonteCarloModel.RECOMMEND_RUNS): Double =
        lifeSuccessRate(
            inp, inp.stockReturn, inp.bondReturn, MonteCarloModel.STOCK_SD, MonteCarloModel.BOND_SD,
            MonteCarloModel.CORRELATION, MonteCarloModel.NU, runs, MonteCarloModel.SEED,
        )

    private fun withExtraSpend(inp: FixedInputs, v: Double): FixedInputs {
        val effRetAge = Finance.effectiveRetireAge(inp.currentAge, inp.retireAge, inp.socialSecurityAge)
        return inp.copy(cashFlows = inp.cashFlows + Presets.customFlow(effRetAge, inp.lifeExpectancy, v, income = false, inflates = true))
    }

    @Test
    fun reportedExtraSpendSitsOnThe80PercentBoundary() {
        // A plan retiring a few years past the recommended age has real-but-bounded headroom.
        val rec = recommendedRetireAge(reference())
        val inp = reference().copy(retireAge = rec + 5)
        val a = afford(inp)

        assertTrue(a.survives, "the base plan should clear the bar at rec+5")
        assertTrue(a.extraSpend > 0.0, "expected positive spend headroom, got ${a.extraSpend}")
        assertTrue(!a.extraSpendAtCap, "the search should not have pegged the ceiling for this plan")

        // AT the reported extra spend the plan still clears 80%; well ABOVE it, it no longer does. That is
        // exactly the risk-adjusted boundary — proof the number isn't a rosy steady-return artifact.
        assertTrue(survival(withExtraSpend(inp, a.extraSpend)) >= MonteCarloModel.RECOMMEND_SURVIVAL,
            "survival at the reported extra spend must clear 80%")
        assertTrue(survival(withExtraSpend(inp, a.extraSpend + 30_000.0)) < MonteCarloModel.RECOMMEND_SURVIVAL,
            "survival well beyond the reported extra spend must fall below 80%")
    }

    @Test
    fun overfundedPlanAffordsEveryLever() {
        val inp = reference().copy(initialInvestments = 10_000_000.0, retireAge = 40)
        val a = afford(inp)
        assertTrue(a.survives)
        assertTrue(a.extraSpend > 0.0, "a 10M portfolio should afford extra spending")
        assertTrue(a.homePrice > 0.0, "a 10M portfolio should afford a home")
        assertTrue(a.kids >= 1, "a 10M portfolio should afford at least one more child")
    }

    @Test
    fun stretchedPlanHasNoHeadroom() {
        // Retiring now with nothing saved on a modest income can't clear the bar ⇒ every lever is zero / n-a.
        val inp = reference().copy(initialInvestments = 0.0, retireAge = reference().currentAge)
        val a = afford(inp)
        assertTrue(!a.survives, "a broke early retirement must not clear the bar")
        assertEquals(0.0, a.extraSpend)
        assertEquals(0, a.kids)
    }

    @Test
    fun ownedHomeMakesTheHomeLeverNotApplicable() {
        val home = Presets.homeProperty(35, 400_000.0, 0.2, 0.065, 30, 0.007, 0.02, 0.07, null)
        val inp = reference().copy(initialInvestments = 10_000_000.0, retireAge = 40, properties = listOf(home))
        val a = afford(inp)
        assertTrue(a.survives)
        assertTrue(a.homePrice < 0.0, "already owning a home ⇒ the home lever is not applicable")
    }
}

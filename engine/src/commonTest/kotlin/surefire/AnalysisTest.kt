package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [analysis] — the consolidated entry point: recommended age + resolved retire age + survival +
 * affordability + insights in one result, all evaluated at the same resolved age. Also covers the
 * [Projection.retireSpend] exposure (the resolved retirement-spend the UI must display, never re-derive).
 */
class AnalysisTest {
    private fun run(inp: FixedInputs) = analysis(
        inp,
        homeDownPct = 0.2, homeMortgageRate = 0.065, homeTermYears = 30,
        homeAppreciation = 0.007, homeOngoingPct = 0.02, homeSellPct = 0.07,
        childYears = 18, childAnnualCost = 15_000.0, childBirthCost = 5_000.0, childCollegeCost = 100_000.0,
    )

    @Test
    fun sentinelRetireAgeResolvesToTheRecommendation() {
        val inp = reference().copy(retireAge = -1)
        val r = run(inp)
        assertEquals(recommendedRetireAge(inp), r.recommendedRetireAge)
        assertEquals(r.recommendedRetireAge, r.retireAge, "sentinel must resolve to the recommendation, not the claim age")
    }

    @Test
    fun explicitRetireAgeIsHonored() {
        val r = run(reference().copy(retireAge = 60))
        assertEquals(60, r.retireAge)
        assertTrue(r.recommendedRetireAge != 0, "the recommendation is still computed alongside")
    }

    @Test
    fun piecesAgreeWithTheStandaloneFunctions() {
        // The consolidated call must be the same math as the parts, evaluated at the resolved age.
        val inp = reference().copy(retireAge = 60)
        val r = run(inp)
        val standalone = affordability(
            inp, 0.2, 0.065, 30, 0.007, 0.02, 0.07, 18, 15_000.0, 5_000.0, 100_000.0,
        )
        assertEquals(standalone.extraSpend, r.affordability.extraSpend)
        assertEquals(insights(inp).spendLessFiYears, r.insights.spendLessFiYears)
        assertTrue(r.lifeSuccess in 0.0..1.0)
    }

    @Test
    fun retireSpendResolvesTheFallbackHousingAware() {
        // No override, no home: retirement spending tracks total spending.
        val noHome = reference().copy(retirementSpending = 0.0, spending = 45_000.0, housing = 18_000.0)
        assertEquals(45_000.0, projectFixed(noHome).retireSpend)
        // A home held at retirement replaces the rent slice: the resolved spend must drop by `housing`.
        val home = Presets.homeProperty(40, 400_000.0, 0.2, 0.065, 30, 0.007, 0.02, 0.07, null)
        val withHome = noHome.copy(properties = listOf(home), retireAge = 60)
        assertEquals(45_000.0 - 18_000.0, projectFixed(withHome).retireSpend)
        // An explicit override is used verbatim, home or not.
        assertEquals(52_000.0, projectFixed(withHome.copy(retirementSpending = 52_000.0)).retireSpend)
    }
}

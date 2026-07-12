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
    fun coastingStopsContributionsButKeepsCompounding() {
        // Coasting from today: no growth ⇒ the balance must stay EXACTLY flat (no contributions, no draw
        // until retirement); with growth it must compound from the initial balance alone.
        val flat = reference().copy(stockReturn = 0.0, bondReturn = 0.0, cashReturn = 0.0, coastFromAge = 32, retireAge = 60)
        val p = projectFixed(flat)
        for (t in 0..(60 - 32)) assertEquals(25_000.0, p.liquid[t], 1e-6, "coasting balance must stay flat at age ${32 + t}")
        val growing = projectFixed(reference().copy(coastFromAge = 32, retireAge = 60))
        assertTrue(growing.liquid[10] > 25_000.0, "with growth the coasting portfolio still compounds")
        assertTrue(growing.liquid[10] < projectFixed(reference().copy(retireAge = 60)).liquid[10], "but below the keep-saving path")
    }

    @Test
    fun coastAgeSitsExactlyOnTheSurvivalBoundary() {
        val inp = reference().copy(retireAge = 62, socialSecurity = 24_000.0)
        val r = run(inp)
        assertTrue(r.coastAge in inp.currentAge..62, "expected a coast age within working life, got ${r.coastAge}")
        val ok = { c: Int -> mcSurvival(inp.copy(coastFromAge = c), MonteCarloModel.RECOMMEND_RUNS) >= MonteCarloModel.RECOMMEND_SURVIVAL }
        assertTrue(ok(r.coastAge), "coasting from the reported age must clear ~80%")
        if (r.coastAge > inp.currentAge) assertTrue(!ok(r.coastAge - 1), "coasting a year earlier must miss it")
    }

    @Test
    fun overfundedPlanCoastsTodayAndItsWorstDecileSurvives() {
        val r = run(reference().copy(initialInvestments = 10_000_000.0, retireAge = 50))
        assertEquals(reference().currentAge, r.coastAge, "a 10M portfolio can stop saving immediately")
        assertEquals(-1, r.p10DepletionAge, "even the roughest decile must survive")
        assertTrue(r.p10FinalBalance > 0.0, "and still leave money at the death age")
    }

    @Test
    fun stretchedPlanReportsTheWorstDecileDryAge() {
        // Retiring well before the recommendation ⇒ survival far below 90%, so the tenth-percentile path
        // runs dry at a real age before the death age (and the p10 final balance is exactly 0).
        val inp = reference().copy(retireAge = 45)
        val r = run(inp)
        assertTrue(r.lifeSuccess < 0.9, "premise: this plan must be risky, got ${r.lifeSuccess}")
        assertTrue(r.p10DepletionAge in 45 until inp.lifeExpectancy, "expected a real dry age, got ${r.p10DepletionAge}")
        assertEquals(0.0, r.p10FinalBalance, 1e-9)
    }

    @Test
    fun lifestyleLadderIsMonotonicInSpending() {
        // A richer retirement needs a later ~80%-safe start: leanAge ≤ the 1× recommendation ≤ fatAge.
        // (reference() leaves the tier factors at 1.0 — set the app's real lean/fat spread.)
        val r = run(reference().copy(socialSecurity = 24_000.0, leanFactor = 0.65, fatFactor = 2.0))
        assertTrue(r.leanRetireAge <= r.recommendedRetireAge, "lean (${r.leanRetireAge}) must not be later than 1× (${r.recommendedRetireAge})")
        assertTrue(r.recommendedRetireAge <= r.fatRetireAge, "fat (${r.fatRetireAge}) must not be earlier than 1× (${r.recommendedRetireAge})")
        assertTrue(r.fatRetireAge > r.leanRetireAge, "with fatFactor 2× vs leanFactor 0.65× the ladder must actually spread")
        assertEquals(40_000.0, r.retireSpendBase) // reference() sets an explicit override — used verbatim
    }

    @Test
    fun overfundedPlanRetiresTodayOnEveryTier() {
        val r = run(reference().copy(initialInvestments = 50_000_000.0))
        assertEquals(reference().currentAge, r.leanRetireAge)
        assertEquals(reference().currentAge, r.fatRetireAge)
    }

    @Test
    fun fiProgressIsTodaysBalanceOverTheTarget() {
        val p = projectFixed(reference())
        assertEquals(25_000.0 / p.fireTarget, p.fiProgress, 1e-12)
        assertEquals(1.0, projectFixed(reference().copy(initialInvestments = 10_000_000.0)).fiProgress, 1e-12) // clamped
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

package surefire

/**
 * Everything the plan card's ANALYSIS story needs, in ONE call: the recommended retire age, the resolved
 * retire age actually analyzed, the survival odds at that age, the affordability headroom, and the
 * biggest-levers insights. All at the same risk-adjusted Monte Carlo footing, so the pieces can't drift
 * apart (the callout, chips, and levers all describe the SAME plan at the SAME age).
 *
 * Retire-age resolution: an explicit [FixedInputs.retireAge] > 0 is honored; the ≤ 0 sentinel resolves
 * to the RECOMMENDED age (the earliest that clears ~80% survival) — this is the "auto-tracks the
 * recommendation" behavior a UI wants for its analysis, unlike the projection entry points, whose
 * sentinel falls back to the Social Security claim age.
 */
class AnalysisResult(
    val recommendedRetireAge: Int, // earliest age clearing ~80% Monte Carlo survival
    val retireAge: Int,            // the age analyzed: the explicit input, else the recommendation
    val lifeSuccess: Double,       // survival to the death age retiring AT [retireAge] (full-run Monte Carlo)
    // Coast FI: the earliest age you could stop saving entirely (earn just your spending until the retire
    // age; the portfolio compounds untouched) and still clear ~80% survival. -1 = no coasting slack.
    val coastAge: Int,
    val p10DepletionAge: Int,      // the roughest-decile path's dry age; -1 = even it lasts to the death age
    val p10FinalBalance: Double,   // the roughest-decile balance at death (0 when ≥10% of paths deplete)
    val affordability: Affordability,
    val insights: Insights,
)

fun analysis(
    inp0: FixedInputs,
    homeDownPct: Double, homeMortgageRate: Double, homeTermYears: Int,
    homeAppreciation: Double, homeOngoingPct: Double, homeSellPct: Double,
    childYears: Int, childAnnualCost: Double, childBirthCost: Double, childCollegeCost: Double,
): AnalysisResult {
    val rec = recommendedRetireAge(inp0)
    val eff = if (inp0.retireAge > 0) maxOf(inp0.currentAge, inp0.retireAge) else rec
    val inp = inp0.copy(retireAge = eff)
    // Survival + the roughest-decile outcome in ONE full-run pass — the headline "Survives" number, so it
    // must agree with the Monte Carlo view; the searches below keep the lean run count.
    val outcomes = lifeOutcomes(inp, MonteCarloModel.RUNS)
    // Coast FI: survival is monotonic in the coast age (coasting later means strictly more saved), and
    // coasting from the retire age itself is the unchanged plan — so a leftmost-true bisection is exact.
    val coastAge = run {
        fun ok(c: Int) = mcSurvival(inp.copy(coastFromAge = c), MonteCarloModel.RECOMMEND_RUNS) >= MonteCarloModel.RECOMMEND_SURVIVAL
        if (eff <= inp0.currentAge || !ok(eff)) -1
        else {
            var lo = inp0.currentAge
            var hi = eff
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (ok(mid)) hi = mid else lo = mid + 1
            }
            lo
        }
    }
    val afford = affordability(
        inp, homeDownPct, homeMortgageRate, homeTermYears, homeAppreciation, homeOngoingPct, homeSellPct,
        childYears, childAnnualCost, childBirthCost, childCollegeCost,
    )
    return AnalysisResult(rec, eff, outcomes.survival, coastAge, outcomes.p10DepletionAge, outcomes.p10FinalBalance, afford, insights(inp))
}

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
    // Survival at the analyzed age uses the full run count — it's the headline "Survives" number, so it
    // must agree with the Monte Carlo view; the searches inside affordability/insights keep the lean count.
    val life = lifeSuccessRate(
        inp, inp.stockReturn, inp.bondReturn, MonteCarloModel.STOCK_SD, MonteCarloModel.BOND_SD,
        MonteCarloModel.CORRELATION, MonteCarloModel.NU, MonteCarloModel.RUNS, MonteCarloModel.SEED,
    )
    val afford = affordability(
        inp, homeDownPct, homeMortgageRate, homeTermYears, homeAppreciation, homeOngoingPct, homeSellPct,
        childYears, childAnnualCost, childBirthCost, childCollegeCost,
    )
    return AnalysisResult(rec, eff, life, afford, insights(inp))
}

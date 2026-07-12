package surefire

/**
 * "Biggest levers" — the marginal effect of small, concrete actions on the plan, each measured on the
 * metric that is HONEST for that lever:
 *
 *  - Pure cashflow nudges (spend less, earn more, drop lifestyle creep) don't change the risk profile,
 *    so their effect is the deterministic Δ years-to-FI (positive = FI sooner).
 *  - Risk-shaping nudges (retire later, delay Social Security, shift the allocation, guardrails
 *    withdrawals) are measured as Δ Monte Carlo survival at the SAME retire age — deterministically,
 *    more stocks always looks strictly better (higher mean, zero volatility), which is exactly the
 *    steady-return fallacy [affordability] exists to avoid. Same seed on both sides ⇒ paired paths,
 *    so a delta is "how many paths flip", not sampling noise.
 *
 * NaN marks a lever that doesn't apply to this plan (creep already 0, claim age already 70, …).
 */
class Insights(
    val spendLessFiYears: Double,    // spending − SPEND_PER_YEAR ⇒ FI this many years sooner
    val earnMoreFiYears: Double,     // income + INCOME_PER_YEAR ⇒ FI this many years sooner
    val noCreepFiYears: Double,      // lifestyle creep → 0 ⇒ FI this many years sooner (NaN if already 0)
    val retireLaterSurvival: Double, // retire one year later ⇒ Δ survival (fraction, e.g. +0.06)
    val delaySsSurvival: Double,     // claim Social Security one year later ⇒ Δ survival
    val allocShiftSurvival: Double,  // shift ALLOC_SHIFT of the portfolio (the better direction) ⇒ Δ survival
    val allocShiftToStocks: Boolean, // true = the better direction is toward stocks, false = toward bonds
    val guardrailsSurvival: Double,  // switch fixed → guardrails withdrawals ⇒ Δ survival (NaN if not on fixed)
)

/** The nudge sizes the levers test (exported so UI copy always matches the math). */
object InsightNudges {
    const val SPEND_PER_YEAR = 1_200.0  // "spend $100/mo less"
    const val INCOME_PER_YEAR = 5_000.0 // "earn $5k/yr more"
    const val ALLOC_SHIFT = 0.10        // "move 10 pts of the allocation"
}

fun insights(inp0: FixedInputs, runs: Int = MonteCarloModel.RECOMMEND_RUNS): Insights {
    // Pin the retire age up front so no lever can smuggle in "also retire later": retireAge ≤ 0 falls
    // back to the claim age, so bumping the claim age would otherwise silently move retirement too.
    val effRet = Finance.effectiveRetireAge(inp0.currentAge, inp0.retireAge, inp0.socialSecurityAge)
    val inp = inp0.copy(retireAge = effRet)

    fun fiYears(i: FixedInputs): Double = projectFixed(i).yearsToFire
    fun surv(i: FixedInputs): Double = lifeSuccessRate(
        i, i.stockReturn, i.bondReturn, MonteCarloModel.STOCK_SD, MonteCarloModel.BOND_SD,
        MonteCarloModel.CORRELATION, MonteCarloModel.NU, runs, MonteCarloModel.SEED,
    )

    val baseFi = fiYears(inp)
    val baseSurv = surv(inp)

    // Positive = FI sooner. NaN unless both sides reach FI within the horizon.
    fun fiDelta(i: FixedInputs): Double {
        val v = fiYears(i)
        return if (baseFi.isNaN() || v.isNaN()) Double.NaN else baseFi - v
    }

    val spendLess = fiDelta(inp.copy(spending = (inp.spending - InsightNudges.SPEND_PER_YEAR).coerceAtLeast(0.0)))
    val earnMore = fiDelta(inp.copy(income = inp.income + InsightNudges.INCOME_PER_YEAR))
    val noCreep = if (inp.lifestyleCreep > 0.0) fiDelta(inp.copy(lifestyleCreep = 0.0)) else Double.NaN

    val retireLater = if (effRet < inp.lifeExpectancy) surv(inp.copy(retireAge = effRet + 1)) - baseSurv else Double.NaN

    val ca = Finance.claimAge(inp.socialSecurityAge)
    val delaySs = if (inp.socialSecurity > 0.0 && ca < 70) surv(inp.copy(socialSecurityAge = ca + 1)) - baseSurv else Double.NaN

    // Allocation: try ALLOC_SHIFT of the portfolio each way — toward stocks (drawn from bonds, then
    // cash) and toward bonds (drawn from stocks) — and report the better direction. Skip a direction
    // when there isn't a meaningful slice (< 2 pts) to move.
    fun towardStocks(): FixedInputs? {
        val take = minOf(InsightNudges.ALLOC_SHIFT, inp.bondPct + inp.cashPct)
        if (take < 0.02) return null
        val fromBonds = minOf(take, inp.bondPct)
        return inp.copy(stockPct = inp.stockPct + take, bondPct = inp.bondPct - fromBonds, cashPct = inp.cashPct - (take - fromBonds))
    }
    fun towardBonds(): FixedInputs? {
        val take = minOf(InsightNudges.ALLOC_SHIFT, inp.stockPct)
        if (take < 0.02) return null
        return inp.copy(stockPct = inp.stockPct - take, bondPct = inp.bondPct + take)
    }
    val up = towardStocks()?.let { surv(it) - baseSurv } ?: Double.NaN
    val down = towardBonds()?.let { surv(it) - baseSurv } ?: Double.NaN
    val toStocks = !up.isNaN() && (down.isNaN() || up >= down)
    val alloc = if (toStocks) up else down // NaN when neither direction has a slice to move

    val guardrails = if (inp.withdrawalStrategy == WithdrawalStrategy.FIXED)
        surv(inp.copy(withdrawalStrategy = WithdrawalStrategy.GUARDRAILS)) - baseSurv else Double.NaN

    return Insights(spendLess, earnMore, noCreep, retireLater, delaySs, alloc, toStocks, guardrails)
}

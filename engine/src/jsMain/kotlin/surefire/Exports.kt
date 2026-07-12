@file:OptIn(ExperimentalJsExport::class)

package surefire

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * The sureFIRE engine's public JS/TS API. Only interop-safe types cross the boundary (Double/Int/
 * Boolean/String, @JsExport classes, and typed arrays Int32Array/Float64Array).
 *
 * Contract for a frontend (this is the ONLY surface a UI needs — swap the UI freely):
 *   1. Build life events with the `*Event` factories.
 *   2. Build ONE [FireInputsJs] (all the knobs, plus the events array).
 *   3. Call the entry point for each speed tier: [projectFixedJs] + [eventImpactsJs] are cheap
 *      deterministic runs (call per keystroke), [analysisJs] + [monteCarloJs] are Monte Carlo work
 *      (call debounced). Each returns EVERYTHING its tier computes in one object.
 * ALL modeling lives behind here: tax gross-up, the retirement-spend fallback, which events count
 * (disabled ones are dropped), life-event compilation, tiers, drawdown, Monte Carlo, impacts, analysis.
 */

/** Claim-age-adjusted annual Social Security benefit, given the benefit at full retirement age. */
@JsExport
fun socialSecurityBenefitJs(pia: Double, claimAge: Int): Double = Finance.socialSecurityBenefit(pia, claimAge)

// --- Life-event presets ----------------------------------------------------------------------------
// The engine owns what each preset *costs* (see commonMain Presets) AND the default values a UI should
// seed a new event with (EventDefaults) — so every frontend models them identically.

/** Default modeling values for a freshly-added event (a UI seeds its form from these). */
@JsExport
object EventDefaults {
    const val childYears: Int = 18
    const val childAnnualCost: Double = 15_000.0
    const val childBirthCost: Double = 5_000.0
    // ≈ 4 years all-in (tuition + room & board) at a public in-state university, in today's dollars
    // (College Board 2024-25 budgets run ~$25-30k/yr). Lands as one lump when the child turns 18.
    const val childCollegeCost: Double = 100_000.0
    const val homePrice: Double = 400_000.0
    const val homeDownPct: Double = 0.2
    const val homeMortgageRate: Double = 0.065
    const val homeTermYears: Int = 30
    const val homeAppreciation: Double = 0.007
    const val homeOngoingPct: Double = 0.02
    const val homeSellPct: Double = 0.07
    const val oneTimeAmount: Double = 20_000.0
    const val customAmount: Double = 10_000.0
    const val marriageCeremonyCost: Double = 30_000.0
    const val marriageSpouseIncome: Double = 60_000.0
    const val marriageSpouseSpending: Double = 15_000.0
    const val marriageSpouseNetWorth: Double = 50_000.0
    const val sabbaticalYears: Int = 1
    const val sabbaticalReduction: Double = 1.0 // fraction of income removed (1.0 = full pause)
}

@JsExport
class LifeEventInput(
    val kind: String, // "child" | "home" | "oneTime" | "custom" | "marriage" | "sabbatical"
    val startAge: Int = 0,
    val endAge: Int = 0,
    val years: Int = 0,
    val annualCost: Double = 0.0,
    val birthCost: Double = 0.0,
    val collegeCost: Double = 0.0, // child: lump-sum college cost (today's $) at birth + 18

    val buyAge: Int = 0,
    val price: Double = 0.0,
    val downPct: Double = 0.0,
    val mortgageRate: Double = 0.0,
    val termYears: Int = 0,
    val appreciation: Double = 0.0,
    val ongoingPct: Double = 0.0,
    val sellPct: Double = 0.0,
    val sellAge: Int = -1, // -1 => never sold
    val age: Int = 0,
    val amount: Double = 0.0,
    val income: Boolean = false,
    val inflates: Boolean = true,
    val ceremonyCost: Double = 0.0,  // marriage: one-time ceremony expense
    val spouseIncome: Double = 0.0,  // marriage: spouse's annual real income (begins at `age`, stops at RE)
    val spouseSpending: Double = 0.0, // marriage: extra annual household spending (begins at `age`, lasts through retirement)
    val spouseNetWorth: Double = 0.0, // marriage: spouse's net worth, a one-time lump into your liquid
    val spouse: Boolean = false,     // sabbatical: false = your income, true = the spouse's
    val reduction: Double = 1.0,     // sabbatical: fraction of that earner's income removed (1.0 = full pause)
    val enabled: Boolean = true, // a disabled event is kept in the list but excluded from the projection
)

@JsExport
fun childEvent(startAge: Int, years: Int, annualCost: Double, birthCost: Double, collegeCost: Double, enabled: Boolean): LifeEventInput =
    LifeEventInput(kind = "child", startAge = startAge, years = years, annualCost = annualCost, birthCost = birthCost, collegeCost = collegeCost, enabled = enabled)

@JsExport
fun homeEvent(buyAge: Int, price: Double, downPct: Double, mortgageRate: Double, termYears: Int, appreciation: Double, ongoingPct: Double, sellPct: Double, sellAge: Int, enabled: Boolean): LifeEventInput =
    LifeEventInput(kind = "home", buyAge = buyAge, price = price, downPct = downPct, mortgageRate = mortgageRate, termYears = termYears, appreciation = appreciation, ongoingPct = ongoingPct, sellPct = sellPct, sellAge = sellAge, enabled = enabled)

@JsExport
fun oneTimeEvent(age: Int, amount: Double, income: Boolean, enabled: Boolean): LifeEventInput =
    LifeEventInput(kind = "oneTime", age = age, amount = amount, income = income, enabled = enabled)

@JsExport
fun customEvent(startAge: Int, endAge: Int, amount: Double, income: Boolean, inflates: Boolean, enabled: Boolean): LifeEventInput =
    LifeEventInput(kind = "custom", startAge = startAge, endAge = endAge, amount = amount, income = income, inflates = inflates, enabled = enabled)

@JsExport
fun marriageEvent(age: Int, ceremonyCost: Double, spouseIncome: Double, spouseSpending: Double, spouseNetWorth: Double, enabled: Boolean): LifeEventInput =
    LifeEventInput(kind = "marriage", age = age, ceremonyCost = ceremonyCost, spouseIncome = spouseIncome, spouseSpending = spouseSpending, spouseNetWorth = spouseNetWorth, enabled = enabled)

@JsExport
fun sabbaticalEvent(spouse: Boolean, startAge: Int, years: Int, reduction: Double, enabled: Boolean): LifeEventInput =
    LifeEventInput(kind = "sabbatical", spouse = spouse, startAge = startAge, years = years, reduction = reduction, enabled = enabled)

/** Result of compiling life events: the cashflow/property primitives plus the two-earner extras. */
private class Compiled(
    val cashFlows: List<CashFlow>,
    val properties: List<Property>,
    val spouseIncome: Double,
    val spouseIncomeStartAge: Int,
    val sabbaticals: List<Sabbatical>,
)

/** Compile ENABLED life-event presets to engine primitives. Dispatch only — modeling lives in [Presets]. */
private fun compileEvents(events: Array<LifeEventInput>): Compiled {
    val cashFlows = mutableListOf<CashFlow>()
    val properties = mutableListOf<Property>()
    val sabbaticals = mutableListOf<Sabbatical>()
    var spouseIncome = 0.0
    var spouseStartAge = -1
    for (e in events) {
        if (!e.enabled) continue // disabled events don't affect the projection
        when (e.kind) {
            "child" -> cashFlows += Presets.childFlows(e.startAge, e.years, e.annualCost, e.birthCost, e.collegeCost)
            "home" -> properties += Presets.homeProperty(e.buyAge, e.price, e.downPct, e.mortgageRate, e.termYears, e.appreciation, e.ongoingPct, e.sellPct, if (e.sellAge < 0) null else e.sellAge)
            "oneTime" -> cashFlows += Presets.oneTimeFlow(e.age, e.amount, e.income)
            "marriage" -> {
                // One-time flows at a PAST marriage age (already married) simply don't fire, so a
                // retroactive marriage drops the ceremony + the net-worth lump on its own — exactly right
                // (that net worth is already inside your current investments). Spouse income/spending then
                // run from today via the streams below.
                if (e.ceremonyCost > 0.0) cashFlows += Presets.oneTimeFlow(e.age, e.ceremonyCost, income = false)
                if (e.spouseNetWorth != 0.0) cashFlows += Presets.oneTimeFlow(e.age, e.spouseNetWorth, income = true)
                if (e.spouseSpending != 0.0) cashFlows += Presets.customFlow(e.age, 200, e.spouseSpending, income = false, inflates = true) // extra household spend, into retirement
                if (e.spouseIncome > 0.0) {
                    spouseIncome += e.spouseIncome
                    spouseStartAge = if (spouseStartAge < 0) e.age else minOf(spouseStartAge, e.age)
                }
            }
            "sabbatical" -> sabbaticals += Sabbatical(e.spouse, e.startAge, e.years, e.reduction)
            else -> cashFlows += Presets.customFlow(e.startAge, e.endAge, e.amount, e.income, e.inflates)
        }
    }
    return Compiled(cashFlows, properties, spouseIncome, spouseStartAge, sabbaticals)
}

// --- The single input object every entry point takes -----------------------------------------------

/**
 * Every input the engine needs, in one object. A frontend builds this once and passes it to any of the
 * projection functions. [retirementSpending] <= 0 means "track current spending" (the engine resolves
 * the fallback). [events] carries an `enabled` flag per event; disabled ones are excluded internally.
 */
@JsExport
class FireInputsJs(
    val currentAge: Int,
    val initialInvestments: Double,
    val income: Double,
    val spending: Double,        // TOTAL annual spending = housing + discretionary + fixed (the UI splits it)
    val housing: Double,         // the rent slice a held home replaces (suppressed from spending while owned)
    val incomeGrowth: Double,
    val lifestyleCreep: Double,
    val inflation: Double,
    val stockPct: Double,
    val bondPct: Double,
    val cashPct: Double,
    val stockReturn: Double,
    val bondReturn: Double,
    val cashReturn: Double,
    val retirementSpending: Double, // <= 0 => fall back to `spending`
    val withdrawalRate: Double,
    val taxRate: Double,
    val correctTax: Boolean,
    val cashBucket: Double,
    val cashReturnReal: Double,
    val events: Array<LifeEventInput>,
    val maxYears: Int,
    val lifeExpectancy: Int,
    val socialSecurity: Double,
    val socialSecurityAge: Int,
    val leanFactor: Double,
    val fatFactor: Double,
    val withdrawalStrategy: String, // "fixed" | "vpw" | "guardrails" (how the retirement drawdown spends)
    val retireAge: Int, // age earned income stops & drawdown begins; ≤0 ⇒ fall back to the SS claim age
)

private fun parseStrategy(s: String): WithdrawalStrategy = when (s.lowercase()) {
    "vpw" -> WithdrawalStrategy.VPW
    "guardrails" -> WithdrawalStrategy.GUARDRAILS
    else -> WithdrawalStrategy.FIXED
}

/** Resolve the JS input object into the engine's internal [FixedInputs] — the one place the fallback +
 *  event compilation happen. [eventsOverride] lets [eventImpactsJs] reuse this with a subset of events. */
private fun FireInputsJs.toFixed(eventsOverride: Array<LifeEventInput> = events): FixedInputs {
    val c = compileEvents(eventsOverride)
    return FixedInputs(
        currentAge = currentAge,
        initialInvestments = initialInvestments,
        income = income,
        spending = spending,
        housing = housing,
        incomeGrowth = incomeGrowth,
        lifestyleCreep = lifestyleCreep,
        inflation = inflation,
        stockPct = stockPct,
        bondPct = bondPct,
        cashPct = cashPct,
        // The inputs are NOMINAL (headline) returns; the engine runs in real terms, so convert here.
        stockReturn = Finance.realReturn(stockReturn, inflation),
        bondReturn = Finance.realReturn(bondReturn, inflation),
        cashReturn = Finance.realReturn(cashReturn, inflation),
        retirementSpending = retirementSpending, // raw override; ≤0 ⇒ engine tracks total spending (housing-aware)
        withdrawalRate = withdrawalRate,
        taxRate = taxRate,
        correctTax = correctTax,
        leanFactor = leanFactor,
        fatFactor = fatFactor,
        cashFlows = c.cashFlows,
        spouseIncome = c.spouseIncome,
        spouseIncomeStartAge = c.spouseIncomeStartAge,
        sabbaticals = c.sabbaticals,
        cashBucket = cashBucket,
        cashReturnReal = cashReturnReal,
        properties = c.properties,
        maxYears = maxYears,
        lifeExpectancy = lifeExpectancy,
        socialSecurity = socialSecurity,
        socialSecurityAge = socialSecurityAge,
        retireAge = retireAge,
        withdrawalStrategy = parseStrategy(withdrawalStrategy),
    )
}

// --- Fixed (deterministic) projection --------------------------------------------------------------

@JsExport
class ProjectionResult(
    val fireTarget: Double,
    val retirementEventCost: Double,
    val retireSpend: Double, // resolved retirement spending (override, or total minus the rent a held home replaces)
    val fiProgress: Double, // today's liquid ÷ the FIRE target, clamped to [0,1]
    val savingsRate: Double,
    val yearsToFire: Double,
    val ageAtFire: Int,
    val leanTarget: Double,
    val leanYears: Double,
    val leanAge: Int,
    val fatTarget: Double,
    val fatYears: Double,
    val fatAge: Int,
    val netWorthAtFire: Double,
    val retireAge: Int,
    val claimAge: Int,
    val depletionAge: Int,
    val lifeLiquid: DoubleArray,
    val lifeNetWorth: DoubleArray,
    val ages: IntArray,
    val liquid: DoubleArray,
    val saved: DoubleArray,
    val returns: DoubleArray,
    val netWorth: DoubleArray,
    val cash: DoubleArray,
    val homeValue: DoubleArray,
    val mortgageBalance: DoubleArray,
)

private fun ProjectionResult(p: Projection) = ProjectionResult(
    p.fireTarget, p.retirementEventCost, p.retireSpend, p.fiProgress, p.savingsRate, p.yearsToFire, p.ageAtFire,
    p.leanTarget, p.leanYears, p.leanAge, p.fatTarget, p.fatYears, p.fatAge, p.netWorthAtFire,
    p.retireAge, p.claimAge, p.depletionAge, p.lifeLiquid, p.lifeNetWorth,
    p.ages, p.liquid, p.saved, p.returns, p.netWorth, p.cash, p.homeValue, p.mortgageBalance,
)

@JsExport
fun projectFixedJs(inputs: FireInputsJs): ProjectionResult = ProjectionResult(projectFixed(inputs.toFixed()))

/** Per-event and joint effect of the life events on the FIRE date (years). */
@JsExport
class EventImpactsJs(
    val impacts: DoubleArray, // per-event MARGINAL impact, aligned to [FireInputsJs.events]
    val netYears: Double,     // joint impact of ALL enabled events vs. an event-free plan (NaN if none enabled)
)

/**
 * Effect of the life events on the FIRE date, in years:
 *  - [EventImpactsJs.impacts]: each event's MARGINAL impact — years-to-FIRE with the whole plan minus
 *    years-to-FIRE with everything EXCEPT that event. Each badge answers "what does removing this cost
 *    me?" in the context of the rest, so it moves as other events come and go. Disabled → NaN.
 *  - [EventImpactsJs.netYears]: the joint impact of ALL enabled events vs. an event-free plan — the
 *    holistic "together they shift FI by…" readout.
 * Sentinels for both: +∞ = pushes FIRE past the horizon, −∞ = is what makes FIRE reachable, NaN =
 * neither side reaches / not applicable.
 */
@JsExport
fun eventImpactsJs(inputs: FireInputsJs): EventImpactsJs {
    val all = inputs.events
    fun fireYears(evs: Array<LifeEventInput>): Double = projectFixed(inputs.toFixed(evs)).yearsToFire
    fun delta(with: Double, without: Double): Double = when {
        !with.isNaN() && !without.isNaN() -> with - without // + delays FIRE, − speeds FIRE
        !without.isNaN() && with.isNaN() -> Double.POSITIVE_INFINITY
        !with.isNaN() && without.isNaN() -> Double.NEGATIVE_INFINITY
        else -> Double.NaN
    }
    val full = fireYears(all) // the WHOLE plan (compilation drops disabled events) — the shared reference
    val out = DoubleArray(all.size) { Double.NaN }
    for (k in all.indices) {
        if (!all[k].enabled) continue // a disabled event isn't in the plan → no marginal effect
        out[k] = delta(full, fireYears(Array(all.size - 1) { i -> if (i < k) all[i] else all[i + 1] }))
    }
    val net = if (all.none { it.enabled }) Double.NaN else delta(full, fireYears(emptyArray()))
    return EventImpactsJs(out, net)
}

// --- Monte Carlo -----------------------------------------------------------------------------------

@JsExport
class MonteCarloResultJs(
    val ages: IntArray,
    val p10: DoubleArray,
    val p25: DoubleArray,
    val p50: DoubleArray,
    val p75: DoubleArray,
    val p90: DoubleArray,
    val medianYears: Double,
    val p10Years: Double,
    val p90Years: Double,
    val successRate: Double,
    val lifeSuccessRate: Double,
    val fireTarget: Double,
    val savingsRate: Double,
    val leanTarget: Double,
    val leanMedianYears: Double,
    val fatTarget: Double,
    val fatMedianYears: Double,
    val medianNetWorthAtFire: Double,
)

@JsExport
fun monteCarloJs(inputs: FireInputsJs): MonteCarloResultJs {
    val inp = inputs.toFixed()
    // The MC return model (σ, correlation, ν, runs, seed) is the engine's; means are the user's real returns.
    val r = monteCarlo(inp, inp.stockReturn, inp.bondReturn, MonteCarloModel.STOCK_SD, MonteCarloModel.BOND_SD, MonteCarloModel.CORRELATION, MonteCarloModel.NU, MonteCarloModel.RUNS, MonteCarloModel.SEED)
    return MonteCarloResultJs(
        r.ages, r.p10, r.p25, r.p50, r.p75, r.p90, r.medianYears, r.p10Years, r.p90Years, r.successRate,
        r.lifeSuccessRate, r.fireTarget, Finance.savingsRate(inp.income, inp.spending), // fireTarget comes from the simulate MC already ran
        r.leanTarget, r.leanMedianYears, r.fatTarget, r.fatMedianYears, r.medianNetWorthAtFire,
    )
}

// --- Analysis (recommended age + survival + affordability + insights, one call) ---------------------

/** Affordability headroom at the analyzed retire age: the most of each single lever the plan sustains at
 *  the same ~80% Monte Carlo survival bar as the recommendation — home & child terms come from
 *  [EventDefaults] so an "affordable home" matches a hand-added one. `*AtCap` ⇒ the search maxed out
 *  (report the value as ≥). */
@JsExport
class AffordabilityJs(
    val survives: Boolean,
    val extraSpend: Double,
    val extraSpendAtCap: Boolean,
    val homePrice: Double, // < 0 ⇒ not applicable (the plan already models a home)
    val homePriceAtCap: Boolean,
    val kids: Int,
    val kidsAtCap: Boolean,
)

/** "Biggest levers" — marginal effect of small concrete actions, each on its honest metric: cashflow
 *  nudges as deterministic Δ years-to-FI (positive = sooner), risk-shaping nudges as Δ Monte Carlo
 *  survival at the same retire age (fraction; paired paths, so a delta is paths flipping, not noise).
 *  NaN = the lever doesn't apply. The `*Nudge` fields echo the tested nudge sizes so UI copy always
 *  matches the math (see [InsightNudges]); `alloc*Pct` is the full post-shift allocation to apply verbatim. */
@JsExport
class InsightsJs(
    val spendNudge: Double,          // $/yr the spend lever tested ("spend $100/mo less")
    val incomeNudge: Double,         // $/yr the income lever tested ("earn $5k/yr more")
    val allocShift: Double,          // fraction of the portfolio the allocation lever moves
    val spendLessFiYears: Double,
    val earnMoreFiYears: Double,
    val noCreepFiYears: Double,
    val retireLaterSurvival: Double,
    val delaySsSurvival: Double,
    val allocShiftSurvival: Double,
    val allocShiftToStocks: Boolean,
    val allocStockPct: Double,       // the allocation AFTER the suggested shift (apply as-is; NaN = n/a)
    val allocBondPct: Double,
    val allocCashPct: Double,
    val guardrailsSurvival: Double,
)

/** One object with everything the retirement-analysis story needs — computed together, at the same
 *  risk-adjusted footing, for the same resolved retire age, so the pieces can never disagree. */
@JsExport
class AnalysisJs(
    val recommendedRetireAge: Int, // earliest age clearing ~80% Monte Carlo survival
    val retireAge: Int,            // the age analyzed: the explicit input, else the recommendation
    val lifeSuccess: Double,       // survival to the death age retiring AT [retireAge]
    val coastAge: Int,             // earliest stop-saving age still clearing ~80% (Coast FI); -1 = no slack
    val p10DepletionAge: Int,      // the roughest-decile path's dry age; -1 = even it lasts to the death age
    val p10FinalBalance: Double,   // the roughest-decile balance at death (0 when ≥10% of paths deplete)
    val retireSpendBase: Double,   // the resolved 1× retirement spend the lifestyle tiers multiply
    val leanRetireAge: Int,        // earliest ~80% age funding leanFactor × retireSpendBase
    val fatRetireAge: Int,         // earliest ~80% age funding fatFactor × retireSpendBase
    val affordability: AffordabilityJs,
    val insights: InsightsJs,
)

/** The single analysis entry point. Note the retire-age sentinel here resolves to the RECOMMENDED age
 *  (auto-tracking), not the Social Security claim age like the projection entry points. */
@JsExport
fun analysisJs(inputs: FireInputsJs): AnalysisJs {
    val r = analysis(
        inputs.toFixed(),
        EventDefaults.homeDownPct, EventDefaults.homeMortgageRate, EventDefaults.homeTermYears,
        EventDefaults.homeAppreciation, EventDefaults.homeOngoingPct, EventDefaults.homeSellPct,
        EventDefaults.childYears, EventDefaults.childAnnualCost, EventDefaults.childBirthCost, EventDefaults.childCollegeCost,
    )
    val a = r.affordability
    val i = r.insights
    return AnalysisJs(
        r.recommendedRetireAge, r.retireAge, r.lifeSuccess, r.coastAge, r.p10DepletionAge, r.p10FinalBalance,
        r.retireSpendBase, r.leanRetireAge, r.fatRetireAge,
        AffordabilityJs(a.survives, a.extraSpend, a.extraSpendAtCap, a.homePrice, a.homePriceAtCap, a.kids, a.kidsAtCap),
        InsightsJs(
            InsightNudges.SPEND_PER_YEAR, InsightNudges.INCOME_PER_YEAR, InsightNudges.ALLOC_SHIFT,
            i.spendLessFiYears, i.earnMoreFiYears, i.noCreepFiYears,
            i.retireLaterSurvival, i.delaySsSurvival, i.allocShiftSurvival, i.allocShiftToStocks,
            i.allocStockPct, i.allocBondPct, i.allocCashPct, i.guardrailsSurvival,
        ),
    )
}

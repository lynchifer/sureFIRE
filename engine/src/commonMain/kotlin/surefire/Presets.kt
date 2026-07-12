package surefire

/**
 * Monarch-style life-event presets compiled to the engine's [CashFlow] / [Property] primitives.
 * This is the single source of truth for what a child / home / windfall *means* financially — all
 * such modeling lives in the engine and is JVM-tested, never in the UI. The UI only picks a preset
 * and its parameters; the JS facade ([compileEvents]) dispatches to the functions below.
 */
/**
 * Default modeling values for a freshly-added life event — engine-owned so every frontend (a UI seeds
 * its forms from these) AND the engine's own probes (an affordability "home", an insights "child")
 * model an added event identically. Exported to JS as-is; inert on the JVM.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@kotlin.js.JsExport
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

object Presets {
    /** The age a child starts college — the lump-sum [childFlows] collegeCost lands at birth + this. */
    const val COLLEGE_AGE: Int = 18

    /** A dependent: an inflating annual expense over [years], an optional one-time birth cost, and an
     *  optional lump-sum college cost (today's dollars) landing when the child turns [COLLEGE_AGE]. */
    fun childFlows(startAge: Int, years: Int, annualCost: Double, birthCost: Double, collegeCost: Double = 0.0): List<CashFlow> {
        val out = ArrayList<CashFlow>(3)
        out.add(CashFlow(-annualCost, startAge, startAge + years - 1, inflates = true))
        if (birthCost > 0.0) out.add(CashFlow(-birthCost, startAge, startAge, inflates = true))
        if (collegeCost > 0.0) out.add(CashFlow(-collegeCost, startAge + COLLEGE_AGE, startAge + COLLEGE_AGE, inflates = true))
        return out
    }

    /** A primary home: appreciating asset + amortizing mortgage. Down payment = price × [downPct].
     *  Owning replaces your rent automatically — the `housing` slice of spending is suppressed while held. */
    fun homeProperty(
        buyAge: Int, price: Double, downPct: Double, mortgageRate: Double, termYears: Int,
        appreciation: Double, ongoingPct: Double, sellPct: Double, sellAge: Int?,
    ): Property = Property(
        price = price,
        downPayment = price * downPct,
        mortgageRate = mortgageRate,
        mortgageTermYears = termYears,
        appreciationReal = appreciation,
        ongoingCostRate = ongoingPct,
        buyAge = buyAge,
        sellingCostRate = sellPct,
        sellAge = sellAge,
    )

    /** A one-time windfall ([income] = true) or cost ([income] = false) at a single age. */
    fun oneTimeFlow(age: Int, amount: Double, income: Boolean): CashFlow =
        CashFlow(if (income) amount else -amount, age, age, inflates = true)

    /** A raw custom stream over [startAge, endAge]. */
    fun customFlow(startAge: Int, endAge: Int, amount: Double, income: Boolean, inflates: Boolean): CashFlow =
        CashFlow(if (income) amount else -amount, startAge, endAge, inflates)
}

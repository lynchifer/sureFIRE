package surefire

/**
 * Monarch-style life-event presets compiled to the engine's [CashFlow] / [Property] primitives.
 * This is the single source of truth for what a child / home / windfall *means* financially — all
 * such modeling lives in the engine and is JVM-tested, never in the UI. The UI only picks a preset
 * and its parameters; the JS facade ([compileEvents]) dispatches to the functions below.
 */
object Presets {
    /** A dependent: an inflating annual expense over [years], plus an optional one-time birth cost. */
    fun childFlows(startAge: Int, years: Int, annualCost: Double, birthCost: Double): List<CashFlow> {
        val out = ArrayList<CashFlow>(2)
        out.add(CashFlow(-annualCost, startAge, startAge + years - 1, inflates = true))
        if (birthCost > 0.0) out.add(CashFlow(-birthCost, startAge, startAge, inflates = true))
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

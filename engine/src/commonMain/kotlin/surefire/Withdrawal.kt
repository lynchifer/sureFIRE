package surefire

import kotlin.math.pow

/**
 * How much to pull from the portfolio each year in retirement. Only the DRAWDOWN is affected — the
 * accumulation, FIRE target, and tiers are independent of this choice.
 *
 *  - [FIXED]      constant real spend (the 4%-rule / Trinity line): draw = grossed-up spend − Social
 *                 Security, held flat in real terms.
 *  - [VPW]        Variable Percentage Withdrawal (Bogleheads): each year withdraw the annuity payment
 *                 PMT(assumedReturn, yearsLeft, balance) — a rising % of the CURRENT balance. Spends
 *                 the surplus when markets are good and self-corrects down when they're not; by design
 *                 it draws the portfolio toward ~0 at life expectancy and never "fails".
 *  - [GUARDRAILS] Guyton-Klinger: start by drawing the withdrawal-rate % of the portfolio AT retirement;
 *                 if the draw RATE then drifts more than 20% above that, cut spending 10% (capital-
 *                 preservation); more than 20% below, raise it 10% (prosperity). Spends a meaningful share
 *                 of real wealth (so an over-funded retiree isn't stuck at their tiny stated need).
 */
enum class WithdrawalStrategy { FIXED, VPW, GUARDRAILS }

/** Level annuity payment: the constant amount that draws [pv] to zero over [n] years at real rate [r]. */
internal fun pmt(r: Double, n: Int, pv: Double): Double {
    if (n <= 0) return pv
    return if (r == 0.0) pv / n else pv * r / (1.0 - (1.0 + r).pow(-n.toDouble()))
}

/**
 * A year's withdrawal split: [portfolio] is what's pulled from investments; [spend] is total real
 * spending (portfolio draw + any Social Security). FIXED's [portfolio] can be negative when Social
 * Security exceeds the spending need (a net inflow); VPW/GUARDRAILS floor it at 0 and cap at the balance.
 */
internal class DrawResult(val portfolio: Double, val spend: Double)

/**
 * Stateful per-path withdrawal calculator (guardrails carry state across years, so use one instance per
 * simulated life/path). Social Security ([ssNow]) and the grossed-up spending need ([grossSpendNow]) are
 * supplied PER YEAR: ssNow is 0 during an early-retirement bridge and the benefit once claiming begins;
 * grossSpendNow tracks housing transitions (rent resumes when a home is sold, stops when one is bought).
 */
internal class WithdrawalPlan(
    private val strategy: WithdrawalStrategy,
    private val initialBalance: Double,  // portfolio at retirement (drives the GK starting draw)
    private val assumedReturn: Double,   // VPW's PMT discount rate (the plan's expected real return)
    private val lifeExpectancy: Int,
    private val withdrawalRate: Double,  // GK manages a % OF THE PORTFOLIO around this rate
) {
    private val guardBand = 0.20 // ±20% rate drift trips a guardrail
    private val guardStep = 0.10 // each trip adjusts spending ±10%

    // GK starts at withdrawalRate × the ACTUAL retirement portfolio — so an over-funded retiree draws a
    // meaningful share of their wealth (e.g. 3.7% of $50M ≈ $1.85M), not their tiny stated spending need.
    private var gkDraw = withdrawalRate * initialBalance

    fun draw(age: Int, balance: Double, ssNow: Double, grossSpendNow: Double): DrawResult = when (strategy) {
        // FIXED: spend the (housing-aware) real amount; Social Security covers part, the portfolio the rest.
        WithdrawalStrategy.FIXED -> DrawResult(portfolio = grossSpendNow - ssNow, spend = grossSpendNow)
        WithdrawalStrategy.VPW -> {
            val yearsLeft = (lifeExpectancy - age).coerceAtLeast(1)
            val d = pmt(assumedReturn, yearsLeft, balance).coerceIn(0.0, balance.coerceAtLeast(0.0))
            DrawResult(portfolio = d, spend = d + ssNow) // Social Security is income on top of the VPW draw
        }
        WithdrawalStrategy.GUARDRAILS -> {
            if (balance > 0.0 && withdrawalRate > 0.0) {
                val rate = gkDraw / balance
                if (rate > withdrawalRate * (1.0 + guardBand)) gkDraw *= (1.0 - guardStep) // capital preservation
                else if (rate < withdrawalRate * (1.0 - guardBand)) gkDraw *= (1.0 + guardStep) // prosperity
            }
            val d = gkDraw.coerceIn(0.0, balance.coerceAtLeast(0.0))
            DrawResult(portfolio = d, spend = d + ssNow)
        }
    }
}

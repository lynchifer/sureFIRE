package surefire

import kotlin.math.floor
import kotlin.math.pow

/** Blended real return from the asset allocation. */
fun weightedGrowthRate(
    stockPct: Double,
    bondPct: Double,
    cashPct: Double,
    stockReturn: Double,
    bondReturn: Double,
    cashReturn: Double,
): Double = stockPct * stockReturn + bondPct * bondReturn + cashPct * cashReturn

/**
 * Total earned (salary) income at [age] in projection year [t]: your primary income (compounded by
 * income growth) plus the spouse's flat-real income once married, with either earner zeroed during a
 * matching sabbatical window. Earned income stops at the RE age — but that's the caller's drawdown
 * branch, not here. With no spouse and no sabbaticals this is exactly `income·(1+growth)^t` (parity).
 */
internal fun earnedIncome(inp: FixedInputs, age: Int, t: Int): Double {
    fun paused(spouse: Boolean) = inp.sabbaticals.any { it.spouse == spouse && age >= it.startAge && age < it.startAge + it.years }
    var earned = if (paused(false)) 0.0 else inp.income * (1.0 + inp.incomeGrowth).pow(t.toDouble())
    if (inp.spouseIncomeStartAge in 0..age && !paused(true)) earned += inp.spouseIncome
    return earned
}

/** Sum of all streams active at [age], converting fixed-nominal streams to real dollars. */
internal fun activeCashflow(flows: List<CashFlow>, age: Int, deflator: Double): Double {
    var sum = 0.0
    for (f in flows) {
        if (age in f.startAge..f.endAge) {
            sum += if (f.inflates) f.amount else f.amount / deflator
        }
    }
    return sum
}

/** Level (constant-nominal) annual mortgage payment for an annually-compounded loan. */
internal fun annualMortgagePayment(principal: Double, rate: Double, termYears: Int): Double {
    if (principal <= 0.0 || termYears <= 0) return 0.0
    return if (rate == 0.0) principal / termYears
    else principal * rate / (1.0 - (1.0 + rate).pow(-termYears.toDouble()))
}

/** Linear interpolation of a series at fractional index [x] (NaN → the final value). */
internal fun interpAt(series: DoubleArray, x: Double): Double {
    if (x.isNaN()) return series[series.size - 1]
    val i = floor(x).toInt()
    if (i < 0) return series[0]
    if (i + 1 >= series.size) return series[series.size - 1]
    val f = x - i
    return series[i] * (1 - f) + series[i + 1] * f
}

/** Real market value of a property at [age] — purchase price compounded by real appreciation. */
internal fun homeValueAt(p: Property, age: Int): Double = p.price * (1.0 + p.appreciationReal).pow((age - p.buyAge).toDouble())

/**
 * Whether any property is owned (bought, not yet sold) at [age] — matches the projection loop's `held`.
 * While a home is held its rent (the `housing` slice of `spending`) is replaced by the home's mortgage +
 * upkeep, so that slice is suppressed from spending; selling resumes it. Subsumes the already-owned case
 * (a home with buyAge < currentAge is held from year 0, so its rent never appears).
 */
internal fun homeHeldAt(properties: List<Property>, age: Int): Boolean =
    properties.any { age >= it.buyAge && (it.sellAge == null || age < it.sellAge) }

/** First age at which the [liquid] series reaches [target], interpolated (the years-to-FIRE convention). */
internal fun crossYears(liquid: DoubleArray, currentAge: Int, target: Double): Pair<Double, Int> {
    if (liquid[0] >= target) return 0.0 to currentAge
    for (k in 1 until liquid.size) {
        if (liquid[k] >= target) {
            val frac = (target - liquid[k - 1]) / (liquid[k] - liquid[k - 1])
            val years = (k - 1) + frac
            return years to floor(currentAge + years).toInt()
        }
    }
    return Double.NaN to -1
}

/**
 * Net annual cost of life events still active at the retirement age [retireAge] — the extra spending
 * the nest egg must also fund, folded into the FIRE target. One-time flows (down payments, sales,
 * lump sums) are excluded; only ongoing obligations count. Sign: positive = net cost, negative = net
 * income (e.g. a pension event lowers the target).
 */
internal fun retirementEventCost(inp: FixedInputs, retireAge: Int): Double {
    val defl = (1.0 + inp.inflation).pow((retireAge - inp.currentAge).toDouble())
    // Only ongoing obligations count: a one-time lump (startAge == endAge) landing on the retirement age
    // is a windfall/cost, not annual spending, so it must not be capitalized into the target (÷ wr).
    val recurring = inp.cashFlows.filter { it.startAge != it.endAge }
    var net = activeCashflow(recurring, retireAge, defl) // income +, expense −
    for (p in inp.properties) {
        val held = retireAge >= p.buyAge && (p.sellAge == null || retireAge < p.sellAge)
        if (!held) continue
        val mort0 = (p.price - p.downPayment).coerceAtLeast(0.0)
        val mortYear = retireAge - p.buyAge
        if (mort0 > 0.0 && mortYear in 0 until p.mortgageTermYears) {
            net -= annualMortgagePayment(mort0, p.mortgageRate, p.mortgageTermYears) / defl // real mortgage payment, still owing
        }
        net -= p.ongoingCostRate * homeValueAt(p, retireAge) // upkeep (% of current value)
    }
    return -net
}

/**
 * Core projection given a per-year real growth path [gByYear]. The deterministic fixed mode passes a
 * constant path; Monte Carlo / historical pass random or historical paths. This is the single source
 * of truth for the accumulation + net-worth math.
 *
 * CALIBRATED accumulation (reproduces the engaging-data reference): beginning-of-year contribution,
 * THEN growth: liquid' = (liquid + flows) * (1 + g[t]). FIRE crosses LIQUID investments.
 */
internal fun simulate(inp: FixedInputs, gByYear: DoubleArray): Projection {
    val effectiveRetireAge = Finance.effectiveRetireAge(inp.currentAge, inp.retireAge, inp.socialSecurityAge) // income stops, drawdown begins
    val ssClaimAge = Finance.claimAge(inp.socialSecurityAge) // Social Security begins here — may be AFTER retirement (a bridge)
    val ssBenefit = Finance.socialSecurityBenefit(inp.socialSecurity, inp.socialSecurityAge) // claim-age-adjusted
    // Retirement-spending anchor: an explicit override (retirementSpending > 0) is used verbatim; otherwise
    // track total spending, minus the housing slice if a home is held at retirement (you own it, not rent).
    val retSpendBase = if (inp.retirementSpending > 0.0) inp.retirementSpending
        else (inp.spending - if (homeHeldAt(inp.properties, effectiveRetireAge)) inp.housing else 0.0).coerceAtLeast(0.0)
    // The FIRE target folds in life-event costs still active at retirement; the drawdown handles the actual
    // (declining) event flows via `cf`, so `grossSpend` stays on BASE spending to avoid double-counting.
    val grossSpend = Finance.fireTarget(retSpendBase, inp.withdrawalRate, inp.taxRate, inp.correctTax) * inp.withdrawalRate
    val eventCost = retirementEventCost(inp, effectiveRetireAge)
    val target = Finance.fireTarget((retSpendBase + eventCost).coerceAtLeast(0.0), inp.withdrawalRate, inp.taxRate, inp.correctTax)
    val assumedReturn = weightedGrowthRate(inp.stockPct, inp.bondPct, inp.cashPct, inp.stockReturn, inp.bondReturn, inp.cashReturn) // VPW's expected real return
    var plan: WithdrawalPlan? = null // built lazily at retirement so it sees the actual starting balance
    var depletionAge = -1 // first age the retired balance hits 0 (-1 = never)
    val sr = Finance.savingsRate(inp.income, inp.spending)
    val n = inp.maxYears
    val inf = inp.inflation
    // In retirement the portfolio funds recurring obligations out of taxable withdrawals, so they're tax-
    // grossed exactly like base spending and the FIRE target ([retirementEventCost]). One-time flows (down
    // payments, sales, lump sums) are capital moves, not annual spending — never grossed (matching the
    // target, which also excludes them). Split the streams once so the loop can treat each correctly.
    val retireGross = Finance.taxGrossUp(inp.taxRate, inp.correctTax)
    val recurringFlows = inp.cashFlows.filter { it.startAge != it.endAge }
    val oneTimeFlows = inp.cashFlows.filter { it.startAge == it.endAge }

    val ages = IntArray(n + 1) { inp.currentAge + it }
    val liquid = DoubleArray(n + 1)
    val saved = DoubleArray(n + 1)
    val returns = DoubleArray(n + 1)
    val cash = DoubleArray(n + 1)
    val homeValue = DoubleArray(n + 1)
    val mortgageBalance = DoubleArray(n + 1)
    val netWorth = DoubleArray(n + 1)
    val lifeLiquid = DoubleArray(n + 1)   // retire-at-FIRE drawdown path
    val lifeNetWorth = DoubleArray(n + 1)
    val lifeSpending = DoubleArray(n + 1) // total real spending each year in retirement (portfolio draw + SS)
    val annualIncome = DoubleArray(n)
    val annualSpending = DoubleArray(n)
    val annualSavings = DoubleArray(n)

    val props = inp.properties
    val payment = DoubleArray(props.size) {
        annualMortgagePayment((props[it].price - props[it].downPayment).coerceAtLeast(0.0), props[it].mortgageRate, props[it].mortgageTermYears)
    }
    // A home bought BEFORE today (buyAge < currentAge — "I already own one") starts with its mortgage
    // already partly paid: amortize the elapsed years forward so the projection opens on the remaining
    // balance, not the original loan. (homeValueAt already appreciates the value from the buy age.)
    val mortNominal = DoubleArray(props.size) { i ->
        val p = props[i]
        var m = (p.price - p.downPayment).coerceAtLeast(0.0)
        val elapsed = (inp.currentAge - p.buyAge).coerceAtLeast(0)
        repeat(minOf(elapsed, p.mortgageTermYears)) { m = (m * (1.0 + p.mortgageRate) - payment[i]).coerceAtLeast(0.0) }
        m
    }
    val sold = BooleanArray(props.size)

    fun heldForStock(p: Property, age: Int) = p.buyAge < age && (p.sellAge == null || age <= p.sellAge)

    liquid[0] = inp.initialInvestments
    saved[0] = inp.initialInvestments
    cash[0] = inp.cashBucket
    for (i in props.indices) {
        val p = props[i]
        if (heldForStock(p, inp.currentAge)) {
            homeValue[0] += homeValueAt(p, inp.currentAge)
            mortgageBalance[0] += mortNominal[i]
        }
    }
    netWorth[0] = liquid[0] + cash[0] + homeValue[0] - mortgageBalance[0]
    lifeLiquid[0] = liquid[0]
    lifeNetWorth[0] = netWorth[0]

    for (t in 0 until n) {
        val ageStart = inp.currentAge + t
        val deflT = (1.0 + inf).pow(t.toDouble())
        val deflT1 = deflT * (1.0 + inf)
        val incomeT = earnedIncome(inp, ageStart, t) // primary + spouse, minus any sabbatical pause
        val housingNow = if (homeHeldAt(props, ageStart)) inp.housing else 0.0 // a held home replaces the rent slice
        val spendT = (inp.spending - housingNow).coerceAtLeast(0.0) * (1.0 + inp.lifestyleCreep).pow(t.toDouble())
        val cfRecurring = activeCashflow(recurringFlows, ageStart, deflT) // ongoing obligations (child, spouse spend, pension)
        val cfOneTime = activeCashflow(oneTimeFlows, ageStart, deflT)     // windfalls / one-off costs
        val cf = cfRecurring + cfOneTime
        val contribution = incomeT - spendT + cf

        var propOneTime = 0.0   // down payment / sale proceeds — capital moves, never tax-grossed
        var propRecurring = 0.0 // mortgage + upkeep − rent freed — the ongoing cost of owning
        for (i in props.indices) {
            val p = props[i]
            if (sold[i]) continue
            if (ageStart == p.buyAge) propOneTime -= p.downPayment
            if (p.sellAge != null && ageStart == p.sellAge) {
                propOneTime += homeValueAt(p, p.sellAge) * (1.0 - p.sellingCostRate) - mortNominal[i] / deflT
                mortNominal[i] = 0.0
                sold[i] = true
                continue
            }
            val held = ageStart >= p.buyAge && (p.sellAge == null || ageStart < p.sellAge)
            if (held) {
                val mortYear = ageStart - p.buyAge
                if (mortNominal[i] > 0.0 && mortYear < p.mortgageTermYears) {
                    propRecurring -= payment[i] / deflT
                    mortNominal[i] = (mortNominal[i] * (1.0 + p.mortgageRate) - payment[i]).coerceAtLeast(0.0)
                }
                propRecurring -= p.ongoingCostRate * homeValueAt(p, ageStart)
            }
        }
        val propFlow = propOneTime + propRecurring

        val g = gByYear[t]
        val after = (liquid[t] + contribution + propFlow) * (1.0 + g)
        liquid[t + 1] = after
        saved[t + 1] = saved[t] + contribution + propFlow
        returns[t + 1] = after - saved[t + 1]
        cash[t + 1] = cash[t] * (1.0 + inp.cashReturnReal)

        val ageEnd = ageStart + 1
        var hv = 0.0
        var mb = 0.0
        for (i in props.indices) {
            val p = props[i]
            if (!sold[i] && heldForStock(p, ageEnd)) {
                hv += homeValueAt(p, ageEnd)
                mb += mortNominal[i] / deflT1
            }
        }
        homeValue[t + 1] = hv
        mortgageBalance[t + 1] = mb
        netWorth[t + 1] = after + cash[t + 1] + hv - mb

        // Life plan: work/contribute until the RE age; after it, stop earning and live on portfolio
        // withdrawals plus Social Security ONCE it begins at the claim age (during an early-retirement
        // bridge, ssNow = 0 and the portfolio carries the full spend). Life-event flows (mortgage,
        // ongoing costs, a late windfall) continue throughout. Once retired, the investable balance is
        // floored at 0: a depleted account can't keep "withdrawing" into a compounding negative — you've
        // simply run out (net worth may still be negative if a mortgage is underwater, which is real).
        val retiredNow = ageStart >= effectiveRetireAge
        val lifeContribution: Double
        val lifePropFlow: Double
        if (retiredNow) {
            val p = plan ?: WithdrawalPlan(inp.withdrawalStrategy, grossSpend, lifeLiquid[t], assumedReturn, inp.lifeExpectancy, inp.withdrawalRate).also { plan = it }
            val ssNow = if (ageStart >= ssClaimAge) ssBenefit else 0.0 // 0 during the bridge, then the benefit
            val d = p.draw(ageStart, lifeLiquid[t], ssNow) // portfolio draw + total spend per the chosen strategy
            // Recurring obligations are funded by taxable withdrawals ⇒ tax-grossed (like base spend + the
            // FIRE target); one-time capital flows pass through raw. Keeps the path consistent with the target.
            lifeContribution = cfOneTime + cfRecurring * retireGross - d.portfolio
            lifePropFlow = propOneTime + propRecurring * retireGross
            lifeSpending[t] = d.spend
        } else {
            lifeContribution = contribution
            lifePropFlow = propFlow
        }
        val lifeRaw = (lifeLiquid[t] + lifeContribution + lifePropFlow) * (1.0 + g)
        if (retiredNow && lifeRaw < 0.0 && depletionAge < 0) depletionAge = ageStart // first year the portfolio runs dry
        val lifeAfter = if (retiredNow) lifeRaw.coerceAtLeast(0.0) else lifeRaw
        lifeLiquid[t + 1] = lifeAfter
        lifeNetWorth[t + 1] = lifeAfter + cash[t + 1] + hv - mb

        annualIncome[t] = incomeT
        annualSpending[t] = spendT
        annualSavings[t] = contribution + propFlow
    }

    // All three FIRE tiers cross the same LIQUID series via one interpolation helper.
    val (yearsToFire, ageAtFire) = crossYears(liquid, inp.currentAge, target)
    val leanTarget = target * inp.leanFactor
    val fatTarget = target * inp.fatFactor
    val (leanYears, leanAge) = crossYears(liquid, inp.currentAge, leanTarget)
    val (fatYears, fatAge) = crossYears(liquid, inp.currentAge, fatTarget)
    val netWorthAtFire = interpAt(netWorth, yearsToFire)
    return Projection(
        fireTarget = target,
        retirementEventCost = eventCost,
        savingsRate = sr,
        growthRate = if (gByYear.isEmpty()) 0.0 else gByYear.average(),
        yearsToFire = yearsToFire,
        ageAtFire = ageAtFire,
        leanTarget = leanTarget,
        leanYears = leanYears,
        leanAge = leanAge,
        fatTarget = fatTarget,
        fatYears = fatYears,
        fatAge = fatAge,
        netWorthAtFire = netWorthAtFire,
        retireAge = effectiveRetireAge,
        claimAge = ssClaimAge,
        depletionAge = depletionAge,
        lifeLiquid = lifeLiquid,
        lifeNetWorth = lifeNetWorth,
        lifeSpending = lifeSpending,
        ages = ages,
        liquid = liquid,
        saved = saved,
        returns = returns,
        netWorth = netWorth,
        cash = cash,
        homeValue = homeValue,
        mortgageBalance = mortgageBalance,
        annualIncome = annualIncome,
        annualSpending = annualSpending,
        annualSavings = annualSavings,
    )
}

/** Deterministic fixed-return projection: a constant real growth path. */
fun projectFixed(inp: FixedInputs): Projection {
    val g = weightedGrowthRate(inp.stockPct, inp.bondPct, inp.cashPct, inp.stockReturn, inp.bondReturn, inp.cashReturn)
    return simulate(inp, DoubleArray(inp.maxYears) { g })
}

package surefire

/**
 * Pure financial math for sureFIRE. No JS/platform annotations here so the whole
 * thing is testable on the JVM (./gradlew jvmTest); the @JsExport facade lives in jsMain.
 *
 * Everything is in REAL (today's) dollars. Returns and income growth are real.
 */
object Finance {

    /**
     * FIRE Target = the liquid portfolio needed to fund retirement.
     *
     * correctTax = true  -> base / (1 - taxRate)   (mathematically correct gross-up; default)
     * correctTax = false -> base * (1 + taxRate)   (the original engaging-data formula; parity)
     *
     * The reference scenario (retSpend 40000, wr 0.04, tax 0.07, original mode) => 1,070,000.
     */
    fun fireTarget(
        retirementSpending: Double,
        withdrawalRate: Double,
        taxRate: Double,
        correctTax: Boolean,
    ): Double {
        // Guard a 0/negative withdrawal rate from dividing the target to Infinity/negative.
        val base = retirementSpending / withdrawalRate.coerceAtLeast(1e-6)
        return base * taxGrossUp(taxRate, correctTax)
    }

    /**
     * The factor by which real spending funded by taxable withdrawals is grossed up — you must withdraw
     * (and be taxed on) more than you net. correctTax = true ⇒ 1/(1−tax) (mathematically correct);
     * false ⇒ (1+tax) (the original engaging-data parity formula). A degenerate ≥100% tax is guarded.
     * Shared by [fireTarget] and the retirement drawdown so the FIRE target and the path agree on tax.
     */
    fun taxGrossUp(taxRate: Double, correctTax: Boolean): Double =
        if (correctTax) 1.0 / (1.0 - taxRate).coerceAtLeast(0.01) else 1.0 + taxRate

    /** Savings rate = (income - spending) / income. */
    fun savingsRate(income: Double, spending: Double): Double =
        if (income == 0.0) 0.0 else (income - spending) / income

    /**
     * Annual Social Security benefit given the benefit at full retirement age ([pia]) and the age you
     * claim. Per SSA rules (FRA 67): claiming early reduces the benefit 5/9% per month for the first
     * 36 months and 5/12% per month beyond that (62 ⇒ ~70%); delaying past FRA adds 8%/yr of delayed
     * credits up to age 70 (70 ⇒ 124%). You can't claim before 62 or earn credits past 70.
     */
    fun socialSecurityBenefit(pia: Double, claimAge: Int, fra: Int = 67): Double {
        val age = claimAge.coerceIn(62, 70)
        return if (age >= fra) {
            val monthsLate = (age - fra) * 12
            pia * (1.0 + monthsLate * (2.0 / 3.0) / 100.0) // +8%/yr (2/3% per month) delayed credits
        } else {
            val monthsEarly = (fra - age) * 12
            val reduction = minOf(monthsEarly, 36) * (5.0 / 9.0) / 100.0 +
                maxOf(0, monthsEarly - 36) * (5.0 / 12.0) / 100.0
            pia * (1.0 - reduction)
        }
    }

    /** Age you actually claim Social Security: floored at 62 (earliest) and capped at 70 (max credits). */
    fun claimAge(socialSecurityAge: Int): Int = socialSecurityAge.coerceIn(62, 70)

    /**
     * Effective retirement age: when earned income stops and the drawdown begins. This is the user's
     * chosen [retireAge] (which may be earlier than the Social Security claim age — an early-retirement
     * bridge — or later), never before today. A sentinel [retireAge] ≤ 0 falls back to the claim age,
     * which reproduces the original "retire when you claim Social Security" behavior.
     */
    fun effectiveRetireAge(currentAge: Int, retireAge: Int, socialSecurityAge: Int): Int =
        maxOf(currentAge, if (retireAge > 0) retireAge else claimAge(socialSecurityAge))

    /**
     * Convert a NOMINAL (headline) annual return to a REAL one for the given [inflation], via the exact
     * Fisher relation real = (1+nominal)/(1+inflation) − 1. The engine runs entirely in real (today's-
     * dollar) terms, so the JS facade converts the user's nominal return inputs through here.
     */
    fun realReturn(nominal: Double, inflation: Double): Double = (1.0 + nominal) / (1.0 + inflation) - 1.0
}

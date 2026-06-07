package surefire

/**
 * A cashflow stream applied during the projection. Sign convention:
 *   amount > 0  => income / inflow
 *   amount < 0  => expense / outflow
 *
 * A one-time event (inheritance, wedding, down payment) is a stream with startAge == endAge.
 * [inflates] = true means the amount is real (constant in today's dollars); false means it is a
 * fixed-nominal amount (e.g. a mortgage payment) whose real value is eroded by [FixedInputs.inflation].
 */
data class CashFlow(
    val amount: Double,
    val startAge: Int,
    val endAge: Int,
    val inflates: Boolean = true,
)

/**
 * A career break: one earner's income is paused over [startAge, startAge + years).
 * [spouse] = false pauses YOUR (primary) income, true pauses the spouse's.
 */
data class Sabbatical(
    val spouse: Boolean,
    val startAge: Int,
    val years: Int,
)

/**
 * A property (primary home). Drives net worth: an appreciating asset plus a fixed-nominal
 * amortizing mortgage. The engine derives all of its cash flows (down payment, mortgage payment,
 * ongoing costs, sale proceeds) so liquid investments and net worth stay consistent.
 *
 * [buyAge] may be in the past (an already-owned home): the engine appreciates [price] (the original
 * purchase price) forward to today and pre-amortizes the mortgage over the elapsed years, so the
 * projection opens on the home's current value and remaining balance. All amounts are today's dollars;
 * the mortgage payment is fixed-nominal and deflated by [FixedInputs.inflation] (inflation erosion).
 */
data class Property(
    val price: Double,
    val downPayment: Double,
    val mortgageRate: Double,        // nominal annual
    val mortgageTermYears: Int,
    val appreciationReal: Double,    // real appreciation rate (default ~0.7%)
    val ongoingCostRate: Double,     // annual % of current value (tax/insurance/maintenance)
    val buyAge: Int,
    val sellingCostRate: Double = 0.0, // transaction costs (agent + closing) deducted on sale
    val sellAge: Int? = null,        // optional downsize/sell: realizes equity into liquid
)

/**
 * Inputs for the deterministic (fixed real return) projection. All rates are real (inflation
 * already removed) except where a fixed-nominal item is deflated by [inflation].
 */
data class FixedInputs(
    val currentAge: Int,
    val initialInvestments: Double,
    val income: Double,
    val spending: Double,        // TOTAL annual spending = housing + discretionary + fixed (the UI splits it)
    val housing: Double = 0.0,   // the portion of [spending] a held home replaces (rent → mortgage+upkeep); 0 = none
    val incomeGrowth: Double,
    val lifestyleCreep: Double,
    val inflation: Double,
    val stockPct: Double,
    val bondPct: Double,
    val cashPct: Double,
    val stockReturn: Double,
    val bondReturn: Double,
    val cashReturn: Double,
    val retirementSpending: Double,
    val withdrawalRate: Double,
    val taxRate: Double,
    val correctTax: Boolean,
    val leanFactor: Double = 1.0, // leanFIRE spend = leanFactor × retirementSpending
    val fatFactor: Double = 1.0, //  fatFIRE spend = fatFactor × retirementSpending
    val cashFlows: List<CashFlow> = emptyList(),
    // Two-earner income (a Marriage event sets the spouse fields; Sabbaticals pause an earner):
    val spouseIncome: Double = 0.0,      // annual real spouse income (flat in today's dollars); 0 = single
    val spouseIncomeStartAge: Int = -1,  // marriage age the spouse income begins; -1 = no spouse
    val sabbaticals: List<Sabbatical> = emptyList(),
    // Net-worth extras (default to "off" so an empty config reduces to the original model):
    val cashBucket: Double = 0.0,        // separate emergency fund; in net worth, NOT the FIRE target
    val cashReturnReal: Double = 0.0,    // real return on the cash bucket
    val properties: List<Property> = emptyList(),
    val maxYears: Int = 80,
    val lifeExpectancy: Int = 95,        // death age; the retirement drawdown path runs to here
    val socialSecurity: Double = 0.0,    // annual real Social Security benefit at full retirement age (PIA)
    val socialSecurityAge: Int = 67,     // age you claim Social Security (benefit begins); 62–70
    val retireAge: Int = -1,             // age earned income stops & drawdown begins; ≤0 ⇒ fall back to the claim age
    val withdrawalStrategy: WithdrawalStrategy = WithdrawalStrategy.FIXED, // how the retirement drawdown spends
)

/**
 * Result of a deterministic projection. Series are aligned to [ages] (length n+1, a stock value at
 * each age); per-year flow series have length n.
 *
 * The FIRE crossing ([yearsToFire]) is tested against [liquid] investments only (you can't spend
 * your house); [netWorth] = liquid + cash + homeValue − mortgageBalance is shown alongside.
 * With no properties and a zero cash bucket, netWorth == liquid and the model is identical to the
 * original engaging-data calculator. [yearsToFire] is NaN if the target isn't reached within maxYears.
 */
class Projection(
    val fireTarget: Double,
    val retirementEventCost: Double, // annual life-event cost active at retirement, folded into fireTarget
    val savingsRate: Double,
    val growthRate: Double,
    val yearsToFire: Double,
    val ageAtFire: Int,
    // Three FIRE tiers (relative to retirement spending). The FIRE tier is fireTarget/yearsToFire/ageAtFire above.
    val leanTarget: Double,
    val leanYears: Double,
    val leanAge: Int,
    val fatTarget: Double,
    val fatYears: Double,
    val fatAge: Int,
    val netWorthAtFire: Double, // net worth at the (interpolated) FIRE crossing
    // Life plan: work/accumulate until [retireAge] (the chosen retirement age), then stop earning and
    // live on Social Security + withdrawals (grossed-up retirement spending), drawing down to
    // lifeExpectancy. Answers "once I retire, does my money last?" [retireAge] = effective retirement age.
    val retireAge: Int,
    val claimAge: Int,             // age Social Security begins (clamped 62–70); may be after retireAge (a bridge)
    val depletionAge: Int,         // age the retired balance first hits 0 (-1 = never); the RE plan "fails" if < lifeExpectancy
    val lifeLiquid: DoubleArray,   // investable balance over the full life (accumulate → draw down)
    val lifeNetWorth: DoubleArray, // net worth over the full life (drawdown path)
    val lifeSpending: DoubleArray, // total real spending each year in retirement (draw + SS); 0 while working
    val ages: IntArray,
    val liquid: DoubleArray,
    val saved: DoubleArray,
    val returns: DoubleArray,
    val netWorth: DoubleArray,
    val cash: DoubleArray,
    val homeValue: DoubleArray,
    val mortgageBalance: DoubleArray,
    val annualIncome: DoubleArray,
    val annualSpending: DoubleArray,
    val annualSavings: DoubleArray,
)

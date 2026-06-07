package surefire

/**
 * Shared reference scenario for tests: the engaging-data screenshot inputs in original
 * (×(1+tax)) tax-parity mode. `.copy(...)` it to vary a single dimension.
 */
internal fun reference() = FixedInputs(
    currentAge = 32,
    initialInvestments = 25_000.0,
    income = 60_000.0,
    spending = 45_000.0,
    incomeGrowth = 0.01,
    lifestyleCreep = 0.0,
    inflation = 0.025,
    stockPct = 0.80, bondPct = 0.18, cashPct = 0.02,
    stockReturn = 0.081, bondReturn = 0.024, cashReturn = 0.0,
    retirementSpending = 40_000.0,
    withdrawalRate = 0.04,
    taxRate = 0.07,
    correctTax = false,
)

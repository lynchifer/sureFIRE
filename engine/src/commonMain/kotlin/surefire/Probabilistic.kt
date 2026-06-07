package surefire

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Locked Monte Carlo model parameters (the app's calibrated choices, owned by the engine): marginal
 * σ from Shiller history (~18% stock / ~8% bond), −0.2 stock-bond correlation, Student-t ν=5 (fat
 * tails), 1000 runs, and a fixed seed for reproducibility.
 */
object MonteCarloModel {
    const val STOCK_SD = 0.18
    const val BOND_SD = 0.08
    const val CORRELATION = -0.2
    const val NU = 5
    const val RUNS = 1000
    const val SEED = 12345
    const val RECOMMEND_SURVIVAL = 0.80 // recommended RE age = earliest age that clears this MC survival rate
    const val RECOMMEND_RUNS = 256 // fewer paths for the age search — it's a guideline, and it runs ~log2(years) times
}

/** Small deterministic PRNG (splitmix32) — reproducible across JVM and JS. */
class Rng(seed: Int) {
    private var s: Int = seed
    private fun nextBits(): Int {
        s += 0x9E3779B9.toInt()
        var z = s
        z = (z xor (z ushr 16)) * 0x85EBCA6B.toInt()
        z = (z xor (z ushr 13)) * 0xC2B2AE35.toInt()
        return z xor (z ushr 16)
    }

    /** Uniform in (0,1). */
    fun nextUnit(): Double = ((nextBits().toLong() and 0xFFFFFFFFL) + 0.5) / 4294967296.0

    /** Standard normal via Box–Muller. */
    fun nextNormal(): Double {
        val u1 = nextUnit()
        val u2 = nextUnit()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /** Chi-square with [df] degrees of freedom. */
    fun nextChiSquare(df: Int): Double {
        var sum = 0.0
        repeat(df) {
            val z = nextNormal()
            sum += z * z
        }
        return sum
    }
}

/**
 * Multivariate Student-t (ν) return model: stock & bond are drawn with the given means / σ and
 * correlation, scaled by an inverse-χ mixture for fat tails, then blended by the allocation into one
 * real annual portfolio return, floored at −70% (a severe-but-possible single-year loss). Shared by
 * the accumulation Monte Carlo and the post-retirement drawdown so they sample identically.
 */
internal class ReturnSampler(
    private val stockMean: Double,
    private val bondMean: Double,
    private val stockSd: Double,
    private val bondSd: Double,
    private val correlation: Double,
    private val nu: Int,
    private val stockPct: Double,
    private val bondPct: Double,
    private val cashPct: Double,
    private val cashReturn: Double,
) {
    private val cholB = sqrt((1.0 - correlation * correlation).coerceAtLeast(0.0))
    private val tScale = if (nu > 2) sqrt((nu - 2.0) / nu) else 1.0 // standardize Student-t to unit variance

    /** One year's blended real portfolio return. */
    fun next(rng: Rng): Double {
        val z1 = rng.nextNormal()
        val z2 = rng.nextNormal()
        val f = tScale / sqrt(rng.nextChiSquare(nu) / nu) // normal / inverse-chi mixture
        val rStock = stockMean + stockSd * z1 * f
        val rBond = bondMean + bondSd * (correlation * z1 + cholB * z2) * f
        return (stockPct * rStock + bondPct * rBond + cashPct * cashReturn).coerceAtLeast(-0.7)
    }
}

/** Percentile (linear interpolation) of an already-sorted array. p in 0..1. */
internal fun percentileSorted(sorted: DoubleArray, p: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    if (sorted.size == 1) return sorted[0]
    val idx = p * (sorted.size - 1)
    val lo = floor(idx).toInt()
    val hi = lo + 1
    if (hi >= sorted.size) return sorted[sorted.size - 1]
    val f = idx - lo
    return sorted[lo] * (1 - f) + sorted[hi] * f
}

class MonteCarloResult(
    val ages: IntArray,
    val p10: DoubleArray,
    val p25: DoubleArray,
    val p50: DoubleArray,
    val p75: DoubleArray,
    val p90: DoubleArray,
    val yearsToFire: DoubleArray, // per run; NaN if not reached within the horizon
    val medianYears: Double,
    val p10Years: Double,
    val p90Years: Double,
    val successRate: Double, // fraction of runs reaching FIRE within maxYears
    val lifeSuccessRate: Double, // fraction of runs whose retire-at-RE-age drawdown lasts to the death age
    val fireTarget: Double, // event-cost-adjusted FIRE target (constant across runs — returns don't move it)
    val leanTarget: Double,
    val leanMedianYears: Double,
    val fatTarget: Double,
    val fatMedianYears: Double,
    val medianNetWorthAtFire: Double, // p50 net worth at the median FIRE crossing
)

/**
 * Monte Carlo accumulation using a **multivariate Student-t** return model (fat tails) with the
 * given stock/bond means, std-devs, correlation, and degrees of freedom [nu]. Net-worth percentile
 * bands per age + the distribution of years-to-FIRE.
 */
fun monteCarlo(
    inp: FixedInputs,
    mcStockReturn: Double,
    mcBondReturn: Double,
    stockSd: Double,
    bondSd: Double,
    correlation: Double,
    nu: Int,
    runs: Int,
    seed: Int,
): MonteCarloResult {
    val n = inp.maxYears
    val rng = Rng(seed)
    val sampler = ReturnSampler(mcStockReturn, mcBondReturn, stockSd, bondSd, correlation, nu, inp.stockPct, inp.bondPct, inp.cashPct, inp.cashReturn)

    val byAge = Array(n + 1) { DoubleArray(runs) }
    val years = DoubleArray(runs)
    val leanYrs = DoubleArray(runs)
    val fatYrs = DoubleArray(runs)
    var reached = 0
    var lifeReached = 0
    var fireTarget = 0.0
    var leanTarget = 0.0
    var fatTarget = 0.0
    val gByYear = DoubleArray(n)

    for (run in 0 until runs) {
        for (t in 0 until n) gByYear[t] = sampler.next(rng)
        val p = simulate(inp, gByYear)
        for (k in 0..n) byAge[k][run] = p.netWorth[k]
        years[run] = p.yearsToFire
        leanYrs[run] = p.leanYears
        fatYrs[run] = p.fatYears
        fireTarget = p.fireTarget // constant across runs; the target doesn't depend on the return path
        leanTarget = p.leanTarget
        fatTarget = p.fatTarget
        if (!p.yearsToFire.isNaN()) reached++
        if (p.depletionAge < 0 || p.depletionAge >= inp.lifeExpectancy) lifeReached++ // RE-age drawdown survived
    }

    val p10 = DoubleArray(n + 1)
    val p25 = DoubleArray(n + 1)
    val p50 = DoubleArray(n + 1)
    val p75 = DoubleArray(n + 1)
    val p90 = DoubleArray(n + 1)
    for (k in 0..n) {
        val col = byAge[k]
        col.sort()
        p10[k] = percentileSorted(col, 0.10)
        p25[k] = percentileSorted(col, 0.25)
        p50[k] = percentileSorted(col, 0.50)
        p75[k] = percentileSorted(col, 0.75)
        p90[k] = percentileSorted(col, 0.90)
    }
    val reachedYears = years.filter { !it.isNaN() }.sorted().toDoubleArray()
    val leanReached = leanYrs.filter { !it.isNaN() }.sorted().toDoubleArray()
    val fatReached = fatYrs.filter { !it.isNaN() }.sorted().toDoubleArray()
    val ages = IntArray(n + 1) { inp.currentAge + it }
    val medYears = if (reachedYears.isEmpty()) Double.NaN else percentileSorted(reachedYears, 0.50)
    return MonteCarloResult(
        ages = ages,
        p10 = p10, p25 = p25, p50 = p50, p75 = p75, p90 = p90,
        yearsToFire = years,
        medianYears = medYears,
        p10Years = if (reachedYears.isEmpty()) Double.NaN else percentileSorted(reachedYears, 0.10),
        p90Years = if (reachedYears.isEmpty()) Double.NaN else percentileSorted(reachedYears, 0.90),
        successRate = reached.toDouble() / runs,
        lifeSuccessRate = lifeReached.toDouble() / runs,
        fireTarget = fireTarget,
        leanTarget = leanTarget,
        leanMedianYears = if (leanReached.isEmpty()) Double.NaN else percentileSorted(leanReached, 0.50),
        fatTarget = fatTarget,
        fatMedianYears = if (fatReached.isEmpty()) Double.NaN else percentileSorted(fatReached, 0.50),
        medianNetWorthAtFire = interpAt(p50, medYears),
    )
}

/**
 * Lean Monte Carlo survival: the fraction of paths whose retire-at-RE-age drawdown lasts to the death
 * age. Same model as [monteCarlo] but skips all the percentile/band bookkeeping — used by the success
 * readout and the recommended-age search, both of which only need the one number.
 */
internal fun lifeSuccessRate(
    inp: FixedInputs, mcStockReturn: Double, mcBondReturn: Double, stockSd: Double, bondSd: Double,
    correlation: Double, nu: Int, runs: Int, seed: Int,
): Double {
    val n = inp.maxYears
    val rng = Rng(seed)
    val sampler = ReturnSampler(mcStockReturn, mcBondReturn, stockSd, bondSd, correlation, nu, inp.stockPct, inp.bondPct, inp.cashPct, inp.cashReturn)
    val g = DoubleArray(n)
    var ok = 0
    for (run in 0 until runs) {
        for (t in 0 until n) g[t] = sampler.next(rng)
        val d = simulate(inp, g).depletionAge
        if (d < 0 || d >= inp.lifeExpectancy) ok++
    }
    return ok.toDouble() / runs
}

/**
 * Recommended "don't go broke" RE age = the EARLIEST age whose Monte Carlo drawdown survives to the
 * death age at least [threshold] of the time (default 80%). Risk-adjusted, unlike a deterministic
 * average-case break-even. Survival is monotonic in the RE age (retiring later means more accumulation
 * and fewer drawdown years, so every path is at least as safe), and retiring at the death age trivially
 * survives — so a binary search for the leftmost passing age is exact and runs ~log2(years) simulations.
 */
fun recommendedRetireAge(inp: FixedInputs, threshold: Double = MonteCarloModel.RECOMMEND_SURVIVAL): Int {
    fun survives(age: Int): Boolean = lifeSuccessRate(
        inp.copy(retireAge = age), inp.stockReturn, inp.bondReturn, MonteCarloModel.STOCK_SD, MonteCarloModel.BOND_SD,
        MonteCarloModel.CORRELATION, MonteCarloModel.NU, MonteCarloModel.RECOMMEND_RUNS, MonteCarloModel.SEED,
    ) >= threshold
    var lo = inp.currentAge
    var hi = maxOf(inp.currentAge, inp.lifeExpectancy) // retiring at/after death has no drawdown ⇒ always survives
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (survives(mid)) hi = mid else lo = mid + 1
    }
    return lo
}

class SensitivityPoint(val delta: Double, val yearsToFire: Double)

/** Years-to-FIRE as current spending scales by (1+delta) for each delta. */
fun spendingSensitivity(inp: FixedInputs, deltas: DoubleArray): Array<SensitivityPoint> =
    Array(deltas.size) { i -> SensitivityPoint(deltas[i], projectFixed(inp.copy(spending = inp.spending * (1.0 + deltas[i]))).yearsToFire) }

/** Years-to-FIRE as the stock return scales by (1+delta) for each delta. */
fun stockReturnSensitivity(inp: FixedInputs, deltas: DoubleArray): Array<SensitivityPoint> =
    Array(deltas.size) { i -> SensitivityPoint(deltas[i], projectFixed(inp.copy(stockReturn = inp.stockReturn * (1.0 + deltas[i]))).yearsToFire) }

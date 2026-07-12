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
    val n = horizonYears(inp)
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
    val n = horizonYears(inp)
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

/** Monte Carlo survival at the engine's locked model parameters — the ONE way every search (recommended
 *  age, affordability, insights, analysis) measures survival, so they can never drift apart. */
internal fun mcSurvival(inp: FixedInputs, runs: Int): Double = lifeSuccessRate(
    inp, inp.stockReturn, inp.bondReturn, MonteCarloModel.STOCK_SD, MonteCarloModel.BOND_SD,
    MonteCarloModel.CORRELATION, MonteCarloModel.NU, runs, MonteCarloModel.SEED,
)

/** Survival plus the worst-decile outcome of the retire-at-RE-age drawdown. */
internal class LifeOutcomes(
    val survival: Double,
    val p10DepletionAge: Int,     // age the 10th-percentile (bad) path runs dry; -1 = even that path survives
    val p10FinalBalance: Double,  // 10th-percentile balance at the death age (0 when ≥10% of paths deplete)
)

/** One Monte Carlo pass collecting survival AND the roughest-decile outcome — same model, seed, and
 *  per-run sampling as [mcSurvival]/[monteCarlo], so the headline survival can never disagree with the
 *  worst-case readout shown beside it. */
internal fun lifeOutcomes(inp: FixedInputs, runs: Int): LifeOutcomes {
    val n = horizonYears(inp)
    val rng = Rng(MonteCarloModel.SEED)
    val sampler = ReturnSampler(
        inp.stockReturn, inp.bondReturn, MonteCarloModel.STOCK_SD, MonteCarloModel.BOND_SD,
        MonteCarloModel.CORRELATION, MonteCarloModel.NU, inp.stockPct, inp.bondPct, inp.cashPct, inp.cashReturn,
    )
    val g = DoubleArray(n)
    val depl = IntArray(runs)
    val fin = DoubleArray(runs)
    var ok = 0
    for (run in 0 until runs) {
        for (t in 0 until n) g[t] = sampler.next(rng)
        val p = simulate(inp, g)
        val d = p.depletionAge
        val survived = d < 0 || d >= inp.lifeExpectancy
        if (survived) ok++
        depl[run] = if (survived) Int.MAX_VALUE else d // survivors sort last (a later dry-age is better)
        fin[run] = p.lifeLiquid[(inp.lifeExpectancy - inp.currentAge).coerceIn(0, p.lifeLiquid.size - 1)]
    }
    depl.sort()
    fin.sort()
    val d10 = depl[((runs - 1) * 0.10).toInt()]
    return LifeOutcomes(ok.toDouble() / runs, if (d10 == Int.MAX_VALUE) -1 else d10, percentileSorted(fin, 0.10))
}

/**
 * Recommended "don't go broke" RE age = the EARLIEST age whose Monte Carlo drawdown survives to the
 * death age at least [threshold] of the time (default 80%). Risk-adjusted, unlike a deterministic
 * average-case break-even. Survival is monotonic in the RE age (retiring later means more accumulation
 * and fewer drawdown years, so every path is at least as safe), and retiring at the death age trivially
 * survives — so a binary search for the leftmost passing age is exact and runs ~log2(years) simulations.
 */
fun recommendedRetireAge(inp: FixedInputs, threshold: Double = MonteCarloModel.RECOMMEND_SURVIVAL): Int {
    fun survives(age: Int): Boolean =
        mcSurvival(inp.copy(retireAge = age), MonteCarloModel.RECOMMEND_RUNS) >= threshold
    var lo = inp.currentAge
    var hi = maxOf(inp.currentAge, inp.lifeExpectancy) // retiring at/after death has no drawdown ⇒ always survives
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (survives(mid)) hi = mid else lo = mid + 1
    }
    return lo
}

// --- Affordability headroom ------------------------------------------------------------------------

/** The most of each single lever a plan can ADD and still clear [MonteCarloModel.RECOMMEND_SURVIVAL]
 *  Monte Carlo survival to the death age, holding the retire age and the rest of the plan fixed. Reported
 *  at the SAME risk-adjusted bar as the recommended retire age — so this headroom is consistent with the
 *  rest of the story, unlike a deterministic steady-return test (which, for an over-funded early
 *  retirement whose portfolio compounds faster than it draws, "affords" almost anything). */
class Affordability(
    val survives: Boolean,       // does the plan itself clear the bar? if not, every lever is 0 / n-a
    val extraSpend: Double,      // additional real $/yr of retirement spending it sustains
    val extraSpendAtCap: Boolean, // the search hit its ceiling ⇒ the true figure is ≥ this
    val homePrice: Double,       // priciest home bought AT retirement (search terms); <0 ⇒ already owns one (n-a)
    val homePriceAtCap: Boolean,
    val kids: Int,               // additional staggered children it could raise
    val kidsAtCap: Boolean,
)

private const val AFFORD_SPEND_CAP = 2_000_000.0 // most extra real $/yr of retirement spend the search probes
private const val AFFORD_HOME_CAP = 5_000_000.0  // priciest home the search probes
private const val AFFORD_KIDS_CAP = 8            // most additional kids the search probes
private const val AFFORD_BISECTIONS = 20         // ~cap/2^20 resolution — finer than the UI ever displays

/**
 * Binary-search the headroom on each lever at the risk-adjusted survival bar. Survival is monotonic in
 * every lever (more spending / a pricier home / another child is strictly costlier), so a bisection for the
 * largest still-surviving value is exact. The home & child terms are passed in (the JS layer owns the
 * defaults a UI seeds new events with) so an "affordable home" uses the exact economics a hand-added one would.
 */
fun affordability(
    inp: FixedInputs,
    homeDownPct: Double, homeMortgageRate: Double, homeTermYears: Int,
    homeAppreciation: Double, homeOngoingPct: Double, homeSellPct: Double,
    childYears: Int, childAnnualCost: Double, childBirthCost: Double, childCollegeCost: Double,
    threshold: Double = MonteCarloModel.RECOMMEND_SURVIVAL,
    runs: Int = MonteCarloModel.RECOMMEND_RUNS,
): Affordability {
    fun survives(i: FixedInputs): Boolean = mcSurvival(i, runs) >= threshold

    // Already stretched at this retire age ⇒ nothing to spare on any lever.
    if (!survives(inp)) return Affordability(false, 0.0, false, -1.0, false, 0, false)

    val effRetAge = Finance.effectiveRetireAge(inp.currentAge, inp.retireAge, inp.socialSecurityAge)
    val end = inp.lifeExpectancy

    // Largest lever value in [0, cap] that still survives (apply(0) is a no-op, which we know survives).
    fun maxScalar(cap: Double, apply: (Double) -> FixedInputs): Double {
        var lo = 0.0
        var hi = cap
        repeat(AFFORD_BISECTIONS) {
            val mid = (lo + hi) / 2
            if (survives(apply(mid))) lo = mid else hi = mid
        }
        return lo
    }

    // Extra retirement spending: an added recurring spending stream from retirement through the death age
    // (funded by the drawdown, tax-grossed like the base spend — so `v` reads as real after-tax lifestyle).
    val extraSpend = maxScalar(AFFORD_SPEND_CAP) { v ->
        inp.copy(cashFlows = inp.cashFlows + Presets.customFlow(effRetAge, end, v, income = false, inflates = true))
    }

    // Priciest primary home bought AT retirement — only if not already modeling one (a held home replaces
    // rent, so stacking a second is meaningless). A $0 home is a no-op that just frees rent ⇒ survives.
    val ownsHome = inp.properties.isNotEmpty()
    val homePrice = if (ownsHome) -1.0 else maxScalar(AFFORD_HOME_CAP) { v ->
        inp.copy(properties = inp.properties + Presets.homeProperty(
            effRetAge, v, homeDownPct, homeMortgageRate, homeTermYears, homeAppreciation, homeOngoingPct, homeSellPct, null,
        ))
    }

    // Additional children, each staggered two years apart starting next year — the largest count that survives.
    var kids = 0
    for (n in 1..AFFORD_KIDS_CAP) {
        val extra = (0 until n).flatMap { j ->
            Presets.childFlows(inp.currentAge + 1 + j * 2, childYears, childAnnualCost, childBirthCost, childCollegeCost)
        }
        if (survives(inp.copy(cashFlows = inp.cashFlows + extra))) kids = n else break
    }

    return Affordability(
        survives = true,
        extraSpend = extraSpend,
        extraSpendAtCap = extraSpend >= AFFORD_SPEND_CAP * 0.98,
        homePrice = homePrice,
        homePriceAtCap = homePrice >= AFFORD_HOME_CAP * 0.98,
        kids = kids,
        kidsAtCap = kids >= AFFORD_KIDS_CAP,
    )
}

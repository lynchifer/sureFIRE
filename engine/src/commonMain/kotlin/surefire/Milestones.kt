package surefire

/**
 * Wealth milestones on the way to the FIRE target — the "time to each $100k" story: because compounding
 * back-loads growth, the FIRST milestone takes the longest and each later one arrives faster, so the
 * crossings bunch up toward the FI date. All crossings are on the deterministic (steady-return) LIQUID
 * path, the same series the FI tiers cross.
 */
class WealthMilestones(
    val amounts: DoubleArray,   // ascending round levels strictly above today's balance, ending at the FIRE target when it's reached
    val years: DoubleArray,     // years until each level is crossed (aligned to [amounts]; strictly increasing)
    val halfwayYears: Double,   // years until HALF the FIRE target — lands well past half the total time (NaN if never)
)

// Round "feels like a milestone" levels; the ones between today's balance and the target are used.
private val MILESTONE_LEVELS = doubleArrayOf(
    25e3, 50e3, 100e3, 250e3, 500e3, 750e3, 1e6, 1.5e6, 2e6, 3e6, 4e6, 5e6, 7.5e6, 10e6, 15e6, 20e6,
)
private const val MILESTONE_MAX = 6 // most intermediate dots a strip can carry legibly

/** Milestone crossings for a projection: up to [MILESTONE_MAX] round levels (the largest ones, for a
 *  high balance the tiny early levels are already behind) plus the FIRE target itself, each with its
 *  crossing time; levels never reached within the horizon are dropped. */
fun wealthMilestones(p: Projection): WealthMilestones {
    val start = p.liquid[0]
    val currentAge = p.ages[0]
    val levels = MILESTONE_LEVELS.filter { it > start && it < p.fireTarget }.takeLast(MILESTONE_MAX).toMutableList()
    if (!p.yearsToFire.isNaN()) levels.add(p.fireTarget)
    val crossed = levels
        .map { it to crossYears(p.liquid, currentAge, it).first }
        .filter { !it.second.isNaN() }
    return WealthMilestones(
        amounts = DoubleArray(crossed.size) { crossed[it].first },
        years = DoubleArray(crossed.size) { crossed[it].second },
        halfwayYears = crossYears(p.liquid, currentAge, p.fireTarget / 2.0).first,
    )
}

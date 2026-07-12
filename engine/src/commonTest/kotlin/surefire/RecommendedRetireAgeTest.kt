package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [recommendedRetireAge] — the EARLIEST RE age whose Monte Carlo drawdown survives to the death age at
 * least [MonteCarloModel.RECOMMEND_SURVIVAL] (80%) of the time. Risk-adjusted, and monotonic in the RE
 * age (retiring later is never riskier), so it's the leftmost age clearing the bar.
 */
class RecommendedRetireAgeTest {
    private fun survivalAt(inp: FixedInputs, age: Int, runs: Int = MonteCarloModel.RECOMMEND_RUNS): Double =
        mcSurvival(inp.copy(retireAge = age), runs)

    @Test
    fun overfundedCanRetireNow() {
        // A huge portfolio relative to spending clears 80% even retiring today.
        val inp = reference().copy(initialInvestments = 10_000_000.0)
        assertEquals(inp.currentAge, recommendedRetireAge(inp))
    }

    @Test
    fun recommendedAgeClearsThresholdAndOneYearEarlierDoesNot() {
        val inp = reference()
        val rec = recommendedRetireAge(inp)
        assertTrue(rec in inp.currentAge..inp.lifeExpectancy, "expected a real RE age, got $rec")
        assertTrue(survivalAt(inp, rec) >= MonteCarloModel.RECOMMEND_SURVIVAL, "recommended age must clear 80% survival")
        if (rec > inp.currentAge)
            assertTrue(survivalAt(inp, rec - 1) < MonteCarloModel.RECOMMEND_SURVIVAL, "one year earlier must miss it (earliest passing age)")
    }

    @Test
    fun survivalRateIsMonotonicInRetireAge() {
        // Same seed ⇒ retiring later dominates path-by-path, so survival never falls as the RE age rises.
        val inp = reference()
        var prev = -1.0
        for (age in inp.currentAge..inp.lifeExpectancy) {
            val s = survivalAt(inp, age, runs = 200)
            assertTrue(s >= prev - 1e-12, "survival fell at age $age ($s < $prev) — non-monotonic")
            prev = s
        }
    }

    @Test
    fun socialSecurityLowersTheRecommendedAge() {
        // A Social Security floor lets you clear 80% earlier.
        assertTrue(recommendedRetireAge(reference().copy(socialSecurity = 30_000.0)) <= recommendedRetireAge(reference()))
    }
}

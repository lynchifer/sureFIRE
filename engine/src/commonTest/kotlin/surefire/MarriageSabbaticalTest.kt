package surefire

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two-earner income: a Marriage event adds a spouse's flat-real income (from the marriage age, stopping
 * at the RE age); a Sabbatical pauses one earner's income for a window. Plus the withdrawal-rate guard.
 */
class MarriageSabbaticalTest {
    private fun idx(inp: FixedInputs, age: Int) = age - inp.currentAge
    private fun primaryAt(age: Int) = reference().income * (1.0 + reference().incomeGrowth).pow((age - reference().currentAge).toDouble())

    @Test
    fun spouseIncomeAddsToEarnedIncomeFromTheMarriageAge() {
        val inp = reference().copy(spouseIncome = 40_000.0, spouseIncomeStartAge = 40)
        val p = projectFixed(inp)
        assertEquals(primaryAt(39), p.annualIncome[idx(inp, 39)], 1e-6) // before marriage: primary only
        assertEquals(primaryAt(40) + 40_000.0, p.annualIncome[idx(inp, 40)], 1e-6) // from marriage: + spouse
    }

    @Test
    fun spouseIncomeSpeedsFire() {
        val married = projectFixed(reference().copy(spouseIncome = 40_000.0, spouseIncomeStartAge = 35))
        assertTrue(married.yearsToFire < projectFixed(reference()).yearsToFire, "a second income should pull FIRE earlier")
    }

    @Test
    fun yourSabbaticalPausesYourIncomeAndDelaysFire() {
        val inp = reference().copy(sabbaticals = listOf(Sabbatical(spouse = false, startAge = 35, years = 3)))
        val p = projectFixed(inp)
        for (age in 35..37) assertEquals(0.0, p.annualIncome[idx(inp, age)], 1e-9) // paused across the window
        assertEquals(primaryAt(38), p.annualIncome[idx(inp, 38)], 1e-6) // resumes after
        assertTrue(p.yearsToFire > projectFixed(reference()).yearsToFire, "a career break should push FIRE later")
    }

    @Test
    fun partialSabbaticalScalesIncomeAndIsLighterThanAFullPause() {
        val partial = reference().copy(sabbaticals = listOf(Sabbatical(spouse = false, startAge = 35, years = 2, reduction = 0.4)))
        val p = projectFixed(partial)
        for (age in 35..36) assertEquals(primaryAt(age) * 0.6, p.annualIncome[idx(partial, age)], 1e-6) // 40% cut keeps 60%
        assertEquals(primaryAt(37), p.annualIncome[idx(partial, 37)], 1e-6) // full income resumes after the window
        val fullPause = reference().copy(sabbaticals = listOf(Sabbatical(spouse = false, startAge = 35, years = 2))) // default reduction = 1.0
        assertTrue(p.yearsToFire < projectFixed(fullPause).yearsToFire, "a partial break should delay FIRE less than a full pause")
    }

    @Test
    fun spouseSabbaticalPausesOnlyTheSpouseShare() {
        val married = reference().copy(spouseIncome = 40_000.0, spouseIncomeStartAge = 30)
        val withBreak = married.copy(sabbaticals = listOf(Sabbatical(spouse = true, startAge = 40, years = 2)))
        assertEquals(primaryAt(40), projectFixed(withBreak).annualIncome[idx(married, 40)], 1e-6) // your income unaffected
        assertEquals(primaryAt(40) + 40_000.0, projectFixed(married).annualIncome[idx(married, 40)], 1e-6) // both, without the break
    }

    @Test
    fun zeroWithdrawalRateIsGuardedNotInfinite() {
        val t = Finance.fireTarget(40_000.0, 0.0, 0.07, correctTax = false)
        assertTrue(t.isFinite() && t > 0.0, "a 0% withdrawal rate must not divide the target to Infinity")
    }

    @Test
    fun noSpouseNoSabbaticalIsParityUnchanged() {
        val p = projectFixed(reference())
        assertEquals(1_070_000.0, p.fireTarget, 1e-6)
        assertEquals(21.255604019681794, p.yearsToFire, 1e-9)
    }
}

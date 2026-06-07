package surefire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Claim-age adjustment of the Social Security benefit (SSA rules, FRA 67). */
class SocialSecurityTest {
    private val pia = 24_000.0 // benefit at full retirement age

    @Test
    fun fullRetirementAgeIsOneHundredPercent() {
        assertEquals(pia, Finance.socialSecurityBenefit(pia, 67), 1e-6)
    }

    @Test
    fun claimingAt62IsSeventyPercent() {
        assertEquals(pia * 0.70, Finance.socialSecurityBenefit(pia, 62), 1e-6)
    }

    @Test
    fun claimingAt70IsOneHundredTwentyFourPercent() {
        assertEquals(pia * 1.24, Finance.socialSecurityBenefit(pia, 70), 1e-6)
    }

    @Test
    fun claimingAt65IsAboutEightySevenPercent() {
        assertEquals(pia * (1.0 - 24 * (5.0 / 9.0) / 100.0), Finance.socialSecurityBenefit(pia, 65), 1e-6) // 24 mo early
    }

    @Test
    fun clampsBelow62AndAbove70() {
        assertEquals(Finance.socialSecurityBenefit(pia, 62), Finance.socialSecurityBenefit(pia, 55), 1e-6)
        assertEquals(Finance.socialSecurityBenefit(pia, 70), Finance.socialSecurityBenefit(pia, 75), 1e-6)
    }

    @Test
    fun benefitIncreasesWithClaimAge() {
        var prev = Finance.socialSecurityBenefit(pia, 62)
        for (age in 63..70) {
            val b = Finance.socialSecurityBenefit(pia, age)
            assertTrue(b > prev, "benefit should rise from $prev at age ${age - 1} to $b at $age")
            prev = b
        }
    }
}

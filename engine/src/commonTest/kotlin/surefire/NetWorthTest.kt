package surefire

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetWorthTest {

    private fun home(buyAge: Int, sellAge: Int? = null) = Property(
        price = 300_000.0,
        downPayment = 60_000.0,
        mortgageRate = 0.06,
        mortgageTermYears = 30,
        appreciationReal = 0.007,
        ongoingCostRate = 0.02,
        buyAge = buyAge,
        sellAge = sellAge,
    )

    // --- The invariant that protects the original math ---

    @Test
    fun noPropertiesIdenticalToOriginal() {
        val p = projectFixed(reference())
        assertEquals(21.255604019681794, p.yearsToFire, 1e-9) // unchanged from Phase 2
        for (k in p.ages.indices) assertEquals(p.liquid[k], p.netWorth[k], 1e-9) // NW == liquid
    }

    @Test
    fun sellBeforeBuyIsTreatedAsNeverSold() {
        // Nonsense input (sell at 38, buy at 45) must not pay phantom proceeds or eat the down payment:
        // the engine treats it exactly like the same home never sold.
        val nonsense = projectFixed(reference().copy(properties = listOf(home(buyAge = 45, sellAge = 38))))
        val neverSold = projectFixed(reference().copy(properties = listOf(home(buyAge = 45))))
        assertEquals(neverSold.yearsToFire, nonsense.yearsToFire, 1e-9)
        for (k in neverSold.ages.indices) {
            assertEquals(neverSold.netWorth[k], nonsense.netWorth[k], 1e-9)
            assertEquals(neverSold.liquid[k], nonsense.liquid[k], 1e-9)
        }
    }

    @Test
    fun alreadyOwnedHomeOpensOnTheRemainingMortgage() {
        // reference currentAge = 32; a home bought at 22 is 10 years into its 30-yr loan.
        val p = projectFixed(reference().copy(properties = listOf(home(buyAge = 22))))
        val originalLoan = 240_000.0 // 300k − 60k down
        assertTrue(p.mortgageBalance[0] > 1.0 && p.mortgageBalance[0] < originalLoan, "opens on the remaining balance, not the full loan")
        assertEquals(300_000.0 * 1.007.pow(10.0), p.homeValue[0], 1.0) // value appreciated 10 years
    }

    @Test
    fun homeReplacesHousingSpend() {
        // Total spending includes $24k rent (housing). Once a home is held, that rent is REPLACED by the
        // home's mortgage + upkeep, so the housing slice drops out of spending — no double-count, no add-back.
        val inp = reference().copy(housing = 24_000.0, properties = listOf(home(buyAge = 40)))
        val p = projectFixed(inp)
        assertEquals(45_000.0, p.annualSpending[39 - 32], 1e-6)            // renting: full spend
        assertEquals(45_000.0 - 24_000.0, p.annualSpending[41 - 32], 1e-6) // owning: rent slice suppressed
        // An already-owned home (buyAge < currentAge) suppresses the rent slice from year 0 — this subsumes
        // the old rentFreed guard for free.
        val owned = projectFixed(reference().copy(housing = 24_000.0, properties = listOf(home(buyAge = 22))))
        assertEquals(45_000.0 - 24_000.0, owned.annualSpending[0], 1e-6)
    }

    @Test
    fun retirementTargetExcludesHousingWhenAHomeIsHeldAtRetirement() {
        // With retirement spend tracking total (no override), owning a home at retirement drops the housing
        // (rent) slice from the FI target — you pay a mortgage/upkeep, not rent. Lower target than renting on.
        val base = reference().copy(retirementSpending = 0.0, housing = 24_000.0) // track total; $24k of it is rent
        val renting = projectFixed(base) // no home ⇒ rent persists into the target
        val cashHome = Property(price = 200_000.0, downPayment = 200_000.0, mortgageRate = 0.0, mortgageTermYears = 0,
            appreciationReal = 0.0, ongoingCostRate = 0.01, buyAge = 40, sellAge = null) // held through retirement (67)
        val owning = projectFixed(base.copy(properties = listOf(cashHome)))
        assertTrue(owning.fireTarget < renting.fireTarget, "owning a home at retirement removes rent from the target")
    }

    // --- Cash bucket: net worth only, never the FIRE target ---

    @Test
    fun cashBucketCountsForNetWorthNotFire() {
        val base = projectFixed(reference())
        val withCash = projectFixed(reference().copy(cashBucket = 50_000.0, cashReturnReal = 0.0))
        assertEquals(base.yearsToFire, withCash.yearsToFire, 1e-12) // FIRE date unaffected
        for (k in withCash.ages.indices) {
            assertEquals(withCash.liquid[k] + 50_000.0, withCash.netWorth[k], 1e-6) // cash adds to NW
        }
    }

    // --- Mortgage amortization ---

    @Test
    fun annualPaymentMatchesAmortizationFormula() {
        // 240k principal, 6% annual, 30 yr -> ~$17,436/yr
        assertEquals(17_435.97, annualMortgagePayment(240_000.0, 0.06, 30), 0.5)
    }

    @Test
    fun mortgageAmortizesToZeroByEndOfTerm() {
        val p = projectFixed(reference().copy(properties = listOf(home(buyAge = 35)), maxYears = 80))
        val payoffIdx = (35 + 30) - 32 // age 65
        assertTrue(p.mortgageBalance[10] > 0.0, "mortgage should be outstanding mid-term")
        assertTrue(p.mortgageBalance[payoffIdx] < 1.0, "mortgage should be paid off by end of term")
    }

    // --- Net worth identity & continuity at purchase ---

    @Test
    fun netWorthIdentityHolds() {
        val p = projectFixed(reference().copy(properties = listOf(home(buyAge = 35))))
        for (k in p.ages.indices) {
            assertEquals(p.liquid[k] + p.cash[k] + p.homeValue[k] - p.mortgageBalance[k], p.netWorth[k], 1e-6)
        }
    }

    @Test
    fun buyingHomeDoesNotDestroyNetWorth() {
        // Buying converts cash into equity; net worth must NOT drop by the down payment.
        val base = projectFixed(reference())
        val withHome = projectFixed(reference().copy(properties = listOf(home(buyAge = 35))))
        val idx = (35 - 32) + 1 // first snapshot that includes the home (age 36)
        assertTrue(
            withHome.netWorth[idx] > base.netWorth[idx] - 40_000.0,
            "net worth cratered at purchase (phantom down-payment loss)",
        )
    }

    // --- Sell / downsize releases equity into liquid ---

    @Test
    fun sellingReleasesEquityIntoLiquid() {
        val noSale = projectFixed(reference().copy(properties = listOf(home(buyAge = 35))))
        val withSale = projectFixed(reference().copy(properties = listOf(home(buyAge = 35, sellAge = 50))))
        val after = (50 - 32) + 2 // a couple years after the sale
        assertTrue(
            withSale.liquid[after] > noSale.liquid[after] + 100_000.0,
            "sale should inject substantial equity into liquid",
        )
        assertEquals(0.0, withSale.homeValue[after], 1e-9)      // home gone
        assertEquals(0.0, withSale.mortgageBalance[after], 1e-9)
    }

    @Test
    fun sellingCostsReduceProceeds() {
        val after = (50 - 32) + 2
        val noCost = projectFixed(reference().copy(properties = listOf(home(buyAge = 35, sellAge = 50)))) // 0% default
        val withCost = projectFixed(reference().copy(properties = listOf(home(buyAge = 35, sellAge = 50).copy(sellingCostRate = 0.07))))
        assertTrue(withCost.liquid[after] < noCost.liquid[after], "transaction costs should reduce realized sale proceeds")
    }
}

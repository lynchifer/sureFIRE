// BOUNDARY ONLY. All financial math lives in the Kotlin engine (engine/src). This file just marshals
// the UI's input shape into the engine's single `FireInputsJs` object and copies typed-array results
// into JS arrays — NO calculations (no gross-up, fallback, filtering, interpolation, tier or impact math).
import {
  projectFixedJs,
  monteCarloJs,
  analysisJs,
  eventImpactsJs,
  socialSecurityBenefitJs,
  childEvent,
  homeEvent,
  oneTimeEvent,
  customEvent,
  marriageEvent,
  sabbaticalEvent,
  LifeEventInput,
  FireInputsJs,
} from 'surefire-engine'
import { isEnabled } from './lifeEvents'
import type { LifeEvent } from './lifeEvents'

export interface FireInputs {
  currentAge: number
  initialInvestments: number
  income: number
  // Spending is split into three categories; their sum is the total (the anchor for the FI tiers).
  housing: number // rent / shelter — REPLACED by a home's mortgage + upkeep when you own one
  discretionary: number // flexible spending (travel, dining, fun)
  fixed: number // non-housing fixed costs (utilities, insurance, subscriptions)
  incomeGrowth: number
  lifestyleCreep: number
  inflation: number
  stockPct: number
  bondPct: number
  cashPct: number
  stockReturn: number
  bondReturn: number
  cashReturn: number
  retirementSpending?: number // optional; when unset the engine falls back to `spending`
  withdrawalRate: number
  taxRate: number
  cashBucket: number
  cashReturnReal: number
  leanFactor: number // leanFIRE spend = leanFactor × spending
  fatFactor: number // fatFIRE spend = fatFactor × spending
  lifeEvents: LifeEvent[]
  maxYears: number
  lifeExpectancy: number // death age; the retirement drawdown path runs to here
  socialSecurity: number // annual real Social Security benefit at full retirement age (PIA)
  socialSecurityAge: number // age you claim Social Security (benefit begins); 62–70
  retireAge?: number // age earned income stops & drawdown begins; undefined ⇒ fall back to the claim age
  withdrawalStrategy: WithdrawalStrategy // how the retirement drawdown spends
}

export type WithdrawalStrategy = 'fixed' | 'vpw' | 'guardrails'

export interface ProjView {
  fireTarget: number
  retirementEventCost: number
  retireSpend: number // resolved retirement spending (override, or total minus the rent a held home replaces)
  fiProgress: number // today's liquid ÷ the FIRE target, clamped to [0,1]
  savingsRate: number
  yearsToFire: number
  ageAtFire: number
  leanTarget: number
  leanYears: number
  leanAge: number
  fatTarget: number
  fatYears: number
  fatAge: number
  netWorthAtFire: number
  retireAge: number
  claimAge: number
  depletionAge: number
  lifeLiquid: number[]
  lifeNetWorth: number[]
  ages: number[]
  liquid: number[]
  saved: number[]
  returns: number[]
  netWorth: number[]
  cash: number[]
  homeValue: number[]
  mortgageBalance: number[]
}

export interface MCView {
  ages: number[]
  p10: number[]
  p25: number[]
  p50: number[]
  p75: number[]
  p90: number[]
  medianYears: number
  p10Years: number
  p90Years: number
  successRate: number
  lifeSuccessRate: number
  fireTarget: number
  savingsRate: number
  leanTarget: number
  leanMedianYears: number
  fatTarget: number
  fatMedianYears: number
  medianNetWorthAtFire: number
}

export const LIFE_EXPECTANCY = 95 // default death age (the engine owns the MC model params)

export const DEFAULTS: FireInputs = {
  currentAge: 32,
  initialInvestments: 25_000,
  income: 60_000,
  housing: 18_000, // rent — a home event replaces this
  discretionary: 15_000,
  fixed: 12_000,
  incomeGrowth: 0.01,
  lifestyleCreep: 0.0,
  inflation: 0.025,
  stockPct: 0.8,
  bondPct: 0.18,
  cashPct: 0.02,
  stockReturn: 0.1, // NOMINAL (headline) returns — the engine subtracts inflation to get the real return
  bondReturn: 0.04,
  cashReturn: 0.02,
  withdrawalRate: 0.037,
  taxRate: 0.07,
  cashBucket: 0,
  cashReturnReal: 0.0,
  leanFactor: 0.65,
  fatFactor: 2.0,
  lifeEvents: [],
  maxYears: 80,
  lifeExpectancy: LIFE_EXPECTANCY,
  socialSecurity: 24_000,
  socialSecurityAge: 67,
  withdrawalStrategy: 'fixed',
}

const arr = (x: ArrayLike<number>) => Array.from(x)

/** Total annual spending = the three categories summed (the engine's single `spending` knob). */
export const totalSpending = (i: Pick<FireInputs, 'housing' | 'discretionary' | 'fixed'>): number =>
  i.housing + i.discretionary + i.fixed

/** Marshal a UI life-event into the engine's typed input via its factory (carrying the `enabled` flag;
 *  the engine decides what each kind costs and whether a disabled event participates). */
function toEngineEvent(e: LifeEvent): LifeEventInput {
  const on = isEnabled(e)
  switch (e.kind) {
    case 'child':
      return childEvent(e.startAge, e.years, e.annualCost, e.birthCost, e.collegeCost ?? 0, on)
    case 'home':
      return homeEvent(e.buyAge, e.price, e.downPct, e.mortgageRate, e.termYears, e.appreciation, e.ongoingPct, e.sellPct ?? 0, e.sellAge ?? -1, on)
    case 'oneTime':
      return oneTimeEvent(e.age, e.amount, e.income, on)
    case 'custom':
      return customEvent(e.startAge, e.endAge, e.amount, e.income, e.inflates, on)
    case 'marriage':
      return marriageEvent(e.age, e.ceremonyCost, e.spouseIncome, e.spouseSpending, e.spouseNetWorth, on)
    case 'sabbatical':
      return sabbaticalEvent(e.spouse, e.startAge, e.years, e.reduction ?? 1, on)
  }
}

/** The single boundary object the whole engine API takes. `retirementSpending` of 0 tells the engine to
 *  fall back to `spending`; `correctTax` is always true in this app (the corrected /(1−tax) gross-up). */
function toInputs(i: FireInputs): FireInputsJs {
  return new FireInputsJs(
    i.currentAge,
    i.initialInvestments,
    i.income,
    totalSpending(i), // total = housing + discretionary + fixed
    i.housing, // the rent slice a held home replaces
    i.incomeGrowth,
    i.lifestyleCreep,
    i.inflation,
    i.stockPct,
    i.bondPct,
    i.cashPct,
    i.stockReturn,
    i.bondReturn,
    i.cashReturn,
    i.retirementSpending ?? 0,
    i.withdrawalRate,
    i.taxRate,
    true,
    i.cashBucket,
    i.cashReturnReal,
    i.lifeEvents.map(toEngineEvent),
    i.maxYears,
    i.lifeExpectancy,
    i.socialSecurity,
    i.socialSecurityAge,
    i.leanFactor,
    i.fatFactor,
    i.withdrawalStrategy,
    i.retireAge ?? -1, // undefined ⇒ sentinel ⇒ engine falls back to the SS claim age
  )
}

export function runFixed(i: FireInputs): ProjView {
  const r = projectFixedJs(toInputs(i))
  return {
    fireTarget: r.fireTarget, retirementEventCost: r.retirementEventCost, retireSpend: r.retireSpend, fiProgress: r.fiProgress, savingsRate: r.savingsRate, yearsToFire: r.yearsToFire, ageAtFire: r.ageAtFire,
    leanTarget: r.leanTarget, leanYears: r.leanYears, leanAge: r.leanAge, fatTarget: r.fatTarget, fatYears: r.fatYears, fatAge: r.fatAge,
    netWorthAtFire: r.netWorthAtFire, retireAge: r.retireAge, claimAge: r.claimAge, depletionAge: r.depletionAge, lifeLiquid: arr(r.lifeLiquid), lifeNetWorth: arr(r.lifeNetWorth),
    ages: arr(r.ages), liquid: arr(r.liquid), saved: arr(r.saved), returns: arr(r.returns), netWorth: arr(r.netWorth),
    cash: arr(r.cash), homeValue: arr(r.homeValue), mortgageBalance: arr(r.mortgageBalance),
  }
}

export function runMonteCarlo(i: FireInputs): MCView {
  const r = monteCarloJs(toInputs(i))
  return {
    ages: arr(r.ages), p10: arr(r.p10), p25: arr(r.p25), p50: arr(r.p50), p75: arr(r.p75), p90: arr(r.p90),
    medianYears: r.medianYears, p10Years: r.p10Years, p90Years: r.p90Years, successRate: r.successRate, lifeSuccessRate: r.lifeSuccessRate, fireTarget: r.fireTarget, savingsRate: r.savingsRate,
    leanTarget: r.leanTarget, leanMedianYears: r.leanMedianYears, fatTarget: r.fatTarget, fatMedianYears: r.fatMedianYears,
    medianNetWorthAtFire: r.medianNetWorthAtFire,
  }
}

export interface ImpactsView {
  impacts: number[] // per-event MARGINAL FI impact (years), aligned to `i.lifeEvents` (disabled → NaN)
  netYears: number // joint impact of ALL enabled events vs an event-free plan (NaN if none enabled)
}

/** Effect of the life events on the FIRE date — per-event marginals + the joint net, one engine call. */
export function runImpacts(i: FireInputs): ImpactsView {
  const r = eventImpactsJs(toInputs(i))
  return { impacts: arr(r.impacts), netYears: r.netYears }
}

export interface AffordabilityView {
  survives: boolean // does the plan clear the ~80% survival bar at its retire age? (else no headroom)
  extraSpend: number // additional real $/yr of retirement spending it sustains
  extraSpendAtCap: boolean // search hit its ceiling ⇒ the true figure is ≥ this
  homePrice: number // priciest home bought at retirement; < 0 ⇒ not applicable (already owns one)
  homePriceAtCap: boolean
  kids: number // additional staggered children it could raise
  kidsAtCap: boolean
}

export interface InsightsView {
  spendNudge: number // $/yr the spend lever tested ("spend $100/mo less")
  incomeNudge: number // $/yr the income lever tested
  allocShift: number // fraction of the portfolio the allocation lever moves
  spendLessFiYears: number // Δ years-to-FI, positive = sooner (NaN = doesn't apply)
  earnMoreFiYears: number
  noCreepFiYears: number
  retireLaterSurvival: number // Δ Monte Carlo survival (fraction) at the same retire age
  delaySsSurvival: number
  allocShiftSurvival: number
  allocShiftToStocks: boolean // the better direction: true = toward stocks, false = toward bonds
  allocStockPct: number // the allocation AFTER the suggested shift — apply verbatim (NaN = n/a)
  allocBondPct: number
  allocCashPct: number
  guardrailsSurvival: number
}

export interface AnalysisView {
  recommendedRetireAge: number // earliest age clearing ~80% Monte Carlo survival
  retireAge: number // the age analyzed: the explicit input, else the recommendation (auto-tracking)
  lifeSuccess: number // survival to the death age retiring AT `retireAge`
  coastAge: number // earliest stop-saving age still clearing ~80% (Coast FI); -1 = no slack
  p10DepletionAge: number // the roughest-decile path's dry age; -1 = even it lasts to the death age
  p10FinalBalance: number // the roughest-decile balance at death (0 when ≥10% of paths deplete)
  affordability: AffordabilityView
  insights: InsightsView
}

/** The whole retirement-analysis story in ONE engine call: recommended age, resolved retire age, survival
 *  odds, affordability headroom, and biggest-lever insights — computed together at the same risk-adjusted
 *  footing so the pieces can't disagree. The retire-age sentinel (unset) resolves to the RECOMMENDED age. */
export function runAnalysis(i: FireInputs): AnalysisView {
  const r = analysisJs(toInputs(i))
  const a = r.affordability
  const s = r.insights
  return {
    recommendedRetireAge: r.recommendedRetireAge, retireAge: r.retireAge, lifeSuccess: r.lifeSuccess,
    coastAge: r.coastAge, p10DepletionAge: r.p10DepletionAge, p10FinalBalance: r.p10FinalBalance,
    affordability: {
      survives: a.survives, extraSpend: a.extraSpend, extraSpendAtCap: a.extraSpendAtCap,
      homePrice: a.homePrice, homePriceAtCap: a.homePriceAtCap, kids: a.kids, kidsAtCap: a.kidsAtCap,
    },
    insights: {
      spendNudge: s.spendNudge, incomeNudge: s.incomeNudge, allocShift: s.allocShift,
      spendLessFiYears: s.spendLessFiYears, earnMoreFiYears: s.earnMoreFiYears, noCreepFiYears: s.noCreepFiYears,
      retireLaterSurvival: s.retireLaterSurvival, delaySsSurvival: s.delaySsSurvival,
      allocShiftSurvival: s.allocShiftSurvival, allocShiftToStocks: s.allocShiftToStocks,
      allocStockPct: s.allocStockPct, allocBondPct: s.allocBondPct, allocCashPct: s.allocCashPct,
      guardrailsSurvival: s.guardrailsSurvival,
    },
  }
}

/** Claim-age-adjusted annual Social Security benefit (engine math) for the given age-67 benefit. */
export const socialSecurityBenefit = (piaAtFra: number, claimAge: number): number => socialSecurityBenefitJs(piaAtFra, claimAge)

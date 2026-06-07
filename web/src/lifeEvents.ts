// Life-event presets (Monarch-style) for the UI. The engine compiles these into its cashflow +
// property primitives (see the engine's Presets / LifeEventInput) AND owns the default modeling values
// (EventDefaults); this module just defines the UI shape, seeds new events, and timeline helpers.
import { EventDefaults } from 'surefire-engine'

const D = EventDefaults.getInstance() // engine-owned default modeling values for a freshly-added event

// Every event carries `enabled`: a disabled event stays in the list (and on its profile) but is
// excluded from the projection, so you can A/B its impact without deleting it.
export type LifeEvent =
  | { id: string; enabled: boolean; name?: string; kind: 'child'; startAge: number; years: number; annualCost: number; birthCost: number }
  | {
      id: string
      enabled: boolean
      name?: string
      kind: 'home'
      buyAge: number
      price: number
      downPct: number
      mortgageRate: number
      termYears: number
      appreciation: number
      ongoingPct: number
      sellPct: number
      sellAge: number | null
    }
  | { id: string; enabled: boolean; name?: string; kind: 'oneTime'; age: number; amount: number; income: boolean; label: string }
  | { id: string; enabled: boolean; name?: string; kind: 'custom'; startAge: number; endAge: number; amount: number; income: boolean; inflates: boolean; label: string }
  | { id: string; enabled: boolean; name?: string; kind: 'marriage'; age: number; ceremonyCost: number; spouseIncome: number; spouseSpending: number; spouseNetWorth: number }
  | { id: string; enabled: boolean; name?: string; kind: 'sabbatical'; spouse: boolean; startAge: number; years: number }

/** A disabled event is excluded from the engine; treat a missing flag (older saved plans) as enabled. */
export const isEnabled = (e: LifeEvent): boolean => e.enabled !== false

export type EventKind = LifeEvent['kind']

const uid = () => Math.random().toString(36).slice(2, 9)

export function newEvent(kind: EventKind, currentAge: number): LifeEvent {
  const id = uid()
  switch (kind) {
    case 'child':
      return { id, enabled: true, kind, startAge: currentAge + 2, years: D.childYears, annualCost: D.childAnnualCost, birthCost: D.childBirthCost }
    case 'home':
      return { id, enabled: true, kind, buyAge: currentAge + 3, price: D.homePrice, downPct: D.homeDownPct, mortgageRate: D.homeMortgageRate, termYears: D.homeTermYears, appreciation: D.homeAppreciation, ongoingPct: D.homeOngoingPct, sellPct: D.homeSellPct, sellAge: null }
    case 'oneTime':
      return { id, enabled: true, kind, age: currentAge + 5, amount: D.oneTimeAmount, income: false, label: 'Wedding' }
    case 'custom':
      return { id, enabled: true, kind, startAge: currentAge, endAge: currentAge + 5, amount: D.customAmount, income: false, inflates: true, label: 'Custom' }
    case 'marriage':
      return { id, enabled: true, kind, age: currentAge + 1, ceremonyCost: D.marriageCeremonyCost, spouseIncome: D.marriageSpouseIncome, spouseSpending: D.marriageSpouseSpending, spouseNetWorth: D.marriageSpouseNetWorth }
    case 'sabbatical':
      return { id, enabled: true, kind, spouse: false, startAge: currentAge + 5, years: D.sabbaticalYears }
  }
}

/** The age a life-event sits at on the timeline. */
export const eventAge = (e: LifeEvent): number =>
  e.kind === 'home' ? e.buyAge : e.kind === 'oneTime' || e.kind === 'marriage' ? e.age : e.startAge

export const eventIcon = (e: LifeEvent): string =>
  e.kind === 'child' ? '👶' : e.kind === 'home' ? '🏠' : e.kind === 'marriage' ? '💍' : e.kind === 'sabbatical' ? '🌴' : e.kind === 'oneTime' ? (e.income ? '💰' : '💸') : '⭐'

const KIND_LABEL: Record<EventKind, string> = {
  child: 'Child', home: 'Home', marriage: 'Marriage', sabbatical: 'Sabbatical', oneTime: 'One-time', custom: 'Custom',
}

/** The generic kind title — the rename placeholder, ignoring any custom name/label. */
export const eventDefaultTitle = (e: LifeEvent): string => KIND_LABEL[e.kind]

/** Display title: the user's custom name — stored in `label` for one-time/custom events and `name` for
 *  the rest — falling back to the generic kind title. */
export const eventTitle = (e: LifeEvent): string => {
  const custom = e.kind === 'oneTime' || e.kind === 'custom' ? e.label : e.name
  return custom?.trim() || KIND_LABEL[e.kind]
}

/**
 * [startAge, endAge] the event spans on the timeline (equal for one-time events). A home you never
 * sell is owned indefinitely → end is +Infinity (callers clamp it to the chart's right edge).
 */
export const eventSpan = (e: LifeEvent): [number, number] =>
  e.kind === 'child'
    ? [e.startAge, e.startAge + e.years]
    : e.kind === 'home'
      ? [e.buyAge, e.sellAge ?? Number.POSITIVE_INFINITY]
      : e.kind === 'oneTime'
        ? [e.age, e.age]
        : e.kind === 'marriage'
          ? [e.age, e.age]
          : e.kind === 'sabbatical'
            ? [e.startAge, e.startAge + e.years]
            : [e.startAge, e.endAge]

/** Fill any fields a saved event is missing (added in later versions) from the kind's defaults. */
export function normalizeEvents(events: LifeEvent[], currentAge: number): LifeEvent[] {
  return events.map((e) => ({ ...newEvent(e.kind, currentAge), ...e }) as LifeEvent)
}

/** Distinct colors assigned to events by index (chart duration band + list dot). */
export const EVENT_COLORS = ['#34d399', '#60a5fa', '#fbbf24', '#f472b6', '#a78bfa', '#22d3ee', '#fb923c', '#4ade80']

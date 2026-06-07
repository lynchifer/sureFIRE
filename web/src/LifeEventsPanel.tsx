import { useState } from 'react'
import type { ChangeEvent } from 'react'
import { newEvent, eventIcon, eventTitle, eventDefaultTitle, eventAge, eventSpan, isEnabled } from './lifeEvents'
import type { LifeEvent, EventKind } from './lifeEvents'
import { useClearableNumber } from './useClearableNumber'

function Num({
  label,
  value,
  onChange,
  step = 1,
  prefix,
  suffix,
}: {
  label: string
  value: number
  onChange: (n: number) => void
  step?: number
  prefix?: string
  suffix?: string
}) {
  const money = prefix === '$'
  const { text, onText } = useClearableNumber(value, onChange, 0, money)
  const onInput = (e: ChangeEvent<HTMLInputElement>) => {
    if (!money) return onText(e.target.value)
    const el = e.currentTarget
    const caret = el.selectionStart ?? el.value.length
    const before = (el.value.slice(0, caret).match(/\d/g) || []).length
    onText(el.value)
    requestAnimationFrame(() => {
      const v = el.value
      let s = 0
      let i = 0
      while (i < v.length && s < before) {
        if (/\d/.test(v[i])) s++
        i++
      }
      try {
        el.setSelectionRange(i, i)
      } catch {
        /* not a text input */
      }
    })
  }
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[11px] uppercase tracking-wide text-neutral-400">{label}</span>
      <div className="flex items-center rounded-md border border-white/10 bg-white/[0.03] px-2 focus-within:border-emerald-500/60">
        {prefix && <span className="text-xs text-neutral-400">{prefix}</span>}
        <input
          type={money ? 'text' : 'number'}
          inputMode={money ? 'decimal' : undefined}
          step={money ? undefined : step}
          value={text}
          onChange={onInput}
          onWheel={(e) => e.currentTarget.blur()}
          className="w-full bg-transparent px-1 py-1.5 text-base tabular-nums text-neutral-100 outline-none sm:text-sm"
        />
        {suffix && <span className="text-xs text-neutral-400">{suffix}</span>}
      </div>
    </label>
  )
}

function ToggleIncome({ income, onChange }: { income: boolean; onChange: (b: boolean) => void }) {
  return (
    <div className="flex overflow-hidden rounded-md border border-white/10 text-[11px]">
      <button onClick={() => onChange(false)} className={`px-2 py-1 ${!income ? 'bg-rose-500/20 text-rose-300' : 'text-neutral-500'}`}>
        Expense
      </button>
      <button onClick={() => onChange(true)} className={`px-2 py-1 ${income ? 'bg-emerald-500/20 text-emerald-300' : 'text-neutral-500'}`}>
        Income
      </button>
    </div>
  )
}

/** Every enabled event shows its marginal effect on the FI date (vs. removing it). */
function ImpactBadge({ impact }: { impact: number }) {
  if (impact === Infinity)
    return <span title="This event alone pushes FI past the horizon" className="rounded bg-rose-500/15 px-1.5 py-0.5 text-[11px] font-medium text-rose-300">delays FI past horizon</span>
  if (impact === -Infinity)
    return <span title="Without this event you'd never reach FI" className="rounded bg-emerald-500/15 px-1.5 py-0.5 text-[11px] font-medium text-emerald-300">enables FI</span>
  if (Number.isNaN(impact))
    return <span title="No effect on the FI date" className="rounded bg-white/[0.05] px-1.5 py-0.5 text-[11px] font-medium text-neutral-500">no FI effect</span>
  if (Math.abs(impact) < 0.05)
    return <span title="Negligible effect on the FI date" className="rounded bg-white/[0.05] px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-neutral-400">≈0 yr to FI</span>
  const delays = impact > 0
  return (
    <span
      title="Effect on your FI date vs. removing this event"
      className={`rounded px-1.5 py-0.5 text-[11px] font-medium tabular-nums ${delays ? 'bg-rose-500/15 text-rose-300' : 'bg-emerald-500/15 text-emerald-300'}`}
    >
      {delays ? '+' : '−'}
      {Math.abs(impact).toFixed(1)} yr to FI
    </span>
  )
}

const ADD: { kind: EventKind; label: string }[] = [
  { kind: 'child', label: '👶 Child' },
  { kind: 'home', label: '🏠 Home' },
  { kind: 'marriage', label: '💍 Marriage' },
  { kind: 'sabbatical', label: '🌴 Sabbatical' },
  { kind: 'oneTime', label: '💸 One-time' },
  { kind: 'custom', label: '⭐ Custom' },
]

function spanText(e: LifeEvent): string {
  const [a, b] = eventSpan(e)
  if (a === b) return `age ${a}`
  if (!Number.isFinite(b)) return `from age ${a}`
  return `age ${a} → ${b}`
}

export default function LifeEventsPanel({
  events,
  currentAge,
  onChange,
  colors,
  impacts,
}: {
  events: LifeEvent[]
  currentAge: number
  onChange: (events: LifeEvent[]) => void
  colors: string[]
  impacts: number[]
}) {
  const [openId, setOpenId] = useState<string | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null) // event whose name is being inline-edited
  const [draftName, setDraftName] = useState('')
  const patch = (id: string, p: Record<string, unknown>) => onChange(events.map((e) => (e.id === id ? ({ ...e, ...p } as LifeEvent) : e)))
  // One-time/custom events title themselves via `label`; the rest via `name`. Edit whichever applies.
  const startRename = (e: LifeEvent) => {
    setEditingId(e.id)
    setDraftName(e.kind === 'oneTime' || e.kind === 'custom' ? e.label : (e.name ?? ''))
  }
  const commitRename = (id: string) => {
    const e = events.find((x) => x.id === id)
    if (e) {
      const v = draftName.trim()
      if (e.kind === 'oneTime' || e.kind === 'custom') patch(id, { label: v || eventDefaultTitle(e) })
      else patch(id, { name: v || undefined }) // blank ⇒ clear, falling back to the kind default
    }
    setEditingId(null)
  }
  const remove = (id: string) => onChange(events.filter((e) => e.id !== id))
  const add = (kind: EventKind) => {
    const e = newEvent(kind, currentAge)
    onChange([...events, e])
    setOpenId(e.id)
  }

  // Age-sorted rows; color/impact stay keyed to the original index.
  const rows = events
    .map((e, i) => ({ e, color: colors[i] ?? '#888', impact: impacts[i] ?? NaN, enabled: isEnabled(e) }))
    .sort((a, b) => eventAge(a.e) - eventAge(b.e))

  return (
    <div className="rounded-2xl border border-white/[0.07] bg-white/[0.015] p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-neutral-300"><span className="h-2 w-2 rounded-full bg-pink-400" />Life events</h3>
        <div className="flex flex-wrap gap-1.5">
          {ADD.map((a) => (
            <button
              key={a.kind}
              onClick={() => add(a.kind)}
              className="rounded-md border border-white/10 bg-white/[0.03] px-2 py-1 text-xs text-neutral-300 hover:border-emerald-500/40 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60"
            >
              + {a.label}
            </button>
          ))}
        </div>
      </div>

      {events.length === 0 ? (
        <p className="mt-3 text-xs text-neutral-600">Add a child, home purchase, or one-time event to model how it shifts your FIRE date.</p>
      ) : (
        <div className="mt-3 divide-y divide-white/[0.05] overflow-hidden rounded-xl border border-white/[0.07]">
          {rows.map(({ e, color, impact, enabled }) => (
            <div key={e.id}>
              <div
                className={`flex cursor-pointer items-center gap-3 px-3 py-2.5 text-sm transition-colors hover:bg-white/[0.02] ${openId === e.id ? 'bg-white/[0.03]' : ''}`}
                onClick={() => setOpenId((o) => (o === e.id ? null : e.id))}
              >
                <div className={`flex min-w-0 items-center gap-3 transition-opacity ${enabled ? '' : 'opacity-40'}`}>
                  <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: color }} />
                  <span className="shrink-0">{eventIcon(e)}</span>
                  {editingId === e.id ? (
                    <input
                      autoFocus
                      value={draftName}
                      placeholder={eventDefaultTitle(e)}
                      onClick={(ev) => ev.stopPropagation()}
                      onFocus={(ev) => ev.currentTarget.select()}
                      onChange={(ev) => setDraftName(ev.target.value)}
                      onKeyDown={(ev) => {
                        if (ev.key === 'Enter') commitRename(e.id)
                        else if (ev.key === 'Escape') setEditingId(null)
                      }}
                      onBlur={() => commitRename(e.id)}
                      aria-label="Event name"
                      className="w-36 min-w-0 rounded border border-emerald-500/50 bg-white/[0.06] px-1.5 py-0.5 text-sm font-medium text-neutral-100 outline-none"
                    />
                  ) : (
                    <span
                      onClick={(ev) => {
                        ev.stopPropagation()
                        startRename(e)
                      }}
                      title="Click to rename"
                      className={`truncate font-medium decoration-dotted underline-offset-2 hover:underline ${enabled ? 'cursor-text text-neutral-200' : 'cursor-text text-neutral-400 line-through'}`}
                    >
                      {eventTitle(e)}
                    </span>
                  )}
                  <span className="shrink-0 text-xs tabular-nums text-neutral-500">{spanText(e)}</span>
                </div>
                <div className="flex-1" />
                {enabled ? <ImpactBadge impact={impact} /> : <span className="text-[11px] uppercase tracking-wide text-neutral-600">off</span>}
                <button
                  onClick={(ev) => {
                    ev.stopPropagation()
                    patch(e.id, { enabled: !enabled })
                  }}
                  role="switch"
                  aria-checked={enabled}
                  aria-label={`${enabled ? 'Disable' : 'Enable'} ${eventTitle(e)}`}
                  title={enabled ? 'Disable — keep it but exclude from the projection' : 'Enable'}
                  className={`relative h-4 w-7 shrink-0 rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60 ${enabled ? 'bg-emerald-500' : 'bg-white/25'}`}
                >
                  <span className={`absolute left-0.5 top-0.5 h-3 w-3 rounded-full bg-white shadow-sm transition-transform ${enabled ? 'translate-x-3' : 'translate-x-0'}`} />
                </button>
                <span className="text-neutral-600">{openId === e.id ? '▾' : '▸'}</span>
                <button
                  onClick={(ev) => {
                    ev.stopPropagation()
                    remove(e.id)
                  }}
                  aria-label={`Remove ${eventTitle(e)}`}
                  className="rounded text-neutral-600 hover:text-rose-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500/60"
                  title="Remove"
                >
                  ×
                </button>
              </div>

              {openId === e.id && (
                <div className="border-t border-white/[0.05] bg-black/20 px-3 py-3">
                  {e.kind === 'child' && (
                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                      <Num label="Born at age" value={e.startAge} onChange={(v) => patch(e.id, { startAge: Math.round(v) })} />
                      <Num label="For years" value={e.years} onChange={(v) => patch(e.id, { years: Math.round(v) })} />
                      <Num label="Annual cost" prefix="$" step={1000} value={e.annualCost} onChange={(v) => patch(e.id, { annualCost: v })} />
                      <Num label="One-time" prefix="$" step={1000} value={e.birthCost} onChange={(v) => patch(e.id, { birthCost: v })} />
                    </div>
                  )}
                  {e.kind === 'home' && (
                    <div className="space-y-2">
                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                      <Num label="Buy at age" value={e.buyAge} onChange={(v) => patch(e.id, { buyAge: Math.round(v) })} />
                      <Num label="Price" prefix="$" step={10000} value={e.price} onChange={(v) => patch(e.id, { price: v })} />
                      <Num label="Down" suffix="%" value={Math.round(e.downPct * 1000) / 10} onChange={(v) => patch(e.id, { downPct: v / 100 })} />
                      <Num label="Rate" suffix="%" step={0.1} value={Math.round(e.mortgageRate * 1000) / 10} onChange={(v) => patch(e.id, { mortgageRate: v / 100 })} />
                      <Num label="Term" suffix="yr" value={e.termYears} onChange={(v) => patch(e.id, { termYears: Math.round(v) })} />
                      <Num label="Apprec." suffix="%" step={0.1} value={Math.round(e.appreciation * 1000) / 10} onChange={(v) => patch(e.id, { appreciation: v / 100 })} />
                      <Num label="Costs/yr" suffix="%" step={0.1} value={Math.round(e.ongoingPct * 1000) / 10} onChange={(v) => patch(e.id, { ongoingPct: v / 100 })} />
                      <Num label="Sell age (0=keep)" value={e.sellAge ?? 0} onChange={(v) => patch(e.id, { sellAge: v > 0 ? Math.round(v) : null })} />
                      <Num label="Sell cost" suffix="%" step={0.5} value={Math.round(e.sellPct * 1000) / 10} onChange={(v) => patch(e.id, { sellPct: v / 100 })} />
                    </div>
                    <p className="text-[11px] leading-snug text-neutral-500">While you own this home your <span className="text-neutral-300">Rent</span> is replaced by the mortgage + upkeep above — no double-count. Selling resumes it.</p>
                    </div>
                  )}
                  {e.kind === 'oneTime' && (
                    <div className="flex flex-wrap items-end gap-2">
                      <input
                        value={e.label}
                        onChange={(ev) => patch(e.id, { label: ev.target.value })}
                        placeholder="Label (e.g. Inheritance)"
                        className="min-w-[8rem] flex-1 rounded-md border border-white/10 bg-white/[0.03] px-2 py-1.5 text-xs text-neutral-100 outline-none focus:border-emerald-500/50"
                      />
                      <Num label="At age" value={e.age} onChange={(v) => patch(e.id, { age: Math.round(v) })} />
                      <Num label="Amount" prefix="$" step={1000} value={e.amount} onChange={(v) => patch(e.id, { amount: v })} />
                      <ToggleIncome income={e.income} onChange={(income) => patch(e.id, { income })} />
                    </div>
                  )}
                  {e.kind === 'custom' && (
                    <div className="flex flex-wrap items-end gap-2">
                      <input
                        value={e.label}
                        onChange={(ev) => patch(e.id, { label: ev.target.value })}
                        placeholder="Label"
                        className="min-w-[8rem] flex-1 rounded-md border border-white/10 bg-white/[0.03] px-2 py-1.5 text-xs text-neutral-100 outline-none focus:border-emerald-500/50"
                      />
                      <Num label="Start age" value={e.startAge} onChange={(v) => patch(e.id, { startAge: Math.round(v) })} />
                      <Num label="End age" value={e.endAge} onChange={(v) => patch(e.id, { endAge: Math.round(v) })} />
                      <Num label="Annual amt" prefix="$" step={1000} value={e.amount} onChange={(v) => patch(e.id, { amount: v })} />
                      <ToggleIncome income={e.income} onChange={(income) => patch(e.id, { income })} />
                      <label className="flex items-center gap-1.5 pb-1.5 text-[11px] text-neutral-400">
                        <input type="checkbox" checked={e.inflates} onChange={(ev) => patch(e.id, { inflates: ev.target.checked })} className="accent-emerald-500" />
                        Inflates
                      </label>
                    </div>
                  )}
                  {e.kind === 'marriage' && (
                    <div className="space-y-2">
                      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                        <Num label="Marry at age" value={e.age} onChange={(v) => patch(e.id, { age: Math.round(v) })} />
                        <Num label="Ceremony cost" prefix="$" step={1000} value={e.ceremonyCost} onChange={(v) => patch(e.id, { ceremonyCost: v })} />
                        <Num label="Spouse income/yr" prefix="$" step={1000} value={e.spouseIncome} onChange={(v) => patch(e.id, { spouseIncome: v })} />
                        <Num label="Extra spend/yr" prefix="$" step={1000} value={e.spouseSpending} onChange={(v) => patch(e.id, { spouseSpending: v })} />
                        <Num label="Spouse net worth" prefix="$" step={1000} value={e.spouseNetWorth} onChange={(v) => patch(e.id, { spouseNetWorth: v })} />
                      </div>
                      {e.age <= currentAge && (
                        <p className="text-[11px] leading-snug text-neutral-500">Already married (age ≤ today) — spouse income and spending start now; the ceremony and net-worth lump are skipped (already in your numbers).</p>
                      )}
                    </div>
                  )}
                  {e.kind === 'sabbatical' && (
                    <div className="space-y-2">
                      <div className="flex flex-wrap items-end gap-2">
                        <div className="flex flex-col gap-1">
                          <span className="text-[11px] uppercase tracking-wide text-neutral-400">Whose income</span>
                          <div className="flex overflow-hidden rounded-md border border-white/10 text-[11px]">
                            <button onClick={() => patch(e.id, { spouse: false })} className={`px-2 py-1 ${!e.spouse ? 'bg-emerald-500/20 text-emerald-300' : 'text-neutral-500'}`}>You</button>
                            <button onClick={() => patch(e.id, { spouse: true })} className={`px-2 py-1 ${e.spouse ? 'bg-emerald-500/20 text-emerald-300' : 'text-neutral-500'}`}>Spouse</button>
                          </div>
                        </div>
                        <Num label="Start age" value={e.startAge} onChange={(v) => patch(e.id, { startAge: Math.round(v) })} />
                        <Num label="Years" value={e.years} onChange={(v) => patch(e.id, { years: Math.round(v) })} />
                        <Num label="Income cut" suffix="%" step={5} value={Math.round(e.reduction * 1000) / 10} onChange={(v) => patch(e.id, { reduction: Math.min(100, Math.max(0, v)) / 100 })} />
                      </div>
                      <p className="text-[11px] leading-snug text-neutral-500">100% = a full career break (no pay); a lower cut models part-time or lower-paying work — e.g. 40% keeps 60% of that income for the window.</p>
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

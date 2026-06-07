import { useEffect, useMemo, useRef, useState } from 'react'
import type { ChangeEvent, ReactNode } from 'react'
import { runFixed, runMonteCarlo, runLifeSuccess, recommendedRetireAge, runImpacts, socialSecurityBenefit, totalSpending, DEFAULTS } from './engine'
import type { FireInputs, ProjView } from './engine'
import Chart from './Chart'
import { usdShort, pct } from './format'
import { listPlans, savePlan, deletePlan } from './api'
import type { PlanDTO } from './api'
import { PLAN_COLORS, newPlan } from './plans'
import LifeEventsPanel from './LifeEventsPanel'
import { eventAge, eventIcon, eventSpan, EVENT_COLORS, normalizeEvents, isEnabled } from './lifeEvents'
import { useClearableNumber, groupThousands } from './useClearableNumber'

/** Migrate a saved plan's legacy single `spending` number into the Housing/Discretionary/Fixed split:
 *  the whole amount goes to Discretionary (Housing 0), so the total — and every number — is unchanged on
 *  load. The user moves their rent into Housing to activate home-replacement. New/migrated plans (housing
 *  already a number) pass through untouched. */
function migrateSpending(i: FireInputs): FireInputs {
  const legacy = i as FireInputs & { spending?: number }
  if (typeof legacy.spending === 'number' && legacy.housing == null) {
    const { spending, ...rest } = legacy
    return { ...rest, housing: 0, discretionary: spending, fixed: 0 } as FireInputs
  }
  return i
}

const C = {
  initial: 'rgba(251, 191, 36, 0.55)',
  saved: 'rgba(96, 165, 250, 0.5)',
  returns: 'rgba(52, 211, 153, 0.45)',
  netWorth: '#c4b5fd',
  fire: '#fb7185',
  drawdown: '#f472b6',
  drawdownFill: 'rgba(244, 114, 182, 0.13)',
}

/** Tracks whether the viewport is phone-sized (matches Tailwind's `sm` breakpoint), updating on
 *  rotate/resize. Used to size the chart shorter on mobile so it doesn't dominate the scroll. */
function useIsMobile() {
  const query = '(max-width: 640px)'
  const [isMobile, setIsMobile] = useState(() => typeof window !== 'undefined' && window.matchMedia(query).matches)
  useEffect(() => {
    const mq = window.matchMedia(query)
    const onChange = () => setIsMobile(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])
  return isMobile
}

/** Help tooltip anchored to a ⓘ trigger. Native `title` only shows after a ~1s delay (and is flaky in
 *  iframes), so it read as "saying nothing"; this appears instantly on hover, keyboard focus, and tap
 *  (the trigger is focusable). Position is computed in JS and clamped inside the viewport — a centered
 *  fixed-width popover near the right edge would otherwise overflow and give mobile a horizontal
 *  scrollbar. While idle it's display:none, so it never widens the page. */
function InfoTip({ help }: { help: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const [box, setBox] = useState<{ left: number; width: number } | null>(null)
  const open = () => {
    const el = ref.current
    if (!el) return
    const r = el.getBoundingClientRect()
    const m = 8
    const width = Math.min(224, window.innerWidth - m * 2)
    const vpLeft = Math.max(m, Math.min(r.left + r.width / 2 - width / 2, window.innerWidth - width - m))
    setBox({ left: vpLeft - r.left, width }) // left is relative to the trigger (the tooltip's offset parent)
  }
  const close = () => setBox(null)
  return (
    <span ref={ref} className="relative inline-flex items-center" onMouseEnter={open} onMouseLeave={close}>
      <span
        tabIndex={0}
        role="img"
        aria-label={help}
        onFocus={open}
        onBlur={close}
        className="-m-1.5 cursor-help p-1.5 leading-none text-neutral-500 outline-none transition-colors hover:text-neutral-300 focus-visible:text-neutral-300"
      >
        ⓘ
      </span>
      <span
        role="tooltip"
        style={box ? { left: box.left, width: box.width } : undefined}
        className={`pointer-events-none absolute bottom-full z-50 mb-2 rounded-lg border border-white/10 bg-neutral-900/95 px-3 py-2 text-[11px] font-normal normal-case leading-snug tracking-normal text-neutral-200 shadow-xl backdrop-blur ${box ? 'block' : 'hidden'}`}
      >
        {help}
      </span>
    </span>
  )
}

function FieldLabel({ label, help }: { label: string; help?: string }) {
  return (
    <span className="flex items-center gap-1 text-xs font-medium uppercase tracking-wide text-neutral-400">
      {label}
      {help && <InfoTip help={help} />}
    </span>
  )
}

const inputBox =
  'flex items-center rounded-md border border-white/10 bg-white/[0.03] px-2.5 transition-colors focus-within:border-emerald-500/70 focus-within:bg-white/[0.06]'
const inputBoxError =
  'flex items-center rounded-md border border-rose-500/55 bg-white/[0.03] px-2.5 transition-colors focus-within:border-rose-500 focus-within:bg-white/[0.06]'
// text-base (16px) on mobile prevents iOS Safari's auto-zoom on input focus; shrink to 14px on ≥sm.
const inputText = 'w-full bg-transparent px-0.5 py-1.5 text-base tabular-nums text-neutral-50 outline-none placeholder:text-neutral-600 sm:text-sm'
const affix = 'shrink-0 text-sm text-neutral-400'

/** Cursor-preserving onChange for comma-grouped money inputs (keeps the caret on the same digit). */
function moneyOnChange(onText: (raw: string) => void) {
  return (e: ChangeEvent<HTMLInputElement>) => {
    const el = e.currentTarget
    const caret = el.selectionStart ?? el.value.length
    const digitsBefore = (el.value.slice(0, caret).match(/\d/g) || []).length
    onText(el.value)
    requestAnimationFrame(() => {
      const v = el.value
      let seen = 0
      let i = 0
      while (i < v.length && seen < digitsBefore) {
        if (/\d/.test(v[i])) seen++
        i++
      }
      try {
        el.setSelectionRange(i, i)
      } catch {
        /* not a text input */
      }
    })
  }
}

function Field({
  label,
  value,
  defaultValue,
  onChange,
  step = 1,
  prefix,
  suffix,
  help,
  error,
}: {
  label: string
  value: number
  defaultValue: number
  onChange: (n: number) => void
  step?: number
  prefix?: string
  suffix?: string
  help?: string
  error?: string
}) {
  const money = prefix === '$'
  const { text, onText } = useClearableNumber(value, onChange, defaultValue, money)
  return (
    <label className="flex flex-col gap-1">
      <FieldLabel label={label} help={help} />
      <div className={error ? inputBoxError : inputBox}>
        {prefix && <span className={affix}>{prefix}</span>}
        <input
          type={money ? 'text' : 'number'}
          inputMode={money ? 'decimal' : undefined}
          step={money ? undefined : step}
          value={text}
          placeholder={money ? groupThousands(String(defaultValue)) : String(defaultValue)}
          onChange={money ? moneyOnChange(onText) : (e) => onText(e.target.value)}
          onWheel={(e) => e.currentTarget.blur()}
          className={inputText}
          aria-invalid={error ? true : undefined}
        />
        {suffix && <span className={affix}>{suffix}</span>}
      </div>
      {error && <span className="px-1 text-[11px] text-rose-400">{error}</span>}
    </label>
  )
}

/** Like Field, but the value is optional: an empty box means "unset" (→ undefined), so it tracks the
 *  dynamic [placeholder] default (e.g. current spending) instead of pinning to a number. */
function OptionalField({
  label,
  value,
  placeholder,
  onChange,
  prefix,
  help,
  error,
}: {
  label: string
  value: number | undefined
  placeholder: number
  onChange: (n: number | undefined) => void
  prefix?: string
  help?: string
  error?: string
}) {
  const money = prefix === '$'
  const fmt = (n: number | undefined) => (n != null && Number.isFinite(n) ? (money ? groupThousands(String(n)) : String(n)) : '')
  const [text, setText] = useState(() => fmt(value))
  const last = useRef<number | undefined>(value)
  useEffect(() => {
    if (value !== last.current) {
      setText(fmt(value))
      last.current = value
    }
  }, [value])
  const onText = (raw: string) => {
    const cleaned = raw.replace(/,/g, '')
    setText(money ? groupThousands(cleaned) : cleaned)
    const n = parseFloat(cleaned)
    const next = cleaned.trim() === '' || Number.isNaN(n) ? undefined : n
    last.current = next
    onChange(next)
  }
  return (
    <label className="flex flex-col gap-1">
      <FieldLabel label={label} help={help} />
      <div className={error ? inputBoxError : inputBox}>
        {prefix && <span className={affix}>{prefix}</span>}
        <input
          type={money ? 'text' : 'number'}
          inputMode={money ? 'decimal' : undefined}
          value={text}
          placeholder={money ? groupThousands(String(placeholder)) : String(placeholder)}
          onChange={money ? moneyOnChange(onText) : (e) => onText(e.target.value)}
          onWheel={(e) => e.currentTarget.blur()}
          className={inputText}
          aria-invalid={error ? true : undefined}
        />
      </div>
      {error && <span className="px-1 text-[11px] text-rose-400">{error}</span>}
    </label>
  )
}

/** A bounded integer slider — for inputs with a hard, narrow range (e.g. the SS claim age, 62–70). */
function Slider({ label, value, min, max, onChange, help, format }: { label: string; value: number; min: number; max: number; onChange: (n: number) => void; help?: string; format?: (n: number) => string }) {
  const v = Math.min(max, Math.max(min, value))
  return (
    <label className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between">
        <FieldLabel label={label} help={help} />
        <span className="text-sm font-semibold tabular-nums text-neutral-50">{format ? format(v) : v}</span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={1}
        value={v}
        onChange={(e) => onChange(Number(e.target.value))}
        className="h-1.5 w-full cursor-pointer appearance-none rounded-full accent-emerald-500"
        style={{ background: `linear-gradient(to right, #34d399 ${((v - min) / (max - min)) * 100}%, rgba(255,255,255,0.1) ${((v - min) / (max - min)) * 100}%)` }}
      />
      <div className="flex justify-between text-[10px] tabular-nums text-neutral-600">
        <span>{min}</span>
        <span>{max}</span>
      </div>
    </label>
  )
}

/** A compact labelled figure used in the hero's supporting-stats row (lighter than a tier card).
 *  [tone] optionally colors the value (e.g. a risk-graded survival rate). */
function Stat({ label, value, hint, tone = 'text-neutral-50' }: { label: string; value: string; hint?: string; tone?: string }) {
  return (
    <div>
      <div className="text-[11px] font-medium uppercase tracking-wide text-neutral-400">{label}</div>
      <div className={`mt-1 text-lg font-semibold leading-tight tabular-nums ${tone}`}>{value}</div>
      {hint && <div className="mt-0.5 text-[11px] text-neutral-500">{hint}</div>}
    </div>
  )
}

function Section({ title, accent, cols = 2, children }: { title: string; accent: string; cols?: 2 | 3; children: ReactNode }) {
  return (
    <div className="rounded-lg border border-white/[0.07] bg-white/[0.02] p-3">
      <h3 className="mb-2.5 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-neutral-300">
        <span className="h-1.5 w-1.5 rounded-full" style={{ background: accent }} />
        {title}
      </h3>
      <div className={`grid ${cols === 3 ? 'grid-cols-3' : 'grid-cols-2'} gap-x-2.5 gap-y-2.5`}>{children}</div>
    </div>
  )
}

const round2 = (n: number) => Math.round(n * 100) / 100

const sliceEnd = (proj: ProjView) => {
  const last = proj.ages.length - 1
  const end = Number.isFinite(proj.fatYears) ? proj.fatYears : proj.yearsToFire
  return Number.isFinite(end) ? Math.min(Math.ceil(end) + 6, last) : last
}

/** Overlay traces (a liquid line + FIRE marker) for one profile in compare mode. */
function compareTraces(plan: PlanDTO, proj: ProjView, color: string): unknown[] {
  const e = sliceEnd(proj)
  const traces: unknown[] = [
    {
      x: proj.ages.slice(0, e + 1),
      y: proj.liquid.slice(0, e + 1),
      type: 'scatter',
      mode: 'lines',
      name: plan.name,
      line: { color, width: 2 },
      hovertemplate: `${plan.name}: %{y:$,.0f}<extra></extra>`,
    },
  ]
  if (Number.isFinite(proj.yearsToFire)) {
    traces.push({
      x: [plan.inputs.currentAge + proj.yearsToFire],
      y: [proj.fireTarget],
      type: 'scatter',
      mode: 'markers',
      showlegend: false,
      marker: { color, size: 10, symbol: 'circle-open', line: { width: 2 } },
      hovertemplate: `${plan.name} FI<extra></extra>`,
    })
  }
  return traces
}

type PctKey =
  | 'incomeGrowth'
  | 'lifestyleCreep'
  | 'stockPct'
  | 'bondPct'
  | 'cashPct'
  | 'withdrawalRate'
  | 'taxRate'
  | 'stockReturn'
  | 'bondReturn'
  | 'inflation'

const baseAxes = {
  paper_bgcolor: 'rgba(0,0,0,0)',
  plot_bgcolor: 'rgba(0,0,0,0)',
  font: { color: '#9ca3af', size: 13 },
  hoverlabel: { bgcolor: '#111318', bordercolor: 'rgba(255,255,255,0.08)', font: { color: '#e5e7eb', size: 13 } },
  xaxis: { title: { text: 'Age', font: { size: 13 } }, gridcolor: 'rgba(255,255,255,0.05)', zeroline: false, tickfont: { size: 12 } },
  yaxis: { tickprefix: '$', tickformat: '.2s', gridcolor: 'rgba(255,255,255,0.05)', zeroline: false, tickfont: { size: 12 } },
}

export default function App() {
  const [plans, setPlans] = useState<PlanDTO[]>([])
  const [activeId, setActiveId] = useState<string>('')
  const [loaded, setLoaded] = useState(false)
  const [compare, setCompare] = useState(false)
  const [mode, setMode] = useState<'fixed' | 'mc'>('fixed')
  const didInit = useRef(false)
  const deletedIds = useRef<Set<string>>(new Set())
  const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved'>('idle')
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null)
  const confirmTimer = useRef<number | undefined>(undefined)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [draftName, setDraftName] = useState('')
  const renameCanceled = useRef(false)
  const isMobile = useIsMobile()
  const chartHeight = isMobile ? 440 : 540
  const brand = 'sure'
  useEffect(() => {
    document.title = `${brand}FIRE — when can I retire?`
  }, [brand])

  // Load profiles from browser storage (localStorage); seed a Base Plan if none exist yet.
  useEffect(() => {
    if (didInit.current) return // guard against StrictMode double-invoke (avoids seeding two plans)
    didInit.current = true
    listPlans()
      .then(async (ps) => {
        let list = ps.map((p) => {
          const inputs = { ...DEFAULTS, ...migrateSpending(p.inputs) }
          return { ...p, inputs: { ...inputs, lifeEvents: normalizeEvents(inputs.lifeEvents, inputs.currentAge) } }
        })
        if (list.length === 0) {
          const base = newPlan('Base Plan')
          await savePlan(base)
          list = [base]
        }
        setPlans(list)
        setActiveId(list[0].id)
        setLoaded(true)
      })
      .catch(() => {
        const base = newPlan('Base Plan')
        setPlans([base])
        setActiveId(base.id)
        setLoaded(true)
      })
  }, [])

  const active = plans.find((p) => p.id === activeId)

  // Debounced persistence of the active profile.
  useEffect(() => {
    if (!loaded || !active) return
    const snapshot = active
    setSaveState('saving')
    const t = setTimeout(() => {
      if (deletedIds.current.has(snapshot.id)) {
        setSaveState('saved')
        return
      }
      void savePlan(snapshot).then(() => setSaveState('saved'))
    }, 400)
    return () => clearTimeout(t)
  }, [active, loaded])

  const updateInputs = (fn: (i: FireInputs) => FireInputs) =>
    setPlans((ps) => ps.map((p) => (p.id === activeId ? { ...p, inputs: fn(p.inputs) } : p)))
  const inp = active?.inputs ?? DEFAULTS
  const set = <K extends keyof FireInputs>(k: K, v: FireInputs[K]) => updateInputs((i) => ({ ...i, [k]: v }))
  const pf = (k: PctKey) => round2(inp[k] * 100)
  const pd = (k: PctKey) => round2(DEFAULTS[k] * 100)
  const setPct = (k: PctKey, v: number) => updateInputs((i) => ({ ...i, [k]: v / 100 }))

  // Client-side input guardrails — advisory (the engine still computes), shown inline under each field.
  const neg = 'Can’t be negative'
  const verr = {
    currentAge: inp.currentAge < 0 ? neg : inp.currentAge > 110 ? 'Too high' : undefined,
    lifeExpectancy: inp.lifeExpectancy <= inp.currentAge ? `Must be above ${inp.currentAge}` : undefined,
    retireAge:
      inp.retireAge == null ? undefined : inp.retireAge < inp.currentAge ? `Can’t be before ${inp.currentAge}` : inp.retireAge > inp.lifeExpectancy ? `After your death age (${inp.lifeExpectancy})` : undefined,
    initialInvestments: inp.initialInvestments < 0 ? neg : undefined,
    income: inp.income < 0 ? neg : undefined,
    housing: inp.housing < 0 ? neg : undefined,
    discretionary: inp.discretionary < 0 ? neg : undefined,
    fixed: inp.fixed < 0 ? neg : undefined,
    cashBucket: inp.cashBucket < 0 ? neg : undefined,
    retirementSpending: inp.retirementSpending != null && inp.retirementSpending < 0 ? neg : undefined,
    withdrawalRate: inp.withdrawalRate <= 0 ? 'Must be above 0%' : inp.withdrawalRate >= 1 ? 'Must be below 100%' : undefined,
    taxRate: inp.taxRate < 0 ? neg : inp.taxRate >= 1 ? 'Must be below 100%' : undefined,
    leanFactor: inp.leanFactor <= 0 ? 'Must be above 0%' : undefined,
    fatFactor: inp.fatFactor <= 0 ? 'Must be above 0' : undefined,
    socialSecurityAge: inp.socialSecurityAge < 62 || inp.socialSecurityAge > 70 ? '62–70 only' : undefined,
    socialSecurity: inp.socialSecurity < 0 ? neg : undefined,
  }

  const addPlan = async () => {
    const np = newPlan(`Plan ${plans.length + 1}`, inp)
    setPlans((ps) => [...ps, np])
    setActiveId(np.id)
    await savePlan(np)
  }
  // Inline rename (replaces window.prompt, which is blocked in sandboxed/preview frames).
  const startRename = (id: string) => {
    const cur = plans.find((p) => p.id === id)
    if (!cur) return
    setActiveId(id)
    setDraftName(cur.name)
    renameCanceled.current = false
    setEditingId(id)
  }
  const commitRename = (id: string) => {
    setEditingId(null)
    if (renameCanceled.current) {
      renameCanceled.current = false
      return
    }
    const name = draftName.trim()
    const cur = plans.find((p) => p.id === id)
    if (!name || !cur || cur.name === name) return
    setPlans((ps) => ps.map((p) => (p.id === id ? { ...p, name } : p)))
    void savePlan({ ...cur, name })
  }
  const cancelRename = () => {
    renameCanceled.current = true
    setEditingId(null)
  }
  const removePlan = async (id: string) => {
    if (plans.length <= 1) return
    deletedIds.current.add(id) // block any in-flight/debounced save from resurrecting it
    const remaining = plans.filter((p) => p.id !== id)
    setPlans(remaining)
    if (activeId === id) setActiveId(remaining[0].id)
    await deletePlan(id)
  }
  // Destructive action → require a second click within 2.5s to confirm (Nielsen: user control & freedom).
  const onDeleteClick = (id: string) => {
    if (confirmDeleteId === id) {
      window.clearTimeout(confirmTimer.current)
      setConfirmDeleteId(null)
      void removePlan(id)
    } else {
      setConfirmDeleteId(id)
      window.clearTimeout(confirmTimer.current)
      confirmTimer.current = window.setTimeout(() => setConfirmDeleteId(null), 2500)
    }
  }

  const proj = useMemo(() => runFixed(inp), [inp])
  const allProj = useMemo(() => plans.map((p) => ({ plan: p, proj: runFixed(p.inputs) })), [plans])
  const mc = useMemo(() => (mode === 'mc' && !compare ? runMonteCarlo(inp) : null), [inp, mode, compare])
  // The Monte Carlo readouts (recommended age + survival) cost ~100ms, so run them off a DEBOUNCED copy of
  // the inputs — the chart and tiers stay live while you type; these catch up ~150ms after you pause.
  const [mcInp, setMcInp] = useState(inp)
  useEffect(() => {
    const t = setTimeout(() => setMcInp(inp), 150)
    return () => clearTimeout(t)
  }, [inp])
  // Probability the plan lasts if you retire AT your chosen RE age (reuse the MC-mode result when shown,
  // else run the life-path MC). runLifeSuccess returns 1 when there's no drawdown (retire at/after death).
  const lifeSuccess = useMemo(() => (mc ? mc.lifeSuccessRate : runLifeSuccess(mcInp)), [mc, mcInp])
  // The recommended "don't go broke" RE age: the earliest age whose Monte Carlo drawdown clears ~80%
  // survival (risk-adjusted in the engine — so it IS the safe age, no separate reality-check needed).
  const recRetire = useMemo(() => recommendedRetireAge(mcInp), [mcInp])
  const eventColors = inp.lifeEvents.map((_, i) => EVENT_COLORS[i % EVENT_COLORS.length])
  // Per-event marginal FIRE-date impact — computed entirely in the engine (eventImpactsJs).
  const eventImpacts = useMemo(() => runImpacts(inp), [inp])

  // Three FI tiers (lean/FI/fat) — all crossings computed in the Kotlin engine; here we just read them.
  // Red → amber → green across the three tiers: leanFI (bare-minimum, risky) → FI → fatFI (comfortable).
  const tierMeta = [
    { label: 'leanFI', icon: '🥗', color: '#f87171' },
    { label: 'FI', icon: '🍽️', color: '#fbbf24' },
    { label: 'fatFI', icon: '🥩', color: '#34d399' },
  ]
  const singleTiers = [
    { ...tierMeta[0], target: proj.leanTarget, years: proj.leanYears, age: proj.leanAge },
    { ...tierMeta[1], target: proj.fireTarget, years: proj.yearsToFire, age: proj.ageAtFire },
    { ...tierMeta[2], target: proj.fatTarget, years: proj.fatYears, age: proj.fatAge },
  ]
  const mcTiers = mc
    ? [
        { ...tierMeta[0], target: mc.leanTarget, years: mc.leanMedianYears, age: Math.floor(inp.currentAge + mc.leanMedianYears) },
        { ...tierMeta[1], target: mc.fireTarget, years: mc.medianYears, age: Math.floor(inp.currentAge + mc.medianYears) },
        { ...tierMeta[2], target: mc.fatTarget, years: mc.fatMedianYears, age: Math.floor(inp.currentAge + mc.fatMedianYears) },
      ]
    : []

  // Full-life horizon: accumulate up to the retirement age, then draw down to the death age.
  const lastIdx = proj.ages.length - 1
  const deathIdx = Math.min(Math.max(1, inp.lifeExpectancy - inp.currentAge), lastIdx)
  const retireIdx = Math.min(Math.max(0, proj.retireAge - inp.currentAge), deathIdx)
  const hasRetirement = retireIdx < deathIdx // retire before death ⇒ there's a drawdown phase
  const planBroke = proj.depletionAge >= 0 && proj.depletionAge < inp.lifeExpectancy // portfolio runs dry before death
  const toneFor = (r: number) => (r >= 0.8 ? 'text-emerald-300' : r >= 0.5 ? 'text-amber-300' : 'text-rose-400') // risk-graded color
  const hasAssets = inp.cashBucket > 0 || inp.lifeEvents.some((e) => e.kind === 'home')
  const x = proj.ages.slice(0, deathIdx + 1)
  const accX = proj.ages.slice(0, retireIdx + 1) // accumulation phase (stacked decomposition)
  const drawX = proj.ages.slice(retireIdx, deathIdx + 1) // retirement drawdown phase
  const lifeSlice = proj.lifeLiquid.slice(0, deathIdx + 1)

  // A wildly over-funded plan compounds so far past the tiers that a linear axis squashes everything
  // flat. When the balance stays positive and dwarfs fatFIRE, switch to a log axis (and one balance
  // line — stacked areas are meaningless on a log scale); otherwise stay linear, capped to the
  // actionable region with a small negative band if the money runs out.
  const lifeMax = Math.max(...lifeSlice)
  const lifeMin = Math.min(...lifeSlice)
  const useLog = lifeMin > 0 && lifeMax > proj.fatTarget * 2.5
  const accMax = Math.max(inp.initialInvestments, ...proj.liquid.slice(0, retireIdx + 1))
  const drawMin = hasRetirement ? Math.min(...proj.lifeLiquid.slice(retireIdx, deathIdx + 1)) : accMax
  const yTop = Math.max(proj.fatTarget, accMax) * 1.18
  const yBottom = drawMin < 0 ? -0.25 * yTop : 0

  const band = (name: string, y: number[], color: string) => ({
    x: accX,
    y,
    type: 'scatter',
    mode: 'lines',
    name,
    line: { width: 0, color },
    fillcolor: color,
    stackgroup: 'one',
    hovertemplate: `${name}: %{y:$,.0f}<extra></extra>`,
  })
  const netWorthTrace = { x, y: proj.lifeNetWorth.slice(0, deathIdx + 1), type: 'scatter', mode: 'lines', name: 'Net worth', line: { color: C.netWorth, width: 2, dash: 'dot' }, hovertemplate: 'Net worth: %{y:$,.0f}<extra></extra>' }
  // Two timeline markers: you RETIRE (income stops, drawdown begins) at the RE age, then — if that's
  // earlier than your claim age — SOCIAL SECURITY begins later, ending the bridge. They coincide (one
  // marker) when you retire exactly at your claim age.
  const retireMarker = { x: [proj.ages[retireIdx]], y: [proj.lifeLiquid[retireIdx]], type: 'scatter', mode: 'markers', name: 'Retire', showlegend: false, marker: { color: C.fire, size: 12, symbol: 'circle', line: { color: '#0a0b0f', width: 2 } }, hovertemplate: `Retire at ${proj.retireAge}<extra></extra>` }
  const claimAge = proj.claimAge // Social Security claim age (clamped 62–70 in the engine)
  const claimIdx = claimAge - inp.currentAge
  const showSsMarker = inp.socialSecurity > 0 && claimAge > proj.retireAge && claimIdx <= deathIdx // a real bridge
  const ssMarker = { x: [proj.ages[claimIdx]], y: [proj.lifeLiquid[claimIdx]], type: 'scatter', mode: 'markers', name: 'Social Security', showlegend: false, marker: { color: '#fbbf24', size: 11, symbol: 'diamond', line: { color: '#0a0b0f', width: 2 } }, hovertemplate: `Social Security at ${claimAge}<extra></extra>` }
  // VPW/Guardrails flex spending, which bends the retirement-balance line (VPW winds it down toward $0,
  // Guardrails dips/rises) — that's the on-chart "tell". We used to also plot a spending line on a second
  // y-axis, but for an over-funded plan it scaled to absurd $-hundreds-of-thousands and looked broken, so
  // the balance line + a caption carry it instead.
  const flexStrategy = hasRetirement && inp.withdrawalStrategy !== 'fixed'
  // Tier labels (🥗 leanFI etc.) as a text TRACE pinned to the left — annotations with yref:'y' silently
  // fail to render once a secondary y-axis exists, but a scatter-text trace draws reliably in data coords.
  const financedTiers = singleTiers.filter((t) => Number.isFinite(t.years))
  const tierLabelTrace = { x: financedTiers.map(() => x[0]), y: financedTiers.map((t) => t.target), type: 'scatter', mode: 'text', text: financedTiers.map((t) => `${t.icon} ${t.label}`), textposition: 'top right', textfont: { color: financedTiers.map((t) => t.color), size: 11 }, showlegend: false, hoverinfo: 'skip', cliponaxis: false }

  const singleData = useLog
    ? [
        { x, y: lifeSlice, type: 'scatter', mode: 'lines', name: 'Balance', line: { color: C.drawdown, width: 2.5 }, hovertemplate: 'Balance: %{y:$,.0f}<extra></extra>' },
        ...(hasAssets ? [netWorthTrace] : []),
        tierLabelTrace,
        ...(hasRetirement ? [retireMarker] : []),
        ...(showSsMarker ? [ssMarker] : []),
      ]
    : [
        band('Initial', accX.map(() => inp.initialInvestments), C.initial),
        band('Saved', proj.saved.slice(0, retireIdx + 1).map((s) => s - inp.initialInvestments), C.saved),
        band('Returns', proj.returns.slice(0, retireIdx + 1), C.returns),
        // Retirement drawdown: investable balance from retirement down to the death age.
        ...(hasRetirement
          ? [{ x: drawX, y: proj.lifeLiquid.slice(retireIdx, deathIdx + 1), type: 'scatter', mode: 'lines', name: 'Retirement balance', line: { color: C.drawdown, width: 2.5 }, hovertemplate: 'Retirement: %{y:$,.0f}<extra></extra>' }]
          : []),
        ...(hasAssets ? [netWorthTrace] : []),
        tierLabelTrace,
        ...(hasRetirement ? [retireMarker] : []),
        ...(showSsMarker ? [ssMarker] : []),
      ]

  const hexA = (hex: string, a: number) => {
    const n = parseInt(hex.slice(1), 16)
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`
  }
  // Disabled events are excluded from the engine, so drop their bands/icons from the chart too.
  const eventVis = inp.lifeEvents
    .map((e, i) => ({ color: eventColors[i], span: eventSpan(e), icon: eventIcon(e), start: eventAge(e), on: isEnabled(e) }))
    .filter((v) => v.on)
  const chartEnd = x[x.length - 1]
  // Life-event markers as a "flag" lane: each event is a dotted stem at its exact start age topped by
  // an icon chip. Events that fall too close together in age step DOWN into separate lanes so the chips
  // never overlap (the old version pinned every icon to the same y, so clustered events collided).
  const evRangeYears = Math.max(1, chartEnd - x[0])
  const laneLastX: number[] = []
  const placedEvents = [...eventVis]
    .sort((a, b) => a.start - b.start)
    .map((v) => {
      let lane = 0
      while (lane < laneLastX.length && v.start - laneLastX[lane] < evRangeYears * 0.04) lane++
      laneLastX[lane] = v.start
      return { ...v, lane }
    })
  const laneY = (lane: number) => 0.985 - lane * 0.07 // paper-y of the chip; clustered events step downward
  const eventShapes = [
    // Faint full-height band over a duration event's span (clamp an indefinitely-held home to the edge).
    ...placedEvents
      .filter((v) => v.span[1] > v.span[0])
      .map((v) => ({ type: 'rect', xref: 'x', yref: 'paper', x0: v.span[0], x1: Number.isFinite(v.span[1]) ? v.span[1] : chartEnd, y0: 0, y1: 1, fillcolor: hexA(v.color, 0.06), line: { width: 0 }, layer: 'below' })),
    // A dotted stem from the axis up to each event's chip, marking its exact start age.
    ...placedEvents.map((v) => ({ type: 'line', xref: 'x', yref: 'paper', x0: v.start, x1: v.start, y0: 0, y1: laneY(v.lane), line: { color: hexA(v.color, 0.4), width: 1, dash: 'dot' } })),
  ]
  // Icon chips at the top of each stem, stacked by lane so they never overlap. Kept slim (small glyph,
  // tight padding, faint hairline border) so a cluster of early-life events doesn't crowd the corner.
  const eventAnnotations = placedEvents.map((v) => ({
    x: v.start,
    y: laneY(v.lane),
    yref: 'paper',
    yanchor: 'middle',
    xanchor: 'center',
    text: v.icon,
    showarrow: false,
    font: { size: 13 },
    bgcolor: 'rgba(12,14,19,0.85)',
    bordercolor: hexA(v.color, 0.55),
    borderwidth: 1,
    borderpad: 2,
  }))

  const singleLayout = {
    ...baseAxes,
    xaxis: { ...baseAxes.xaxis, range: [x[0], x[x.length - 1]] },
    yaxis: useLog
      ? { ...baseAxes.yaxis, type: 'log', dtick: 1, tickformat: '~s' } // decades only ($1M·$10M·$100M) — no minor-tick clutter
      : { ...baseAxes.yaxis, range: [yBottom, yTop] },
    height: chartHeight,
    margin: { l: 66, r: 20, t: 16, b: 64 },
    hovermode: 'x unified',
    legend: { orientation: 'h', y: -0.16, x: 0.5, xanchor: 'center', font: { size: 12 } },
    shapes: [
      ...singleTiers.filter((t) => Number.isFinite(t.years)).map((t) => ({ type: 'line', xref: 'x', yref: 'y', x0: x[0], x1: x[x.length - 1], y0: t.target, y1: t.target, line: { color: t.color, width: 1.25, dash: 'dash' } })),
      ...eventShapes,
    ],
    annotations: [
      // When the (linear) retirement balance compounds off the top of the frame, label its end value.
      ...(!useLog && hasRetirement && proj.lifeLiquid[deathIdx] > yTop
        ? [{ x: x[x.length - 1], y: yTop * 0.95, xref: 'x', yref: 'y', xanchor: 'right', yanchor: 'top', text: `↑ ${usdShort(proj.lifeLiquid[deathIdx])} by ${inp.lifeExpectancy}`, showarrow: false, font: { color: C.drawdown, size: 10 } }]
        : []),
      ...eventAnnotations,
    ],
  }

  const compareData = allProj.flatMap(({ plan, proj: pr }, i) => compareTraces(plan, pr, PLAN_COLORS[i % PLAN_COLORS.length]))
  const compareLayout = { ...baseAxes, height: chartHeight, margin: { l: 66, r: 24, t: 16, b: 64 }, hovermode: 'closest', legend: { orientation: 'h', y: -0.16, x: 0.5, xanchor: 'center', font: { size: 12 } } }

  const showMc = mode === 'mc' && !compare && mc !== null
  const heroTiers = showMc && mc ? mcTiers : singleTiers
  const mcEndYears = mc ? (Number.isFinite(mc.fatMedianYears) ? mc.fatMedianYears : mc.medianYears) : NaN
  const mcEnd = mc ? (Number.isFinite(mcEndYears) ? Math.min(Math.ceil(mcEndYears) + 6, mc.ages.length - 1) : mc.ages.length - 1) : 0
  const mcx = mc ? mc.ages.slice(0, mcEnd + 1) : []
  const mcBand = (lo: number[], hi: number[], fill: string, name: string) => [
    { x: mcx, y: hi.slice(0, mcEnd + 1), type: 'scatter', mode: 'lines', line: { width: 0 }, showlegend: false, hoverinfo: 'skip' },
    { x: mcx, y: lo.slice(0, mcEnd + 1), type: 'scatter', mode: 'lines', line: { width: 0 }, fill: 'tonexty', fillcolor: fill, name, hovertemplate: `${name}: %{y:$,.0f}<extra></extra>` },
  ]
  const mcFinanced = mcTiers.filter((t) => Number.isFinite(t.years))
  const mcTierLabels = { x: mcFinanced.map(() => mcx[0]), y: mcFinanced.map((t) => t.target), type: 'scatter', mode: 'text', text: mcFinanced.map((t) => `${t.icon} ${t.label}`), textposition: 'top right', textfont: { color: mcFinanced.map((t) => t.color), size: 11 }, showlegend: false, hoverinfo: 'skip', cliponaxis: false }
  const mcData = mc
    ? [
        ...mcBand(mc.p10, mc.p90, 'rgba(96,165,250,0.12)', '10–90%'),
        ...mcBand(mc.p25, mc.p75, 'rgba(96,165,250,0.22)', '25–75%'),
        { x: mcx, y: mc.p50.slice(0, mcEnd + 1), type: 'scatter', mode: 'lines', line: { color: '#93c5fd', width: 2 }, name: 'Median', hovertemplate: 'Median: %{y:$,.0f}<extra></extra>' },
        mcTierLabels,
        ...mcTiers
          .filter((t) => Number.isFinite(t.years))
          .map((t) => ({ x: [inp.currentAge + t.years], y: [t.target], type: 'scatter', mode: 'markers', showlegend: false, marker: { color: t.color, size: 11, symbol: 'circle-open', line: { width: 2.5 } }, hovertemplate: `${t.label}<extra></extra>` })),
      ]
    : []
  const mcLayout = {
    ...baseAxes,
    xaxis: { ...baseAxes.xaxis, range: mc ? [mcx[0], mcx[mcx.length - 1]] : undefined },
    height: chartHeight,
    margin: { l: 66, r: 24, t: 16, b: 64 },
    hovermode: 'x unified',
    legend: { orientation: 'h', y: -0.16, x: 0.5, xanchor: 'center', font: { size: 12 } },
    shapes: [...mcTiers.filter((t) => Number.isFinite(t.years)).map((t) => ({ type: 'line', xref: 'x', yref: 'y', x0: mcx[0], x1: mcx[mcx.length - 1], y0: t.target, y1: t.target, line: { color: t.color, width: 1.25, dash: 'dash' } })), ...eventShapes],
    annotations: [...eventAnnotations],
  }

  const allocSum = Math.round((inp.stockPct + inp.bondPct + inp.cashPct) * 100)
  const ssActual = socialSecurityBenefit(inp.socialSecurity, inp.socialSecurityAge) // claim-age-adjusted (engine)

  if (!loaded) {
    return <div className="flex min-h-screen items-center justify-center bg-[#0a0b0f] text-neutral-500">Loading profiles…</div>
  }

  return (
    <div className="min-h-screen bg-[#0a0b0f] text-neutral-200">
      <div className="mx-auto max-w-6xl px-5 py-8">
        <header className="mb-5 flex items-center gap-3">
          <img src={`${import.meta.env.BASE_URL}logo.png`} alt={`${brand}FIRE logo`} className="h-10 w-auto rounded-lg ring-1 ring-white/10" />
          <h1 className="text-2xl font-bold tracking-tight text-white">
            {brand}
            <span className="text-emerald-400">FIRE</span>
          </h1>
          <span className="hidden text-sm text-neutral-500 sm:inline">when can I retire? — net-worth forecasting</span>
        </header>

        {/* Profile bar */}
        <div className="mb-5 flex flex-wrap items-center gap-2">
          {plans.map((p, i) => {
            const active = p.id === activeId
            const confirming = confirmDeleteId === p.id
            const editing = editingId === p.id
            return (
              <div
                key={p.id}
                className={`flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-sm transition-colors ${active ? 'border-emerald-500/50 bg-emerald-500/10 text-white' : 'border-white/10 bg-white/[0.03] text-neutral-400'}`}
              >
                {editing ? (
                  <input
                    autoFocus
                    value={draftName}
                    onChange={(e) => setDraftName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') (e.currentTarget as HTMLInputElement).blur()
                      else if (e.key === 'Escape') cancelRename()
                    }}
                    onBlur={() => commitRename(p.id)}
                    aria-label="Profile name"
                    className="w-32 rounded bg-black/30 px-1.5 py-0.5 text-sm text-white outline-none ring-1 ring-emerald-500/60"
                  />
                ) : (
                  <>
                    <button
                      onClick={() => setActiveId(p.id)}
                      onDoubleClick={() => startRename(p.id)}
                      aria-current={active}
                      title="Double-click to rename"
                      className="flex items-center gap-2 rounded hover:text-neutral-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60"
                    >
                      <span className="h-2 w-2 rounded-full" style={{ background: PLAN_COLORS[i % PLAN_COLORS.length] }} />
                      {p.name}
                    </button>
                    {active && (
                      <button
                        onClick={() => startRename(p.id)}
                        aria-label={`Rename profile ${p.name}`}
                        title="Rename"
                        className="-m-1 rounded p-1 text-neutral-500 hover:text-neutral-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60"
                      >
                        ✎
                      </button>
                    )}
                    {plans.length > 1 && (
                      <button
                        onClick={() => onDeleteClick(p.id)}
                        aria-label={confirming ? `Confirm delete ${p.name}` : `Delete profile ${p.name}`}
                        title={confirming ? 'Click again to delete' : 'Delete'}
                        className={`-m-1 rounded p-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500/60 ${confirming ? 'font-semibold text-rose-400' : 'text-neutral-600 hover:text-rose-400'}`}
                      >
                        {confirming ? '✓?' : '×'}
                      </button>
                    )}
                  </>
                )}
              </div>
            )
          })}
          <button
            onClick={() => void addPlan()}
            aria-label="New profile"
            className="rounded-lg border border-dashed border-white/15 px-3 py-1.5 text-sm text-neutral-400 hover:text-neutral-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60"
          >
            + New
          </button>
          <span className="text-xs text-neutral-600 transition-opacity" aria-live="polite">
            {saveState === 'saving' ? 'Saving…' : saveState === 'saved' ? '✓ Saved' : ''}
          </span>
          <div className="flex-1" />
          {!compare && (
            <div className="flex overflow-hidden rounded-lg border border-white/10 text-sm" role="group" aria-label="Projection method">
              <button
                onClick={() => setMode('fixed')}
                aria-pressed={mode === 'fixed'}
                className={`px-3 py-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-emerald-500/60 ${mode === 'fixed' ? 'bg-white/10 text-white' : 'text-neutral-400 hover:text-neutral-200'}`}
              >
                Fixed
              </button>
              <button
                onClick={() => setMode('mc')}
                aria-pressed={mode === 'mc'}
                className={`px-3 py-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-emerald-500/60 ${mode === 'mc' ? 'bg-white/10 text-white' : 'text-neutral-400 hover:text-neutral-200'}`}
              >
                Monte Carlo
              </button>
            </div>
          )}
          <button
            onClick={() => setCompare((c) => !c)}
            aria-pressed={compare}
            className={`rounded-lg border px-3 py-1.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/60 ${compare ? 'border-violet-500/50 bg-violet-500/10 text-white' : 'border-white/10 text-neutral-400 hover:text-neutral-200'}`}
          >
            {compare ? '◉ Comparing' : 'Compare'}
          </button>
        </div>

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3 lg:items-start">
          {/* Configuration — left sidebar (1/3) on desktop; below the results on mobile */}
          <div className="order-2 lg:order-1 lg:col-span-1">
            <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-neutral-400">Configuration</h2>
            <div className="grid grid-cols-1 items-start gap-4 sm:grid-cols-2 lg:grid-cols-1">
            {/* Column 1 — you & your cashflow */}
            <div className="space-y-3">
            <Section title="You" accent="#34d399">
              <Field label="Current age" value={inp.currentAge} defaultValue={DEFAULTS.currentAge} onChange={(v) => set('currentAge', Math.round(v))} error={verr.currentAge} />
              <Field label="Expected death" suffix="yrs" step={1} help="Age your retirement balance is drawn down to. ~95 is conservative." value={inp.lifeExpectancy} defaultValue={DEFAULTS.lifeExpectancy} onChange={(v) => set('lifeExpectancy', v)} error={verr.lifeExpectancy} />
              <Field label="Investments" prefix="$" step={1000} value={inp.initialInvestments} defaultValue={DEFAULTS.initialInvestments} onChange={(v) => set('initialInvestments', v)} error={verr.initialInvestments} />
              <div className="col-span-2">
                <OptionalField label="Retire at" help="Age you stop working — income ends, drawdown begins. Below your SS age models an early-retirement bridge; blank = retire when SS starts." value={inp.retireAge} placeholder={claimAge} onChange={(v) => set('retireAge', v)} error={verr.retireAge} />
              </div>
            </Section>
            <Section title="Cash flow" accent="#60a5fa">
              <p className="col-span-2 -mb-1 text-[10px] font-semibold uppercase tracking-wider text-neutral-500">Income</p>
              <Field label="Income (after tax)" prefix="$" step={1000} help="Take-home pay. You save income − spending each year." value={inp.income} defaultValue={DEFAULTS.income} onChange={(v) => set('income', v)} error={verr.income} />
              <Field label="Income growth" suffix="%" step={0.1} help="Real (above-inflation) raises per year." value={pf('incomeGrowth')} defaultValue={pd('incomeGrowth')} onChange={(v) => setPct('incomeGrowth', v)} />
              <p className="col-span-2 mt-0.5 -mb-1 border-t border-white/5 pt-2.5 text-[10px] font-semibold uppercase tracking-wider text-neutral-500">Spending</p>
              <Field label="Rent" prefix="$" step={1000} help="Rent / shelter. A Home event replaces this with the mortgage + upkeep while you own — so buying never double-counts it." value={inp.housing} defaultValue={DEFAULTS.housing} onChange={(v) => set('housing', v)} error={verr.housing} />
              <Field label="Discretionary" prefix="$" step={1000} help="Travel, dining, fun." value={inp.discretionary} defaultValue={DEFAULTS.discretionary} onChange={(v) => set('discretionary', v)} error={verr.discretionary} />
              <Field label="Fixed" prefix="$" step={1000} help="Utilities, insurance, subscriptions." value={inp.fixed} defaultValue={DEFAULTS.fixed} onChange={(v) => set('fixed', v)} error={verr.fixed} />
              <Field label="Lifestyle creep" suffix="%" step={0.1} help="Real growth in spending over time. Off by default." value={pf('lifestyleCreep')} defaultValue={pd('lifestyleCreep')} onChange={(v) => setPct('lifestyleCreep', v)} />
              <p className="col-span-2 mt-0.5 text-[11px] leading-snug text-neutral-500">Total <span className="tabular-nums text-neutral-200">{usdShort(totalSpending(inp))}</span>/yr — the FI anchor. Rent drops out while you own a home.</p>
            </Section>
            </div>
            {/* Column 2 — portfolio & market assumptions */}
            <div className="space-y-3">
            <Section accent="#a78bfa" cols={3} title={`Allocation${allocSum !== 100 ? ` · ⚠ ${allocSum}%` : ''}`}>
              <Field label="Stock" suffix="%" value={pf('stockPct')} defaultValue={pd('stockPct')} onChange={(v) => setPct('stockPct', v)} />
              <Field label="Bond" suffix="%" value={pf('bondPct')} defaultValue={pd('bondPct')} onChange={(v) => setPct('bondPct', v)} />
              <Field label="Cash" suffix="%" value={pf('cashPct')} defaultValue={pd('cashPct')} onChange={(v) => setPct('cashPct', v)} />
              {allocSum !== 100 && <p className="col-span-3 -mt-1 text-[11px] text-rose-400">Stock + bond + cash must total 100% (now {allocSum}%).</p>}
            </Section>
            <Section title="Returns & assumptions" accent="#22d3ee">
              <Field label="Stock return" suffix="%" step={0.1} help="Nominal (headline) return, ~10%. Converted to real via your inflation rate." value={pf('stockReturn')} defaultValue={pd('stockReturn')} onChange={(v) => setPct('stockReturn', v)} />
              <Field label="Bond return" suffix="%" step={0.1} help="Nominal (headline) return, ~4%. Converted to real via your inflation rate." value={pf('bondReturn')} defaultValue={pd('bondReturn')} onChange={(v) => setPct('bondReturn', v)} />
              <Field label="Inflation" suffix="%" step={0.1} help="Converts nominal returns to real, and deflates fixed-nominal items like mortgages." value={pf('inflation')} defaultValue={pd('inflation')} onChange={(v) => setPct('inflation', v)} />
              <Field label="Cash reserve" prefix="$" step={1000} help="Emergency fund — counts toward net worth, not the FI target." value={inp.cashBucket} defaultValue={DEFAULTS.cashBucket} onChange={(v) => set('cashBucket', v)} error={verr.cashBucket} />
            </Section>
            </div>
            {/* Column 3 — the retirement plan */}
            <div className="space-y-3">
            <Section title="FI target" accent="#fb7185">
              <OptionalField label="Retirement spend" prefix="$" help="Annual spending in retirement. Blank = current total (rent drops if you own a home). Anchors the FI tiers." value={inp.retirementSpending} placeholder={totalSpending(inp)} onChange={(v) => set('retirementSpending', v)} error={verr.retirementSpending} />
              <Field label="Withdrawal rate" suffix="%" step={0.1} help="Share of the nest egg withdrawn per year (the 4% rule). FI target = spending ÷ this." value={pf('withdrawalRate')} defaultValue={pd('withdrawalRate')} onChange={(v) => setPct('withdrawalRate', v)} error={verr.withdrawalRate} />
              <Field label="Avg tax rate" suffix="%" step={0.1} help="Average tax on withdrawals; grosses up the target so you net your spending." value={pf('taxRate')} defaultValue={pd('taxRate')} onChange={(v) => setPct('taxRate', v)} error={verr.taxRate} />
              <Field label="leanFI" suffix="%" step={1} help="leanFI = this % of your spending (essentials only)." value={Math.round(inp.leanFactor * 100)} defaultValue={65} onChange={(v) => set('leanFactor', v / 100)} error={verr.leanFactor} />
              <Field label="fatFI" suffix="×" step={0.1} help="fatFI = this multiple of your spending (abundance)." value={Math.round(inp.fatFactor * 10) / 10} defaultValue={2.0} onChange={(v) => set('fatFactor', v)} error={verr.fatFactor} />
            </Section>
            <Section title="Retirement & drawdown" accent="#fbbf24">
              <div className="col-span-2">
                <Slider label="Claim Social Security at" value={inp.socialSecurityAge} min={62} max={70} format={(n) => `age ${n}`} help="When Social Security starts (62–70). Delaying raises it: ~70% at 62, 100% at 67, 124% at 70." onChange={(v) => set('socialSecurityAge', v)} />
              </div>
              <Field label="Benefit at 67 (full age)" prefix="$" step={1000} help="Annual benefit at full age (67), today's dollars. US avg ≈ $25k, max ≈ $50k. 0 to ignore." value={inp.socialSecurity} defaultValue={DEFAULTS.socialSecurity} onChange={(v) => set('socialSecurity', v)} error={verr.socialSecurity} />
              <div className="col-span-2 flex flex-col gap-1.5">
                <FieldLabel label="Withdrawal strategy" help="How you spend in retirement. Fixed: constant real (4% rule). VPW: a rising % of balance — never depletes. Guardrails: cut 10% when the draw runs hot, raise 10% when cold." />
                <div className="flex overflow-hidden rounded-lg border border-white/10 text-xs" role="group" aria-label="Withdrawal strategy">
                  {(['fixed', 'vpw', 'guardrails'] as const).map((k) => (
                    <button
                      key={k}
                      type="button"
                      onClick={() => set('withdrawalStrategy', k)}
                      aria-pressed={inp.withdrawalStrategy === k}
                      className={`flex-1 px-2 py-2 capitalize transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-emerald-500/60 ${inp.withdrawalStrategy === k ? 'bg-white/10 text-white' : 'text-neutral-400 hover:text-neutral-200'}`}
                    >
                      {k === 'vpw' ? 'VPW' : k}
                    </button>
                  ))}
                </div>
                <p className="text-[11px] leading-snug text-neutral-500">
                  {inp.withdrawalStrategy === 'fixed'
                    ? 'Spend the same amount every year (the 4% rule). The simplest plan and the right default — it answers “does my lifestyle last?”'
                    : inp.withdrawalStrategy === 'vpw'
                      ? 'Spend a rising % of your balance, drawing the pot down to ~$0 by your death age — it never runs out, but answers “how much could I spend?”, so an over-funded plan shows very high early spending.'
                      : 'Start at your withdrawal rate, then trim spending 10% when the draw runs hot and bump it 10% when it runs cold (Guyton-Klinger). Flexible spending kept inside a safe band.'}
                </p>
              </div>
              <p className="col-span-2 px-1 text-xs leading-relaxed text-neutral-400">
                You retire at <span className="text-neutral-200">age {proj.retireAge}</span>
                {inp.socialSecurity <= 0 ? (
                  <> — earned income stops and you draw entirely from savings (no Social Security).</>
                ) : claimAge > proj.retireAge ? (
                  <>
                    ; your portfolio bridges the gap until <span className="text-amber-300/90">Social Security</span> begins at age {claimAge}, paying{' '}
                    {usdShort(ssActual)}/yr ({Math.round((ssActual / inp.socialSecurity) * 100)}% of your age-67 benefit).
                  </>
                ) : (
                  <>
                    , collecting {usdShort(ssActual)}/yr of Social Security ({Math.round((ssActual / inp.socialSecurity) * 100)}% of your age-67 benefit) — earned income stops and you draw the rest from savings.
                  </>
                )}
              </p>
            </Section>
            </div>
            </div>
          </div>

          {/* Results — on top, full width */}
          <div className="order-1 space-y-5 lg:order-2 lg:col-span-2">
            <details className="rounded-2xl border border-white/[0.06] bg-white/[0.015] p-4 text-sm">
              <summary className="cursor-pointer font-medium text-neutral-300">How {brand}FIRE works</summary>
              <ul className="mt-3 list-disc space-y-2 pl-4 text-neutral-500">
                <li>
                  <b className="text-rose-300/90">FI</b> (financial independence) = enough <span className="text-blue-400/90">liquid investments</span> to live off: your spending ÷ withdrawal rate, grossed up for tax. <b className="text-emerald-300/90">leanFI · FI · fatFI</b> mark 65% / 100% / 200% of that.
                </li>
                <li>
                  <b className="text-emerald-300/90">RE</b> (retire age) = when you actually stop working — set it and the chart draws your drawdown from there. Retire before your Social Security age and the portfolio <b className="text-neutral-300">bridges</b> the gap until benefits start.
                </li>
                <li>
                  On the chart, the filled bands split your investments into <span className="text-amber-400/90">initial</span> · <span className="text-blue-400/90">saved</span> · <span className="text-emerald-400/90">returns</span> while you work, then a <span style={{ color: C.drawdown }}>line</span> for the retirement drawdown.
                </li>
                <li>
                  <b className="text-neutral-300">Plan survives</b> = share of 1,000 fat-tailed (Student-t) market simulations where the money lasts to age {inp.lifeExpectancy}. <b className="text-neutral-300">Fixed</b> mode uses one steady return instead.
                </li>
                <li>Everything is in today's (real) dollars. Plans save to your browser; <b className="text-neutral-300">Compare</b> overlays them.</li>
                <li>I don't save your data — {brand}FIRE has no backend, and nothing you type ever leaves your browser. Don't believe me? <a href="https://github.com/lynchifer/sureFIRE" target="_blank" rel="noreferrer" className="text-neutral-300 underline decoration-dotted underline-offset-2 transition-colors hover:text-neutral-200">read the source</a>.</li>
              </ul>
            </details>
            <div className="rounded-2xl border border-white/[0.07] bg-gradient-to-b from-emerald-500/[0.05] to-white/[0.015] p-5">
              <div className="flex items-center justify-between">
                <div className="text-sm font-semibold text-neutral-100">
                  {active?.name ?? 'Plan'}
                  {showMc ? <span className="font-normal text-neutral-500"> · Monte Carlo median</span> : ''}
                </div>
                <div className="rounded-full bg-white/[0.05] px-2.5 py-1 text-[11px] font-medium tabular-nums text-neutral-400">{pct(proj.savingsRate, 0)} saved</div>
              </div>
              <div className="mt-4 grid grid-cols-3 gap-2.5">
                {heroTiers.map((t) => {
                  const fin = Number.isFinite(t.years)
                  return (
                    <div
                      key={t.label}
                      className="min-w-0 rounded-xl border p-3"
                      style={{ borderColor: hexA(t.color, 0.22), background: hexA(t.color, 0.07) }}
                    >
                      <div className="flex items-center gap-1 text-sm font-semibold" style={{ color: t.color }}>
                        <span className="text-base">{t.icon}</span>
                        {t.label}
                      </div>
                      <div className={`mt-2 flex items-baseline gap-1 font-bold leading-none tabular-nums ${fin ? 'text-white' : 'text-neutral-600'}`}>
                        <span className="text-2xl sm:text-[2.1rem]">{fin ? t.years.toFixed(1) : '—'}</span>
                        {fin && <span className="text-xs font-medium text-neutral-400">yr</span>}
                      </div>
                      <div className="mt-1.5 text-[11px] tabular-nums text-neutral-500">
                        {fin ? <>age {t.age} · {usdShort(t.target)}</> : <>{usdShort(t.target)} target</>}
                      </div>
                    </div>
                  )
                })}
              </div>
              <p className="mt-3 text-xs leading-relaxed text-neutral-400">
                Scales your <span className="text-neutral-200">{usdShort(inp.retirementSpending ?? totalSpending(inp))}</span>{' '}
                {inp.retirementSpending != null ? 'retirement' : 'annual'} spend (<span className="text-red-300/90">{Math.round(inp.leanFactor * 100)}%</span> · <span className="text-amber-300/90">100%</span> · <span className="text-emerald-300/90">{Math.round(inp.fatFactor * 100)}%</span>) ÷ your{' '}
                <span className="text-neutral-200">{(inp.withdrawalRate * 100).toFixed(1)}%</span> withdrawal rate, grossed up for tax
                {Math.round(proj.retirementEventCost) !== 0 && (
                  <>
                    , {proj.retirementEventCost > 0 ? 'plus' : 'less'}{' '}
                    <span className="text-neutral-300">{usdShort(Math.abs(proj.retirementEventCost))}/yr</span> of life-event {proj.retirementEventCost > 0 ? 'costs' : 'income'}
                  </>
                )}
                .
              </p>
              <div className="mt-3 grid grid-cols-1 divide-y divide-white/[0.06] overflow-hidden rounded-xl border border-white/[0.05] bg-white/[0.02] sm:grid-cols-3 sm:divide-x sm:divide-y-0">
                <div className="px-4 py-3"><Stat label="Retire age" value={`${proj.retireAge}`} hint="when drawdown starts" /></div>
                <div className="px-4 py-3"><Stat label={`Portfolio @ ${proj.retireAge}`} value={usdShort(proj.lifeLiquid[retireIdx])} hint="invested at retirement" /></div>
                <div className="px-4 py-3"><Stat label="Plan survives" value={lifeSuccess != null ? pct(lifeSuccess, 0) : '—'} tone={lifeSuccess != null ? toneFor(lifeSuccess) : undefined} hint={planBroke ? `broke at ${proj.depletionAge}` : `lasts to ${inp.lifeExpectancy}`} /></div>
              </div>
              <div className="mt-3 flex items-start gap-2.5 rounded-xl border border-white/[0.06] bg-white/[0.025] px-3.5 py-2.5">
                <span className="mt-px text-sm leading-none">💡</span>
                <p className="text-xs leading-relaxed text-neutral-400">
                  <button type="button" onClick={() => set('retireAge', recRetire)} className="font-semibold text-emerald-400 underline decoration-dotted underline-offset-2 hover:text-emerald-300">
                    Age {recRetire}
                  </button>{' '}
                  is the earliest you can retire with ~<span className="font-semibold text-emerald-300">80%</span> of markets lasting to {inp.lifeExpectancy}.
                  {planBroke ? (
                    <span className="text-rose-400"> Yours runs dry at {proj.depletionAge}.</span>
                  ) : proj.retireAge < recRetire ? (
                    <span className="text-amber-300"> Your age {proj.retireAge} only clears <span className="font-semibold">{pct(lifeSuccess, 0)}</span>.</span>
                  ) : null}
                </p>
              </div>
              {showMc && mc && (
                <div className="mt-2 text-xs text-neutral-500">
                  {Number.isFinite(mc.medianYears)
                    ? `${(mc.successRate * 100).toFixed(0)}% reach FI within ${inp.maxYears} yrs${Number.isFinite(mc.p10Years) ? ` · FI p10–p90 ${mc.p10Years.toFixed(0)}–${mc.p90Years.toFixed(0)} yr` : ''}`
                    : `Most paths don't reach FI within ${inp.maxYears} years`}
                </div>
              )}
            </div>

            {compare && (
              <div className="overflow-hidden rounded-2xl border border-white/[0.07] bg-white/[0.02]">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-[11px] uppercase tracking-wide text-neutral-500">
                      <th className="px-4 py-2.5 font-medium">Profile</th>
                      <th className="px-4 py-2.5 text-right font-medium">Years</th>
                      <th className="px-4 py-2.5 text-right font-medium">Age</th>
                      <th className="px-4 py-2.5 text-right font-medium">FI target</th>
                      <th className="px-4 py-2.5 text-right font-medium">Net worth @ FI</th>
                    </tr>
                  </thead>
                  <tbody>
                    {allProj.map(({ plan, proj: pr }, i) => {
                      const fin = Number.isFinite(pr.yearsToFire)
                      const nw = pr.netWorthAtFire
                      return (
                        <tr key={plan.id} className={`border-t border-white/[0.05] ${plan.id === activeId ? 'bg-white/[0.03]' : ''}`}>
                          <td className="px-4 py-2.5">
                            <span className="mr-2 inline-block h-2 w-2 rounded-full align-middle" style={{ background: PLAN_COLORS[i % PLAN_COLORS.length] }} />
                            {plan.name}
                          </td>
                          <td className="px-4 py-2.5 text-right tabular-nums">{fin ? pr.yearsToFire.toFixed(1) : '—'}</td>
                          <td className="px-4 py-2.5 text-right tabular-nums">{fin ? pr.ageAtFire : '—'}</td>
                          <td className="px-4 py-2.5 text-right tabular-nums">{usdShort(pr.fireTarget)}</td>
                          <td className="px-4 py-2.5 text-right tabular-nums">{usdShort(nw)}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}

            <div className="rounded-2xl border border-white/[0.07] bg-white/[0.015] p-4">
              <Chart data={compare ? compareData : showMc ? mcData : singleData} layout={compare ? compareLayout : showMc ? mcLayout : singleLayout} />
            </div>

            {!compare && !showMc && flexStrategy && (
              <p className="text-[13px] leading-relaxed text-neutral-500">
                {inp.withdrawalStrategy === 'vpw'
                  ? `The retirement balance bends down toward $0 by ${inp.lifeExpectancy} on purpose — VPW spends a rising slice of it each year. That dip is you spending the surplus, not running out.`
                  : `The retirement balance flexes as Guyton-Klinger trims spending after weak markets and lifts it after strong ones, so it bends rather than holding a flat path.`}
              </p>
            )}

            {!compare && (
              <LifeEventsPanel events={inp.lifeEvents} currentAge={inp.currentAge} onChange={(evs) => set('lifeEvents', evs)} colors={eventColors} impacts={eventImpacts} />
            )}

            {compare && (
              <p className="text-[13px] leading-relaxed text-neutral-500">
                Each line is a profile's liquid investments; the open circle marks where it crosses its FI target. Profiles are saved locally in your browser.
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

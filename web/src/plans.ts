import { DEFAULTS } from './engine'
import type { FireInputs } from './engine'
import type { PlanDTO } from './api'

/** Distinct, CVD-friendly colors for overlaying profiles in compare mode. */
export const PLAN_COLORS = ['#34d399', '#60a5fa', '#fbbf24', '#f472b6', '#a78bfa', '#22d3ee']

export const uid = () => Math.random().toString(36).slice(2, 9)

/** A fresh plan (no inputs passed) leaves retireAge unset, so "Retire at" auto-tracks the recommended
 *  "don't go broke" age as the user edits. Cloning an existing plan copies its inputs (incl. retireAge). */
export const newPlan = (name: string, inputs?: FireInputs): PlanDTO => ({
  id: uid(),
  name,
  inputs: inputs ? { ...inputs } : { ...DEFAULTS },
})

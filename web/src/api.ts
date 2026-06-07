import type { FireInputs } from './engine'

// Profile persistence — kept entirely in the BROWSER (localStorage). No server / SQLite. The async
// signatures are preserved so callers don't care that storage is now synchronous and local.

export interface PlanDTO {
  id: string
  name: string
  inputs: FireInputs
}

const KEY = 'surefire.plans'

function read(): PlanDTO[] {
  try {
    const raw = localStorage.getItem(KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? (parsed as PlanDTO[]) : []
  } catch {
    return [] // corrupt/unavailable storage → start fresh rather than throw
  }
}

function write(plans: PlanDTO[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(plans))
  } catch {
    /* storage full or disabled (e.g. private mode) — silently ignore */
  }
}

export async function listPlans(): Promise<PlanDTO[]> {
  return read()
}

export async function savePlan(p: PlanDTO): Promise<void> {
  const plans = read()
  const next: PlanDTO = { id: p.id, name: p.name, inputs: p.inputs }
  const i = plans.findIndex((x) => x.id === p.id)
  if (i >= 0) plans[i] = next
  else plans.push(next)
  write(plans)
}

export async function deletePlan(id: string): Promise<void> {
  write(read().filter((x) => x.id !== id))
}

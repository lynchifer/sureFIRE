# sureFIRE web

React + TypeScript + Vite + Tailwind v4 + Apache ECharts front end for [sureFIRE](../README.md).

All financial math lives in the Kotlin engine (`../engine`); this app imports its built JS package
(`surefire-engine` → `file:../engine/build/dist/js/productionLibrary`). [`src/engine.ts`](src/engine.ts)
is the only boundary — it marshals the UI's input shape into the engine and copies results out, with
**no calculations** on the TypeScript side.

## Setup

```bash
# 1. Build the engine JS package this app depends on (once, and after any Kotlin change)
cd ../engine && ./gradlew --no-daemon jsNodeProductionLibraryDistribution

# 2. Install and run
npm install
npm run dev
```

After rebuilding the engine, restart the dev server; if Vite still serves the stale package, clear
its dependency cache: `rm -rf node_modules/.vite`.

## Commands

| Command | What it does |
|---|---|
| `npm run dev` | Vite dev server (HMR) on :5173 |
| `npm run build` | `tsc -b` type-check + production build to `dist/` |
| `npm run lint` | ESLint over the project |
| `npm run preview` | Serve the built `dist/` on :4173 |

## Structure

- `src/App.tsx` — app shell: plan tabs, inputs, results card, ECharts chart options (Fixed / Monte Carlo / Compare)
- `src/Chart.tsx` — thin ECharts wrapper (init/resize/dispose)
- `src/LifeEventsPanel.tsx` + `src/lifeEvents.ts` — life-event editing UI and (de)serialization
- `src/api.ts` — plan persistence (localStorage only; there is no server)
- `src/plans.ts`, `src/format.ts`, `src/useClearableNumber.ts` — helpers

Plans save to the browser. Deployed to GitHub Pages by [`web.yml`](../.github/workflows/web.yml).

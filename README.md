# sureFIRE

**When can I retire?** — a free FIRE calculator and net-worth forecaster.

**Live app: https://lynchifer.github.io/sureFIRE/**

Project your path to financial independence (leanFI · FI · fatFI), pick a retirement age (or let it
auto-track the recommended "don't go broke" age), and stress-test the plan against 1,000 fat-tailed
market simulations. Model life events — kids, a home (with a real amortizing mortgage), marriage,
sabbaticals — and see each one's marginal impact on your FI date.

**Your data never leaves your browser.** There is no backend and no account; plans persist in
localStorage. Don't take our word for it — this is the source.

## How it works

| Directory | What it is |
|---|---|
| [`engine/`](engine/) | Kotlin Multiplatform library — **all** financial math lives here (projections, tiers, drawdown strategies, Monte Carlo, life-event compilation). JVM target runs the test suite; JS/IR target emits the ESM package (+ TypeScript definitions) the web app consumes. |
| [`web/`](web/) | React + TypeScript + Vite + Tailwind + Apache ECharts front end. A thin boundary ([`web/src/engine.ts`](web/src/engine.ts)) marshals inputs to the engine — no math in TypeScript. |

Everything is computed in today's (real) dollars.

## Development

```bash
# Engine: run the math test suite (fast, JVM)
cd engine && ./gradlew --no-daemon jvmTest

# Engine: build the JS package the web app imports
cd engine && ./gradlew --no-daemon jsNodeProductionLibraryDistribution

# Web: install & run (after building the engine JS once)
cd web && npm install && npm run dev
```

After changing Kotlin code, rebuild the engine JS distribution and restart the Vite dev server
(clear `web/node_modules/.vite` if the old package is still cached).

## CI / deploy

- [`engine.yml`](.github/workflows/engine.yml) — runs the JVM test suite on engine changes.
- [`web.yml`](.github/workflows/web.yml) — builds the engine JS, lints + type-checks + builds the web,
  and deploys to GitHub Pages on `main`.

export const usd = (n: number, max = 0): string =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: max })

/** Compact currency, e.g. $1.44M, $940k. */
export const usdShort = (n: number): string => {
  const abs = Math.abs(n)
  if (abs >= 1e6) return `$${(n / 1e6).toFixed(2)}M`
  if (abs >= 1e3) return `$${Math.round(n / 1e3)}k`
  return usd(n)
}

export const pct = (frac: number, digits = 1): string => `${(frac * 100).toFixed(digits)}%`

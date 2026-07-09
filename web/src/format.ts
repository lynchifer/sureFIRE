const usd = (n: number, max = 0): string =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: max })

/** Compact currency, e.g. $1.44M, $940k, -$3.2M (sign before the $, not inside it). */
export const usdShort = (n: number): string => {
  const abs = Math.abs(n)
  const sign = n < 0 ? '-' : ''
  if (abs >= 1e6) return `${sign}$${(abs / 1e6).toFixed(2)}M`
  if (abs >= 1e3) return `${sign}$${Math.round(abs / 1e3)}k`
  return usd(n)
}

export const pct = (frac: number, digits = 1): string => `${(frac * 100).toFixed(digits)}%`

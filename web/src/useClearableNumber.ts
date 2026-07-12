import { useEffect, useRef, useState } from 'react'

/** Insert thousands separators into a numeric string, preserving sign and any decimal part. */
export function groupThousands(s: string): string {
  if (s === '' || s === '-' || s === '.') return s
  const neg = s.startsWith('-')
  const body = neg ? s.slice(1) : s
  const dot = body.indexOf('.')
  const int = dot >= 0 ? body.slice(0, dot) : body
  const rest = dot >= 0 ? body.slice(dot) : ''
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (neg ? '-' : '') + grouped + rest
}

/**
 * Text-buffer state for a number <input> that can be emptied. While editing, the field may hold
 * an empty/partial string; on commit, an empty/NaN value falls back to [emptyFallback]. Resyncs
 * when [value] changes from outside (e.g. switching profiles). When [group] is true the buffer is
 * displayed with thousands separators (commas). Shared by App's Field and the life-event Num input.
 */
export function useClearableNumber(value: number, onChange: (n: number) => void, emptyFallback: number, group = false) {
  const fmt = (s: string, grouped: boolean) => (grouped ? groupThousands(s) : s)
  const [text, setText] = useState(() => (Number.isFinite(value) ? fmt(String(value), group) : ''))
  const last = useRef(value)
  useEffect(() => {
    if (value !== last.current) {
      setText(Number.isFinite(value) ? (group ? groupThousands(String(value)) : String(value)) : '')
      last.current = value
    }
  }, [value, group])
  const onText = (raw: string) => {
    const cleaned = raw.replace(/,/g, '')
    setText(fmt(cleaned, group))
    const n = parseFloat(cleaned)
    const next = cleaned.trim() === '' || Number.isNaN(n) ? emptyFallback : n
    last.current = next
    onChange(next)
  }
  return { text, onText }
}

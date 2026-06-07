import { useEffect, useRef } from 'react'
import Plotly from 'plotly.js-dist-min'

export default function Chart({
  data,
  layout,
  className,
}: {
  data: unknown[]
  layout: Record<string, unknown>
  className?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  // Reserve the chart's height up front. With responsive:true Plotly fits the container, so if the
  // div mounts at 0 height (before the grid settles) the absolutely-positioned SVG overflows and
  // overlaps the panels below until a resize. An explicit height keeps the container sized at mount.
  const height = typeof layout.height === 'number' ? (layout.height as number) : undefined

  useEffect(() => {
    const el = ref.current
    if (el) void Plotly.react(el, data, layout, { displayModeBar: false, responsive: true })
  }, [data, layout])

  useEffect(() => {
    const el = ref.current
    return () => {
      if (el) Plotly.purge(el)
    }
  }, [])

  return <div ref={ref} className={className} style={{ height }} />
}

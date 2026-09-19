export const COLUMNS = 48
export const ROWS = 10

export interface TrackerFrame {
  sequence: number
  generated_at_ms: number
  frame: string
  tracking: boolean
  calibrated: boolean
  playing: boolean
  tempo_bpm: number
  song_seconds: number
}

export interface TextPatch {
  offset: number
  replacedLength: number
  content: string
}

export function normalizeFrame(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Tracker returned a non-text frame')
  const rows = value.replace(/\r/g, '').split('\n')
  if (rows.length !== ROWS) throw new Error(`Tracker frame has ${rows.length} rows; expected ${ROWS}`)
  return rows.map(row => {
    const ascii = [...row].map(char => {
      const code = char.charCodeAt(0)
      return code >= 32 && code <= 126 ? char : ' '
    }).join('')
    return ascii.slice(0, COLUMNS).padEnd(COLUMNS)
  }).join('\n')
}

export function diagnosticFrame(...lines: string[]): string {
  const rows = Array<string>(ROWS).fill(' '.repeat(COLUMNS))
  const visible = lines.slice(0, ROWS)
  const first = Math.floor((ROWS - visible.length) / 2)
  visible.forEach((line, index) => {
    const clipped = line.slice(0, COLUMNS)
    const left = Math.max(0, Math.floor((COLUMNS - clipped.length) / 2))
    rows[first + index] = `${' '.repeat(left)}${clipped}`.padEnd(COLUMNS).slice(0, COLUMNS)
  })
  return rows.join('\n')
}

export function diffText(previous: string, next: string): TextPatch | null {
  if (previous === next) return null
  let prefix = 0
  const limit = Math.min(previous.length, next.length)
  while (prefix < limit && previous[prefix] === next[prefix]) prefix += 1
  let suffix = 0
  while (
    suffix < previous.length - prefix &&
    suffix < next.length - prefix &&
    previous[previous.length - 1 - suffix] === next[next.length - 1 - suffix]
  ) suffix += 1
  return {
    offset: prefix,
    replacedLength: previous.length - prefix - suffix,
    content: next.slice(prefix, next.length - suffix),
  }
}

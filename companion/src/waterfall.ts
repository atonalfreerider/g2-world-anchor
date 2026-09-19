export const COLUMNS = 48
export const ROWS = 10

export interface WaterfallSettings {
  tempoBpm: number
  centerColumn: number
  laneSpacing: number
  strikeRow: number
  lookaheadBeats: number
  farScale: number
}

export const DEFAULT_SETTINGS: WaterfallSettings = {
  tempoBpm: 92,
  centerColumn: 24,
  laneSpacing: 8,
  strikeRow: 8,
  lookaheadBeats: 4,
  farScale: 0.48,
}

interface SongNote {
  label: 'G' | 'A' | 'B'
  lane: -1 | 0 | 1
  startBeat: number
  durationBeats: number
}

const CYCLE_BEATS = 16
const NOTES: SongNote[] = [
  { label: 'B', lane: 1, startBeat: 0, durationBeats: 1 },
  { label: 'A', lane: 0, startBeat: 1, durationBeats: 1 },
  { label: 'G', lane: -1, startBeat: 2, durationBeats: 2 },
  { label: 'B', lane: 1, startBeat: 4, durationBeats: 1 },
  { label: 'A', lane: 0, startBeat: 5, durationBeats: 1 },
  { label: 'G', lane: -1, startBeat: 6, durationBeats: 2 },
  { label: 'G', lane: -1, startBeat: 8, durationBeats: 0.5 },
  { label: 'G', lane: -1, startBeat: 8.5, durationBeats: 0.5 },
  { label: 'G', lane: -1, startBeat: 9, durationBeats: 0.5 },
  { label: 'G', lane: -1, startBeat: 9.5, durationBeats: 0.5 },
  { label: 'A', lane: 0, startBeat: 10, durationBeats: 0.5 },
  { label: 'A', lane: 0, startBeat: 10.5, durationBeats: 0.5 },
  { label: 'A', lane: 0, startBeat: 11, durationBeats: 0.5 },
  { label: 'A', lane: 0, startBeat: 11.5, durationBeats: 0.5 },
  { label: 'B', lane: 1, startBeat: 12, durationBeats: 1 },
  { label: 'A', lane: 0, startBeat: 13, durationBeats: 1 },
  { label: 'G', lane: -1, startBeat: 14, durationBeats: 2 },
]

export interface TextPatch {
  offset: number
  replacedLength: number
  content: string
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

export function renderWaterfall(
  elapsedSeconds: number,
  settings: WaterfallSettings,
  playing: boolean,
): string {
  const cells = Array.from({ length: ROWS }, () => Array<string>(COLUMNS).fill(' '))
  const secondsPerBeat = 60 / settings.tempoBpm
  const playheadBeat = Math.max(0, elapsedSeconds) / secondsPerBeat
  const cycle = Math.floor(playheadBeat / CYCLE_BEATS)
  const topRow = 1
  const trackRows = Math.max(1, settings.strikeRow - topRow)

  const plot = (x: number, y: number, value: string) => {
    if (x >= 0 && x < COLUMNS && y >= 0 && y < ROWS) cells[y][x] = value
  }
  const write = (x: number, y: number, value: string) => {
    for (let i = 0; i < value.length; i += 1) plot(x + i, y, value[i])
  }
  const rowForDelta = (beatDelta: number) => {
    const progress = 1 - Math.max(0, Math.min(settings.lookaheadBeats, beatDelta)) / settings.lookaheadBeats
    return topRow + progress * trackRows
  }
  const spacingAt = (row: number) => {
    const progress = (row - topRow) / trackRows
    return settings.laneSpacing * (settings.farScale + (1 - settings.farScale) * progress)
  }
  const laneColumn = (lane: number, row: number) =>
    Math.round(settings.centerColumn + lane * spacingAt(row))

  write(0, 0, `${playing ? '>' : '||'} HOT CROSS BUNS  ${settings.tempoBpm} BPM`.slice(0, COLUMNS))

  // Beat bands and converging lane boundaries establish simulated depth.
  const nextBeat = Math.ceil(playheadBeat)
  for (let beat = nextBeat; beat <= nextBeat + settings.lookaheadBeats; beat += 1) {
    const row = Math.round(rowForDelta(beat - playheadBeat))
    const spacing = spacingAt(row)
    const left = Math.round(settings.centerColumn - spacing * 1.5)
    const right = Math.round(settings.centerColumn + spacing * 1.5)
    for (let x = left; x <= right; x += 1) plot(x, row, '.')
  }
  for (let row = topRow; row <= settings.strikeRow; row += 1) {
    const spacing = spacingAt(row)
    for (const boundary of [-1.5, -0.5, 0.5, 1.5]) {
      plot(Math.round(settings.centerColumn + boundary * spacing), row, '|')
    }
  }

  const strikeSpacing = spacingAt(settings.strikeRow)
  const strikeLeft = Math.round(settings.centerColumn - strikeSpacing * 1.5)
  const strikeRight = Math.round(settings.centerColumn + strikeSpacing * 1.5)
  for (let x = strikeLeft; x <= strikeRight; x += 1) plot(x, settings.strikeRow, '=')

  for (const cycleOffset of [-1, 0, 1]) {
    const cycleStart = (cycle + cycleOffset) * CYCLE_BEATS
    for (const note of NOTES) {
      const untilStart = cycleStart + note.startBeat - playheadBeat
      const untilEnd = untilStart + note.durationBeats
      if (untilEnd <= 0 || untilStart >= settings.lookaheadBeats) continue

      const leadFloat = rowForDelta(Math.max(0, untilStart))
      const tailFloat = rowForDelta(Math.min(settings.lookaheadBeats, untilEnd))
      const leadRow = Math.round(leadFloat)
      const tailRow = Math.round(tailFloat)
      for (let row = Math.min(leadRow, tailRow); row <= Math.max(leadRow, tailRow); row += 1) {
        const column = laneColumn(note.lane, row)
        plot(column - 1, row, '#')
        plot(column, row, '#')
        plot(column + 1, row, '#')
      }

      const column = laneColumn(note.lane, leadRow)
      const phase = Math.floor((leadFloat - Math.floor(leadFloat)) * 4) & 3
      const wrappers = [['<', '>'], ['(', ')'], ['[', ']'], ['{', '}']] as const
      write(column - 1, leadRow, `${wrappers[phase][0]}${note.label}${wrappers[phase][1]}`)
    }
  }

  const labelRow = Math.min(ROWS - 1, settings.strikeRow + 1)
  plot(laneColumn(-1, settings.strikeRow), labelRow, 'G')
  plot(laneColumn(0, settings.strikeRow), labelRow, 'A')
  plot(laneColumn(1, settings.strikeRow), labelRow, 'B')

  return cells.map(row => row.join('')).join('\n')
}

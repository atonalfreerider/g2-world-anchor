export const FRAME_WIDTH = 288
export const FRAME_HEIGHT = 144

export const PATTERNS = [
  { id: 'anchor', label: 'Anchor cube' },
  { id: 'center', label: 'Optical center' },
  { id: 'grid', label: 'Geometry grid' },
  { id: 'pulse', label: 'Transport pulse' },
] as const

export type PatternId = (typeof PATTERNS)[number]['id']

function line(ctx: CanvasRenderingContext2D, points: Array<[number, number]>) {
  ctx.beginPath()
  points.forEach(([x, y], index) => index === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y))
  ctx.stroke()
}

function anchorCube(ctx: CanvasRenderingContext2D) {
  const front = [[98, 43], [190, 43], [190, 111], [98, 111]] as Array<[number, number]>
  const back = front.map(([x, y]) => [x + 22, y - 17] as [number, number])
  line(ctx, [...front, front[0]])
  line(ctx, [...back, back[0]])
  front.forEach((point, index) => line(ctx, [point, back[index]]))

  ctx.lineWidth = 1
  ctx.setLineDash([3, 3])
  line(ctx, [[144, 14], [144, 130]])
  line(ctx, [[52, 72], [236, 72]])
  ctx.setLineDash([])
}

function opticalCenter(ctx: CanvasRenderingContext2D) {
  ctx.lineWidth = 1
  line(ctx, [[0, 72], [288, 72]])
  line(ctx, [[144, 0], [144, 144]])
  ctx.lineWidth = 2
  for (const radius of [8, 20, 36, 54]) {
    ctx.beginPath()
    ctx.arc(144, 72, radius, 0, Math.PI * 2)
    ctx.stroke()
  }
  ctx.fillRect(142, 70, 5, 5)
}

function geometryGrid(ctx: CanvasRenderingContext2D) {
  ctx.lineWidth = 1
  ctx.strokeStyle = '#777'
  for (let x = 0; x <= 288; x += 24) line(ctx, [[x, 0], [x, 144]])
  for (let y = 0; y <= 144; y += 24) line(ctx, [[0, y], [288, y]])
  ctx.strokeStyle = '#fff'
  ctx.lineWidth = 2
  ctx.strokeRect(1, 1, 286, 142)
  line(ctx, [[132, 72], [156, 72]])
  line(ctx, [[144, 60], [144, 84]])
}

function transportPulse(ctx: CanvasRenderingContext2D, frame: number) {
  const bandWidth = FRAME_WIDTH / 16
  for (let band = 0; band < 16; band += 1) {
    const shade = Math.round((band / 15) * 255)
    ctx.fillStyle = `rgb(${shade},${shade},${shade})`
    ctx.fillRect(band * bandWidth, 0, Math.ceil(bandWidth), FRAME_HEIGHT)
  }
  const x = (frame * 17) % FRAME_WIDTH
  ctx.fillStyle = '#fff'
  ctx.fillRect(x, 0, 5, FRAME_HEIGHT)
  ctx.fillStyle = '#000'
  ctx.fillRect((x + 5) % FRAME_WIDTH, 0, 3, FRAME_HEIGHT)
}

export function renderPattern(pattern: PatternId, frame = 0): Uint8Array {
  const canvas = document.createElement('canvas')
  canvas.width = FRAME_WIDTH
  canvas.height = FRAME_HEIGHT
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('2D canvas is unavailable')

  ctx.fillStyle = '#000'
  ctx.fillRect(0, 0, FRAME_WIDTH, FRAME_HEIGHT)
  ctx.strokeStyle = '#fff'
  ctx.fillStyle = '#fff'
  ctx.lineWidth = 2
  ctx.lineJoin = 'round'
  ctx.lineCap = 'round'

  if (pattern === 'anchor') anchorCube(ctx)
  if (pattern === 'center') opticalCenter(ctx)
  if (pattern === 'grid') geometryGrid(ctx)
  if (pattern === 'pulse') transportPulse(ctx, frame)

  // SDK 0.0.12+ accepts packed raw gray4 and compresses it internally. This
  // avoids PNG encode/decode and gives the compressor ideal line-art input.
  // Two pixels share one byte, with the left pixel in the high nibble.
  const rgba = ctx.getImageData(0, 0, FRAME_WIDTH, FRAME_HEIGHT).data
  const gray4 = new Uint8Array((FRAME_WIDTH * FRAME_HEIGHT) / 2)
  for (let pixel = 0, packed = 0; pixel < FRAME_WIDTH * FRAME_HEIGHT; pixel += 2, packed += 1) {
    const first = pixel * 4
    const second = first + 4
    const high = ((rgba[first] * 77 + rgba[first + 1] * 150 + rgba[first + 2] * 29) >> 12) & 0x0f
    const low = ((rgba[second] * 77 + rgba[second + 1] * 150 + rgba[second + 2] * 29) >> 12) & 0x0f
    gray4[packed] = (high << 4) | low
  }
  return gray4
}

import {
  CreateStartUpPageContainer,
  ImageContainerProperty,
  ImageRawDataUpdate,
  OsEventTypeList,
  StartUpPageCreateResult,
  TextContainerProperty,
  TextContainerUpgrade,
  waitForEvenAppBridge,
} from '@evenrealities/even_hub_sdk'
import { FRAME_HEIGHT, FRAME_WIDTH, PATTERNS, type PatternId, renderPattern } from './patterns'

const statusElement = document.querySelector<HTMLElement>('#status')!
const detailElement = document.querySelector<HTMLElement>('#detail')!
const dotElement = document.querySelector<HTMLElement>('#statusDot')!
const patternsElement = document.querySelector<HTMLElement>('#patterns')!
const logElement = document.querySelector<HTMLElement>('#log')!

function log(message: string) {
  const timestamp = new Date().toLocaleTimeString()
  logElement.textContent = `${timestamp}  ${message}\n${logElement.textContent ?? ''}`.trim().split('\n').slice(0, 8).join('\n')
}

function phoneStatus(title: string, detail: string, state: 'waiting' | 'ready' | 'error' = 'waiting') {
  statusElement.textContent = title
  detailElement.textContent = detail
  dotElement.className = `dot ${state === 'waiting' ? '' : state}`
}

let activePattern: PatternId = 'anchor'
let controlsEnabled = false
for (const pattern of PATTERNS) {
  const button = document.createElement('button')
  button.type = 'button'
  button.textContent = pattern.label
  button.dataset.pattern = pattern.id
  button.disabled = true
  button.addEventListener('click', () => {
    if (controlsEnabled) selectPattern(pattern.id).catch(reportError)
  })
  patternsElement.append(button)
}

function updateButtons() {
  for (const button of Array.from(patternsElement.querySelectorAll<HTMLButtonElement>('button'))) {
    button.disabled = !controlsEnabled
    button.classList.toggle('active', button.dataset.pattern === activePattern)
  }
}

const bridge = await waitForEvenAppBridge()

const eventLayer = new TextContainerProperty({
  xPosition: 0,
  yPosition: 0,
  width: 576,
  height: 288,
  borderWidth: 0,
  borderColor: 0,
  paddingLength: 0,
  containerID: 1,
  containerName: 'events',
  content: ' ',
  isEventCapture: 1,
})

const image = new ImageContainerProperty({
  xPosition: (576 - FRAME_WIDTH) / 2,
  yPosition: (288 - FRAME_HEIGHT) / 2,
  width: FRAME_WIDTH,
  height: FRAME_HEIGHT,
  containerID: 2,
  containerName: 'anchor-frame',
})

const statusLine = new TextContainerProperty({
  xPosition: 0,
  yPosition: 252,
  width: 576,
  height: 36,
  borderWidth: 0,
  borderColor: 5,
  paddingLength: 2,
  containerID: 3,
  containerName: 'status',
  content: 'World Anchor Lab · starting…',
  isEventCapture: 0,
})

const page = new CreateStartUpPageContainer({
  containerTotalNum: 3,
  textObject: [eventLayer, statusLine],
  imageObject: [image],
})

const created = await bridge.createStartUpPageContainer(page)
if (created !== StartUpPageCreateResult.success) {
  throw new Error(`Glasses page creation failed (${created})`)
}

let renderTail: Promise<void> = Promise.resolve()
let lastTransferMs = 0
let pulseGeneration = 0

async function setGlassesStatus(content: string) {
  await bridge.textContainerUpgrade(new TextContainerUpgrade({
    containerID: 3,
    containerName: 'status',
    content,
  }))
}

async function pushFrame(bytes: Uint8Array) {
  renderTail = renderTail.catch(() => undefined).then(async () => {
    const started = performance.now()
    const result = await bridge.updateImageRawData(new ImageRawDataUpdate({
      containerID: 2,
      containerName: 'anchor-frame',
      imageData: bytes,
    }))
    lastTransferMs = performance.now() - started
    if (result !== 'success') throw new Error(`Image transfer failed (${result})`)
  })
  await renderTail
}

async function showStaticPattern(pattern: PatternId) {
  const bytes = renderPattern(pattern)
  await pushFrame(bytes)
  const label = PATTERNS.find(value => value.id === pattern)?.label ?? pattern
  const detail = `${FRAME_WIDTH}×${FRAME_HEIGHT} gray4/LZ4 · ${lastTransferMs.toFixed(0)} ms transfer`
  await setGlassesStatus(`${label} · tap next · double-tap exit`)
  phoneStatus(label, detail, 'ready')
  log(`${label}: ${bytes.byteLength} bytes, ${lastTransferMs.toFixed(0)} ms`)
}

async function runPulse(generation: number) {
  let frame = 0
  const started = performance.now()
  while (generation === pulseGeneration && activePattern === 'pulse') {
    const bytes = renderPattern('pulse', frame)
    await pushFrame(bytes)
    frame += 1
    const elapsedSeconds = Math.max((performance.now() - started) / 1000, 0.001)
    const completedFps = frame / elapsedSeconds
    if (frame === 1 || frame % 5 === 0) {
      await setGlassesStatus(`Pulse · ${completedFps.toFixed(1)} completed fps · tap next`)
      phoneStatus('Transport pulse', `${completedFps.toFixed(1)} completed frames/s · ${lastTransferMs.toFixed(0)} ms last transfer`, 'ready')
    }
    await new Promise(resolve => window.setTimeout(resolve, 60))
  }
}

async function selectPattern(pattern: PatternId) {
  pulseGeneration += 1
  activePattern = pattern
  updateButtons()
  if (pattern === 'pulse') {
    phoneStatus('Transport pulse', 'Measuring completed image transfers…')
    await runPulse(pulseGeneration)
  } else {
    await showStaticPattern(pattern)
  }
}

function reportError(error: unknown) {
  const message = error instanceof Error ? error.message : String(error)
  phoneStatus('Companion error', message, 'error')
  log(`ERROR: ${message}`)
  setGlassesStatus(`Error · ${message}`).catch(() => undefined)
}

function eventTypeOf(envelope?: { eventType?: OsEventTypeList }): OsEventTypeList | null {
  if (!envelope) return null
  return envelope.eventType ?? OsEventTypeList.CLICK_EVENT
}

let cleanedUp = false
const unsubscribe = bridge.onEvenHubEvent(event => {
  const sysType = eventTypeOf(event.sysEvent)
  const textType = eventTypeOf(event.textEvent)

  if (sysType === OsEventTypeList.DOUBLE_CLICK_EVENT || textType === OsEventTypeList.DOUBLE_CLICK_EVENT) {
    cleanup()
    bridge.shutDownPageContainer(1)
    return
  }

  if (sysType === OsEventTypeList.CLICK_EVENT || textType === OsEventTypeList.CLICK_EVENT) {
    const index = PATTERNS.findIndex(value => value.id === activePattern)
    selectPattern(PATTERNS[(index + 1) % PATTERNS.length].id).catch(reportError)
    return
  }

  if (sysType === OsEventTypeList.SYSTEM_EXIT_EVENT || sysType === OsEventTypeList.ABNORMAL_EXIT_EVENT) cleanup()
})

function cleanup() {
  if (cleanedUp) return
  cleanedUp = true
  pulseGeneration += 1
  unsubscribe()
}

window.addEventListener('beforeunload', cleanup)

controlsEnabled = true
updateButtons()
await selectPattern('anchor')

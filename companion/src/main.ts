import {
  CreateStartUpPageContainer,
  OsEventTypeList,
  StartUpPageCreateResult,
  TextContainerProperty,
  TextContainerUpgrade,
  waitForEvenAppBridge,
} from '@evenrealities/even_hub_sdk'
import {
  DEFAULT_SETTINGS,
  diffText,
  renderWaterfall,
  type WaterfallSettings,
} from './waterfall'

const statusElement = document.querySelector<HTMLElement>('#status')!
const detailElement = document.querySelector<HTMLElement>('#detail')!
const dotElement = document.querySelector<HTMLElement>('#statusDot')!
const previewElement = document.querySelector<HTMLElement>('#preview')!
const calibrationElement = document.querySelector<HTMLElement>('#calibration')!
const playbackButton = document.querySelector<HTMLButtonElement>('[data-action="toggle"]')!
const logElement = document.querySelector<HTMLElement>('#log')!

function log(message: string) {
  const timestamp = new Date().toLocaleTimeString()
  logElement.textContent = `${timestamp}  ${message}\n${logElement.textContent ?? ''}`
    .trim().split('\n').slice(0, 8).join('\n')
}

function phoneStatus(title: string, detail: string, state: 'waiting' | 'ready' | 'error' = 'waiting') {
  statusElement.textContent = title
  detailElement.textContent = detail
  dotElement.className = `dot ${state === 'waiting' ? '' : state}`
}

let settings: WaterfallSettings = { ...DEFAULT_SETTINGS }
let playing = true
let accumulatedBeat = 0
let playbackStartedAt = performance.now()
let completedFps = 0
let lastTransferMs = 0
let completedInWindow = 0
let updateWindowStartedAt = performance.now()

function currentBeat(now = performance.now()) {
  if (!playing) return accumulatedBeat
  return accumulatedBeat + (now - playbackStartedAt) / 1000 * settings.tempoBpm / 60
}

function elapsedForRender(now = performance.now()) {
  return currentBeat(now) * 60 / settings.tempoBpm
}

function refreshPhone(frame: string) {
  previewElement.textContent = frame
  calibrationElement.textContent =
    `center ${settings.centerColumn} · lanes ${settings.laneSpacing} cols · ` +
    `strike row ${settings.strikeRow} · depth ${settings.lookaheadBeats} beats · ${settings.tempoBpm} BPM`
  playbackButton.textContent = playing ? 'Pause' : 'Play'
  phoneStatus(
    playing ? 'Waterfall playing' : 'Waterfall paused',
    `${completedFps.toFixed(1)} completed updates/s · ${lastTransferMs.toFixed(0)} ms last update`,
    'ready',
  )
}

const initialFrame = renderWaterfall(0, settings, playing)
refreshPhone(initialFrame)

const bridge = await waitForEvenAppBridge()
const waterfall = new TextContainerProperty({
  xPosition: 0,
  yPosition: 0,
  width: 576,
  height: 288,
  borderWidth: 0,
  borderColor: 0,
  paddingLength: 0,
  containerID: 1,
  containerName: 'waterfall',
  content: initialFrame,
  textColor: 4,
  isEventCapture: 1,
})
const page = new CreateStartUpPageContainer({
  containerTotalNum: 1,
  textObject: [waterfall],
})

const created = await bridge.createStartUpPageContainer(page)
if (created !== StartUpPageCreateResult.success) {
  throw new Error(`Glasses page creation failed (${created})`)
}

let desiredFrame = initialFrame
let lastSentFrame = initialFrame
let pumpActive = false
let cleanedUp = false
let animationTimer = 0

function reportError(error: unknown) {
  const message = error instanceof Error ? error.message : String(error)
  phoneStatus('Companion error', message, 'error')
  log(`ERROR: ${message}`)
}

function queueFrame(frame: string) {
  desiredFrame = frame
  if (!pumpActive && !cleanedUp) void pumpFrames()
}

async function pumpFrames() {
  pumpActive = true
  try {
    while (!cleanedUp && desiredFrame !== lastSentFrame) {
      const target = desiredFrame
      const patch = diffText(lastSentFrame, target)
      if (!patch) break
      const useDelta = patch.content.length < target.length * 0.65
      const started = performance.now()
      const ok = await bridge.textContainerUpgrade(new TextContainerUpgrade({
        containerID: 1,
        containerName: 'waterfall',
        contentOffset: useDelta ? patch.offset : 0,
        contentLength: useDelta ? patch.replacedLength : 0,
        content: useDelta ? patch.content : target,
      }))
      lastTransferMs = performance.now() - started
      if (!ok) throw new Error('Text update rejected by Even Hub')
      lastSentFrame = target
      completedInWindow += 1
      const now = performance.now()
      const windowSeconds = (now - updateWindowStartedAt) / 1000
      if (windowSeconds >= 1) {
        completedFps = completedInWindow / windowSeconds
        completedInWindow = 0
        updateWindowStartedAt = now
        log(`${useDelta ? 'delta' : 'full'} ${useDelta ? patch.content.length : target.length} chars · ${lastTransferMs.toFixed(0)} ms`)
      }
    }
  } catch (error) {
    reportError(error)
    // Avoid a hot retry loop if the Even app or glasses temporarily rejects
    // updates. The desired frame remains conflated and is retried after backoff.
    await new Promise(resolve => window.setTimeout(resolve, 250))
  } finally {
    pumpActive = false
    if (!cleanedUp && desiredFrame !== lastSentFrame) queueFrame(desiredFrame)
  }
}

function renderNow(now = performance.now()) {
  const frame = renderWaterfall(elapsedForRender(now), settings, playing)
  refreshPhone(frame)
  queueFrame(frame)
}

function freezeBeat(now = performance.now()) {
  accumulatedBeat = currentBeat(now)
  playbackStartedAt = now
}

function togglePlayback() {
  const now = performance.now()
  freezeBeat(now)
  playing = !playing
  playbackStartedAt = now
  renderNow(now)
}

function restart() {
  accumulatedBeat = 0
  playbackStartedAt = performance.now()
  renderNow(playbackStartedAt)
}

function updateTempo(delta: number) {
  const now = performance.now()
  freezeBeat(now)
  settings = { ...settings, tempoBpm: Math.max(40, Math.min(180, settings.tempoBpm + delta)) }
  playbackStartedAt = now
  renderNow(now)
}

function adjust(action: string) {
  if (action === 'toggle') togglePlayback()
  else if (action === 'restart') restart()
  else if (action === 'tempo-down') updateTempo(-4)
  else if (action === 'tempo-up') updateTempo(4)
  else if (action === 'left') settings = { ...settings, centerColumn: Math.max(16, settings.centerColumn - 1) }
  else if (action === 'right') settings = { ...settings, centerColumn: Math.min(31, settings.centerColumn + 1) }
  else if (action === 'width-down') settings = { ...settings, laneSpacing: Math.max(5, settings.laneSpacing - 1) }
  else if (action === 'width-up') settings = { ...settings, laneSpacing: Math.min(10, settings.laneSpacing + 1) }
  else if (action === 'strike-up') settings = { ...settings, strikeRow: Math.max(5, settings.strikeRow - 1) }
  else if (action === 'strike-down') settings = { ...settings, strikeRow: Math.min(8, settings.strikeRow + 1) }
  else if (action === 'depth-down') settings = { ...settings, lookaheadBeats: Math.max(2, settings.lookaheadBeats - 1) }
  else if (action === 'depth-up') settings = { ...settings, lookaheadBeats: Math.min(8, settings.lookaheadBeats + 1) }
  renderNow()
}

for (const button of Array.from(document.querySelectorAll<HTMLButtonElement>('button[data-action]'))) {
  button.addEventListener('click', () => adjust(button.dataset.action ?? ''))
}

function eventTypeOf(envelope?: { eventType?: OsEventTypeList }): OsEventTypeList | null {
  if (!envelope) return null
  return envelope.eventType ?? OsEventTypeList.CLICK_EVENT
}

const unsubscribe = bridge.onEvenHubEvent(event => {
  const sysType = eventTypeOf(event.sysEvent)
  const textType = eventTypeOf(event.textEvent)
  if (sysType === OsEventTypeList.DOUBLE_CLICK_EVENT || textType === OsEventTypeList.DOUBLE_CLICK_EVENT) {
    cleanup()
    bridge.shutDownPageContainer(1)
  } else if (sysType === OsEventTypeList.CLICK_EVENT || textType === OsEventTypeList.CLICK_EVENT) {
    togglePlayback()
  } else if (sysType === OsEventTypeList.SYSTEM_EXIT_EVENT || sysType === OsEventTypeList.ABNORMAL_EXIT_EVENT) {
    cleanup()
  }
})

animationTimer = window.setInterval(() => renderNow(), 33)

function cleanup() {
  if (cleanedUp) return
  cleanedUp = true
  window.clearInterval(animationTimer)
  unsubscribe()
}

window.addEventListener('beforeunload', cleanup)
log('Native text waterfall ready · tap glasses to pause/play')

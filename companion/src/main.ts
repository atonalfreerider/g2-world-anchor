import {
  CreateStartUpPageContainer,
  OsEventTypeList,
  StartUpPageCreateResult,
  TextContainerProperty,
  TextContainerUpgrade,
  waitForEvenAppBridge,
} from '@evenrealities/even_hub_sdk'
import { diagnosticFrame, diffText, normalizeFrame, type TrackerFrame } from './frame'

const TRACKER_ORIGIN = 'http://127.0.0.1:8080'
const POLL_INTERVAL_MS = 20
const OFFLINE_AFTER_MS = 750

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

const waitingFrame = diagnosticFrame('OPEN PIANO TRACKER', 'CALIBRATE STRIKE LINE', 'THEN RETURN TO EVEN HUB')
previewElement.textContent = waitingFrame
phoneStatus('Waiting for Piano Tracker…', 'Open the tracker APK and grant camera access.')

const evenBridge = await waitForEvenAppBridge()
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
  content: waitingFrame,
  textColor: 4,
  isEventCapture: 1,
})
const page = new CreateStartUpPageContainer({
  containerTotalNum: 1,
  textObject: [waterfall],
})

const created = await evenBridge.createStartUpPageContainer(page)
if (created !== StartUpPageCreateResult.success) throw new Error(`Glasses page creation failed (${created})`)

let desiredFrame = waitingFrame
let lastSentFrame = waitingFrame
let lastSequence = -1
let lastTrackerAt = 0
let pumpActive = false
let cleanedUp = false
let pollTimer = 0
let completedFps = 0
let lastTransferMs = 0
let completedInWindow = 0
let updateWindowStartedAt = performance.now()
let offlineFrameShown = true

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
      const ok = await evenBridge.textContainerUpgrade(new TextContainerUpgrade({
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
    await new Promise(resolve => window.setTimeout(resolve, 250))
  } finally {
    pumpActive = false
    if (!cleanedUp && desiredFrame !== lastSentFrame) queueFrame(desiredFrame)
  }
}

function showTracker(frame: TrackerFrame) {
  const normalized = normalizeFrame(frame.frame)
  previewElement.textContent = normalized
  playbackButton.textContent = frame.playing ? 'Pause' : 'Play'
  calibrationElement.textContent = frame.calibrated
    ? `${frame.tracking ? 'face tracked' : 'tracking stale'} · ${frame.tempo_bpm} BPM · ${frame.song_seconds.toFixed(1)} s`
    : 'Not calibrated — set the strike line in Piano Tracker.'
  const age = Math.max(0, Date.now() - frame.generated_at_ms)
  phoneStatus(
    frame.calibrated ? (frame.tracking ? 'Live tracker connected' : 'Tracker waiting for face') : 'Tracker needs calibration',
    `${completedFps.toFixed(1)} G2 updates/s · ${lastTransferMs.toFixed(0)} ms transfer · frame age ${age} ms`,
    frame.tracking && frame.calibrated ? 'ready' : 'waiting',
  )
  offlineFrameShown = false
  queueFrame(normalized)
}

async function pollTracker() {
  if (cleanedUp) return
  const started = performance.now()
  try {
    const response = await fetch(`${TRACKER_ORIGIN}/frame?t=${Date.now()}`, { cache: 'no-store' })
    if (!response.ok) throw new Error(`tracker HTTP ${response.status}`)
    const frame = await response.json() as TrackerFrame
    if (!Number.isFinite(frame.sequence)) throw new Error('tracker response is invalid')
    lastTrackerAt = performance.now()
    if (frame.sequence !== lastSequence) {
      lastSequence = frame.sequence
      showTracker(frame)
    }
  } catch (error) {
    const elapsed = performance.now() - lastTrackerAt
    if (lastTrackerAt === 0 || elapsed >= OFFLINE_AFTER_MS) {
      phoneStatus('Piano Tracker not reachable', 'Open the Piano Tracker APK; this eHPK does not simulate tracking.', 'error')
      calibrationElement.textContent = 'The local bridge at 127.0.0.1:8080 is offline.'
      previewElement.textContent = waitingFrame
      if (!offlineFrameShown) {
        queueFrame(waitingFrame)
        offlineFrameShown = true
        log(error instanceof Error ? error.message : String(error))
      }
    }
  } finally {
    const wait = Math.max(0, POLL_INTERVAL_MS - (performance.now() - started))
    pollTimer = window.setTimeout(() => void pollTracker(), wait)
  }
}

async function sendControl(action: string) {
  try {
    const response = await fetch(`${TRACKER_ORIGIN}/control`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: action,
    })
    if (!response.ok) throw new Error(`control ${action} failed (${response.status})`)
  } catch (error) {
    reportError(error)
  }
}

for (const button of Array.from(document.querySelectorAll<HTMLButtonElement>('button[data-action]'))) {
  button.addEventListener('click', () => void sendControl(button.dataset.action ?? ''))
}

function eventTypeOf(envelope?: { eventType?: OsEventTypeList }): OsEventTypeList | null {
  if (!envelope) return null
  return envelope.eventType ?? OsEventTypeList.CLICK_EVENT
}

const unsubscribe = evenBridge.onEvenHubEvent(event => {
  const sysType = eventTypeOf(event.sysEvent)
  const textType = eventTypeOf(event.textEvent)
  if (sysType === OsEventTypeList.DOUBLE_CLICK_EVENT || textType === OsEventTypeList.DOUBLE_CLICK_EVENT) {
    cleanup()
    evenBridge.shutDownPageContainer(1)
  } else if (sysType === OsEventTypeList.CLICK_EVENT || textType === OsEventTypeList.CLICK_EVENT) {
    void sendControl('toggle')
  } else if (sysType === OsEventTypeList.SYSTEM_EXIT_EVENT || sysType === OsEventTypeList.ABNORMAL_EXIT_EVENT) {
    cleanup()
  }
})

function cleanup() {
  if (cleanedUp) return
  cleanedUp = true
  window.clearTimeout(pollTimer)
  unsubscribe()
}

window.addEventListener('beforeunload', cleanup)
void pollTracker()
log('Even Hub owns G2 · waiting for the phone-local tracker')

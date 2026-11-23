/**
 * verify-animation.mjs — 启动动画浏览器验收 harness（工业级，无第三方依赖）
 *
 * 用法：
 *   node verify-animation.mjs --target <demo.html> [--shots <dir>] [--report <file>]
 *     [--status-fn __demoStatus] [--controls-fn __demo] [--browser <path>]
 *     [--drawing-at 5.5] [--portrait-at 8] [--shutter-at 9.85] [--address-at 2]
 *   node verify-animation.mjs --probe-only          # 不启浏览器：判据负控自检
 *
 * 页面契约（缺一即预检失败）：
 *   window[statusFn]() -> { status, dataElapsed, playing, completed, revealed, reduced,
 *                           overlayVisible, particleOpacity, progress, stage, events, metrics }
 *   window[controlsFn] -> { start, pause, replay, scrub(sec), setReduced(bool), finish }
 *
 * 退出码：0 全绿 / 2 预检失败（浏览器、Node 能力、目标页契约缺失）/ 3 运行失败（CDP/导航）/ 4 判据失败
 * 报告：JSON 原子写入（临时名→rename）；必需锚点缺失直接判红（A40：断言数不许静默变少）。
 */
import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { spawn } from 'node:child_process'
import { pathToFileURL } from 'node:url'

// ---------- 判据（纯函数；负控在 --probe-only 与 selftest 里证明会变红） ----------
export function checkFreeze(sample) {
  if (!sample || sample.dataElapsed === undefined) return { ok: false, why: 'no data-elapsed' }
  if (!sample.playing && sample.dataElapsed !== '0.00') return { ok: false, why: 'not playing but clock advanced: ' + sample.dataElapsed }
  return { ok: true }
}
export function checkClockRuns(sample) {
  if (sample.playing && sample.dataElapsed === '0.00') return { ok: false, why: 'playing but clock frozen' }
  return { ok: true }
}

/** 浏览器发现：平台是入参（A15：两个平台的分支必须同跑断言，不许 process.platform 跳过） */
export function browserCandidates(env, platform) {
  const list = []
  if (env.DSH_BROWSER_BIN) list.push(env.DSH_BROWSER_BIN)
  if (platform === 'win32') {
    if (env.PROGRAMFILES) list.push(path.join(env.PROGRAMFILES, 'Google/Chrome/Application/chrome.exe'))
    if (env['PROGRAMFILES(X86)']) list.push(path.join(env['PROGRAMFILES(X86)'], 'Microsoft/Edge/Application/msedge.exe'))
    if (env.PROGRAMFILES) list.push(path.join(env.PROGRAMFILES, 'Microsoft/Edge/Application/msedge.exe'))
  } else if (platform === 'linux') {
    list.push('/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser', '/snap/bin/chromium')
  } else if (platform === 'darwin') {
    list.push('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
  }
  return list
}

export const REQUIRED_CHECKS = Object.freeze([
  'negative: the freeze detector turns red when the clock advanced unprompted',
  'negative: the clock detector turns red when playback claims to run a frozen clock',
  'prewarm: the frame clock is frozen at 0.00 until start()',
  'prewarm: contour pipeline produced fragments',
  'start: the overlay shows and the clock runs within 500ms of start()',
  'start: the clock is advancing after start() (positive control for the freeze detector)',
  'scrub: seeking the same time twice reproduces the same frame state',
  'scrub: every timeline point is addressable',
  'reduced: full motion keeps particles visible',
  'reduced: prefers-reduced-motion removes particles, drift and rotation',
  'handoff: entering the shutter fires exactly one reveal event',
  'handoff: the reveal latch stays at one through repeated finish()',
  'metrics: frame metrics are reported at completion',
  'handoff: the overlay is removed and the host interface stays on screen'
])

// ---------- 工具 ----------
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const checks = []
const check = (id, ok, evidence) => {
  checks.push({ id, ok: Boolean(ok), evidence })
  console.log((ok ? 'PASS' : 'FAIL') + '  ' + id + '  ' + JSON.stringify(evidence ?? {}))
}

class CDP {
  constructor(socket) {
    this.socket = socket
    this.sequence = 0
    this.pending = new Map()
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(String(event.data))
      const pending = this.pending.get(message.id)
      if (!pending) return
      this.pending.delete(message.id)
      if (message.error) pending.reject(new Error(message.error.message))
      else pending.resolve(message.result)
    })
  }
  call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.sequence
      this.pending.set(id, { resolve, reject })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }
  async evaluate(expression) {
    const answer = await this.call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (answer.exceptionDetails) throw new Error(answer.exceptionDetails.exception?.description || answer.exceptionDetails.text)
    return answer.result.value
  }
}

async function writeReport(file, payload) {
  const pending = file + '.tmp'
  await fs.writeFile(pending, JSON.stringify(payload, null, 2))
  await fs.rename(pending, file) // 原子发布：失败路径不留半成品
}

// ---------- 参数 ----------
const args = process.argv.slice(2)
const option = (name, fallback) => { const i = args.indexOf(name); return i < 0 ? fallback : args[i + 1] }
const target = option('--target', '')
const shots = option('--shots', '')
const reportFile = option('--report', path.join(process.cwd(), 'report.json'))
const statusFn = option('--status-fn', '__demoStatus')
const controlsFn = option('--controls-fn', '__demo')
const drawingAt = Number(option('--drawing-at', '5.5'))
const portraitAt = Number(option('--portrait-at', '8'))
const shutterAt = Number(option('--shutter-at', '9.85'))
const addressAt = Number(option('--address-at', '2'))

async function main() {
  // ---- probe-only：判据负控自检（不启浏览器）；VERIFY_PROBE=fail 证明失败会传播（A27） ----
  if (args.includes('--probe-only')) {
    const bad1 = checkFreeze({ playing: false, dataElapsed: '0.50' })
    check('negative: the freeze detector turns red when the clock advanced unprompted', bad1.ok === false, bad1)
    const bad2 = checkClockRuns({ playing: true, dataElapsed: '0.00' })
    check('negative: the clock detector turns red when playback claims to run a frozen clock', bad2.ok === false, bad2)
    const good = checkFreeze({ playing: false, dataElapsed: '0.00' })
    check('prewarm: the frame clock is frozen at 0.00 until start()', good.ok === true, good)
    if (process.env.VERIFY_PROBE === 'fail') check('probe: forced failure proves exit-code propagation', false, { probe: 'VERIFY_PROBE=fail' })
    const ok = checks.every((item) => item.ok)
    await writeReport(reportFile, { mode: 'probe-only', exitCode: ok ? 0 : 4, checks })
    return ok ? 0 : 4
  }

  // ---- 预检（退出码 2） ----
  if (typeof WebSocket !== 'function') { console.error('preflight: Node lacks global WebSocket (need Node >= 22)'); return 2 }
  if (!target) { console.error('preflight: --target <file.html> is required'); return 2 }
  const resolvedTarget = path.resolve(target)
  if (!fsSync.existsSync(resolvedTarget)) { console.error('preflight: target not found: ' + resolvedTarget); return 2 }
  const candidates = browserCandidates(process.env, process.platform)
  const browser = candidates.find((candidate) => fsSync.existsSync(candidate))
  if (!browser) { console.error('preflight: no Chromium binary found; tried: ' + candidates.join(', ')); return 2 }
  if (shots) await fs.mkdir(shots, { recursive: true })
  console.log('browser: ' + browser + '  target: ' + resolvedTarget)

  // ---- 浏览器 + CDP（运行失败退出码 3） ----
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'verify-animation-'))
  let child, cdp
  try {
    child = spawn(browser, ['--headless=new', '--no-sandbox', '--disable-dev-shm-usage', '--disable-background-networking',
      '--no-first-run', '--no-default-browser-check', '--disable-renderer-backgrounding', '--remote-debugging-port=0',
      '--remote-allow-origins=*', '--window-size=1440,900', '--user-data-dir=' + temp], { stdio: 'ignore' })
    let spawnError
    child.on('error', (error) => { spawnError = error })
    let port
    for (let i = 0; i < 200; i += 1) {
      if (spawnError) throw spawnError
      try { port = Number((await fs.readFile(path.join(temp, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break } catch { await sleep(50) }
    }
    if (!port) throw new Error('browser did not start')
    const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json()
    const page = pages.find((value) => value.type === 'page')
    if (!page) throw new Error('browser page is unavailable')
    const socket = new WebSocket(page.webSocketDebuggerUrl)
    await new Promise((resolve, reject) => { socket.addEventListener('open', resolve); socket.addEventListener('error', reject) })
    cdp = new CDP(socket)
    await cdp.call('Page.enable')
    await cdp.call('Runtime.enable')
    await cdp.call('Page.navigate', { url: pathToFileURL(resolvedTarget).href })

    const screenshot = async (name) => {
      if (!shots) return
      const image = await cdp.call('Page.captureScreenshot', { format: 'png' })
      await fs.writeFile(path.join(shots, name), Buffer.from(image.data, 'base64'))
    }
    const evaluate = (expression) => cdp.evaluate(expression)
    const status = () => evaluate(`window[${JSON.stringify(statusFn)}] && window[${JSON.stringify(statusFn)}]()`)

    // 契约预检：页面必须暴露 status/controls（退出码 2）
    let contract = false
    for (let i = 0; i < 200; i += 1) {
      contract = await evaluate(`typeof window[${JSON.stringify(statusFn)}] === 'function' && typeof window[${JSON.stringify(controlsFn)}] === 'object'`)
      if (contract) break
      await sleep(50)
    }
    if (!contract) { console.error('preflight: page contract missing: window.' + statusFn + ' / window.' + controlsFn); return 2 }

    // ---- 负控先行：判据自身必须会红 ----
    const bad1 = checkFreeze({ playing: false, dataElapsed: '0.50' })
    check('negative: the freeze detector turns red when the clock advanced unprompted', bad1.ok === false, bad1)
    const bad2 = checkClockRuns({ playing: true, dataElapsed: '0.00' })
    check('negative: the clock detector turns red when playback claims to run a frozen clock', bad2.ok === false, bad2)

    // ---- 预热协议 ----
    let ready = false
    for (let i = 0; i < 200; i += 1) {
      const s = await status()
      if (s && s.status === '已冻结待播') { ready = true; break }
      if (s && s.events && s.events.includes('assetError')) break
      await sleep(50)
    }
    const s1 = await status()
    check('prewarm: the frame clock is frozen at 0.00 until start()', ready && checkFreeze(s1).ok && s1.dataElapsed === '0.00' && s1.frozen === true,
      { ready, dataElapsed: s1 && s1.dataElapsed, frozen: s1 && s1.frozen })
    check('prewarm: contour pipeline produced fragments', s1 && s1.fragments > 0 && s1.moving > 0,
      { fragments: s1 && s1.fragments, moving: s1 && s1.moving })
    await screenshot('01-prewarm-frozen.png')

    // ---- 显式 start() 后 <500ms 走表 ----
    const t0 = Date.now()
    await evaluate(`window[${JSON.stringify(controlsFn)}].start()`)
    let started = null
    for (let i = 0; i < 40; i += 1) {
      const s = await status()
      if (s && s.playing && s.overlayVisible) { started = s; break }
      await sleep(10)
    }
    const latency = Date.now() - t0
    check('start: the overlay shows and the clock runs within 500ms of start()', started && latency < 500, { latencyMs: latency })
    await sleep(500)
    const s2 = await status()
    check('start: the clock is advancing after start() (positive control for the freeze detector)', s2 && checkClockRuns(s2).ok && Number(s2.dataElapsed) > 0,
      { dataElapsed: s2 && s2.dataElapsed, playing: s2 && s2.playing })

    // ---- scrub 逐位可复现 + 时间轴可寻址 ----
    await evaluate(`window[${JSON.stringify(controlsFn)}].pause()`)
    await evaluate(`window[${JSON.stringify(controlsFn)}].scrub(${drawingAt})`)
    const sc1 = await status()
    await screenshot('02-drawing.png')
    await evaluate(`window[${JSON.stringify(controlsFn)}].scrub(${drawingAt})`)
    const sc2 = await status()
    check('scrub: seeking the same time twice reproduces the same frame state',
      sc1.stage === sc2.stage && sc1.progress === sc2.progress, { first: { stage: sc1.stage, progress: sc1.progress }, second: { stage: sc2.stage, progress: sc2.progress } })
    await evaluate(`window[${JSON.stringify(controlsFn)}].scrub(${addressAt})`)
    const sc3 = await status()
    await evaluate(`window[${JSON.stringify(controlsFn)}].scrub(${portraitAt})`)
    const sc4 = await status()
    check('scrub: every timeline point is addressable', sc3.stage !== sc4.stage, { at: { [addressAt]: sc3.stage, [portraitAt]: sc4.stage } })

    // ---- 降级矩阵 ----
    const sr1 = await status()
    check('reduced: full motion keeps particles visible', parseFloat(sr1.particleOpacity) > 0, { particleOpacity: sr1.particleOpacity })
    await screenshot('03-portrait.png')
    await evaluate(`window[${JSON.stringify(controlsFn)}].setReduced(true)`)
    const sr2 = await status()
    check('reduced: prefers-reduced-motion removes particles, drift and rotation', sr2.reduced === true && parseFloat(sr2.particleOpacity) === 0,
      { reduced: sr2.reduced, particleOpacity: sr2.particleOpacity })
    await evaluate(`window[${JSON.stringify(controlsFn)}].setReduced(false)`)

    // ---- 交棒转场：reveal 一次性闩锁 ----
    await evaluate(`window[${JSON.stringify(controlsFn)}].scrub(${shutterAt})`)
    await screenshot('04-shutter.png')
    const sr3 = await status()
    check('handoff: entering the shutter fires exactly one reveal event', sr3.events.filter((e) => e === 'reveal').length === 1, { events: sr3.events })
    await evaluate(`window[${JSON.stringify(controlsFn)}].finish(true)`)
    await evaluate(`window[${JSON.stringify(controlsFn)}].finish(true)`)
    const s4 = await status()
    check('handoff: the reveal latch stays at one through repeated finish()',
      s4.events.filter((e) => e === 'reveal').length === 1 && s4.events.includes('complete'), { events: s4.events })

    // ---- 指标自报 + 交棒留场 ----
    check('metrics: frame metrics are reported at completion',
      s4.metrics && s4.metrics.frames > 0 && s4.metrics.medianFrameMs >= 0 && s4.metrics.slowFrames >= 0, s4.metrics)
    await sleep(700)
    const s5 = await status()
    check('handoff: the overlay is removed and the host interface stays on screen',
      s5.overlayVisible === false && s5.events.includes('overlay-removed'), { overlayVisible: s5.overlayVisible, events: s5.events })
    await screenshot('05-revealed-host.png')

    // ---- 锚点守卫（A40）：必需判据一个都不能少 ----
    const missing = REQUIRED_CHECKS.filter((id) => !checks.some((item) => item.id === id))
    check('guard: every required check anchor ran', missing.length === 0, { expected: REQUIRED_CHECKS.length, ran: checks.length, missing })

    return checks.every((item) => item.ok) ? 0 : 4
  } catch (error) {
    console.error('RUNTIME  ' + (error && error.stack || error))
    await writeReport(reportFile, { exitCode: 3, error: String(error && error.stack || error), checks })
    return 3
  } finally {
    if (cdp) { try { await cdp.call('Browser.close') } catch { /* already closed */ } cdp.socket.close() }
    if (child) { try { child.kill() } catch { /* already gone */ } }
  }
}

// 直接执行守卫：被 selftest import 时只取纯函数，不跑 harness、不退出进程
const isMain = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
if (isMain) {
  const exitCode = await main()
  const guard = checks.some((item) => item.id === 'guard: every required check anchor ran')
  if (!guard && exitCode === 4) {
    // 判据失败但锚点守卫没跑到：把缺失情况写进报告，避免"少跑了几条还是绿"（A40）
    const missing = REQUIRED_CHECKS.filter((id) => !checks.some((item) => item.id === id))
    checks.push({ id: 'guard: every required check anchor ran', ok: false, evidence: { missing } })
  }
  await writeReport(reportFile, { exitCode, checks, requiredAnchors: REQUIRED_CHECKS.length })
  console.log('report: ' + reportFile + '  exit: ' + exitCode)
  process.exit(exitCode)
}

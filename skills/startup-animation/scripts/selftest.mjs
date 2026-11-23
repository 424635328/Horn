/**
 * selftest.mjs — startup-animation skill 脚本与判据自检（门禁自身的门禁）
 *
 * 用法：node scripts/selftest.mjs            # 全量自检
 *       SELFTEST_PROBE=fail node scripts/selftest.mjs --probe-only   # 自红探针（供父进程断言失败会传播）
 *
 * 覆盖：脚本语法、矢量化正/负控、时间轴确定性、判据负控、双平台浏览器发现分支（A15）、
 *       verify-animation 退出码传播（A27）、必需锚点不许静默变少（A40）。
 * 退出码：0 全绿 / 2 预检失败 / 4 任一变红。所有用例注册完毕后**单点 drain**（A27）。
 */
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { vectorize } from './vectorize.mjs'
import { DURATION, smooth, seg, progress, stageOf, MILESTONES, STAGES } from './timeline.mjs'
import { checkFreeze, checkClockRuns, browserCandidates, REQUIRED_CHECKS } from './verify-animation.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const checks = []
const check = (id, ok, evidence) => checks.push({ id, ok: Boolean(ok), evidence: evidence ?? {} })
const SELFTEST_REQUIRED = Object.freeze([
  'syntax: every shipped script passes node --check',
  'vectorize: a square on flat background yields contour paths',
  'vectorize: a flat image yields zero paths',
  'vectorize: output coordinates are mapped to the art canvas',
  'vectorize: invalid input throws with the parameter name',
  'timeline: progress endpoints and monotonicity',
  'timeline: identical input reproduces identical output',
  'timeline: stage boundaries match the storyboard',
  'detectors: the freeze detector turns red on a moved clock',
  'detectors: the clock detector turns red on a frozen playback',
  'discovery: the win32 and linux branches are both asserted in one run',
  'anchors: the required check list is unique and holds both negative controls',
  'probe: verify-animation --probe-only passes its own negative controls',
  'probe: VERIFY_PROBE=fail turns verify-animation red with exit 4',
  'probe: SELFTEST_PROBE=fail turns this selftest red with exit 4'
])

function runNode(scriptArgs, extraEnv = {}) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, scriptArgs, { env: { ...process.env, ...extraEnv }, stdio: ['ignore', 'pipe', 'pipe'] })
    let out = ''
    child.stdout.on('data', (chunk) => { out += chunk })
    child.stderr.on('data', (chunk) => { out += chunk })
    child.on('error', (error) => resolve({ code: -1, out: String(error) }))
    child.on('close', (code) => resolve({ code, out }))
  })
}

// ---- probe-only：最小自检面（父进程用它断言退出码传播） ----
if (process.argv.includes('--probe-only')) {
  const forced = process.env.SELFTEST_PROBE === 'fail'
  console.log((forced ? 'FAIL' : 'PASS') + '  probe: forced self-red channel  ' + JSON.stringify({ SELFTEST_PROBE: process.env.SELFTEST_PROBE ?? null }))
  process.exit(forced ? 4 : 0)
}

async function main() {
  // 1. 脚本语法（构建前置；裸 Node 可检查，无构建步骤）
  try {
    const names = ['vectorize.mjs', 'timeline.mjs', 'verify-animation.mjs', 'selftest.mjs']
    const results = []
    for (const name of names) {
      const file = path.join(here, name)
      results.push({ name, ...(await runNode(['--check', file])) })
    }
    check('syntax: every shipped script passes node --check', results.every((r) => r.code === 0),
      Object.fromEntries(results.map((r) => [r.name, r.code])))
  } catch (error) { check('syntax: every shipped script passes node --check', false, { error: String(error) }) }

  // 2. 矢量化正控/负控/映射/输入校验（自写实现，教科书算法）
  try {
    const W = 96, H = 54
    const square = new Uint8ClampedArray(W * H * 4)
    for (let y = 12; y < 42; y++) for (let x = 24; x < 72; x++) { const i = (y * W + x) * 4; square[i] = square[i + 1] = square[i + 2] = 255; square[i + 3] = 255 }
    const paths = vectorize(square, W, H)
    check('vectorize: a square on flat background yields contour paths', paths.length > 0, { paths: paths.length })
    const flat = new Uint8ClampedArray(W * H * 4).fill(128)
    const none = vectorize(flat, W, H)
    check('vectorize: a flat image yields zero paths', none.length === 0, { paths: none.length })
    const coords = paths.flat()
    const mapped = coords.every(([x, y]) => x >= 0 && x <= 1920 && y >= 0 && y <= 1080)
    check('vectorize: output coordinates are mapped to the art canvas', mapped, { samples: coords.length })
    let threw = ''
    try { vectorize(square, 0, H) } catch (error) { threw = String(error.message || error) }
    check('vectorize: invalid input throws with the parameter name', /width/.test(threw), { threw })
  } catch (error) {
    for (const id of ['vectorize: a square on flat background yields contour paths', 'vectorize: a flat image yields zero paths',
      'vectorize: output coordinates are mapped to the art canvas', 'vectorize: invalid input throws with the parameter name']) check(id, false, { error: String(error) })
  }

  // 3. 时间轴：端点/单调/确定性/阶段边界
  try {
    const samples = Array.from({ length: 21 }, (_, i) => i * DURATION / 20)
    const values = samples.map((t) => progress(t))
    const monotone = values.every((v, i) => i === 0 || v >= values[i - 1])
    check('timeline: progress endpoints and monotonicity',
      progress(0) === 0 && progress(DURATION) === 1 && monotone && smooth(0.5) === 0.5 && seg(500, 0, 1000) === smooth(0.5),
      { start: progress(0), end: progress(DURATION), monotone })
    const repeat = samples.map((t) => progress(t))
    check('timeline: identical input reproduces identical output',
      JSON.stringify(values) === JSON.stringify(repeat) && MILESTONES.length >= 4 && STAGES.length >= 4, { samples: samples.length })
    check('timeline: stage boundaries match the storyboard',
      stageOf(0) === 'pulse' && stageOf(1150) === 'identity' && stageOf(5500) === 'drawing' && stageOf(7200) === 'colorize' && stageOf(8000) === 'portrait' && stageOf(10399) === 'shutter',
      { at0: stageOf(0), at5500: stageOf(5500), at10399: stageOf(10399) })
  } catch (error) {
    for (const id of ['timeline: progress endpoints and monotonicity', 'timeline: identical input reproduces identical output',
      'timeline: stage boundaries match the storyboard']) check(id, false, { error: String(error) })
  }

  // 4. 判据负控（判据自身必须会红——正控在 verify-animation 里跑）
  try {
    const bad1 = checkFreeze({ playing: false, dataElapsed: '0.50' })
    check('detectors: the freeze detector turns red on a moved clock', bad1.ok === false, bad1)
    const bad2 = checkClockRuns({ playing: true, dataElapsed: '0.00' })
    check('detectors: the clock detector turns red on a frozen playback', bad2.ok === false, bad2)
  } catch (error) {
    check('detectors: the freeze detector turns red on a moved clock', false, { error: String(error) })
    check('detectors: the clock detector turns red on a frozen playback', false, { error: String(error) })
  }

  // 5. 双平台浏览器发现分支（A15：同一次运行断言两个平台，不许 process.platform 跳过）
  try {
    const env = { PROGRAMFILES: 'C:/Program Files', 'PROGRAMFILES(X86)': 'C:/Program Files (x86)', DSH_BROWSER_BIN: '' }
    const win = browserCandidates(env, 'win32')
    const linux = browserCandidates(env, 'linux')
    const override = browserCandidates({ DSH_BROWSER_BIN: '/opt/custom/chrome' }, 'linux')
    check('discovery: the win32 and linux branches are both asserted in one run',
      win.some((p) => p.endsWith('chrome.exe')) && win.some((p) => p.endsWith('msedge.exe')) &&
      linux.includes('/usr/bin/google-chrome') && override[0] === '/opt/custom/chrome' && win.length > 0 && linux.length > 0,
      { win32: win.length, linux: linux.length, overrideFirst: override[0] })
  } catch (error) { check('discovery: the win32 and linux branches are both asserted in one run', false, { error: String(error) }) }

  // 6. 锚点清单自身不许静默缩水（A40）
  try {
    const unique = new Set(REQUIRED_CHECKS).size === REQUIRED_CHECKS.length
    const hasNegatives = REQUIRED_CHECKS.some((id) => id.startsWith('negative: the freeze')) && REQUIRED_CHECKS.some((id) => id.startsWith('negative: the clock'))
    check('anchors: the required check list is unique and holds both negative controls', unique && hasNegatives && REQUIRED_CHECKS.length >= 14,
      { count: REQUIRED_CHECKS.length, unique })
  } catch (error) { check('anchors: the required check list is unique and holds both negative controls', false, { error: String(error) }) }

  // 7. 退出码传播（A27：门禁自身要有负控）
  try {
    const verify = path.join(here, 'verify-animation.mjs')
    const report = path.join(os.tmpdir(), 'selftest-verify-probe-' + process.pid + '.json')
    const good = await runNode([verify, '--probe-only', '--report', report])
    check('probe: verify-animation --probe-only passes its own negative controls', good.code === 0 && good.out.includes('PASS'), { exit: good.code })
    const bad = await runNode([verify, '--probe-only', '--report', report], { VERIFY_PROBE: 'fail' })
    const payload = JSON.parse(await fs.readFile(report, 'utf8'))
    check('probe: VERIFY_PROBE=fail turns verify-animation red with exit 4',
      bad.code === 4 && bad.out.includes('FAIL') && payload.exitCode === 4 && payload.checks.some((c) => !c.ok), { exit: bad.code, reportExit: payload.exitCode })
    const self = await runNode([fileURLToPath(import.meta.url), '--probe-only'], { SELFTEST_PROBE: 'fail' })
    check('probe: SELFTEST_PROBE=fail turns this selftest red with exit 4', self.code === 4 && self.out.includes('FAIL'), { exit: self.code })
    await fs.rm(report, { force: true })
  } catch (error) {
    for (const id of ['probe: verify-animation --probe-only passes its own negative controls', 'probe: VERIFY_PROBE=fail turns verify-animation red with exit 4',
      'probe: SELFTEST_PROBE=fail turns this selftest red with exit 4']) check(id, false, { error: String(error) })
  }

  // 单点 drain：所有用例注册/执行完毕后统一出结果（A27：中途不 exit、不 await 后再补注册）
  for (const item of checks) console.log((item.ok ? 'PASS' : 'FAIL') + '  ' + item.id + '  ' + JSON.stringify(item.evidence))
  const missing = SELFTEST_REQUIRED.filter((id) => !checks.some((item) => item.id === id))
  if (missing.length) check('guard: every required selftest anchor ran', false, { missing })
  else check('guard: every required selftest anchor ran', true, { ran: checks.length, expected: SELFTEST_REQUIRED.length })
  const ok = checks.every((item) => item.ok)
  console.log(`[selftest] ${ok ? '通过' : '未通过'}  用例 ${checks.length}，失败 ${checks.filter((c) => !c.ok).length}`)
  return ok ? 0 : 4
}

const exitCode = await main().catch((error) => { console.error('FATAL  ' + (error && error.stack || error)); return 4 })
process.exit(exitCode)

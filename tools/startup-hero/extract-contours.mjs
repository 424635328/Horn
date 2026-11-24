#!/usr/bin/env node
/**
 * 参考图 → 轮廓 polylines → 二进制素材（开屏线稿聚合用）。
 *
 * 为什么在构建期做：轮廓提取（Sobel + 非极大值抑制 + 边缘行走）是「重活」，
 * skill 要求它只在换图时跑一次、绝不进每帧路径；放进运行时还会带来首帧解码与堆分配。
 * 因此这里离线跑一次，产出 assets/startup/hero_contours.bin，App 侧只做一次读取与切分。
 *
 * 算法本身来自仓库内 vendored skill（只读引用，不改动）：
 *   .agents/skills/startup-animation/scripts/vectorize.mjs
 *
 * 产物格式（小端）：
 *   magic  'HRNC' (4) | version u16=1 | reserved u16 | imageW u16 | imageH u16 | pathCount u32
 *   每路径： pointCount u16 | pointCount × (xNorm u16, yNorm u16)   // 归一化到 0..1
 *
 * 用法：node tools/startup-hero/extract-contours.mjs [--check]
 *   --check  只校验已存在素材与参考图是否一致（不重写文件），不一致退出码 3
 * 退出码：0 成功 / 2 参数或输入问题 / 3 --check 不一致 / 4 自检失败（两次结果不同）
 */
import { promises as fs } from 'node:fs'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { vectorize } from '../../.agents/skills/startup-animation/scripts/vectorize.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..', '..')
const HERO = path.join(ROOT, 'feature/horn/src/main/res/drawable-nodpi/startup_hero.png')
const OUT = path.join(ROOT, 'feature/horn/src/main/assets/startup/hero_contours.bin')
const MAGIC = 'HRNC'
const VERSION = 1
const MAX_PATHS = 1_000
const MAX_LINE_ART_POINTS = 26_000

/** 最小 PNG 解码：8bit、非隔行、RGB/RGBA、filter 0..4。够用即止，不做通用解码器。 */
function decodePng(buffer) {
  if (buffer.readUInt32BE(0) !== 0x89504e47) throw new Error('not a PNG')
  let offset = 8
  let width = 0
  let height = 0
  let bitDepth = 0
  let colorType = 0
  let interlace = 0
  const idat = []
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset)
    const type = buffer.toString('ascii', offset + 4, offset + 8)
    const data = buffer.subarray(offset + 8, offset + 8 + length)
    if (type === 'IHDR') {
      width = data.readUInt32BE(0)
      height = data.readUInt32BE(4)
      bitDepth = data[8]
      colorType = data[9]
      interlace = data[12]
    } else if (type === 'IDAT') {
      idat.push(data)
    } else if (type === 'IEND') {
      break
    }
    offset += 12 + length
  }
  if (bitDepth !== 8) throw new Error(`unsupported bit depth ${bitDepth}`)
  if (interlace !== 0) throw new Error('interlaced PNG is not supported')
  const channels = colorType === 6 ? 4 : colorType === 2 ? 3 : 0
  if (!channels) throw new Error(`unsupported color type ${colorType}`)

  const raw = zlib.inflateSync(Buffer.concat(idat))
  const stride = width * channels
  const out = Buffer.alloc(height * stride)
  let src = 0
  for (let y = 0; y < height; y++) {
    const filter = raw[src++]
    const rowStart = y * stride
    const prevStart = (y - 1) * stride
    for (let x = 0; x < stride; x++) {
      const value = raw[src + x]
      const left = x >= channels ? out[rowStart + x - channels] : 0
      const up = y > 0 ? out[prevStart + x] : 0
      const upLeft = y > 0 && x >= channels ? out[prevStart + x - channels] : 0
      let restored
      switch (filter) {
        case 0: restored = value; break
        case 1: restored = value + left; break
        case 2: restored = value + up; break
        case 3: restored = value + ((left + up) >> 1); break
        case 4: {
          const p = left + up - upLeft
          const pa = Math.abs(p - left)
          const pb = Math.abs(p - up)
          const pc = Math.abs(p - upLeft)
          const predictor = pa <= pb && pa <= pc ? left : pb <= pc ? up : upLeft
          restored = value + predictor
          break
        }
        default: throw new Error(`unsupported PNG filter ${filter}`)
      }
      out[rowStart + x] = restored & 0xff
    }
    src += stride
  }
  // RGBA（alpha 恒 255：参考图不透明）
  const rgba = new Uint8Array(width * height * 4)
  for (let i = 0, j = 0; i < width * height; i++, j += channels) {
    rgba[i * 4] = out[j]
    rgba[i * 4 + 1] = out[j + 1]
    rgba[i * 4 + 2] = out[j + 2]
    rgba[i * 4 + 3] = channels === 4 ? out[j + 3] : 255
  }
  return { width, height, rgba }
}

function arcLength(points) {
  let total = 0
  for (let i = 1; i < points.length; i += 2) {
    total += Math.hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1])
  }
  return total
}

/** 归一化 + 统一抽稀到预算内在 32k 点以内（超过就跳点，保持首尾）。 */
function buildPolylines(paths, width, height) {
  const normalized = paths
    .map((points) => {
      const flat = new Float64Array(points.length * 2)
      points.forEach(([x, y], index) => {
        flat[index * 2] = x / width
        flat[index * 2 + 1] = y / height
      })
      return flat
    })
    .filter((polyline) => polyline.length >= 4)
  let totalPoints = normalized.reduce((sum, polyline) => sum + polyline.length / 2, 0)
  let stride = 1
  while (totalPoints / stride > MAX_LINE_ART_POINTS) stride += 1
  if (stride === 1) return normalized
  return normalized.map((polyline) => {
    const kept = []
    const count = polyline.length / 2
    for (let i = 0; i < count; i += stride) kept.push(polyline[i * 2], polyline[i * 2 + 1])
    if (count > 1) kept.push(polyline[(count - 1) * 2], polyline[(count - 1) * 2 + 1])
    return Float64Array.from(kept)
  })
}

function encode(width, height, polylines) {
  const header = 16
  const bodyBytes = polylines.reduce((sum, polyline) => sum + 2 + (polyline.length / 2) * 4, 0)
  const buffer = Buffer.alloc(header + bodyBytes)
  buffer.write(MAGIC, 0, 'ascii')
  buffer.writeUInt16LE(VERSION, 4)
  buffer.writeUInt16LE(0, 6)
  buffer.writeUInt16LE(width, 8)
  buffer.writeUInt16LE(height, 10)
  buffer.writeUInt32LE(polylines.length, 12)
  let offset = header
  for (const polyline of polylines) {
    const count = polyline.length / 2
    buffer.writeUInt16LE(count, offset)
    offset += 2
    for (let i = 0; i < count; i++) {
      const x = Math.max(0, Math.min(65535, Math.round(polyline[i * 2] * 65535)))
      const y = Math.max(0, Math.min(65535, Math.round(polyline[i * 2 + 1] * 65535)))
      buffer.writeUInt16LE(x, offset)
      buffer.writeUInt16LE(y, offset + 2)
      offset += 4
    }
  }
  return buffer
}

function extract() {
  const png = readFileSync(HERO)
  const { width, height, rgba } = decodePng(png)
  const paths = vectorize(rgba, width, height, {
    maxPaths: MAX_PATHS,
    minPoints: 8,
    magThreshold: 32,
    sampleEvery: 2,
    outWidth: width,
    outHeight: height
  })
  const polylines = buildPolylines(paths, width, height)
  const buffer = encode(width, height, polylines)
  const points = polylines.reduce((sum, polyline) => sum + polyline.length / 2, 0)
  return { buffer, width, height, polylines, points, pngHash: createHash('sha256').update(png).digest('hex') }
}

async function main() {
  const checkOnly = process.argv.includes('--check')
  let first
  try {
    first = extract()
  } catch (error) {
    console.error('extract failed: ' + (error && error.message))
    return 2
  }
  // 自检：同输入必须同输出（skill 的确定性硬要求）
  let second
  try {
    second = extract()
  } catch (error) {
    console.error('determinism probe failed: ' + (error && error.message))
    return 4
  }
  if (!first.buffer.equals(second.buffer)) {
    console.error('self-test failed: two runs produced different assets')
    return 4
  }
  const summary = {
    image: `${first.width}x${first.height}`,
    paths: first.polylines.length,
    points: first.points,
    bytes: first.buffer.length,
    sha256: createHash('sha256').update(first.buffer).digest('hex').slice(0, 16)
  }
  if (checkOnly) {
    const existing = await fs.readFile(OUT).catch(() => null)
    if (!existing) {
      console.error(`check failed: ${path.relative(ROOT, OUT)} is missing; run without --check`)
      return 3
    }
    if (!existing.equals(first.buffer)) {
      console.error('check failed: hero contour asset is stale; regenerate it from startup_hero.png')
      return 3
    }
    console.log('contours check OK ' + JSON.stringify(summary))
    return 0
  }
  await fs.mkdir(path.dirname(OUT), { recursive: true })
  await fs.writeFile(OUT, first.buffer)
  console.log('wrote ' + path.relative(ROOT, OUT) + ' ' + JSON.stringify(summary))
  return 0
}

process.exit(await main())

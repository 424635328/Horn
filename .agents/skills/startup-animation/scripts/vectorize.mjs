/**
 * vectorize(rgba, width, height, options) -> path[]
 *   path = [[x, y], ...]  (坐标已映射到 options.outWidth/outHeight 的画布空间)
 *
 * 浏览器上下文的图片轮廓矢量化：ImageData -> 折线路径。
 * 管线：灰度 -> Sobel 梯度 -> 非极大值抑制 -> 边缘行走 -> 抽稀 -> 按长度截断。
 * 确定性：同输入必同输出（无随机数）。每张图跑一次，禁止进每帧路径。
 * 用法：import { vectorize } from './vectorize.mjs'
 *   把图缩到 ~960x540 画进 canvas，再 vectorize(ctx.getImageData(...).data, 960, 540)。
 * 退出码语义不适用于本模块；输入非法抛 TypeError（消息含参数名），调用方自行映射。
 */

export function vectorize(rgba, width, height, options = {}) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 8 || height < 8) {
    throw new TypeError('vectorize: width/height must be integers >= 8')
  }
  if (!rgba || typeof rgba.length !== 'number' || rgba.length < width * height * 4) {
    throw new TypeError('vectorize: rgba must hold width*height*4 samples')
  }
  const o = Object.assign(
    { maxPaths: 1100, minPoints: 8, magThreshold: 32, sampleEvery: 2, outWidth: 1920, outHeight: 1080 },
    options
  );
  for (const [key, value] of [['maxPaths', o.maxPaths], ['minPoints', o.minPoints], ['sampleEvery', o.sampleEvery]]) {
    if (!Number.isInteger(value) || value < 1) throw new TypeError(`vectorize: ${key} must be a positive integer`)
  }
  const count = width * height;
  const gray = new Float32Array(count);
  const mag = new Float32Array(count);
  const bucket = new Uint8Array(count);
  const edge = new Uint8Array(count);
  const visited = new Uint8Array(count);
  const paths = [];

  for (let i = 0; i < count; i++) {
    gray[i] = rgba[i * 4] * 0.299 + rgba[i * 4 + 1] * 0.587 + rgba[i * 4 + 2] * 0.114;
  }

  // Sobel：4 个方向桶，供非极大值抑制选比较轴
  const axis = [[1, -1], [width + 1, -width - 1], [width, -width], [width - 1, -width + 1]];
  for (let y = 1; y < height - 1; y++) {
    for (let x = 1; x < width - 1; x++) {
      const i = y * width + x;
      const tl = gray[i - width - 1], tc = gray[i - width], tr = gray[i - width + 1];
      const ml = gray[i - 1], mr = gray[i + 1];
      const bl = gray[i + width - 1], bc = gray[i + width], br = gray[i + width + 1];
      const gx = -tl + tr - 2 * ml + 2 * mr - bl + br;
      const gy = -tl - 2 * tc - tr + bl + 2 * bc + br;
      mag[i] = Math.hypot(gx, gy);
      const deg = (Math.atan2(gy, gx) * 180 / Math.PI + 180) % 180;
      bucket[i] = deg < 22.5 || deg >= 157.5 ? 0 : deg < 67.5 ? 1 : deg < 112.5 ? 2 : 3;
    }
  }

  // 非极大值抑制：沿梯度轴保留局部峰值
  for (let y = 2; y < height - 2; y++) {
    for (let x = 2; x < width - 2; x++) {
      const i = y * width + x;
      const [a, b] = axis[bucket[i]];
      if (mag[i] > o.magThreshold && mag[i] >= mag[i + a] && mag[i] > mag[i + b]) edge[i] = 1;
    }
  }

  // 边缘行走：每步沿"延续上一步方向"得分最高的邻点前进
  const around = [-width - 1, -width, -width + 1, -1, 1, width - 1, width, width + 1];
  function walk(start) {
    let cur = start, prev = -1;
    const pts = [];
    while (cur >= 0 && !visited[cur]) {
      visited[cur] = 1;
      pts.push([cur % width, Math.floor(cur / width)]);
      let next = -1, best = -Infinity;
      for (const d of around) {
        const n = cur + d;
        if (n < 0 || n >= count || !edge[n] || visited[n]) continue;
        const score = prev < 0
          ? mag[n]
          : (n % width - cur % width) * (cur % width - prev % width) +
            (Math.floor(n / width) - Math.floor(cur / width)) * (Math.floor(cur / width) - Math.floor(prev / width));
        if (score > best) { best = score; next = n; }
      }
      prev = cur; cur = next;
    }
    if (pts.length >= o.minPoints) {
      const sampled = pts.filter((_, i) => i % o.sampleEvery === 0 || i === pts.length - 1);
      const sx = o.outWidth / width, sy = o.outHeight / height;
      paths.push(sampled.map(([x, y]) => [Math.round(x * sx), Math.round(y * sy)]));
    }
  }

  // 先走端点（8 邻域内边数 <= 1），再走剩余闭合环
  for (let i = 0; i < count; i++) {
    if (edge[i] && !visited[i]) {
      let n = 0;
      for (const d of around) if (edge[i + d]) n++;
      if (n <= 1) walk(i);
    }
  }
  for (let i = 0; i < count; i++) if (edge[i] && !visited[i]) walk(i);

  const lengthOf = (pts) => {
    let total = 0;
    for (let i = 1; i < pts.length; i++) total += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
    return total;
  };
  return paths.sort((a, b) => lengthOf(b) - lengthOf(a)).slice(0, o.maxPaths);
}

/**
 * 时间轴参考实现（单时钟动画的纯函数核心，selftest 可直接断言）。
 * 页面里 import 复用，或照抄公式；禁止在这里引入带状态的排程器。
 */

export const DURATION = 10400; // 参考时间轴总时长 ms（按分镜表调整）

export const clamp01 = (n) => Math.max(0, Math.min(1, n));
export const smooth = (n) => { const t = clamp01(n); return t * t * (3 - 2 * t); };
export const seg = (t, start, dur) => smooth((t - start) / dur);

/** [时间比例, 进度比例]：慢起步 → 中段加速 → 尾段收住 */
export const MILESTONES = [[0, 0], [.12, .06], [.35, .41], [.56, .76], [.66, .924], [.72, .97], [.77, 1], [1, 1]];

/** 非线性进度：进度条/同步条/碎片归位共用一条节奏曲线 */
export function progress(t, duration = DURATION) {
  const p = clamp01(t / duration);
  for (let i = 1; i < MILESTONES.length; i++) {
    if (p <= MILESTONES[i][0]) {
      const [a, x] = MILESTONES[i - 1], [b, y] = MILESTONES[i];
      return x + (y - x) * smooth((p - a) / (b - a));
    }
  }
  return 1;
}

/** 阶段投影（root.dataset.stage 用同一函数派生，验收可断言） */
export const STAGES = [
  ['pulse', 1150], ['identity', 4200], ['drawing', 7200],
  ['colorize', 7700], ['portrait', 9600], ['shutter', Infinity]
];
export function stageOf(t) {
  for (const [name, bound] of STAGES) if (t < bound) return name;
  return 'shutter';
}

/** 心跳：呼吸/微浮起（比 sine 更"有机"） */
export const heartbeat = (t, period = 1080) => Math.pow((1 - Math.cos(Math.max(0, t) * Math.PI * 2 / period)) / 2, 2);

/** 相位循环：圆环脉冲、扫描灯、粒子闪烁的错峰重复 */
export const phaseLoop = (t, speed, offset = 0) => (t * speed + offset) % 1;

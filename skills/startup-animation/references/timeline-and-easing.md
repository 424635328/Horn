# 时间轴与缓动 — 分镜表 → 单时钟实现

参考实现：[../scripts/timeline.mjs](../scripts/timeline.mjs)（`DURATION / smooth / seg / progress / stageOf`，可被 selftest 直接断言）。

## 1. 先写分镜表，再写代码

每一行是一个"时间段"，不是"元素"。同一个元素出现在多行（入场/保持/出场）：

| 时刻(s) | 阶段名 | 元素 | 入场/出场公式 |
|---|---|---|---|
| 0.0–1.2 | pulse | 脉冲圆环 | 3 环错峰 `(t*0.00075 + i*0.33) % 1` 循环缩放 |
| 1.0–4.2 | identity | 头像+雷达+标题 | opacity `seg(t,1000,650)`；标题 clip-path 擦除 `1700→2400` |
| 4.2–7.2 | drawing | 线稿聚合 | 进度 `p=clamp((t-4200)/3000)`，碎片按目标 X 依次归位 |
| 7.2–7.7 | colorize | 线稿→彩图 | 交叉淡化 `seg(t,7200,500)`（同几何对齐） |
| 7.7–9.6 | portrait | HUD/字幕/粒子 | 少量缓慢运动 + 光扫一次性 |
| 9.6–10.4 | shutter | 合拢转场 | `scaleY(1-collapse)` + 中线 `scaleX(1-pinch)`，9.6s 发 `reveal` |

把阶段名投影到 DOM（`root.dataset.stage`）并用 `stageOf(t)` 派生，验收脚本可直接断言"第 5.5s 处于 drawing"。

## 2. 里程碑节奏曲线（非线性进度）

用户感知的"进度"不该匀速。用控制点表 + smoothstep 逐段插值（`timeline.mjs` 的 `progress()`）：

```js
// [时间比例, 进度比例]：慢起步 → 中段加速 → 尾段收住
const milestones = [[0,0],[.12,.06],[.35,.41],[.56,.76],[.66,.924],[.72,.97],[.77,1],[1,1]];
```

进度条、同步条、碎片归位全部消费 `progress(t)`，节奏改一处即可。

## 3. 缓动原语只有三个

- **smoothstep 段**：`seg(t, start, dur)` —— 一切淡入淡出、擦除、缩放。
- **心跳**：`Math.pow((1 - Math.cos(t * Math.PI*2 / period)) / 2, 2)` —— 呼吸/微浮起（比 sine 更"有机"）。
- **相位循环**：`(t * speed + i * offset) % 1` —— 圆环脉冲、扫描灯、粒子闪烁的错峰重复。

需要"回弹/弹性"时用 smoothstep 叠加或极短的二次 overshoot，不引缓动库。

## 4. 单时钟纪律

- 所有状态 = `f(elapsed)`；`tick` 只做：算 `elapsed` → `render(elapsed)` → 判结束。
- **UI 节流**：读数/文字每 ≥60ms 更新一次（`Math.abs(t - lastUI) > 60`），画面仍逐帧。
- **scrub 免费且可复现**：拖进度条 = `stop()` 后 `render(value*1000)`；同一 t 必须逐位复现同一状态
  （验收判据 `scrub: seeking the same time twice reproduces the same frame state`）。
  不可逆的累积绘制必须先 `reset` 再画，或设计成"增量可重放"。
- **标签页隐藏暂停**：`visibilitychange` 时冻结 `elapsed`，回来从断点续播，不补帧、不快进。
- **结束只发生一次**：`finish()` 里停 rAF、上报指标、切 `finished` 状态；完成判据闩锁，重复触发无副作用（幂等）。

## 5. 收尾转场 = 交棒协议

开场动画不能"消失"，要"让位"：

1. 转场（画面纵向合拢到中线 + 发光中线横向收窄）开始的**那一帧**发出一次性 `reveal`（布尔闩锁）；
2. 宿主此刻把真实界面显示出来，动画层随后整块撤掉；
3. 转场期间动画层背景转 `transparent`，合拢的两半之间露出的就是真实界面。

判据：`reveal` 每次播放只发一条（`handoff: entering the shutter fires exactly one reveal event`），
且发出时刻可由 `dataset.stage === 'shutter'` 复算。

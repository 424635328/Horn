---
name: startup-animation
description: Design and build cinematic startup/boot and timeline-driven UI animations — single-clock render loops, smoothstep milestone pacing, canvas line-art assembly, HUD/sci-fi visual language, prewarm-before-show protocols, reduced-motion fallback, and frame-budget verification. Use when 用户要做启动动画/开场动画/boot animation/splash/hero intro，或要做 Canvas 线稿聚合、粒子、扫描线、光扫、快门转场等电影感动画，或要求开场动画"先预热再开演"不卡首帧。
whenToUse: 制作启动/开场/加载动画、Canvas 线稿聚合与粒子编排、预热协议与交棒转场、动画性能与降级验收时。
---

# 启动动画制作

把「电影感开场」拆成**一条可拖动的时间轴** + **一套一次性预计算的素材**。
方法论提炼自一个商业启动动画实现的动画层（其上游无开源许可：只学方法、不抄代码，实现全部自己写）。

## Quick start — 单时钟渲染骨架

一切视觉状态都是 `f(t)`，绝不用 `setTimeout` 排程：

```js
import { DURATION, smooth, seg, progress, stageOf } from './scripts/timeline.mjs';

function render(t) {                    // 每帧唯一入口，可从任意时刻调用
  intro.style.opacity  = String(1 - seg(t, 4180, 280));
  title.style.clipPath = `inset(0 ${(1 - seg(t, 1900, 700)) * 100}% 0 0)`;
  scene.style.transform = `scaleY(${Math.max(.001, 1 - seg(t, 11000, 300))})`;
  if (Math.abs(t - lastUI) > 60) { updateReadouts(t); lastUI = t; }  // 文字/读数节流
}
function tick(now) {
  elapsed = Math.min(DURATION, now - origin);
  render(elapsed);
  if (elapsed >= DURATION) return finish();
  frame = requestAnimationFrame(tick);
}
```

scrub（拖进度条）、暂停、replay 全部免费：任意 `render(elapsed)` 都能复现那一帧。

## Workflows

1. **分镜表**：先列时间轴表（时刻/阶段/元素/入出���公式），再写代码——[references/timeline-and-easing.md](references/timeline-and-easing.md)。
2. **素材预计算**：轮廓矢量化、碎片切分、纹理烘焙一次完成（换图时重算一次）——[references/canvas-line-art.md](references/canvas-line-art.md)，算法实现 [scripts/vectorize.mjs](scripts/vectorize.mjs)。
3. **渲染实现**：单时钟 render；动画属性只用 transform/opacity/clip-path + 增量 canvas；节奏与阶段直接用 [scripts/timeline.mjs](scripts/timeline.mjs)。
4. **预热协议**：隐藏层里解码、烘焙、画好第 0 帧并冻结（`data-elapsed` 恒 0.00），宿主确认就绪后显式 `start()` 才走表。
5. **验收**：`node scripts/selftest.mjs` 先绿，再按 [references/production-discipline.md](references/production-discipline.md) 的页面契约接入 `scripts/verify-animation.mjs` 做浏览器验收；两者的退出码与报告就是交付证据。

## 关键原则（违反即返工）

- **单时钟**：一个 `elapsed` 驱动所有元素；任何 `setTimeout` 链式排程都不可 scrub、不可暂停。
- **缓动只留一个原语**：smoothstep 段；特殊节奏用「里程碑曲线」和「%1 相位循环」推导，不引入缓动库。
- **重活只做一次**：边缘检测、碎片切分、文字/头像烘焙成纹理，都在预热期完成；每帧只做变换与增量绘制，零分配。
- **UI 与视觉分频**：画面每帧，读数/文字 ≤60ms 一次。
- **结尾必须交棒**：收尾转场开始的那一刻发出**一次性** `reveal`（闩锁），宿主画面从转场后接上。
- **预热期帧号冻结**：`data-elapsed` 恒 0.00，`start()` 后才走表——这是"不卡首帧"的可测判据。
- **降级是设计的一部分**：`prefers-reduced-motion` 下无粒子/无漂移/无旋转，只留透明度淡入。
- **验收靠机器，正控+负控**：每条判据要有会变红的负控证明（判据自身也要被负控打过），禁止"看着没问题"。
- **退出码有语义**：0 成功 / 2 预检失败 / 3 运行失败 / 4 判据失败；失败必须真的传播。
- **幂等可重入**：验收脚本可反复跑，报告原子写入（临时名→rename），不留半成品。
- **安全红线**：动画运行时零网络；不读受保护像素（用几何选片代替 getImageData）；代码与报告不落个人路径/凭据。

## Verify

```
node scripts/selftest.mjs                                # 脚本与判据自检（负控+自红探针+双平台分支）
node scripts/verify-animation.mjs --target <demo.html>   # 浏览器验收（--shots <dir> 留截图证据）
```

- `selftest.mjs`：矢量化正/负控、时间轴确定性、判据负控、`SELFTEST_PROBE=fail` 自红探针（证明失败会传播）。
- `verify-animation.mjs`：帧号冻结（含负控）、start <500ms 走表、scrub 逐位可复现、reduced-motion 降级、
  reveal 一次性闩锁、指标自报（median/p95/慢帧）、覆盖层撤下交棒；报告 `report.json` 原子落盘，
  **关键锚点缺失即判红**（断言数不许静默变少）。
- 页面契约：目标页暴露 `window.__demoStatus()`（status/dataElapsed/playing/completed/revealed/reduced/
  overlayVisible/particleOpacity/events/metrics）与 `window.__demo`（start/pause/replay/scrub/setReduced/finish），
  自定义名用 `--status-fn` / `--controls-fn` 注入。

## 参考

- [references/timeline-and-easing.md](references/timeline-and-easing.md) — 分镜表模板、里程碑节奏、缓动与错峰、收尾转场
- [references/canvas-line-art.md](references/canvas-line-art.md) — 图片→线稿→碎片→聚合管线与逐帧编排
- [references/production-discipline.md](references/production-discipline.md) — 性能纪律、预热协议、降级矩阵、HUD 词汇、验收清单→判据映射

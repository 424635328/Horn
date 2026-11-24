# 制作纪律 — 性能、预热、降级、视觉词汇、验收映射

## 1. 性能纪律

| 规则 | 做法 |
|---|---|
| 重活一次化 | 边缘检测/纹理烘焙/碎片切分只在换图或预热时跑；每帧只做 transform/opacity + 增量 canvas |
| 每帧零分配 | 粒子、碎片、Path2D 全部建一次；不在 render 里 new 对象/数组 |
| 文字与画面分频 | 画面逐帧，读数 ≤60ms 一次 |
| 少用 filter/blur | 一次性烘焙到纹理（如去色用 `saturation` 合成），不做活体滤镜 |
| will-change 克制 | 只给少数真在动的层；结束时移除 |
| 大资源走 Blob URL | 图片解码后 `URL.createObjectURL`，换图 `revoke` 旧 URL；不塞进 CSS 变量 |
| 隐藏即停 | `visibilitychange` 冻结时钟、暂停视频；回可见再续播 |
| 指标自报 | 记帧间隔与 render 耗时，完成时上报 median / p95 / 慢帧数（>33.4ms） |

## 2. 预热协议（"先预热再开演"，不卡首帧）

```
挂载（隐藏层: visibility:hidden 或 alpha=0）
  → 解码图片 → 轮廓/碎片/纹理烘焙 → render(0) 画好第 0 帧
  → 保持 data-elapsed === "0.00"（帧号冻结）
宿主确认就绪（自己的信号：数据加载完 / 窗口准备好）
  → 显式调用 start()：才置 playing、走表、显示层
  → 看门狗：预热超时 / 播放超时（25s）强制撤层，绝不永久占屏
```

- 就绪信号要**多重证据 + 超时兜底**：任何一条探针都可能永远不来，看门狗保证结局。
- 嵌入宿主时用 `postMessage` 桥（校验 `event.source`），就绪/完成/失败各发一次事件。

## 3. 降级矩阵

| 条件 | 行为 |
|---|---|
| `prefers-reduced-motion: reduce` | 无粒子/无漂移/无旋转/无心跳；只留透明度淡入，线稿直接给终态 |
| 图片解码失败 | 显示文字错误提示 + `assetError` 事件；不留半屏 |
| 播放中切后台 | 冻结进度，回来续播 |
| 用户跳过（Esc/按钮） | `finish(skipped=true)` 直接给终态并发交棒事件 |
| 宿主没响应 | 看门狗超时撤层；有错误通道就显示错误文案 |

## 4. HUD / 启动序列视觉词汇（科幻 boot 风）

- **底层氛围**：径向渐变暗底、扫描线（`repeating-linear-gradient`，opacity ≈ .02–.06）、斜向光扫（110° 白渐变横穿一次）。
- **HUD 元素**：等宽小字 + 大字距（`letter-spacing: 2px+`）、四角括号、同步进度条、时间码、逐行浮现的 boot 日志（每行错峰 0.3–0.4s）。
- **雷达表盘**：SVG 圆 + 虚线环（慢速旋转）+ 60 刻度（每 5 个加长）+ 十字准星 + 头像框四角 reticle。
- **文字技巧**：标题双色错位阴影（红/青 `text-shadow: 2px 0 …, -2px 0 …`）；标题用 clip-path 擦除入场；字距不做动画，用静态大字距。
- **粒子**：≤6 个 DOM 点，透明度随 sin 波呼吸、缓慢上浮；不是越多越好。
- **结尾**：画面纵向合拢 + 发光中线收窄 + 一次性闪白（可选），背景转透明完成交棒。

## 5. 验收清单 → 机器判据映射（`scripts/verify-animation.mjs`）

| # | 清单条目 | 判据（check id） | 类型 |
|---|---|---|---|
| 1 | 判据自身可信 | `negative: the freeze detector turns red…`、`negative: the clock detector turns red…` | 负控 |
| 2 | 预热帧号冻结 | `prewarm: the frame clock is frozen at 0.00 until start()` | 正控 |
| 3 | 素材管线出货 | `prewarm: contour pipeline produced fragments` | 正控 |
| 4 | start 后 <500ms 走表 | `start: the overlay shows and the clock runs within 500ms of start()` | 正控 |
| 5 | 时钟确实在走 | `start: the clock is advancing after start()` | 正控 |
| 6 | scrub 逐位可复现 | `scrub: seeking the same time twice reproduces the same frame state` | 正控 |
| 7 | 时间轴可寻址 | `scrub: every timeline point is addressable` | 正控 |
| 8 | 完整动效可见 | `reduced: full motion keeps particles visible` | 正控 |
| 9 | reduced 降级 | `reduced: prefers-reduced-motion removes particles, drift and rotation` | 正控 |
| 10 | reveal 一次性 | `handoff: entering the shutter fires exactly one reveal event` | 正控 |
| 11 | 闩锁幂等 | `handoff: the reveal latch stays at one through repeated finish()` | 幂等 |
| 12 | 指标自报 | `metrics: frame metrics are reported at completion` | 正控 |
| 13 | 交棒留场 | `handoff: the overlay is removed and the host interface stays on screen` | 正控 |

- **锚点守卫**：上表 13 个 id 是必需锚点；报告里缺任何一个直接判红——断言数不许静默变少（A40 教训）。
- **负控先行**：第 1 行先跑，判据自身不红就不信后面的绿。
- **退出码语义**：0 全绿 / 2 预检失败（浏览器、Node WebSocket、目标页契约缺失）/ 3 运行失败（CDP/导航）/ 4 判据失败。
- **报告**：`report.json` 原子写入（临时名→rename），含每条 id、ok、evidence；截图是补充证据不是判据。
- **幂等**：脚本可反复跑；临时用户目录、随机端口、退出时清理浏览器进程。

## 6. 安全红线（工业交付底线）

- 动画运行时**零网络请求**：素材全部内联（data URL / Blob），验收用 `file://` 可离线跑。
- **不读受保护像素**：瓦片选片用几何位置；必须 `getImageData` 时限定同源画布并留降级路径（canvas 跨域污染会直接抛错）。
- 代码、报告、截图名里**不落个人路径/凭据/会话 ID**；浏览器发现逻辑走环境变量与 `browserCandidates(env, platform)` 参数注入。
- **双平台同测**：路径/浏览器发现这类平台分支把 `platform` 作为入参，同一次 selftest 断言 win32 与 linux 两个分支（A15 教训），禁止用 `process.platform` 在测试里跳过另一平台。

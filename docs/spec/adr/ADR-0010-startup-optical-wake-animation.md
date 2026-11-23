# ADR-0010: 开屏动画 Optical Wake —— 单时钟 f(t) 自绘 + core-splashscreen 无缝交棒

- 状态：Accepted（v4.0 材质更新见 [ADR-0011](ADR-0011-precision-automotive-cockpit.md)）
- 日期：2026-11-22

> **v4.0 更新说明**：本决策的**机制**（单时钟 f(t)、闩锁、可跳过、无缝 Splash 交棒、零重组）
> 全部保留且仍为现行契约；仅**画面材质**由玻璃球改为机加工表盘（材质/几何/主题见 ADR-0011 与
> `docs/spec/03 §11`）。因此下文对 AGSL/`GlassCapabilities`/`AppleMotion` 的引用属于历史记录，
> 现行实现见 `core/designsystem/src/main/java/com/industrial/horn/ui/theme/StartupWake.kt`、
> `Motion.kt` 与 `feature/horn/src/main/java/com/industrial/horn/ui/StartupOverlay.kt`。

## 背景与问题

v3.7 需要品牌级开屏动画「Optical Wake 光学唤醒」：玻璃球剪影唤醒 → 双光环聚焦 → 声压绽放 →
无缝融入主界面，以深海蓝 `#2E5F86` → 琥珀 `#FFB547` 能量渐变呼应设计原型的蓝黄撞色。开屏是冷启动
的第一印象，但鸣笛是产品灵魂——开屏动画不得显著推迟紧急鸣笛的可达性。既有系统不变量对开屏同样
刚性：I7（高频视觉零重组）、I8（生命周期感知、结束后零残留）、I10（Roborazzi 80 张基准逐字节
确定性）、供应链克制（不引第三方动画运行时）、ReducedMotion 无障碍、静音红线（全程零音频）。
核心问题：**开屏动画以何种渲染与交棒机制实现，才能同时满足确定性测试、零重组性能、可跳过 UX
与低维护成本，并与 Android 12+ 系统 Splash 无缝衔接？**

## 决策驱动因素

- **紧急可达性优先**：开屏不得推迟紧急鸣笛；跳过与快速入口绕过必须是零负担路径。
- **包体与供应链克制**：不新增第三方动画运行时（Lottie 等）；仅允许官方 AndroidX 小库承担系统 Splash 交棒。
- **确定性测试（I10）**：新增定格帧必须可冻结时钟注入、跨平台逐字节复现，不冲击既有 80 张基准。
- **零重组性能（I7）**：动画帧只重绘不重组，逐帧零分配，不与 120fps 交互预算抢时间。
- **可跳过 UX**：任意区域按下即跳过、≤120ms 收起、跳过后零遮罩残留（鸣笛手势立即可达）。
- **维护成本**：时间轴常量与公式集中单点，改节奏不动绘制组件。
- **无缝 Splash**：系统 Splash 与 Optical Wake 底色/构图连续，严禁双开屏闪烁。
- **无障碍与降级**：ReducedMotion 或动画时长缩放为 0 直接绕过；渲染能力 AGSL/Canvas 分级。

## 备选方案对比

对比维度：包体、确定性测试、零重组性能、跳过 UX、维护成本。

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| ① Lottie/第三方动画库（lottie-android 等） | 动效由设计工具导出即用，视觉迭代快（维护成本：改动画不动代码） | 运行时与 JSON 解析器增加包体与依赖面（包体）；渲染黑盒，无头 JVM 定格不可逐字节确定，Roborazzi 基准漂移（确定性测试）；逐帧解析/属性更新易触发组合层读取，难守 I7 零重组（零重组性能）；跳过需二次封装取消合成，遮罩残留风险（跳过 UX） | 否决 |
| ② 纯 Compose AnimatedVisibility/Animatable 组合动画（重组驱动） | 零第三方依赖、Compose 原生、上手快（包体零增量） | 多属性各挂 animate*AsState，状态驱动重组违反 I7（零重组性能）；多动画时钟不同源，冻结时钟注入困难、定格帧漂移（确定性测试）；跳过需逐节点取消，≤120ms 统一收起难保证（跳过 UX）；时序散落多组件，元素越多维护成本越高 | 否决 |
| ③ 延长系统 Splash + 无自绘动画 | 零代码、零包体，系统观感统一（包体/维护成本最低） | 无品牌动效，产品目标不达；系统 Splash 不可交互，跳过 UX 无法实现；人为拉长交棒时长引发双开屏闪烁与时长不可控 | 否决（仅保留其交棒职责） |
| ④ 单时钟 f(t) + Compose DrawScope 自绘 + core-splashscreen 交棒（本决策） | 包体零第三方动画运行时（仅官方 core-splashscreen）；f(t) 纯函数任意帧可复现，冻结时钟注入后定格逐字节确定（确定性测试）；延迟读取零重组（I7）；按下即跳过 + 闩锁语义完整（跳过 UX）；时间轴/公式单点集中（维护成本） | 自绘代码量高于 Lottie 导入；AGSL/Canvas 需按能力分级；复杂特效（真粒子等）扩展成本高 | **采纳** |

## 最终决策

1. **单时钟 f(t) 渲染**：一切视觉状态是 t（elapsed ms）的纯函数，总时长 1040ms（预算 800–1150ms）；
   时间轴常量与公式集中于 StartupTimeline（`core/designsystem/src/main/java/com/industrial/horn/ui/theme/StartupWake.kt`），
   绘制原语 drawWakeScene 逐帧零分配；唯一缓动原语 smoothstep，弹簧落位以确定性里程碑曲线 settle
   （ζ=0.55，TactileOrbSpring 手感同源）实现，禁一切 setTimeout/多时钟排程。
2. **Compose DrawScope 自绘 + 能力分级**：AGSL/Canvas 分级沿 `GlassCapabilities` 探测（ADR-0008、
   03 §6 分级矩阵）；低档位 Canvas 降级不阻塞播放、不改变时间轴。
3. **core-splashscreen 无缝交棒**：`installSplashScreen()` + `Theme.Horn.Starting`
   （parent Theme.SplashScreen、postSplashScreenTheme=Theme.Horn、深浅双份底色自适应）；
   `setKeepOnScreenCondition` 仅等首帧就绪即交棒，严禁人为拉长系统 Splash、严禁双开屏闪烁。
4. **四幕时间轴**：WAKE 0–160（剪影 0.62→0.72、opacity 0→1）、FOCUS 160–500（双光环错峰 60ms、
   镜面高光扫过、`#2E5F86` → `#FFB547` 能量渐变渗入）、BLOOM 500–820（球体 0.72→1.0 微过冲落位、
   双琥珀声压弧 %1 相位循环、星芒 glint ×3、字标字距落定；t=560 单次轻触觉）、MERGE 820–1040
   （t=820 一次性 reveal 闩锁、主界面错峰 40ms SnappySpring 浮现、覆盖层 scale 1.0→1.04 + fade out
   后彻底移出组合树）。逐元素公式见 docs/spec/03-ux-interaction-and-haptics.md §11.1。
5. **状态机与绕过**：`StartupState = Playing | Completed | Skipped | Bypassed`；
   `StartupCoordinator.skip()`（幂等）/`complete()`/`playbackAllowed(coldStart, quickAction, reducedMotion)`；
   进程级单次守卫 `StartupPlaybackGuard`；绕过优先级 QUICK_ACTION > WARM_START > REDUCED_MOTION，
   Widget/Quick Tile/DeepLink 快速入口 0ms 直达。
6. **点击跳过**：任意区域按下即触发（按下即跳、不等抬手）；≤120ms 阻尼弹簧收起（scale 0.96 +
   alpha→0）后移除，跳过后零遮罩残留；底部「轻触跳过 / Tap to skip」提示（中英文 + TalkBack）。
7. **静音与性能红线**：全程零音频；仅 t=560 一次轻触觉（无马达/系统触觉关闭时静默降级）；
   `elapsedProvider: () -> Float` 延迟读取（I7）、渐变/噪点/星芒一次预计算、UI 文字 ≤60ms 节流、
   结束后覆盖层移出组合树（I8 同族）。

## 正负面影响与权衡

正面影响：

- 紧急鸣笛可达性有三条兜底：快速入口 0ms 直达、任意区域按下跳过（≤120ms 收起）、热启动不重播；
  1040ms 品牌时刻只存在于冷启动一次性路径。
- 包体零第三方动画运行时；视觉全部为自有公式，可解释、可调、可离线复算。
- f(t) 纯函数 + 冻结时钟注入 → Roborazzi 定格帧逐字节确定（I10），既有 80 张基准零影响
  （覆盖层挂载于 MainActivity 层，HornScreen 截图天然隔离）。
- I7 零重组与 I8 同族零残留同时落实；跳过语义由状态机单测 + Compose UI 测试双重锁定。

负面影响与权衡：

- **1040ms 时长 vs 紧急鸣笛效率**：品牌节奏与"越快按到喇叭越好"存在天然张力；以预算带 800–1150ms
  封顶 + 三条兜底路径换取品牌时刻，热路径（热启动/快速入口）零开销。
- **AGSL/Canvas 分级**：API 33+ 的 AGSL 观感在低档位降为 Canvas，两档视觉差异已接受（与 03 §6
  分级矩阵一致），换取零第三方依赖与无头确定性。
- **跳过弹簧 120ms 上限**：收起速度优先于回弹质感——可达性 > 观感；120ms 是硬上限而非目标值。
- **reveal 闩锁一次性语义**：t=820 发出后不可撤销、不重复；t≥820 的跳过仅加速覆盖层收起，不回滚
  reveal（主界面接棒不可逆），换取转场无闪烁、无状态回跳。
- **自绘维护成本**：新增视觉元素需手写绘制原语（对比 Lottie 拖入即用）；以 StartupTimeline 公式
  单点、冻结时钟定格测试与 Roborazzi 基准压低漂移成本。
- **触觉克制**：全程仅 t=560 一次轻触觉，品牌"打击感"弱于多段触觉编排，换取静音红线的绝对性。

## 源码验证路径

现状实现（真实文件）：

- 时间轴常量/公式与绘制原语（StartupTimeline、smoothstep、settle、WakeSceneBrushes、drawWakeScene）：core/designsystem/src/main/java/com/industrial/horn/ui/theme/StartupWake.kt
- 状态机/绕过矩阵/进程级守卫（StartupState、StartupBypassReason、StartupPlaybackGuard、StartupCoordinator）：feature/horn/src/main/java/com/industrial/horn/presentation/StartupCoordinator.kt
- Splash 主题（Theme.Horn.Starting → Theme.Horn，深浅双份）：app/src/main/res/values/themes.xml、app/src/main/res/values-night/themes.xml
- core-splashscreen 依赖声明：app/build.gradle.kts
- 弹簧预设（SnappySpring/TactileOrbSpring）：core/designsystem/src/main/java/com/industrial/horn/ui/theme/AppleMotion.kt
- 覆盖层挂载与窗口入口：app/src/main/java/com/industrial/horn/MainActivity.kt

契约规划路径（实现并行落地中，按下列落点接入；截至 2026-11-22 尚未入库，以非反引号标注）：

- 覆盖层 Composable、点击跳过手势与「轻触跳过」提示：feature/horn/src/main/java/com/industrial/horn/ui/StartupOverlay.kt
- installSplashScreen() 与 setKeepOnScreenCondition 首帧就绪交棒接线（MainActivity 内）
- t=820 reveal 闩锁触发与主界面错峰 40ms 浮现接线（MainActivity/HornScreen 容器层）
- 测试三件套：StartupCoordinator 状态机单测、Compose 跳过语义测试、Roborazzi 定格帧 t=300/650/900 × 深浅主题

规约真源：docs/spec/03-ux-interaction-and-haptics.md §11（四幕时间轴、点击跳过契约、绕过矩阵、
Splash 衔接、静音红线、性能契约、测试隔离、Non-Goals）；弹簧与渲染分级沿
[ADR-0008](ADR-0008-apple-glass-design-system-and-spring-motion.md)。

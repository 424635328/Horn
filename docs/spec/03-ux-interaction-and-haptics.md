# 03 · 交互状态机、座舱视觉规范与触觉规约

- 状态：Living Spec
- 日期：2026-11-23

> 关联 ADR：[ADR-0011 精密汽车座舱：材质、仪表几何与主题三挡](adr/ADR-0011-precision-automotive-cockpit.md)
> （取代 [ADR-0008](adr/ADR-0008-apple-glass-design-system-and-spring-motion.md)，状态 Superseded）
>
> 本文约束 `:feature:horn` 的手势与安全交互、`:core:designsystem` 的座舱设计 Token 与仪表几何、
> `:core:haptic` 的触觉指纹。修改任一契约 MUST 同步更新本文与相关测试。

## 1. 主按钮指针手势状态机

实现：`feature/horn/src/main/java/com/industrial/horn/ui/HornInstrumentPad.kt`（`awaitEachGesture` + 双 `pointerInput`）。
圆形热区判定为几何圆（`isInsideCircle`），透明角落 MUST NOT 触发鸣笛。

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Honking: awaitFirstDown(按下) 且 preferencesLoaded
    Honking --> Modulating: 垂直滑动(Δexpression > 0.02)
    Modulating --> Modulating: 持续滑动, expression ∈ [-1, 1]
    Honking --> Idle: waitForUpOrCancellation == up(松手) → onPressEnd
    Modulating --> Idle: 松手 → expression 归零 → onPressEnd
    Honking --> Cancelled: 手指移出圆形热区(Initial pass 消费 move)
    Modulating --> Cancelled: 手指移出圆形热区
    Cancelled --> Idle: onPressCancel 立即止音
    Idle --> Idle: 切换音色 / 退后台 / 失焦 → panicStop 兜底
```

硬性契约：

- 按下 MUST 同步 `onPressStart` + `onExpressionChanged(0f)`（起音从中性表达开始）。
- 垂直位移映射：`expression = clamp((centerY - y) / (0.38 × height), -1, 1)`，增量小于 0.02 MUST NOT 产生事件（去抖）。
- 横向/任意方向移出圆 MUST 立即 `onPressCancel`（由 `waitForUpOrCancellation` 返回 null 触达），MUST NOT 等到抬手。
- 一切终止路径（含协程取消/组合销毁）MUST 走 `finally` 兜底：`onPressCancel` + 状态复位（防"幽灵卡音"）。
- 松手 MUST 先归零 expression 再止音，避免尾音弯音残留。

## 2. Expression 连续调制契约

实现：`core/audio/src/main/java/com/industrial/horn/audio/dsp/ExpressionModulator.kt`。

| 参数 | 范围/系数 | 说明 |
|---|---|---|
| 表达输入 | `-1f .. +1f`，默认 `0f` | 手势层钳制 |
| 音高弯音 | `±8%`（`MAX_PITCH_BEND = 0.08`） | `pitchScale = 1 + current × 0.08` |
| 饱和驱动 | `+35%`（`DRIVE_RANGE = 0.35`） | 上限方向增强谐波亮度 |
| 气流噪声 | `+60%`（`NOISE_RANGE = 0.6`） | 阀门通气量的听感近似 |
| 激波铜管亮度（v3.6） | `beta = 0.05 + 0.30·max(0,e)` | Brassiness 幅度依赖分数延迟驱动度 |
| 激波不对称度（v3.6） | `alpha = 0.10 + 0.15·max(0,e)` | 带限非对称整形预级 |
| 平滑系数 | `SMOOTHING = 0.0025`/采样 | 逐采样指数平滑，禁 zipper 噪声 |

> v3.6 起，Expression 同时驱动**气压（音高微滑）**与**激波铜管亮度（Brassiness）**：上滑＝踩下气阀，
> 音色随气压上升变亮、变陡（见 `docs/spec/research/01-horn-physical-modeling.md` §3 与冻结契约 §3 常量表）。

## 3. 防呆与护耳交互规范

实现：`core/audio/src/main/java/com/industrial/horn/audio/AudioSafetyState.kt`（`AudioSafetyPolicy` 纯策略）、
`core/audio/src/main/java/com/industrial/horn/audio/AndroidAudioSafetyMonitor.kt`、
`feature/horn/src/main/java/com/industrial/horn/ui/SafetyUi.kt`、
`feature/horn/src/main/java/com/industrial/horn/presentation/HornViewModel.kt`。

1. **零音量智能引导**：按压时媒体音量 == 0 → 状态轨下方展开 `zero_volume_prompt` 警示条（文案见
   `feature/horn/src/main/res/values/strings.xml`），一键 `AudioSafetyPolicy.sixtyPercentVolume(max)` 恢复 60%；点击或恢复后警示条即时移出组合树。
2. **耳机护耳限幅**：`earProtectionActive = headphonesConnected && volumeFraction > 0.7` 成立时，
   引擎侧下发保护增益（≈ −6 dB，`PROTECTION_GAIN`），状态轨右端展示 `ear_protection_badge` 徽章。摘下耳机 MUST 自动解除。
3. **信息优先级**（单一状态轨 + 单一警示条，禁多弹窗）：警示条（零音量）> 状态轨徽章（护耳）> 状态轨状态令牌（引擎/声场/采样率）。
4. 音量监听经 `AudioDeviceCallback`（`AndroidAudioSafetyMonitor.kt`）；策略计算纯函数化以便单测（`AudioSafetyPolicyTest`）。
5. **心理声学输出模式感知（v3.6）**：耳机检测结果 MUST 同步驱动引擎输出级——耳机连接 →
   `OutputMode.HEADPHONE_FULL_RANGE`（全频双耳宽度链），未连接 → `OutputMode.SPEAKER_VBS`
   （外放虚拟低音链）；模式经 `HornSession.setOutputMode` 下发（20–50ms 交叉淡化），并回显于
   Debug HUD（`Speaker VBS` / `Headphone Full-Range`）、遥测 `AudioTelemetry.outputMode` 与座舱遥测面板（`OUTPUT` 行）。
6. **声场四档（v3.6）**：`AcousticSpaceSelector` 为一行四个 `TactileKeycap`
   （街道 / 隧道 / 旷野 / 地库），新增 `GARAGE(storageKey="garage")`；文案键
   `acoustic_space_garage`（`values`/`values-zh`）；旧数据未知键 MUST 回落 `STREET`。

## 4. 物理音量键拦截契约

实现：`app/src/main/java/com/industrial/horn/MainActivity.kt#dispatchKeyEvent`。

- 开关关闭（默认）：MUST 完全放行 `KEYCODE_VOLUME_UP/DOWN`，系统音量行为零改变。
- 开关开启：`ACTION_DOWN && repeatCount == 0` 起音；重复 DOWN 仅消费不重触发；`ACTION_UP` 止音；其余事件放行。
- 其他按键 MUST 永远放行。开关状态由 `:core:data` 持久化（`volumeTriggerEnabled`）。

## 5. Precision Cockpit 设计 Token（`:core:designsystem`）

实现：`core/designsystem/src/main/java/com/industrial/horn/ui/theme/` 下的
`Color.kt`、`Type.kt`、`Theme.kt`、`Motion.kt`、`CockpitCanvas.kt`、`MachinedPanel.kt`、
`TactileKeycap.kt`、`InstrumentDial.kt`、`PrecisionExpressionGauge.kt`、`InstrumentSlider.kt`。

### 5.1 双材质色板（v4.0 冻结）

| Token | 语义 | Ceramic Daylight | Stealth Carbon |
|---|---|---|---|
| `canvas` | 全屏基底 | `#F3F3EE` | `#0D0F12` |
| `grid` | 工程网格刻线 | `#E2E2DA` | `#1C2027` |
| `panel` | 标准面板 | `#FFFFFF` | `#16191F` |
| `panelRaised` | 凸起面板 / 仪表中心盘 | `#FFFFFF` | `#1D2129` |
| `panelSunken` | 凹陷面板 / 滑槽 | `#EAEAE4` | `#101318` |
| `hairline` | 面板发丝边 | `#DFDFD8` | `#2C313C` |
| `hairlineStrong` | 刻度/交互键边框 | `#C4C4BC` | `#3C4250` |
| `textPrimary` | 主文本 | `#141619` | `#F4F6F9` |
| `textSecondary` | 次文本 / 刻度数字 | `#626770` | `#8C94A3` |
| `textTertiary` | 微标签 / 未点亮刻度 | `#6F747C` | `#7C8493` |
| `dialTop` / `dialBottom` | 车削盘面 | `#F0F0E9` / `#D5D5CD` | `#2C333D` / `#12151A` |
| `bezelTop` / `bezelBottom` | 表圈刻度带 | `#EDEDE6` / `#E0E0D8` | `#2A323D` / `#1E242C` |
| `fault` | 故障态 | `#C62E20` | `#FF6A5A` |
| `protection` | 护耳态 | `#8A5A00` | `#FFC24D` |

色板只在 `Color.kt` 定义一次，并通过 `LocalHornColors`（`staticCompositionLocalOf`）下发；
Material 3 `ColorScheme` 同步映射到同一组 canvas/panel 值，保证 Material 原生组件与自绘仪表同材质。

### 5.2 对比度门禁（机器执法）

`app/src/test/java/com/industrial/horn/ui/theme/CockpitPaletteTest.kt` 用 WCAG 2.2 相对亮度公式断言：

- `textPrimary` / `textSecondary` 在 canvas、panel、sunken 上 ≥ **4.5:1**；
- `textTertiary` 在面板上 ≥ 4.5:1、在 canvas 上 ≥ 3:1（微标签属于装饰性小字，但不允许低于大文本阈值）；
- 5 个音色强调色在各自面板与 canvas 上 ≥ 4.5:1；
- `fault` / `protection` 在两种表面上 ≥ 4.5:1；
- **正控**：黑/白 = 21.0:1（±0.05）；**负控**：品牌琥珀 `#FFB547` 在白底仅 1.76:1，必须被判红，
  否则「全绿」可能只是公式失效。

### 5.3 五音色仪表强调色（深浅双值）

单一中调无法同时在白面板与碳黑面板过 4.5:1，因此每个音色给出成对值，由 `InstrumentAccent.resolve(dark)` 解析：

| 音色 | Daylight | Carbon |
|---|---|---|
| Car | `#A85B00` | `#FFB547` |
| Train | `#B3201A` | `#FF8A66` |
| E-Bike | `#0F766E` | `#43E3C0` |
| Motorcycle | `#1D4ED8` | `#82B8FF` |
| Custom / Lab | `#6D28D9` | `#C9A6FF` |

映射入口：`:feature:horn` 的 `HornType.visualStyle(dark)`（保持 `:core:designsystem` 零项目依赖）。
五色在 sRGB 空间两两距离 MUST ≥ 48（`CockpitPaletteTest` 断言），避免"五个音色两个颜色"。

### 5.4 圆角与动效（`HornRadii` / `HornMotion`）

圆角是机加工尺度，不是消费级胶囊：`Plate = 16dp`、`Instrument = 12dp`、`Key = 9dp`、
`Chip = 7dp`、`Well = 5dp`；嵌套仍遵守 `R_outer = R_inner + padding`（`HornRadii.outerOf`）。

| 预设 | dampingRatio / stiffness | 用途 |
|---|---|---|
| `KeySpring` | 0.90 / 1500 | 键帽/主盘按压（短行程、无明显回弹） |
| `IndexSpring` | 0.88 / 1050 | 选择指示片位移 |
| `NeedleSpring` | 0.62 / 520 | 表针（允许一次物理过冲） |
| `PanelSpring` | 0.92 / 620 | 面板/工坊展开 |
| `RailSpring` | 1.00 / 900 | 警示条淡入淡出 |

交互态 MUST NOT 使用线性 `tween`（颜色过渡除外，颜色不是位移）。

### 5.5 仪表几何（冻结比例，`GaugeGeometry`）

| 元素 | 半径比例 | 规格 |
|---|---|---|
| 车削盘面 | `0.858 R` | 径向金属渐变 + 交替车削环 + 72 齿滚花边 + 4 颗紧固螺钉 |
| 表圈刻度带 | `0.862–0.925 R` | 240° 刻度：每 10° 一小格、每 60° 一大格 |
| 压力环 | `0.932–1.000 R` | **60 格** RMS 压力刻度，每 5 格加重；点亮格数 `= round(rms × 60)` |
| 中心盘 | `0.46 D` 直径 | 不透明，覆盖表针轴心；承载图标 / 标签 / `EXP` 读数 |
| 表针 | 尾 `-0.20 R` → 尖 `0.905 R` | 2dp 主色 + 4dp 深色描边保证浅色盘面对比 |

Expression 角度：`angle = 150° + (expression + 1)/2 × 240°`，即 0 指正上方（270°），−1 → 150°、+1 → 30°；
正向半圈带一条细强调弧（z 序在刻度之下），读数 MUST 为直连（禁指针缓动滞后于手指）。

### 5.6 组件契约

| 组件 | 行为契约 |
|---|---|
| `CockpitCanvas` | 工程网格 + 四角卡尺标记 + 暗角；网格 Path 在 `drawWithCache` 内一次构建；仅鸣笛时叠加扫描带（`State<Float>` 只被 draw 读取，不进组合层） |
| `MachinedPanel` | 1dp 高光缝 + 1dp 底缝 + 发丝边 + 四角刻度 + 可选强调索引条；`raised` / `sunken` 两态 |
| `TactileKeycap` | 键帽：选中=强调色边框 + 强调色标签 + 2dp 索引条（三重冗余，非仅颜色）；按压 0.97 行程；语义 `Role.RadioButton` + `Selected` + 每项 testTag |
| `InstrumentToggle` | 机械拨杆开关：`toggleableState` + `stateDescription`，默认关闭即系统音量键行为零改变 |
| `InstrumentDial` | 车削盘面 + 压力环 + 表圈 + 中心盘；`rmsProvider`/`expressionProvider` 只在 draw 内读取 |
| `PrecisionExpressionGauge` | 240° 动圈刻度 + 直连表针 + 正向强调弧；无自身动画 |
| `InstrumentSlider` | 凹陷滑槽 + 印刷刻度 + 硬边填充 + 滚花滑钮；拖动/点击均可赋值；数值 MUST 用 `HornText.Data`（tnum） |

### 5.7 主题三挡与系统栏极性

- `ThemeMode { SYSTEM, LIGHT, DARK }` 持久化于 DataStore，键值 `system|light|dark`（稳定字符串，
  `fromStorageKey` 对未知键回落 `SYSTEM`）；`ThemeMode.next()` 为 AUTO → DAY → NIGHT 三挡循环。
- 顶栏主题键：单击循环（`onCycleThemeMode`）；长按或 TalkBack 自定义动作打开三挡菜单
  （`onSelectThemeMode`），保证偏好不依赖隐藏手势。
- `MainActivity` 解析 `useDarkTheme` 后 MUST 同步 `WindowCompat.getInsetsController`
  的 `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`（edge-to-edge 下系统栏透明，
  图标极性只能跟随解析后的主题，不能跟系统设置）。

## 6. 渲染基线与窗口形态

| 能力 | 全部 API 26–36 |
|---|---|
| 面板/仪表材质 | 纯色填充 + 线性/径向渐变 + 1px 描边，无模糊、无 AGSL、无 `RenderEffect` |
| 3D 观感 | 高光缝 / 底缝 / 暗角 / 车削环（几何暗示，不是真实景深） |
| 动效 | Compose spring + 单时钟 f(t)，无能力分级 |

v4.0 显式移除毛玻璃与背景虚化：视觉不再依赖 `RuntimeShader`/`RenderEffect` 探测，
同一套几何在模拟器、无头 JVM 与真机上逐像素一致，Roborazzi 基准不再因 GPU 能力分叉。

### 6.1 窗口形态断点（WindowSizeClass）

| 形态 | 布局 | 实现 |
|---|---|---|
| Compact（竖屏单手） | 顶栏（品牌 + 主题键 + 工坊键）+ 状态轨 + 仪表盘 + 底部机加工控制台 + 脚注 | `HornScreen` `compact_layout` |
| Medium / Expanded（横屏/平板/折叠展开） | 左：音色 + 声场控制台 + 遥测仪表盘；右：超大仪表盘 | `HornScreen` `expanded_layout` |

大屏 MUST 零遮挡、零挤压；1.35× 无障碍大字体 MUST 零裁切（`ScreenshotMatrixTest` 1.35x 档覆盖 80 张基准；仪表状态由 `InstrumentDialSnapshotTest` 的 6 帧覆盖）。

## 7. 触觉指纹规约（`:core:haptic`）

实现：`core/haptic/src/main/java/com/industrial/horn/haptic/HapticPattern.kt`、`AndroidHornHaptics.kt`。
波形时序 off/on 交替（首拍 off）；振幅数组仅在支持振幅控制的设备生效，否则走
`fallbackTimingsMs` 节奏降级；无马达设备为 No-Op。API 31+ 经 `VibratorManager` 取默认马达，以下走
`VIBRATOR_SERVICE` 降级。

| 车型 | 波形（ms） | 振幅 | 意图 |
|---|---|---|---|
| Car | `1,24,80,12,50,9,55,11` | 190→50 递减 | 平稳中强持续脉冲 |
| Train | `1,120,100,50,110,45,90,55` | **255**→120 | 最高振幅低频重脉冲（120ms 首拍） |
| E-Bike | `1,7,23,5,19,4,21,3` | 70→40 | 轻量 7ms 高频轻点 |
| Motorcycle | `1,18,30,10,26,8,28,6` | 150→60 | 18ms 颗粒机械脉冲 |
| Custom | `1,10,28,6,20,6,18,5` | 125→50 | 中性克制节奏 |

约束：`HapticPattern` 构造期 MUST 校验时序为正、振幅 ∈ [0,255] 且含非零脉冲（`require`）；
`HapticSignatureTest` 锁定指纹差异性。`TactileKeycap` 的选择刻度 SHOULD 使用
`CLOCK_TICK` 类细粒度反馈（由宿主 `:core:haptic` 注入）。

## 8. 零重组延迟读取规范（性能红线）

- RMS 包络、Expression 位移、按压行程、扫描带相位 MUST 经 `() -> Float` Provider 或
  `State` 在 `drawWithCache`/`drawBehind`/`graphicsLayer` 内读取；MUST NOT 在高频更新时触发重组。
- 全屏网格 Path MUST 在 `drawWithCache` 内按尺寸构建一次；动画值 MUST 以 `State<Float>` 形式传入
  draw 层（禁在组合层解包，否则 `drawWithCache` 会被逐帧重建）。
- `rememberUpdatedState` 用于保持 Provider 新鲜而稳定（`HornScreen` 的 `rmsProvider`/`expressionProvider`）。
- **稳定回调身份**：`HornScreen` 传入深层子树（`InstrumentZone`/`CockpitDeck` 等）的回调 MUST 以
  `remember { { currentTarget() } }` 包装（目标经 `rememberUpdatedState` 转发），使父级重组
  （如遥测节拍）不会因 lambda 身份变化击穿子树的可跳过性（skippability）。
- 文本（`EXP` 读数、字标字距）MUST 以 60ms 定时器采样更新：改字符串是排版操作，不属于帧时钟；
  其余纯视觉属性仍逐帧。

## 9. 非目标（Non-Goals）

- 不做自定义手势录制/宏；单按钮交互是产品灵魂，MUST NOT 复杂化。
- 不做实时 FFT 频谱显示（时域示波器已满足调试需求）。
- 不做毛玻璃 / 背景虚化 / 半透明堆叠（v4.0 明确移除，见 ADR-0011）。

## 10. 已知局限与权衡（Known Limitations & Trade-offs）

- 1px 发丝线与刻度在极低 dpi 设备上可能被渲染为 1.5–2px 灰边；以 `hairlineStrong` 与 ≥4.5:1 文本兜底可读性。
- 车削环 / 滚花 / 螺钉是几何暗示而非真实金属反射；换取零 GPU 依赖与跨设备逐像素一致。
- `FontFamily.Monospace` 在个别 OEM 字体下回退为比例数字；因此每个数值样式同时声明
  `fontFeatureSettings = "tnum"`，即使字体回退也保持等宽数字。
- Roborazzi 基准对系统字体光栅化敏感，跨平台差异 MUST 走 `update-screenshots.yml` Linux 重录 SOP（见 05 规约）。

## 11. 开屏动画与启动体验（Cold-Start Calibration, v4.1；原 Optical Wake v3.7）

> 关联决策：[ADR-0010 开屏动画：单时钟自绘与无缝交棒](adr/ADR-0010-startup-optical-wake-animation.md)、
> [ADR-0011 精密汽车座舱](adr/ADR-0011-precision-automotive-cockpit.md)（v4.0 换材质，v4.1 换视觉词汇）。
> 冷启动一次性品牌时刻：扫描线底 + 四角 reticle + boot 日志 + 表盘点亮 + 刻度与雷达环扫入 + 光标式字标
> 擦除 + **上下遮片合拢的中线交棒**。它是一段「仪器开机自检」，不是 logo 动画。

### 11.1 四幕时间轴（单时钟 t = elapsed ms）

总时长 ≈ 1040ms（预算带 800–1150ms）；一切视觉状态均为 t 的纯函数 f(t)，任意时刻可定格复现。

| 时刻 | 阶段 | 元素 | 入出公式 |
| --- | --- | --- | --- |
| 0–160 | WAKE 唤醒 | 表盘剪影 + 四角 reticle | `scale = 0.62 + 0.10·smoothstep(0, 160, t)`；`alpha = smoothstep(0, 160, t)`；`reticle = smoothstep(0, 220, t)`（臂长 26dp × reticle） |
| 160–300 | FOCUS 聚焦 | boot 日志逐行浮现 | `line_k = smoothstep(180+300k, 320+300k, t)`，k ∈ {0,1,2}（180/480/780 ms 起） |
| 160–500 | FOCUS 聚焦 | 60 格刻度顺时针扫入 | `ring = smoothstep(160, 460, t)`，可见格数 `⌊ring × 60⌋`；每 5 格加长、每 15 格强调色 |
| 160–500 | FOCUS 聚焦 | 雷达虚线环旋转 | `dash = smoothstep(160, 500, t)` 控制出现；`rotation = 0.010·(t − 160)°`（慢速，非装饰性抖动） |
| 160–560 | FOCUS 聚焦 | 斜向光扫横穿一次 | `scan = smoothstep(160, 560, t)`；光带位移 `x = (−0.45 + 1.9·scan)·W`，alpha `1 − |0.5 − scan|·1.2` |
| 160–500 | FOCUS 聚焦 | 表圈高光弧 + 十字准星 | `focus = smoothstep(160, 500, t)` 驱动 34° 高光弧旋转；准星臂长 1.58R→1.78R |
| 500–820 | BLOOM 绽放 | 表盘落位 | `scale = 0.72 + 0.28·settle(smoothstep(500, 820, t))`（同源欠阻尼微过冲 ≈12%，精确落位 1.0） |
| 500–820 | BLOOM 绽放 | 12 路声压喷口 | `ventPhase = ((t−500)/260) % 1`，第 k 路相位 `(ventPhase + k/12) % 1`，半径 `1.27R → 1.61R`，alpha `(1−p)·0.45` |
| 500–820 | BLOOM 绽放 | 校准点 ×3 | `glint_i = smoothstep(t_i, t_i+60, t) × (1 − smoothstep(t_i+120, t_i+260, t))`，`t_i = 520+85i` |
| 540–820 | BLOOM 绽放 | 字标擦除入场 | `reveal = smoothstep(540, 820, t)`；`clipRect(right = W·reveal)` 擦除 + 18dp 上浮；**字距静态 6sp，禁做动画** |
| t=560 | BLOOM 绽放 | 轻触觉 ×1 | 单次轻触觉脉冲（`HAPTIC_MS = 560`；无马达/系统触觉关闭 → 静默降级） |
| 820–1040 | MERGE 融合 | reveal 闩锁 | t=820 一次性发出；主界面自转场下层接棒、错峰 40ms `KeySpring` 浮现 |
| 820–1000 | MERGE 融合 | 上下遮片向表盘轴线合拢 | `shutter = smoothstep(820, 1000, t)`；上片高 `(0.42H − gap)·shutter`、下片高 `(0.58H − gap)·shutter`，边缘 1dp 接缝 + 12dp 内阴影 |
| 930–1040 | MERGE 融合 | 一次性闪白 | `flash = smoothstep(930, 985, t) × (1 − smoothstep(985, 1040, t))`，叠加 `accent@12%` |
| 1000–1040 | MERGE 融合 | 中线收窄 + 覆盖层淡出 | `line = 1 − smoothstep(1000, 1040, t)`；中线半宽 `0.26W·(1 − 0.55·shutter)·line`；`alpha = 1 − smoothstep(980, 1040, t)`；t=1040 彻底移出组合树 |

公式语义（全部为 t 的纯函数，禁 setTimeout/多时钟排程）：

- `smoothstep(a, b, x) = s²(3-2s)`，其中 `s = clamp((x-a)/(b-a), 0, 1)`——全片唯一缓动原语（C¹ 连续段）。
- `settle(p)`：确定性解析欠阻尼阶跃（ζ=0.55、ω=9，与 `KeySpring` 同源的机加工手感），微过冲后精确收敛 1.0；
  属里程碑曲线推导，不引缓动库。
- `% 1`：相位循环取小数部分（声压喷口发射节律）。
- 时间轴常量（1040/160/500/820/560/120/40/1000/930/985/980）单点集中于 `StartupTimeline`（`core/designsystem/src/main/java/com/industrial/horn/ui/theme/StartupWake.kt`）。

### 11.1.1 视觉词汇（boot 序列）

| 元素 | 规格 | 为什么 |
| --- | --- | --- |
| 扫描线底 | 每 3dp 一条 1px 横线，透明度 0.04–0.05 | 让整屏读作「显示器」而不是「壁纸」 |
| 四角 reticle | 26dp 内缩、26dp 臂长、1.2dp 描边，左上角一个强调色刻度 | 取景器/仪表的固定语法，同时给出画面边界 |
| boot 日志 | 等宽 9sp、字距 2sp、三行错峰 300ms，公开事实（`ACOUSTIC CORE` / `48 kHz · 2CH` / `DIAL 60`） | HUD 叙事；不使用任何无法核实的「系统消息」 |
| 序列标记 | 右上 `SEQ 1.04 s` 静态文本 | 时间码若逐帧刷新会不停触发布局；静态令牌同样传达「一段固定流程」 |
| 同步进度条 | 底部 168dp，1px 轨 + 强调填充 + 820ms 闩锁刻度 | 把「还有多久可以操作」可视化，替代不可读的百分比 |
| 雷达虚线环 | 48 段、1px、强调色 22%、`0.010°/ms` 慢速旋转 | 静态刻度 + 慢旋转＝「在扫描」，不引入噪声粒子 |
| 字标 | 26sp 粗体、字距 6sp 静态、`clipRect` 擦除、下挂 96dp 强调细线 | 字距动画会逐帧重排版，属明确禁止项（见 skill 的「字距不做动画」） |
| 交棒 | 上下遮片合拢（表盘轴线）+ 1dp 接缝 + 12dp 内阴影 + 中线收窄 + 一次性闪白 | 「关机/合拢再交棒」比淡出更像机械动作，也避免与主界面争抢注意力 |

### 11.1.2 降级（reduce motion）

- 系统动画时长为 0 或 `LocalReducedMotion` 为真：`StartupCoordinator` 直接 `Bypassed(REDUCED_MOTION)`，覆盖层不出现。
- 覆盖层内部同样有兜底：`LocalReducedMotion` 为真时以 `t = TOTAL_MS` 渲染（终态静态帧），不启动内部时钟，也不播放任何动画。

### 11.2 点击跳过契约

- 触发：覆盖层任意区域 `detectTapGestures` 按下即触发（不等抬手、不判长按）→ `StartupCoordinator.skip()` → `Skipped`。
- 收起：≤120ms 阻尼弹簧（scale 1.0→0.96 + alpha→0）后覆盖层移除；120ms 是硬上限而非目标值。
- 红线：跳过后 MUST NOT 残留任何可拦截鸣笛手势的遮罩——`horn_button` 立即可按下（Compose UI 测试锁定）。
- 幂等：`skip()` 仅 `Playing → Skipped`，重复调用与终态调用均无操作。
- 与 reveal 的关系：t=820 闩锁发出后，跳过仅加速覆盖层收起，MUST NOT 撤销已发出的 reveal（一次性语义）。
- 提示：底部极低调「轻触跳过 / Tap to skip」，中英文文案 + TalkBack `contentDescription`；不遮挡主视觉。

### 11.3 智能绕过矩阵

| 场景 | 判定 | 结果 | 时延 |
| --- | --- | --- | --- |
| Widget / Quick Tile / DeepLink 快速入口 | `startedFromQuickAction` | `Bypassed(QUICK_ACTION)` | 0ms 直达主界面 |
| 热启动（退后台返回 / 旋转 / 主题切换 / 折叠展开） | `!coldStart`（进程守卫已置位） | `Bypassed(WARM_START)` | 0ms |
| 系统减弱动态效果 / 动画时长缩放为 0 | `LocalReducedMotion` 或 `ANIMATOR_DURATION_SCALE == 0` | `Bypassed(REDUCED_MOTION)` | 0ms |
| 冷启动且非快速入口且允许动效 | `playbackAllowed(coldStart, quickAction, reducedMotion)` 且 `StartupPlaybackGuard` 认领成功 | 播放（`Playing`） | 自 t=0 走表 |

- 优先级：QUICK_ACTION > WARM_START > REDUCED_MOTION（同时命中取高优先项归因）。
- 仅冷启动播放：进程级单次守卫 `StartupPlaybackGuard` 保证同一进程至多播放一次；播放或绕过后 MUST NOT 复播。

### 11.4 启动状态机

```mermaid
flowchart TD
    Cold["冷启动判定"] -->|"playbackAllowed == true 且 Guard 认领成功"| Playing["Playing 播放"]
    Cold -->|"QUICK_ACTION / WARM_START / REDUCED_MOTION"| Bypassed["Bypassed 绕过"]
    Playing -->|"complete()（t=1040 自动收尾）"| Completed["Completed 完成"]
    Playing -->|"skip()（任意区域按下，幂等）"| Skipped["Skipped 跳过"]
    Completed -->|"覆盖层移出组合树"| Done["移除覆盖层"]
    Skipped -->|"≤120ms 阻尼弹簧收起"| Done
    Bypassed -->|"0ms 直达主界面"| Done
```

- `StartupState = Playing | Completed | Skipped | Bypassed`（`StartupBypassReason = QUICK_ACTION | WARM_START | REDUCED_MOTION`）。
- API 契约：`StartupCoordinator.skip()`（幂等）、`complete()`（幂等）、`playbackAllowed(coldStart, quickAction, reducedMotion)`
  （冷启动 ∧ 非快速入口 ∧ 允许动效）。
- 终态（Completed/Skipped/Bypassed）均为吸收态：不再接受跳过/完成调用，覆盖层随后移出组合树。

### 11.5 Android 12+ SplashScreen 无缝衔接

- 依赖官方 androidx.core:core-splashscreen；`MainActivity.onCreate` 首行 `installSplashScreen()`（契约规划接线）。
- `Theme.Horn.Starting`（parent `Theme.SplashScreen`、`postSplashScreenTheme=Theme.Horn`）深浅双份已落地：
  `app/src/main/res/values/themes.xml` 与 `app/src/main/res/values-night/themes.xml`；`windowSplashScreenBackground`
  对齐窗口底色，系统 Splash → Optical Wake 零色跳、零白闪。
- `setKeepOnScreenCondition` 仅等首帧就绪（覆盖层第 0 帧预热完成）即交棒；MUST NOT 人为拉长系统 Splash；
  严禁系统 Splash 与 Optical Wake 双开屏闪烁。
- 应用层时间轴自交棒时刻起算 t=0；系统 Splash 图标动画不并入四幕时间轴（两层时间轴解耦）。

### 11.6 静音红线

- 全程零音频：MUST NOT 播放任何启动音/品牌音，音频引擎 MUST NOT 因开屏启动。
- 全程仅 t=560 一次轻触觉（轻量 CLOCK_TICK 类）；无震动马达或系统触觉关闭时静默降级——无任何输出、无替代提示。
- 触觉经 `:core:haptic` 既有安全链路下发（无马达设备 No-Op，见 §7）。

### 11.7 性能契约

- 单时钟：一切视觉 = f(t)（t = elapsed ms 唯一时钟）；MUST NOT 以 setTimeout/多动画时钟排程。
- 延迟读取（I7）：`elapsedProvider: () -> Float` 在 `DrawScope`/`graphicsLayer` 内读取，动画帧只重绘零重组；
  `rememberUpdatedState` 保持 Provider 新鲜而稳定。
- 预计算一次：扫描线 Path、渐变、三角函数表等逐帧素材以 `remember`/`CacheDrawScope` 一次预计算
  （`WakeSceneBrushes`），逐帧零分配。
- **文字零动画**：字标字距静态（擦除用 `clipRect`），boot 日志只改图层 alpha；覆盖层内没有逐帧排版的文本，
  因此「文字 ≤60ms」规则在此退化为「零文本重排」。
- 移出组合树（I8 同族）：`Completed`/`Skipped`/`Bypassed` 后覆盖层 MUST 彻底移出组合树，不留图层、不留无限动画。
- 测试注入：冻结时钟经 `elapsedProvider` 注入，任意时刻定格可复现（scrub/Roborazzi 免费）。
- **指标自报**：debug 构建在动画完成时输出 `startup frames: n / median / p95 / slow(>33.4ms)`
  （`StartupFrameMeter`，环形缓冲零分配；release 不输出、不落日志）。「流畅」不是结论，这行日志才是。

### 11.8 测试与 Roborazzi 隔离

- 状态机 100% 覆盖：Playing→Completed、Playing→Skipped（`skip()` 幂等重放）、旋转不重播（WARM_START）、
  QUICK_ACTION 绕过（0ms 直达）、REDUCED_MOTION 绕过、优先级 QUICK_ACTION > WARM_START > REDUCED_MOTION。
- Compose UI 测试：任意区域按下即跳过的语义；跳过后 `horn_button` 可交互（零遮罩残留）。
- Roborazzi 定格帧：**wake(120) / focus(300) / reveal(820) / shutter(920)** × 深浅双主题 = **8 帧**，
  冻结时钟注入保证逐字节确定性（`startup_wake_*` 命名空间）。
- 时间轴契约测试：`StartupTimelineTest` 断言各里程碑端点、单调性与闩锁前后关系（防止「小调」悄悄挪动时间轴）。
- 既有 80 张主界面基准不受影响：开屏覆盖层挂载于 `app/src/main/java/com/industrial/horn/MainActivity.kt` 层，
  HornScreen 截图天然隔离（I10 零回归）。

### 11.9 Non-Goals

- 不引入 Lottie/第三方动画库（包体与确定性红线，见 ADR-0010）。
- 不做联网预热/预取：开屏期零网络请求（冷启动延迟不可控）。
- 不做启动音效/品牌音（静音红线的绝对性）。
- 不做用户可配置的开屏时长或「跳过开关」：跳过 = 一次按下，不进设置。
- 不把系统 Splash 延长充当动画：其职责仅限无缝交棒。

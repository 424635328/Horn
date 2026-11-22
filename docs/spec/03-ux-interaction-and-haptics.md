# 03 · 交互状态机、视觉降级与触觉规约

- 状态：Living Spec
- 日期：2026-11-22

> 关联 ADR：[ADR-0008 Apple Glass 设计系统与弹簧动效](adr/ADR-0008-apple-glass-design-system-and-spring-motion.md)
>
> 本文约束 `:feature:horn` 的手势与安全交互、`:core:designsystem` 的 Apple Glass 设计 Token 与渲染分级、
> `:core:haptic` 的触觉指纹。修改任一契约 MUST 同步更新本文与相关测试。

## 1. 主按钮指针手势状态机

实现：`feature/horn/src/main/java/com/industrial/horn/ui/HornButton.kt`（`awaitEachGesture` + 双 `pointerInput`）。
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

1. **零音量智能引导**：按压时媒体音量 == 0 → 灵动岛横幅展示 `zero_volume_prompt`（文案见
   `feature/horn/src/main/res/values/strings.xml`），一键 `AudioSafetyPolicy.sixtyPercentVolume(max)` 恢复 60%；横幅按优先级即时收起。
2. **耳机护耳限幅**：`earProtectionActive = headphonesConnected && volumeFraction > 0.7` 成立时，
   引擎侧下发保护增益（≈ −6 dB，`PROTECTION_GAIN`），横幅展示 `ear_protection_badge` 徽章。摘下耳机 MUST 自动解除。
3. **横幅优先级**（`DynamicPillBanner`，唯一悬浮胶囊，禁多弹窗）：零音量提示 > 护耳徽章 > 引擎/声场状态。
4. 音量监听经 `AudioDeviceCallback`（`AndroidAudioSafetyMonitor.kt`）；策略计算纯函数化以便单测（`AudioSafetyPolicyTest`）。
5. **心理声学输出模式感知（v3.6）**：耳机检测结果 MUST 同步驱动引擎输出级——耳机连接 →
   `OutputMode.HEADPHONE_FULL_RANGE`（全频双耳宽度链），未连接 → `OutputMode.SPEAKER_VBS`
   （外放虚拟低音链）；模式经 `HornSession.setOutputMode` 下发（20–50ms 交叉淡化），并回显于
   Debug HUD（`Speaker VBS` / `Headphone Full-Range`）与遥测 `AudioTelemetry.outputMode`。
6. **声场四档（v3.6）**：`AcousticSpaceSelector` 由三档扩为四档 `GlassSegmentedControl`
   （街道 / 隧道 / 旷野 / 地库），新增 `GARAGE(storageKey="garage")`；文案键
   `acoustic_space_garage`（`values`/`values-zh`）；旧数据未知键 MUST 回落 `STREET`。

## 4. 物理音量键拦截契约

实现：`app/src/main/java/com/industrial/horn/MainActivity.kt#dispatchKeyEvent`。

- 开关关闭（默认）：MUST 完全放行 `KEYCODE_VOLUME_UP/DOWN`，系统音量行为零改变。
- 开关开启：`ACTION_DOWN && repeatCount == 0` 起音；重复 DOWN 仅消费不重触发；`ACTION_UP` 止音；其余事件放行。
- 其他按键 MUST 永远放行。开关状态由 `:core:data` 持久化（`volumeTriggerEnabled`）。

## 5. Apple Glass 设计 Token（`:core:designsystem`）

实现：`core/designsystem/src/main/java/com/industrial/horn/ui/theme/AppleMotion.kt`、
`core/designsystem/src/main/java/com/industrial/horn/ui/theme/AppleGlass.kt`、
`core/designsystem/src/main/java/com/industrial/horn/ui/theme/AmbientMeshBackground.kt`、
`core/designsystem/src/main/java/com/industrial/horn/ui/theme/GlassComponents.kt`、
`core/designsystem/src/main/java/com/industrial/horn/ui/theme/AmbientPalettes.kt`。

### 5.1 同心圆角法则（Concentric Radii）

外层容器圆角 MUST 满足 `R_outer = R_inner + padding`（`AppleRadii.outerOf`）。常用值：
`Capsule/Orb = 50dp`、`Sheet = 28dp`、`Panel = 22dp`、`Tile = 16dp`、`Inner = 12dp`。

### 5.2 动态数值排版

所有动态数值（Hz / ms / dB / xrun / µs）MUST 启用等宽数字：`TextStyle.fontFeatureSettings = "tnum"`
（`AppleNumbers.TABULAR`），杜绝数值跳动引发的左右抖动。

### 5.3 弹簧物理预设（`AppleMotion`）

| 预设 | dampingRatio / stiffness | 用途 |
|---|---|---|
| `SnappySpring` | 0.72 / 900 | 高响应切换（胶囊滑动、开关） |
| `FluidSpring` | 0.82 / 420 | 流畅展开（横幅、BottomSheet） |
| `TactileOrbSpring` | 0.55 / 650 | 主按钮物理按压（细腻回弹） |
| `CapsuleSpring` | 0.78 / 620 | 滑动指示胶囊行程 |

交互态 MUST NOT 使用线性 `tween`。橡皮筋非线性阻尼：`AppleMotion.rubberBand(offset, limit, coefficient = 0.55)`，
越界位移渐近收敛于 `2 × limit`。

### 5.4 五车型环境光色板（深浅双主题独立精调）

| 车型 | 浅色主题 | 深色主题 | meshSoft |
|---|---|---|---|
| 汽车 Car | 钛金银 `#8A9BA8` | 深海蓝 `#2E5F86` | `#B8C4CC` |
| 火车 Train | 琥珀金 `#C98F2E` | 熔岩铜 `#B4562A` | `#E2B263` |
| 电车 E-Bike | 极光青 `#2FA8B8` | 翡翠绿 `#1F8A5C` | `#6FD6DC` |
| 摩托车 Moto | 竞速红 `#C43A32` | 炽热橙 `#D96A22` | `#E07B62` |
| 自定义 Custom | 星云紫 `#7B5FC9` | 虹彩靛 `#4B3FA8` | `#A78BE8` |

映射入口：`AmbientPalettes.of(AmbientKey)`；`HornType → AmbientKey` 由 `:feature:horn` 的
`HornType.ambientKey()` 完成（保持 `:core:designsystem` 零项目依赖）。

### 5.5 `AppleGlassSurface` 三层构造

1. **背景虚化层**：对背后 `AmbientMeshSpec` 图层做真实重绘并施加 `RenderEffect.createBlurEffect(22px)`
   （经 `GlassCapabilities.blurAvailable` 探测），MUST NOT 仅模糊自身前景；
2. **磨砂层**：半透明 frost（白/黑双层 scrim）+ **1.5% 程序化微颗粒**（固定种子 LCG，160 点，
   `drawPoints` 单次批量绘制）抗 8-bit 渐变色带；
3. **方向性镜面描边**：1dp 左上亮/右下柔线性渐变；API 33+ 且 `GlassCapabilities.agslAvailable` 时
   改用 AGSL `RuntimeShader` 边缘微折射光晕（`RIM_SHADER`）。

### 5.6 会呼吸的环境光网格（`AmbientMeshBackground`）

- 三球轨道光斑（26s 周期慢速漂移）+ 网格 + 同心参考环；鸣笛 RMS 包络驱动光斑半径膨胀（压力系数
  `1 + 0.45 × rms`）透射过玻璃面板。
- **相位帧率治理**：呼吸相位 MUST 经 `derivedStateOf` 量化（`PHASE_STEPS = 480`，约 18.75 次有效重绘/秒），
  使全屏重绘不随 60/120Hz 屏幕刷新率空转；量化值是动画时钟的纯函数，Roborazzi 确定性不受影响。
  `rememberAmbientMeshSpec` 产出的 spec MUST 同时供全屏背景与各玻璃面板虚化层共用，保证两层相位一致。
- **生命周期契约**：`ON_STOP`/不可见 MUST 停播无限动画（`resumed` 标志门控 `rememberInfiniteTransition`），
  息屏/后台零功耗。
- **无障碍**：`LocalReducedMotion == true` 时禁用呼吸动画，保留静态构图。

### 5.7 通用玻璃组件契约

| 组件 | 行为契约 |
|---|---|
| `GlassSegmentedControl` | 指示胶囊以 `CapsuleSpring` 滑移；速度形变经 `graphicsLayer` 延迟读取（`velocity`）；每次确认选择回调 `onTick()` 供 CLOCK_TICK 触觉；语义 `Role.RadioButton` + `Selected` + 每项 testTag |
| `GlassCapsuleSlider` | 玻璃槽 + 渐变填充条；拖动/点击均可赋值；越界经 `rubberBand` 橡皮筋视觉拉伸；数值 MUST 用 tnum 展示 |
| `DynamicPillBanner` | 灵动岛式悬浮胶囊；`animateContentSize` 弹簧形变切换内容；由宿主按优先级择一渲染 |

## 6. 视觉与硬件能力分级兼容矩阵

| 能力 | API 33+ | API 31–32 | API 26–30 |
|---|---|---|---|
| 玻璃背景虚化 | `RenderEffect` 22px | `RenderEffect` 22px | 半透明磨砂层（无 blur） |
| 镜面描边 | AGSL `RuntimeShader` 微折射 | 线性渐变描边 | 线性渐变描边 |
| 声压光晕 `AudioAura` | AGSL 金属光场 | Canvas 高质量降级 | Canvas 高质量降级 |
| 主按钮/玻璃组件 | 全量 | 全量 | 全量（磨砂观感） |

- 运行时 MUST 以 `GlassCapabilities`（`runCatching` 构造探测）为准，而非仅凭 SDK_INT——
  模拟器/无头 JVM 上 AGSL 或 RenderEffect 可缺失，探测失败 MUST 优雅降级且绝不崩溃。
- 测试可覆盖渲染档位：`LocalGlassRenderMode`（`AGSL`/`BLUR`/`CANVAS`），保证 Roborazzi 确定性。

### 6.1 窗口形态断点（WindowSizeClass）

| 形态 | 布局 | 实现 |
|---|---|---|
| Compact（竖屏单手） | 品牌头 + 主按钮居中 + 底部悬浮玻璃控制台 | `HornScreen` `compact_layout` |
| Medium / Expanded（横屏/平板/折叠展开） | 左：品牌 + 玻璃控制岛 + 状态脚注；右：超大玻璃鸣笛球 | `HornScreen` `expanded_layout` |

大屏 MUST 零遮挡、零挤压；200% 无障碍大字体 MUST 零裁切（`ScreenshotMatrixTest` 1.35x 档覆盖）。

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
`HapticSignatureTest` 锁定指纹差异性。`GlassSegmentedControl` 的选择刻度 SHOULD 使用
`CLOCK_TICK` 类细粒度反馈（由宿主 `:core:haptic` 注入）。

## 8. 零重组延迟读取规范（性能红线）

- RMS 包络、Expression 位移、环境光相位、按钮按压缩放 MUST 经 `() -> Float` Provider 或
  `State` 在 `drawWithCache`/`drawBehind`/`graphicsLayer` 内读取；MUST NOT 在高频更新时触发重组。
- 环境光动画值经 `rememberInfiniteTransition` 的 `State<Float>` 直接供 draw 层消费（禁在组合层解包），
  并经 `derivedStateOf` 量化封顶重绘频率（见 §5.6）。
- `rememberUpdatedState` 用于保持 Provider 新鲜而稳定（`HornScreen` 的 `rmsProvider`/`expressionProvider`）。
- **稳定回调身份**：`HornScreen` 传入深层子树（`HornHero`/`GlassConsole` 等）的回调 MUST 以
  `remember { { currentTarget() } }` 包装（目标经 `rememberUpdatedState` 转发），使父级重组
  （如遥测节拍）不会因 lambda 身份变化击穿子树的可跳过性（skippability）。

## 9. 非目标（Non-Goals）

- 不做自定义手势录制/宏；单按钮交互是产品灵魂，MUST NOT 复杂化。
- 不做实时 FFT 频谱显示（时域示波器已满足调试需求）。
- 不追求全机型真实物理模糊（低 API 以磨砂近似为已接受权衡）。

## 10. 已知局限与权衡（Known Limitations & Trade-offs）

- `RenderEffect` 背景虚化在低 GPU 设备有合成成本；以 22px 半径与面板级裁剪控制开销。
- AGSL 微折射为屏幕空间近似，非真实光追；换取零依赖与确定性渲染。
- 微颗粒以固定种子生成，放大看为规则噪点而非真随机；换取 Roborazzi 逐字节可复现。
- Roborazzi 基准对系统字体光栅化敏感，跨平台差异 MUST 走 `update-screenshots.yml` Linux 重录 SOP（见 05 规约）。

## 11. 开屏动画与启动体验（Optical Wake, v3.7）

> 关联决策：[ADR-0010 开屏动画 Optical Wake：单时钟自绘与无缝交棒](adr/ADR-0010-startup-optical-wake-animation.md)。
> Optical Wake 是冷启动一次性品牌时刻：玻璃球剪影唤醒 → 双光环聚焦 → 声压绽放 → 无缝融入主界面，
> 能量渐变呼应设计原型蓝黄撞色。实现并行落地中，尚未入库的文件以「契约规划路径」标注（非反引号写法）。

### 11.1 四幕时间轴（单时钟 t = elapsed ms）

总时长 ≈ 1040ms（预算带 800–1150ms）；一切视觉状态均为 t 的纯函数 f(t)，任意时刻可定格复现。

| 时刻 | 阶段 | 元素 | 入出公式 |
| --- | --- | --- | --- |
| 0–160 | WAKE 唤醒 | 玻璃球剪影 | `scale = 0.62 + 0.10·smoothstep(0, 160, t)`（0.62→0.72）；`alpha = smoothstep(0, 160, t)`（0→1，smoothstep 段） |
| 160–500 | FOCUS 聚焦 | 双同心光环扫入（错峰 60ms） | `ring_k = smoothstep(160+60k, 460+60k, t)`，k ∈ {0,1}（环 0 160→460、环 1 220→520） |
| 160–500 | FOCUS 聚焦 | 镜面高光扫过 | `focus = smoothstep(160, 500, t)` 驱动高光带横掠球冠 |
| 160–500 | FOCUS 聚焦 | 环境光网格渐变渗入 | 渗入进度 `meshWash = smoothstep(160, 500, t)`；能量渐变色标深海蓝 `#2E5F86` → 琥珀 `#FFB547`（蓝黄撞色） |
| 500–820 | BLOOM 绽放 | 球体落位 | `scale = 0.72 + 0.28·settle(smoothstep(500, 820, t))`（`TactileOrbSpring` 0.55 手感同源微过冲 ≈12%，精确落位 1.0） |
| 500–820 | BLOOM 绽放 | 双琥珀声压弧 | `arcPhase = ((t-500)/260) % 1`（%1 相位循环；双弧相位差 0.5 反相发射） |
| 500–820 | BLOOM 绽放 | 星芒 glint ×3 | `glint_i = smoothstep(t_i, t_i+60, t) × (1 - smoothstep(t_i+120, t_i+260, t))`，`t_i = 520+85i` ms（i ∈ {0,1,2}） |
| 500–820 | BLOOM 绽放 | 品牌字标字距落定 | `tracking = tracking_end + Δ·(1 - settle(bloom))`（字距沿 settle 曲线收敛落定） |
| t=560 | BLOOM 绽放 | 轻触觉 ×1 | 单次轻触觉脉冲（`HAPTIC_MS = 560`；无马达/系统触觉关闭 → 静默降级） |
| 820–1040 | MERGE 融合 | reveal 闩锁 | t=820 一次性发出；主界面自转场下层接棒、错峰 40ms `SnappySpring` 浮现 |
| 820–1040 | MERGE 融合 | 覆盖层退场 | `scale = 1 + 0.04·smoothstep(820, 1040, t)`（1.0→1.04）；`alpha = 1 - smoothstep(820, 1040, t)`；t=1040 彻底移出组合树 |

公式语义（全部为 t 的纯函数，禁 setTimeout/多时钟排程）：

- `smoothstep(a, b, x) = s²(3-2s)`，其中 `s = clamp((x-a)/(b-a), 0, 1)`——全片唯一缓动原语（C¹ 连续段）。
- `settle(p)`：确定性解析欠阻尼阶跃（ζ=0.55、ω=9，与 `TactileOrbSpring` 手感同源），微过冲后精确收敛 1.0；
  属里程碑曲线推导，不引缓动库。
- `% 1`：相位循环取小数部分（声压弧发射节律）。
- 时间轴常量（1040/160/500/820/560/120/40）单点集中于 `StartupTimeline`（`core/designsystem/src/main/java/com/industrial/horn/ui/theme/StartupWake.kt`）。

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
- 预计算一次：渐变、噪点、星芒等逐帧素材以 `remember` 一次预计算（如 `WakeSceneBrushes`），逐帧零分配。
- 文字节流：UI 文字/读数 ≤60ms 一次更新。
- 移出组合树（I8 同族）：`Completed`/`Skipped`/`Bypassed` 后覆盖层 MUST 彻底移出组合树，不留图层、不留无限动画。
- 测试注入：冻结时钟经 `elapsedProvider` 注入，任意时刻定格可复现（scrub/Roborazzi 免费）。

### 11.8 测试与 Roborazzi 隔离

- 状态机 100% 覆盖：Playing→Completed、Playing→Skipped（`skip()` 幂等重放）、旋转不重播（WARM_START）、
  QUICK_ACTION 绕过（0ms 直达）、REDUCED_MOTION 绕过、优先级 QUICK_ACTION > WARM_START > REDUCED_MOTION。
- Compose UI 测试：任意区域按下即跳过的语义；跳过后 `horn_button` 可交互（零遮罩残留）。
- Roborazzi 定格帧：t=300 / 650 / 900 × 深浅双主题，冻结时钟注入保证逐字节确定性。
- 既有 80 张基准不受影响：开屏覆盖层挂载于 `app/src/main/java/com/industrial/horn/MainActivity.kt` 层，
  HornScreen 截图天然隔离（I10 零回归）。

### 11.9 Non-Goals

- 不引入 Lottie/第三方动画库（包体与确定性红线，见 ADR-0010）。
- 不做联网预热/预取：开屏期零网络请求（冷启动延迟不可控）。
- 不做启动音效/品牌音（静音红线的绝对性）。
- 不做用户可配置的开屏时长或「跳过开关」：跳过 = 一次按下，不进设置。
- 不把系统 Splash 延长充当动画：其职责仅限无缝交棒。

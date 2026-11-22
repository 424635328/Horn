# ADR-0008: Apple Glass 设计系统与弹簧动效体系

- 状态：Accepted
- 日期：2026-11-22

## 背景与问题

v3.0 的视觉语言是金属/琥珀风格的 Material 3 定制主题。产品目标要求向旗舰级"毛玻璃/液态玻璃"
质感与物理弹簧动效全面演进，同时不得违反既有系统不变量：高频视觉零重组风暴（I7）、
息屏/后台零功耗（I8）、音频零 GC（I1/I2）、以及 Roborazzi 80 张基准的逐字节确定性（I10）。
核心问题：**背景虚化、玻璃层次与动效物理如何在不引入重依赖、不破坏无头测试确定性的前提下落地？**

## 决策驱动因素

- 真实背景虚化（对背后环境光层）而非"模糊自身前景"的视觉诚实性。
- 依赖体积与供应链克制：优先平台能力（`RenderEffect`/AGSL），避免新增第三方渲染依赖。
- Robolectric/Roborazzi 无头 JVM 的能力缺失（AGSL、RenderEffect 可能不可用）MUST 优雅降级。
- 120fps 预算：高频状态（RMS、按压、滑动、光斑呼吸）禁重组，只允许重绘。
- 功耗红线：环境光动画生命周期感知，后台 MUST 自动停播。
- 无障碍：系统"减弱动态效果"（reduce motion）MUST 被尊重。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 引入 `dev.chrisbanes.haze` 做背景模糊 | 成熟 API、真实 backdrop blur | 新增第三方依赖及其版本兼容面；对 Robolectric 渲染确定性不可控 | 否决 |
| 仅半透明磨砂近似（无 blur） | 零成本、零风险 | 视觉层次达不到旗舰质感；违背"真实背景虚化"目标 | 仅作低 API 降级 |
| 自维护图层重绘 + `RenderEffect` 模糊 | 真实虚化背后图层；零新依赖；可在探测失败时降级 | 需要面板原点对齐的重绘约定；GPU 合成有成本 | **采纳** |
| 动效用 `tween`/`animate*AsState` 默认参数 | 实现最简单 | 视观感生硬，违背 Apple 式物理手感目标 | 否决（交互态禁线性 tween） |
| 动效用 AndroidX DynamicAnimation | 物理弹簧 API 丰富 | 与 Compose 动画系统割裂，状态同步复杂 | 否决 |

## 最终决策

1. **三层玻璃构造**（`core/designsystem/src/main/java/com/industrial/horn/ui/theme/AppleGlass.kt`）：
   - 背景虚化层：玻璃面板经 `backdrop` 契约重绘背后 `AmbientMeshSpec` 图层，并施加
     `android.graphics.RenderEffect.createBlurEffect(22px).asComposeRenderEffect()`（`graphicsLayer.renderEffect`）；
   - 磨砂层：白/黑双 scrim + 1.5% 固定种子 LCG 微颗粒（160 点 `drawPoints` 单次绘制）抗色带；
   - 镜面描边：API 33+ 且探测通过时用 AGSL `RuntimeShader` 边缘微折射（`RIM_SHADER`），否则线性渐变描边。
2. **能力探测而非 SDK 猜测**：`GlassCapabilities.agslAvailable/blurAvailable` 以 `runCatching`
   构造探测一次性判定；`LocalGlassRenderMode`（AGSL/BLUR/CANVAS）允许测试强制降级档位。
3. **弹簧物理体系**（`AppleMotion`）：`SnappySpring(0.72/900)`、`FluidSpring(0.82/420)`、
   `TactileOrbSpring(0.55/650)`、`CapsuleSpring(0.78/620)` 与 `rubberBand(offset, limit, 0.55)`；
   交互态 MUST NOT 使用线性 `tween`。
4. **延迟读取规范**：高频值一律经 `() -> Float` Provider 或 `State` 在 `drawWithCache`/`graphicsLayer`
   内消费；`rememberUpdatedState` 保持 Provider 稳定且新鲜。
5. **功耗保护**：`AmbientMeshBackground` 以 `LifecycleEventObserver` 门控无限动画，
   `ON_STOP`/不可见即停播；`LocalReducedMotion` 支持减弱动效。
6. **组件集**：`GlassSegmentedControl`（速度形变滑动胶囊 + CLOCK_TICK 刻度回调）、
   `GlassCapsuleSlider`（橡皮筋边界 + tnum 数值）、`DynamicPillBanner`（`animateContentSize` 弹簧形变）。

## 正面影响与权衡

**正面**：视觉层次达到旗舰质感；零新增依赖；无头测试可用 `LocalGlassRenderMode` 锁定确定性；
功耗与无障碍红线均落实；动效参数集中管理（`AppleMotion`）。

**负面/已知权衡**：

- 面板级 backdrop 重绘 + 22px blur 在低端 GPU 有合成成本，以面板裁剪与固定半径控制。
- AGSL 微折射是屏幕空间近似，非物理光追。
- 微颗粒固定种子在超大屏放大下呈规则噪点——用确定性换随机感，已被 I10 排序为更高优先级。
- `RenderEffect` 需 API 31+；API 26–30 仅磨砂近似（视觉可接受的降级）。

## 源码验证路径

- 三层玻璃与能力探测：`core/designsystem/src/main/java/com/industrial/horn/ui/theme/AppleGlass.kt`
- 弹簧预设与橡皮筋：`core/designsystem/src/main/java/com/industrial/horn/ui/theme/AppleMotion.kt`
- 环境光网格与生命周期停播：`core/designsystem/src/main/java/com/industrial/horn/ui/theme/AmbientMeshBackground.kt`
- 五车型深浅双主题色板：`core/designsystem/src/main/java/com/industrial/horn/ui/theme/AmbientPalettes.kt`
- 通用玻璃组件：`core/designsystem/src/main/java/com/industrial/horn/ui/theme/GlassComponents.kt`
- 液态玻璃鸣笛球与 Expression 玻璃胶囊：`feature/horn/src/main/java/com/industrial/horn/ui/HornButton.kt`
- 灵动岛横幅与玻璃控制台：`feature/horn/src/main/java/com/industrial/horn/ui/HornScreen.kt`
- 设计 Token 与兼容矩阵规约：`docs/spec/03-ux-interaction-and-haptics.md`
- 视觉回归基准（80 张）：`app/src/test/screenshots/`

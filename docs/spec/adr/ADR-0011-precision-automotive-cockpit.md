# ADR-0011: 精密汽车座舱 —— 双材质、仪表几何与主题三挡

- 状态：Accepted（取代 [ADR-0008](ADR-0008-apple-glass-design-system-and-spring-motion.md)）
- 日期：2026-11-23

## 背景与问题

v3.5 的 Apple Glass 视觉体系建立在三层玻璃（背景虚化 + 磨砂 + AGSL 折射）、五音色环境光色板与
弹簧动效之上。它解决了"质感"问题，但也积累了三类结构性债务：

1. **语义失真**：产品是一支"多功能喇叭/声学仪器"，玻璃堆叠是消费电子语言，无法承载仪表语义
   （刻度、量程、读数、状态灯）。
2. **跨设备不确定**：`RenderEffect` 与 AGSL 需运行期能力探测，API 31/33 分档渲染，模拟器与无头
   JVM 的能力缺失迫使我们维护三条降级路径；同一张基准图在不同 GPU 上可能不同。
3. **可访问性靠自觉**：强调色在浅色/深色下由人挑选，缺少机器可验证的对比度门禁；玻璃面板上的
   半透明白字在浅色主题下多次逼近 3:1。

核心问题：**如何在不引入新依赖、不牺牲零重组性能与测试确定性的前提下，把视觉语言换成可承载
仪表语义、且在两种材质下都可机器验证的工业设计体系？**

## 决策驱动因素

- **语义优先**：视觉必须表达"仪器"——刻度、量程、指针、状态灯，而非装饰性材质。
- **确定性（I10）**：任何像素都不应依赖 GPU 能力探测；同一 commit 在任何设备上应渲染一致。
- **可访问性可证（WCAG 2.2 AA）**：文本/强调色对比度必须由 JVM 测试守住，且测试本身要有正控/负控。
- **零重组（I7）与空闲帧静止（I8）**：高频读数仍走延迟读取；空闲态不得存在无人观看的动画。
- **材质成本**：视觉纵深用几何（高光缝、底缝、车削环、卡尺角标）表达，不用模糊与半透明。
- **主题可控**：用户需要显式三挡（AUTO / DAY / NIGHT），而非仅有跟随系统。
- **一致的手势契约**：v2 起的按压/滑出/垂直拖拽状态机与无障碍标签不得回归。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| ① 继续打磨 Apple Glass（加厚模糊、补对比度） | 视觉延续，改动量小 | 仍是消费电子语言，仪表语义无处安放；AGSL/RenderEffect 三档降级与能力探测长期维护；跨 GPU 基准不可能逐字节一致 | 否决 |
| ② 纯 Material 3 动态取色（Material You） | 零自研成本，系统级无障碍基线 | 动态取色会把品牌与音色身份交给壁纸；仪表几何仍要自绘，等于只换配色不换语言 | 否决 |
| ③ 精密汽车座舱：双材质 + 机加工面板 + 仪表几何（本决策） | 语义与产品一致；纯填充 + 1px 描边即可获得纵深，无能力探测、跨设备确定；对比度门禁可机器验证；组件可复用（面板/键帽/滑杆/表盘） | 需自绘车削环、滚花、卡尺角标等细节；1px 线在极低 dpi 设备上可能被放大为 1.5–2px | **采纳** |
| ④ 极简扁平（纯色块 + 大字号） | 实现最省，可读性最好 | 无仪表纵深，五音色身份弱化为颜色标签；"精密仪器"的产品定位丢失 | 否决 |

## 最终决策

1. **双材质色板单一事实源**：`Color.kt` 定义 Ceramic Daylight / Stealth Carbon 两套 `HornColors`
   与 5 组 `InstrumentAccent(daylight, carbon)`，经 `LocalHornColors` 下发；Material 3 `ColorScheme`
   映射到同一 canvas/panel，保证原生组件与自绘仪表同材质。
2. **对比度门禁**：`CockpitPaletteTest` 用 WCAG 2.2 相对亮度公式断言文本（≥4.5:1）、强调色（≥4.5:1）、
   故障/护耳（≥4.5:1）；含正控（黑/白 = 21:1）与负控（琥珀/白 = 1.76:1 必须判红）。
3. **仪表几何冻结**：盘面 `0.858 R`、表圈 `0.862–0.925 R`、压力环 `0.932–1.000 R`（60 格，
   点亮数 `round(rms × 60)`）、中心盘 `0.46 D`、表针 `-0.20 R → 0.905 R`；
   240° 刻度（10° 小格 / 60° 大格），零位在 270°。
4. **移除玻璃与着色器**：删除 `AppleGlassSurface`、`AmbientMeshBackground`、`AmbientPalettes`、
   `AudioAura`、`GlassComponents`、`AppleMotion`；不保留 `RenderEffect`/AGSL 路径，渲染在所有
   API 26+ 设备上同构。
5. **主题三挡**：`ThemeMode { SYSTEM, LIGHT, DARK }`（稳定字符串键），VM 提供 `onCycleThemeMode`
   与 `onSelectThemeMode`；`MainActivity` 解析后驱动 `HornTheme` 与系统栏图标极性
   （`WindowInsetsController.isAppearanceLightStatusBars/NavigationBars`）。
6. **开屏只换材质，不动时间轴**：Optical Wake 的四幕 f(t)、闩锁（560/820/1040）、可跳过（≤120ms）
   全部保留；场景改为机加工表盘 + 60 格刻度扫入 + 表圈高光弧，字标移到表盘下方 132dp。
7. **组件契约**：`CockpitCanvas`、`MachinedPanel`、`PlateCaption`、`CockpitStatusRail`、
   `TactileKeycap`、`InstrumentToggle`、`InstrumentDial`、`PrecisionExpressionGauge`、
   `InstrumentSlider`、`InstrumentActionKey`；`HornMotion` 以临界阻尼为主（ζ ≥ 0.88），
   仅表针允许一次物理过冲。

## 后果

**正面**

- 视觉语义与产品定位一致：仪表盘、刻度、读数、状态灯都在讲"这是一台声学仪器"。
- 渲染不再依赖能力探测，同一 commit 在模拟器/无头 JVM/真机上一致，基准图跨设备可比。
- 对比度从"设计师挑选"升级为"测试断言"，并带正控/负控，防止指标失效。
- 每帧成本下降：无离屏渲染、无 `saveLayer`、全屏仅一次网格 Path 绘制。

**负面 / 权衡**

- 车削环、滚花、螺钉、卡尺角标是几何暗示而非真实反射，近看不如真实材质。
- 1px 发丝线在极低 dpi 设备可能渲染为 1.5–2px 灰边，需靠 `hairlineStrong` 与文本对比度兜底。
- `FontFamily.Monospace` 在个别 OEM 字体下回退为比例数字，故所有数值样式同时声明 `tnum`。
- 删除六个文件并重写主界面属于破坏性重构，必须一次性更新 80+8+6 张基准图与三份规约。

## 相关文件与测试

- 色板/主题：`core/designsystem/src/main/java/com/industrial/horn/ui/theme/Color.kt`、`Theme.kt`、`Type.kt`、`Motion.kt`
- 组件：`CockpitCanvas.kt`、`MachinedPanel.kt`、`TactileKeycap.kt`、`InstrumentDial.kt`、
  `PrecisionExpressionGauge.kt`、`InstrumentSlider.kt`
- 主界面：`feature/horn/src/main/java/com/industrial/horn/ui/HornScreen.kt`、`HornInstrumentPad.kt`、
  `HornTypeSelector.kt`、`SafetyUi.kt`、`CustomLabSheet.kt`、`StartupOverlay.kt`
- 主题持久化：`core/data/src/main/java/com/industrial/horn/data/ThemeMode.kt`、
  `HornPreferencesRepository.kt`、`HornSettings.kt`
- 测试：`app/src/test/java/com/industrial/horn/ui/theme/CockpitPaletteTest.kt`、
  `CockpitThemeWiringTest.kt`、`app/src/test/java/com/industrial/horn/ui/ScreenshotMatrixTest.kt`、
  `StartupOverlaySnapshotTest.kt`、`core/data/src/test/java/com/industrial/horn/data/HornPreferencesRepositoryTest.kt`、
  `feature/horn/src/test/java/com/industrial/horn/presentation/HornViewModelTest.kt`
- 规约：[docs/spec/03 §5–§6](../03-ux-interaction-and-haptics.md)、[docs/spec/01 §1.1](../01-system-architecture.md)、
  [docs/spec/06 §2](../06-key-technologies.md)

## 验收证据

- `./gradlew checkAll` 绿（spotless / detekt / lint / 单测 / Roborazzi 校验）。
- `CockpitPaletteTest` 8 项断言全绿，含 1 项负控。
- `ScreenshotMatrixTest` 80 张 + `StartupOverlaySnapshotTest` 8 张 + `InstrumentDialSnapshotTest` 6 张基准全部重录并通过校验。
- `HornViewModelTest` 覆盖主题循环（AUTO→DAY→NIGHT→AUTO）、同步切换、同值不写盘、冷启动恢复。

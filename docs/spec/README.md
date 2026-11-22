# Horn 系统规约（docs/spec）

- 状态：Living Spec
- 日期：2026-11-22

> 本目录是 Horn 工程的**技术契约源**。与 README 的幽默文风不同，此处一切表述以冷静、可验证、可执行为准。
> 修改任何系统不变量 MUST 在同一 PR 内更新对应规约或新增 MADR 决策记录（见 `adr/`）。

## 文档地图

| 文档 | 范围 | 状态 |
|---|---|---|
| [01-system-architecture.md](01-system-architecture.md) | 多模块拓扑、UDF 状态机、防卡音生命周期矩阵、DataStore Schema 契约 | Living Spec |
| [02-audio-dsp-engine.md](02-audio-dsp-engine.md) | 双后端音频引擎、DSP 信号链、5 音色声学参数、混响、遥测与性能预算 | Living Spec |
| [03-ux-interaction-and-haptics.md](03-ux-interaction-and-haptics.md) | 手势状态机、防呆护耳交互、Apple Glass 设计 Token 与渲染降级、触觉指纹 | Living Spec |
| [04-ecosystem-and-protocols.md](04-ecosystem-and-protocols.md) | Glance 微件、Quick Tile、Wear OS 跨端协议 | Living Spec |
| [05-dx-quality-and-ci.md](05-dx-quality-and-ci.md) | Debug/Release 隔离、质量门禁矩阵、R8、CI/CD | Living Spec |
| [adr/](adr/) | MADR 架构决策记录（ADR-0001 ~ ADR-0008） | Accepted |

## 系统核心不变量（Top-Level System Invariants）

以下 10 条是全项目最不能被破坏的铁律。Konsist、单测、Lint 与 CI 门禁是它们的执法机器；任何违反 MUST 回退重做。

| # | 不变量 | 执法方式 | 锚点 |
|---|---|---|---|
| I1 | 音频渲染回调 MUST 零内存分配、零锁、零日志、零 I/O（Zero-GC） | `ZeroGcAudioPathTest`、代码评审红线 | `core/audio/src/main/java/com/industrial/horn/audio/HornRenderer.kt` |
| I2 | 引擎参数跨线程同步 MUST 无锁（原子/原子数组），渲染线程禁读可变共享对象 | `AudioTelemetryBuffer` 设计、C++ `std::atomic` | `core/audio/src/main/java/com/industrial/horn/audio/AudioTelemetry.kt`、`core/audio/src/main/cpp/native_engine.h` |
| I3 | 一切退后台、失焦、手势取消、切换音色场景 MUST 安全止音（防"卡音"） | 生命周期矩阵、`HornScreenTest` 手势用例 | `app/src/main/java/com/industrial/horn/MainActivity.kt` |
| I4 | 输出 MUST 经护耳衰减与软限幅，任何路径不得产生超越 ±0.98 硬边界的样本 | DSP 测试防削波断言 | `core/audio/src/main/java/com/industrial/horn/audio/HornRenderer.kt` |
| I5 | `:core:audio` MUST NOT 依赖任何 UI 框架；`:feature:horn` 与 `:core:designsystem` MUST NOT 导入 `android.media.*` | Konsist 架构测试 | `app/src/test/java/com/industrial/horn/architecture/ArchitectureTest.kt` |
| I6 | Release 产物 MUST 零调试代码泄露（示波器/诊断浮层仅存在于 `src/debug`） | 编译期源码集隔离 + R8 | `feature/horn/src/debug/` 与 `feature/horn/src/release/` |
| I7 | 高频视觉状态（RMS 包络、按压缩放、滑动气压位移、环境光呼吸）MUST 以延迟读取（`graphicsLayer`/`drawWithCache`/`() -> Float`）驱动，禁重组风暴 | 代码评审 + `AppleGlass` 组件契约 | `core/designsystem/src/main/java/com/industrial/horn/ui/theme/` |
| I8 | 背景环境光动画 MUST 感知生命周期，`ON_STOP`/不可见时自动停播（息屏零功耗） | `AmbientMeshBackground` 生命周期观察 | `core/designsystem/src/main/java/com/industrial/horn/ui/theme/AmbientMeshBackground.kt` |
| I9 | 手机与手表 MUST 共享同一 `applicationId`，跨端协议 MUST 可脱 Play Services 离线单测 | `WearProtocolTest` | `core/data/src/main/java/com/industrial/horn/data/WearProtocol.kt` |
| I10 | UI 视觉基准（80 张 Roborazzi）MUST 100% 确定性通过；跨平台差异 MUST 走 Linux 重录 SOP | `verifyRoborazziDebug` 门禁 | `app/src/test/screenshots/` |

## 核心术语表（Glossary）

| 术语 | 定义 |
|---|---|
| xrun | 音频缓冲区欠载/溢出计数；实时引擎健康度指标 |
| RMS | 均方根响度包络，归一化 `0f..1f`，驱动视觉声压 |
| AGSL | Android Graphics Shading Language，API 33+ `RuntimeShader` 所用着色语言 |
| MADR | Markdown Any Decision Records，本项目 ADR 采用的决策记录格式 |
| Prefab | Gradle/NDK 的原生库分发机制，本项目经其引入 Oboe |
| UDF | Unidirectional Data Flow，单向数据流；本项目以 `HornViewModel` 为单一事实源 |
| Zero-GC | 音频线程零垃圾回收：全程预分配、无装箱、无闭包捕获分配 |
| Expression | 按住垂直滑动的连续表达量 `-1f..+1f`，映射 ±8% 音高弯音与谐波亮度 |
| 等响校准 | 按人耳敏感度为各音色独立标定 Makeup Gain，使主观响度一致 |
| 软限幅 | `tanh` 平滑压缩 + ±0.98 硬边界，防削波破音 |
| Deferred Read | 延迟状态读取：在 draw/graphicsLayer 阶段读取 `State`，只重绘不重组 |
| Concentric Radii | 同心圆角法则：`R_outer = R_inner + padding` |
| tnum | Tabular Numbers 字体特性，动态数值等宽防跳动 |

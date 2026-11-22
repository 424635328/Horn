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
| [06-key-technologies.md](06-key-technologies.md) | 全项目关键技术全景：构建/UI/音频/数据/测试/CI 六域技术账本与技术雷达 | Living Spec |
| [research/00-stereo-pipeline-contract.md](research/00-stereo-pipeline-contract.md) | 立体声物理管线冻结契约（v3.6 声学重构基线） | Frozen Contract |
| [research/01-horn-physical-modeling.md](research/01-horn-physical-modeling.md) | 号筒物理声源建模研究：激励器、非线性激波、五音色参数 | Research Spec |
| [research/02-spatial-scene-acoustics.md](research/02-spatial-scene-acoustics.md) | 3D 立体声场与场景传播研究：ISM、FDN、大气吸声、单声道折叠红线 | Research Spec |
| [research/03-psychoacoustics-transducer-adaptation.md](research/03-psychoacoustics-transducer-adaptation.md) | 心理声学与移动扬声器适配研究：VBS、等响校准、真峰值 | Research Spec |
| [adr/](adr/) | MADR 架构决策记录（ADR-0001 ~ ADR-0008） | Accepted |

> `research/` 目录定位：**Frozen Contract + 研究规约**。`00-stereo-pipeline-contract.md` 是主架构师在汇总 01~03 三篇研究规约后冻结的实现契约（双端 MUST 遵循）；01~03 为 Research Spec（研究规约），结论落地前须经 ADR 评审后并入 Living Spec。

## 系统核心不变量（Top-Level System Invariants）

以下 14 条是全项目最不能被破坏的铁律。Konsist、单测、Lint 与 CI 门禁是它们的执法机器；任何违反 MUST 回退重做。

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
| I11 | 立体声 MUST 单声道折叠安全：L+R 折叠能量损失 < 3 dB、200 Hz–2 kHz 陷波深度 < 6 dB | 声学 QA 测试（逐场景粉噪折叠/扫频断言） | `core/audio/src/test/java/com/industrial/horn/audio/HornSynthesizerContractTest.kt` |
| I12 | 参数平滑防拉链：一切控制参数（type/space 交叉淡化、expression、护耳增益、custom 滑块、输出模式）MUST 经一阶平滑后才触及增益或系数 | `OnePoleSmoother` 契约 + 代码评审 | `core/audio/src/main/java/com/industrial/horn/audio/dsp/OnePoleSmoother.kt` |
| I13 | 防次正规数：C++ FTZ（FPCR Flush-to-Zero）与双端块级零吸附策略 MUST 一致（Kotlin 端零吸附 + DC 偏置） | 冻结契约 §2 红线 + 双端 QA 对拍 | `docs/spec/research/00-stereo-pipeline-contract.md` |
| I14 | 双端算法严格对齐：共享常量单源化（Kotlin dsp/DspConstants.kt ↔ C++ dsp_constants.h 逐值镜像）+ 同拓扑，逐样本容差 1e-5 对拍 | 常量单源表 + 双端对拍 QA | `docs/spec/research/00-stereo-pipeline-contract.md` |

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
| FDN | Feedback Delay Network，反馈延迟网络；正交矩阵（Hadamard/Householder）+ 阻尼延迟线构成晚混响，谱半径 ρ(A·G) < 1 保证稳定 |
| ISM | Image Source Method，镜像源法；矩形房间早反射的参数化几何近似（Allen-Berkley） |
| VBS | Virtual Bass System，虚拟低频系统；利用非线性产生的谐波让微扬声器"听起来"有低频 |
| NLD | Nonlinear Device，非线性器件；VBS 中负责谐波生成的环节，本项目取整流主导复合 NLD |
| Brassiness | 非线性激波变陡效应：铜管/气喇叭在大振幅下高次谐波增长的"金属亮度"，以幅度依赖分数延迟近似 |
| PolyBLEP | 多项式带限阶跃修正；在振荡器波形跳变处叠加多项式残差抑制混叠 |
| ITD | Interaural Time Difference，双耳时间差；本项目限制反射通道延迟差 ≤ 0.66 ms |
| ILD | Interaural Level Difference，双耳电平差；本项目声像定位以幅度声像优先 |
| RT60 | 混响衰减 60 dB 时间；本项目按扩散场统计公式设定，实测容差 ±15% |
| ISO 9613-1 | 大气吸声衰减国际标准；用于场景传播的空气吸收滤波 |
| K 加权 | ITU-R BS.1770-5 附件 1 的响度测量预滤波（高架 + RLB 高通）；仅用于 QA 与 Debug 遥测 |
| True Peak | 真峰值：4× 多相 FIR 过采样估计的采样间峰值（BS.1770-5 附件 2 口径） |
| dBTP | 相对满量程的真峰值电平（dBTP = 20·log10(tp)）；出口红线 ≤ 0.98 |
| 谱重心 | Spectral Centroid，幅度谱加权平均频率；音色"明亮度"的 QA 断言指标 |
| 单声道折叠 | Mono Fold：m = (L+R)/2；立体声在单扬声器设备上的兼容性红线（能量损失、陷波深度） |
| 去相关 | Decorrelation：L/R 通道相关系数 ρ 控制在 0.35~0.80，避免 L=R 复制与窄带陷波 |
| one-pole 平滑 | 一阶平滑器 y[n] = y[n-1] + a(x − y[n-1])，防止控制参数阶跃产生拉链噪声 |
| 防次正规数 | Denormal/FTZ 防护：递归状态近零时钳为 0（+ C++ 端 Flush-to-Zero），防 ARM 次正规运算降速 10–100 倍 |
| Hadamard 矩阵 | 归一化正交矩阵，FDN 混合矩阵候选；变换 O(M log M) 且严格保范数 |
| Householder 矩阵 | 基于单一反射向量的正交矩阵，FDN 混合矩阵候选；稠密混合、去相关强 |
| M/S | Mid/Side 编码：M=(L+R)/2、S=(L−R)/2；宽度处理对 S 操作，单声道折叠时 S 精确抵消 |
| FTZ/DAZ | Flush-to-Zeros / Denormals-Are-Zeros，FPCR/MXCSR 的次正规数加速位 |
| 对拍 | 双实现逐样本比较（本项目容差 1e-5），验证 Kotlin/C++ 镜像一致性的 QA 手段 |
| 交叉淡化 | Crossfade：音色/场景/输出模式切换的等功率 20–80 ms 平滑过渡，防切换爆音 |

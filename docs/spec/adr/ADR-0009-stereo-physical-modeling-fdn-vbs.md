# ADR-0009: 采用实时程序化物理建模 + 正交 FDN 立体声场 + 手机外放虚拟低音（VBS）声学架构

- 状态：Accepted
- 日期：2026-11-22

## 背景与问题

Horn 在 v3.0 形态下的声学栈由三部分构成：五音色波表合成（ADR-0001 选定的实时程序化 DSP）、单声道 Schroeder/Freeverb 混响（`SpatialReverb` 4 梳状 + 2 全通）、以及两级 tanh 软限幅输出链（ADR-0004）。该栈在真实感上有三个结构性短板：其一，瞬态质感缺失——起音只有升余弦包络与 tanh 饱和，缺少继电器冲击、气阀开启、激波变陡这类"物理起音"细节，鸣笛听感偏电子；其二，空间感缺失——单声道 Freeverb 只有密度有限的混响尾，没有方向性早反射、没有场景传播（隧道轰鸣、车库驻波、远距离大气吸声），也没有立体声像；其三，换能器适配缺失——手机微扬声器重放不了 <100 Hz 能量，低音体感（尤其 TRAIN 低八度）在外放场景直接丢失。与此同时，v3.6 立体声重构确立了不可退让的既有不变量：渲染线程零 GC（I1）、Kotlin/C++ 双端同拓扑对拍（冻结契约 C3）、(L+R)/2 单声道折叠安全、输出样本 ±0.98 硬边界（I4），以及 ADR-0001 的零采样素材承诺。本 ADR 记录为何在这些约束下采用"实时程序化物理建模 + 正交 FDN 立体声场 + VBS"的声学架构，而非采样素材、双耳渲染或第三方混响库。

## 决策驱动因素

- 真实感：瞬态（继电器 2–4 ms、DC 咔哒 1 ms、气阀 ODE）、频谱（激波变陡 brassiness）、空间（早反射 + 晚混响 + 场景染色）三个维度都必须有物理可解释的建模，而不是靠更多谐波或更多混响尾堆砌。
- 实时预算：48 kHz、128 帧/块（2.66 ms）立体声全管线，单块渲染 MUST 远低于预算；渲染线程零分配、零锁、零日志。
- 零素材与零版权风险：延续 ADR-0001，APK 内严禁采样素材；任何方案不得引入第三方素材/库的授权与再分发问题。
- 折叠安全：立体声增强 MUST NOT 破坏 (L+R)/2 单声道可用性（单声道手机、单扬声器路由、下游混音）；差异化设计必须有可测的折叠预算。
- 双端可对拍：同一声学模型同时服务 Kotlin（AudioTrack 兜底）与 C++（Oboe 主引擎）两条渲染路径，常量单源、拓扑同构、逐样本容差 1e-5 可验收。
- 可测试性：每个声学参数必须显式、可离线复算（RMS、频谱、折叠凹陷、dBTP、RT60），验收不依赖主观听感。
- 设备适配：手机微扬声器的低频缺陷需要在 DSP 层补偿（VBS），且补偿 MUST 折叠安全、MUST 有界。
- 可演进：HRTF/真双耳留作 Non-Goal；模型复杂度可随预算余量渐进加深（如 Lagrange 插值、更高镜像源阶数），不推翻架构。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| ① 采样素材库方案（真实录音 + 运行时解码/卷积素材） | 初始听感上限高，录音真实感无需建模 | 体积随音色数 × 时长线性增长（体积）；录音与卷积素材需要授权、存在再分发风险（版权）；解码与流式读取在实时线程引入分配，弯音依赖重采样（实时成本）；素材多为固定声像，折叠行为不可控、难以给出折叠预算（折叠安全）；参数不可复算、无法逐样本对拍（可测试性） | 否决（ADR-0001 决策延续） |
| ② HRTF/真双耳方案（头相关卷积 + 头部追踪） | 真 3D 定位与前后/上下线索完整，空间真实感上限最高 | HRTF 数据集体积与许可、个体头模差异（体积/版权）；双耳卷积 × 2 耳 + 头部追踪更新的实时成本高，难进 2.66 ms 预算（实时成本）；双耳信号折叠即失去双耳信息，(L+R)/2 可用性差（折叠安全）；个性化与设备相关，离线复算困难（可测试性） | 否决，留作 Non-Goal |
| ③ 商业/第三方混响库（闭源混响 SDK/插件） | 混响音质成熟，开发见效快 | SDK 体积与授权费用、再分发限制（体积/版权）；黑盒内部状态不可控，难保零 GC 与渲染预算，且无法与 Kotlin/C++ 双端逐样本对拍（实时成本/可测试性）；折叠安全行为未知，无法出具 C1–C4 证据（折叠安全） | 否决 |
| ④ 单声道增强（伪立体声：L=R 复制 + 宽度扩展/相位技巧） | 实现成本最低、零体积零版权、折叠天然安全（L=R 时折叠无损） | 无真实去相关与方向信息，3D/立体声目标不达（真实感）；相位/宽度技巧（如 M/S 扩边）在折叠时产生不可控陷波（折叠安全）；可测试性尚可但指标无物理意义 | 否决（仅保留为兼容回退语义） |
| ⑤ 实时程序化物理建模 + 正交 FDN 立体声场 + VBS（本决策） | 零素材体积、零版权风险（体积/版权）；逐样本显式参数，RMS/频谱/折叠/dBTP/RT60 全可离线复算（可测试性）；折叠安全有预算与验收目标（折叠安全）；双端常量单源 + 1e-5 对拍（可测试性） | 建模真实感受模型复杂度约束（非录音级）；全管线 CPU 预算需控制（约 44 K FLOP/块场景链 + 45 K op/块自适应链）；场景/响度表待真机标定（实时成本/标定） | 采用 |

## 最终决策

采用三层声学架构，全部为实时程序化建模，仓库内继续保持零采样素材、零第三方声学库：

1. 声源层——物理建模激励器（research/01 选型）：带限谐波波表（动态带限 `N_max = floor(0.45·fs/f_max)`，裁决 C2）+ 非线性激波变陡（幅度依赖分数延迟为主算法、带限非对称波形变陡为预级，`beta ≤ 0.35`）+ 物理微瞬态（12V 继电器冲击、DC 咔哒、气阀一阶 ODE、断电阻尼余振）+ 五通道 Expression 联动（brassBeta/brassAsym 随气压增长）。
2. 声场层——ISM 早反射 + 正交矩阵 FDN 晚混响 + 地面微梳状/隧道染色 + ISO 9613-1 大气吸声（research/02）：矩形镜像源 N=1~3 不对称早反射簇；M=4/8 素数延迟线 + Hadamard/Householder 正交反馈（`rho(A·G) ≤ g < 1`），四场景冻结 g = 0.567 / 0.914 / 0.375 / 0.853（STREET / TUNNEL / OPEN_HIGHWAY / GARAGE）；宽度 W ≤ 0.7 的 M/S 输出保证折叠时 side 精确抵消。
3. 设备适配层——VBS 双模式输出 + 响度前馈 + 单级真峰值限幅（research/03）：外放链以整流主导复合 NLD 生成虚拟低音（`w_e = 0.70`、`w_o = 0.25`、`g_mix = 0.45`，保护性高通 90 Hz，单声道核混音折叠安全）；耳机链全频 + M/S 宽度微延；响度为全前馈 `G = makeup[v]×g_scene[s]×g_mode[v][m]×(护耳?0.501:1)×0.9`（否决 AGC）；尾段为单级 StereoOutputStage：真峰值检测 → 前瞻软限幅(T=0.98) → `tanh(1.0)` → ±0.98 唯一出口（裁决 C8）。

裁决引用（冻结契约 C1–C8）：C1 立体声 2ch 交错 Float32 胜出、旧"不做立体声"作废；C2 动态带限修复 EBIKE/CUSTOM 混叠；C3 常量单源 + 双端同拓扑 + 1e-5 对拍；C4 Train 起音弯音并入气阀 pitch sag（阀开 -1.8% → 一阶回 0，范围 [-1.9%, +1.5%]，`TRAIN_PITCH_SAG_ONSET = -0.018`）；C5 双层包络（物理微瞬态 + 共享主包络，attack/release 数值不变）；C6 ExpressionModulator 常量单源化（Kotlin 侧 dsp/DspConstants.kt 已落地，C++ 侧 dsp_constants.h 为 v3.6 契约规划路径）；C7 E-Bike 低通以代码为准 4.2 kHz；C8 两级 tanh 合并为单级 StereoOutputStage。

架构演进脉络：ADR-0001 决定"程序化而非采样"，本决策将其深化为"程序化物理建模"（同一承诺下提高真实感上限）；ADR-0002 决定双后端，本决策以 C3/C6 把"两套镜像"升级为"单源常量 + 同拓扑 + 对拍"，压低漂移风险；ADR-0004 决定等响校准/软限幅/护耳三层防护，本决策保持其全部不变量（Makeup 0.47/0.52/0.18/0.30/0.22、`PROTECTION_GAIN 0.501`、±0.98 唯一出口），并把尾段升级为可测 dBTP 的单级真峰值限幅、把外放低频补偿纳入 VBS。工程红线（零 GC、缓冲线程约束、常量单源表、管线级序、API 冻结、DoD）以 docs/spec/research/00-stereo-pipeline-contract.md 为唯一真源，docs/spec/02-audio-dsp-engine.md 的 v3.6 章节与之逐条一致。

## 正负面影响与权衡

正面影响：

- 真实感三维度同时提升：物理瞬态（继电器/气阀/DC 咔哒）、激波变陡 brassiness、场景化立体声场（早反射方向性、隧道/车库染色、远距离大气吸声），且每一维都有物理参数可解释、可调。
- 体积与版权为零：无采样素材、无第三方声学库，APK 体积与音色数解耦，无授权/再分发风险（ADR-0001 承诺延续）。
- 全链可测可复算：N_max、beta、RT60↔g、折叠凹陷、dBTP、LUFS（离线）均为显式常量与公式，QA 以机器断言（防削波、折叠红线、双端 1e-5 对拍、RenderBudget/ZeroGc）验收。
- 折叠安全成为一等公民：直达声纯幅度声像、ITD/增益预算、M/S 宽度上限、公共滤波原则（C1–C4）全部有可测目标，单声道路由不劣化。
- 换能器适配闭环：VBS 让手机外放获得可感知低音体感，耳机链保持全频，双模式等功率交叉淡化无爆音。

负面影响与权衡：

- CPU 预算占用：全管线约 44 K FLOP/块（场景链）+ 45 K op/块（自适应链）+ 激励器约 30.7 kops/块，虽在 2.66 ms 预算内（ARM 核 <1 ms 量级），但 `tanh` 等超越函数必须查表/有界多项式近似，且 Kotlin 兜底路径按 3× 裕量估算。
- 限幅附加延迟 ≤ 2.7 ms：真峰值前瞻 1–2 ms + 4× 多相 FIR 群延迟 ≈ 0.65 ms，与 128 帧回调周期同量级；对 65 ms 级鸣笛起音可感性低，但与触觉反馈（03-ux）的时间对齐需整体评估，超预算走回退路径（保留两级 tanh + dBTP 遥测）。
- VBS 互调残余：复合 NLD 在多频输入下产生和差频，谐波带限只能约束带外，带内互调与真实谐波共存（单频可测、复音主观）；相位对齐残余使梳状干涉只能部分缓解。
- 真机标定缺失：响度对齐表（g_scene × g_mode）、四场景参数、VBS g_mix 上限均为前馈初始工程值，未经真机扬声器标定；个体听感与设备频响差异未建模。
- HRTF/真双耳留作 Non-Goal：空间真实感上限受限于幅度声像 + 有界 ITD + M/S 宽度，前后/上下线索不完整。
- 模型上限与维护成本：程序化物理建模的真实感受模型复杂度约束（号筒一阶等效、矩形 ISM 近似、RT60 ±15% 偏差），五音色需各自建模与标定，边际成本高于录制采样；双端非 bit-exact，1e-5 对拍为 float32 舍入级近似。

## 源码验证路径

现状实现（双端立体声重构进行中，以下为真实文件）：

- 五音色合成与音色参数基线：core/audio/src/main/java/com/industrial/horn/audio/DualToneHornSynth.kt、core/audio/src/main/java/com/industrial/horn/audio/TrainHornSynth.kt、core/audio/src/main/java/com/industrial/horn/audio/EBikeHornSynth.kt、core/audio/src/main/java/com/industrial/horn/audio/MotorcycleHornSynth.kt、core/audio/src/main/java/com/industrial/horn/audio/CustomHornSynth.kt
- 音色注册表（Makeup 等响标定与包络数值）：core/audio/src/main/java/com/industrial/horn/audio/HornVoice.kt
- 流式合成接口（render 零分配、Expression 连续调制）：core/audio/src/main/java/com/industrial/horn/audio/HornSynthesizer.kt
- 渲染器（缓冲预分配、增益链、软限幅出口 `writeLimitedPcm`）：core/audio/src/main/java/com/industrial/horn/audio/HornRenderer.kt
- 混音与软限幅原语现状（两级 tanh 的合并对象）：core/audio/src/main/java/com/industrial/horn/audio/PcmRenderer.kt、core/audio/src/main/java/com/industrial/horn/audio/dsp/DspMath.kt
- v3.0 Schroeder/Freeverb 混响（被 ISM + FDN 替换的对象）：core/audio/src/main/java/com/industrial/horn/audio/SpatialReverb.kt
- DSP 原语（波表、振荡器、双二阶、噪声、平滑）：core/audio/src/main/java/com/industrial/horn/audio/dsp/HarmonicWavetable.kt、core/audio/src/main/java/com/industrial/horn/audio/dsp/WavetableOscillator.kt、core/audio/src/main/java/com/industrial/horn/audio/dsp/BiquadFilter.kt、core/audio/src/main/java/com/industrial/horn/audio/dsp/NoiseSource.kt、core/audio/src/main/java/com/industrial/horn/audio/dsp/OnePoleSmoother.kt
- Expression 调制（常量单源化对象，C6）：core/audio/src/main/java/com/industrial/horn/audio/dsp/ExpressionModulator.kt
- C++ 主引擎（流配置、渲染回调、限幅镜像；现为单声道，目标 `ChannelCount::Stereo`）：core/audio/src/main/cpp/native_engine.cpp、core/audio/src/main/cpp/native_engine.h
- JNI 边界（`nativeSetOutputMode`/`nativeReadWaveformStereo` 的新增落点）：core/audio/src/main/cpp/native_jni.cpp
- 双后端路由与参数镜像（新方法镜像义务）：core/audio/src/main/java/com/industrial/horn/audio/HornAudioEngineFactory.kt、core/audio/src/main/java/com/industrial/horn/audio/OboeHornAudioEngine.kt、core/audio/src/main/java/com/industrial/horn/audio/SynthAudioTrackEngine.kt
- 会话透传（`setOutputMode`/`readWaveformStereo`）：core/audio/src/main/java/com/industrial/horn/audio/HornSession.kt、core/audio/src/main/java/com/industrial/horn/audio/DefaultHornSession.kt
- 输出模式与立体声遥测（已实现，本文架构的观测面）：core/audio/src/main/java/com/industrial/horn/audio/AudioTelemetry.kt
- 护耳策略与设备监视（`PROTECTION_GAIN` 触发链）：core/audio/src/main/java/com/industrial/horn/audio/AudioSafetyState.kt、core/audio/src/main/java/com/industrial/horn/audio/AndroidAudioSafetyMonitor.kt
- ISO 9613-1 大气吸声与共享常量单源表（Kotlin 侧）：core/audio/src/main/java/com/industrial/horn/audio/dsp/Iso9613Absorption.kt、core/audio/src/main/java/com/industrial/horn/audio/dsp/DspConstants.kt
- 离线测量与预算执法测试：core/audio/src/test/java/com/industrial/horn/audio/DspMeasurement.kt、core/audio/src/test/java/com/industrial/horn/audio/RenderBudgetTest.kt、core/audio/src/test/java/com/industrial/horn/audio/ZeroGcAudioPathTest.kt、core/audio/src/test/java/com/industrial/horn/audio/HornRendererTest.kt、core/audio/src/test/java/com/industrial/horn/audio/AudioTelemetryBufferTest.kt

v3.6 契约规划路径（截至 2026-11-22 尚未落地，实施时新增）：

- 共享常量单源表 C++ 镜像：core/audio/src/main/cpp/dsp_constants.h（C++ 侧，v3.6 契约规划路径），与已落地的 Kotlin 侧 DspConstants.kt 逐值镜像冻结契约 §3 全表。
- 场景参数目录：core/audio/src/main/java/com/industrial/horn/audio/dsp/SceneSpecCatalog.kt（v3.6 契约规划路径），取值即 docs/spec/02-audio-dsp-engine.md §4 四张表。
- 单级真峰值输出级：core/audio/src/main/java/com/industrial/horn/audio/dsp/StereoOutputStage.kt（v3.6 契约规划路径），真峰值 → 前瞻软限幅 → tanh(1.0) → ±0.98。
- 双端对拍测试（固定种子、四场景、逐样本容差 1e-5）与折叠红线验收测试（v3.6 契约规划路径）。

规约与研究真源：docs/spec/02-audio-dsp-engine.md（v3.6 目标态规约）、docs/spec/research/00-stereo-pipeline-contract.md（冻结契约：C1–C8 裁决、缓冲线程红线、常量单源表、管线级序、API 冻结、DoD）、docs/spec/research/01-horn-physical-modeling.md（声源数学）、docs/spec/research/02-spatial-scene-acoustics.md（声场数学）、docs/spec/research/03-psychoacoustics-transducer-adaptation.md（心理声学与 VBS/限幅数学）。

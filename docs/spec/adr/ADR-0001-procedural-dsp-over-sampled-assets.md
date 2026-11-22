# ADR-0001: 采用实时程序化 DSP 合成而非内置采样音频素材

- 状态：Accepted
- 日期：2026-11-22

## 背景与问题

Horn 是按住即响（press-and-hold）的实时鸣笛应用，需要提供 5 种差异化音色（汽车、火车、电车、摩托、自定义），并在按压过程中接受 Expression 连续调制（±8% 音高弯音）。音频内容的供给存在两条基本路线：其一，在 APK 内置 WAV/MP3 采样文件，运行时解码回放；其二，不携带任何采样素材，由 DSP 在渲染线程逐样本计算波形。采样回放路线存在三个结构性矛盾：解码与流式读取在实时线程引入内存分配，与 Zero-GC 约束冲突；弯音需要重采样，音质随调制深度劣化，难以支撑逐样本连续调制；自定义音色的基频在 150–3000 Hz 连续可调，任何有限采样集都无法覆盖连续参数空间。本 ADR 记录为何选择实时程序化 DSP 合成。

## 决策驱动因素

- 实时调制：Expression MUST 逐样本改变音高、饱和度与噪声配比，调制精度不得受采样长度或重采样质量限制；±8% 音高弯音只有程序化合成才能实时、连续地响应。
- Zero-GC：渲染线程严禁分配内存；采样回放所需的解码缓冲、流式读盘与临时对象与该约束冲突。
- 连续参数空间：自定义音色基频 150–3000 Hz 连续可调，采样资产无法覆盖连续参数网格。
- APK 体积：采样素材体积随音色数量与时长线性增长，程序化合成的素材开销近似为零。
- 可验证性：程序化合成的每个参数都是显式常量，应当可用离线测量（RMS、频谱幅度、过零率）做机器验收。
- 跨后端复用：同一套声学模型需要同时服务 Kotlin（AudioTrack）与 C++（Oboe）两条渲染路径，采样资产会在两路之间重复携带。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 内置 WAV/MP3 采样 + 运行时解码回放 | 音色可来自真实录音，初始听感上限高 | 解码与流式读取在实时线程引入分配；弯音依赖重采样，调制深度越大音质劣化越明显；素材体积随音色线性增长；无法覆盖自定义音色的连续频率参数 | 否决 |
| 预渲染 PCM 常驻内存 | 回放路径简单，无解码开销 | 内存占用与采样时长成正比；弯音仍需插值重采样；每个参数变体都需一份缓冲，自定义音色不可行 | 否决 |
| 实时程序化 DSP 合成 | 构造期建表、渲染期零分配；逐样本连续调制；参数显式可测；无素材体积开销 | 合成音与真实录音存在可闻的"真实感"差距；每个音色需独立声学建模与标定 | 采用 |
| 采样基底 + DSP 后处理混合 | 真实感与可调性折中 | 同时承担采样与合成两类复杂度；弯音对采样基底仍是重采样问题；体积与实时线程分配问题依旧存在 | 否决 |

## 最终决策

采用实时程序化 DSP 合成，仓库内严禁提交任何 WAV/MP3 等采样音频素材（当前仓库内零音频素材，无 wav/mp3 文件）。具体约束：

1. 谐波 Wavetable MUST 在引擎构造期一次性生成（默认 2048 点单周期表，峰值归一化，线性插值采样），严禁在渲染线程建表或改表。
2. 振荡器 MUST 基于相位累加器逐样本推进，音高调制通过缩放相位增量实现，严禁为弯音重建或重采样波表。
3. 所有 Synth 的 render 函数严禁分配对象或数组；输出缓冲、包络、滤波器与噪声状态一律预分配。
4. Expression 调制 MUST 逐样本平滑，音高弯音上限 ±8%；饱和驱动与噪声配比跟随较慢系数变化，避免参数阶跃产生 zipper 噪声。
5. 音色参数（基频、谐波配比、滤波器截止、增益）MUST 以显式常量写入各自 Synth；任何新增音色 MUST 附带可离线复算的测量断言。

5 种音色的真实参数基线：

| 音色 | 实现类 | 基频与结构 |
| --- | --- | --- |
| 汽车 | DualToneHornSynth | 双基频 400 Hz + 500 Hz，衰减谐波堆叠 + 软饱和 |
| 火车 | TrainHornSynth | 三音笛 311.13 / 369.99 / 493.88 Hz（各带 ±0.4% 失谐对，另有 155.56 / 123.47 Hz 副八度） |
| 电车 | EBikeHornSynth | 2740 Hz + 2785 Hz 双脉冲发射体，685 Hz 低音体 |
| 摩托 | MotorcycleHornSynth | 单音 444 Hz，29 Hz 幅度调制模拟振膜颤振 |
| 自定义 | CustomHornSynth | 基频 150–3000 Hz 连续可调（CustomHornProfile 校验边界），12 次谐波建模 |

## 正面影响与权衡

正面影响：

- APK 不携带音频素材，体积与音色数量解耦。
- 渲染线程 Zero-GC：稳态渲染无分配，由单元测试以分配字节数断言兜底。
- Expression 连续调制是逐样本生效的物理弯音，不是离散变调回放；±8% 弯音、驱动与噪声联动均可实时响应。
- 每个参数显式可测，RMS、频谱幅度、过零率均可离线复算，验收不依赖主观听感。

负面影响与已知局限：

- 合成音与真实录音之间存在可闻的"真实感"差距，程序化建模的上限受模型复杂度约束，本决策接受该差距。
- 5 个音色需要各自独立的声学建模与增益标定，新增音色的边际成本高于录制一段采样。
- Wavetable 大小（2048 点）与谐波级数是音质与渲染开销的折中；更高谐波密度需要更大的表或更多逐样本正弦运算，当前实现选择构造期建表换取渲染期开销确定。
- 火车的压缩空气噪声、汽车的湍流噪声均为固定种子的确定性伪随机，重复渲染结果可复现，但与真实噪声的统计特性不完全一致。
- 参数标定依赖离线测量断言（RMS 区间等），断言边界本身是工程近似，会随模型调整而修订。

## 源码验证路径

- 流式合成接口规定 render 不得分配、Expression 连续调制语义：core/audio/src/main/java/com/industrial/horn/audio/HornSynthesizer.kt
- 渲染器全部缓冲预分配、类型切换在静音边界完成：core/audio/src/main/java/com/industrial/horn/audio/HornRenderer.kt
- 汽车双基频 400 + 500 Hz 与谐波堆叠模型：core/audio/src/main/java/com/industrial/horn/audio/DualToneHornSynth.kt
- 火车三音笛 311.13 / 369.99 / 493.88 Hz 参数表：core/audio/src/main/java/com/industrial/horn/audio/TrainHornSynth.kt
- 电车 2740 + 2785 Hz 双发射体与 685 Hz 低音体：core/audio/src/main/java/com/industrial/horn/audio/EBikeHornSynth.kt
- 摩托 444 Hz 单音与 29 Hz 颤振调制：core/audio/src/main/java/com/industrial/horn/audio/MotorcycleHornSynth.kt
- 自定义音色 12 次谐波建模与构造期建表：core/audio/src/main/java/com/industrial/horn/audio/CustomHornSynth.kt
- 自定义基频边界 150–3000 Hz 与参数消毒：core/audio/src/main/java/com/industrial/horn/audio/CustomHornProfile.kt
- 构造期生成 2048 点谐波 Wavetable 并峰值归一化：core/audio/src/main/java/com/industrial/horn/audio/dsp/HarmonicWavetable.kt
- 相位累加振荡器支持逐样本频率缩放：core/audio/src/main/java/com/industrial/horn/audio/dsp/WavetableOscillator.kt
- Expression ±8% 音高弯音与逐样本平滑：core/audio/src/main/java/com/industrial/horn/audio/dsp/ExpressionModulator.kt
- 稳态渲染零分配的机器断言：core/audio/src/test/java/com/industrial/horn/audio/ZeroGcAudioPathTest.kt
- RMS、频谱幅度、过零率离线测量工具：core/audio/src/test/java/com/industrial/horn/audio/DspMeasurement.kt

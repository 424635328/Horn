# ADR-0004: 多音色感知响度校准、软限幅与耳机自动 -6 dB 护耳策略

- 状态：Accepted
- 日期：2026-11-22

## 背景与问题

Horn 的 5 种音色频谱分布差异巨大：电车音色能量集中在 2.7 kHz 附近（2740 + 2785 Hz），汽车音色基频在 400 + 500 Hz。人耳等响曲线在 2–4 kHz 区间的灵敏度远高于 400 Hz 附近，若按相同 RMS 播放，电车的感知响度将显著高于汽车，快速切换音色会产生明显的响度跳变。与此同时，耳机连接且媒体音量较高时鸣笛存在听力损伤风险；多级增益（Synth 输出增益、makeup gain、混响级联）叠加存在数字削波失真风险。需要一套同时覆盖等响校准、削波防护与听力保护的策略。

## 决策驱动因素

- 等响：音色切换严禁产生响度跳变；2.7 kHz 电车音色必须在 RMS 上明显低于 400 Hz 汽车音色，以换取感知响度接近。
- 听力保护：耳机连接且媒体音量超过 70% 时必须自动施加 -6 dB 衰减，且该动作不应要求用户配置。
- 削波防护：任何增益组合下输出必须经过统一软限幅并受硬边界约束，严禁存在绕过限幅的增益路径。
- 可恢复：零音量状态必须有明确提示与一键恢复路径（恢复到 60% 音量）。
- 可测试性：策略判定必须与 Android 平台 API 解耦，可在纯 JVM 单元测试中覆盖全部分支。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 全部音色统一增益（同 RMS 播放） | 实现最简单 | 忽略等响效应，音色切换响度跳变明显；电车听感刺耳 | 否决 |
| 仅依赖系统音量，应用内不做校准与护耳 | 应用内零复杂度 | 无法补偿等响差异；耳机高音量场景无任何防护；削波风险转移到用户 | 否决 |
| 每音色 makeup gain 等响校准 + tanh 软限幅 + 耳机自动 -6 dB | 等响、防削波、护耳三者兼顾；参数显式可测 | 标定值是工程近似；策略阈值需人工定标 | 采用 |
| 实时 LUFS 响度归一化 | 对任意内容自适应 | 实时响度测量计算量大且引入延迟；对持续单音内容收益有限 | 否决 |

## 最终决策

引入三层防护：按音色标定的 makeup gain（等响校准）、统一 tanh 软限幅加硬边界、耳机高音量场景自动 -6 dB。具体约束：

1. 每个音色必须在 HornVoiceCatalog 标定独立 makeup gain：汽车 0.47、火车 0.52、电车 0.18、摩托 0.30、自定义 0.22。等响目标以 RMS 区间断言固定：电车 RMS 必须低于汽车 RMS 的 0.65 倍，火车 RMS 严禁高于汽车 RMS 的 1.35 倍。
2. 全部输出必须经过统一 tanh 软限幅（softSaturate = tanh(x·drive) / tanh(drive)），并必须被限制在 [-0.98, 0.98] 硬边界内（HARD_CEILING = 0.98）；任何增益路径严禁绕过该限幅。
3. 耳机连接且媒体音量分数大于 70% 时，AudioSafetyPolicy.evaluate 必须置 earProtectionActive 为 true，渲染增益必须乘 PROTECTION_GAIN = 0.501（约 -6 dB）；扬声器（非耳机）场景严禁触发护耳。
4. 零音量（mediaVolume ≤ 0）必须由 AudioSafetyState.isZeroVolume 检出并驱动 UI 提示；一键恢复必须经 AudioSafetyPolicy.sixtyPercentVolume 计算目标值（最大音量 × 0.6 取整）并由平台监视器执行。
5. AudioSafetyPolicy 必须保持纯函数对象，不依赖 AudioManager；AndroidAudioSafetyMonitor 只负责平台观测（ContentObserver 监听音量、AudioDeviceCallback 监听设备插拔、耳机类型判定）与副作用执行。
6. 等响标定与限幅的验收必须以机器断言为准（DspMeasurement 的 RMS 测量 + 各 Synth 契约测试），严禁仅凭主观听感调整。

## 正面影响与权衡

正面影响：

- 音色切换响度连续：等响校准以感知响度而非物理 RMS 为目标，快速换音色不产生音量跳变。
- 削波在结构上不可能：统一软限幅 + 硬边界是所有输出的必经路径，后级增益组合无法突破。
- 耳机护耳自动生效：策略基于系统音量与输出路由自动判定，无需用户配置，且扬声器场景不受影响。
- 政策与平台解耦：AudioSafetyPolicy.evaluate 与 sixtyPercentVolume 可纯 JVM 单测，阈值行为有回归保护（护耳恰在 70% 上方触发、永不作用于扬声器、60% 恢复值可复算）。

负面影响与已知局限：

- makeup gain 是工程标定而非标准响度计（声级计 / LUFS）测量结果；等响以 RMS 区间断言近似，存在个体听感与设备频响差异。
- 电车 RMS 被压低至汽车的 0.65 倍以下，在外放扬声器上可能显得偏小，这是为耳机听感等响付出的代价。
- -6 dB 与 70% 阈值是策略折中，不是医学标准；耳机灵敏度差异未纳入模型。
- tanh 软限幅在大信号下产生谐波失真，属于有意接受的非线性染色（与音色饱和级共享同一原语）。
- 硬边界 0.98 保留约 2% 余量是防御性取值，依赖混响尾与保护增益不再引入超过该余量的提升。
- 护耳策略依赖系统上报的设备类型与媒体音量，个别 ROM 的路由上报延迟会造成策略短暂滞后。

## 源码验证路径

- PROTECTION_GAIN = 0.501（-6 dB）、HARD_CEILING = 0.98 与统一软限幅出口：core/audio/src/main/java/com/industrial/horn/audio/HornRenderer.kt
- tanh 软限幅原语 softSaturate = tanh(x·drive) / tanh(drive)：core/audio/src/main/java/com/industrial/horn/audio/dsp/DspMath.kt
- 五音色 makeup gain 标定（0.47 / 0.52 / 0.18 / 0.30 / 0.22）与等响注释：core/audio/src/main/java/com/industrial/horn/audio/HornVoice.kt
- AudioSafetyPolicy.evaluate（耳机且音量分数 > 0.7 触发）与 sixtyPercentVolume：core/audio/src/main/java/com/industrial/horn/audio/AudioSafetyState.kt
- 音量/设备监听、耳机类型判定与 setVolumeTo60Percent 平台实现：core/audio/src/main/java/com/industrial/horn/audio/AndroidAudioSafetyMonitor.kt
- 安全监视接口与零副作用测试替身：core/audio/src/main/java/com/industrial/horn/audio/AudioSafetyMonitor.kt
- 各音色 RMS 等响区间断言与 -6 dB 护耳断言：core/audio/src/test/java/com/industrial/horn/audio/HornRendererTest.kt
- 各音色输出有限、有界（不削波）的契约断言：core/audio/src/test/java/com/industrial/horn/audio/HornSynthesizerContractTest.kt
- RMS 等离线测量实现（等响标定的复算依据）：core/audio/src/test/java/com/industrial/horn/audio/DspMeasurement.kt
- 护耳阈值、扬声器豁免与 60% 恢复值的策略单测：core/audio/src/test/java/com/industrial/horn/audio/AudioSafetyPolicyTest.kt

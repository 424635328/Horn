# 00 · 立体声物理管线冻结契约（Frozen Contract）

- 状态：Frozen Contract（v3.6 声学重构基线）
- 日期：2026-11-22

> 本契约由主架构师在汇总 `01-horn-physical-modeling.md`（声源）、`02-spatial-scene-acoustics.md`（声场）、
> `03-psychoacoustics-transducer-adaptation.md`（心理声学）三份研究规约后**冻结**。C++ Oboe 与 Kotlin
> AudioTrack 双端实现 MUST 严格遵循本契约；实现期间发现的契约缺陷 MUST 走契约修订（更新本文并记录），
> 严禁单端私自偏离。

## 1. 冻结裁决（对 01 §5.4 冲突 C1–C8）

| # | 冲突 | 裁决 |
|---|---|---|
| C1 | 旧规约单声道输出 vs 立体声重构 | **立体声胜出**：双端输出 2ch 交错 Float32 @48kHz；AudioTrack 升级 `ENCODING_PCM_FLOAT` + `CHANNEL_OUT_STEREO`；Oboe `ChannelCount::Stereo`。旧规约"不做立体声"表述作废 |
| C2 | 波表谐波超 Nyquist 混叠（EBIKE/CUSTOM 实存缺陷） | **修复**：谐波激励启用动态带限 `N_max = floor(0.45·fs/f_max)`，超限谐波不入表 |
| C3 | Kotlin/C++ 拓扑与常量不一致 | **单源化**：共享常量表（§3）+ 双端同拓扑；QA 以逐样本容差 `1e-5` 对拍 |
| C4 | Train 起音弯音 -1.8% 与气压音高微滑方向冲突 | **合并**：弯音并入气阀 ODE 的 pitch sag——阀开瞬间 sag 置 **-1.8%**，随气压趋稳一阶回到 0，允许总范围 **[-1.9%, +1.5%]**（`kappa ∈ [0.005, 0.015]`，起音偏置单独叠加）；删除独立 BEND_START 机制 |
| C5 | 物理包络 vs AttackReleaseEnvelope 语义 | **双层包络**：合成器内部负责物理微瞬态（继电器 2–4ms、气阀 ODE、DC 咔哒 1ms）；`AttackReleaseEnvelope` 保留为全声部共享主包络，HornVoiceCatalog 的 attack/release 数值**不变**（EBIKE 保持 4/6ms，"极陡"由激励瞬态补足） |
| C6 | ExpressionModulator 常量私有/内联 | **单源化**：`core/audio/src/main/java/com/industrial/horn/audio/dsp/DspConstants.kt`（Kotlin）与 `core/audio/src/main/cpp/dsp_constants.h`（C++）逐值镜像，§3 列冻结值 |
| C7 | E-Bike 低通注释 5.2kHz vs 代码 4.2kHz | **以代码为准**：canonical `4.2kHz`，修正注释 |
| C8 | ShortArray 16-bit 单声道输出链 | **立体声 Float32 化**：渲染缓冲为交错 `FloatArray(2*frames)`；`PcmRenderer` 与 `writeLimitedPcm` 两级 tanh 合并为单级 `StereoOutputStage`（03 §5.4 迁移方案）：真峰值检测 → 前瞻软限幅(T=0.98) → `tanh(d=1.0)` → **±0.98 硬边界唯一出口**；PCM16 转换仅存在于遥测镜像 |

## 2. 缓冲与线程契约（红线）

- 采样率 **48000Hz**；块长 **128 帧**；缓冲 **交错立体声 Float32**（`buffer[2i]=L`、`buffer[2i+1]=R`），值域经出口硬边界 ±0.98。
- 渲染线程（Oboe `onAudioReady` / AudioTrack writer）**零堆分配、零锁、零 Log、零阻塞**；全部延迟线/波表/工作缓冲在 `prepare`/构造期预分配。
- 防次正规数：C++ 于流开启置 FPCR Flush-to-Zo + 块级零吸附（`|x|<1e-20 → 0`）；Kotlin 块级零吸附 + DC 偏置 1e-20，双端一致。
- **参数平滑红线**：一切控制参数（type 交叉淡化、expression 五通道、space 交叉淡化 30–80ms 等功率、护耳增益、custom 滑块、输出模式切换 20–50ms）MUST 经一阶平滑 `y[n]=y[n-1]+a(x-y[n-1])`，`a = 1-exp(-1/(τ·fs))`；τ 默认 20ms（expression 相关 5–10ms，mode 切换 30ms）。
- 极点/反馈稳定性：所有滤波器 `|p|<1`（RBJ 系数天然满足）；FDN 谱半径 `ρ(A·G) ≤ g < 1`（正交矩阵 + 阻尼）；非线性全部有界（见 01 §4、03 §2）。

## 3. 共享常量单源表（`core/audio/src/main/java/com/industrial/horn/audio/dsp/DspConstants.kt` ↔ `core/audio/src/main/cpp/dsp_constants.h`，逐值 MUST 一致）

| 常量 | 值 | 语义 |
|---|---|---|
| `MAKEUP_CAR/TRAIN/EBIKE/MOTORCYCLE/CUSTOM` | 0.47 / 0.52 / 0.18 / 0.30 / 0.22 | 等响校准（ADR-0004） |
| `MASTER_GAIN` | 0.9 | 主增益 |
| `PROTECTION_GAIN` | 0.501（≈-6dB） | 护耳 |
| `HARD_CEILING` | 0.98 | 唯一硬边界出口 |
| `LIMITER_DRIVE` | 1.0 | 单级软饱和 tanh |
| `EXPR_PITCH_BEND` | 0.08 | ±8% 音高 |
| `EXPR_DRIVE_RANGE` | 0.35 | 饱和驱动 |
| `EXPR_NOISE_RANGE` | 0.6 | 气流噪声 |
| `EXPR_BRASS_BETA_BASE/RANGE` | 0.05 / 0.30 | `beta = base + range·max(0,e)` |
| `EXPR_BRASS_ASYM_BASE/RANGE` | 0.10 / 0.15 | `alpha = base + range·max(0,e)` |
| `EXPR_SMOOTHING` | 0.0025（≈τ 8.32ms） | 表达通道平滑（保留现值） |
| `TRAIN_PITCH_SAG_ONSET/RANGE` | -0.018 / [-0.019, +0.015] | 阀开 sag（C4 裁决） |
| `VBS_WE/WO/GMIX` | 0.70 / 0.25 / 0.45 | 整流主导复合 NLD（03 选型） |
| `VBS_HP_FC` | 90Hz（80–100 可配） | 保护性高通 |
| `CORR_SILENCE_EPS` | 1e-12 | 相关系数静音判据 |

场景参数（ISM/FDN/大气/干湿/宽度）以 **02 §4 四张表**为唯一真源（STREET g=0.567 / TUNNEL g=0.914 M=8 / OPEN_HIGHWAY g=0.375 / GARAGE g=0.853 M=8）；响度对齐 `G = makeup[v]×g_scene[s]×g_mode[v][m]×(护耳?0.501:1)×0.9` 以 **03 §4 表**为唯一真源。

## 4. 管线级顺序（双端 MUST 相同）

```
物理激励器(带限+瞬态+非线性激波) → 多喇叭立体声摆位 → 主包络/声部增益
  → 场景处理(ISM 早反射 → 正交 FDN 晚混响 → 地面微梳状/隧道染色 → ISO 9613-1 大气吸声 → 干湿+宽度)
  → 输出模式(VBS 外放链 / 耳机全频+宽度链, 交叉淡化切换)
  → 响度校准(前馈 G) → 护耳 → StereoOutputStage(真峰值→前瞻软限幅→tanh(1.0)→±0.98)
  → 交错 Float32 输出 + 遥测分流(L/R RMS、corr、mode、波形环)
```

## 5. API 变更冻结（Kotlin 签名）

```kotlin
interface HornSynthesizer {
    val sampleRate: Int
    /** 交错立体声：写满前 2*frameCount 个 Float；线程契约不变。 */
    fun render(buffer: FloatArray, frameCount: Int)
    fun setExpression(expression: Float) = Unit
    fun reset()
}

interface HornAudioEngine {
    // 既有方法不变……
    fun setOutputMode(mode: OutputMode)            // 新增
    fun readWaveformStereo(left: FloatArray, right: FloatArray): Int  // 新增
}
```

- `HornSession` 同步新增 `setOutputMode`/`readWaveformStereo` 透传；`FallbackHornAudioEngine` 参数镜像 MUST 覆盖新方法。
- `AcousticSpace` 末位追加 `GARAGE(storageKey = "garage")`（ordinal 3；前三档 ordinal/storageKey 不动；`fromStorageKey` 未知键回退 STREET）；DSP 参数从枚举迁移至 `SceneSpecCatalog`（02 §4 表）。
- `AudioTelemetry`/`AudioTelemetryBuffer` 立体声契约见 docs/spec/02 §5.1（已实现）。
- JNI 面新增 `nativeSetOutputMode(jint)`、`nativeReadWaveformStereo(jfloatArray, jfloatArray)`；`nativeSetSpace` case 3 = GARAGE。

## 6. 实现完成定义（DoD）

1. 双端同拓扑、常量逐值一致（§3），`./gradlew :core:audio:testDebugUnitTest` 全绿（含既有 ZeroGc/RenderBudget/防削波/声学测试——语义不变的断言 MUST 保持）。
2. 立体声真实去相关（非 L=R 复制）且满足 02 §5 单声道折叠红线（C1–C4 可测目标）。
3. 10s@48kHz 立体声全管线：0 NaN/Inf、峰值 ≤1.0、单帧 128 帧 < 2.66ms、无分配回退。
4. 旧 Known Limitations 的 4 项 Kotlin/C++ 镜像偏差全部消除。

## 7. 对齐裁决补录（实现期仲裁，2026-11-22，终版）

双端实现落地后由主架构师裁决的残差（逐样本对拍 1e-5 的前置条件）。**裁定原则**：以既有校准与
测试锁定值为优先（C5 冻结标定不可重标定），物理真实性次之，字面差异最后。

| # | 残差 | 裁决 |
|---|---|---|
| a | 梳状延迟取整：C++ round-half-up vs Kotlin `toInt()` 截断 | **round-half-up 胜出**（02 §6.5 明文）：Kotlin `SceneSpecCatalog` MUST 修正 |
| b | ISM ILD：Kotlin 有 ILD 施加、C++ 仅 ITD+增益 | **统一到 02 §3.1**：C++ 补齐与 Kotlin 相同的 ILD 高频倾斜施加 |
| c | Custom 谐波公式：Kotlin 12 项 + 衰减指数 `1.45-0.30r` + `(0.22+0.78r)` 倾斜（N_max 带限截断） vs C++ `2+8r` 项数制 | **Kotlin 语义胜出**（01 §2.8 映射表 + 既有 Custom 测试锁定）：C++ MUST 改为同一公式 |
| d | TRAIN 低音两声部（B2/E♭3）：Kotlin 单振子（0.06/0.10，v3 校准）vs C++ 全五声部失谐对 | **修订原裁决**：和弦三声部（E♭4/F♯4/B4）保留 ±0.4% 失谐对（01 拍频意图），低音两声部单振子（v3 校准与既有精确频点测试优先）；C++ 侧改齐 |
| e | 护耳触发的模式 gating 表述差异 | **维持现状**（策略层仅耳机连接时置位，实践等价）；不改代码 |
| f | 声像归一化（**对拍阻断项**）：Kotlin 折叠保持 `(g_L+g_R)/2=1`（CAR 1.16/0.84 等）vs C++ 等功率 `(0.58/0.42)`、`PAN_CENTER 0.707` | **折叠保持胜出**：I11 折叠零损失 + C5 标定/RMS 验收带锁定；C++ MUST 全部改为折叠保持约定（居中 1.0/1.0） |
| g | `TRAIN_SHAPE_K` 1.6（Kotlin，v3 DRIVE）vs 1.4；`EBIKE_CLICK_AMP` 0.03 vs 0.035；`COMB_MAX_DELAY` 2304 vs 2400 | **Kotlin 值胜出**（校准/测试锁定）：C++ 改齐 |
| h | CAR 微失谐：Kotlin 0.4Hz 慢摆 AM（depth 0.007）vs C++ 真失谐 0.4Hz | **AM 模型胜出**：保住 DFT 谱线位置（400/500Hz 精确频点测试）；C++ 改齐 |
| i | FDN 拓扑细节：Kotlin 8 点 Hadamard 蝶形 + M=4 零权线 + √2 反馈补偿 + `FDN_SEND_GAIN=4` | **Kotlin 实现为唯一真源**：C++ 逐值镜像蝶形/归一/发送增益（尾音断言锁定） |
| j | 大气 shelf 拟合与 ISM 几何 | 双端同算法同源（SceneSpecCatalog 几何参数、2×2 二分 40 次、`rho^k/R` + Woodworth + 每源 LP）；对拍时逐值核验 |

补充裁决（2026-11-22 二次仲裁）：

| # | 项 | 裁决 |
|---|---|---|
| k | ITD 样本换算取整 | **沿 Kotlin 现行约定**：`(0.0875/343)·(θ+sinθ)·fs` 以 `toInt()` 截断后钳 ≤31 样本；C++ MUST 逐样本一致 |
| l | TUNNEL flutter 梳状 2237 的来源 | **勘误备案**：2237 来自 02 §4.2 冻结的 46.6ms 周期（46.6×48=2236.8→round-half-up），非 `2*8/343*48000`（该式=2239.07）；C++ 注释 MUST 按此更正，避免对拍审计误判 |

附注：GARAGE 响度行（外放 −0.95/−1.65/−0.95/−0.95/−1.35、耳机 −0.65）为 TUNNEL/OPEN_ROAD 中值推导，
双端同源；MOTO flutter 位于饱和级之后（01 §2.7 级序补充说明）。

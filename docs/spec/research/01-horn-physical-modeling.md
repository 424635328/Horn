# 01 · 号筒物理声源建模研究

- 日期：2026-11-22
- 状态：Research Spec
- 读者：为 `:core:audio`（C++ Oboe 主引擎 + 零 GC Kotlin AudioTrack 兜底）实施立体声重构与物理声源建模的工程师。
- 关联：[02-audio-dsp-engine.md](../02-audio-dsp-engine.md)（现有 DSP 架构规约）、[README.md](../README.md)（系统不变量 I1/I2/I4）。
- 关键词遵循 RFC 2119：MUST（必须）/ MUST NOT（严禁）/ SHOULD（应当）/ MAY（可以）。

> 本文给出可直接工程化的声学数学规约：离散时间差分方程、滤波器系数公式、伪代码级参数表，
> 并标注符号单位与取值范围。数学式一律以纯文本/代码块表达，不使用 KaTeX。
> 本文为研究规约（Research Spec），不改动任何现有代码；实施前 MUST 另立 ADR 与 02 规约修订。

## 0. 文档定位与诚实声明

- 本文所有文献引用均经 web 实际检索核实（Crossref API、HAL、DAFx 官方论文库、CCRMA、IEEE Xplore、
  Google Patents Scholar 集群），核实状态见 §1 表格。凡未能取得完整书目信息者，明确标注 `[待核实]`，
  不以记忆补全。
- 现有管线为单声道 48 kHz（`native_engine.cpp` `setChannelCount(1)`、AudioTrack `ENCODING_PCM_16BIT` 单声道）；
  即将重构为 **2 声道交错 Float32、48 kHz、块长 128 帧立体声**。本文的 L/R 设计、内存清单与复杂度估算
  均以重构目标为准，凡与现有代码冲突处汇总于 §5.4。
- 系统不变量 I1（渲染回调零分配/零锁/零日志/零 I/O）、I2（跨线程无锁原子参数）、I4（输出必经软限幅，样本不超 ±0.98）
  在本文全部模型中继续生效；新增状态 MUST 预分配，新增参数 MUST 经原子量或单调音频线程所有权发布。

## 1. 文献综述

核实口径：Crossref/出版方页面给出完整书目与 DOI 记为「已核实」；仅检索到标题/作者/年份/出处之部分信息
（如 AES e-Library 旧文无 DOI、页码不可得）记为「部分核实」并标注 `[待核实]` 字段。

| # | 文献 | 出处 | 核实状态 | 对本规约的用途 |
|---|---|---|---|---|
| L1 | Hirschberg, A., Gilbert, J., Msallam, R., Wijnands, A. P. J. "Shock waves in trombones" | J. Acoust. Soc. Am. 99(3), 1754-1758 (1996-03)。DOI: 10.1121/1.414698。HAL: hal-01105563 | 已核实（Crossref + HAL；HAL 书目将年份记为 1995，按刊期 1996-03 记录，两者并存如实标注） | 激波变陡与"brassiness"的物理来源：高声压下管内传播非线性导致波形变陡，见 §2.3 |
| L2 | Holland, K. R., Morfey, C. L. "A Model of Nonlinear Wave Propagation in Horns" | J. Audio Eng. Soc., 1996；AES e-Library id 7891: https://aes.org/publications/elibrary/elibrary-page/?id=7891 | 部分核实：标题/作者/年份/期刊经 Google Patents Scholar 集群与 Semantic Scholar 记录交叉确认；卷/期/页码 `[待核实]`（AES e-Library 与 ACNP 索引均不可匿名访问） | 指数号筒内有限振幅传播的分段指数单元模型，是 §2.2 号筒体与 §2.3 方案③的理论依据 |
| L3 | Smith, Julius O. "Physical Audio Signal Processing" | W3K Publishing，ISBN 978-0-9745607-2-4；在线版 2010 edition: http://ccrma.stanford.edu/~jos/pasp/（引用格式见 https://ccrma.stanford.edu/~jos/pasp/pasp-citation.html ；2005 在线版含 "Horns"、"Brass Instruments" 章） | 已核实（CCRMA 官方引用页） | 波导/延迟线、号筒与辐射阻抗建模、波表与抗混叠的工程参考；§2.1/§2.2 |
| L4 | Cooper, C., Abel, J. "Digital Simulation of 'Brassiness' and Amplitude-Dependent Propagation Speed in Wind Instruments" | Proc. 13th Int. Conf. Digital Audio Effects (DAFx-10), Graz, 2010, paper P77: https://dafx.de/paper-archive/details.php?id=3hItZLKZNqLmwDQFHzXQXg | 已核实（DAFx 官方论文库；页码范围 `[待核实]`，库内 PDF 文件名标 P77） | 幅度依赖传播速度 → 幅度依赖延迟（分布式增量延迟级联/集中参数处理器），§2.3 选型直接依据 |
| L5 | Välimäki, V., Pakarinen, J., Erkut, C., Karjalainen, M. "Discrete-time modelling of musical instruments" | Rep. Prog. Phys. 69(1), 1-78 (2006)。DOI: 10.1088/0034-4885/69/1/R01 | 已核实（Crossref 全记录） | 带限振荡/波表/混叠抑制、离散乐器建模综述；§2.1 |
| L6 | Klippel, W. "Nonlinear wave propagation in horns and ducts" | J. Acoust. Soc. Am. 98(1), 431-436 (1995)。DOI: 10.1121/1.413699 | 已核实（Crossref） | 号筒/波导非线性传播的第二种独立表述，§2.3 稳定取值范围参照 |
| L7 | Resch, J., Vanderkooy, J., Krivodonova, L. "Numerical verification of the existence of shock waves in trombones" | Applied Acoustics 209, 109407 (2023)。DOI: 10.1016/j.apacoust.2023.109407 | 已核实（Crossref） | L1 激波现象的现代数值验证，支撑"变陡不可省略"的听感判断 |
| L8 | Fletcher, N. H., Rossing, T. D. "The Physics of Musical Instruments" | Springer；DOI: 10.1007/978-1-4612-2980-3（Crossref 记录年份 1991；常用第 2 版 1998 `[待核实]`） | 部分核实（DOI/书名/第一作者经 Crossref 引文记录确认；版次年份 `[待核实]`） | 指数号筒截止频率、辐射阻抗、气鸣激励的经典物理口径；§2.2/§2.5 |
| L9 | Bristow-Johnson, R. "Cookbook formulae for audio EQ biquad filter coefficients" | 1998-2001，镜像全文: https://webaudio.github.io/Audio-EQ-Cookbook/Audio-EQ-Cookbook.txt | 已核实（全文可取回） | 本规约全部二阶滤波器（RBJ 双二阶）系数公式来源；§2 全篇 |
| L10 | Välimäki, V., Huovilainen, A. "Antialiasing Oscillators in Subtractive Synthesis" | IEEE Signal Processing Magazine（2007 年前后）；IEEE Xplore 文献号 4117934: https://ieeexplore.ieee.org/document/4117934 | 部分核实：标题/存在性/收录（IEEE SPM "Signal Processing for Sound Synthesis" 专刊）确认；作者全名、卷/期/页码 `[待核实]` | PolyBLEP 类多项式消波残差抑制的公开出处；§2.1 带限振荡备选方案 |

核实统计：**已核实 7 篇（L1、L3、L4、L5、L6、L7、L9），部分核实 3 篇（L2、L8、L10，各含明确 `[待核实]` 字段）**。
未核实到原文的线索一律未写入；产品级事实（Nathan K3LA / Leslie RS3L 的实际音列配置）无法经学术文献核实，
相关表述标注 `[待核实]`，仅作工程建议而非事实主张。

## 2. 数学模型

符号约定（完整符号表见附录 A）：`fs = 48000 Hz` 采样率，`T = 1/fs = 20.8333 us` 采样周期，
`n` 采样索引，`x[n]` 归一化幅度 `[-1, 1]`，`e in [-1, 1]` 为 Expression（气压/驱动表达量），
`c = 343 m/s` 声速（20 °C 干空气），`rho = 1.21 kg/m^3` 空气密度。

```mermaid
flowchart LR
    OSC["带限振荡器组<br/>2048 点谐波波表 / PolyBLEP"]
    HORN["号筒体 + 喉部共振峰群<br/>RBJ biquad 组"]
    BRASS["非线性变陡<br/>幅度依赖分数延迟 + 非对称整形"]
    ENV["物理包络<br/>气阀 ODE / 阻尼余振"]
    PAN["L/R 声像与安装间距<br/>幅度主导 + 有界 ITD"]
    LIM["既有增益与软限幅链<br/>Makeup x 护耳 x tanh 限幅"]

    OSC --> HORN
    HORN --> BRASS
    BRASS --> ENV
    ENV --> PAN
    PAN --> LIM
```

### 2.1 通用激励器基座

#### 2.1.1 带限振荡

主方案沿用现有 2048 点谐波波表积分相位（`HarmonicWavetable`，2048+1 点线性插值，构造期归一化峰值），
备选方案为 PolyBLEP 锯齿（用于非对称脉冲激励与测试信号）。两者 MUST 满足带限条件：

```
N_max = floor(0.45 * fs / f_max)          // 波表允许的最高谐波次数
f_max = f0_max * pitchScale_max            // 含 +8% 弯音后的最高基频
```

- 波表读取（积分相位 `phi in [0,1)`，步进 `inc = f0/fs * pitchScale`）：

```
pos   = phi * 2048
i     = floor(pos)  mod 2048
frac  = pos - floor(pos)
y[n]  = W[i] * (1 - frac) + W[i+1] * frac
phi  += inc
if phi >= 1.0: phi -= 1.0
```

- PolyBLEP 锯齿（2 采样二阶残差，`t = phi`，`dt = inc`，见 L10 一类实现）：

```
y[n] = 2*phi - 1 - polyBlep(phi, dt)

polyBlep(t, dt):
    if t < dt:      u = t / dt;        return u + u - u*u - 1.0
    if t > 1 - dt:  u = (t - 1) / dt;  return u*u + u + u + 1.0
    return 0.0
```

- 抗混叠策略：
  1. 波表谐波数 MUST 满足 `N_max`（见上）；高于 `N_max` 的谐波构造期即剔除，不依赖事后低通。
  2. PolyBLEP 仅校正 2 采样窗口，深调制（`|d(inc)/dt|` 大）时残余混叠由出口级 12-14 kHz 低通压制。
  3. 现有实现的已知缺口：`EBikeHornSynth` 的 2785 Hz 脉冲波表含 8 次谐波，+8% 弯音后
     `2785 * 1.08 * 8 = 24.06 kHz > fs/2`，必然混叠；`CustomHornSynth` 主频上限 3000 Hz 配 12 次谐波
     同理（`3000 * 12 = 36 kHz`）。重构 MUST 按上式动态带限（详见 §5.4 冲突 C2）。

#### 2.1.2 一阶参数平滑（one-pole）

一切可变参数（Expression 通道、声像、驱动度、brassiness 深度、气压包络目标值）MUST 经一阶平滑：

```
y[n] = y[n-1] + a * (x[n] - y[n-1])        // 等价 y[n] = (1-a)*y[n-1] + a*x[n]
a    = 1 - exp(-1 / (tau * fs))            // tau 为时间常数 [s]
     ≈ 1 / (tau * fs)                      // tau*fs >> 1 时
```

48 kHz 系数对照表（`a` 无量纲，取值 (0,1)）：

| tau [ms] | a = 1 - exp(-1/(tau*fs)) | 近似 1/(tau*fs) | 用途建议 |
|---|---|---|---|
| 5 | 0.004158 | 0.004167 | brassiness 深度、驱动度 |
| 8.32 | 0.002500 | 0.002500 | 现行 ExpressionModulator `SMOOTHING = 0.0025` 的等效时间常数 |
| 10 | 0.002082 | 0.002083 | Expression 三通道 |
| 20 | 0.001041 | 0.001042 | 声像/气压目标 |
| 30 | 0.000694 | 0.000694 | 声学空间参数、慢速装饰参数 |

- 换算逆公式：`tau = -1 / (fs * ln(1 - a))`；现行 `SMOOTHING = 0.0025` 对应 `tau ≈ 8.32 ms`，
  落在 5-30 ms 平滑带内，重构可保留该值。
- 稳定性：`0 < a < 1`，极点 `p = 1 - a in (0,1)`，`|p| < 1` 恒成立；平滑器为凸组合，
  输出有界 `|y[n]| <= max(|x|)`，无爆音可能。
- 参数平滑 MUST NOT 逐样本重算滤波器系数（代价高且易产生拉链噪声）；系数按块（128 帧）重算，
  块间系数以一阶平滑过渡，逐样本仅平滑标量目标（如驱动度、声像）。

#### 2.1.3 防次正规数（denormal / FTZ）策略

递归状态（一阶平滑器、biquad 状态、余振环）衰减到 `|s| < 1.175e-38`（float）即进入次正规区，
ARM 上次正规运算可慢 10-100 倍，是实时断流的隐蔽来源。策略分端：

- C++ 端（Oboe 回调线程，启动时一次性设置，非逐块）：
  1. AArch64 设置 FPCR 的 FZ（flush-to-zero）位：`fpcr |= (1u << 24)`，同设 FZ16；
     x86_64 模拟器设 MXCSR 的 FTZ|DAZ 位。设置动作 MUST 在 `onAudioReady` 之外完成。
  2. 兜底零吸附：每块（128 帧）末尾对全部递归状态做
     `if (fabsf(state) < 1e-15f) state = 0.0f;`（约 20 次比较/块，可忽略）。
- Kotlin/JVM 端（无 FTZ 控制）：
  1. 块级零吸附（同上，阈值 1e-15）。
  2. DC 偏置法：一阶平滑器改写为对 `y + dc` 的递归并在输出扣除 `dc`，`dc = 1e-20`
     （远低于 16-bit LSB `5.96e-8`，不可能污染输出）：

```
y[n] = (1-a)*(y[n-1] + dc) + a*x[n] - dc     // 状态永不精确到 0 的邻域
```

- 两法 MUST 同时具备（C++ 以 FTZ 为主、零吸附为审计兜底；Kotlin 以零吸附 + DC 偏置为主），
  保证双端在"鸣笛结束后的长释放尾"不出现 CPU 尖峰。

### 2.2 号筒声学公共层（CAR/TRAIN/CUSTOM 共用）

#### 2.2.1 指数号筒截面与截止频率

喉部（x = 0）到口部（x = L）的指数截面：

```
S(x) = S_throat * exp(m * x)        // S: [m^2], x: [m], m: 蜿蜒指数 [1/m]
f_c  = m * c / (4 * pi)             // 指数号筒理论截止频率 [Hz]（L8 口径）
```

- 取值范围：汽车双蜗牛喇叭 `f_c in [250, 350] Hz`（低于基频 400/500 Hz，保证基频以上高效辐射），
  对应 `m = 4*pi*f_c/c in [9.2, 12.9] 1/m`；TRAIN 大号筒 `f_c in [100, 200] Hz`；
  CUSTOM `f_c = 0.55 * primaryFrequencyHz`，钳制到 `[80, 1500] Hz`。
- 工程近似：号筒体不做分布参数求解，等效为
  `喉部声抗一阶等效（高通 fc，一阶） + 口部辐射阻抗一阶等效 + 喉部共振峰群（二阶带通组）`。
  口部辐射阻抗的一阶等效（L3/L8）：辐射质量抗 `M_rad ≈ 0.37 * rho * a_mouth`（`a_mouth` 为口半径 [m]），
  与辐射阻 `R_rad = rho*c / S_mouth` 合成一阶高通，转角频率 `f_rad = R_rad / (2*pi*M_rad)`，
  蜗牛喇叭典型 `f_rad in [80, 150] Hz`。

#### 2.2.2 离散滤波器（RBJ 双二阶，L9）

全部二阶环节采用 RBJ 系数（`w0 = 2*pi*f0/fs`，`alpha = sin(w0)/(2*Q)`）：

```
一阶等效（喉部声抗/辐射高通，一阶）:
    g = exp(-2*pi*fc/fs)
    y[n] = g*(y[n-1] + x[n] - x[n-1])

二阶带通（喉部共振峰群 / 紊流噪声，constant 0 dB peak gain）:
    b0 = alpha,  b1 = 0,  b2 = -alpha
    a0 = 1 + alpha,  a1 = -2*cos(w0),  a2 = 1 - alpha
    （按 a0 归一后实现）

二阶峰值（共鸣盘/共振峰着色）:
    A = 10^(dBgain/40)
    b0 = 1 + alpha*A,  b1 = -2*cos(w0),  b2 = 1 - alpha*A
    a0 = 1 + alpha/A,  a1 = -2*cos(w0),  a2 = 1 - alpha/A
```

稳定性条件（Jury 判据，MUST 在参数校验中执法）：`|a2| < 1` 且 `|a1| < 1 + a2`。
RBJ 结构在 `0 < Q <= 10`、`0 < f0 < fs/2` 范围内自动满足；`CustomHornProfile` 越界值由
`sanitized()` 兜底的既有做法 MUST 延续。

#### 2.2.3 喉部共振峰群（CAR 模型用）

二阶谐振带通/峰值组，模拟号筒喉部与腔体着色（建议值，可标定）：

| 共振峰 | f0 [Hz] | Q | 增益 [dB] | 取值范围 |
|---|---|---|---|---|
| F1 | 700 | 2.0 | 0 | 600-900 |
| F2 | 1400 | 2.5 | +2 | 1200-1800 |
| F3 | 2800 | 3.0 | +4 | 2400-3400 |

### 2.3 非线性激波变陡（Brassiness）核心算法

物理来源（L1/L7）：高声压下声速随压力微增（`c(p) ≈ c0 * (1 + beta_nl * p/p0`，`beta_nl ≈ 0.4`
为非线性系数量级 `[待核实为通用常数]`），压力峰比波谷传播更快，波形前缘变陡直至近激波，
频谱显著增亮即 "brassiness"（L4 的幅度依赖传播速度口径；L2/L6 给出号筒内有限振幅传播的独立模型）。

三方案比较（实时约束：48 kHz、128 帧/块、2.66 ms 预算、零分配）：

| 方案 | 离散模型 | 稳定性/有界性 | CPU | 抗混叠 | 结论 |
|---|---|---|---|---|---|
| ① 幅度依赖延迟/相位调制 | `y[n] = x[n - d*(1 + beta*|x|)]`（L4 的幅度依赖延迟单向退化版） | 纯前馈，无条件稳定；`|y| <= max|x|` | 低（1 次分数延迟读 + 1 次平滑） | 调制信号低频化后边带可控 | **选为主算法** |
| ② 分段波形变陡（带限非对称 waveshaping） | `y = tanh(k*(x + alpha_asym*(x^2 - 1/3))) / W_bound` | `tanh` 有界；归一后 `|y| < 1` | 低-中（含 2 次 tanh） | 输入限带后抑制折返 | **选为预级（与现有 softSaturate 合并）** |
| ③ 短 FIR 串联级联近似非线性传播 | K 段"1 采样延迟 + 记忆非线性"级联（L2/L4 的分布式增量延迟直译） | FIR 无条件稳定；界 = 各段界之积 | 中-高（K=4-8 段 × 每样本） | 同② | 不采用（列入 Non-Goals，留作离线标定参照） |

#### 2.3.1 方案①：幅度依赖分数延迟（主算法）

```
s[n]   = onePole(s[n-1], |x[n]|, tau_s)         // tau_s = 2~5 ms，调制带宽 < 80 Hz
d_eff  = d0 * (1 + beta * s[n])                 // [样本]
y[n]   = x_lin[n - d_eff]                       // 一阶线性插值分数延迟

x_lin[q] = x[i]*(1 - frac) + x[i+1]*frac,  i = floor(q), frac = q - i
```

参数取值（防爆约束：`d_eff > 0.25` 样本且读指针不翻转）：

| 符号 | 含义 | 取值范围 | 48 kHz 折算 | 约束 |
|---|---|---|---|---|
| `d0` | 基础延迟 | 0.5-1.5 ms | 24-72 样本 | 延迟线长度 `L = ceil(d0_max*(1+beta_max)) + 4`，≤ 100 样本 |
| `beta` | 变陡深度 | 0-0.35 | — | `beta_max = 0.35` 保证 `d_eff in [0.65,1.35]*d0` |
| `tau_s` | 包络平滑 | 2-5 ms | `a = 0.0021~0.0052` | 防止调制边带折叠 |
| 出口低通 | 抑制整形残余 | 12-14 kHz 一阶 | — | 与 §2.1 抗混叠叠加 |

有界性：前馈结构 `|y[n]| <= max_k |x[k]|`；参数 `beta <= 0.35` 时延迟为正、插值为凸组合，
输出不可能超出输入包络。与 Expression 联动见 §2.3.3。

#### 2.3.2 方案②：带限非对称波形变陡（预级）

```
u      = x_pre                                // 输入先经低通 f_lp = fs / (2*(H+1))
w      = tanh(k * (u + alpha_asym*(u*u - 1.0/3.0)))
W_b    = tanh(k * (1 + alpha_asym*2.0/3.0))   // |u|<=1 时的上界
y      = w / W_b                              // 归一后 |y| < 1
```

| 符号 | 含义 | 取值范围 | 约束 |
|---|---|---|---|
| `k` | 驱动陡度 | 1.2-3.0 | `k` 增大 → 峰值变陡、高次谐波增长 |
| `alpha_asym` | 非对称度（偶次谐波） | 0.1-0.25 | 非零偶次项即"电磁振膜非对称激励"的来源 |
| `H` | 预期最高谐波次数 | 3-8 | 输入低通 `f_lp = fs/(2*(H+1))` = 3.4-6.0 kHz |
| `f_lp` | 输入限带 | 3.4-6.0 kHz | 抗折返混叠的关键 |

有界性：`|u| <= 1` 时 `|w| <= W_b`，归一后 `|y| < 1`；若 `|u| > 1` 先钳制（上游已有 `MAX_OUTPUT 0.95` 钳，
构成双重防线）。该级与现有 `softSaturate(v, drive) = tanh(v*drive)/tanh(drive)` 拓扑同族，
实施时 MUST 明确是替换还是串联（建议：替换 Synth 内部 tanh，避免三重饱和过度压缩）。

#### 2.3.3 与 Expression 的联动映射

现行 `ExpressionModulator` 三通道（`SMOOTHING 0.0025`、`MAX_PITCH_BEND 0.08`、
`DRIVE_RANGE 0.35`、`NOISE_RANGE 0.6`）扩展为五通道：

| 通道 | 公式 | 常量 | 范围/说明 |
|---|---|---|---|
| pitchScale | `1 + e*0.08` | `MAX_PITCH_BEND = 0.08`（保留） | ±8% 音高弯音 |
| driveScale | `1 + e*0.35` | `DRIVE_RANGE = 0.35`（保留） | 直接乘入 `k` 与 softSaturate drive |
| noiseScale | `1 + e*0.6` | `NOISE_RANGE = 0.6`（保留） | CAR 噪声底 / TRAIN 紊流 |
| brassBeta | `BETA_BASE + BETA_RANGE*max(0, e)` | `BETA_BASE = 0.05`、`BETA_RANGE = 0.30` | 方案① 深度；气压↑ → 变陡↑ |
| brassAsym | `ALPHA_BASE + ALPHA_RANGE*max(0, e)` | `ALPHA_BASE = 0.10`、`ALPHA_RANGE = 0.15` | 方案② 非对称度；气压↑ → 亮度↑ |

- 平滑：brassBeta/brassAsym 与 driveScale 共用 `tau = 5 ms` 一阶平滑（比 pitch 通道略快，避免音高与亮度脱节）。
- 数值上限：`brassBeta <= 0.35`、`brassAsym <= 0.25`、`driveScale in [0.65, 1.35]`（`e = -1..1`），
  与 §2.3.1/§2.3.2 的防爆范围一致。
- 双端同步：上述常量 MUST 同时落地 `ExpressionModulator.kt`（现为 private companion，需提升为
  可镜像常量）与 `native_engine.cpp`（现为 `onAudioReady` 内联字面量），满足 02 规约"常量双端一致"约束。

### 2.4 汽车双蜗牛喇叭（CAR）

#### 2.4.1 双指数号筒体

| 体 | 基频 f0 | 号筒 fc | 蜿蜒 m | 喉部声抗高通 | 权重 |
|---|---|---|---|---|---|
| 低频体 | 400.0 Hz | 260 Hz | 9.5 1/m | 一阶 260 Hz | 0.52 |
| 高频体 | 500.0 Hz | 330 Hz | 12.1 1/m | 一阶 330 Hz | 0.48 |

- 激励：2048 点谐波波表（沿用 `LOW_HARMONICS` 8 项 / `HIGH_HARMONICS` 7 项，含偶次谐波"体感"），
  带限 `N_max = floor(0.45*48000/(500*1.08)) = 40`，现 8 项安全。
- 电磁振膜非对称脉冲：方案②预级 `alpha_asym = 0.10 + 0.15*max(0, e)`，`k = 1.55*driveScale`（延续 `DRIVE 1.55` 量级）。
- 喉部共振峰群：§2.2.3 三峰。
- 高声压变陡：§2.3 方案①，`d0 = 0.8 ms`（38 样本），`beta` 由 §2.3.3 给出。
- 噪声底：沿用 `NOISE_LEVEL 0.0055` + 单极平滑 0.35；5.5 Hz 幅度摇摆 `SWAY_DEPTH 0.018` 保留。
- 断电自由阻尼余振（二阶欠阻尼系统）：

```
r     = exp(-zeta * omega0 * T)              // 极点半径, |r| < 1
theta = omega0 * sqrt(1 - zeta^2) * T        // 极点角
a1    = -2*r*cos(theta),  a2 = r*r           // y[n] = -a1*y[n-1] - a2*y[n-2] + w[n]

zeta in [0.01, 0.05],  f0_ring in [380, 520] Hz,  omega0 = 2*pi*f0_ring
释放叠加包络 e_off(t) = exp(-t/tau_off),  tau_off = 30~60 ms
```

- 时域闭式（校验用）：`y(t) = A*exp(-zeta*omega0*t)*cos(omega0*sqrt(1-zeta^2)*t + phi0)`；
  离散极点模 `r < 1` 恒稳。衰减到 -26 dB 所需时间 `t_26 = 26/(8.686*zeta*omega0)`：
  `zeta = 0.02, f0 = 440 Hz` 时约 54 ms，`zeta ∈ [0.01, 0.05]` 对应 21-108 ms，
  覆盖"断电后短促自由余振"的听感窗口。
- 12V 继电器吸合机械冲击微音（带通噪声突发包络）：

```
c[n] = A_r * exp(-t/tau_r) * BPF(n_w[n])     // n_w: 白噪（沿用 LCG）
A_r in [0.03, 0.07],  tau_r in [2, 4] ms,  BPF: f = 1.5 kHz, Q = 1.2
触发: noteOn 前 1 ms 预冲击 + noteOn 同步一次；MUST 随包络总线进入限幅前混合
```

#### 2.4.2 L/R 物理安装与声像

- 安装间距 `d_sp = 8 cm` → ITD `= d_sp/c = 0.233 ms = 11.2 样本 @48 kHz`；真实双蜗牛喇叭同壳近距安装，
  以**幅度主导声像**为主、有界 ITD 为辅：
  - 高频体偏右：`(g_L, g_R) = (0.42, 0.58)`（等功率近似 `r = g_R/g_L = 0.71`）；低频体偏左对称。
  - 可选 ITD：每体通道间延迟 `Delta <= 12 样本 (0.25 ms)`，仅在幅度差 ≥ 3 dB 时启用（§2.9 预算）。
- 自然拍频：两体微失谐 `delta_f in [0.3, 0.6] Hz`（高频体 +0.4 Hz）产生 0.4 Hz 级慢摆，
  与 5.5 Hz sway 叠加后总幅度摆动 MUST ≤ 0.03，避免听感"颤抖"。

### 2.5 重载火车气喇叭（TRAIN，3-5 音）

#### 2.5.1 音程组合（真实音名/频率建议表，A4 = 440 Hz 十二平均律）

| 角色 | 音名 | 频率 [Hz] | 微失谐对（±0.4%） | 建议声像 |
|---|---|---|---|---|
| 低音 I | B2 | 123.47 | 123.47 x (0.996 / 1.004) | C |
| 低音 II | E♭3 | 155.56 | 155.56 x (0.996 / 1.004) | L-0.3 |
| 中音 | F♯4 | 369.99 | 369.99 x (0.996 / 1.004) | R-0.2 |
| 高音 I | E♭4 | 311.13 | 311.13 x (0.996 / 1.004) | L-0.2 |
| 高音 II | B4 | 493.88 | 493.88 x (0.996 / 1.004) | R-0.3 |

- 组 A（现用，`TrainHornSynth` 的 Leslie/Nathan 栈）：E♭4 311.13 / F♯4 369.99 / B4 493.88
  + E♭3 155.56 + B2 123.47；微失谐 ±0.4%（`x0.996 / x1.004`）落在任务约束 ±0.3~0.6% 内。
- 组 B（五音可选，任选非整数比组合，例：B♭2 116.54 / E♭3 155.56 / A♭3 207.65 / C4 261.63 / E♭4 311.13）。
  Nathan K3LA / Leslie RS3L 的**产品实际音列配置未经核实** `[待核实]`（公开网页为爱好者 wiki，
  不具学术可靠性）；本表为工程建议（真实音名/频率可复算：`f = 440 * 2^((midi-69)/12)`），
  不主张复刻具体产品。

#### 2.5.2 气阀动力学与错峰时序

每声部独立气压腔一阶 ODE（`p` 腔内气压 [Pa]，`p0` 供气气压，`tau_v` 惯性常数 [s]）：

```
dp/dt = (p0 - p) / tau_v
p[n]  = p[n-1] + (p0 - p[n-1]) * (1 - exp(-T/tau_v))     // 精确离散解
tau_v = tau_ref * V / V_ref                              // 随腔容积线性
tau_v in [15, 45] ms（大腔取大值）
```

阀开/阀关错峰时序表（错峰窗 15-45 ms：相邻声部步进 12-15 ms，全组展开 ≤ 45 ms；
示例为 5 声部，3-4 音时截取前几行）：

| 声部 | 阀开偏移 t_on [ms] | 阀关偏移 t_off [ms] | tau_open [ms] | tau_close [ms] |
|---|---|---|---|---|
| V1（B2） | 0 | 0 | 15 | 30 |
| V2（E♭3） | 12 | 11 | 18 | 32 |
| V3（F♯4） | 24 | 22 | 22 | 35 |
| V4（E♭4） | 36 | 33 | 25 | 38 |
| V5（B4） | 45 | 45 | 28 | 40 |

#### 2.5.3 气压 → 音高微滑与激波驱动联动

```
f_voice[n] = f0 * (1 + kappa * (p[n]/p0 - 1)) * pitchScale    // kappa: 微滑系数
kappa in [0.005, 0.015]                                        // 即 ±0.5~1.5%
```

- 阀开气压上升 → 音高上滑 ≤ +1.5%；断气气压衰减（`tau_v` 或更慢的腔泄放 `tau_bleed = 40~80 ms`）
  → 音高下滑 ≥ -1.5%。现有 `BEND_START 0.982`（-1.8% 起音下滑）超界且方向与气压模型相反，
  见 §5.4 冲突 C4。
- 驱动度联动：`driveScale_eff = driveScale * (0.6 + 0.4*p/p0)`，`brassBeta_eff = brassBeta * (p/p0)`，
  即"气压足 → 变陡足"（§2.3 与本节共用同一 `p` 状态）。
- 高压紊流噪声：带通 1-3 kHz（RBJ BPF 级联：1.2 kHz Q0.8 + 2.4 kHz Q1.0），
  电平 `L_air = 0.055 * (0.5 + 0.5*p/p0) * noiseScale`（延续 `AIR_LEVEL 0.055` 量级）。
- 前向/后向喇叭口朝向（直达/反射比建议）：

| 朝向 | L/R 幅度比 r | 直达:反射 | wetMix 建议 | 说明 |
|---|---|---|---|---|
| 前向 | 0.50（窄） | 0.8:0.2 | x0.8 | 干、贴脸、指向性强 |
| 后向 | 0.70（宽） | 0.5:0.5 | x1.2 | 反射多、空间感强 |

### 2.6 电车/两轮电动车（EBIKE）

| 环节 | 模型 | 参数与范围 |
|---|---|---|
| 双高频共振峰 | 波表脉冲波双发射器 | 2740.0 Hz（权重 0.50）+ 2785.0 Hz（权重 0.32），微失谐 `delta_f in [35, 55] Hz` 产生 45 Hz 级拍感 |
| 琴体 | 波表 | 685.0 Hz（权重 0.13） |
| 带限 | 波表谐波动态带限 | `N_max = floor(0.45*fs/(2785*1.08)) = 7`（现 8 项超限，见 §5.4 C2） |
| 极陡起音 | 包络 attack | 1.5-2.5 ms（目标 ~2 ms；现为 4 ms，见 §5.4 C5） |
| 微弱 DC 开关咔哒 | 1 ms 单极性脉冲 + 一阶高通 | `c[n] = A_dc`（`n = 0..47`，`A_dc = 0.02~0.05`），高通 fc = 2 kHz；`A_dc` MUST ≤ 0.05 |
| 4-7 kHz 毛刺消除 | 低通斜率 + 陷波 | 双 biquad 级联低通 4.2 kHz Q0.707（24 dB/oct）+ 二阶陷波 5.5 kHz Q3.0（-6 dB）；4-7 kHz 抑制 ≥ 20 dB |
| 饱和 | 方案② | `k = 1.85*driveScale`（延续 `DRIVE 1.85` 量级），`alpha_asym = 0.08` |

- 起音、释放走既有 `AttackReleaseEnvelope` 升余弦（§5.4 C5 决定其与物理包络的叠加语义）。
- 陷波与低通均用 §2.2.2 RBJ 系数，满足 Jury 稳定条件。

### 2.7 摩托车（MOTORCYCLE）

| 环节 | 模型 | 参数与范围 |
|---|---|---|
| 金属振动盘 | 波表基频 | 444.0 Hz，8 项谐波（沿用 `DIAPHRAGM_HARMONICS`） |
| 前置共鸣盘 | 二阶峰值组 | 680 Hz Q1.5 +4 dB；1050 Hz Q2.0 +3 dB（鼻音共振峰群，f0 ∈ [600, 1200] Hz） |
| 电磁触点颤振 | AM + FM 微偏 | `AM: 1 + d_am*sin(2*pi*f_flut*t)`，`f_flut = 25~38 Hz`（现 29 Hz 在界内），`d_am = 0.12~0.18`（现 0.18 为上界）；`FM: phaseInc *= (1 + 0.003*sin(2*pi*f_flut*t))` |
| 小口径辐射 | 高通 | 一阶/二阶 120-200 Hz（现二阶 190 Hz 在界内） |
| 点按包络 | attack / release | 8 / 10 ms（短促；沿用现值） |
| 饱和 | 方案② | `k = 2.0*driveScale`（延续 `DRIVE 2.0`），`alpha_asym = 0.12` |

- AM 深度约束：`d_am < 1`（12-18% 远小于 1，包络恒正 `1 - d_am >= 0.82`，无爆音可能）。
- FM 微偏约束：`|delta_f/f| <= 0.005`，相位步进恒正，振荡器不倒走。

### 2.8 Custom Pro Lab 参数映射（CUSTOM）

现有 `CustomHornProfile` 7 参数到统一物理参数（号筒 fc、brassiness 驱动、振膜阻尼、气压惯性）的映射表：

| 用户参数 | 范围 | 统一物理参数 | 映射公式 | 取值域 |
|---|---|---|---|---|
| `primaryFrequencyHz` | 150-3000 Hz | 主体基频 f0；号筒 fc | `fc = 0.55*f0`，钳 `[80, 1500]` Hz | — |
| `secondaryFrequencyRatio` | 0.5-2 | 第二体 f0' 与 fc' | `f0' = ratio*f0`；`fc' = 0.55*f0'` | — |
| `dualTone` | bool | 单/双号筒拓扑与 L/R 分置 | true: 双体 + §2.4.2 声像；false: 单体居中 | — |
| `harmonicRichness` | 0-1 | 谐波衰减、brassiness 上限、辐射高通 | 衰减指数 `1.45 -> 1.15`；`beta_max = 0.05 + 0.30*r`；口部高通 `120 -> 80` Hz；低通 `3800 + 6000*r` Hz（沿用现公式） | — |
| `attackMillis` | 3-120 | 气阀/振膜上升惯性、继电器冲击 | `tau_open = attack/3`；`A_r = clamp(0.05*(20/attack), 0, 0.05)`（越短越"啪"） | `tau_open ∈ [1, 40]` ms |
| `releaseMillis` | 5-250 | 振膜阻尼与余振 | `zeta = 0.01 + 0.04*(release/250)`；`tau_off = release/2` | `zeta ∈ [0.01, 0.05]` |
| `saturation` | 0-1 | 驱动与变陡 | `k = 1.2 + 5.2*sat`（沿用现公式）；`beta = 0.05 + 0.30*sat`；`alpha_asym = 0.10 + 0.15*sat` | — |

- 双端一致性：Kotlin `CustomHornSynth` 与 `native_engine.cpp` 的谐波权重公式现存差异
  （02 规约 §8 已记录），新映射表 MUST 以本文公式为单一事实源，两端同表实现。
- 现有"仅在 CUSTOM 声部静默边界重建合成器"的约束（`HornRenderer.applyPendingCustomProfile`）保留。

### 2.9 单声道折叠安全（L/R 差异化设计预算）

立体声折叠 `M = (L + R)/2`。对直达声 `L = g_L*x[n]`、`R = g_R*x[n - Delta]`：

```
M(w) = (g_L + g_R*exp(-j*w*Delta)) / 2
|M|max = (g_L + g_R)/2,  |M|min = |g_L - g_R|/2
梳状零点 f_k = (2k+1) / (2*Delta_t)     // Delta_t = Delta/fs [s]
折叠凹陷深度 D = 20*log10(|M|max / |M|min) [dB]，等幅时 D -> inf（全零点）
```

预算表（MUST 全部满足；`r = g_R/g_L <= 1`）：

| 约束 | 预算值 | 推导 |
|---|---|---|
| 直达声通道间延迟 | `Delta <= 8 样本 (0.167 ms)` | 首个梳状零点 `1/(2*Delta_t) >= 3 kHz`，能量主区（300 Hz-3 kHz）无零点 |
| 等幅（r = 1）直达声 | `Delta <= 2 样本 (0.042 ms)` | 等幅 + 延迟产生全零点，只允许零点 > 12 kHz |
| 幅度差要求 | `r <= 0.6`（≥ 3.5 dB）当 `Delta in (2, 8]` 样本 | 折叠凹陷 `D = 20*log10((1+r)/(1-r)) <= 12 dB` |
| 调制类差异（拍频/颤振/气压摆动） | 通道同相，深度差 ≤ 20% 相对 | 调制相位差会被单声道折叠削平为 AM 失真 |
| 混响/反射延迟差 | 不受限 | 本身为空间效果，折叠后仍保有干声 |
| 折叠能量验收 | `(L+R)/2` 的块 RMS 相对较响通道跌落 ≤ 3.0 dB | `20*log10((1+r)/2*2/(1+r))` 化简后按最坏频点核算 |

- 实施规则：EBIKE/MOTORCYCLE/CUSTOM 的直达声 MUST 同相居中（`Delta = 0`，50/50）；
  仅 CAR 双体与 TRAIN 前后喇叭口允许 §2.4.2/§2.5 的差异化设计，且 MUST 满足上表。
- 验收方式：对每音色渲染 (L+R)/2 单声道副本，做 1/6 倍频程平滑频谱，主能量区（300-3000 Hz）
  凹陷 MUST ≤ 12 dB（对照 §3 的测试执法建议）。

## 3. 工程约束

### 3.1 实时线程约束

- 渲染回调（Oboe `onAudioReady` / AudioTrack 写线程）MUST 零分配、零锁、零日志、零 I/O（不变量 I1）；
  本文新增的延迟线、波表、biquad 状态、气压状态、包络状态 MUST 全部构造期预分配。
- 跨线程参数（Expression、brassiness、声像、气压目标）MUST 经 `std::atomic` / `AtomicInteger` 位转
  发布（不变量 I2），或归属单一音频线程（如声部时序表）。
- 输出为 2 声道交错 Float32、48 kHz、块长 128 帧（= 256 float = 1024 B/块）；块预算 2.66 ms，
  本文模型目标单块渲染 < 1.2 ms（含混响与限幅，留 ≥ 50% 调度裕量）。
- 参数一律一阶平滑（§2.1.2）防拉链噪声；滤波器系数按块重算 + 块间平滑，MUST NOT 逐样本重算。

### 3.2 极点稳定性与有界性

| 环节 | 稳定性/有界性条件 | 证明思路 |
|---|---|---|
| 一阶平滑 / 一阶滤波 | `0 < a < 1` ⇔ `|p| = |1-a| < 1` | 凸组合或极点在单位圆内 |
| RBJ 双二阶 | `|a2| < 1` 且 `|a1| < 1 + a2`（Jury） | 0 < Q ≤ 10、0 < f0 < fs/2 自动满足 |
| 余振环 | `r = exp(-zeta*omega0*T) < 1` | 极点半径恒 < 1，指数衰减 |
| 方案① 幅度依赖延迟 | `d_eff > 0.25` 样本；`beta <= 0.35` | 纯前馈 FIR 型插值，`|y| <= max|x|` |
| 方案② 波形整形 | 归一除子 `W_b = tanh(k*(1+2*alpha_asym/3)) > 0` | `tanh` 有界 + 归一 |
| AM 颤振 | `d_am < 1`（12-18%） | 包络 `1 - d_am >= 0.82 > 0` |
| FM 微偏 | `|delta_f/f| <= 0.005` | 相位步进恒正 |
| 气压 ODE | `p0 > 0`、离散系数 `1-exp(-T/tau_v) in (0,1)` | 一阶惯性，`p -> p0` |
| 继电器冲击 | `A_r <= 0.07`，包络指数衰减 | 有界突发 × 有界滤波 |

非线性有界性证明思路统一为：每级输出 `|y| <= B_i`（tanh 归一、凸组合、前馈插值各自易证），
级联总界 `<= prod(B_i)`，再经既有 `MAX_OUTPUT 0.95` 钳与两级 `±0.98` 软限幅（不变量 I4），
任何参数组合不可能产生超界样本。

### 3.3 预分配内存清单（每音色，float 口径，48 kHz 立体声）

| 音色 | 波表 | 延迟线 | biquad/一阶状态 | 其他 | 合计（约） |
|---|---|---|---|---|---|
| CAR | 2 x (2049) = 16.4 KB | brassiness 2 x 100 + ITD 2 x 12 = 224 | 共振峰 3 + 噪声/低通 2 | 余振 2 状态、LCG 2 | ~17 KB |
| TRAIN | 2 x (2049) = 16.4 KB | brassiness 2 x 100 | 紊流 2 + 高/低通 2 | 气压 p[5]、时序表 5 | ~17 KB |
| EBIKE | 2 x (2049) = 16.4 KB | — | 低通 2 + 陷波 1 + 高通 1 | 咔哒计数 1 | ~17 KB |
| MOTORCYCLE | 1 x (2049) = 8.2 KB | — | 共鸣峰 2 + 高/低通 2 | AM/FM 相位 2 | ~9 KB |
| CUSTOM | 1 x (2049) = 8.2 KB | brassiness 1 x 100 | 高/低通 2 | 气压/阻尼标量 | ~9 KB |
| 全局 | — | — | — | 交错块缓冲 256 f（1 KB）+ 双声道混响约 18 KB | ~19 KB |

- 波表统一 2048+1 点（沿用 `HarmonicWavetable` 尺寸），构造期归一，运行期只读共享。
- 双声道混响：`SpatialReverb` 每通道一套（4 梳 + 2 全通 + 预延迟），或 M/S 处理；
  具体立体声化方案超出本文范围（见 Non-Goals）。
- 五音色全部预分配总额 < 100 KB，满足零 GC 审计（`ZeroGcAudioPathTest` 64 KB 稳态分配预算不受影响，
  预分配发生在构造期）。

### 3.4 逐样本/逐块复杂度估算（单声部，ops/样本，ARM64 标量口径）

| 环节 | 估算 ops/样本 | 说明 |
|---|---|---|
| 波表振荡器 | ~6 | 1 lerp（2 mul + 2 add）+ 索引 + 相位步进 |
| PolyBLEP 锯齿 | ~10 | 2 段多项式 + 相位 |
| RBJ biquad | ~9 | 5 mul + 4 add |
| 一阶平滑/一阶滤波 | ~3 | 1 mul + 2 add |
| 方案① 幅度依赖延迟 | ~10 | 平滑 + 延迟读 + lerp |
| 方案② 非对称整形 | ~15-25 | 2 次 tanh（建议查表/多项式近似，界保持） |
| 气压 ODE + 音高耦合 | ~8（每声部） | 1 exp（构造期算 `1-exp(-T/tau_v)`，运行期仅 mul-add） |

- CAR 最重路径（2 波表 + 3 共振峰 + 变陡①+② + 噪声）约 90-120 ops/样本/声道；
  128 帧 x 2 声道 x 120 ops ≈ 30.7 kops/块，ARM64 单核远低于 2.66 ms 预算；瓶颈是 `tanh` 库调用，
  MUST 以查表或有界多项式近似替代（保持 §3.2 有界性证明成立）。
- 计数口径含乘加各计 1；NEON 向量化后按 2-4 倍余量预估。验收沿用 `RenderBudgetTest`（单块均值 < 2.66 ms）。

## 4. Non-Goals

- 不做完整气鸣自激振荡模型（唇/簧片-气流反馈环，Vergez/Rodet 一类），激励器为开环波表/整形。
- 不做双向波导与分布参数非线性传播（L4 的 bidirectional waveguide、L2 的分段指数单元全模型）；
  仅取其幅度依赖延迟/集中参数结论（§2.3 方案③ 仅作离线参照）。
- 不做 HRTF/双耳空间音频、不卷积真实 RIR（沿用 02 规约 Non-Goals）。
- 不承诺 Kotlin 与 C++ 逐位（bit-exact）一致；但同一常量表、同一拓扑 MUST 两端镜像。
- 不复刻具体商业气喇叭（Nathan/Leslie 等）的实测音列与声压级；音程表为工程建议。
- 不做 16 bit 以上定点路径；输出 Float32 交错，交付格式转换由后端层处理。
- 不在本文定义立体声混响的具体拓扑（M/S、去相关），仅约束其折叠安全。

## 5. Known Limitations

### 5.1 模型局限

- 方案① 的一阶线性插值分数延迟在 `beta` 边界（0.35）会有轻微高频着色；严格低通插值
  （Lagrange 3 点）可改善但增加约 3 倍 ops，留作可选项。
- 喉部声抗/辐射阻抗的一阶等效在 `f > 3 kHz` 与真实号筒偏差可达数 dB；共振峰群仅补偿中频，
  高频辐射指向性未建模。
- 气压 ODE 为一阶惯性近似，未建模腔体谐振与阀片回弹；错峰表为听感工程值，非实测。
- 微失谐拍频（0.3-0.6 Hz）与真实气喇叭的慢摆机理（腔体互耦）不同源，仅近似其听感。
- PolyBLEP 在深调制（大 `beta`、快速 Expression 拖动）下残余混叠未量化，需听测标定。

### 5.2 文献局限

- L2（Holland & Morfey）卷/期/页码未能匿名核实，AES e-Library id 7891 为稳定入口；
  L10（Välimäki & Huovilainen）卷/期/页码与 PolyBLEP 术语的首倡归属 `[待核实]`；
  L8 版次年份 `[待核实]`。这些文献的定性结论（非线性变陡、抗混叠振荡、号筒物理）有
  L1/L4/L5/L6/L7/L9 的完整书目互相支撑，不依赖待核实字段成立。
- Nathan K3LA / Leslie RS3L 的产品音列配置 `[待核实]`，§2.5 音程表不构成产品事实主张。

### 5.3 实施局限

- 本规约不改动现有代码；实施需先立 ADR（立体声重构 + 物理建模）、修订 02 规约
  （单声道 Non-Goal、块格式、常量表）并更新测试。
- 双端镜像仍靠人工同步（02 规约 §8 已知问题），本文建议的常量集中化是降低该风险的前置条件。

### 5.4 与现有代码的冲突清单（实施前必须裁决）

| # | 冲突 | 证据 | 建议 |
|---|---|---|---|
| C1 | 02 规约 §7"不做立体声、输出为单声道"与 2 声道交错 Float32 重构直接冲突；AudioTrack 兜底现为 `ENCODING_PCM_16BIT` 单声道 240 帧块 | `02-audio-dsp-engine.md` §1.3/§7、`SynthAudioTrackEngine.kt` | ADR 记录立体声化，同步改 02 规约与 `AudioTelemetryBuffer.pushWaveform` 的 stride |
| C2 | 波表谐波无动态带限：EBIKE 8 次谐波 +8% 弯音后 24.06 kHz 超 Nyquist；CUSTOM 12 次谐波在 3000 Hz 主频下 36 kHz | `EBikeHornSynth.kt`、`CustomHornSynth.kt`、`native_engine.cpp` `harmonicCount` | 按 §2.1.1 `N_max` 构造期带限 |
| C3 | Kotlin 与 C++ DSP 拓扑不一致：CAR 谐波（8/7 项自定义权重 vs 4 项 1/h）、TRAIN（8 振子波表 vs 6+2 正弦、噪声 0.055 vs 0.04）、Custom（12 项 vs 2+8r 项、权重公式不同） | `DualToneHornSynth.kt` 对比 `native_engine.cpp` `renderVoice` | 以本文常量表为单一事实源重建镜像；补充双端对拍测试 |
| C4 | TRAIN 起音弯音 `BEND_START 0.982`（-1.8%，向上滑）与气压音高微滑 ±0.5~1.5% 方向/幅度冲突 | `TrainHornSynth.kt` `PITCH_BEND_SECONDS/BEND_START` | 用 §2.5.3 气压耦合替代，钳 ±1.5% |
| C5 | 包络语义：`AttackReleaseEnvelope` 升余弦 vs 本文物理包络（气阀 ODE/阻尼余振/EBIKE 2 ms 起音）；EBIKE 现 attack 4 ms 与目标 ~2 ms 不一致 | `HornVoice.kt` profile 表 | 物理包络乘在升余弦之上（升余弦保留为 UI 平滑层），EBIKE attack 改 2 ms |
| C6 | `ExpressionModulator` 常量为 private companion const，C++ 侧为内联字面量，扩展五通道（§2.3.3）需提升为共享常量镜像 | `ExpressionModulator.kt`、`native_engine.cpp` `onAudioReady` | 常量表 + 双端一致性单测 |
| C7 | E-Bike 低通注释 5.2 kHz vs 代码 4.2 kHz（02 规约 §8 已记录）；本文按 4.2 kHz + 24 dB/oct 斜率设计 | `EBikeHornSynth.kt` 类注释 | 修注释；斜率升级为双 biquad |
| C8 | 输出链为 16-bit `ShortArray` 单声道（`PcmRenderer`、`HornRenderer.writeLimitedPcm`、`PCM_MAX`），立体声 Float32 需新缓冲契约 | `HornRenderer.kt`、`core/audio/src/main/java/com/industrial/horn/audio/dsp/StereoOutputStage.kt`（v3.6 已取代 `PcmRenderer`） | 新增交错 Float32 输出缓冲；±0.98 限幅语义保留（不变量 I4） |

## 附录 A 符号与单位表

| 符号 | 含义 | 单位 | 取值/范围 |
|---|---|---|---|
| `fs` | 采样率 | Hz | 48000 |
| `T` | 采样周期 `1/fs` | s | 20.8333e-6 |
| `n` | 采样索引 | — | 整数 |
| `x[n]`, `y[n]` | 归一化样本 | — | [-1, 1] |
| `e` | Expression | — | [-1, 1] |
| `a` | 一阶平滑/滤波系数 | — | (0, 1) |
| `tau` | 时间常数 | s | 5e-3 ~ 30e-3（参数平滑） |
| `p`, `p0` | 腔气压/供气气压 | Pa | `p -> p0`，相对值 0~1 |
| `tau_v` | 气阀惯性常数 | s | 15e-3 ~ 45e-3 |
| `f0`, `fc`, `f_c` | 基频/截止频率 | Hz | 见各节 |
| `m` | 号筒蜿蜒指数 | 1/m | 9.2 ~ 12.9（CAR） |
| `c` | 声速 | m/s | 343（20 °C） |
| `rho` | 空气密度 | kg/m^3 | 1.21 |
| `S(x)`, `S_throat`, `S_mouth` | 号筒截面 | m^2 | 正 |
| `zeta` | 阻尼比（余振环） | — | 0.01 ~ 0.05 |
| `omega0` | 自然角频率 | rad/s | `2*pi*f0_ring` |
| `d0`, `d_eff`, `Delta` | 延迟（基础/调制后/通道差） | 样本 | 见 §2.3.1/§2.9 |
| `beta`, `brassBeta` | 变陡深度 | — | 0 ~ 0.35 |
| `alpha_asym` | 非对称度 | — | 0 ~ 0.25 |
| `k` | 整形陡度 | — | 1.2 ~ 3.0（乘 driveScale） |
| `d_am`, `f_flut` | AM 深度/颤振频率 | —, Hz | 0.12~0.18, 25~38 |
| `g_L`, `g_R`, `r` | 声像增益与比 | — | `r = g_R/g_L <= 0.6`（延时时） |
| `kappa` | 音高-气压耦合系数 | — | 0.005 ~ 0.015 |

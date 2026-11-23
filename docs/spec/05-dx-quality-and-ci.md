# Horn 系统规约 05：工程效能、测试金字塔与 CI/CD

- 状态：Living Spec
- 日期：2026-11-22

> 关联 ADR：[ADR-0005 编译期 Debug/Release 隔离](adr/ADR-0005-compile-time-debug-release-isolation.md)、[ADR-0006 Roborazzi 视觉回归](adr/ADR-0006-roborazzi-headless-visual-regression.md)、[ADR-0003 多模块与 Konsist](adr/ADR-0003-multi-module-build-logic-and-konsist.md)。

## 0. 范围与效力

本规约定义 Horn 的工程效能契约：Debug/Release 编译期隔离、质量门禁矩阵、Baseline Profiles、R8 混淆、测试金字塔与 GitHub Actions CI/CD。跨端协议见 [04-ecosystem-and-protocols.md](04-ecosystem-and-protocols.md)。

本文使用 RFC 2119 关键词：MUST / MUST NOT / SHOULD / MAY 与必须 / 严禁 / 应当 / 可以同义。所有工具版本以 `gradle/libs.versions.toml` 与根构建脚本为唯一事实源；版本变更 MUST 同步修订本文的门禁矩阵。

## 1. Debug/Release 编译期隔离（关联 ADR-0005）

### 1.1 同名 API 双实现

DSP 调试浮层以同名 Composable API `DebugAudioOverlayHost`（签名 `accent: Color`、`telemetry: AudioTelemetry`、`readWaveform: (FloatArray) -> Int`、`modifier`）在两个源码集提供实现，使主源码集调用处零分支：

| 源码集 | 文件 | 行为 |
|---|---|---|
| `src/debug` | `feature/horn/src/debug/java/com/industrial/horn/ui/DebugAudioOverlay.kt` | 真实 DSP HUD |
| `src/release` | `feature/horn/src/release/java/com/industrial/horn/ui/DebugAudioOverlay.kt` | NoOp 空实现 |

Debug 实现的硬约束：

- 遥测展示 MUST 来自真实 `AudioTelemetry`（`engineName`、`rms`、`latencyMs`、`xrunCount`、`renderTimeMicros`、`isPlaying`），严禁合成装饰数据。
- 示波器 MUST 消费真实波形环形缓冲：`FloatArray(256)` 采样，`LaunchedEffect` 以 33 ms 周期调用 `readWaveform(scratch)` 拉取并拷贝渲染，MUST NOT 伪造波形。
- 测试锚点 MUST 稳定：容器 `testTag("debug_audio_overlay")`，展开按钮 `testTag("debug_overlay_toggle")`。
- 数值行 MUST 使用等宽数字：`fontFeatureSettings = HornNumbers.TABULAR`（`"tnum"`，定义见 `core/designsystem/src/main/java/com/industrial/horn/ui/theme/Type.kt`），防止数值刷新跳动。

Release 实现 MUST 为空实现：不引入状态、不消费遥测、不分配内存，保证调试面在编译期即从 release 产物中消失（系统不变量 I6）。

### 1.2 buildConfig=false

约定插件 MUST 统一关闭 BuildConfig 生成，杜绝以 `BuildConfig.DEBUG` 之类的运行时分支绕开源码集隔离：

- `build-logic/convention/src/main/kotlin/horn.android.application.gradle.kts`
- `build-logic/convention/src/main/kotlin/horn.android.library.gradle.kts`
- `build-logic/convention/src/main/kotlin/horn.android.wear.gradle.kts`

以上文件均含 `buildFeatures.buildConfig = false`。新增模块 MUST 复用上述约定插件，严禁在模块构建脚本内重开 buildConfig。

## 2. 质量门禁矩阵

| 门禁 | 工具/版本 | 命令 | 卡点 |
|---|---|---|---|
| 格式与排版 | Spotless 8.9.0 + ktlint 1.8.0 | `./gradlew spotlessApply`（本地）/ `./gradlew spotlessCheck`（CI） | CI `checks` job 失败即拒合 |
| Kotlin 静态分析 | Detekt 1.23.8 | `./gradlew detekt` | `buildUponDefaultConfig` + `config/detekt/detekt.yml`，`jvmTarget = 17`，违规即红 |
| Android Lint | AGP Lint（`app/lint.xml`） | `./gradlew lintDebug` | `abortOnError = true`、`warningsAsErrors = true` |
| 架构约束 | Konsist 0.17.3 | `./gradlew :app:testDebugUnitTest`（ArchitectureTest） | 4 条规则任一违反即红 |
| DSP 声学/性能断言 | JUnit 4（JVM） | `./gradlew :core:audio:testDebugUnitTest` | 15 个测试类；渲染预算与零分配断言 |
| 视觉回归 | Roborazzi 1.79.0 + Robolectric 4.18.1 | `./gradlew verifyRoborazziDebug` / `./gradlew recordRoborazziDebug` | 80 张主界面 + 8 张开屏 + 6 张仪表状态帧逐像素一致 |
| Skills 完整性 | `check_skills.py`（Python 3） | `python .github/scripts/check_skills.py --self-test` + `python .github/scripts/check_skills.py` | 结构/frontmatter/脱敏/相对链接/manifest 逐字节，含正控与负控 |
| DSP 性能基准 | `:benchmark` DspPerformanceTest | `./gradlew :benchmark:assembleBenchmark` + `./gradlew testDebugUnitTest` | 五音色渲染显著快于实时 |

### 2.1 Spotless + ktlint

根 `build.gradle.kts` 配置三类目标：

- `kotlin`：`target("**/*.kt")`，ktlint 1.8.0；
- `kotlinGradle`：`target("**/*.gradle.kts")`，ktlint 1.8.0；
- `format("misc")`：`target("**/*.md", "**/*.yml", "**/*.yaml", "**/*.xml", "**/*.toml", "**/*.pro")`，仅做 `trimTrailingWhitespace()` + `endWithNewline()`。

三类目标均排除 `**/build/**` 与 `**/.gradle/**`。本 Markdown 文档同样受 misc 目标约束：UTF-8 无 BOM、LF 换行、无行尾空格、文件末尾恰好一个换行；违反者 MUST 由 `spotlessApply` 修复后才允许提交。

### 2.2 Detekt

`subprojects` 统一应用 Detekt 1.23.8：`buildUponDefaultConfig = true`，配置文件 `config/detekt/detekt.yml`，源码范围覆盖 `src/main|test|androidTest` 的 java/kotlin 目录，`Detekt` 任务 `jvmTarget = "17"`，报告输出 html/xml/txt。架构级约束不进 Detekt，由 Konsist 执法（§2.4）。

### 2.3 Android Lint

模块级 lint 约定（约定插件 `build-logic/convention/src/main/kotlin/horn.android.application.gradle.kts` / `build-logic/convention/src/main/kotlin/horn.android.library.gradle.kts`）设置 `abortOnError = true`、`warningsAsErrors = true`；`app/build.gradle.kts` 额外指定 `lintConfig = file("lint.xml")`，即 `app/lint.xml`。

### 2.4 Konsist 架构测试

`app/src/test/java/com/industrial/horn/architecture/ArchitectureTest.kt` 固化 4 条规则，随 `:app:testDebugUnitTest` 执行：

1. `feature:horn` 与 `core:designsystem` 的 import 严禁以 `android.media.` 开头；
2. `core:audio` 的 import 严禁以 `androidx.compose.` 开头；
3. 所有 `*ViewModel` 类必须位于 `..presentation..` 包；
4. `core:audio` 严禁出现顶层 `var` 属性。

新增架构约束 MUST 以测试形式加入，严禁只写进文档。

### 2.5 DSP 声学/性能断言

`core/audio/src/test/java/com/industrial/horn/audio/` 下共 15 个测试类（另有测量工具对象 `core/audio/src/test/java/com/industrial/horn/audio/DspMeasurement.kt`，不计入），重点锚点：

- `core/audio/src/test/java/com/industrial/horn/audio/RenderBudgetTest.kt`：`FRAMES_PER_BLOCK = 128`、`REALTIME_BUDGET_MILLIS = 2.66`——128 帧 @ 48 kHz 的硬截止预算为 2.66 ms，JVM 平均渲染耗时 MUST 低于该预算。
- `core/audio/src/test/java/com/industrial/horn/audio/ZeroGcAudioPathTest.kt`：以 `ThreadMXBean.getCurrentThreadAllocatedBytes` 审计实时渲染路径，多块稳态渲染分配 MUST 低于 64 KB 预算（回归即红）。
- 其余覆盖包络（`AttackReleaseEnvelopeTest`）、安全策略（`AudioSafetyPolicyTest`）、遥测缓冲（`AudioTelemetryBufferTest`）、五音色合成（`DualToneHornSynthTest`、`TrainHornSynthTest`、`EBikeHornSynthTest`、`MotorcycleHornSynthTest`、`CustomHornSynthTest`）、渲染器（`HornRendererTest`、`PcmRendererTest`）、混响（`SpatialReverbTest`）、合成器契约（`HornSynthesizerContractTest`）与 `HornTypeTest`。

`:benchmark` 模块的 `benchmark/src/test/java/com/industrial/horn/benchmark/DspPerformanceTest.kt` 断言十秒五音色音频渲染显著快于实时（`tenSecondsOfFiveVoiceAudioRendersManyTimesFasterThanRealTime`），随全量 `./gradlew testDebugUnitTest` 执行；`./gradlew :benchmark:assembleBenchmark` 提供专用 benchmark 构建产物。

### 2.6 视觉回归（Roborazzi + Robolectric）

`app/src/test/java/com/industrial/horn/ui/ScreenshotMatrixTest.kt` 的矩阵契约：

- 维度：5 音色（`HornType` 全枚举）× 2 主题（dark/light）× 2 语言（en/zh）× 2 字号（1.0 / 1.35）× 2 布局（compact `w360dp-h800dp-xhdpi` / expanded `w840dp-h800dp-xhdpi`）= 80 张主界面基准；另有 `StartupOverlaySnapshotTest` 的 8 张开屏定格帧（wake/focus/reveal/shutter × 2 主题，`startup_wake_*` 命名空间）与 `InstrumentDialSnapshotTest` 的 6 张仪表状态帧（idle / pressure / peak × 双主题，`dial_*` 命名空间）。
- 运行器：`RobolectricTestRunner` + `@Config(sdk = [35])` + `@GraphicsMode(GraphicsMode.Mode.NATIVE)`，`captureRoboImage` 写入 `app/src/test/screenshots/`，命名 `{voice}_{dark|light}_{en|zh}_{font1_0|font1_35}_{compact|expanded}.png`。
- 任务语义：`./gradlew recordRoborazziDebug` 录制基准；`./gradlew verifyRoborazziDebug` 逐像素验证。
- 跨平台约束：字体光栅化在 Windows 与 Linux 存在像素级差异，基准 MUST 以 Linux 渲染为准；重录 MUST 通过 `.github/workflows/update-screenshots.yml` 在 Linux 执行（§5.3）。Windows 本地录制的基准严禁直接入库。

### 2.7 随仓库分发的 skills 完整性

`skills/` 是**只读、脱敏**的开发期 skill 快照（只收录本项目实际使用并产出仓库内产物的 skill），不是 Gradle 模块、不进入任何产物。

- 收录证据与脱敏记录集中在 `skills/README.md`；快照字节由 `skills/manifest.sha256` 固定。
- `check_skills.py` 校验四件事：目录/frontmatter 结构、脱敏黑名单（宿主路径/凭据/Token/私钥）、相对链接可解析、manifest 逐字节一致；`--self-test` 提供正控（合法树零误报）与负控（宿主路径、死链、name 不匹配、manifest 篡改必须变红）。
- 退出码：`0` 通过 / `2` 结构或脱敏或链接失败 / `3` manifest 不一致 / `4` 自检失败。
- CI 在 `checks` job、`pre-push` 钩子同样执行；`skills/**` 被排除在 spotless `misc` 之外，避免格式化器改写 vendored 字节。
- 维护规则：内容只读（回上游修改后重新快照）、重快照必须同步 manifest 与快照日期、新增 skill 必须能指出本项目产物。

## 3. Baseline Profiles

- 内置规则：`app/src/main/baseline-prof.txt` 随 APK 分发，安装期由 profileinstaller 消费。
- 依赖：`androidx.profileinstaller 1.4.1`（版本源 `gradle/libs.versions.toml`，装配见 `app/build.gradle.kts` 的 `implementation(libs.androidx.profileinstaller)`）。
- 生成模块：`:baselineprofile`（`baselineprofile/build.gradle.kts`）使用 `com.android.test` 插件、`targetProjectPath = ":app"`、`experimentalProperties["android.experimental.self-instrumenting"] = true`，即自插桩针对 `:app` 采集。
- 生成器：`baselineprofile/src/main/java/com/industrial/horn/baselineprofile/BaselineProfileGenerator.kt` 的 `generateHornStartupProfile` 以 `BaselineProfileRule.collect(packageName = "com.industrial.horn", includeInStartupProfile = true)` 覆盖冷启动路径（pressHome → startActivityAndWait → 等待主界面文本 → waitForIdle）。
- 执行约束：生成 MUST 在连接真机/模拟器上执行（`connectedAndroidTest` 语境），不在 CI 内自动化（见 §8）。

## 4. Release R8 混淆与保活

Release 构建 MUST 开启代码与资源压缩：`app/build.gradle.kts` 的 release 构建型设置 `isMinifyEnabled = true`、`isShrinkResources = true`，规则文件为 `getDefaultProguardFile("proguard-android-optimize.txt")` + `app/proguard-rules.pro`。

`app/proguard-rules.pro` 的契约：

```text
# 入口与 JNI 保活
-keep class com.industrial.horn.MainActivity { *; }
-keep class com.industrial.horn.HornApplication { *; }
-keep class com.industrial.horn.audio.OboeHornAudioEngine { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Release 日志剥除：v / d / i 三级
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# 可读堆栈：保留源文件与行号
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses
-renamesourcefileattribute SourceFile
```

约束：

- `MainActivity`、`HornApplication`、`OboeHornAudioEngine` MUST 保活：前两者是清单入口，后者是 JNI 桥，Oboe 原生入口的符号必须稳定。
- `keepclasseswithmembernames` 保活全部 native 方法声明。
- `Log.v/d/i` MUST 剥除以杜绝 release 日志泄露；`Log.w/e` 保留用于故障定位。
- `SourceFile` / `LineNumberTable` MUST 保留，保证线上堆栈可映射。

## 5. GitHub Actions CI/CD

### 5.1 ci.yml

`.github/workflows/ci.yml` 触发于 push（main、develop）、pull_request 与 workflow_dispatch；`permissions: contents: read`；非 main 分支的过期运行会被 concurrency 取消。公共环境：JDK 21（temurin）、`gradle/actions/setup-gradle@v4`、`GRADLE_OPTS: -Dorg.gradle.daemon=false`；NDK 27.0.12077973 与 CMake 3.22.1 **仅**在 `release-build-and-size` job 内经 runner 预装的 `sdkmanager` 安装（其余 job 无需原生编译，完全不装）。

**增量门禁机制（v3.8）**：每次 push/PR 只跑**变更影响面**内的检查，周度全量套件兜底；构建配置类变更（gradle/、build-logic/、config/、workflows、版本目录）一律保守升级为全量。影响映射由 `.github/scripts/impact_map.py` 确定性计算（变更模块 + 传递依赖扇出；含 `--self-test` 正负控，CI 内自检），纯文档变更仅跑格式与死链检查。

| job id | 显示名 | 核心命令 | 失败/产物行为 |
|---|---|---|---|
| `changes` | Impact analysis | `python3 .github/scripts/impact_map.py`（含 `--self-test`） | 输出 mode/gradle_args/needs_release 供下游裁剪 |
| `checks` | Incremental checks | `./gradlew <影响面任务集>` + `python3 .github/scripts/check_docs_links.py` | 失败上传 `check-reports`（7 天） |
| `release-build-and-size` | Release R8 Build & APK Size Report | `./gradlew :app:assembleRelease :wear:assembleRelease`（仅 `needs_release=true` 时运行） | `$GITHUB_STEP_SUMMARY` 体积表；上传 `release-apks`（5 天） |
| `ci-gate` | ci-gate（聚合门禁） | 汇总 `changes`/`checks`/`release-build-and-size` | `if: always()`；release job 的 `skipped`（判定无需）视为通过 |

**周度全量兜底**：`.github/workflows/full-suite.yml` 于每周一 02:00 UTC 定时（+ `workflow_dispatch` 手动）执行完整门禁
（`spotlessCheck detekt lintDebug testDebugUnitTest verifyRoborazziDebug` + 死链 + 双端 Release 构建与体积报告），
覆盖增量模式的漏检风险面。

说明：

- **本地优先（Local-first）**：检查面在本地由 `./gradlew checkAll` 一键执行；`.githooks/pre-push` 钩子推送前自动运行 `checkAll` + 文档死链检查（启用：`git config core.hooksPath .githooks`；紧急绕行 `git push --no-verify`）。CI 是轻量复验而非首道防线。
- **同类合并**：静态分析/单测/视觉回归/文档校验合并进单一 `checks` job 的**单次 Gradle 调用**，全工程编译只发生一次；这与本地 `checkAll` 严格同面（MUST 保持两者任务清单一致）。
- `checks` job 校验所有 README 与 `docs/**/*.md` 的相对链接及反引号源码路径必须真实存在，本文档全部锚点均受其执法。
- 视觉回归失败时的处置流程 MUST 为：确认改动是否为有意变更 → 以 `.github/workflows/update-screenshots.yml` 在 Linux 重录基准 → 提交刷新后的基准，严禁在本地（Windows）录制基准混入。

### 5.2 release.yml

`.github/workflows/release.yml` 触发于 tag `v*.*.*` 与 workflow_dispatch，单 job `release`（Build, Sign & Publish）：

1. 全量验证门：`./gradlew spotlessCheck detekt testDebugUnitTest`；
2. 构建：`./gradlew :app:assembleRelease :wear:assembleRelease`；
3. 签名优雅降级：Secret `ANDROID_KEYSTORE_BASE64` 存在时解码为 `release.jks` 并标记 `signed=true`；缺失时 MUST NOT 失败，标记 `signed=false` 并在 `$GITHUB_STEP_SUMMARY` 声明未签名发布；
4. 暂存 APK 至 `dist/` 并生成 checksums.txt（sha256 校验和），经 `softprops/action-gh-release@v2` 发布，正文按 `signed` 标记标注签名状态。

### 5.3 pr-hygiene.yml 与 update-screenshots.yml

- `.github/workflows/pr-hygiene.yml`：`title-check` 以 `amannn/action-semantic-pull-request@v5` 校验 Conventional Commits 标题（types：feat/fix/perf/refactor/ui/audio/docs/test/build/ci/chore）；`label` 以 `actions/labeler@v5`（配置 `.github/labeler.yml`，`sync-labels: true`)自动打标。
- `.github/workflows/update-screenshots.yml`：workflow_dispatch 手动触发（可选 ref），job `record`（Record Linux Baselines）在 ubuntu-latest 运行 `./gradlew recordRoborazziDebug`，上传 `roborazzi-baselines-linux` 产物（14 天），维护者下载覆盖 `app/src/test/screenshots/` 后本地 `verifyRoborazziDebug` 复核再提交。该流程是跨平台字体差异的唯一合法基准刷新通道。

### 5.4 comment-feedback.yml（评论回执）

`.github/workflows/comment-feedback.yml` 给 **PR 上的人类评论**补一个轻量回执：从调色板里选一个 reaction 贴上，作为"已读/收到"的信号，不产生任何评论、不刷屏。

- **触发**：`issue_comment: [created]`；只处理 `issue.pull_request != null` 且 `comment.user.type != 'Bot'` 的评论（Dependabot 自己的 👍 不回贴，避免回声）。
- **选择**：默认调色板 `+1 / rocket / eyes / heart / hooray`，按 `comment_id % palette.length` 确定性取一个——同一条评论永远得到同一个表情，工作流重跑不会漂移。
- **可配置**：仓库变量 `COMMENT_REACTION_EMOJIS`（逗号分隔，最多 8 个）覆盖调色板；非法值被丢弃，为空回落默认。
- **幂等**：先列该评论已有 reactions，若 `github-actions[bot]` 已存在则直接退出，重复投递不叠加。
- **手动验证**：`gh workflow run comment-feedback.yml -f comment_id=<id> -f emoji=rocket -f dry_run=false`；`dry_run` 默认 `true`，只打印决策不落 reaction，防止误触发刷屏。
- **权限**：`issues: write` + `pull-requests: write`（reaction API 属于 issues 命名空间）；其余 `contents: read`。

## 6. 测试金字塔

```mermaid
graph TD
    T3["顶层 · 设备与基准: DspPerformanceTest(:benchmark) BaselineProfileGenerator(:baselineprofile) connectedDebugAndroidTest"] --> T2["中层 · 视觉回归: ScreenshotMatrixTest 80 张 + 开屏 8 帧 + 仪表 6 帧 Roborazzi 基准 verifyRoborazziDebug"]
    T2 --> T1["底层 · DSP 与领域单测: core/audio 15 个测试类 含 RenderBudgetTest ZeroGcAudioPathTest"]
    T1 --> T0["基座 · 协议/存储/交互/架构: WearProtocolTest HornPreferencesRepositoryTest HapticSignatureTest HornViewModelTest ArchitectureTest"]
```

层级职责：

- 基座与底层（JVM 毫秒级，`testDebugUnitTest`）：协议编解码、DataStore 偏好、触觉指纹、ViewModel 交互、Konsist 架构规则、DSP 声学与预算断言。数量最大、反馈最快，是 PR 门禁的主力。
- 中层（JVM 秒级，`verifyRoborazziDebug`）：80 组合视觉矩阵逐像素回归，捕获主题/语言/字号/布局维度的渲染回归。
- 顶层（设备分钟级，本地执行）：`:benchmark` DSP 性能基准、`:baselineprofile` 冷启动基线、`connectedDebugAndroidTest` 设备交互验证。它们需要真机/模拟器，不在 CI 内运行。

## 7. 非目标（Non-Goals）

- 不在 CI 内运行 `connectedAndroidTest`、模拟器或真机测试；设备级验证为本地/手工流程（`docs/VERIFICATION.md` 保留执行记录）。
- 不引入代码覆盖率卡点（无 JaCoCo 门禁）。
- 不做自动合并：CI 全绿后仍由人工评审合并。
- 不做 nightly 长稳/monkey 测试与性能趋势监控服务。
- 不支持 Windows 本地录制视觉基准作为入库来源（见 §2.6）。
- 不做多 JDK 矩阵构建；CI 固定 JDK 21。

## 8. 已知局限与权衡（Known Limitations / Trade-offs）

- Roborazzi 跨平台字体差异：字体光栅化随操作系统变化，Windows 本地与 Linux CI 渲染不逐像素一致。权衡结果是基准一律 Linux 录制（`.github/workflows/update-screenshots.yml`），代价是基准刷新需走手动工作流。
- `connectedAndroidTest` 需真机/模拟器，CI 仅覆盖 JVM 测试；设备独有行为（音频路由、触觉、生命周期）的回归依赖本地执行记录。
- `RenderBudgetTest` / `ZeroGcAudioPathTest` 在 JVM 上测量，反映相对预算而非真机实时线程行为；真机 xrun 与延迟以运行时遥测为准。
- R8 断言缺失：`app/proguard-rules.pro` 的正确性目前由 release 构建成功与人工抽查验证，无 mapping 自动比对测试。
- 签名链路为预留：当前构建脚本未配置 AGP signingConfig，`.github/workflows/release.yml` 解码的 keystore 尚未被构建消费，发布产物实为未签名 APK；Secrets 就绪后 MUST 补齐 signingConfig 并复核发布产物。
- 分支保护若未绑定 `ci-gate` 而逐个绑定前置 job id，漏配单个 job 会留出绕过窗口；推荐只绑 `ci-gate`。
- Baseline Profile 无 CI 自动生成：`generateHornStartupProfile` 需手工在设备上跑，规则文件可能滞后于 UI 结构变化。
- 质量门禁已合并为 `checks` 单 job 单次 Gradle 调用；NDK/CMake 安装已收敛至 `release-build-and-size` 一处（曾为多 job 重复安装，已于本地优先改造中消除）。

## 9. 源码锚点

- Debug 源码集实现：`feature/horn/src/debug/java/com/industrial/horn/ui/DebugAudioOverlay.kt`
- Release NoOp 实现：`feature/horn/src/release/java/com/industrial/horn/ui/DebugAudioOverlay.kt`
- tnum 常量：`core/designsystem/src/main/java/com/industrial/horn/ui/theme/Type.kt`
- 约定插件：`build-logic/convention/src/main/kotlin/horn.android.application.gradle.kts`、`build-logic/convention/src/main/kotlin/horn.android.library.gradle.kts`、`build-logic/convention/src/main/kotlin/horn.android.wear.gradle.kts`
- 根构建脚本（Spotless/Detekt）：`build.gradle.kts`
- 版本目录：`gradle/libs.versions.toml`
- Detekt 配置：`config/detekt/detekt.yml`
- Lint 配置：`app/lint.xml`
- 架构测试：`app/src/test/java/com/industrial/horn/architecture/ArchitectureTest.kt`
- 视觉矩阵测试：`app/src/test/java/com/industrial/horn/ui/ScreenshotMatrixTest.kt`
- 视觉基准目录：`app/src/test/screenshots`
- DSP 测试目录：`core/audio/src/test/java/com/industrial/horn/audio`
- 协议单测：`core/data/src/test/java/com/industrial/horn/data/WearProtocolTest.kt`
- DSP 性能基准：`benchmark/src/test/java/com/industrial/horn/benchmark/DspPerformanceTest.kt`
- 基线生成器：`baselineprofile/src/main/java/com/industrial/horn/baselineprofile/BaselineProfileGenerator.kt`
- 基线模块脚本：`baselineprofile/build.gradle.kts`
- 内置基线规则：`app/src/main/baseline-prof.txt`
- R8 规则：`app/proguard-rules.pro`
- 应用构建脚本：`app/build.gradle.kts`
- CI 工作流：`.github/workflows/ci.yml`
- 发布工作流：`.github/workflows/release.yml`
- PR 工作流：`.github/workflows/pr-hygiene.yml`
- 基准重录工作流：`.github/workflows/update-screenshots.yml`
- 文档链路检查器：`.github/scripts/check_docs_links.py`
- 本地验收记录：`docs/VERIFICATION.md`

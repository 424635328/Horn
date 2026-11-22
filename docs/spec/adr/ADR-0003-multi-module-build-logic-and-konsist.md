# ADR-0003: 多模块工程结构、build-logic 约定插件与 Konsist 架构防腐

- 状态：Accepted
- 日期：2026-11-22

## 背景与问题

Horn（包名 com.industrial.horn）同时包含手机应用、Wear OS 手表端、实时音频引擎（core:audio，含 Oboe/NDK 路径）、触觉反馈、设置持久化、设计系统以及性能基准模块。若全部代码堆在单个 `:app` 模块中，UI、`android.media`、DSP 与数据层会互相渗透：编译器无法阻止任意类引用音频实现细节，"core:audio 不得依赖 Compose"之类的约束只能停留在文档层面，靠 code review 自觉维持。

即便拆分模块，若每个模块的 `build.gradle.kts` 各自手写完整 Android 配置，compileSdk、Java 版本、lint 策略等样板会在 9 个模块间漂移；升级 AGP 或 Kotlin 需要逐文件修改。因此需要同时解决两件事：构建样板的单一事实源，以及架构规则的机器执法。

## 决策驱动因素

- 高内聚低耦合：模块依赖方向 MUST 单向（feature 与 app 依赖 core，core 之间仅允许指向 core:audio），MUST NOT 出现反向依赖。
- 实时音频模块的确定性：core:audio MUST NOT 持有顶层可变全局状态，MUST NOT 引入 UI 框架，这些约束必须可自动检查。
- 构建样板收敛：compileSdk 36、minSdk、Java 17、lint 严格化等配置 MUST 只在 build-logic 声明一次，模块脚本只保留差异。
- 新模块接入成本应当低：接入约定插件只需一行 `id("horn.android.*")`。
- 违规必须让 CI 变红，而非依赖 code review 自觉；架构规则 MUST 以测试形式落地并随 `testDebugUnitTest` 执行。
- 模块实现细节默认以 `internal` 可见性收口，公共 API 面最小化。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 单模块（所有代码在 :app） | 配置最少，无模块间依赖管理 | UI/DSP/数据层互相渗透；无编译隔离；架构规则无法机器检查 | 否决 |
| 多模块 + 各模块手写完整 Gradle 配置 | 模块边界由编译器强制 | 9 个模块的重复样板（compileSdk 36、Java 17、lint 严格模式）易漂移；升级 AGP/Kotlin 需改多处 | 否决 |
| 多模块 + build-logic 约定插件，仅靠 code review 执法 | 样板收敛到 build-logic，配置单点升级 | 架构规则仍是"自觉"，违规只能靠人发现，历史证明会腐化 | 否决 |
| 多模块 + build-logic 约定插件 + Konsist 机器执法 | 样板收敛且规则随 `testDebugUnitTest` 自动执法 | 引入 build-logic 维护成本与少量测试运行时间 | 采纳 |

## 最终决策

1. 采用多模块结构。`settings.gradle.kts` 声明 `:app`、`:wear`、`:core:audio`、`:core:haptic`、`:core:data`、`:core:designsystem`、`:feature:horn`、`:benchmark`、`:baselineprofile` 共 9 个模块，并在 `pluginManagement` 中 `includeBuild("build-logic")`。依赖方向 MUST 单向：`:core:data` 与 `:core:haptic` 指向 `:core:audio`；`:feature:horn` 指向 4 个 core 模块；`:wear` 指向 `:core:data`；`:benchmark` 以 `testImplementation` 指向 `:core:audio`；`:baselineprofile` 通过 `targetProjectPath = ":app"` 生成基准；`:app` 聚合 feature 与 core。core 模块 MUST NOT 依赖 feature 或 app。
2. 构建样板抽入 build-logic 约定插件。build-logic 是独立 included build（`rootProject.name = "horn-build-logic"`，含 `:convention` 子项目），以 kotlin-dsl 预编译脚本插件形式提供 4 个约定插件：`horn.android.application`、`horn.android.library`、`horn.android.compose`、`horn.android.wear`。所有 Android 模块 MUST 通过约定插件获得 compileSdk 36、minSdk 26（wear 为 30）、Java 17、`buildConfig = false`、lint 严格化（`abortOnError`、`checkReleaseBuilds`、`warningsAsErrors`）与 Kotlin `allWarningsAsErrors`。依赖版本 MUST 集中于 `gradle/libs.versions.toml`（build-logic 通过 versionCatalogs 复用同一文件）。
3. 模块实现细节默认 `internal`：core:audio 的 `DefaultHornSession`、`dsp` 包（`BiquadFilter`、`WavetableOscillator`、`HarmonicWavetable`、`NoiseSource`、`ExpressionModulator`、`DspMath`）等均以 `internal` 声明，仅暴露接口与工厂。
4. 架构规则以 Konsist 机器执法。`app/src/test/java/com/industrial/horn/architecture/ArchitectureTest.kt` 固化 4 条规则：(1) `feature:horn` 与 `core:designsystem` 的 import MUST NOT 以 `android.media.` 开头；(2) `core:audio` 的 import MUST NOT 以 `androidx.compose.` 开头；(3) 所有以 `ViewModel` 结尾的类 MUST 位于 `..presentation..` 包；(4) `core:audio` MUST NOT 存在顶层 `var` 属性。这些测试随 `:app:testDebugUnitTest` 执行，CI 的 "Unit & Architecture Tests" job 以 `./gradlew testDebugUnitTest` 全量覆盖（步骤名 "All-module unit tests (DSP, Konsist, ViewModel, Wear)"）。新增架构约束 MUST 以测试形式加入，MUST NOT 只写进文档。

## 正面影响与权衡

正面影响：

- 模块边界获得双重保证：Gradle 编译隔离 + Konsist 测试执法。core:audio 的纯 JVM 单测（如 `RenderBudgetTest`、`ZeroGcAudioPathTest`）无需模拟器即可验证实时性预算。
- 构建配置单点升级：AGP 8.13.2、Kotlin 2.2.21、Compose compiler 2.2.21 只在 build-logic 与版本目录出现。
- 违规即刻在 `testDebugUnitTest` 变红，架构防腐从"评审自觉"变为"机器门禁"。

负面影响与已知局限：

- build-logic 是额外的 included build，增加构建配置复杂度与上手成本（需理解 kotlin-dsl 预编译脚本插件与 versionCatalogs 复用）。
- Konsist 只覆盖已写成测试的 4 条规则；未写成测试的语义约定（如模块间禁止传递泄漏 internal 类型）仍靠评审。
- `Konsist.scopeFromModule("feature/horn")` 以路径字符串引用模块，模块改名或移动 MUST 同步修改测试。
- 多模块与 included build 使 Gradle 配置阶段略有开销，换来的是增量构建与边界隔离，属于可接受权衡。

## 源码验证路径

- 模块清单与 build-logic 接入（9 个 include + `includeBuild("build-logic")`）：settings.gradle.kts
- build-logic 独立 settings 与版本目录复用：build-logic/settings.gradle.kts
- build-logic 根项目声明（group `com.industrial.horn.buildlogic`）：build-logic/build.gradle.kts
- 约定插件的编译依赖（AGP 8.13.2、Kotlin 2.2.21、Compose compiler 2.2.21）：build-logic/convention/build.gradle.kts
- application 约定（compileSdk 36、minSdk 26、`buildConfig = false`、lint 严格化）：build-logic/convention/src/main/kotlin/horn.android.application.gradle.kts
- library 约定（同款样板用于所有库模块）：build-logic/convention/src/main/kotlin/horn.android.library.gradle.kts
- compose 约定（按 application/library 适配开启 `buildFeatures.compose`）：build-logic/convention/src/main/kotlin/horn.android.compose.gradle.kts
- wear 约定（minSdk 30 的手表应用基线）：build-logic/convention/src/main/kotlin/horn.android.wear.gradle.kts
- Konsist 4 条架构规则：app/src/test/java/com/industrial/horn/architecture/ArchitectureTest.kt
- Konsist 依赖版本 0.17.3：gradle/libs.versions.toml
- `:core:data` 指向 `:core:audio`：core/data/build.gradle.kts
- `:core:haptic` 指向 `:core:audio`：core/haptic/build.gradle.kts
- `:feature:horn` 指向 4 个 core 模块：feature/horn/build.gradle.kts
- `:wear` 指向 `:core:data`：wear/build.gradle.kts
- `:benchmark` 以 testImplementation 指向 `:core:audio`：benchmark/build.gradle.kts
- `:baselineprofile` 以 `targetProjectPath = ":app"` 生成基准：baselineprofile/build.gradle.kts
- `:app` 聚合 feature 与 core 依赖：app/build.gradle.kts
- core:audio 构建依赖仅 core-ktx/coroutines/oboe，无 Compose：core/audio/build.gradle.kts
- `internal` 默认收口的实现示例：core/audio/src/main/java/com/industrial/horn/audio/DefaultHornSession.kt
- CI 以 `./gradlew testDebugUnitTest` 机器执法架构规则：.github/workflows/ci.yml

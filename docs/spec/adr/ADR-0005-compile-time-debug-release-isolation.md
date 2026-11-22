# ADR-0005: 以 debug/release 多 source set 编译期隔离调试示波器

- 状态：Accepted
- 日期：2026-11-22

## 背景与问题

Horn 需要一个仅供开发调试的 DSP 示波器（`DebugAudioOverlayHost`）：它消费真实音频遥测 `AudioTelemetry`（engineName、rms、latencyMs、xrunCount、renderTimeMicros、isPlaying）并以 `readWaveform: (FloatArray) -> Int` 拉取真实波形环形缓冲。这类调试面 MUST NOT 进入 Release 包——既不能增加体积，也不能在生产环境暴露音频内部状态。

最常见的做法是 `if (BuildConfig.DEBUG)` 运行时分支，但本仓库该方案根本不可用：`horn.android.application`、`horn.android.library`、`horn.android.wear` 三个约定插件均设置 `buildFeatures.buildConfig = false`，工程不生成 `BuildConfig` 类，任何 `BuildConfig.DEBUG` 引用无法编译。退一步即使重新开启 buildConfig，运行时分支仍意味着调试代码进入 Release dex，只能寄望 R8 正确剥离，缺乏编译期保证。

## 决策驱动因素

- Release 构建 MUST NOT 包含任何调试 UI 代码或资源；隔离 MUST 由编译期保证，MUST NOT 依赖 shrinker 兜底。
- 主 source set（src/main）MUST 保持无 `if` 分支，调用点无条件调用同一 API。
- Debug 与 Release 的对位 API 签名 MUST 一致，防止调用点漂移。
- `buildConfig = false` 约定 MUST 保持，不为调试分支重新引入 BuildConfig。
- Release 体积 MUST 可验证：R8 minify + 资源收缩必须真实生效。
- 调试示波器在 Debug 变体中 MUST 消费真实遥测，而非装饰性假波形。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| `BuildConfig.DEBUG` 运行时分支 | 单 source set，实现直观 | 约定插件 `buildConfig = false` 下无法编译；即使启用，调试代码仍进入 Release dex，仅靠 R8 剥离，无编译期保证 | 否决（本仓库不可用） |
| `debugImplementation` 注入调试模块 + 运行时查找 | Release 依赖图零调试代码 | 需要注册表或 ServiceLoader 式间接层，调用点复杂化，示波器与 Compose 宿主解耦成本高 | 否决 |
| 调试内容以 slot 参数由 :app 注入（:app 的 src/debug 提供实现） | feature:horn 完全不含调试代码 | 传参链路变长；每新增一个调试面都需修改 HornScreen 公共 API | 否决 |
| src/debug 与 src/release 同名双实现（多 source set） | 编译期物理隔离；主 source set 零分支；Release 零调试代码 | 同一 API 两份实现需同步维护签名 | 采纳 |

## 最终决策

1. 同名 API 双实现。`DebugAudioOverlayHost(accent, telemetry, readWaveform, modifier)` 在两个 source set 各有一份实现，包名均为 `com.industrial.horn.ui`：
   - `feature/horn/src/debug/java/com/industrial/horn/ui/DebugAudioOverlay.kt`：真实示波器。以 `FloatArray(256)` 为 scratch 缓冲，`LaunchedEffect` 内每 33 ms 调用 `readWaveform` 采样并绘制波形，标题显示 `telemetry.engineName`，数值行显示 RMS、LAT、XRUN、RENDER、PLAYING，折叠态为 "DSP" 按钮（testTag `debug_overlay_toggle` / `debug_audio_overlay`）。
   - `feature/horn/src/release/java/com/industrial/horn/ui/DebugAudioOverlay.kt`：签名完全相同的空实现（NoOp），函数体为空并注明调试仪表不得随包发布。
2. 调用点唯一且无分支。主 source set 的 `feature/horn/src/main/java/com/industrial/horn/ui/HornScreen.kt` 在布局尾部直接调用 `DebugAudioOverlayHost(accent, telemetry, readWaveform, ...)`，不存在任何 build-type 判断；`telemetry: AudioTelemetry = AudioTelemetry()` 与 `readWaveform: (FloatArray) -> Int = { 0 }` 作为参数由 `app/src/main/java/com/industrial/horn/MainActivity.kt` 注入真实实现（`viewModel::readWaveform`）。
3. source set 物理隔离由 Gradle 变体机制保证：debug 变体编译 `feature/horn/src/main` + `feature/horn/src/debug`，release 变体编译 `feature/horn/src/main` + `feature/horn/src/release`。Release 变体中示波器代码在编译期就不存在，R8 无需为它兜底。
4. `buildConfig = false` MUST 保持于全部约定插件；MUST NOT 以 `BuildConfig.DEBUG` 引入运行时分支。
5. Release 验证：`app/build.gradle.kts` 的 release buildType 启用 `isMinifyEnabled = true` 与 `isShrinkResources = true`，ProGuard 配置为 AGP 内置 `proguard-android-optimize`（经 `getDefaultProguardFile` 引用）+ `app/proguard-rules.pro`；CI 的 "Release R8 Build & APK Size Report" job 持续构建 Release APK（R8 + 资源收缩）作为回归门禁。

## 正面影响与权衡

正面影响：

- Release 二进制中零示波器代码：由编译期保证，不依赖 R8 的正确性，零体积膨胀。
- 主 source set 无 `if` 分支，`HornScreen` 的生产渲染路径保持干净可读。
- Debug 示例波器消费 `AudioTelemetryBuffer` 的真实 lock-free 遥测（原子数组写、观察端分配值对象），调试面本身即是对真实音频路径的验证。

负面影响与已知局限：

- 同名双实现需要签名同步：若修改 `DebugAudioOverlayHost` 签名，两个 source set 的文件 MUST 同时修改，否则对应变体编译失败——这是可接受的失败模式，问题在编译期暴露而非运行期。
- Release 无法在运行时临时打开示波器。这是产品决策：生产包 MUST NOT 提供调试入口。
- 每新增一个调试面板，都必须在 `src/debug` 与 `src/release` 各放一份实现（release 侧为空实现），存在少量重复样板。
- 该约定目前只覆盖 `:feature:horn`；`:wear` 或其他模块将来若需要调试面板，需重复同样的多 source set 约定。

## 源码验证路径

- Debug 真实示波器实现（消费 AudioTelemetry 与 readWaveform）：feature/horn/src/debug/java/com/industrial/horn/ui/DebugAudioOverlay.kt
- Release NoOp 空实现（同名同签名）：feature/horn/src/release/java/com/industrial/horn/ui/DebugAudioOverlay.kt
- 主 source set 无分支调用点：feature/horn/src/main/java/com/industrial/horn/ui/HornScreen.kt
- telemetry 与 readWaveform 的真实注入点：app/src/main/java/com/industrial/horn/MainActivity.kt
- 遥测数据结构与 lock-free 缓冲：core/audio/src/main/java/com/industrial/horn/audio/AudioTelemetry.kt
- `buildConfig = false`（application 约定，使 BuildConfig.DEBUG 方案不可用）：build-logic/convention/src/main/kotlin/horn.android.application.gradle.kts
- `buildConfig = false`（library 约定）：build-logic/convention/src/main/kotlin/horn.android.library.gradle.kts
- Release 的 R8 minify + 资源收缩配置：app/build.gradle.kts
- CI 的 Release R8 构建与体积报告门禁：.github/workflows/ci.yml

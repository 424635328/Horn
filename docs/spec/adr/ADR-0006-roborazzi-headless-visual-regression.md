# ADR-0006: 选用 Roborazzi + Robolectric JVM 无头截图回归

- 状态：Accepted
- 日期：2026-11-22

## 背景与问题

Horn 的主界面 `HornScreen` 是多维视觉矩阵：5 种音色（CAR、TRAIN、EBIKE、MOTORCYCLE、CUSTOM）各有独立配色与视觉风格，叠加深浅主题、中英文语言、字号缩放、compact/expanded 布局后共有 80 种呈现状态。人工目视回归不可重复、不可机器判定；模拟器截图方案需要启动 emulator，速度慢且难以稳定进入 PR 门禁。

同时存在一个现实约束：字体光栅化在不同操作系统上存在像素级差异，同一份 Compose UI 在 Windows 开发机与 Linux CI 上渲染结果不同。截图回归方案必须正视并缓解该差异，否则基准图永远处于"本地绿、CI 红"的噪声状态。

## 决策驱动因素

- 回归 MUST 能在 PR 门禁自动运行：JVM 无头执行、无需模拟器或真机。
- 像素级确定性 MUST 可控：固定 qualifiers、固定 SDK、固定图形模式，同平台渲染可重复。
- 基准图 MUST 入库可 diff；校验失败时 MUST 输出可视差异供评审。
- 录制与校验 MUST 在同一平台进行，跨平台字体差异 MUST 有明确缓解流程。
- 成本可控：80 张基准的仓库存储与重录维护成本 MUST 可接受。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 模拟器/真机截图（androidTest 管道） | 最接近真机渲染 | 启动慢、依赖 emulator 或设备农场、CI 成本高、难以稳定进 PR 门禁 | 否决 |
| 人工目视回归 | 零基础设施 | 不可重复、不可机器判定，80 种组合下必然漏检 | 否决 |
| Roborazzi + Robolectric JVM 无头截图 | 无需模拟器、速度快、与既有 JVM 单测同管道、`captureRoboImage` 直接产出基准 PNG | 渲染走 JVM 图形栈，不等于真机像素；跨平台字体光栅化有差异 | 采纳 |
| Paparazzi 类布局快照 | 同为 JVM 无头渲染 | 引入第二套渲染栈与独立 Gradle 任务，与既有 Robolectric 测试管道不互通 | 否决 |

## 最终决策

1. 采用 Roborazzi 1.79.0 + Robolectric 4.18.1（`gradle/libs.versions.toml` 钉扎；roborazzi、roborazzi-compose、roborazzi-junit-rule 三个构件 + `io.github.takahirom.roborazzi` 插件）。
2. 截图矩阵测试 `app/src/test/java/com/industrial/horn/ui/ScreenshotMatrixTest.kt`：
   - 确定性配置：`@RunWith(RobolectricTestRunner::class)`、`@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")`、`@GraphicsMode(GraphicsMode.Mode.NATIVE)`。
   - 矩阵维度：`HornType.entries` 5 音色 × 深/浅 2 主题 × en/zh 2 语言 × 字号 1.0/1.35 × compact/expanded 2 布局 = 80 张；compact 测试固定 qualifiers `w360dp-h800dp-xhdpi`，expanded 测试固定 `w840dp-h800dp-xhdpi`。
   - 每个组合以 `captureRoboImage("src/test/screenshots/$name.png")` 落盘（与测试源码一致），命名如 `car_dark_en_font1_35_compact.png`。
3. 80 张基准图 MUST 提交入库至 `app/src/test/screenshots/`（当前恰为 80 个 PNG），diff 即代码评审的一部分。
4. PR 门禁：`ci.yml` 的 "Visual Regression Tests" job（ubuntu-latest）运行 `./gradlew verifyRoborazziDebug`；失败时输出操作指引并上传 `roborazzi-diffs` 差异产物（`**/build/outputs/roborazzi/**`、`**/build/reports/roborazzi/**`）。
5. 跨平台缓解流程：基准 MUST 在 Linux 上录制。`update-screenshots.yml` 是手动触发（workflow_dispatch）的 "Record Linux Baselines" 助手，在 ubuntu-latest 运行 `./gradlew recordRoborazziDebug` 并上传 `roborazzi-baselines-linux` 产物，维护者下载覆盖 `app/src/test/screenshots/` 后本地复核再提交。Windows 本地录制的基准 MUST NOT 直接入库，否则因字体光栅化差异 CI 必然误报。

## 正面影响与权衡

正面影响：

- 截图回归进入 PR 门禁：JVM 无头执行、无需模拟器，与 DSP/Konsist/ViewModel 单测共用 `testDebugUnitTest` 管道。
- 同平台像素级确定：固定 sdk 35、固定 qualifiers、`@GraphicsMode(NATIVE)` 使 Linux CI 上渲染可重复。
- 失败可解释：`roborazzi-diffs` 直接给出逐图差异，操作指引明确指向重录流程。

负面影响与已知局限：

- 80 张基准的维护成本：`HornScreen` 的任何视觉改动都需重录并逐张评审 diff，属于真实且持续的工时投入。
- 渲染走 Robolectric NATIVE 图形栈，不等于真机像素；真机专属的渲染缺陷（GPU 合成、字体 hinting 差异）可能漏检，截图回归不能替代真机冒烟。
- 跨平台字体差异是已知现实：本地 Windows 录制与 Linux CI 校验不一致是预期行为，必须走 `update-screenshots.yml` 的 Linux 重录流程；这是本方案最大的流程负担。
- 矩阵是有限枚举：仅覆盖 en/zh 两种语言与 1.0/1.35 两档字号，不含 RTL 语言与极端字号，覆盖范围之外的回归需另行保障。

## 源码验证路径

- 80 张截图矩阵与确定性配置（sdk 35、qualifiers、GraphicsMode NATIVE）：app/src/test/java/com/industrial/horn/ui/ScreenshotMatrixTest.kt
- 已提交的 80 张基准 PNG：app/src/test/screenshots/
- 5 音色枚举（CAR/TRAIN/EBIKE/MOTORCYCLE/CUSTOM）：core/audio/src/main/java/com/industrial/horn/audio/HornType.kt
- roborazzi 1.79.0 与 robolectric 4.18.1 版本钉扎：gradle/libs.versions.toml
- PR 门禁 "Visual Regression Tests"（`verifyRoborazziDebug` + 差异上传）：.github/workflows/ci.yml
- Linux 基准录制助手（`recordRoborazziDebug`，缓解跨平台字体差异）：.github/workflows/update-screenshots.yml

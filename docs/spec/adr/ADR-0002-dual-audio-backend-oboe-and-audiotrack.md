# ADR-0002: C++ Oboe Prefab 主引擎与零 GC Kotlin AudioTrack 兜底的双引擎架构

- 状态：Accepted
- 日期：2026-11-22

## 背景与问题

按住即响的交互要求音频输出路径具备稳定的低延迟与无 GC 抖动。Android 低延迟音频的现实是：AAudio 仅在 API 26 及以上可用，本项目 minSdk 已为 26，但部分真机与模拟器上仍无法打开 AAudio 流；纯 Kotlin AudioTrack 路径兼容面广，但延迟与调度稳定性弱于原生路径。需要决定输出后端架构：单一后端还是多后端；若多后端，如何分工、如何切换、状态如何延续。同时，原生依赖的集成方式（手工放置预编译库还是构建系统托管）与 R8 混淆下的 JNI 符号稳定性都是必须解决的工程问题。

## 决策驱动因素

- 低延迟：按压到出声的延迟应当最小化，输出流应优先独占设备。
- 覆盖率：在 AAudio 不可用的设备与模拟器上仍必须能出声，严禁因后端失败而无声。
- Zero-GC：两条渲染路径的实时线程都严禁分配内存。
- 状态一致性：切换后端时音色、声学空间、Expression、护耳状态必须无缝延续，用户无感。
- 构建可重复性：原生依赖必须版本锁定并经标准构建集成，严禁手工拷贝 .so。
- 混淆安全：R8 混淆后 JNI 导出符号必须保持稳定。
- 可维护性：后端差异必须收敛在 HornAudioEngine 接口之下，上层不感知具体后端。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 仅 C++ Oboe 单引擎 | 实时路径最简；延迟最优 | AAudio 不可用时完全无声；覆盖面不全 | 否决 |
| 仅 Kotlin AudioTrack 单引擎 | 无原生构建链；兼容面广 | 延迟与调度稳定性弱于 Oboe/AAudio；GC 防护与抖动控制全靠手工 | 否决 |
| Oboe 主引擎 + 零 GC AudioTrack 兜底双引擎 | 兼顾延迟与覆盖；失败自动降级；参数镜像保持状态一致 | 两套 DSP 渲染实现需并行维护；构建链含 NDK/CMake | 采用 |
| 直接绑定 AAudio（不经 Oboe 封装） | 少一层封装，低延迟路径同样直接 | 部分设备与模拟器无法打开 AAudio 流，无兜底时完全无声；设备差异处理与降级逻辑需自行实现 | 否决 |

## 最终决策

采用 C++ Oboe Prefab 主引擎 + 零 GC Kotlin AudioTrack 兜底的双引擎架构。具体约束：

1. 原生引擎必须通过 Gradle Prefab 集成 Oboe 1.9.3（core/audio/build.gradle.kts 开启 prefab，CMake 以 find_package(oboe REQUIRED CONFIG) 引入），严禁手工放置预编译库。
2. Oboe 输出流必须以 SharingMode::Exclusive 优先打开，失败时降级 SharingMode::Shared；两种模式均必须使用 PerformanceMode::LowLatency 与 Float32（AudioFormat::Float），48000 Hz 单声道，每回调 128 帧。
3. 跨线程参数（音色类型、声学空间、Expression、护耳开关、自定义音色七项参数）必须经 std::atomic 传递，渲染回调内严禁加锁、严禁 JNI 往返。
4. JNI 导出符号必须保持 Java_com_industrial_horn_audio_OboeHornAudioEngine_* 命名；R8 必须保留 OboeHornAudioEngine 全部成员及 native 方法符号（app/proguard-rules.pro 的 keep 规则）。
5. FallbackHornAudioEngine 必须将每个参数同时镜像到主、兜底两个后端；主引擎 start 失败或上报 Failure 事件时，必须锁死兜底引擎（fallbackLocked）并 release 原生引擎，同一会话内不再切回。
6. 兜底引擎必须为零 GC 实现：AudioTrack 以 MODE_STREAM + PERFORMANCE_MODE_LOW_LATENCY + ENCODING_PCM_16BIT 单声道打开，专用写线程以 WRITE_BLOCKING 推送预分配缓冲。
7. 后端选择必须由 HornAudioEngineFactory 统一完成：原生库可用则构造 OboeHornAudioEngine 并包装 FallbackHornAudioEngine，否则直接使用 SynthAudioTrackEngine。

## 正面影响与权衡

正面影响：

- 延迟与覆盖率兼顾：能开 AAudio 的设备走独占低延迟路径，其余设备自动落到 AudioTrack，鸣笛功能不因后端失败而中断。
- 故障降级对用户无感：参数镜像保证兜底引擎启动时已持有与主引擎相同的类型、空间、Expression 与护耳状态。
- 构建可重复：Oboe 1.9.3、NDK 27.0.12077973、CMake 3.22.1 全部版本锁定，Prefab 由 Gradle 托管依赖解析。
- 混淆安全：keep 规则显式声明 JNI 边界，R8 压缩不会破坏原生符号。

负面影响与已知局限：

- Kotlin 与 C++ 两套 DSP 渲染实现并行维护，同一音色参数修改需要同步两处，存在漂移风险；当前以测试约束而非代码共享来抑制漂移。
- 双引擎引入 NDK 与 CMake 工具链依赖（ndkVersion 27.0.12077973、CMake 3.22.1），构建时间与构建机要求高于纯 JVM 工程。
- 降级是单向的：一旦锁死兜底引擎，本次会话不再尝试原生引擎。这避免反复失败打断鸣笛，但也放弃了恢复低延迟路径的机会。
- 兜底路径输出 PCM 16-bit，主引擎输出 Float32，两路数值不逐位一致；遥测数据取自当前活跃后端，切换瞬间存在口径切换。
- SharingMode::Exclusive 在部分设备上仍会被系统拒绝而降级 Shared，实际延迟取决于设备音频调度，无法承诺统一数值。

## 源码验证路径

- 原生主引擎封装 JNI 调用、原子参数发布与遥测读取：core/audio/src/main/java/com/industrial/horn/audio/OboeHornAudioEngine.kt
- 零 GC AudioTrack 兜底引擎（MODE_STREAM、PERFORMANCE_MODE_LOW_LATENCY、PCM 16-bit 单声道、专用写线程）：core/audio/src/main/java/com/industrial/horn/audio/SynthAudioTrackEngine.kt
- FallbackHornAudioEngine 参数镜像、失败锁死兜底并释放原生引擎、工厂选择逻辑：core/audio/src/main/java/com/industrial/horn/audio/HornAudioEngineFactory.kt
- 后端无关的引擎契约与事件通道：core/audio/src/main/java/com/industrial/horn/audio/HornAudioEngine.kt
- Oboe 1.9.3 经 Prefab 集成、ndkVersion 27.0.12077973、CMake 3.22.1：core/audio/build.gradle.kts
- Oboe 依赖坐标与版本号 1.9.3：gradle/libs.versions.toml
- CMake 以 find_package(oboe REQUIRED CONFIG) 链接 oboe::oboe：core/audio/src/main/cpp/CMakeLists.txt
- std::atomic 参数表与 48000 Hz / 每回调 128 帧常量：core/audio/src/main/cpp/native_engine.h
- SharingMode::Exclusive 优先、降级 Shared、PerformanceMode::LowLatency 与 Float32 流配置：core/audio/src/main/cpp/native_engine.cpp
- Java_com_industrial_horn_audio_OboeHornAudioEngine_* JNI 导出符号：core/audio/src/main/cpp/native_jni.cpp
- R8 对 OboeHornAudioEngine 与 native 方法的 keep 规则：app/proguard-rules.pro

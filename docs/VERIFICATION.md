# AutoHorn 2.0 本机验证报告

验证日期：2026-11-21（Asia/Shanghai）
工程路径：仓库根目录
版本：`2.0.0` / versionCode `2`

## 1. 环境

| 项 | 值 |
|---|---|
| Host | Windows 10.0.26300 x86_64 |
| JDK | Temurin 21.0.11+10 LTS |
| Gradle | Wrapper 8.13 |
| AGP | 8.13.2 |
| Kotlin | 2.2.21 |
| Compose BOM | 2025.06.01 |
| DataStore | 1.2.1 |
| Android SDK | platform 36 / build-tools 36.0.0 |
| 设备 | `AutoHorn_API_35` AVD（API 35，`aosp_atd` x86_64，WHPX） |
| 应用 | `com.industrial.autohorn` v2.0.0 |

## 2. 命令与结果

| 命令 | 结果 |
|---|---|
| `.\gradlew.bat :app:testDebugUnitTest` | **PASS**，58 tests / 0 failures |
| `.\gradlew.bat :app:connectedDebugAndroidTest` | **PASS**，7 tests / 0 failures / 0 skipped |
| `.\gradlew.bat :app:lintDebug` | **PASS**，0 errors / 0 warnings（`warningsAsErrors=true`） |
| `.\gradlew.bat :app:assembleDebug` | **PASS** |
| `.\gradlew.bat :app:assembleRelease` | **PASS**（R8 minify + resource shrink） |

## 3. 四音色声学验证

DSP 测量通过最终 `HornRenderer`（含包络、Makeup Gain、统一软限幅）渲染后统计。RMS 是相对满幅的线性值：

| 音色 | 主频谱验证 | Attack/Release | Makeup | 实测 RMS |
|---|---|---|---:|---:|
| Car | 400 / 500 Hz + 谐波 | 12 / 14 ms | 0.55 | 0.2239 |
| Train | 311.13 / 369.99 / 493.88 Hz 三和弦，155.56 / 123.47 Hz 低八度 | 65 / 185 ms | 0.52 | 0.1830 |
| E-Bike | 2740 / 2785 Hz 双发射器 + 685 Hz 琴体 | 4 / 6 ms | 0.18 | 0.0803 |
| Motorcycle | 444 Hz 基频 + 29 Hz 侧带 | 8 / 10 ms | 0.33 | 0.2267 |

关键频谱证据：

```text
TRAIN d#=0.272 f#=0.250 b=0.220 lowD#=0.118 lowB=0.0676 centroid=423.5
EBIKE highA=0.410 highB=0.245 body=0.135 centroid=5052.3 zcr=0.1215
MOTO carrier=0.746 lowerSideband=0.0273 upperSideband=0.0278 centroid=1390.4
MOTO windowed RMS variation=1.086  (29 Hz diaphragm flutter)
```

E-Bike 的 RMS 刻意显著低于 Car/Moto：2.7 kHz 落在人耳高敏感区，等响校准不能使用相同 RMS。所有音色最终经过 `tanh` 软限幅 + `[-0.98, 0.98]` 硬边界，PCM 峰值钳位在 ±32112。

## 4. 切换与并发验证

- `HornRendererTest` 使用 4 个请求线程与 1 个音频线程，并发执行 12,000 次音色请求 / 3,000 个音频块；无异常、无 `Short.MIN_VALUE`、音频线程观察到多次声部切换。
- 类型切换前会先渲染旧声部 Release；Car→Train 切换不会在第一块立即换波，Train 长尾测试确认 ≥30 个 5 ms 块。
- `HornRenderer.panic()` 立即回到数字静音。
- ViewModel 测试确认：鸣笛中切换 → `session.stop()` → Idle → 新音色持久化；同一音色不重复写入。

## 5. 持久化与真机行为

在 API 35 模拟器上实际执行：

1. 启动默认 Car；点击 Train；`uiautomator` 确认主按钮 contentDescription 变为 `Train horn. Press and hold to honk.`。
2. `force-stop` 后重新启动；再次 dump，`restored_train=True`，说明 DataStore 恢复生效。
3. DataStore 文件存在：`/data/data/com.industrial.autohorn/files/datastore/horn_preferences.preferences_pb`。
4. 按住 Train：`AudioTrack state:started`、`sampleRate=48000`、`GAIN_TRANSIENT`、`PAUSES_ON_DUCKABLE_LOSS`。
5. 按住期间按 HOME：焦点 `abandonAudioFocus`，AudioTrack `state:paused`，进程仍存活，`logcat -b crash` 为空。

## 6. 产物与校验和

| 产物 | 大小 | SHA-256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 11,444,086 B | `B0241DD781C8B3B81693B2FD6C879FDC049F44C6363477C2876A629504BEC3AD` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 1,046,320 B | `299C5C826C0D1288D13174F00911671052A26B7BAD8BC44397A59583FE36A8C3` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 999,280 B | `9584955969E7BC9F30F68942DC2E04713D1F3D08DD826F107C2C70C090356029` |

参考截图由 Compose `captureToImage()` 在仪器测试中生成：

- `docs/horn-screen.png`（深色 Car）
- `docs/horn-screen-light.png`（浅色 Train）

## 7. 新增依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| `androidx.datastore:datastore-preferences` | 1.2.1 | 冷启动恢复最后一次音色；异步 IO、无主线程阻塞 |

DataStore 使用自带 consumer ProGuard 规则；Release R8 构建已验证通过。

## 8. 未验证 / 残余风险

1. **实际声学听感未通过物理扬声器试听**：频谱、RMS、包络、侧带均由数值测试验证；火车/电车/摩托的主观音色仍需真机扬声器试听。
2. **硬件触觉未验证**：ATD 模拟器无真实振动马达；振幅控制与无振幅降级路径已实现并有纯 Kotlin 签名测试，机械手感需真机确认。
3. **真实低延迟未测量**：已配置 `PERFORMANCE_MODE_LOW_LATENCY`、5 ms 块与 urgent-audio 线程；模拟器不代表真机 fast-track 延迟。
4. **Release APK 未签名**：正式发布前必须配置生产签名。
5. **测试设备为 API 35**：`minSdk 26` 到 API 36 的厂商 ROM 未逐一覆盖。
6. `local.properties` 是当前机器的 SDK 路径，换机后由 Android Studio 重新生成。

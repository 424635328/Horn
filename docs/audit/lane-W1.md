# Lane W1 — 会话/引擎所有权与安全静音（P0 S1、P1 S8/S10）

## 根因

| ID | 根因（可定位到行） |
|---|---|
| S1 | `HornViewModel.onCleared()` 调用了 `session.release()`，而 `session` 是 `AppContainer` 的进程级 `by lazy` 实例。首次页面销毁就把进程级引擎永久 release，二次进入 `start()` 只能拿到 `ENGINE_UNAVAILABLE`。 |
| S8 | `DefaultHornSession.stop()` 在同一个 `synchronized(stateLock)` 里先 `engine.stop()` 再 `focusManager.abandonFocus()`。`engine.stop()` 只是请求释放包络，尾音还在渲染线程上；焦点立刻交还后，上一个应用恢复播放会被我们的尾音盖住。`panicStop()` 必须是立即交还，两者被写成了同一路径。 |
| S10 | `AudioFocusManager.focusRequest` 是普通 `var`，却被系统回调线程写、调用线程读写；焦点丢失路径只把自己置 `null`，**没有** `abandonAudioFocusRequest`，`request:abandon` 不是 1:1；且 `AUDIOFOCUS_GAIN` 分支缺失，回来时无人知晓。 |

## 改动文件

| 文件 | 行数量级 | 说明 |
|---|---|---|
| `feature/horn/.../presentation/HornViewModel.kt` | ~12 | `onCleared()` 不再 release 进程级 session，改为「停止音频 + 保留所有权」，并写明所有权契约 |
| `core/audio/.../AudioFocusManager.kt` | 全文件重写（~110 行） | 抽出 `AudioFocusSystem`（可注入）与 `FocusLedger`（CAS 记账）；丢失路径也 abandon；补 `AUDIOFOCUS_GAIN` |
| `core/audio/.../DefaultHornSession.kt` | ~35 | `stop()` 改为「请求释放 → 等静音（≤60 ms）→ 再交还焦点」，尾音等待在 session 协程里，不持锁不阻塞；`panicStop()` 保持立即 |
| `feature/horn/src/test/.../HornViewModelTest.kt` | +55 | 新增 2 条所有权测试 |
| `core/audio/src/test/.../AudioFocusManagerTest.kt` | 新增（~130 行） | 记账 1:1、重复交还、失败请求不入账、并发交还唯一成功者 |

## 前后日志（反向样本）

**改前（把 `onCleared()` 里的修复换回 `session.release()`）**

```
> Task :feature:horn:testDebugUnitTest FAILED
HornViewModelTest > clearingTheViewModelStopsAudioButNeverReleasesTheProcessSession FAILED
HornViewModelTest > aSecondLaunchedViewModelCanStillStartTheSameSession FAILED
32 tests completed, 2 failed
```

**改后**

```
HornViewModelTest tests=32 failures=0
AudioFocusManagerTest tests=5 failures=0
```

## 未覆盖项

- **S8 的时序断言未验证**：`DefaultHornSession.stop()` 的「先静音再交还」需要真实 `Context`（注册 noisy receiver）与可控引擎时钟，`core:audio` 模块测试当前只有 JUnit（无 Robolectric）。本轮只做了代码级实现 + 静态复核，**未**写自动化断言。
- **A1 的仪器测试未验证**：`launch→press→close→launch→press = GRANTED` 需要设备/模拟器。本轮用 JVM 级不变量替代（同一 session 跨 ViewModel 重建仍能 start），真机行为未验证。
- 焦点交还的尾音上限 60 ms 是硬上限而非目标值；实际尾音长度取决于 Custom 音色的 release 参数，未做端到端延迟测量。

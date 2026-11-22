# ADR-0007: Wear OS 远程鸣笛采用 MessageClient 低延迟协议

- 状态：Accepted
- 日期：2026-11-22

## 背景与问题

Wear OS 手表端需要远程遥控手机鸣笛：用户在手表上按住按钮发送 START、抬手发送 STOP，手机端起音/止音，并把当前状态（是否鸣笛、当前音色）回传手表显示。Wearable Data Layer 提供两类通道：`MessageClient`（点对点、即发即忘的消息）与 `DataClient`（`DataItem` 状态同步，自带过期、冲突、版本语义）。START/STOP 是瞬时指令，对延迟与时序敏感；而 DataClient 的同步语义会引入不确定延迟并重排时序，不适合"按住即响、松手即停"的交互。

另有一个通信约束：Wearable Data Layer 的配对通信要求手机应用与手表应用共享同一 `applicationId`。还有一条可测性约束：协议编解码逻辑若与 Google Play services 绑定，两端将无法在普通 JVM 单测中验证。

## 决策驱动因素

- START/STOP 是瞬时指令，端到端延迟 MUST 低，消息语义 MUST 保持"最新指令优先"；命令偶发丢失可接受（用户仍在按住或已松手），盲目重传反而可能造成喇叭卡响。
- 协议编解码 MUST 可脱离 Google Play services 离线测试（纯 JVM 单测）。
- 起音权限与生命周期权威 MUST 在手机 UI 处于 STARTED 时才生效，后台服务收到命令 MUST NOT 直接起音。
- 手机与手表 MUST 共享 `applicationId = "com.industrial.horn"`（Wearable Data Layer 配对通信硬性约束）。
- 未配对、断连场景下 UI MUST 可感知（提示配对），发送端 MUST 容错不崩溃。
- 手表侧 onPause 等生命周期中断 MUST 兜底发送 STOP，防止松手丢失造成长鸣。

## 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| DataClient 同步（putDataItem 下发命令） | 最终一致、可查询当前状态、自动处理离线补发 | 有过期/冲突/版本语义，延迟高且不确定；START/STOP 的瞬时时序会被同步层吞掉或重排 | 否决 |
| MessageClient 点对点消息 | 低延迟、即发即忘、时序清晰（按住发 START、松手发 STOP） | 无重传、断连丢消息、无持久状态 | 采纳 |
| 自建网络通道（TCP/UDP） | 语义完全可控 | 需自行处理设备发现、配对、安全与连接管理，成本远超收益；手表网络环境不稳定 | 否决 |
| MessageClient 与 DataClient 混合（命令走消息、状态走同步） | 状态具备最终一致性 | 双栈复杂度高；状态仅两个字段且手机侧已在状态变化时推送，收益不成比例 | 否决（预留演进空间） |

## 最终决策

1. 协议层置于 core:data，平台无关。`core/data/src/main/java/com/industrial/horn/data/WearProtocol.kt` 定义：`PATH_COMMAND = "/horn/command"`、`PATH_STATUS = "/horn/status"`、`PATH_PREFIX = "/horn"`；`WearCommand` 枚举仅 `START`/`STOP`；`WearStatus(isHonking, voice: HornType)`。编解码为 UTF-8 字节：命令载荷为枚举名字符串，状态载荷为 `"<0 或 1> + 分隔符 + HornType.name"` 两段式文本，解码对格式错误返回 null 而非抛异常。
2. 手机侧接收：`WearCommandListenerService`（继承 `WearableListenerService`）在 `onMessageReceived` 中过滤 `PATH_COMMAND`、解码后 `WearCommandBus.publish(command)`；`app/src/main/AndroidManifest.xml` 以 intent-filter `com.google.android.gms.wearable.MESSAGE_RECEIVED` + `android:pathPrefix="/horn"` 注册。
3. 生命周期权威设计：`WearCommandBus` 仅是进程内 `MutableSharedFlow`（`extraBufferCapacity = 8`、`BufferOverflow.DROP_OLDEST`，最新指令优先）；`MainActivity` 在 `repeatOnLifecycle(Lifecycle.State.STARTED)` 中 collect 后才调用 `viewModel.onPressStart()` / `viewModel.onPressEnd()`。UI 非 STARTED 时命令只进总线、不执行，后台 MUST NOT 起音，背景音频策略可预测。
4. 状态回传为 best-effort：`WearStatusSender` 经 `NodeClient.connectedNodes` 枚举节点后用 `MessageClient.sendMessage(node.id, PATH_STATUS, payload)` 扇出；手表侧 `WearMainActivity` 注册 `MessageClient.OnMessageReceivedListener` 监听 `PATH_STATUS` 刷新显示。
5. 手表侧发送：`WearLink.sendCommand` 同样经 NodeClient + `MessageClient.sendMessage(node.id, PATH_COMMAND, payload)`，整体 `runCatching` 容错；`WearMainActivity.onPause` 在仍处于按住状态时兜底发送 STOP。
6. 共享 applicationId：`wear/build.gradle.kts` 的 `applicationId = "com.industrial.horn"` 与 `app/build.gradle.kts` 完全一致，两端 MUST 作为同一应用一同发布。
7. 协议单测离线化：`core/data/src/test/java/com/industrial/horn/data/WearProtocolTest.kt` 以纯 JUnit 覆盖命令往返（`commandsRoundTrip`）、全音色状态往返（`statusRoundTripsEveryVoice`）与畸形载荷拒绝（`malformedStatusIsRejected`），不依赖 Google Play services。

## 正面影响与权衡

正面影响：

- 瞬时指令低延迟、时序清晰，符合"按住即响、松手即停"的交互语义。
- 协议层在 core:data 且平台无关，两端可在无 Play services 环境下离线单测。
- 生命周期权威明确：只有 UI 处于 STARTED 才真正起音，后台收到命令不会误鸣笛。
- 容错路径完整：发送端 `runCatching`、解码失败返回 null、手表侧 onPause 兜底 STOP。

负面影响与已知局限：

- 依赖配对手机与 Google Play services；无 Play services 的设备上 Wearable Data Layer 不可用，手表 UI 只能显示 "PAIR YOUR PHONE"。
- MessageClient 即发即忘：断连时命令丢失无重传。onPause 兜底 STOP 只能覆盖手表侧可感知的场景，极端断连下手机侧以自身生命周期守卫（onPause/onStop 停音）为最终防线。
- `DROP_OLDEST`（缓冲 8）意味着极端连发会丢最早的命令；对 START/STOP 语义这是刻意选择（最新指令优先），但不适用于需要可靠投递的载荷。
- 状态回传是 best-effort：手表显示的 "PHONE HONKING" 可能滞后或缺失，仅作指示、不作权威。
- 共享 `applicationId` 意味着手机与手表必须作为同一应用的多 APK 一同发布，不能各自独立上架。

## 源码验证路径

- 协议定义（PATH_COMMAND、PATH_STATUS、WearCommand、UTF-8 编解码）：core/data/src/main/java/com/industrial/horn/data/WearProtocol.kt
- 离线协议单测（往返与畸形拒绝）：core/data/src/test/java/com/industrial/horn/data/WearProtocolTest.kt
- 手机侧接收服务（WearableListenerService → WearCommandBus）：app/src/main/java/com/industrial/horn/wear/WearCommandListenerService.kt
- 进程内命令总线（SharedFlow、DROP_OLDEST）：app/src/main/java/com/industrial/horn/wear/WearCommandBus.kt
- 生命周期权威（STARTED 才执行 START/STOP）：app/src/main/java/com/industrial/horn/MainActivity.kt
- 手机侧状态回传（NodeClient + MessageClient 扇出）：app/src/main/java/com/industrial/horn/wear/WearStatusSender.kt
- 手表侧通道封装（sendCommand 容错、状态监听注册）：wear/src/main/java/com/industrial/horn/wear/WearLink.kt
- 手表侧 UI 与按住手势（onPause 兜底 STOP）：wear/src/main/java/com/industrial/horn/wear/WearMainActivity.kt
- MESSAGE_RECEIVED intent-filter 与 pathPrefix="/horn"：app/src/main/AndroidManifest.xml
- 手表端共享 applicationId "com.industrial.horn"：wear/build.gradle.kts
- 手机端 applicationId 对照：app/build.gradle.kts

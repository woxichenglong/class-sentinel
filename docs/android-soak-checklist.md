# ClassSentinel Android 真机 soak checklist

> 目的：验证当前已经落地的实时监听、Room 历史、提醒和即时回答在目标 Android/MIUI 设备上的长时间稳定性。
>
> 这是一份执行清单，不是自动化脚本。当前代码证据来自 `AudioRecord → AudioStreamer → SherpaOnnxStreamingEngine → StreamingListenPipeline → SessionPipelineAdapter → EventEngine / Room / Alert / AnswerService`；JVM、MockWebServer、debug APK 和 CI 不能替代真实设备行为。

## 0. 结论标记与安全红线

每一项只能填写以下之一：

- `PASS`：按通过条件完成，并留下了可回读证据。
- `FAIL`：观察到失败，记录最小复现步骤、时间点和安全日志码。
- `BLOCKED`：设备、权限、账号或工具条件阻断，记录阻断原因。
- `NOT RUN`：尚未执行，不得按“看起来应该能工作”填写通过。

安全要求：

- 不要把 API key、keystore、密码、base64 signing 内容、课堂原文、答案、原始音频或 provider body 放进日志、截图、工单或报告。
- 只保存安全日志摘要，以及模块、固定 error code、状态、耗时、计数、PSS/RSS/native heap、CPU/温度/电量等聚合证据。
- 手动完成锁屏、权限撤销、通知设置、Quick Settings Tile 和网络切换；不要使用 adb input 伪造用户点击或解锁。
- 不要用 JVM test 代替真机；JVM 结果只能作为进入本清单的前置条件。
- 完成后删除临时数据库副本、导出的 logcat 和其他测试材料中的敏感内容。

## 1. 设备与构建记录

执行前填写：

| 字段 | 值 |
|---|---|
| 设备型号 |  |
| Android/API |  |
| ROM/MIUI 版本 |  |
| ABI |  |
| 设备序列号（来自 `adb devices -l` 的完整值） |  |
| App package | `com.classsentinel` |
| versionName/versionCode |  |
| 测试 APK 路径 |  |
| 测试 APK SHA-256 |  |
| local ASR profile | `x-asr-480`（唯一 Bundled production profile） |
| 开始时间 |  |
| 结束时间 |  |
| 测试人/环境说明 |  |

开始前确认：

- [ ] 使用目标设备实际安装的 APK，不把旧 APK 的结果混到本轮。
- [ ] 记录 APK 大小和 SHA-256；不要把 hash 当作未来构建的永久固定值。
- [ ] 唯一的 `x-asr-480` 模型已准备，记录 profile ID；本轮不执行模型切换。
- [ ] 麦克风、通知、前台服务相关系统条件已记录。
- [ ] 测试期间有可控的课堂/合成音频来源，且报告不保存音频内容。

只读设备基线命令（把 `<SERIAL>` 替换为 `adb devices -l` 输出的完整序列号）：

```bash
adb devices -l
adb -s <SERIAL> shell getprop ro.product.model
adb -s <SERIAL> shell getprop ro.build.version.release
adb -s <SERIAL> shell getprop ro.build.version.sdk
adb -s <SERIAL> shell getprop ro.product.cpu.abilist
adb -s <SERIAL> shell dumpsys package com.classsentinel
adb -s <SERIAL> shell cmd appops get com.classsentinel
adb -s <SERIAL> shell dumpsys deviceidle whitelist
```

## 2. 运行期间的固定采样

在开始、每 15 分钟、每个重大状态切换后、结束时采样；报告只保留聚合值：

```bash
adb -s <SERIAL> shell dumpsys meminfo com.classsentinel
adb -s <SERIAL> shell dumpsys cpuinfo
adb -s <SERIAL> shell dumpsys batterystats --charged
adb -s <SERIAL> shell dumpsys battery
adb -s <SERIAL> shell dumpsys thermalservice
adb -s <SERIAL> shell dumpsys media.audio_flinger
adb -s <SERIAL> shell logcat -d -s ClassSentinel:I '*:S'
```

记录：

| 采样时间 | App PSS | RSS/Private Dirty | native heap | CPU | 温度 | 电量 | pipeline 状态 | safe error code |
|---|---:|---:|---:|---:|---:|---:|---|---|
|  |  |  |  |  |  |  |  |  |
|  |  |  |  |  |  |  |  |  |
|  |  |  |  |  |  |  |  |  |

采样的**通过条件**：指标能持续回读，数值异常有时间点和上下文；不能用单次通知出现证明麦克风、ASR 或 Room 一定工作喵~

## 3. 90 分钟主 soak

目标时长：**连续监听 60–90 分钟**，建议执行 90 分钟；若提前中止，必须填写实际时长和原因。

| 阶段 | 手动动作 | 观察与记录 | 通过条件 | 结果 |
|---|---|---|---|---|
| 0–15 分钟 | App 前台、屏幕亮，开始监听 | `Starting → Listening`、Final 计数、PSS/RSS/native heap | 没有重复课程/重复 collector；有真实 Final 或明确记录音频源未产生 Final | `NOT RUN` |
| 15–30 分钟 | 保持 App 前台，使用固定 X-ASR 480 | 转写数量、提醒次数、CPU/温度/电量 | 监听持续；Partial 不进入历史；Final/提醒无明显重复 | `NOT RUN` |
| 30–45 分钟 | 屏幕亮/灭切换一次 | FGS 通知、pipeline 状态、PSS/native heap | 屏幕灭后监听不无故消失；恢复亮屏后 UI 状态与服务一致 | `NOT RUN` |
| 45–60 分钟 | App 前台/后台切换，保持监听 | 后台状态、通知、CPU/温度/电量 | 后台没有静默停止或资源泄漏；若 ROM 限制导致停止，记录安全状态/时间点 | `NOT RUN` |
| 60–75 分钟 | 再次检查锁屏和通知 | 锁屏可见内容、服务状态 | 锁屏不显示不必要课堂正文；通知权限状态与 UI 记录一致 | `NOT RUN` |
| 75–90 分钟 | 继续监听，最后执行一次 stop | STOP→Stopping→Idle、Room 课程收尾 | stop 可完成；课程可在历史中读取；无活动麦克风残留 | `NOT RUN` |

主 soak 的**失败条件**：进程崩溃/ANR、状态永久卡 Starting/Stopping、出现无法解释的 duplicate course、Room 历史与 Final 明显不一致、native heap 持续无界增长、停止后仍占用麦克风，或日志出现课堂正文/凭证喵~

## 4. 场景回归矩阵

以下场景应在主 soak 期间或同一 APK 的独立 session 中执行。

### 4.1 权限、屏幕和系统生命周期

- [ ] **屏幕亮/灭**：手动熄屏和唤醒；确认监听、FGS、Live 状态和资源释放。
- [ ] **锁屏**：锁屏保持一段时间，再解锁；检查通知隐私和是否仍有状态更新。
- [ ] **通知权限撤销**：在系统设置手动撤销通知权限；确认 App 不崩溃，提醒缺失是可解释结果，恢复授权后新通知行为可观察。
- [ ] **麦克风权限中途撤销**：监听中在系统设置撤销录音权限；确认服务进入安全错误/停止路径，停止后没有继续读麦克风。
- [ ] **电池优化**：分别记录系统默认和“无限制/允许后台”设置；每种设置都要标明结果，不能把 OEM 策略差异写成代码保证。
- [ ] **App 前台/后台**：切换最近任务、回到桌面再返回；确认唯一 session 和状态没有漂移。
- [ ] **来电/音频焦点变化**：用授权的测试来电或另一个音频应用触发 audio focus；确认录音、ASR、通知和 stop/recovery 行为，不保存通话内容。

### 4.2 Quick Settings Tile

- [ ] 手动把 `ClassSentinel` Tile 加入 Quick Settings。
- [ ] 未准备模型/未授予麦克风时点击 Tile；应进入 setup/self-test 路径而不是假装开始。
- [ ] 权限和模型就绪后点击 Tile 开始，再点击停止。
- [ ] Tile、Home、Live 三处状态都与同一 `LiveStreamBus.pipelineState` 一致。
- [ ] 重复快速点击不产生重复课程、重复 collector 或重复 stop。

**通过条件**：Tile 只复用现有 START/STOP 入口，未使用屏幕自动化；Unavailable/setup 状态仍可点击且结果可解释喵~

### 4.3 Wi-Fi/移动网络断开再恢复

- [ ] 监听期间手动关闭 Wi-Fi，记录是否使用移动网络。
- [ ] 在需要 LLM/联网恢复的阶段断开 Wi-Fi/移动网络，再恢复网络。
- [ ] 记录本地 ASR 是否继续、LLM 错误是否为安全类别、网络恢复后是否能继续请求。
- [ ] 不把网络断开期间的失败误判为本地 ASR 失败；答案/总结 provider body 不进入 logcat。

### 4.4 LLM 失败/恢复

- [ ] 在不暴露 key 的前提下使用受控配置制造一次 LLM 网络/服务失败，记录 safe code 和 UI 文案。
- [ ] 恢复可用 provider/config，重新触发一个问题。
- [ ] 对已落库事件检查：失败不会写入 provider body；成功终态只写一次 answerText。
- [ ] 对 transient answer 检查：没有 eventId 时答案只在 App 内内存状态显示，不伪造 Room row/id。

### 4.5 模型与 session 生命周期

- [ ] session 1 使用 baseline profile，正常 STOP 并完成课程收尾。
- [ ] 在设置中切换到另一个 daily profile。
- [ ] **模型切换后下一 session** 启动，记录实际 profile ID 和 readiness 结果。
- [ ] 确认活动 session 不被热切换；下一 session 才使用新 profile。
- [ ] 执行 **STOP→START 多轮**，至少 5 轮；每轮记录 courseId、开始/结束时间、Final 数量和是否有重复写入。

### 4.6 进程被系统杀死后的恢复

先完成一次有数据的 session，再使用授权的停止进程方式，例如：

```bash
adb -s <SERIAL> shell am force-stop com.classsentinel
```

然后手动重新打开 App，记录：

- [ ] **进程被系统杀死后的恢复**：旧前台 session 不应因 `START_NOT_STICKY` 自动重新打开麦克风。
- [ ] 下一次打开 App 后，stale running course recovery 是否按超时规则处理。
- [ ] Room 中旧课程状态、转写数量和新课程状态是否可区分。
- [ ] 没有 orphan listener、残留 notification 或无法停止的 AudioRecord。

## 5. transcript 数量与实际 Final 对账

此项不读取或复制课堂正文，只记录聚合计数：

1. 记录监听 session 的开始/结束时间和 courseId。
2. 通过授权的 debug observer/instrumentation 记录实际收到的非空 `StreamingAsrEvent.Final` 次数。
3. 从 App 历史 UI 或只读 Room 聚合查询读取该 course 的 transcript 行数。
4. 单独读取 QUESTION/ROLLCALL event 行数，确认 Partial、`UtteranceEnded` 和空文本没有成为历史行。
5. 对账结果填写下表；任何差异都附时间段和 safe log code，不附正文。

| courseId | 实际 Final 数 | Room transcript 行数 | Room event 行数 | 差异 | 结论 |
|---:|---:|---:|---:|---:|---|
|  |  |  |  |  | `NOT RUN` |

如果需要导出数据库，只能导出到受控临时目录：

- [ ] 导出前确认文件权限和目标路径。
- [ ] 只执行 `COUNT`/聚合查询，不打印 transcript/event 正文。
- [ ] 对账完成后**删除临时数据库副本**和查询输出。
- [ ] 报告中只保留 count、courseId、时间范围和结果。

**通过条件**：每个真实非空 Final 恰好对应最多一条 transcript 历史写入；空 endpoint/flush 的 `UtteranceEnded` 对应 0 条 transcript、0 条 event、0 次 LLM/alert 业务副作用喵~

## 6. 内存、CPU/温度/电量

- [ ] 每 15 分钟记录一次 `PSS/RSS/native heap`；报告最小值、最大值、结束值和采样间隔。
- [ ] 记录 listener 前台期间的 CPU；区分 App CPU、系统负载和 ASR native 线程。
- [ ] 记录设备温度/thermal 状态；出现降频或过热时标 `FAIL` 或 `BLOCKED`，不要用估算替代。
- [ ] 记录开始/结束电量和持续时间；必要时结合 `batterystats`，不要从一次短测外推续航。
- [ ] stop 后再次采样，确认 native heap、线程、AudioRecord/音频焦点没有持续泄漏。

## 7. 证据包与报告模板

每次 soak 只提交以下安全材料：

```text
设备/ROM/API/ABI：
APK version + SHA-256：
local ASR profile：
开始时间：
结束时间：
实际监听分钟数：
STOP→START 轮数：
Final 总数：
Room transcript 总数：
Room event 总数：
PSS min/max/end：
RSS/Private Dirty min/max/end：
native heap min/max/end：
CPU 采样摘要：
温度采样摘要：
电量起止：
SafeLog errorCode 列表：
失败/阻断项及最小复现：
未执行项：
最终结论：PASS / FAIL / BLOCKED / NOT RUN
```

附加证据只允许是：

- `adb devices -l` 的设备识别信息。
- `dumpsys meminfo/cpuinfo/batterystats/thermalservice` 的聚合结果。
- `ClassSentinel` 安全日志摘要。
- App 内状态、课程计数和权限状态的必要截图；截图不得含课堂正文、答案、key 或 provider body。

不要上传完整 logcat、完整 Room 数据库、音频文件或包含正文的屏幕录制喵~

## 8. 当前准入结论

在实际执行本清单前，以下项目统一为 `NOT RUN`，不能从自动化结果推断通过：

- 连续监听 60–90 分钟。
- 屏幕亮/灭、锁屏、App 前台/后台和 Quick Settings Tile。
- Wi-Fi/移动网络断开再恢复。
- LLM 失败/恢复、通知权限撤销、麦克风权限中途撤销。
- 电池优化、来电/音频焦点变化。
- 模型切换后下一 session、STOP→START 多轮。
- 进程被系统杀死后的恢复。
- PSS/RSS/native heap、CPU/温度/电量和 transcript 数量与实际 Final 对账。

本清单完成后也只能说明指定设备、指定 APK、指定测试时段的观察结果，不能宣布项目没有其他 bug 喵~

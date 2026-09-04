# 课堂哨兵 ClassSentinel

> 大学课堂 AI 助手：后台听讲转写，老师点名/提问时提醒并生成回答，课后保存课程历史与可选总结。
> 当前文档对应 **ASR 架构重构期**：实时主链已切到本地 sherpa-onnx 连续流式接线；旧 VAD/HTTP ASR 只保留在 WAV 导入与 pending recovery 边界。自动化质量门已跑通；完整真机/MIUI 业务验收尚未完成。

[English summary](#english)

## 当前验证状态

这是源码和 JVM/构建证据，不等同于真实课堂或特定手机上的效果：

| 检查 | 命令/依据 | 结果 |
|---|---|---|
| JVM 全量测试 | `./gradlew :app:testDebugUnitTest --rerun-tasks` | 85 个测试类、437 个用例；失败 0、错误 0、跳过 0 |
| Debug APK | `./gradlew :app:assembleDebug --rerun-tasks` | 构建成功；`app/build/outputs/apk/debug/app-debug.apk` 已生成，170,883,379 bytes；SHA-256：`104e813e0fac178fa25c692d2af7e316a2932c5019aa4821cd3f19fb41aea266` |
| 设置消费者 | `SettingConsumerMatrixTest` + 源码矩阵 | 可见设置 15 个，消费者 key 15 个，集合精确相等 |
| Manifest/权限 | `app/src/main/AndroidManifest.xml` 静态检查 | 无 AccessibilityService、MediaProjection、`MANAGE_EXTERNAL_STORAGE`、开机自动录音 receiver；`allowBackup=false` |
| Room | schema v3 + `Migration1To2Test`/`Migration2To3Test` | v1/v2 数据保留，课程/转写元数据/待处理音频/学习产物表存在 |
| Android Lint | `./gradlew :app:lintDebug --rerun-tasks` | 文本报告为 `No issues found.` |
| K80 安装与冷启动 smoke | ADB 安装/回读、`am start -W`、PID/Activity/logcat | 最终 APK hash 一致；Android 16/API 36，冷启动约 977 ms，无 app 崩溃/ANR |

密钥扫描的边界也已记录：生产代码没有实际凭证命中；当前测试代码包含讯飞公开签名示例和合成测试 key，不能当作生产密钥。

## 已验证的当前 v0.3.0 能力

下列“已验证”指实现路径已存在，并由 JVM 测试或静态检查覆盖；云服务准确率、真实网络配额、后台长时间运行和手机厂商行为仍需设备测试。

### 听讲、分段与 ASR

- `AudioRecord` 以 16 kHz 单声道 PCM 采集；实时主链经 `StreamingSpeechEngine` 进入本地 sherpa-onnx 连续流式识别，保留 decoder 状态，不由 VAD 切成 HTTP 请求。
- `StreamingAsrEvent` 区分可替换的 `Partial` 和权威的 `Final`；只有 `Final` 进入事件检测、历史和 LLM，失败事件只携带封闭的安全错误类别。
- 旧 `VadSplitter`、`SegmentSpeechEngine`、HTTP ASR 和讯飞适配器暂留在 WAV 导入/pending recovery 边界；它们不作为实时课堂链路的 fallback。
- 点名支持名字表和拼音/同音变体；提问检测和滚动课堂上下文均有代码路径和 JVM 测试，实时提醒当前只保留振动与系统通知。

### 会话生命周期、通知与界面状态

- 同一时刻的重复 START 只创建一个课程/管线；STOP 会先停止管线、等待必要的持久化、完成课程收尾，再请求停止服务。
- 前台通知包含“停止听讲”动作，并显示已听时长、当前引擎和待处理段数；通知正文不放课堂原文或 AI 答案。
- Home/Live 从权威 `PipelineState` 读取 Listening、Recovering、Stopping 等状态，不另维护一个容易过期的 listening Boolean。
- 浮窗在点名/提问模式切换和新会话时清理旧内容；没有悬浮窗权限时，应用会给出可见反馈，但答案卡不会显示。

### Room 历史、总结与重点标记

- Room schema v3 保存课程状态/总结状态、转写片段偏移/标记元数据、`pending_audio_segments` 和 `study_artifacts`；v1/v2 旧课程和旧转写可通过 migration 保留。
- 课程历史支持按保留天数清理已结束课程，也支持确认后清空课程、事件、转写和待处理音频记录；正在进行的课程不会被自动保留清理删除。
- 总结可手动生成/重试；打开 `autoSummary` 后，课程收尾时仅在有转写内容且 AI 配置完整的条件下提交带 `CONNECTED` 约束的任务，网络可用后执行。空转写不会调用 LLM，状态为 `NONE/QUEUED/RUNNING/SUCCEEDED/FAILED` 并持久化。
- 内置默认四段式、考试复习、研讨课、实验课模板，也支持长度受限的自定义要求。总结正文在 UI 中按二级 Markdown 标题拆成卡片，失败只显示安全错误类别。
- Live 可标记最近一条已落库句子；课程详情支持“全部/已标记”筛选，更新时同时校验 `chunkId` 和 `courseId`，避免串课标记。

### 学习产物、WAV 导入、回放与 Tile

- 课程详情正式提供闪卡、小测、双语复习三类学习产物；请求只把课程 ID、产物类型和模式交给 WorkManager，原文与密钥在运行时从本地读取。
- 历史页使用 SAF `OpenDocument` 导入本地音频；当前明确支持 16 kHz、单声道、PCM16 WAV，并限制输入大小和内存中的单段数据。
- 音频回放遵循设置中的保留策略，只从应用私有目录读取可回放的失败/保留片段，路径经过 canonical-root 校验。
- Quick Settings Tile 直接读取权威 `PipelineState`；未配置时保持可点击并回到引导/自检，不把 `STATE_UNAVAILABLE` 当作配置提示的替代品。
- 以上功能已有 JVM 合约测试；系统文件选择器、MediaPlayer、Tile 点击和 MIUI 后台行为仍需 Android 真机验收。

## 实时本地 ASR 与旧分段恢复边界

当前实现的边界要说清楚：

1. **实时课堂 ASR 走本地 sherpa-onnx 连续流式链。** 这只解决实时转写的本地推理路径；答案生成仍可能需要用户配置的 LLM 服务。
2. WAV 导入或旧链路某个片段最终失败时，才把该失败片段写入应用私有的 `noBackupFilesDir/pending-audio`，不默认保存整节课录音。
3. `PendingTranscriptionWorker` 使用 WorkManager 的 `CONNECTED` 网络约束、指数退避和有界尝试次数；网络恢复后再按 `createdTs` 顺序转写，成功写入 Room 后才删除待处理文件。
4. 缺失/损坏文件、认证/配置错误会进入可见的终态失败，不会无限重试；WorkManager Data 不携带 API key、音频、文件路径或转写正文。

### 音频存储估算

按 16 kHz、单声道、16-bit PCM 估算，原始采样约为 32,000 bytes/s；下表未计少量 WAV 头。由于默认只保留失败片段，实际占用取决于失败片段的总时长，而不是整节课时长。

| 连续音频时长 | 约占用 |
|---:|---:|
| 1 分钟 | 1.83 MiB |
| 5 分钟 | 9.16 MiB |
| 45 分钟 | 82.40 MiB |
| 90 分钟 | 164.79 MiB |

当前版本没有“整节课录音”默认策略；也没有把离线恢复宣传成真正的离线语音识别。上述恢复流程已有 JVM 合约测试，但尚未在真机上做断网→重连的完整观察。

## AI 服务与 Command Code

AI 使用 OpenAI 兼容接口。内置预设包括 DeepSeek 官方、硅基流动和 Command Code：

| 预设 | Base URL | 模型 |
|---|---|---|
| Command Code | `https://api.commandcode.ai/provider/v1` | `deepseek/deepseek-v4-flash` |

Command Code 预设会在请求中关闭 thinking（`thinking.type=disabled`），并有 MockWebServer 测试验证。API key 必须由用户显式填写；预设不会从 ASR 设置复制 key，也不会把 key 写入 WorkManager Data。

## 设置项的真实行为

设置页当前可见设置与生产消费者矩阵严格对应（15/15），不是“能保存但运行时无效果”的占位项：

| 分组 | 已接入行为 |
|---|---|
| 点名/提问 | 名字与变体、匹配灵敏度、点名/提问抑制窗口、提问词等级 |
| 本地 ASR | sherpa-onnx 模型状态与实时本地转写；旧 VAD/HTTP ASR 只在导入/恢复边界使用 |
| 提醒 | 振动与系统通知两个通道、锁屏内容固定隐藏、震动模式；不修改系统音量 |
| AI | Base URL、AI key、模型、回答长度、答案风格、流式输出 |
| 数据/通用 | 清空问答历史、跟随系统/深色/浅色模式 |

### API key 存储与迁移

- 生产实现通过 Android Keystore 生成不可导出的 AES key，用 AES-GCM 把 AI/ASR key 写入应用私有目录的密文文件；JVM 测试使用显式 fake seam，不冒充硬件 Keystore 验收。
- 旧版 DataStore 明文迁移顺序固定为：读取旧值 → 写入 SecretStore → 精确读回 → 删除旧值 → 最后写入迁移标记。任一步失败都会保留旧值并允许下次重试。
- 普通设置仍在 DataStore；API key 不以明文设置项保存。日志只允许有限的模块、耗时、HTTP 状态、字符数、片段 ID、重试次数等诊断字段，禁止课堂原文、答案、provider body 和 key。

## 隐私边界与明确不做的事

- 课程、事件、转写、设置和待处理片段默认留在本机；应用关闭系统云备份（`allowBackup=false`）。
- 实时课堂 ASR 在本地执行；WAV 导入/pending recovery 和答案/总结等功能才会按配置访问相应 ASR/LLM 服务。项目不提供云同步、团队共享、订阅或计费。
- 不申请 `MANAGE_EXTERNAL_STORAGE`，不使用 AccessibilityService、MediaProjection、开机自动录音，也不做微信/QQ 通话录音。
- 当前 v0.3.0 正式纳入闪卡、小测、双语复习、音频回放、WAV 导入和 Quick Settings Tile；这些路径已通过 JVM 合约测试，但尚未完成真机行为验收。

## Android、MIUI 与当前限制

- 要求 Android 8.0（API 26）或更高；compileSdk/targetSdk 为 35，`versionName` 为 `0.3.0`、`versionCode` 为 `3`，源码构建使用 JDK 17，Gradle wrapper 为 8.7。
- Android 13+ 的通知权限、麦克风权限、悬浮窗权限需要用户在系统设置授权。没有悬浮窗权限时，监听和转写路径可以继续，但浮窗回答不会出现。
- MIUI/其他国产 ROM 可能需要把本应用电池策略设为“无限制”、允许自启动，并手动开启悬浮窗；否则后台服务可能被系统暂停。
- K80 已完成最终 APK 安装回读、冷启动、权限/AppOps 和 UI hierarchy smoke；这不等于 MIUI 业务验收。锁屏、全屏提醒、后台保活、真实 ASR 准确率、WAV 选择器、回放、Tile 点击和断网重连仍是后续设备门。

## 快速开始

### 方式一：下载 APK

从 [Releases](../../releases) 下载 `app-debug.apk`。如果 Releases 没有产物，请使用源码构建；当前质量门生成的 APK 仅是 debug 构建，不是设备验收证明。

### 方式二：源码构建

环境要求：JDK 17、Android SDK Platform 35。Gradle 8.7 已由 wrapper 管理。

```bash
# Git Bash：把路径替换为本机 Android SDK 路径
printf 'sdk.dir=C:/path/to/Android/Sdk\n' > local.properties

# 日常构建
./gradlew :app:testDebugUnitTest :app:assembleDebug

# 需要排除缓存影响时使用质量门命令
./gradlew :app:testDebugUnitTest --rerun-tasks
./gradlew :app:assembleDebug --rerun-tasks

# APK 产物
# app/build/outputs/apk/debug/app-debug.apk
```

Windows 命令提示符或 PowerShell 可将 `./gradlew` 替换为 `gradlew.bat`。当前使用 AGP 8.6.1，已针对 compileSdk 35；本次 `testDebugUnitTest` 和 `assembleDebug` 均成功。

### 首次使用

1. 在引导中录入名字及可选拼音变体。
2. 授予麦克风、通知权限；需要浮窗回答时另外授予悬浮窗权限。
3. 在 AI 设置中选择预设并填写 AI key；ASR key 在“语音”分组单独填写。
4. 先用自检页确认权限和配置，再开始听讲。

## 项目结构

```text
app/src/main/java/com/classsentinel/
├── core/audio       # AudioRecord、VAD、WAV 分段、失败片段私有存储
├── core/speech      # 流式 ASR 契约、本地 sherpa 实现、legacy 分段路由
├── core/context     # 最近课堂上下文
├── core/detect      # 点名/提问检测
├── core/alert       # 震动、铃声、通知、闪屏、耳机提醒
├── core/llm         # OpenAI 兼容 LLM 与 provider 预设
├── core/importer    # SAF WAV 导入与流式解析
├── core/summary     # 总结生成与模板
├── data             # Room、DataStore、迁移、历史与设置仓库
├── security         # SecretStore 与 Android Keystore 实现
├── service          # 前台服务、会话生命周期、浮窗、实时状态总线
├── tile             # Quick Settings Tile
├── ui                # Compose 页面和状态映射
└── worker            # 待处理音频、总结、历史清理 Worker
```

## 许可证

[MIT](LICENSE) © ClassSentinel Contributors

---

## English

ClassSentinel is an Android classroom assistant. Its live listening path captures foreground audio and feeds a local sherpa-onnx streaming ASR engine, then detects name calls and questions, presents alerts, and stores course history locally. Legacy VAD/HTTP ASR remains isolated for import/recovery paths. Optional answers and summaries use a user-configured OpenAI-compatible LLM.

The current source has a verified JVM gate (437 tests across 85 test classes), a clean Android Lint report, and a successful debug APK build. A K80 install/cold-start/permission smoke has run, but this is not a device certification: MIUI background limits, real local-ASR accuracy, long-running capture, import/replay behavior, Quick Settings interaction, and offline-to-online recovery still require controlled Android testing.

Important privacy boundaries:

- Only failed/untranscribed segments are retained for recovery by default; this is not offline ASR.
- API keys use an Android Keystore-backed private store in production and are migrated from legacy DataStore values only after read-back verification.
- Classroom audio/transcript/question data is sent only to the provider configured for the requested feature. There is no cloud sync, broad storage permission, boot auto-recording, AccessibilityService, MediaProjection, or call recording.
- Flashcards, quizzes, bilingual review, replay/import, and Quick Settings tile are part of the v0.3.0 source scope and have JVM contract coverage; their Android device behavior remains unverified.

Built with Kotlin 2, Jetpack Compose Material 3, Room, DataStore, WorkManager, Coroutines, OkHttp, and JUnit/Robolectric. Licensed under MIT.
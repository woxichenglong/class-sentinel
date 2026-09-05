# Changelog

本文件记录 v0.3.0 工作树中已经落地并有自动化证据的变化。真实 Android 设备、MIUI 行为和第三方服务准确率不会从 JVM 测试结果推断。

## [Unreleased] — ASR 架构重构期

### 边界收敛

- 实时主链固定为 `AudioStreamer → StreamingSpeechEngine → StreamingListenPipeline → final-only service adapter → EventEngine/DB/Alert`。
- 实时 ASR 使用本地 sherpa-onnx 连续流式实现；`Partial` 只更新展示，`Final` 才进入事件检测、历史和 LLM。
- `StreamingAsrEvent.Failed` 改为只接受封闭的 `StreamingAsrErrorKind`，避免任意异常文本跨越 live 边界。
- `StreamingListenPipeline` 收到失败事件后进入终态 `Error`，迟到 ASR 事件不能把状态覆盖回 `Listening`。
- 旧 `SpeechEngine`、`VadSplitter`、`SegmentSpeechEngine`、HTTP ASR 和 fallback 暂留在 WAV 导入/pending recovery 边界，不作为实时链路 fallback；删除前必须先证明无生产引用。
- 新增 `docs/asr-refactor-checklist.md`，记录保留、隔离、删除候选、接口守护规则和下一切片准入条件。
- 日常本地 ASR 新增 profile 选择：设置页当前只开放 14M baseline 与 small bilingual；X-ASR 480/960 留在 evaluation/debug catalog，待 live endpoint-on smoke 后再进入日常选择。选择写入独立的 `local_asr_model_id`，每次新监听按同一 profile 安装/复用模型，默认仍为 14M，切换不热切换当前会话。
- 修复点名提醒时机：新增只针对 ROLLCALL 的 Partial exact-name fast path；同一 utterance 只提前提醒一次，Final 仍权威落库，已提前提醒的最终 ROLLCALL 不重复 alert，QUESTION/LLM 仍保持 Final-only。
- 收紧点名时序：Partial 只保留 provisional 状态，confirmed suppression 只由 Final/legacy authoritative path 推进；X-ASR live 使用 endpoint-on，官方 deployment/smoke 保留 endpoint-off 独立模式。
- 修复事件状态机边界：CLASS_OPEN 与 DIRECT 使用独立 question suppression；开放式“为什么/解释”优先于尾部“吗”，Question level 与 STRICT/STANDARD/LOOSE 语义一致；FinalWindow 不再用历史姓名提升当前句 scope。
- 隔离实时副作用：姓名变体 gate 失败后继续检查完整姓名；AlertCoordinator 按通道隔离普通异常；transcript/event Room 写入失败不阻断当前提醒，QUESTION 只有拿到 eventId 才提交 LLM。
- 模型启动增加 readiness gate：完整 hash 在 IO dispatcher 执行并带 stat-signature cache；未 ready 时先准备模型，准备失败不发 live START；controller handle 的 false start 结果会进入 service failure callback。
- 收口所有 START 入口：Quick Settings Tile 改用与 Home 相同的 `LocalListenStartPreflight`，只读取 selected local profile 和模型 readiness，不再以 SiliconFlow/讯飞 key 判定本地 live 是否可用。
- 收紧问题抑制：每个 question scope 记录归一化 fingerprint 和时间；窗口内只抑制相同 normalized fingerprint，不同问题立即进入事件/提醒路径，避免整句 Levenshtein 误杀关键术语不同的问题。
- 补齐姓名定向问题：标准模式下当前 Final 的 exact configured name 可作为 answerable question 的 DIRECT targeting evidence，不放宽普通裸姓名 ROLLCALL gate。
- 收紧 DIRECT 姓名证据：新增独立 `QuestionTargetMatcher`，只接受 display/明确 aliases，不接受 ASR-only 同音字或拼音；要求姓名边界及呼语/定向续接词，避免嵌入长姓名和普通姓名提及误升级。
- 名字配置分层：`NameEntry` 区分 `display`、`aliases`、`asrVariants`；旧 JSON 的 `variants` 保守兼容为 `asrVariants`，设置页与首次引导可分别录入两类数据。

### 验证

- Detector/name/settings focused regression：3 个测试套件、48 个用例，失败 0、错误 0、跳过 0。
- JVM 全量：90 个测试类、489 个用例，失败 0、错误 0、跳过 0。
- live factory 静态检查确认不引用 VAD、旧 adapter、HTTP ASR、segment router 或 fallback。
- 新增 `ModelProfile`、profile 驱动的 hash/version installer、参数化 recognizer factory、独立 PCM/WAV replay runner、Runner 层 FAST/REALTIME 绝对音频时间轴与分层 timing、`PreparedModel` 绑定、统一 scorer 和 41 列 CSV 输出；CSV 固定记录 `scorer_version=1` 与 `normalization_profile=mixed-zh-en-v1`。默认 live 仍为 14M baseline；日常选择只开放 14M/small，X-ASR 留在 evaluation/debug catalog；X-ASR live endpoint-on 与官方 endpoint-off 由独立 config mode 区分。
- 本轮 Debug APK：223,657,379 bytes；SHA-256 为 `62829613fada8e91f204f7a9942376256cafa338486c746931d36817efba1137`。

### 模型实验门

- 加入官方 small bilingual Zipformer 的四文件 INT8/decoder-fp32 artifact、`SMALL_BILINGUAL_ZH_EN` profile 和 debug-only 外部模型 importer；默认 live 仍使用 14M baseline，small 可在设置页选择。
- 加入 X-ASR-zh-en immutable Hub revision `689ff18c584d29910da37b6fe904db0c1489c9d1` 的 `X_ASR_480`/`X_ASR_960` profile；官方 deployment CPU smoke 仍使用 endpoint-off，live profile 改为 endpoint-on，但 live endpoint-on native smoke 未完成，因此暂不进入日常模型选择列表；X-ASR 大文件未进入 APK。
- A/B/C/D 初始 FAST 只使用同一官方 `test_wavs/0.wav`（1 个公开样本），通过 Kotlin `UnifiedAsrScorer` 生成 41 列 CSV；该结果是流程/初筛证据，不替代金融课堂 corpus，也不构成 K80 winner 决定。
- 本次新 APK 尚未在 K80 重装；旧 APK 的 K80 安装/cold-start 记录不适用于本次模型资产变更。

## [Unreleased] — v0.3.0 可靠性、学习产物与音频工作流（2026-09-03）

### 新增与修复

- 将听讲流程收敛为 `AudioRecord → VAD → WavSegment → 分段 ASR 路由`；失败只影响当前片段，并保留显式 ASR 错误、fallback、超时和取消状态。
- 让 ListenService 会话启停幂等：重复 START 不重复创建课程/管线；STOP 先停止管线并完成持久化收尾，再请求停止服务。
- 前台通知增加“停止听讲”动作，并显示已听时长、ASR 引擎和待处理片段数量；通知不展示课堂原文或 AI 答案。
- Home/Live 改用权威 `PipelineState`，覆盖 Listening、Recovering、Stopping 和 Error 状态，避免 Activity 重建后状态漂移。
- Room v3 增加课程状态、总结状态、转写片段偏移/标记元数据、`pending_audio_segments` 和 `study_artifacts`；v1/v2 课程及转写数据由 migration 保留。
- 增加失败片段的应用私有存储和 WorkManager 恢复队列：只保存未成功转写的片段，使用 `CONNECTED` 约束、指数退避和有界重试。
- 总结支持自动排队（默认关闭）、手动生成/重试、四种内置模板和长度受限的自定义要求；总结状态和安全错误码持久化。
- 增加转写句子“标记最近一句”及课程详情的“全部/已标记”筛选，并校验课程 ID 防止跨课程更新。
- 让历史保留天数和“清空历史”真正执行；数据库事务提交后才清理对应的待处理音频文件。
- 增加 Command Code AI 预设：`https://api.commandcode.ai/provider/v1` + `deepseek/deepseek-v4-flash`，请求关闭 thinking。
- 修复首启引导完成状态、AI/ASR key 分离、深色模式和多个设置项的生产消费者接线。
- 正式纳入闪卡、小测和双语复习：严格校验模型 JSON，原文由本地转写注入，任务状态和失败码持久化。
- 正式纳入 SAF WAV 导入：限制为 16 kHz/单声道/PCM16，流式解析并复用分段 ASR 路由，不申请宽存储权限。
- 正式纳入失败/保留音频片段回放，并按音频保留策略限制读取范围和私有根目录。
- 正式纳入 Quick Settings Tile：状态来自权威管线，缺配置时保持可点击并跳回设置/自检。
- 修复课程详情导航参数接线：由 `course/{id}` 路由显式传入 `courseId`，学习产物、标记和回放不再落到无效课程 ID。
- 完成 fallback 备用引擎连续成功 3 句后回切主引擎，以及讯飞服务端响应后的连续静音主动 close，均有回归测试。
- 升级 AGP 到 8.6.1，补齐录音权限检查、Android 12+ 备份规则和单色启动图标；当前 lint 质量门清零。

### 安全与隐私

- AI/ASR key 通过 Android Keystore-backed AES-GCM 私有存储；旧版 DataStore 明文只在加密写入并精确读回后才删除，迁移失败保留旧值。
- 日志改为白名单元数据，禁止课堂原文、答案、provider body、文件路径和 key；WorkManager Data 只携带 ID/安全错误码。
- 默认只保留失败/未转写音频片段，不保存整节课录音；这提供的是“联网后恢复”，不是手机本地离线 ASR。
- Manifest 保持最小权限边界：无 AccessibilityService、MediaProjection、`MANAGE_EXTERNAL_STORAGE`、开机自动录音 receiver，且关闭系统云备份（`allowBackup=false`）。

### Task 34 质量门证据

以下命令均在父对话中直接执行，并使用 `--rerun-tasks` 排除 Gradle 缓存假绿：

- `./gradlew :app:testDebugUnitTest --rerun-tasks`：`BUILD SUCCESSFUL`；62 个测试套件、344 个测试用例，失败 0、错误 0、跳过 0。它证明当前 JVM 代码和集成测试整体通过，不证明真机音频或厂商后台策略。
- `./gradlew :app:assembleDebug --rerun-tasks`：`BUILD SUCCESSFUL`；生成 `app/build/outputs/apk/debug/app-debug.apk`（18,351,207 bytes），SHA-256 为 `490621b24b5ff00d7ccf931fb01255f43df6929168a543202c6a531ac17a8ea0`。它证明当前源码可打出 debug APK，不证明已完成安装/设备验收。
- 静态检查：设置消费者矩阵 26/26 精确匹配；Room schema v3、`Migrations.kt` 和 v1→v2/v2→v3 migration 测试存在；生产源码没有实际凭证命中。
- `./gradlew :app:lintDebug --rerun-tasks`：`BUILD SUCCESSFUL`；文本报告为 `No issues found.`。它证明当前 Android lint 未发现 error/warning，不替代真机业务验收。
- K80 smoke：设备 `24117RK2CC`、Android 16/API 36；最终 APK 安装后 `versionName=0.3.0`、`versionCode=3`，拉回 base.apk 与本地 SHA-256 一致；`MainActivity` 冷启动约 977 ms，权限/AppOps 读取正常，无 app 崩溃/ANR。它证明安装与冷启动边界，不证明后台监听、Tile 点击或断网恢复。
- 密钥扫描的已知基线例外仅是测试代码中的讯飞公开签名示例和合成测试 key；它们不是生产凭证，历史扫描仍会看到公开测试向量。

### 尚未完成的设备与发布验收

- K80 已完成最终 APK 安装回读、冷启动、权限/AppOps 和 UI hierarchy smoke；MIUI 悬浮窗、电池策略、自启动、通知/锁屏/全屏提醒、长时间后台监听、WAV 文件选择器、音频回放、Tile 点击和断网重连仍未认证。
- 未把第三方 ASR/LLM 的真实准确率、配额或网络连通性写成保证。
- 闪卡、小测、双语复习、音频回放、WAV 导入和 Quick Settings Tile 已纳入 v0.3.0 源码范围，并有 JVM 合约测试；设备行为仍待验收。
- 本次没有 commit 或 push；当前工作树中的前序任务改动仍需在后续提交前单独审阅。

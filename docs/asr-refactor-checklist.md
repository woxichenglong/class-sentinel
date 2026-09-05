# ASR 架构重构清单

> 这份清单以当前源码的实际生产引用为准。它只描述架构边界和迁移状态，不把 JVM 测试通过写成真实设备或真实课堂验收。

## 当前主链（必须稳定）

```text
AudioStreamer
  → StreamingSpeechEngine
  → StreamingListenPipeline
  → SessionPipelineAdapter
  → EventEngine / DB / Alert / LLM
```

已确认的 live 接线：

- `app/src/main/java/com/classsentinel/service/ListenServiceHandleFactory.kt:77-119`
  使用 `SherpaOnnxStreamingEngine`，不经过 `ProductionAsrFactory.createSpeech()`。
- `app/src/main/java/com/classsentinel/service/ListenServiceHandleFactory.kt:161-307`
  收集 streaming event；partial 只进 `LiveStreamBus`，final 才进入顺序写入、事件检测、数据库和提醒。
- `app/src/main/java/com/classsentinel/core/pipeline/StreamingListenPipeline.kt:23-140`
  负责 PCM/streaming event/生命周期状态，不负责 Room、事件策略或通知细节。
- `app/src/main/java/com/classsentinel/core/speech/SherpaOnnxStreamingEngine.kt:8-97`
  负责连续 recognizer stream、partial/final、endpoint/reset、取消和 native 资源释放。

## 接口守护规则

- `StreamingAsrEvent.Partial`：可替换的当前句预览；不得入历史、不得触发 LLM。
- `StreamingAsrEvent.Final`：唯一权威 utterance；同一 `utteranceId` 只允许一次持久化和一次事件检测。
- `StreamingAsrEvent.Failed`：只能携带 `StreamingAsrErrorKind`，不得携带异常原文、课堂文本、音频、URL 或凭证。
- `StreamingSpeechEngine`：live ASR 的唯一事件型入口；具体模型、JNI/AAR 和线程调度不得泄漏到 Pipeline 之外。
- `StreamingListenPipeline`：只拥有采集、事件转发和真实生命周期状态；停止/取消/失败必须终止当前收集并释放上游。
- `PipelineState`：唯一 live 状态源；UI、通知和 Tile 不维护第二个 listening Boolean。
- `EventEngine.processFinal`：只接收 final；窗口聚合、姓名/问题策略和抑制属于事件层，不回流到 ASR。
- DB 写入：由 final-only 适配层顺序提交；写入失败不得伪造成功，重复 final 不得重复插入。

## 实现分层

### 保留

- `[保留]` `AudioStreamer`
  - 采集 16 kHz 单声道 PCM。
  - AudioRecord、零读退避、负值错误和资源释放已有独立契约测试。
- `[保留/扩展]` `StreamingAsrEvent`
- `[保留/扩展]` `StreamingSpeechEngine`
- `[保留/收敛]` `PipelineState`
- `[保留/收敛]` `EventEngine`、`FinalTranscriptWindow`、`NameMatcher`
- `[保留]` Room entity/DAO/migration
  - 内部可继续使用 session/course 关联键；不为界面概念做无必要的大迁移。

### 重构或隔离

- `[重构]` `StreamingListenPipeline`
  - 当前已隔离 Room/通知；继续补 stop、失败终态、写入背压和 collector 生命周期契约。
- `[重构]` `ListenServiceHandleFactory` / `SessionPipelineAdapter`
  - 保持 ASR event、事件策略、持久化、提醒之间的单向边界。
  - 需要补并发 start、停止时在途 final、写入失败和重复消费测试。
- `[隔离]` `VadSplitter`
  - 不进入 live streaming 主链；仅暂留给 WAV 导入和 pending recovery。
- `[隔离]` `SpeechEngine`
  - 旧的 `Flow<ShortArray> → Flow<String>` 兼容接口，不得重新成为 live ASR fallback。
- `[隔离]` `SegmentSpeechEngine` / `SegmentSpeechRouter`
  - 只服务已切段的导入/恢复路径；不负责连续课堂监听。
- `[隔离]` `ProductionAsrFactory`
  - `createSpeech()`/旧 HTTP 装配只允许被 legacy import/recovery 使用。

### 删除候选（必须先证明无生产引用）

- `[删除候选]` `OpenAiCompatAsrEngine`
- `[删除候选]` `TeleSpeechEngine`
- `[删除候选]` `SenseVoiceEngine`
- `[删除候选]` `LegacySpeechAdapter`
- `[删除候选]` `FallbackSpeechEngine`

删除前必须满足：

1. live factory 和 live service 路径无引用；
2. WAV 导入/pending recovery 是否仍需要已单独确认；
3. 旧测试迁移或明确标为 legacy contract；
4. focused + full JVM 回归通过；
5. 不用删除类来掩盖未解决的接口耦合。

### 已有或待补齐的本地实现

- `[已有]` `SherpaOnnxStreamingEngine`
- `[已有]` `SherpaOnnxRecognizerFactory`
- `[已有]` `SherpaModelInstaller`
- `[已完成一部分]` 模型路径/recognizer port/native 资源释放
- `[已完成]` PCM graceful stop：`AudioStreamer.stop()` → PCM completion → `inputFinished`/drain → tail `Final`
- `[待补]` 明确模型生命周期 owner（安装、加载、释放、重建）
- `[待补]` 用版本 marker + SHA-256/size 校验模型安装；不能只用文件大小判断缓存有效
- `[待补]` 推理队列和背压上限；不能让 PCM 或 event 无界堆积
- `[待补]` 长时间暂停、endpoint/reset、模型异常后的可观察恢复策略
- `[待补]` 重新定义 STRICT/STANDARD/LOOSE 与 `questionWordLevel` 的关系；独立问题词设置不能被姓名 preset 偷改
- `[待补]` Listening 初始状态和 `elapsedMs` 使用真实运行时信息，不让安静时长期停在 Starting 或显示假时长
- `[待补]` 姓名 hotword 与中英混讲模型只做目标设备 corpus/性能 POC，不凭静态代码下结论
- `[待补]` 真实目标设备 POC；JVM fake 不替代准确率、功耗和 MIUI 后台验收

## 评测工具状态

- `[已完成]` `ModelProfile`：14M baseline、small bilingual、X-ASR 480/960 的 artifact、SHA-256/size、recognizer 配置、live/official endpoint 策略、能力声明和 evaluation/daily 目录集中管理。
- `[已完成]` `SherpaModelInstaller`：按 profile 校验目标文件 hash/size，使用 profile marker；有效缓存不重读 APK assets，同尺寸损坏文件会被替换。
- `[已完成]` `SherpaOnnxRecognizerFactory`：从 profile 映射 artifact 路径、provider、modelType、modelingUnit、decode、endpoint、hotword/rule 参数；显式区分 live 与 official deployment endpoint mode。
- `[已完成]` `PcmReplayRunner`：`PreparedModel` 将 profile、artifactSetHash 与 profile-bound engine 绑定；直接接受 PCM 或 PCM16 mono WAV，使用独立 `ReplayInputConfig.inputPacketMs`（默认 100ms），支持 FAST/REALTIME；REALTIME pacing 在 Runner 层按累计 sample 的绝对音频时间轴执行，并在首包真正准备发送时 lazy 建立 audio clock，WAV source 只负责切包；输出 init/decode/total timing，不经过 legacy importer/VAD。
- `[已完成]` `UnifiedAsrScorer` + CSV：输出 CER、混合词 WER、脚本级 code-switch error、专业词/姓名 Recall 与 False Discovery Rate、First Partial/Final、`steadyStateRtf` 和可选设备指标；CSV 记录 profile/artifact/run/mode/packet/timing、`scorer_version=1`、`normalization_profile=mixed-zh-en-v1`，不保存参考/识别正文。
- `[已完成]` AudioRecord stop 边界：显式 stop 后平台负读码视为 graceful EOF，正常运行期间负读码仍保持 typed failure。
- `[已完成]` small bilingual 官方 artifact、许可证、配置和四文件 size/SHA-256；已加入 `SMALL_BILINGUAL_ZH_EN` profile，可在设置页作为日常模型选择，默认仍为 14M；root artifact 的默认 chunk size 32 不映射为毫秒。
- `[已完成]` debug-only model importer：只在 debug source set 提供按 profile 导入外部四文件的私有目录 seam；replay 不绑定直接写应用私有目录。
- `[已完成]` X-ASR 官方 Hub revision `689ff18c584d29910da37b6fe904db0c1489c9d1` 的 480/960 两个 deployment artifact、许可证、配置和四文件 size/SHA-256；已加入 `X_ASR_480`/`X_ASR_960` evaluation profile，live endpoint-on、official deployment endpoint-off，未打入 APK，需先通过 debug importer 准备；live native endpoint-on smoke 仍待完成。
- `[已完成]` 点名 Partial fast path：`EventEngine.processPartialRollcall()` 仅接受文本 exact 且 `score == 1.0`，同 utterance 去重；Partial 只触发 ROLLCALL alert，不写 DB/不触发 QUESTION/LLM，provisional 不推进 confirmed suppression，Final 继续权威落库并跳过已提前提醒的重复 ROLLCALL alert。
- `[已完成]` endpoint-off 同 utteranceId 限制和 endpoint-on 两句 reset smoke 已由 JVM seam 回归锁定；真实 X-ASR native endpoint-on 行为仍需 host/目标设备验证，未据此开放日常选择。
- `[部分完成]` 用同一官方 `test_wavs/0.wav` 完成 A/B/C/D 的 1-clip FAST smoke；另有 `proxy-finance-v1` 的 30 scripts/60 WAV，B/C/D FAST 及 C/D quiet/classroom REALTIME 证据。结果只作流程/候选筛选证据，不能替代真实金融课堂 corpus 或 K80 测量。
- `[待补]` 固定金融课堂 corpus、reference transcript、扩大样本后的 FAST 结果、warm-up/交错顺序记录和 K80 E2E 采集。

## Bug 分流

### 现在修（会污染新架构）

- 状态发布顺序、错误状态被迟到事件覆盖
- start/stop 竞态、重复 collector、自然结束语义
- CancellationException 传播和 native/AudioRecord 释放
- partial 进入历史/LLM 或 final 重复入库
- EventEngine 多事件去重、互斥和窗口边界
- service 停止时在途 final 的持久化一致性
- 推理线程阻塞、队列无界增长和背压缺失
- 日志/状态/WorkManager Data 泄漏原文、路径或凭证

### 暂缓（旧实现专属）

- 云 HTTP provider 的重试参数和网络切换
- 旧 VAD 阈值、旧分段长度调优
- TeleSpeech/SenseVoice/OpenAI-compatible provider 的兼容细节
- 旧 fallback 链的恢复体验

## 已落地的架构门禁

- `StreamingListenPipeline` 在收到 `Failed` 后进入终态 `Error`，迟到事件不能恢复为 `Listening`。
- `StreamingAsrEvent.Failed` 使用封闭的 `StreamingAsrErrorKind`，不再接受任意字符串。
- `EventEngine.resetSession()` 在复用 Handle 的新 session start 时清理时间戳、final 去重集合、窗口和内部序号。
- 停止 live pipeline 不再立即取消 ASR；AudioRecord/PCM 先 graceful complete，Sherpa drain 后才释放。
- 旧云 ASR、VAD、数据库迁移和 UI 本批未做无关修改；旧 `ListenPipeline` 仅增加复用 streamer 的 start 复位。

## 下一切片准入

下一步只处理 `SessionPipelineAdapter` 的一个边界：证明并修复“停止时已经产生的 final 必须按顺序完成或以可观察失败结束”，同时覆盖重复 start/stop 和写入 Job 生命周期。该切片完成前，不删除任何 legacy ASR 类，也不扩展 provider 功能。

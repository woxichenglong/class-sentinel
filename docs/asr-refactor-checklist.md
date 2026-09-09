# ASR 架构收敛清单

> 这份清单以当前源码和 APK 产物为准：课堂实时 ASR 只有 X-ASR 480，离线、本地、无下载、无选择、无 ASR fallback。旧分段 HTTP ASR 仅服务明确的 WAV 导入与失败音频恢复。

## 当前生产主链

```text
ModelProfiles.PRODUCTION (X_ASR_480, Bundled)
  → SherpaModelInstaller
  → files/asr/x-asr-zh-en-480ms
  → ModelIntegrityVerifier / ModelReadinessChecker
  → SherpaOnnxRecognizerFactory
  → SherpaOnnxStreamingEngine
  → StreamingListenPipeline
  → SessionPipelineAdapter
  → EventEngine / Room / Alert / LLM
```

已确认的 live 接线：

- `app/src/main/java/com/classsentinel/service/ListenServiceHandleFactory.kt`
  固定使用 `ModelProfiles.PRODUCTION`，先由 `SherpaModelInstaller` 准备 bundled 目录，再做 recognizer preflight，最后创建唯一的 sherpa streaming engine。
- `app/src/main/java/com/classsentinel/core/speech/ProductionAsrEngineStarter.kt`
  只有 prepare → initialize → create 三步；初始化异常直接上抛，`CancellationException` 原样传播，不存在其他模型或 fallback 分支。
- `app/src/main/java/com/classsentinel/core/pipeline/StreamingListenPipeline.kt`
  只负责 PCM、streaming event 和生命周期；partial 仅展示，final 才进入事件/历史/LLM。
- `app/src/main/java/com/classsentinel/core/speech/SherpaOnnxStreamingEngine.kt`
  负责连续 recognizer stream、partial/final、endpoint/reset、取消和 native 资源释放。

## 保留、删除与原因

### 保留

- `AudioStreamer`、`StreamingSpeechEngine`、`StreamingAsrEvent`、`StreamingListenPipeline`、`EventEngine`、Room/DataStore 和 sherpa AAR：仍是课堂实时主链的稳定骨架。
- `SherpaModelInstaller`、`ModelIntegrityVerifier`、`ModelReadinessChecker`：X480 首次安装、完整性校验、readiness gate 仍真实需要。
- `WorkManager`：仍被 `PendingTranscriptionWorker`、`SummaryWorker`、`StudyArtifactWorker`、`RetentionCleanupWorker` 和 `PendingRecoveryResumeCoordinator` 使用。
- `OkHttp`：仍被 LLM 客户端、旧失败音频恢复的 `OpenAiCompatAsrEngine`、讯飞/其他恢复适配器使用。
- `ProductionAsrFactory`、`VadSplitter`、`SpeechEngine`、`SegmentSpeechEngine`、`SegmentSpeechRouter` 及旧 HTTP/讯飞实现：只保留给 `AudioImportService` 和 pending recovery；实时课堂工厂不引用它们。

### 删除

以下组件已无真实生产调用，连同专属 wiring、UI 和测试一起删除：

- 整套远程模型安装、HTTP Range/断点续传、多源定位和临时 sidecar 逻辑。
- 后台模型下载 Worker、下载状态/registry、下载 action 和 unique work wiring。
- debug 外部模型导入入口及其 manifest 注册。
- runtime 模型 resolver、初始化 fallback 和已删除的模型偏好读写 API。
- Settings 模型卡片、来源状态、下载/继续/取消/选择动作。
- 旧的多模型 profile、对应 APK assets、locator/选择器和专属测试。

## 模型与完整性

- 唯一 profile：`ModelProfiles.PRODUCTION`，即 `ModelProfiles.X_ASR_480`。
- distribution：`ModelDistribution.Bundled`。
- APK asset 目录只允许：`app/src/main/assets/asr/x-asr-zh-en-480ms/`。
- 安装顺序：APK asset → 同目录临时文件 → expected size → SHA-256 → 原子晋升 → `.model-profile` marker。
- 首次安装在复制大文件前比较“待安装剩余 bytes + 256 MiB 安全余量”和 `usableSpace`；不足时返回稳定的 `ASR_MODEL_STORAGE_INSUFFICIENT`，不打开 asset，不留下 Ready marker。
- 已有完整合法文件按 hash/size 复用；损坏或缺失文件重新复制；任何失败都清除 marker。

## Settings 事实

设置页只展示：

```text
语音识别模型
X-ASR 中英增强模型
已内置 · 离线可用
```

页面没有模型列表、下载、继续、取消、选择、Remote 来源或 preferred model。`asrEngine` 与 ASR credential 仍属于失败音频恢复设置，不是课堂 live 模型选择。

## 接口边界

- `Partial` 是可替换预览，不入 Room、不触发事件或 LLM。
- 非空 `Final` 是唯一权威输入；同 utterance 只处理一次。
- `Failed` 只携带封闭安全错误类别，不跨层传递异常原文、URL、凭证、音频或课堂文本。
- 初始化失败是课堂启动错误，直接交给现有 service failure path；不得改用任何其他 ASR。
- 课堂实时链不读取已退休的模型偏好字段；旧 DataStore 字段不迁移、不再读取。

## 验收清单

- [ ] X480 四个本地源文件先验 size/SHA，再复制进 assets；复制后再次验 hash。
- [ ] production profile 只有 X480 且为 Bundled。
- [ ] focused tests 覆盖 profile、Settings 静态契约、固定 runtime、初始化失败/取消、installer 损坏重装和空间不足。
- [ ] `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部在最终稳定树执行。
- [ ] 生成 APK 用 ZIP entry 实际检查：X480 四文件存在，其他旧模型文件不存在；记录 APK 字节大小。
- [ ] `git diff --check` 通过。
- [ ] 真机安装、K80/Note11 soak、native mmap 优化、模型更新、commit/push 均不属于本次范围。

JVM、lint 和 APK 只证明软件与打包事实；真实麦克风质量、MIUI 后台行为、温度/功耗和长时间稳定性仍需单独的设备验收。

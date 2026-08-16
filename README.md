# 课堂哨兵 ClassSentinel

> 大学课堂 AI 助手：后台听讲转写，老师点名/提问时多方式提醒 + AI 实时生成回答，课后四段式总结。Material Design 3 简洁风，纯 Kotlin + Jetpack Compose。

[English intro](#english)

## 特性

- 🎙️ **课堂实时转写**：前台服务持续录音 → 云端 ASR（默认免费引擎）→ 句子流
- 🔔 **点名检测**：拼音模糊匹配（同音字/方言口音兜底）+ 上下文确认词，五通道提醒（震动/铃声/锁屏通知/全屏闪屏/耳机提示音）
- ❓ **提问检测 + AI 答题**：触发词检测老师提问 → LLM 流式生成口语化答案 → 悬浮窗展示
- 📚 **历史记录**：课程/转写全文/事件时间线全落库（Room），支持浏览
- 📝 **课后总结**：转写全文 → 四段式 Markdown 总结（知识点/作业/考试重点/下节预告）
- ⚙️ **完整设置**：灵敏度三档、抑制窗口、VAD、提醒通道独立开关、AI 源、深色模式
- 🔬 **自检调试页**：权限矩阵、麦克风电平表、ASR/LLM 连通测试、模拟事件、运行日志
- 🛡️ **隐私优先**：数据本地存储，云端仅发送匿名声音片段/问题文本

## 工作原理

```
AudioRecord(16k PCM, VOICE_RECOGNITION + AEC/NS + 软件增益)
  → 自适应 VAD 分段（噪声基线 + 15dB）
  → ASR 引擎链（TeleSpeechASR 主力[免费] → SenseVoiceSmall 兜底 → 讯飞 rtasr 增强可选）
  → 句子文本流
  → 事件引擎（拼音模糊点名匹配 + 触发词提问检测 + 灵敏度三档 + 抑制窗口）
  → 提醒通道（震动/铃声/锁屏通知/全屏闪屏/耳机音，独立开关）
  + AI 回答（OpenAI 兼容 SSE 流式 → 悬浮窗卡片 → 回填数据库）
  + 历史（Room：课程/转写全文/事件）
  + 课后总结（两级压缩四段式）
```

## 快速开始

### 方式一：直接安装 APK（推荐）

从 [Releases](../../releases) 下载 `app-debug.apk`，安装到 Android 8.0+ 手机。

> 若 Releases 暂无产物，请按方式二自行构建。

### 方式二：源码构建

环境要求：JDK 17、Android SDK（compileSdk 35）、Gradle 8.7（wrapper 已含）。

```bash
# 1. 配置 SDK 路径（首次）
echo "sdk.dir=你的AndroidSDK路径" > local.properties

# 2. 跑测试 + 构建 APK
./gradlew :app:testDebugUnitTest :app:assembleDebug

# 3. 产物在 app/build/outputs/apk/debug/app-debug.apk
```

（中国大陆网络下载依赖慢时，给 gradle 加代理参数：`-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`）

### 首次使用三步配置

1. **录名字**：设置 → 名字表 → 添加你的姓名（建议加拼音变体，防同音误识别）
2. **授权限**：麦克风 + 通知；MIUI 等国产 ROM 悬浮窗需在系统设置手动开（App 内有引导）
3. **配 AI**：设置 → AI → 点「DeepSeek 官方预设」，填你的 API Key（[platform.deepseek.com](https://platform.deepseek.com) 注册）

## ASR 引擎与成本

| 引擎 | 成本 | 特点 |
|---|---|---|
| TeleSpeechASR（电信，经硅基流动） | 永久免费 | 60 方言混说，会议场景字准 94%，**默认主力** |
| SenseVoiceSmall（硅基流动） | 免费 | 兜底 |
| 讯飞实时语音转写 | 免费额度（新用户礼包）；付费 ¥9.9/h | 流式低延迟，重要课可选增强 |

- 硅基流动 ASR Key 获取：[cloud.siliconflow.cn](https://cloud.siliconflow.cn) 注册 → API 密钥（免费额度即可用）
- AI 问答任意 OpenAI 兼容服务均可（DeepSeek 官方/硅基流动/OpenRouter…）

## 设置项说明

| 分组 | 关键项 | 说明 |
|---|---|---|
| 点名 | 名字表 | 支持同音变体（如：张微, 张威, zhang wei） |
| 点名 | 匹配灵敏度 | 严格/标准/宽松（影响模糊匹配阈值与上下文确认） |
| 点名 | 抑制窗口 | 同一名字重复提醒的最小间隔 |
| 语音 | VAD 静音阈值 | 越低越敏感（课堂安静时 -35 即可） |
| 语音 | 分段最长时长 | 一句话的最长切分（建议 8s） |
| 语音 | ASR 引擎 | 三引擎可切 |
| 提醒 | 五通道 | 震动模式/铃声音量/闪屏等独立开关 |
| AI | 三件套 + 预设 | baseUrl/apiKey/model + 一键预设 |
| 数据 | 历史保留 | 7/30/90 天/永久 |

## 常见问题

**Q: 手机外放的声音也被转写了？**  
A: 已内置回声消除（AEC）+ 降噪（NS），手机自己的外放会被消除。外部声源（老师）不受影响。

**Q: 转写效果差？**  
A: 检查「分段最长时长」是否太短（<5s 会把话切碎）；离讲台近效果更好；同音人名依赖名字表拼音变体。

**Q: 切后台就不监听了？**  
A: 国产 ROM（MIUI/EMUI 等）需在系统设置将本 App 的省电策略设为「无限制」，并允许自启动。

**Q: AI 回答不显示？**  
A: 确认悬浮窗权限已开（MIUI 必须系统设置手动开）、AI 三件套已填、网络可直连 AI 服务。

## 隐私

- 名字表、灵敏度、API Key 全部仅存本机 DataStore，不上传
- 转写文本仅发往你配置的 ASR 引擎；问题文本仅发往你配置的 LLM
- 应用禁止了系统云备份（`allowBackup=false`），数据库不会被备份到云端
- 所有权限可随时在系统设置中撤销

## 技术栈

Kotlin 2 · Jetpack Compose (Material 3) · Room · DataStore · Coroutines/Flow · OkHttp SSE · TinyPinyin · 无任何网络框架以外第三方业务依赖

架构分层：`core/audio`（采集/VAD）· `core/speech`（ASR 引擎接口+实现）· `core/detect`（事件检测）· `core/alert`（提醒通道）· `core/llm`（答题）· `data`（Room+DataStore）· `service`（前台服务/悬浮窗）· `ui`（Compose 七屏）

测试：65+ JVM 单测（检测算法/引擎 MockWebServer/Room Robolectric/DataStore/签名向量），`./gradlew :app:testDebugUnitTest` 一键全跑。

## 贡献

欢迎 Issue/PR。提交前请跑全量测试。

## License

[MIT](LICENSE) © ClassSentinel Contributors

---

## English

ClassSentinel is a university classroom AI assistant for Android: it listens to lectures in the background, detects when the teacher calls your name or asks a question, alerts you via vibration/notification/floating window, and streams AI-generated spoken answers. All data stays local; cloud services are only used for speech recognition and answer generation. See [Features](#特性) above for the full list.

Built with Kotlin, Jetpack Compose (Material 3), Room, and DataStore. Licensed under MIT.

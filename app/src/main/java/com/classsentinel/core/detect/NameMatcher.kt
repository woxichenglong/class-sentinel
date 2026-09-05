package com.classsentinel.core.detect

/**
 * 名字条目：展示姓名、可直接称呼的昵称、以及只用于 ASR 容错的变体。
 * 旧的两参数构造会把历史 variants 保守地归入 asrVariants，不让同音字冒充呼语。
 */
data class NameEntry(
    val display: String,
    val aliases: List<String>,
    val asrVariants: List<String>,
) {
    /** Backward-compatible constructor for the legacy mixed `variants` field. */
    constructor(display: String, variants: List<String>) : this(
        display = display,
        aliases = emptyList(),
        asrVariants = variants,
    )
}

/**
 * 点名检测：名字表 × 精确包含/滑窗模糊 × 上下文确认词 × 排除词。
 * 名字表通过 provider 动态读取（支持 StateFlow 热更新——2026-08-16 真机 bug：
 * 服务启动时快照空名单，之后加名字永不生效）。
 * 老师喊名规律：名字 + 「来/回答/起立/说说」等指令词；
 * 「没来/请假」是缺席，不该提醒。
 */
class NameMatcher private constructor(
    private val namesProvider: () -> List<NameEntry>,
) {
    /** 静态名字表（测试/一次性场景） */
    constructor(names: List<NameEntry>) : this({ names })

    /** 动态名字表（生产：AppConfig.names 热更新） */
    constructor(namesFlow: kotlinx.coroutines.flow.StateFlow<List<NameEntry>>) : this({ namesFlow.value })

    data class Hit(
        val name: String,
        val matched: String,
        val score: Double,
        val isExact: Boolean = false,
    )

    private val contextWords =
        listOf("来", "回答", "在不在", "在吗", "起立", "上来", "说说", "讲一下", "发言")


    fun detect(segment: String, sensitivity: Sensitivity): Hit? {
        val names = namesProvider()
        if (segment.length < 2 || names.isEmpty()) return null

        var best: Hit? = null
        for (entry in names) {
            for (v in entry.aliases + entry.asrVariants + entry.display) {
                if (v.isEmpty()) continue
                // 精确包含：逐 occurrence 检查，避免其他人的「没来/请假」污染此姓名。
                var exactStart = segment.indexOf(v)
                while (exactStart >= 0) {
                    val exactEnd = exactStart + v.length
                    if (!NameMatchRules.isExcludedOccurrence(segment, exactEnd)) {
                        val hit = Hit(entry.display, v, 1.0, isExact = true)
                        contextGate(segment, hit, sensitivity, exactStart)?.let { return it }
                    }
                    exactStart = segment.indexOf(v, exactStart + 1)
                }
                // 滑窗模糊：按变体长度滑窗算拼音相似度
                val window = v.length.coerceAtLeast(2)
                if (segment.length >= window) {
                    for (start in 0..segment.length - window) {
                        if (NameMatchRules.isExcludedOccurrence(segment, start + window)) continue
                        val sub = segment.substring(start, start + window)
                        val s = PinyinFuzzy.similarity(sub, v)
                        if (s >= sensitivity.nameScoreMin && (best == null || s > best.score)) {
                            best = Hit(entry.display, sub, s)
                        }
                    }
                }
            }
        }
        return best?.let { contextGate(segment, it, sensitivity) }
    }

    /** Current configured entries for the independent question-targeting seam. */
    internal fun configuredNames(): List<NameEntry> = namesProvider()

    private fun contextGate(segment: String, hit: Hit, sensitivity: Sensitivity, matchStart: Int = segment.indexOf(hit.matched)): Hit? {
        if (sensitivity.contextRequired && contextWords.none { segment.contains(it) }) return null
        // 单字符命中必须处于点名边界，否则「王国来回答/明天来回答」里的 王/明 会被误判为点名
        if (hit.matched.length == 1 && !isCallBoundary(segment, hit.matched, matchStart)) return null
        return hit
    }

    /** 单字符命中是否处于点名边界：前置不是连续字（未被更长词嵌入），且后缀为空/空白/标点/指令词。 */
    private fun isCallBoundary(segment: String, ch: String, index: Int = segment.indexOf(ch)): Boolean {
        if (index < 0) return false
        if (index > 0 && segment[index - 1].isLetterOrDigit()) return false
        val suffix = segment.substring(index + ch.length)
        if (suffix.isEmpty()) return true
        val head = suffix.first()
        if (head.isWhitespace() || !head.isLetterOrDigit()) return true
        return contextWords.any { suffix.startsWith(it) }
    }
}

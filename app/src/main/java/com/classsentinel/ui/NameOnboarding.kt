package com.classsentinel.ui

import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.llm.NameVariantGenerationResult
import com.classsentinel.core.llm.NameVariantGenerator
import com.classsentinel.core.llm.NameVariantSanitizer
import kotlinx.coroutines.CancellationException

/** 首启姓名持久化结果；保存的条目始终沿用现有 NameEntry。 */
internal sealed interface NameOnboardingResult {
    data class Saved(
        val entry: NameEntry,
        val aiGenerated: Boolean,
        val generatedVariantCount: Int,
    ) : NameOnboardingResult

    data class ExistingConfiguration(val entry: NameEntry) : NameOnboardingResult
}

internal fun hasValidNameConfiguration(names: List<NameEntry>): Boolean =
    names.any { it.display.trim().isNotEmpty() }

/** 完成标记缺失时，已有有效姓名配置仍足以避免再次强制首启姓名引导。 */
internal fun shouldShowOnboarding(
    completed: Boolean,
    names: List<NameEntry>,
    nameSavedInCurrentOnboarding: Boolean = false,
): Boolean = when {
    completed -> false
    nameSavedInCurrentOnboarding -> true
    else -> !hasValidNameConfiguration(names)
}

internal fun parseUserAliases(input: String): List<String> =
    input.split(',', '，')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

/**
 * 先检查已有配置，再调用 AI；AI 失败只影响 ASR 变体，不影响 display/aliases 的保存。
 * [saveNames] 是现有 SettingsRepository.saveNameList 的唯一持久化入口。
 */
internal suspend fun saveOnboardingName(
    displayName: String,
    aliasesInput: String,
    existingNames: List<NameEntry>,
    generator: NameVariantGenerator,
    saveNames: suspend (List<NameEntry>) -> Unit,
    aiReady: Boolean = true,
): NameOnboardingResult {
    val result = prepareOnboardingName(
        displayName = displayName,
        aliasesInput = aliasesInput,
        existingNames = existingNames,
        generator = generator,
        aiReady = aiReady,
    )
    if (result is NameOnboardingResult.Saved) {
        saveNames(listOf(result.entry))
    }
    return result
}

/** 只生成并返回待校准的姓名草稿，不把 NameEntry 提前写进 DataStore。 */
internal suspend fun prepareOnboardingName(
    displayName: String,
    aliasesInput: String,
    existingNames: List<NameEntry>,
    generator: NameVariantGenerator,
    aiReady: Boolean = true,
): NameOnboardingResult {
    existingNames.firstOrNull { it.display.trim().isNotEmpty() }?.let {
        return NameOnboardingResult.ExistingConfiguration(it)
    }

    val display = displayName.trim()
    require(display.isNotEmpty()) { "display name must not be blank" }
    val aliases = parseUserAliases(aliasesInput)
    val generation = if (aiReady) {
        try {
            generator.generate(display)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }
    val success = generation as? NameVariantGenerationResult.Success
    val variants = success?.let {
        // 再过一层本地安全门，避免错误实现把未经校验的列表交给 NameEntry。
        NameVariantSanitizer.sanitize(display, it.asrVariants)
    }.orEmpty()
    val entry = NameEntry(
        display = display,
        aliases = aliases,
        asrVariants = variants,
    )
    return NameOnboardingResult.Saved(
        entry = entry,
        aiGenerated = success != null && variants.isNotEmpty(),
        generatedVariantCount = variants.size,
    )
}

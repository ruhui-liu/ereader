package com.ruhuiliu.ereader.translate

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/** Anthropic Claude through the official Java SDK. */
class ClaudeEngine(
    apiKey: String,
    private val model: String,
    private val extraPrompt: String,
) : TranslationEngine {
    override val signature = "claude:$model"
    override val maxBatchItems = LlmPrompt.MAX_ITEMS
    override val maxBatchChars = LlmPrompt.MAX_CHARS

    private val client: AnthropicClient? = apiKey.takeIf { it.isNotBlank() }?.let {
        AnthropicOkHttpClient.builder()
            .apiKey(it)
            .timeout(Duration.ofSeconds(180))
            .build()
    }

    /** Server-side refusal fallback is available on Opus / Sonnet, not on Haiku. */
    private val supportsFallback = model.startsWith("claude-opus") || model.startsWith("claude-sonnet")

    override suspend fun translate(texts: List<String>, source: String?, target: String): List<String> {
        val c = client ?: throw TranslationException("请先在设置中填写 Claude API Key")
        return LlmPrompt.translateWithSplitting(texts) { batch ->
            withContext(Dispatchers.IO) {
                val builder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(16000L)
                    .system(LlmPrompt.system(source, target, extraPrompt))
                    .addUserMessage(LlmPrompt.user(batch))
                    // Translation is a light task: low effort keeps latency and cost down.
                    .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                if (supportsFallback) {
                    builder.addBeta("server-side-fallback-2026-07-01")
                    builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
                val message = try {
                    c.beta().messages().create(builder.build())
                } catch (e: Exception) {
                    throw TranslationException("Claude 请求失败：${e.message}", e)
                }
                if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                    throw TranslationException("Claude 拒绝翻译这段内容")
                }
                message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
            }
        }
    }

    override fun close() {
        client?.close()
    }

    companion object {
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5（质量最好）",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5（更快更便宜）",
            "claude-haiku-5-5" to "Claude Haiku 5.5（最便宜）",
        )
    }
}

package com.ruhuiliu.ereader.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).post(body.toRequestBody(JSON)).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw TranslationException("HTTP ${resp.code}：${errorMessage(text).take(300)}")
                }
                text
            }
        }

    private fun errorMessage(body: String): String = runCatching {
        val o = JSONObject(body)
        o.optJSONObject("error")?.optString("message")?.ifEmpty { null }
            ?: o.optString("message").ifEmpty { null }
            ?: body
    }.getOrDefault(body)
}

/** Google Cloud Translation API v2 (API key). */
class GoogleCloudEngine(private val apiKey: String) : TranslationEngine {
    override val signature = "google"
    override val maxBatchItems = 64
    override val maxBatchChars = 20_000

    override suspend fun translate(texts: List<String>, source: String?, target: String): List<String> {
        if (apiKey.isBlank()) throw TranslationException("请先在设置中填写 Google Cloud API Key")
        val body = JSONObject()
            .put("q", JSONArray(texts))
            .put("target", target)
            .put("format", "text")
        if (source != null) body.put("source", source.substringBefore('-').let { if (source.startsWith("zh")) source else it })
        val resp = JSONObject(Http.postJson("https://translation.googleapis.com/language/translate/v2?key=$apiKey", body.toString()))
        val arr = resp.getJSONObject("data").getJSONArray("translations")
        return List(arr.length()) { arr.getJSONObject(it).getString("translatedText") }
    }
}

/** DeepL API (free keys end with ":fx"). */
class DeepLEngine(private val apiKey: String) : TranslationEngine {
    override val signature = "deepl"
    override val maxBatchItems = 50
    override val maxBatchChars = 25_000

    private fun targetCode(t: String) = when (t) {
        "zh-CN" -> "ZH-HANS"
        "zh-TW" -> "ZH-HANT"
        "en" -> "EN-US"
        else -> t.uppercase()
    }

    override suspend fun translate(texts: List<String>, source: String?, target: String): List<String> {
        if (apiKey.isBlank()) throw TranslationException("请先在设置中填写 DeepL API Key")
        val host = if (apiKey.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val body = JSONObject()
            .put("text", JSONArray(texts))
            .put("target_lang", targetCode(target))
            .put("preserve_formatting", true)
        if (source != null) body.put("source_lang", source.substringBefore('-').uppercase())
        val resp = JSONObject(
            Http.postJson("$host/v2/translate", body.toString(), mapOf("Authorization" to "DeepL-Auth-Key $apiKey"))
        )
        val arr = resp.getJSONArray("translations")
        return List(arr.length()) { arr.getJSONObject(it).getString("text") }
    }
}

/** Microsoft Translator (Azure AI Translator) v3. */
class AzureEngine(private val apiKey: String, private val region: String) : TranslationEngine {
    override val signature = "azure"
    override val maxBatchItems = 50
    override val maxBatchChars = 20_000

    private fun code(t: String) = when (t) {
        "zh-CN" -> "zh-Hans"
        "zh-TW" -> "zh-Hant"
        else -> t
    }

    override suspend fun translate(texts: List<String>, source: String?, target: String): List<String> {
        if (apiKey.isBlank()) throw TranslationException("请先在设置中填写 Azure 翻译密钥")
        var url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=${code(target)}"
        if (source != null) url += "&from=${code(source)}"
        val body = JSONArray(texts.map { JSONObject().put("Text", it) })
        val headers = buildMap {
            put("Ocp-Apim-Subscription-Key", apiKey)
            if (region.isNotBlank()) put("Ocp-Apim-Subscription-Region", region)
        }
        val arr = JSONArray(Http.postJson(url, body.toString(), headers))
        return List(arr.length()) { arr.getJSONObject(it).getJSONArray("translations").getJSONObject(0).getString("text") }
    }
}

/** Any OpenAI-compatible chat completions endpoint (OpenAI, DeepSeek, Qwen, Moonshot, Ollama...). */
class OpenAiCompatibleEngine(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val extraPrompt: String,
) : TranslationEngine {
    private val endpoint = baseUrl.trim().trimEnd('/').let {
        if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
    }
    override val signature = "openai:$model"
    override val maxBatchItems = LlmPrompt.MAX_ITEMS
    override val maxBatchChars = LlmPrompt.MAX_CHARS

    override suspend fun translate(texts: List<String>, source: String?, target: String): List<String> {
        if (model.isBlank()) throw TranslationException("请先在设置中填写模型名称")
        return LlmPrompt.translateWithSplitting(texts) { batch ->
            val body = JSONObject()
                .put("model", model)
                .put("stream", false)
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", LlmPrompt.system(source, target, extraPrompt)))
                        .put(JSONObject().put("role", "user").put("content", LlmPrompt.user(batch)))
                )
            val headers = if (apiKey.isNotBlank()) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
            val resp = JSONObject(Http.postJson(endpoint, body.toString(), headers))
            resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        }
    }
}

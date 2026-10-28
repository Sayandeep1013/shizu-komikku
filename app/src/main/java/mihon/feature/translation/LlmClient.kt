package mihon.feature.translation

import android.util.Base64
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

/** [code] is the HTTP status when the provider answered with an error, null otherwise. */
class TranslationException(message: String, val code: Int? = null) : Exception(message)

/**
 * Minimal OpenAI-compatible chat client (Gemini, OpenRouter, DeepSeek and local servers all
 * expose this protocol), plus the translation prompt.
 */
class LlmClient(
    baseClient: OkHttpClient,
    val endpoint: Endpoint,
) {
    private val client = baseClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    val label: String get() = endpoint.provider.label

    suspend fun chat(messages: JsonArray, jsonMode: Boolean = false, temperature: Double = 0.3): String {
        val body = buildJsonObject {
            put("model", endpoint.model)
            put("messages", messages)
            put("temperature", temperature)
            if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
        }
        val (code, text) = post(body)
        // Not every model accepts response_format; the prompt asks for JSON anyway.
        val (finalCode, finalText) = if (jsonMode && code == 400) {
            post(JsonObject(body - "response_format"))
        } else {
            code to text
        }
        if (finalCode !in 200..299) {
            throw TranslationException("HTTP $finalCode ${errorMessage(finalText)}", finalCode)
        }
        return try {
            json.parseToJsonElement(finalText).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull.orEmpty()
        } catch (e: Exception) {
            throw TranslationException("unexpected reply ${finalText.take(120)}")
        }
    }

    private suspend fun post(body: JsonObject): Pair<Int, String> {
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply {
                if (endpoint.apiKey.isNotEmpty()) header("Authorization", "Bearer ${endpoint.apiKey}")
                if (endpoint.provider == Provider.OPENROUTER) header("X-Title", "Komikku live translation")
            }
            .build()
        return client.newCall(request).await().use { it.code to it.body.string() }
    }

    /** Pulls the human-readable part out of an OpenAI-style error body. */
    private fun errorMessage(body: String): String {
        val message = try {
            val root = json.parseToJsonElement(body)
            val obj = (root as? JsonArray)?.firstOrNull() ?: root
            obj.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
        return (message ?: body).replace('\n', ' ').take(160)
    }

    /** Cheap round trip used by the settings "Test" buttons. */
    suspend fun test(): String {
        val messages = buildJsonArray { add(message("user", "Reply with exactly: OK")) }
        return chat(messages, temperature = 0.0).trim()
    }

    /**
     * Translate one page's bubbles. [context] is recent (source, translation) pairs from earlier
     * pages so names and tone stay consistent. [crops] are images of bubbles whose OCR is unsure;
     * a vision model reads those itself, in the same request.
     */
    suspend fun translate(
        items: Map<String, String>,
        crops: Map<String, ByteArray>,
        sourceLang: String,
        targetLang: String,
        context: List<Pair<String, String>>,
        glossary: String,
    ): Map<String, String> {
        if (items.isEmpty()) return emptyMap()
        val src = languageName(sourceLang)
        val tgt = languageName(targetLang)
        val system = buildString {
            append("You translate dialogue from comics (manga, manhwa, manhua) from $src to $tgt.\n\n")
            append("Input: a JSON object mapping ids to text read from speech bubbles by OCR, in reading order.\n")
            append("Output: a JSON object with exactly the same ids, each mapped to its $tgt translation.\n\n")
            append("Rules:\n")
            append("- Natural, concise $tgt that fits in a speech bubble; keep tone, slang and honorific feel.\n")
            append("- Keep character names and terms consistent with the earlier lines below.\n")
            append("- This is fiction for adult readers: translate every line faithfully, including mature ")
            append("or explicit dialogue; never censor, summarize or skip a line.\n")
            append("- OCR may have small errors; translate the most likely intended text.\n")
            append("- Sound effects: a short $tgt equivalent.\n")
            append("- Only watermarks, site URLs and scanlator credits map to \"\".\n")
            append("- Return only the JSON object.")
            if (glossary.isNotBlank()) append("\n\nGlossary (always use): ${glossary.trim()}")
            if (context.isNotEmpty()) {
                append("\n\nEarlier lines in this chapter:\n")
                context.forEach { (s, t) -> append("- $s => $t\n") }
            }
        }
        val useCrops = if (endpoint.provider.supportsVision) crops.filterKeys { it in items } else emptyMap()
        val result = askTranslation(system, items, useCrops).toMutableMap()
        // One retry for anything the model skipped, so a single sloppy reply doesn't blank bubbles.
        val missing = items.filterKeys { it !in result }
        if (missing.isNotEmpty()) result += askTranslation(system, missing, useCrops.filterKeys { it in missing })
        // Ids still missing are left out: the caller keeps those bubbles untouched.
        return result.filterKeys { it in items }
    }

    private suspend fun askTranslation(
        system: String,
        items: Map<String, String>,
        crops: Map<String, ByteArray>,
    ): Map<String, String> {
        val textJson = JsonObject(items.mapValues { JsonPrimitive(it.value) }).toString()
        val user: JsonElement = if (crops.isEmpty()) {
            JsonPrimitive(textJson)
        } else {
            buildJsonArray {
                add(
                    textPart(
                        "$textJson\n\nThe OCR text is unreliable for ids ${crops.keys.joinToString()}. " +
                            "Their bubble images follow: read what is actually written in each image and translate that.",
                    ),
                )
                crops.forEach { (id, png) ->
                    add(textPart("Image for $id:"))
                    add(imagePart(png))
                }
            }
        }
        val messages = buildJsonArray {
            add(message("system", system))
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", user)
                },
            )
        }
        return parseObject(chat(messages, jsonMode = true))
    }

    private fun parseObject(reply: String): Map<String, String> {
        var text = reply.trim()
        FENCE.find(text)?.let { text = it.groupValues[1].trim() }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) throw TranslationException("model did not return JSON: ${reply.take(120)}")
        val obj = try {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (e: Exception) {
            throw TranslationException("model returned invalid JSON: ${reply.take(120)}")
        }
        return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it.trim() } }.toMap()
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun textPart(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun imagePart(png: ByteArray) = buildJsonObject {
        put("type", "image_url")
        put(
            "image_url",
            buildJsonObject { put("url", "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)) },
        )
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private val FENCE = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)

        fun languageName(code: String): String =
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
    }
}

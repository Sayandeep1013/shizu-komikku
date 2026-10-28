package mihon.feature.translation

import android.util.Base64
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class TranslationException(message: String) : Exception(message)

/**
 * Minimal OpenAI-compatible chat client (Gemini, OpenRouter, DeepSeek and local servers all
 * expose this protocol), plus the two prompts the translator needs.
 */
class LlmClient(
    baseClient: OkHttpClient,
    private val endpoint: Endpoint,
) {
    private val client = baseClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chat(messages: JsonArray, jsonMode: Boolean = false, temperature: Double = 0.3): String {
        val body = buildJsonObject {
            put("model", endpoint.model)
            put("messages", messages)
            put("temperature", temperature)
            if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
        }
        val (code, text) = post(body)
        // Not every model accepts response_format; the prompts ask for JSON anyway.
        val (finalCode, finalText) = if (jsonMode && code == 400) {
            post(JsonObject(body - "response_format"))
        } else {
            code to text
        }
        if (finalCode !in 200..299) {
            throw TranslationException("${endpoint.provider.label}: HTTP $finalCode ${finalText.take(300)}")
        }
        return try {
            json.parseToJsonElement(finalText).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull.orEmpty()
        } catch (e: Exception) {
            throw TranslationException("${endpoint.provider.label}: unexpected reply ${finalText.take(200)}")
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

    /** Cheap round trip used by the settings "Test API key" button. */
    suspend fun test(): String {
        val messages = buildJsonArray { add(message("user", "Reply with exactly: OK")) }
        return chat(messages, temperature = 0.0).trim()
    }

    /**
     * Translate one page's bubbles. [context] is recent (source, translation) pairs from earlier
     * pages so names and tone stay consistent while reading.
     */
    suspend fun translate(
        items: Map<String, String>,
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
        val result = askTranslation(system, items).toMutableMap()
        // One retry for anything the model skipped, so a single sloppy reply doesn't blank bubbles.
        val missing = items.filterKeys { it !in result }
        if (missing.isNotEmpty()) result += askTranslation(system, missing)
        // Ids still missing are left out: the caller keeps those bubbles untouched.
        return result.filterKeys { it in items }
    }

    private suspend fun askTranslation(system: String, items: Map<String, String>): Map<String, String> {
        val user = JsonObject(items.mapValues { JsonPrimitive(it.value) }).toString()
        val messages = buildJsonArray {
            add(message("system", system))
            add(message("user", user))
        }
        return parseObject(chat(messages, jsonMode = true))
    }

    /** Transcribe bubble crops the on-device OCR was unsure about. Returns id -> text. */
    suspend fun readCrops(crops: List<Pair<String, ByteArray>>, sourceLang: String): Map<String, String> {
        if (crops.isEmpty()) return emptyMap()
        val src = languageName(sourceLang)
        val prompt = "Each image is one speech bubble or caption from a $src comic. " +
            "Transcribe the $src text exactly as written (no translation), joining lines in reading order. " +
            "If an image has no real text, use \"\". Return only a JSON object mapping each image id to its text. " +
            "Image ids in order: ${crops.joinToString { it.first }}"
        val content = buildJsonArray {
            add(textPart(prompt))
            crops.forEach { (id, png) ->
                add(textPart("Image $id:"))
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put(
                            "image_url",
                            buildJsonObject {
                                put("url", "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP))
                            },
                        )
                    },
                )
            }
        }
        val messages = buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", content)
                },
            )
        }
        return parseObject(chat(messages, jsonMode = true, temperature = 0.0))
    }

    private fun parseObject(reply: String): Map<String, String> {
        var text = reply.trim()
        FENCE.find(text)?.let { text = it.groupValues[1].trim() }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) throw TranslationException("model did not return JSON: ${reply.take(200)}")
        val obj = try {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (e: Exception) {
            throw TranslationException("model returned invalid JSON: ${reply.take(200)}")
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

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private val FENCE = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)

        fun languageName(code: String): String =
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
    }
}

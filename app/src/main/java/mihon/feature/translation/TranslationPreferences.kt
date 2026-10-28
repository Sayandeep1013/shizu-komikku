package mihon.feature.translation

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun liveTranslation() = preferenceStore.getBoolean("pref_live_translation", false)

    fun provider() = preferenceStore.getString("pref_translation_provider", Provider.GEMINI.id)

    /** One key per provider so switching providers doesn't lose keys. Private: excluded from backups. */
    fun apiKey(provider: Provider) =
        preferenceStore.getString(Preference.privateKey("pref_translation_api_key_${provider.id}"), "")

    /** Empty means the provider's default model. */
    fun model(provider: Provider) = preferenceStore.getString("pref_translation_model_${provider.id}", "")

    /** Empty means the provider's default endpoint. */
    fun baseUrl(provider: Provider) = preferenceStore.getString("pref_translation_base_url_${provider.id}", "")

    fun sourceLanguage() = preferenceStore.getString("pref_translation_source_lang", "ko")

    fun targetLanguage() = preferenceStore.getString("pref_translation_target_lang", "en")

    fun lookahead() = preferenceStore.getInt("pref_translation_lookahead", 5)

    fun visionReread() = preferenceStore.getBoolean("pref_translation_vision_reread", true)

    fun glossary() = preferenceStore.getString("pref_translation_glossary", "")

    fun currentProvider(): Provider = Provider.fromId(provider().get())

    fun endpoint(provider: Provider = currentProvider()) = Endpoint(
        provider = provider,
        apiKey = apiKey(provider).get().trim(),
        model = model(provider).get().trim().ifEmpty { provider.defaultModel },
        baseUrl = baseUrl(provider).get().trim().ifEmpty { provider.defaultBaseUrl }.trimEnd('/'),
    )

    companion object {
        val SOURCE_LANGUAGES = listOf("ko", "ja", "zh", "en")
        val TARGET_LANGUAGES = listOf("en", "hi", "bn", "es", "pt", "fr", "de", "id", "vi", "ru", "ja", "ko", "zh")
    }
}

/** Every provider speaks the OpenAI chat-completions protocol. Defaults checked 2026-10-28. */
enum class Provider(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val supportsVision: Boolean,
) {
    GEMINI(
        "gemini",
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "gemini-3.8-flash",
        true,
    ),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-v4.1-flash", true),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash", false),
    CUSTOM("custom", "Custom (OpenAI-compatible)", "http://192.168.1.2:11434/v1", "qwen3:4b", false),
    ;

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

data class Endpoint(
    val provider: Provider,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
) {
    /** Cache key part: a different model or language must not reuse old translations. */
    val signature: String get() = "${provider.id}|$model"
}

package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import mihon.feature.translation.LlmClient
import mihon.feature.translation.Provider
import mihon.feature.translation.TranslationPreferences
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

object SettingsTranslationScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsTranslationScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        return listOf(
            getReadingGroup(prefs),
            getProviderGroup(prefs),
            Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.pref_translation_info)),
        )
    }

    @Composable
    private fun getReadingGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        val lookahead by prefs.lookahead().collectAsState()
        val glossary by prefs.glossary().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_translation_group_reading),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.liveTranslation(),
                    title = stringResource(KMR.strings.pref_live_translation),
                    subtitle = stringResource(KMR.strings.pref_live_translation_summary),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.sourceLanguage(),
                    entries = languageEntries(TranslationPreferences.SOURCE_LANGUAGES),
                    title = stringResource(KMR.strings.pref_translation_source_lang),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.targetLanguage(),
                    entries = languageEntries(TranslationPreferences.TARGET_LANGUAGES),
                    title = stringResource(KMR.strings.pref_translation_target_lang),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = lookahead,
                    valueRange = 1..5,
                    title = stringResource(KMR.strings.pref_translation_lookahead),
                    valueString = lookahead.toString(),
                    onValueChanged = { prefs.lookahead().set(it) },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.glossary(),
                    title = stringResource(KMR.strings.pref_translation_glossary),
                    subtitle = glossary.ifBlank { stringResource(KMR.strings.pref_translation_glossary_summary) },
                ),
            ),
        )
    }

    @Composable
    private fun getProviderGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val providerId by prefs.provider().collectAsState()
        val provider = Provider.fromId(providerId)
        val keyPref = remember(provider) { prefs.apiKey(provider) }
        val modelPref = remember(provider) { prefs.model(provider) }
        val urlPref = remember(provider) { prefs.baseUrl(provider) }
        val key by keyPref.collectAsState()
        val model by modelPref.collectAsState()
        val url by urlPref.collectAsState()
        var testing by remember { mutableStateOf(false) }

        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_translation_group_provider),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.provider(),
                    entries = Provider.entries.associate { it.id to it.label }.toImmutableMap(),
                    title = stringResource(KMR.strings.pref_translation_provider),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = keyPref,
                    title = stringResource(KMR.strings.pref_translation_api_key),
                    subtitle = maskKey(key) ?: stringResource(KMR.strings.pref_translation_api_key_not_set),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = modelPref,
                    title = stringResource(KMR.strings.pref_translation_model),
                    subtitle = model.ifBlank {
                        stringResource(KMR.strings.pref_translation_default_value, provider.defaultModel)
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = urlPref,
                    title = stringResource(KMR.strings.pref_translation_base_url),
                    subtitle = url.ifBlank {
                        stringResource(KMR.strings.pref_translation_default_value, provider.defaultBaseUrl)
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.visionReread(),
                    title = stringResource(KMR.strings.pref_translation_vision_reread),
                    subtitle = stringResource(KMR.strings.pref_translation_vision_reread_summary),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(
                        if (testing) KMR.strings.pref_translation_testing else KMR.strings.pref_translation_test,
                    ),
                    enabled = !testing,
                    onClick = {
                        testing = true
                        scope.launch {
                            val endpoint = prefs.endpoint(provider)
                            val message = withIOContext {
                                try {
                                    val reply = LlmClient(Injekt.get<NetworkHelper>().client, endpoint).test()
                                    context.stringResource(
                                        KMR.strings.pref_translation_test_ok,
                                        endpoint.model,
                                        reply.take(40),
                                    )
                                } catch (e: Exception) {
                                    context.stringResource(
                                        KMR.strings.pref_translation_test_failed,
                                        e.message ?: e::class.java.simpleName,
                                    )
                                }
                            }
                            testing = false
                            context.toast(message)
                        }
                    },
                ),
            ),
        )
    }

    private fun languageEntries(codes: List<String>) = codes
        .associateWith { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        .toImmutableMap()

    private fun maskKey(key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null
        return "••••" + trimmed.takeLast(4)
    }
}

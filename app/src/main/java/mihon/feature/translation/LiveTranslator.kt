package mihon.feature.translation

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates pages around the reading position while the user reads.
 *
 * The current page and the next few are translated as soon as their images are available
 * (online or downloaded); finished pages are published through [ReaderPage.translatedStream]
 * and the page holders swap the image in place, so the text is part of the page pixels and
 * stays put while scrolling or zooming. Results are cached on disk by image content.
 */
class LiveTranslator(
    private val scope: CoroutineScope,
    private val app: Application = Injekt.get(),
    private val preferences: TranslationPreferences = Injekt.get(),
    private val network: NetworkHelper = Injekt.get(),
) {
    private val cacheDir = File(app.cacheDir, "live_translation").apply { mkdirs() }
    private val jobs = ConcurrentHashMap<ReaderPage, Job>()
    private val deviceLock = Mutex()
    private val networkSlots = Semaphore(2)
    private val recentPairs = ArrayDeque<Pair<String, String>>()
    private var windowJob: Job? = null
    private var translator: PageTranslator? = null
    private var translatorKey: String? = null
    private val reportedErrors: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun onPageSelected(page: ReaderPage) {
        if (page is InsertPage || !preferences.liveTranslation().get()) return
        windowJob?.cancel()
        windowJob = scope.launch {
            // Debounce flings: only translate where the reader settles.
            delay(DEBOUNCE_MS)
            val pages = page.chapter.pages ?: return@launch
            val ahead = preferences.lookahead().get().coerceIn(0, MAX_LOOKAHEAD)
            val first = page.index
            val last = minOf(first + ahead, pages.lastIndex)

            jobs.keys
                .filter { it.chapter != page.chapter || it.index < first - KEEP_BEHIND || it.index > last }
                .forEach { jobs.remove(it)?.cancel() }

            for (index in first..last) {
                val target = pages[index]
                if (target.translatedStream.value != null || jobs[target]?.isActive == true) continue
                jobs[target] = scope.launch(Dispatchers.IO) { translatePage(target) }
            }
        }
    }

    private suspend fun translatePage(page: ReaderPage) {
        try {
            page.statusFlow.first { it == Page.State.Ready }
            val stream = page.stream ?: return
            val bytes = stream().use { it.readBytes() }
            val pageTranslator = currentTranslator() ?: return

            val key = cacheKey(bytes)
            val cached = File(cacheDir, "$key.jpg")
            val noText = File(cacheDir, "$key.none")
            if (noText.exists()) return
            if (cached.exists()) {
                cached.setLastModified(System.currentTimeMillis())
                page.translatedStream.value = { cached.inputStream() }
                return
            }

            val result = networkSlots.withPermit {
                pageTranslator.translate(bytes, synchronized(recentPairs) { recentPairs.toList() })
            }
            val image = result.image
            if (image == null) {
                noText.createNewFile()
                return
            }
            val tmp = File(cacheDir, "$key.tmp")
            tmp.writeBytes(image)
            tmp.renameTo(cached)
            synchronized(recentPairs) {
                result.pairs.forEach {
                    recentPairs.addLast(it)
                    if (recentPairs.size > CONTEXT_PAIRS) recentPairs.removeFirst()
                }
            }
            page.translatedStream.value = { cached.inputStream() }
            trimCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            xLogE("Live translation failed for page ${page.number}", e)
            val message = e.message ?: e::class.java.simpleName
            if (reportedErrors.add(message)) {
                withUIContext { app.toast(app.stringResource(KMR.strings.translation_failed, message)) }
            }
        } finally {
            jobs.remove(page)
        }
    }

    /** Rebuilt when provider, model, key or languages change in settings. */
    @Synchronized
    private fun currentTranslator(): PageTranslator? {
        val endpoint = preferences.endpoint()
        if (endpoint.apiKey.isEmpty() && endpoint.provider != Provider.CUSTOM) {
            if (reportedErrors.add("no-key")) {
                scope.launch { withUIContext { app.toast(KMR.strings.translation_missing_key) } }
            }
            return null
        }
        val source = preferences.sourceLanguage().get()
        val target = preferences.targetLanguage().get()
        val vision = preferences.visionReread().get()
        val glossary = preferences.glossary().get()
        val key = listOf(endpoint.signature, endpoint.baseUrl, endpoint.apiKey, source, target, vision, glossary)
            .joinToString("|")
        if (key != translatorKey) {
            translator?.close()
            translator = PageTranslator(
                llm = LlmClient(network.client, endpoint),
                endpoint = endpoint,
                sourceLang = source,
                targetLang = target,
                visionReread = vision,
                glossary = glossary,
                deviceLock = deviceLock,
            )
            translatorKey = key
            synchronized(recentPairs) { recentPairs.clear() }
        }
        return translator
    }

    private fun cacheKey(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(bytes)
        val settings = listOf(
            preferences.endpoint().signature,
            preferences.sourceLanguage().get(),
            preferences.targetLanguage().get(),
            preferences.glossary().get(),
            CACHE_VERSION,
        ).joinToString("|")
        digest.update(settings.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.extension == "jpg" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            total -= file.length()
            file.delete()
            if (total <= MAX_CACHE_BYTES * 3 / 4) break
        }
    }

    fun close() {
        windowJob?.cancel()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        translator?.close()
        translator = null
    }

    companion object {
        private const val DEBOUNCE_MS = 150L
        private const val MAX_LOOKAHEAD = 5
        private const val KEEP_BEHIND = 1
        private const val CONTEXT_PAIRS = 12
        private const val MAX_CACHE_BYTES = 300L * 1024 * 1024
        private const val CACHE_VERSION = "1"
    }
}

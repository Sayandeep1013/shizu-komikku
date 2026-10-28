package mihon.feature.translation

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogE
import exh.log.xLogW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
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
 *
 * Every page carries a [TranslationStatus] for the reader's translate button, which also lets
 * the user force a page ([translateNow]) or flip between original and translation ([toggleOriginal]).
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
    private val networkSlots = Semaphore(NETWORK_SLOTS)
    private val recentPairs = ArrayDeque<Pair<String, String>>()
    private var windowJob: Job? = null
    private var translator: PageTranslator? = null
    private var translatorKey: String? = null
    private val reportedErrors: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _currentPage = MutableStateFlow<ReaderPage?>(null)

    /** The page the reader is on, for the translate button. */
    val currentPage: StateFlow<ReaderPage?> = _currentPage.asStateFlow()

    /** True when at least one provider has a key, i.e. the button can do something. */
    fun isConfigured(): Boolean = preferences.endpoints().isNotEmpty()

    fun onPageSelected(page: ReaderPage) {
        if (page is InsertPage) return
        _currentPage.value = page
        if (!preferences.liveTranslation().get()) return
        windowJob?.cancel()
        windowJob = scope.launch {
            // Debounce flings: only translate where the reader settles.
            delay(DEBOUNCE_MS)
            val pages = page.chapter.pages ?: return@launch
            val ahead = preferences.lookahead().get().coerceIn(0, MAX_LOOKAHEAD)
            val first = page.index
            val last = minOf(first + ahead, pages.lastIndex)

            // Pages the reader just passed keep going (the result is cached for scrolling back);
            // only work far from the reading position is dropped.
            jobs.keys
                .filter { it.chapter != page.chapter || it.index < first - KEEP_BEHIND || it.index > last + KEEP_BEHIND }
                .forEach { stale ->
                    jobs.remove(stale)?.cancel()
                    if (stale.translationStatus.value == TranslationStatus.Queued) {
                        stale.translationStatus.value = TranslationStatus.Idle
                    }
                }

            for (index in first..last) {
                val target = pages[index]
                if (target.translatedStream.value != null || jobs[target]?.isActive == true) continue
                // Auto mode doesn't hammer a page that just failed; the button retries it.
                if (target.translationStatus.value is TranslationStatus.Failed && index != first) continue
                // The page on screen jumps the network queue; pages ahead wait their turn.
                launchPage(target, force = false, priority = index == first)
            }
            xLogW("window: page ${page.number}..${last + 1} of ${pages.size}, active jobs ${jobs.size}")
        }
    }

    /** The user tapped translate: run this page now, ahead of the queue, ignoring "no text" memory. */
    fun translateNow(page: ReaderPage) {
        if (page.translatedImage != null) {
            showTranslated(page)
            return
        }
        if (jobs[page]?.isActive == true && page.translationStatus.value == TranslationStatus.Working) return
        jobs.remove(page)?.cancel()
        launchPage(page, force = true, priority = true)
    }

    /** Flip between the original page and its translation (long-press on the button). */
    fun toggleOriginal(page: ReaderPage) {
        val translated = page.translatedImage ?: return
        if (page.translatedStream.value != null) {
            page.translatedStream.value = null
            page.translationStatus.value = TranslationStatus.ShowingOriginal
        } else {
            page.translatedStream.value = translated
            page.translationStatus.value = TranslationStatus.Done
        }
    }

    private fun showTranslated(page: ReaderPage) {
        page.translatedStream.value = page.translatedImage
        page.translationStatus.value = TranslationStatus.Done
    }

    private fun launchPage(page: ReaderPage, force: Boolean, priority: Boolean) {
        page.translationStatus.value = TranslationStatus.Queued
        jobs[page] = scope.launch(Dispatchers.IO) { translatePage(page, force, priority) }
    }

    private suspend fun translatePage(page: ReaderPage, force: Boolean, priority: Boolean) {
        val started = System.currentTimeMillis()
        try {
            page.statusFlow.first { it == Page.State.Ready }
            val stream = page.stream ?: return
            val bytes = stream().use { it.readBytes() }
            val pageTranslator = currentTranslator()
            if (pageTranslator == null) {
                page.translationStatus.value = TranslationStatus.Failed(app.stringResource(KMR.strings.translation_no_key))
                return
            }

            val key = cacheKey(bytes)
            val cached = File(cacheDir, "$key.jpg")
            val noText = File(cacheDir, "$key.none")
            if (cached.exists()) {
                cached.setLastModified(System.currentTimeMillis())
                publish(page, cached)
                xLogW("page ${page.number}: from cache")
                return
            }
            if (noText.exists() && !force) {
                page.translationStatus.value = TranslationStatus.NoText
                xLogW("page ${page.number}: cached as no-text")
                return
            }

            page.translationStatus.value = TranslationStatus.Working
            val result = pageTranslator.translate(
                bytes,
                synchronized(recentPairs) { recentPairs.toList() },
                priority = priority,
            )
            // Numbers only: page content is never logged.
            xLogW("page ${page.number}: ${result.stats} total=${System.currentTimeMillis() - started}ms")
            val image = result.image
            if (image == null) {
                // Only remember pages that truly have no text; a failed translation is retried next time.
                if (result.noText) {
                    noText.createNewFile()
                    page.translationStatus.value = TranslationStatus.NoText
                } else {
                    page.translationStatus.value =
                        TranslationStatus.Failed(app.stringResource(KMR.strings.translation_nothing_translated))
                }
                return
            }
            noText.delete()
            val tmp = File(cacheDir, "$key.tmp")
            tmp.writeBytes(image)
            tmp.renameTo(cached)
            synchronized(recentPairs) {
                result.pairs.forEach {
                    recentPairs.addLast(it)
                    if (recentPairs.size > CONTEXT_PAIRS) recentPairs.removeFirst()
                }
            }
            publish(page, cached)
            trimCache()
        } catch (e: CancellationException) {
            xLogW("page ${page.number}: cancelled after ${System.currentTimeMillis() - started}ms")
            throw e
        } catch (e: Throwable) {
            xLogE("Live translation failed for page ${page.number}", e)
            val reason = LlmRouter.shortReason(e)
            page.translationStatus.value = TranslationStatus.Failed(reason)
            if (reportedErrors.add(reason)) {
                withUIContext { app.toast(app.stringResource(KMR.strings.translation_failed, reason)) }
            }
        } finally {
            jobs.remove(page)
        }
    }

    private fun publish(page: ReaderPage, file: File) {
        val image: () -> java.io.InputStream = { file.inputStream() }
        page.translatedImage = image
        page.translatedStream.value = image
        page.translationStatus.value = TranslationStatus.Done
    }

    /** Rebuilt when providers, keys, models or languages change in settings. */
    @Synchronized
    private fun currentTranslator(): PageTranslator? {
        val endpoints = preferences.endpoints()
        if (endpoints.isEmpty()) {
            if (reportedErrors.add("no-key")) {
                scope.launch { withUIContext { app.toast(KMR.strings.translation_missing_key) } }
            }
            return null
        }
        val source = preferences.sourceLanguage().get()
        val target = preferences.targetLanguage().get()
        val vision = preferences.visionReread().get()
        val glossary = preferences.glossary().get()
        val key = (endpoints.map { "${it.signature}|${it.baseUrl}|${it.apiKey}" } + listOf(source, target, "$vision", glossary))
            .joinToString("|")
        if (key != translatorKey) {
            translator?.close()
            translator = PageTranslator(
                router = LlmRouter(network.client, endpoints),
                sourceLang = source,
                targetLang = target,
                visionReread = vision,
                glossary = glossary,
                deviceLock = deviceLock,
                networkSlots = networkSlots,
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
        private const val MAX_LOOKAHEAD = 10
        private const val KEEP_BEHIND = 5
        private const val NETWORK_SLOTS = 3
        private const val CONTEXT_PAIRS = 12
        private const val MAX_CACHE_BYTES = 300L * 1024 * 1024
        private const val CACHE_VERSION = "3"
    }
}

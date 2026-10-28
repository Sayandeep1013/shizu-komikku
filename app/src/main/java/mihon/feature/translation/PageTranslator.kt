package mihon.feature.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream

/**
 * What one page produced. [image] is null when nothing was lettered; [noText] says whether that is
 * because the page has no text at all (safe to remember) rather than a failed translation.
 * [stats] is numbers only, for diagnostics; page content is never included.
 */
class PageResult(
    val image: ByteArray?,
    val pairs: List<Pair<String, String>>,
    val noText: Boolean,
    val stats: String,
)

/**
 * detect (on device) -> translate, with crops of unsure bubbles for vision models (one LLM call)
 * -> erase + letter (on device).
 *
 * CPU-heavy steps share [deviceLock] so only one page uses the CPU at a time; [networkSlots]
 * limits concurrent LLM calls and is held only for the call itself.
 */
class PageTranslator(
    private val router: LlmRouter,
    private val sourceLang: String,
    private val targetLang: String,
    private val visionReread: Boolean,
    private val glossary: String,
    private val deviceLock: Mutex,
    private val networkSlots: Semaphore,
) {
    private val detector by lazy { TextDetector(sourceLang) }

    /** [priority] requests (the user tapped translate) skip the network queue. */
    suspend fun translate(bytes: ByteArray, context: List<Pair<String, String>>, priority: Boolean): PageResult {
        val t0 = System.currentTimeMillis()
        val (bitmap, blocks) = deviceLock.withLock {
            val bitmap = decode(bytes) ?: return PageResult(null, emptyList(), noText = false, stats = "undecodable")
            bitmap to detector.detect(bitmap)
        }
        val ocrMs = System.currentTimeMillis() - t0
        try {
            val size = "${bitmap.width}x${bitmap.height}"
            if (blocks.isEmpty()) return PageResult(null, emptyList(), noText = true, stats = "$size blocks=0 ocr=${ocrMs}ms")

            val ids = blocks.withIndex().filter { it.value.text.isNotBlank() }.associate { "b${it.index}" to it.value }
            val crops = if (visionReread) {
                deviceLock.withLock {
                    ids.filterValues { it.minConfidence < REREAD_BELOW }.mapValues { crop(bitmap, it.value) }
                }
            } else {
                emptyMap()
            }

            val t1 = System.currentTimeMillis()
            var usedProvider = ""
            val request: suspend () -> Map<String, String> = {
                router.run { client ->
                    usedProvider = client.endpoint.provider.id
                    client.translate(ids.mapValues { it.value.text }, crops, sourceLang, targetLang, context, glossary)
                }
            }
            val translated = if (priority) request() else networkSlots.withPermit { request() }
            ids.forEach { (id, block) -> block.translation = translated[id] }
            val llmMs = System.currentTimeMillis() - t1

            val lettered = blocks.filter { it.hasTranslation }
            val stats = "$size blocks=${blocks.size} lines=${blocks.sumOf { it.lines.size }} " +
                "minConf=${"%.2f".format(blocks.minOf { it.minConfidence })} crops=${crops.size} " +
                "translated=${lettered.size} missing=${ids.size - translated.size} " +
                "empty=${translated.count { it.value.isBlank() }} via=$usedProvider ocr=${ocrMs}ms llm=${llmMs}ms"
            if (lettered.isEmpty()) return PageResult(null, emptyList(), noText = false, stats = stats)

            return deviceLock.withLock {
                PageRenderer(targetLang).render(bitmap, lettered)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                PageResult(
                    out.toByteArray(),
                    lettered.map { it.text to it.translation.orEmpty() },
                    noText = false,
                    stats = stats,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun close() {
        detector.close()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight.toLong() * bounds.outWidth > MAX_PIXELS) return null
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun crop(bitmap: Bitmap, block: TextBlock): ByteArray {
        val pad = 12
        val left = (block.box.left - pad).coerceAtLeast(0)
        val top = (block.box.top - pad).coerceAtLeast(0)
        val right = (block.box.right + pad).coerceAtMost(bitmap.width)
        val bottom = (block.box.bottom + pad).coerceAtMost(bitmap.height)
        val piece = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return ByteArrayOutputStream().use {
            piece.compress(Bitmap.CompressFormat.PNG, 100, it)
            piece.recycle()
            it.toByteArray()
        }
    }

    companion object {
        /** ML Kit line confidence below this sends the bubble's crop along for the vision model. */
        private const val REREAD_BELOW = 0.7f
        private const val JPEG_QUALITY = 92
        private const val MAX_PIXELS = 40_000_000L
    }
}

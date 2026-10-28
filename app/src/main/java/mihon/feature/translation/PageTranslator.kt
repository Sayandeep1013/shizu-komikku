package mihon.feature.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

/** What one translated page produced; [image] is null when the page had no text to translate. */
class PageResult(
    val image: ByteArray?,
    val pairs: List<Pair<String, String>>,
)

/**
 * detect (on device) -> optional vision re-read -> translate (LLM) -> erase + letter (on device).
 *
 * CPU-heavy steps share [deviceLock] so only one page uses the CPU at a time while other pages
 * wait on the network.
 */
class PageTranslator(
    private val llm: LlmClient,
    private val endpoint: Endpoint,
    private val sourceLang: String,
    private val targetLang: String,
    private val visionReread: Boolean,
    private val glossary: String,
    private val deviceLock: Mutex,
) {
    private val detector by lazy { TextDetector(sourceLang) }

    suspend fun translate(bytes: ByteArray, context: List<Pair<String, String>>): PageResult {
        val (bitmap, blocks) = deviceLock.withLock {
            val bitmap = decode(bytes) ?: return PageResult(null, emptyList())
            bitmap to detector.detect(bitmap)
        }
        try {
            if (blocks.isEmpty()) return PageResult(null, emptyList())

            if (visionReread && endpoint.provider.supportsVision) {
                val unsure = blocks.filter { it.minConfidence < REREAD_BELOW }
                if (unsure.isNotEmpty()) {
                    val crops = unsure.mapIndexed { i, b -> "c$i" to crop(bitmap, b) }
                    val read = llm.readCrops(crops, sourceLang)
                    unsure.forEachIndexed { i, b -> read["c$i"]?.let { b.text = it } }
                }
            }

            val ids = blocks.withIndex().filter { it.value.text.isNotBlank() }.associate { "b${it.index}" to it.value }
            val translated = llm.translate(ids.mapValues { it.value.text }, sourceLang, targetLang, context, glossary)
            ids.forEach { (id, block) -> block.translation = translated[id].orEmpty() }

            return deviceLock.withLock {
                PageRenderer(targetLang).render(bitmap, blocks)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                PageResult(
                    out.toByteArray(),
                    ids.values.filter { it.translation.isNotBlank() }.map { it.text to it.translation },
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
        /** ML Kit line confidence below this sends the bubble to the vision model. */
        private const val REREAD_BELOW = 0.7f
        private const val JPEG_QUALITY = 92
        private const val MAX_PIXELS = 40_000_000L
    }
}

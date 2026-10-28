package mihon.feature.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val t0 = System.currentTimeMillis()
        val (bitmap, blocks) = deviceLock.withLock {
            val bitmap = decode(bytes) ?: return PageResult(null, emptyList(), noText = false, stats = "undecodable")
            bitmap to detector.detect(bitmap)
        }
        val ocrMs = System.currentTimeMillis() - t0
        try {
            val size = "${bitmap.width}x${bitmap.height}"
            if (blocks.isEmpty()) return PageResult(null, emptyList(), noText = true, stats = "$size blocks=0 ocr=${ocrMs}ms")

            var reread = 0
            var rereadBlank = 0
            if (visionReread && endpoint.provider.supportsVision) {
                val unsure = blocks.filter { it.minConfidence < REREAD_BELOW }
                if (unsure.isNotEmpty()) {
                    val crops = unsure.mapIndexed { i, b -> "c$i" to crop(bitmap, b) }
                    val read = llm.readCrops(crops, sourceLang)
                    unsure.forEachIndexed { i, b ->
                        // A blank or refused re-read must never wipe what OCR already found.
                        val text = read["c$i"]
                        if (text.isNullOrBlank()) rereadBlank++ else b.text = text
                    }
                    reread = unsure.size
                }
            }
            val t1 = System.currentTimeMillis()

            val ids = blocks.withIndex().filter { it.value.text.isNotBlank() }.associate { "b${it.index}" to it.value }
            val translated = llm.translate(ids.mapValues { it.value.text }, sourceLang, targetLang, context, glossary)
            ids.forEach { (id, block) -> block.translation = translated[id] }
            val llmMs = System.currentTimeMillis() - t1

            val lettered = blocks.filter { it.hasTranslation }
            val stats = "$size blocks=${blocks.size} lines=${blocks.sumOf { it.lines.size }} " +
                "minConf=${"%.2f".format(blocks.minOf { it.minConfidence })} reread=$reread blankReread=$rereadBlank " +
                "translated=${lettered.size} missing=${ids.size - translated.size} " +
                "empty=${translated.count { it.value.isBlank() }} ocr=${ocrMs}ms llm=${llmMs}ms"
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
        /** ML Kit line confidence below this sends the bubble to the vision model. */
        private const val REREAD_BELOW = 0.7f
        private const val JPEG_QUALITY = 92
        private const val MAX_PIXELS = 40_000_000L
    }
}

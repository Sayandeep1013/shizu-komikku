package mihon.feature.translation

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

data class TextLine(
    val box: Rect,
    val text: String,
    val confidence: Float,
) {
    /**
     * Not text (no letters: hatching, borders, "!!"), or a single unsure glyph. Low-confidence lines
     * with real letters are kept: the vision re-read fixes them instead of dropping them.
     */
    val junk: Boolean get() = text.none { it.isLetter() } || (confidence < JUNK_CONFIDENCE && text.length <= 1)

    val vertical: Boolean get() = box.height() > 1.5f * box.width() && text.length > 1

    companion object {
        const val JUNK_CONFIDENCE = 0.3f
    }
}

class TextBlock(
    val lines: List<TextLine>,
    val vertical: Boolean,
    var text: String,
) {
    /** Null until translated; null after translation means "leave this bubble untouched". */
    var translation: String? = null

    val box: Rect = Rect(
        lines.minOf { it.box.left },
        lines.minOf { it.box.top },
        lines.maxOf { it.box.right },
        lines.maxOf { it.box.bottom },
    )

    /** Lowest line confidence; a junk line inside a real bubble counts as 0 so the block gets re-read. */
    val minConfidence: Float get() = lines.minOf { if (it.junk) 0f else it.confidence }

    val hasTranslation: Boolean get() = !translation.isNullOrBlank()

    /** Set by the renderer when the text sits on artwork rather than in a bubble. */
    var onArt: Boolean = false
}

/**
 * On-device OCR with ML Kit. Tall webtoon strips are read in overlapping windows because
 * the recognizer downsizes large inputs and loses small lettering.
 */
class TextDetector(sourceLang: String) : Closeable {

    private val recognizer: TextRecognizer = TextRecognition.getClient(
        when (sourceLang) {
            "ko" -> KoreanTextRecognizerOptions.Builder().build()
            "ja" -> JapaneseTextRecognizerOptions.Builder().build()
            "zh" -> ChineseTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        },
    )

    private val noSpaces = sourceLang == "ja" || sourceLang == "zh"
    private val rightToLeft = sourceLang == "ja"

    fun detect(bitmap: Bitmap): List<TextBlock> = group(readLines(bitmap))

    private fun readLines(bitmap: Bitmap): List<TextLine> {
        val w = bitmap.width
        val h = bitmap.height
        val window = max((w * WINDOW_ASPECT).toInt(), MIN_WINDOW)
        if (h <= window + OVERLAP) return recognize(bitmap, 0)

        val lines = mutableListOf<TextLine>()
        var top = 0
        while (top < h) {
            val height = min(window, h - top)
            val slice = Bitmap.createBitmap(bitmap, 0, top, w, height)
            try {
                lines += recognize(slice, top)
            } finally {
                slice.recycle()
            }
            if (top + height >= h) break
            top += window - OVERLAP
        }
        return dedupe(lines)
    }

    private fun recognize(bitmap: Bitmap, offsetY: Int): List<TextLine> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        return result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                box.offset(0, offsetY)
                TextLine(box, line.text.trim(), line.confidence)
            }
        }
    }

    /** Lines read twice in the overlap between windows: keep the longer reading. */
    private fun dedupe(lines: List<TextLine>): List<TextLine> {
        val kept = mutableListOf<TextLine>()
        for (line in lines.sortedByDescending { it.text.length }) {
            if (kept.none { overlapRatio(it.box, line.box) > 0.5f }) kept += line
        }
        return kept
    }

    private fun group(lines: List<TextLine>): List<TextBlock> {
        val parent = IntArray(lines.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                if (sameBlock(lines[i], lines[j])) parent[find(i)] = find(j)
            }
        }
        val joiner = if (noSpaces) "" else " "
        return lines.indices.groupBy(::find).values
            .map { idx -> idx.map { lines[it] } }
            .filterNot { members -> members.all { it.junk } }
            .map { members ->
                val vertical = members.first().vertical
                // Vertical CJK reads right-to-left by column; horizontal reads top-to-bottom.
                val ordered = if (vertical) members.sortedByDescending { it.box.right } else members.sortedBy { it.box.top }
                TextBlock(ordered, vertical, ordered.filterNot { it.junk }.joinToString(joiner) { it.text })
            }
            .sortedWith(
                compareBy<TextBlock> { it.box.top / ROW_BAND }
                    .thenBy { if (rightToLeft) -it.box.left else it.box.left },
            )
    }

    private fun sameBlock(a: TextLine, b: TextLine): Boolean {
        if (a.vertical != b.vertical) return false
        return if (a.vertical) {
            val gap = max(a.box.left, b.box.left) - min(a.box.right, b.box.right)
            gap < 0.8f * min(a.box.width(), b.box.width()) &&
                min(a.box.bottom, b.box.bottom) - max(a.box.top, b.box.top) > 0
        } else {
            val gap = max(a.box.top, b.box.top) - min(a.box.bottom, b.box.bottom)
            gap < 0.8f * min(a.box.height(), b.box.height()) &&
                min(a.box.right, b.box.right) - max(a.box.left, b.box.left) > 0
        }
    }

    override fun close() {
        recognizer.close()
    }

    companion object {
        private const val WINDOW_ASPECT = 1.4f
        private const val MIN_WINDOW = 1000
        private const val OVERLAP = 250
        private const val ROW_BAND = 200

        /** Intersection over the smaller box's area. */
        fun overlapRatio(a: Rect, b: Rect): Float {
            val iw = min(a.right, b.right) - max(a.left, b.left)
            val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
            if (iw <= 0 || ih <= 0) return 0f
            val smaller = min(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
            return iw.toFloat() * ih / smaller
        }
    }
}

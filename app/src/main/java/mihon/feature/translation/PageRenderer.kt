package mihon.feature.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Removes the original lettering and typesets translations into the same bubbles.
 *
 * Works on a mutable ARGB bitmap the size of the page, so the result can replace the page
 * image directly and nothing shifts while scrolling or zooming.
 */
class PageRenderer(private val targetLang: String) {

    private class Region(val left: Int, val top: Int, val width: Int, val height: Int, val mask: BooleanArray) {
        fun contains(x: Int, y: Int): Boolean =
            x in left until left + width && y in top until top + height && mask[(y - top) * width + (x - left)]
    }

    // Flood-fill scratch space, sized per page and reused for every block.
    private var floodMark = BooleanArray(0)
    private var floodQueue = IntArray(0)

    fun render(page: Bitmap, blocks: List<TextBlock>) {
        val w = page.width
        val h = page.height
        val pixels = IntArray(w * h)
        page.getPixels(pixels, 0, w, 0, 0, w, h)
        floodMark = BooleanArray(w * h)
        floodQueue = IntArray(maxFill(w, h) + 4)
        erase(pixels, w, h, blocks)
        val rects = layout(pixels, w, h, blocks)
        page.setPixels(pixels, 0, w, 0, 0, w, h)
        letter(page, pixels, w, blocks, rects)
        floodMark = BooleanArray(0)
        floodQueue = IntArray(0)
    }

    /** Bubbles are never bigger than about two page-widths squared; bigger fills are page background. */
    private fun maxFill(w: Int, h: Int) = min(w.toLong() * h / 2, w.toLong() * w * 2).toInt()

    // region Erase

    private fun erase(px: IntArray, w: Int, h: Int, blocks: List<TextBlock>) {
        for (block in blocks) {
            val ring = ringPixels(px, w, h, block.box, PAD * 3)
            val color = medianColor(ring)
            for (line in block.lines) {
                fillRect(px, w, h, padded(line.box, PAD, w, h), color)
            }
            // Flat surroundings mean a normal bubble: also clear marks the detector missed in it.
            if (isFlat(ring, color)) clearBubble(px, w, h, block.box, color)
        }
    }

    private fun clearBubble(px: IntArray, w: Int, h: Int, box: Rect, color: Int) {
        val region = bubbleRegion(px, w, h, box) ?: return
        // Pixels inside the region's box that are not bubble-coloured and can't reach the
        // box border through other non-bubble pixels are enclosed marks (text), not outline.
        val rw = region.width + 2
        val rh = region.height + 2
        val outside = BooleanArray(rw * rh)
        val queue = IntArray(rw * rh)
        var head = 0
        var tail = 0
        fun inBubble(cx: Int, cy: Int): Boolean {
            val x = cx - 1
            val y = cy - 1
            return x in 0 until region.width && y in 0 until region.height && region.mask[y * region.width + x]
        }
        // The 1px frame around the region's box is outside by construction.
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                if (x != 0 && y != 0 && x != rw - 1 && y != rh - 1) continue
                val i = y * rw + x
                outside[i] = true
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % rw
            val y = i / rw
            for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx !in 0 until rw || ny !in 0 until rh) continue
                val n = ny * rw + nx
                if (outside[n] || inBubble(nx, ny)) continue
                outside[n] = true
                queue[tail++] = n
            }
        }
        for (y in 1 until rh - 1) {
            for (x in 1 until rw - 1) {
                if (!outside[y * rw + x] && !inBubble(x, y)) {
                    px[(region.top + y - 1) * w + region.left + x - 1] = color
                }
            }
        }
    }

    // endregion

    // region Layout

    /** One non-overlapping rectangle per block, grown inside that block's share of its bubble. */
    private fun layout(px: IntArray, w: Int, h: Int, blocks: List<TextBlock>): List<Rect> {
        val regions = blocks.map { bubbleRegion(px, w, h, it.box) }
        return blocks.mapIndexed { i, block ->
            val region = regions[i] ?: return@mapIndexed Rect(block.box).apply { inset(-4, -4) }
            val area = region.mask.copyOf()
            val others = blocks.filterIndexed { j, other ->
                j != i && region.contains(other.box.centerX(), other.box.centerY())
            }
            if (others.isNotEmpty()) {
                // Joined balloons: each pixel belongs to the nearest block.
                for (y in 0 until region.height) {
                    for (x in 0 until region.width) {
                        val idx = y * region.width + x
                        if (!area[idx]) continue
                        val px0 = region.left + x
                        val py0 = region.top + y
                        val mine = distance(block.box, px0, py0)
                        if (others.any { distance(it.box, px0, py0) < mine }) area[idx] = false
                    }
                }
            }
            val seed = Rect(block.box).apply { offset(-region.left, -region.top) }
            growRect(area, region.width, region.height, seed).apply {
                offset(region.left, region.top)
                inset(4, 4)
            }
        }
    }

    private fun growRect(area: BooleanArray, w: Int, h: Int, seed: Rect): Rect {
        val ii = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                if (area[y * w + x]) rowSum++
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + rowSum
            }
        }
        fun inside(l: Int, t: Int, r: Int, b: Int): Boolean {
            if (l < 0 || t < 0 || r > w || b > h || r <= l || b <= t) return false
            val filled = ii[b * (w + 1) + r] - ii[t * (w + 1) + r] - ii[b * (w + 1) + l] + ii[t * (w + 1) + l]
            return filled >= (1 - SLACK) * (r - l) * (b - t)
        }
        val cx = seed.centerX()
        val cy = seed.centerY()
        val r = intArrayOf(cx - 2, cy - 2, cx + 2, cy + 2)
        if (!inside(r[0], r[1], r[2], r[3])) return Rect(seed)
        var grew = true
        while (grew) {
            grew = false
            for ((side, delta) in GROW_ORDER) {
                r[side] += delta
                if (inside(r[0], r[1], r[2], r[3])) grew = true else r[side] -= delta
            }
        }
        return Rect(r[0], r[1], r[2], r[3])
    }

    private fun distance(box: Rect, x: Int, y: Int): Int {
        val dx = max(max(box.left - x, x - box.right), 0)
        val dy = max(max(box.top - y, y - box.bottom), 0)
        return max(dx, dy)
    }

    // endregion

    // region Lettering

    private fun letter(page: Bitmap, px: IntArray, w: Int, blocks: List<TextBlock>, rects: List<Rect>) {
        val canvas = Canvas(page)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        for ((block, rect) in blocks.zip(rects)) {
            val text = block.translation?.trim().orEmpty()
            if (text.isEmpty() || rect.width() < 8 || rect.height() < 8) continue
            val glyph = block.lines.map { min(it.box.width(), it.box.height()) }.sorted()[block.lines.size / 2]
            val cap = (glyph * 1.2f).toInt().coerceIn(14, 72)
            var size = min(cap, rect.height())
            var layout = build(text, paint, size, rect.width())
            while (size > MIN_TEXT && !fits(layout, rect)) {
                size -= 2
                layout = build(text, paint, size, rect.width())
            }
            val dark = averageLuma(px, w, rect) < 110
            val top = rect.top + (rect.height() - layout.height) / 2f
            canvas.save()
            canvas.translate(rect.left.toFloat(), top)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(2f, size / 6f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = if (dark) Color.BLACK else Color.WHITE
            layout.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = if (dark) Color.WHITE else Color.BLACK
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun build(text: String, paint: TextPaint, size: Int, width: Int): StaticLayout {
        paint.textSize = size.toFloat()
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setBreakStrategy(if (targetLang in CJK) Layout.BREAK_STRATEGY_SIMPLE else Layout.BREAK_STRATEGY_BALANCED)
            .build()
    }

    /** Fits when it is short enough and no single word had to be broken mid-word. */
    private fun fits(layout: StaticLayout, rect: Rect): Boolean {
        if (layout.height > rect.height()) return false
        if (targetLang in CJK) return true
        val text = layout.text
        for (i in 0 until layout.lineCount) {
            val end = layout.getLineEnd(i)
            if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
                return false
            }
        }
        return true
    }

    // endregion

    // region Pixel helpers

    /**
     * Flood-fill the bubble around [box] (seeded at its centre, after erasing). Returns null when the
     * fill leaks into the page (open balloons, text on art, white page background).
     */
    private fun bubbleRegion(px: IntArray, w: Int, h: Int, box: Rect): Region? {
        val sx = box.centerX().coerceIn(0, w - 1)
        val sy = box.centerY().coerceIn(0, h - 1)
        val seed = px[sy * w + sx]
        val mark = floodMark
        val queue = floodQueue
        val limit = queue.size - 4
        var head = 0
        var tail = 0
        queue[tail++] = sy * w + sx
        mark[sy * w + sx] = true
        var minX = sx
        var maxX = sx
        var minY = sy
        var maxY = sy
        var leaked = false
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            if (tail >= limit || (minX == 0 && maxX == w - 1)) {
                leaked = true
                break
            }
            for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, i - w, i + w)) {
                if (n < 0 || n >= px.size || mark[n] || !close(px[n], seed, FLOOD_TOL)) continue
                mark[n] = true
                queue[tail++] = n
            }
        }
        val covers = minX <= box.left && minY <= box.top && maxX >= box.right - 1 && maxY >= box.bottom - 1
        val region = if (leaked || (minY == 0 && maxY == h - 1) || !covers) {
            null
        } else {
            val rw = maxX - minX + 1
            val rh = maxY - minY + 1
            val mask = BooleanArray(rw * rh)
            for (k in 0 until tail) {
                val i = queue[k]
                mask[(i / w - minY) * rw + (i % w - minX)] = true
            }
            Region(minX, minY, rw, rh, mask)
        }
        for (k in 0 until tail) mark[queue[k]] = false
        return region
    }

    private fun ringPixels(px: IntArray, w: Int, h: Int, box: Rect, pad: Int): IntArray {
        val outer = padded(box, pad, w, h)
        val out = ArrayList<Int>()
        for (y in outer.top until outer.bottom step 2) {
            for (x in outer.left until outer.right step 2) {
                if (box.contains(x, y)) continue
                out += px[y * w + x]
            }
        }
        return out.toIntArray()
    }

    private fun medianColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return Color.WHITE
        fun median(shift: Int) = pixels.map { (it shr shift) and 0xFF }.sorted()[pixels.size / 2]
        return Color.rgb(median(16), median(8), median(0))
    }

    private fun isFlat(ring: IntArray, color: Int): Boolean =
        ring.isNotEmpty() && ring.count { close(it, color, FLAT_TOL) } > FLAT_FRAC * ring.size

    private fun close(a: Int, b: Int, tol: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tol &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tol &&
            abs((a and 0xFF) - (b and 0xFF)) <= tol

    private fun fillRect(px: IntArray, w: Int, h: Int, r: Rect, color: Int) {
        for (y in max(r.top, 0) until min(r.bottom, h)) {
            val row = y * w
            for (x in max(r.left, 0) until min(r.right, w)) px[row + x] = color
        }
    }

    private fun padded(r: Rect, pad: Int, w: Int, h: Int) =
        Rect(max(r.left - pad, 0), max(r.top - pad, 0), min(r.right + pad, w), min(r.bottom + pad, h))

    private fun averageLuma(px: IntArray, w: Int, r: Rect): Int {
        var sum = 0L
        var n = 0
        val h = px.size / w
        for (y in max(r.top, 0) until min(r.bottom, h) step 3) {
            for (x in max(r.left, 0) until min(r.right, w) step 3) {
                val c = px[y * w + x]
                sum += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                n++
            }
        }
        return if (n == 0) 255 else (sum / n).toInt()
    }

    // endregion

    companion object {
        private const val PAD = 5
        private const val FLOOD_TOL = 10
        private const val FLAT_TOL = 30
        private const val FLAT_FRAC = 0.85f
        private const val SLACK = 0.02f
        private const val MIN_TEXT = 10
        private val CJK = setOf("ja", "zh", "ko")
        private val GROW_ORDER = listOf(0 to -3, 2 to 3, 1 to -3, 3 to 3)
    }
}

package com.prasadbhandary.pdfmarkup

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Annotations live in "page points": the page's own coordinate system as the
 * renderer reports it (top-left origin, y pointing down, page rotation applied).
 */
enum class Tool { HAND, PEN, HIGHLIGHT, TEXT, ERASER }

sealed class Mark {
    abstract val id: Long
    abstract val page: Int
    abstract val color: Int // ARGB
    abstract fun bounds(): Rect
    abstract fun toJson(): JSONObject
    abstract fun withPage(p: Int): Mark
}

class InkMark(
    override val id: Long,
    override val page: Int,
    val points: List<Offset>,
    override val color: Int,
    val width: Float,
    val highlighter: Boolean,
) : Mark() {

    val path: Path by lazy { buildSmoothPath(points) }

    private val box: Rect by lazy {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (p in points) {
            l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
        }
        val h = width / 2f
        Rect(l - h, t - h, r + h, b + h)
    }

    override fun bounds(): Rect = box

    /** True if [p] lies within [tolerance] of the stroke. */
    fun hit(p: Offset, tolerance: Float): Boolean {
        val reach = width / 2f + tolerance
        val b = box
        if (p.x < b.left - tolerance || p.x > b.right + tolerance ||
            p.y < b.top - tolerance || p.y > b.bottom + tolerance) return false
        if (points.size == 1) return (points[0] - p).getDistance() <= reach
        for (i in 0 until points.size - 1) {
            if (distToSegment(p, points[i], points[i + 1]) <= reach) return true
        }
        return false
    }

    override fun toJson(): JSONObject = JSONObject().apply {
        put("t", "ink")
        put("id", id)
        put("p", page)
        put("c", color)
        put("w", width.toDouble())
        put("h", highlighter)
        val arr = JSONArray()
        for (pt in points) {
            arr.put(round2(pt.x)); arr.put(round2(pt.y))
        }
        put("pts", arr)
    }

    override fun withPage(p: Int) = InkMark(id, p, points, color, width, highlighter)
}

class TextMark(
    override val id: Long,
    override val page: Int,
    val x: Float, // top-left of the text box
    val y: Float,
    val text: String,
    override val color: Int,
    val size: Float,
) : Mark() {

    val lines: List<String> get() = text.split('\n')

    override fun bounds(): Rect {
        val w = lines.maxOf { measureWidth(it, size) }
        return Rect(x, y, x + max(w, size) + 2 * TEXT_PAD, y + lines.size * size * LINE_HEIGHT + 2 * TEXT_PAD)
    }

    /** Baseline (page y) of line [i]. */
    fun baseline(i: Int) = y + TEXT_PAD + size * ASCENT + i * size * LINE_HEIGHT

    fun moved(dx: Float, dy: Float) = TextMark(id, page, x + dx, y + dy, text, color, size)

    fun edited(newText: String, newColor: Int, newSize: Float) =
        TextMark(id, page, x, y, newText, newColor, newSize)

    override fun toJson(): JSONObject = JSONObject().apply {
        put("t", "text")
        put("id", id)
        put("p", page)
        put("x", round2(x)); put("y", round2(y))
        put("s", size.toDouble())
        put("c", color)
        put("txt", text)
    }

    override fun withPage(p: Int) = TextMark(id, p, x, y, text, color, size)

    companion object {
        const val LINE_HEIGHT = 1.25f
        const val ASCENT = 0.95f
        const val TEXT_PAD = 2f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }

        fun measureWidth(s: String, size: Float): Float = synchronized(paint) {
            paint.textSize = size
            paint.measureText(s)
        }
    }
}

fun newMarkId(): Long = Random.nextLong(1, Long.MAX_VALUE)

private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0

fun markFromJson(o: JSONObject, page: Int): Mark? = try {
    when (o.getString("t")) {
        "ink" -> {
            val arr = o.getJSONArray("pts")
            val pts = ArrayList<Offset>(arr.length() / 2)
            var i = 0
            while (i + 1 < arr.length()) {
                pts.add(Offset(arr.getDouble(i).toFloat(), arr.getDouble(i + 1).toFloat()))
                i += 2
            }
            if (pts.isEmpty()) null else InkMark(
                o.optLong("id", newMarkId()), page, pts, o.getInt("c"),
                o.getDouble("w").toFloat(), o.optBoolean("h", false)
            )
        }
        "text" -> TextMark(
            o.optLong("id", newMarkId()), page,
            o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
            o.getString("txt"), o.getInt("c"), o.getDouble("s").toFloat()
        )
        else -> null
    }
} catch (e: Exception) {
    null
}

fun distToSegment(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return (p - Offset(a.x + t * dx, a.y + t * dy)).getDistance()
}

/** A smooth path through the points (quadratic curves between midpoints). */
fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) {
        path.lineTo(points[0].x + 0.01f, points[0].y)
        return path
    }
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 1 until points.size - 1) {
        val c = points[i]
        val n = points[i + 1]
        @Suppress("DEPRECATION") path.quadraticBezierTo(c.x, c.y, (c.x + n.x) / 2f, (c.y + n.y) / 2f)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

/** Drops points closer than [minDist] to the previous kept point. */
fun simplify(points: List<Offset>, minDist: Float): List<Offset> {
    if (points.size < 3) return points
    val out = ArrayList<Offset>(points.size)
    out.add(points[0])
    for (i in 1 until points.size - 1) {
        if ((points[i] - out.last()).getDistance() >= minDist) out.add(points[i])
    }
    out.add(points.last())
    return out
}

/** Colours offered in the tool palette (ARGB). */
val PEN_COLORS = listOf(
    0xFF111111.toInt(), 0xFFD32F2F.toInt(), 0xFF1E5BD8.toInt(),
    0xFF2E7D32.toInt(), 0xFF8E24AA.toInt(), 0xFFEF6C00.toInt(),
)
val HIGHLIGHT_COLORS = listOf(
    0xFFFFEB3B.toInt(), 0xFF76FF03.toInt(), 0xFF40C4FF.toInt(),
    0xFFFF80AB.toInt(), 0xFFFFAB40.toInt(),
)
const val HIGHLIGHT_ALPHA = 0.4f

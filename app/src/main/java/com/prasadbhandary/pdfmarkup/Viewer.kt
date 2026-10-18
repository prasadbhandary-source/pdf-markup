package com.prasadbhandary.pdfmarkup

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_ZOOM = 6f
private const val MIN_ZOOM = 1f

/** A sharp, zoomed-in rendering of the visible part of one page. */
private class Tile(
    val page: Int,
    val zoom: Float,
    val contentLeft: Float, // position in content pixels at [zoom]
    val contentTop: Float,
    val image: ImageBitmap,
)

private class PageImage(val bitmap: Bitmap, val image: ImageBitmap)

/**
 * Everything the viewer needs: layout of the pages, zoom/scroll position, rendered
 * bitmaps and the annotations being edited.
 */
class ViewerState(val pdf: OpenedPdf) {

    // ---- annotations ------------------------------------------------------------------
    val marks = mutableStateListOf<Mark>().apply { addAll(pdf.initialMarks) }
    private val undoStack = ArrayList<List<Mark>>()
    private val redoStack = ArrayList<List<Mark>>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var dirty by mutableStateOf(false)

    fun snapshot(): List<Mark> = marks.toList()

    /** Records [before] as an undo step (call after changing [marks]). */
    fun commit(before: List<Mark>) {
        undoStack.add(before)
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
        dirty = true
        updateFlags()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.add(snapshot())
        marks.clear(); marks.addAll(prev)
        dirty = true
        updateFlags()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(snapshot())
        marks.clear(); marks.addAll(next)
        dirty = true
        updateFlags()
    }

    private fun updateFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    // ---- live stroke ------------------------------------------------------------------
    val livePoints = mutableStateListOf<Offset>()
    var livePage by mutableIntStateOf(-1)

    // ---- layout -----------------------------------------------------------------------
    var viewW by mutableFloatStateOf(0f); private set
    var viewH by mutableFloatStateOf(0f); private set
    var zoom by mutableFloatStateOf(1f); private set
    var scrollX by mutableFloatStateOf(0f); private set
    var scrollY by mutableFloatStateOf(0f); private set
    var gestureActive by mutableStateOf(false)
    private var gap = 12f
    /** Page to show first (e.g. where you left off). */
    var startPage = 0

    // Page positions at zoom 1, in pixels.
    private var tops = FloatArray(0)
    private var heights = FloatArray(0)
    private var pageW = 0f
    private var totalH = 0f
    val pageCount get() = pdf.pageSizes.size

    fun setViewport(w: Float, h: Float, density: Float) {
        if (w <= 0f || h <= 0f) return
        if (w == viewW && h == viewH) return
        val oldW = viewW
        val anchorPage = currentPage
        viewW = w; viewH = h
        gap = 8f * density
        pageW = w - 2 * gap
        tops = FloatArray(pageCount)
        heights = FloatArray(pageCount)
        var y = gap
        for (i in 0 until pageCount) {
            val s = pdf.pageSizes[i]
            tops[i] = y
            heights[i] = pageW * s.height / s.width
            y += heights[i] + gap
        }
        totalH = y
        if (oldW > 0f) {
            scrollToPage(anchorPage)
        } else if (startPage > 0) {
            scrollToPage(startPage)
        } else {
            clamp()
        }
        layoutVersion++
    }

    var layoutVersion by mutableIntStateOf(0); private set

    /** Pixels per page point at zoom 1. */
    fun baseScale(page: Int) = pageW / pdf.pageSizes[page].width

    fun pageLeft(): Float = gap * zoom - scrollX
    fun pageTop(page: Int): Float = tops[page] * zoom - scrollY
    fun pageScreenWidth() = pageW * zoom
    fun pageScreenHeight(page: Int) = heights[page] * zoom
    fun screenScale(page: Int) = baseScale(page) * zoom

    fun visiblePages(): IntRange {
        if (pageCount == 0 || viewH == 0f) return IntRange.EMPTY
        var first = -1
        var last = -1
        for (i in 0 until pageCount) {
            val t = pageTop(i)
            val b = t + pageScreenHeight(i)
            if (b >= 0 && t <= viewH) {
                if (first < 0) first = i
                last = i
            } else if (first >= 0) break
        }
        return if (first < 0) IntRange.EMPTY else first..last
    }

    val currentPage: Int
        get() {
            if (pageCount == 0 || tops.isEmpty()) return 0
            val center = (scrollY + viewH / 2f) / zoom
            var idx = 0
            for (i in 0 until pageCount) if (tops[i] <= center) idx = i else break
            return idx
        }

    /** Screen position to (page, page point), or null if not on a page. */
    fun hitPage(pos: Offset, slack: Float = 0f): Pair<Int, Offset>? {
        if (tops.isEmpty()) return null
        val left = pageLeft()
        if (pos.x < left - slack || pos.x > left + pageScreenWidth() + slack) return null
        for (i in visiblePages()) {
            val t = pageTop(i)
            if (pos.y >= t - slack && pos.y <= t + pageScreenHeight(i) + slack) {
                return i to toPage(i, pos)
            }
        }
        return null
    }

    fun toPage(page: Int, pos: Offset): Offset {
        val s = screenScale(page)
        return Offset((pos.x - pageLeft()) / s, (pos.y - pageTop(page)) / s)
    }

    fun panBy(dx: Float, dy: Float) {
        scrollX -= dx
        scrollY -= dy
        clamp()
    }

    fun zoomBy(factor: Float, focus: Offset) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return
        val cx = (scrollX + focus.x) / zoom
        val cy = (scrollY + focus.y) / zoom
        zoom = newZoom
        scrollX = cx * newZoom - focus.x
        scrollY = cy * newZoom - focus.y
        clamp()
    }

    fun setZoomAnimatedTarget(target: Float, focus: Offset) = zoomBy(target / zoom, focus)

    fun scrollToPage(page: Int) {
        if (tops.isEmpty()) return
        scrollY = tops[page.coerceIn(0, pageCount - 1)] * zoom - gap
        clamp()
    }

    private fun clamp() {
        val maxX = max(0f, viewW * zoom - viewW)
        val maxY = max(0f, totalH * zoom - viewH)
        scrollX = scrollX.coerceIn(0f, maxX)
        scrollY = scrollY.coerceIn(0f, maxY)
    }

    // ---- fling ------------------------------------------------------------------------
    private var flingJob: Job? = null

    fun stopFling() {
        flingJob?.cancel()
        flingJob = null
    }

    fun fling(scope: CoroutineScope, vx: Float, vy: Float) {
        stopFling()
        var velX = vx
        var velY = vy
        if (abs(velX) < 100f && abs(velY) < 100f) return
        flingJob = scope.launch {
            var last = awaitFrame()
            while (isActive && (abs(velX) > 30f || abs(velY) > 30f)) {
                val now = awaitFrame()
                val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                last = now
                val beforeX = scrollX
                val beforeY = scrollY
                panBy(velX * dt, velY * dt)
                if (scrollX == beforeX) velX = 0f
                if (scrollY == beforeY) velY = 0f
                val decay = exp(-3.2f * dt)
                velX *= decay
                velY *= decay
            }
        }
    }

    // ---- rendering --------------------------------------------------------------------
    val renderDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val cacheBytes = min(Runtime.getRuntime().maxMemory() / 4, 160L * 1024 * 1024).toInt()
    private val pageCache = object : LruCache<Int, PageImage>(cacheBytes) {
        override fun sizeOf(key: Int, value: PageImage) = value.bitmap.byteCount
    }
    private var cacheWidth = 0
    var renderTick by mutableIntStateOf(0); private set
    private var tiles by mutableStateOf<List<Tile>>(emptyList())

    fun pageImage(page: Int): ImageBitmap? = pageCache.get(page)?.image

    suspend fun renderVisible() {
        val vis = visiblePages()
        if (vis.isEmpty()) return
        val width = min(pageW, 1600f).roundToInt().coerceAtLeast(1)
        if (width != cacheWidth) {
            pageCache.evictAll()
            cacheWidth = width
        }
        val wanted = (max(0, vis.first - 1)..min(pageCount - 1, vis.last + 1)).toList()
        // visible pages first
        val order = vis.toList() + wanted.filter { it !in vis }
        for (p in order) {
            if (pageCache.get(p) != null) continue
            val size = pdf.pageSizes[p]
            val w = width
            var h = (w * size.height / size.width).roundToInt().coerceAtLeast(1)
            // Keep very long pages within a sane bitmap size.
            if (w.toLong() * h > 12_000_000L) h = (12_000_000L / w).toInt()
            val bmp = withContext(renderDispatcher) {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val m = Matrix()
                m.setScale(w / size.width, h / size.height)
                pdf.source.render(p, b, m)
                b
            }
            pageCache.put(p, PageImage(bmp, bmp.asImageBitmap()))
            renderTick++
        }
    }

    suspend fun renderTiles() {
        if (!pdf.source.supportsTiles || zoom < 1.15f) {
            if (tiles.isNotEmpty()) tiles = emptyList()
            return
        }
        val z = zoom
        val sx = scrollX
        val sy = scrollY
        val result = ArrayList<Tile>()
        for (p in visiblePages()) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val left = gap * z - sx
            val top = tops[p] * z - sy
            val l = max(0f, left)
            val t = max(0f, top)
            val r = min(viewW, left + pageW * z)
            val b = min(viewH, top + heights[p] * z)
            val tw = (r - l).roundToInt()
            val th = (b - t).roundToInt()
            if (tw <= 0 || th <= 0) continue
            val scale = baseScale(p) * z
            val bmp = withContext(renderDispatcher) {
                val bm = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                val m = Matrix()
                m.setScale(scale, scale)
                m.postTranslate(left - l, top - t)
                pdf.source.render(p, bm, m)
                bm
            }
            result.add(Tile(p, z, l + sx, t + sy, bmp.asImageBitmap()))
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        tiles = result
    }

    fun close() {
        stopFling()
        val src = pdf.source
        val disp = renderDispatcher
        disp.executor.execute {
            try { src.close() } catch (_: Throwable) {}
        }
        disp.close()
        pageCache.evictAll()
        tiles = emptyList()
    }

    // ---- drawing ----------------------------------------------------------------------
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }

    fun DrawScope.drawDocument(tool: Tool, liveColor: Int, liveWidth: Float, selectedText: Long?) {
        // Reading these registers redraw dependencies.
        renderTick; layoutVersion
        drawRect(Color(0xFFE6E6EA))
        val left = pageLeft()
        val pw = pageScreenWidth()
        val currentTiles = tiles
        for (p in visiblePages()) {
            val top = pageTop(p)
            val ph = pageScreenHeight(p)
            drawRect(Color(0x22000000), Offset(left + 1f, top + 2f), Size(pw, ph))
            drawRect(Color.White, Offset(left, top), Size(pw, ph))
            pageImage(p)?.let { img ->
                drawImage(
                    img,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(pw.roundToInt(), ph.roundToInt()),
                )
            }
            for (tile in currentTiles) {
                if (tile.page != p || tile.zoom != zoom) continue
                drawImage(tile.image, topLeft = Offset(tile.contentLeft - scrollX, tile.contentTop - scrollY))
            }
            val s = screenScale(p)
            clipRect(left, top, left + pw, top + ph) {
                withTransform({
                    translate(left, top)
                    scale(s, s, Offset.Zero)
                }) {
                    for (m in marks) if (m.page == p) drawMark(m, selected = m.id == selectedText)
                    if (livePage == p && livePoints.isNotEmpty() && (tool == Tool.PEN || tool == Tool.HIGHLIGHT)) {
                        drawInk(buildSmoothPath(livePoints.toList()), liveColor, liveWidth, tool == Tool.HIGHLIGHT)
                    }
                }
            }
        }
    }

    private fun DrawScope.drawInk(path: androidx.compose.ui.graphics.Path, color: Int, width: Float, highlighter: Boolean) {
        if (highlighter) {
            drawPath(
                path, Color(color).copy(alpha = HIGHLIGHT_ALPHA),
                style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = BlendMode.Multiply,
            )
        } else {
            drawPath(path, Color(color), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    private fun DrawScope.drawMark(m: Mark, selected: Boolean) {
        when (m) {
            is InkMark -> drawInk(m.path, m.color, m.width, m.highlighter)
            is TextMark -> {
                if (selected) {
                    val b = m.bounds()
                    drawRect(
                        Color(0xFF1E5BD8), Offset(b.left, b.top), b.size,
                        style = Stroke(width = 1f),
                    )
                }
                textPaint.textSize = m.size
                textPaint.color = m.color
                val canvas = drawContext.canvas.nativeCanvas
                m.lines.forEachIndexed { i, line ->
                    canvas.drawText(line, m.x + TextMark.TEXT_PAD, m.baseline(i), textPaint)
                }
            }
        }
    }
}

/** The scrolling, zooming page view with drawing gestures. */
@Composable
fun PdfCanvas(
    state: ViewerState,
    tool: Tool,
    color: Int,
    width: Float,
    selectedText: Long?,
    onTextTap: (page: Int, point: Offset, hit: TextMark?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    // Render whole pages as they scroll into view.
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.visiblePages(), state.layoutVersion, state.viewW) }
            .collectLatest { state.renderVisible() }
    }
    // Render a sharp tile once zooming/panning settles.
    LaunchedEffect(state) {
        snapshotFlow { listOf(state.zoom, state.scrollX, state.scrollY, if (state.gestureActive) 1f else 0f) }
            .collectLatest { v ->
                if (v[3] == 1f) return@collectLatest
                delay(140)
                state.renderTiles()
            }
    }

    var lastTapTime = 0L
    var lastTapPos = Offset.Zero

    Canvas(
        modifier
            .onSizeChanged { state.setViewport(it.width.toFloat(), it.height.toFloat(), density) }
            .pointerInput(state, tool, color, width) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.stopFling()
                    state.gestureActive = true
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    try {

                    val drawTool = tool == Tool.PEN || tool == Tool.HIGHLIGHT || tool == Tool.ERASER
                    var mode = 0 // 0 = pan, 1 = draw, 2 = pinch, 3 = text tap pending, 4 = drag text
                    var drawPage = -1
                    var moved = 0f
                    val before = state.snapshot()
                    var erased = false
                    var dragText: TextMark? = null
                    var dragStart = Offset.Zero
                    val textHit: TextMark?
                    val textPage: Pair<Int, Offset>?
                    val primary: PointerId = down.id

                    fun eraseAt(pos: Offset) {
                        val hp = state.hitPage(pos) ?: return
                        val tol = 10f / state.screenScale(hp.first)
                        val iter = state.marks.iterator()
                        val toRemove = ArrayList<Mark>()
                        while (iter.hasNext()) {
                            val m = iter.next()
                            if (m.page != hp.first) continue
                            val hit = when (m) {
                                is InkMark -> m.hit(hp.second, tol)
                                is TextMark -> m.bounds().inflate(tol).contains(hp.second)
                            }
                            if (hit) toRemove.add(m)
                        }
                        if (toRemove.isNotEmpty()) {
                            state.marks.removeAll(toRemove)
                            erased = true
                        }
                    }

                    if (tool == Tool.TEXT) {
                        textPage = state.hitPage(down.position)
                        textHit = textPage?.let { (pg, pt) ->
                            state.marks.lastOrNull { it is TextMark && it.page == pg && it.bounds().inflate(6f).contains(pt) } as TextMark?
                        }
                        mode = 3
                    } else {
                        textPage = null
                        textHit = null
                        if (drawTool) {
                            val hp = state.hitPage(down.position, slack = 24f)
                            if (hp != null) {
                                mode = 1
                                drawPage = hp.first
                                if (tool == Tool.ERASER) {
                                    eraseAt(down.position)
                                } else {
                                    state.livePage = drawPage
                                    state.livePoints.clear()
                                    state.livePoints.add(state.toPage(drawPage, down.position))
                                }
                            }
                        }
                    }
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2 && mode != 2) {
                            // Second finger: switch to pinch-zoom / two-finger scroll.
                            if (mode == 1) {
                                state.livePoints.clear()
                                state.livePage = -1
                                if (erased) {
                                    state.marks.clear(); state.marks.addAll(before); erased = false
                                }
                            }
                            if (mode == 4 && dragText != null) {
                                state.marks.clear(); state.marks.addAll(before)
                            }
                            mode = 2
                        }

                        when (mode) {
                            1 -> {
                                val ch = event.changes.firstOrNull { it.id == primary } ?: pressed.first()
                                if (tool == Tool.ERASER) {
                                    eraseAt(ch.position)
                                } else {
                                    state.livePoints.add(state.toPage(drawPage, ch.position))
                                }
                                ch.consume()
                            }
                            3, 4 -> {
                                val ch = event.changes.firstOrNull { it.id == primary } ?: pressed.first()
                                moved += ch.positionChange().getDistance()
                                if (mode == 3 && moved > slop) {
                                    if (textHit != null) {
                                        mode = 4
                                        dragText = textHit
                                        dragStart = down.position
                                    } else {
                                        mode = 0
                                    }
                                }
                                if (mode == 4) {
                                    val t = textHit!!
                                    val s = state.screenScale(t.page)
                                    val d = (ch.position - dragStart) / s
                                    val idx = state.marks.indexOfFirst { it.id == t.id }
                                    if (idx >= 0) state.marks[idx] = t.moved(d.x, d.y)
                                } else if (mode == 0) {
                                    state.panBy(ch.positionChange().x, ch.positionChange().y)
                                }
                                ch.consume()
                            }
                            else -> {
                                val zoomChange = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (zoomChange != 1f && centroid != Offset.Unspecified) state.zoomBy(zoomChange, centroid)
                                state.panBy(pan.x, pan.y)
                                moved += pan.getDistance()
                                val ch = event.changes.firstOrNull { it.id == primary } ?: pressed.first()
                                if (pressed.size == 1) tracker.addPosition(ch.uptimeMillis, ch.position)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }

                    // Gesture finished.
                    when (mode) {
                        1 -> {
                            if (tool == Tool.ERASER) {
                                if (erased) state.commit(before)
                            } else if (state.livePoints.isNotEmpty()) {
                                val pts = simplify(state.livePoints.toList(), 0.6f / state.zoom)
                                state.marks.add(
                                    InkMark(newMarkId(), drawPage, pts, color, width, tool == Tool.HIGHLIGHT)
                                )
                                state.livePoints.clear()
                                state.livePage = -1
                                state.commit(before)
                            }
                        }
                        3 -> {
                            if (textPage != null) onTextTap(textPage.first, textPage.second, textHit)
                        }
                        4 -> state.commit(before)
                        0 -> {
                            if (moved < slop && tool == Tool.HAND) {
                                // Double-tap to zoom in / out.
                                val now = down.uptimeMillis
                                if (now - lastTapTime < 320 && (down.position - lastTapPos).getDistance() < 60f) {
                                    val target = if (state.zoom > 1.4f) 1f else 2.5f
                                    state.setZoomAnimatedTarget(target, down.position)
                                    lastTapTime = 0L
                                } else {
                                    lastTapTime = now
                                    lastTapPos = down.position
                                }
                            } else {
                                val v = tracker.calculateVelocity()
                                state.fling(scope, v.x, v.y)
                            }
                        }
                    }
                    } finally {
                        state.gestureActive = false
                        if (state.livePage >= 0) {
                            state.livePoints.clear()
                            state.livePage = -1
                        }
                    }
                }
            }
    ) {
        with(state) { drawDocument(tool, color, width, selectedText) }
    }
}

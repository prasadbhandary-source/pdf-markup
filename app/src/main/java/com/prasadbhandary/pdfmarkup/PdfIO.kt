package com.prasadbhandary.pdfmarkup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "PdfIO"

/** Key under which the app stores its own annotation data inside each annotation it writes. */
private val MARK_KEY: COSName = COSName.getPDFName("PDFMarkupData")

data class PageSize(val width: Float, val height: Float)

/** Something that can draw PDF pages into bitmaps. All calls must come from one thread. */
interface PageSource : Closeable {
    val pageCount: Int
    val supportsTiles: Boolean
    fun pageSize(index: Int): PageSize
    /** Draws page [index] into [bitmap]; [transform] maps page points to bitmap pixels. */
    fun render(index: Int, bitmap: Bitmap, transform: Matrix)
}

/** Android's built-in renderer: fast, and can draw any zoomed-in region sharply. */
class PlatformPageSource(file: File) : PageSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Throwable) {
        pfd.close(); throw e
    }
    private val sizes: List<PageSize> = (0 until renderer.pageCount).map { i ->
        renderer.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
    }
    private var closed = false

    override val pageCount get() = sizes.size
    override val supportsTiles = true
    override fun pageSize(index: Int) = sizes[index]

    override fun render(index: Int, bitmap: Bitmap, transform: Matrix) {
        if (closed) return
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(index).use { page ->
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { renderer.close() } catch (_: Throwable) {}
        try { pfd.close() } catch (_: Throwable) {}
    }
}

/** Fallback renderer for files Android's renderer refuses. Slower; whole pages only. */
class PdfBoxPageSource(private val doc: PDDocument) : PageSource {
    private val renderer = PDFRenderer(doc)
    private val sizes: List<PageSize> = (0 until doc.numberOfPages).map { i ->
        val g = pageGeometry(doc.getPage(i))
        PageSize(g.dispW, g.dispH)
    }
    private var closed = false

    override val pageCount get() = sizes.size
    override val supportsTiles = false
    override fun pageSize(index: Int) = sizes[index]

    override fun render(index: Int, bitmap: Bitmap, transform: Matrix) {
        if (closed) return
        bitmap.eraseColor(Color.WHITE)
        val values = FloatArray(9)
        transform.getValues(values)
        val scale = values[Matrix.MSCALE_X].coerceAtMost(4f)
        val img = renderer.renderImage(index, scale)
        val canvas = Canvas(bitmap)
        val m = Matrix(transform)
        m.preScale(1f / scale, 1f / scale)
        canvas.drawBitmap(img, m, null)
        img.recycle()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { doc.close() } catch (_: Throwable) {}
    }
}

class OpenedPdf(
    val uri: Uri,
    val displayName: String,
    /** Untouched copy of the original file, used as the base for every save. */
    val origFile: File,
    val password: String?,
    val source: PageSource,
    val pageSizes: List<PageSize>,
    val initialMarks: List<Mark>,
    /** False if the file could be shown but not parsed for writing. */
    val canSave: Boolean,
)

sealed class OpenResult {
    class Ok(val pdf: OpenedPdf) : OpenResult()
    class NeedPassword(val wrong: Boolean) : OpenResult()
    class Failed(val message: String) : OpenResult()
}

object PdfIO {

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
        } catch (_: Exception) {}
        val last = uri.lastPathSegment?.substringAfterLast('/')
        return if (last.isNullOrBlank()) "Document.pdf" else last
    }

    /** Copies the document into the app's cache so it can be read repeatedly. */
    fun copyToCache(context: Context, uri: Uri): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val dir = File(context.cacheDir, "docs/$hash").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "original.pdf")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Couldn't read the file")
        input.use { i -> FileOutputStream(out).use { o -> i.copyTo(o, 64 * 1024) } }
        if (out.length() == 0L) throw IOException("The file is empty")
        return out
    }

    private fun loadDoc(file: File, password: String?): PDDocument =
        PDDocument.load(file, password ?: "", MemoryUsageSetting.setupTempFileOnly())

    /**
     * Opens [orig]. Annotations this app wrote earlier are lifted out so they can be
     * edited again; everything else in the file is shown as-is.
     */
    fun open(context: Context, uri: Uri, name: String, orig: File, password: String?): OpenResult {
        val marks = ArrayList<Mark>()
        var renderFile = orig
        var parsed = false
        var boxDocForRender: PDDocument? = null

        // 1. Parse with PDFBox: handles passwords and finds our own annotations.
        try {
            val doc = loadDoc(orig, password)
            parsed = true
            var removedAny = false
            doc.pages.forEachIndexed { index, page ->
                val annots = page.annotations
                val keep = ArrayList<PDAnnotation>(annots.size)
                for (a in annots) {
                    val data = a.cosObject.getString(MARK_KEY)
                    if (data != null) {
                        val m = try { markFromJson(JSONObject(data), index) } catch (_: Exception) { null }
                        if (m != null) { marks.add(m); removedAny = true; continue }
                    }
                    keep.add(a)
                }
                if (keep.size != annots.size) page.setAnnotations(keep)
            }
            if (doc.isEncrypted || removedAny) {
                if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
                val clean = File(orig.parentFile, "view.pdf")
                doc.save(clean)
                renderFile = clean
            }
            doc.close()
        } catch (e: InvalidPasswordException) {
            return OpenResult.NeedPassword(wrong = password != null)
        } catch (e: Throwable) {
            Log.w(TAG, "PDFBox could not parse the file", e)
            marks.clear()
        }

        // 2. Show it with Android's renderer, or PDFBox's if that fails.
        val source: PageSource = try {
            PlatformPageSource(renderFile)
        } catch (e: Throwable) {
            Log.w(TAG, "Platform renderer failed, falling back", e)
            try {
                val d = loadDoc(renderFile, if (renderFile == orig) password else null)
                boxDocForRender = d
                PdfBoxPageSource(d)
            } catch (e2: InvalidPasswordException) {
                return OpenResult.NeedPassword(wrong = password != null)
            } catch (e2: Throwable) {
                try { boxDocForRender?.close() } catch (_: Throwable) {}
                Log.w(TAG, "PDFBox renderer failed too", e2)
                return OpenResult.Failed("This file couldn't be opened. It may be damaged or not a PDF.")
            }
        }
        if (source.pageCount == 0) {
            source.close()
            return OpenResult.Failed("This PDF has no pages.")
        }
        val sizes = (0 until source.pageCount).map { source.pageSize(it) }
        val valid = marks.filter { it.page < sizes.size }
        return OpenResult.Ok(OpenedPdf(uri, name, orig, password, source, sizes, valid, canSave = parsed))
    }

    /** Writes the original document plus [marks] as real PDF annotations into [out]. */
    fun writeAnnotated(context: Context, pdf: OpenedPdf, marks: List<Mark>, out: File) {
        val doc = loadDoc(pdf.origFile, pdf.password)
        try {
            val font = AnnotFont(doc)
            val byPage = marks.groupBy { it.page }
            for (index in 0 until doc.numberOfPages) {
                val page = doc.getPage(index)
                val existing = page.annotations
                val keep = existing.filter { it.cosObject.getString(MARK_KEY) == null }
                val adds = byPage[index].orEmpty()
                if (keep.size == existing.size && adds.isEmpty()) continue
                val list = ArrayList<PDAnnotation>(keep)
                val size = pdf.pageSizes.getOrNull(index)
                if (size != null) {
                    val geo = pageGeometry(page)
                    for (m in adds) {
                        try {
                            list.add(buildAnnotation(doc, page, geo, size, m, font))
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping an annotation that couldn't be written", e)
                        }
                    }
                }
                page.setAnnotations(list)
            }
            try {
                doc.save(out)
            } catch (e: Exception) {
                // Some protected files can't be re-encrypted; save them unprotected instead.
                if (!doc.isEncrypted) throw e
                doc.setAllSecurityToBeRemoved(true)
                doc.save(out)
            }
        } finally {
            doc.close()
        }
    }

    /** Copies [file] over the document at [uri]. */
    fun writeToUri(context: Context, file: File, uri: Uri) {
        val os = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            null
        } ?: context.contentResolver.openOutputStream(uri, "w")
        ?: throw IOException("Couldn't write to this file")
        os.use { o -> file.inputStream().use { it.copyTo(o, 64 * 1024) } }
    }

    // ---- Annotation writing -------------------------------------------------------------

    private fun buildAnnotation(
        doc: PDDocument, page: PDPage, geo: PageGeometry, rendererSize: PageSize, m: Mark, font: AnnotFont,
    ): PDAnnotation {
        val sx = geo.dispW / rendererSize.width
        val sy = geo.dispH / rendererSize.height
        // Maps a point in page points (y down) to PDF user space (y up).
        fun toUser(x: Float, y: Float): Pair<Float, Float> = geo.toUser(x * sx, y * sy)

        val b = m.bounds()
        val pad = 2f
        val corners = listOf(
            toUser(b.left - pad, b.top - pad), toUser(b.right + pad, b.top - pad),
            toUser(b.left - pad, b.bottom + pad), toUser(b.right + pad, b.bottom + pad),
        )
        val llx = corners.minOf { it.first }; val lly = corners.minOf { it.second }
        val urx = corners.maxOf { it.first }; val ury = corners.maxOf { it.second }
        val rect = PDRectangle(llx, lly, urx - llx, ury - lly)

        // Content-stream matrix that lets us draw in page points with y flipped (y up).
        val o = toUser(0f, 0f)
        val px = toUser(1f, 0f).let { Pair(it.first - o.first, it.second - o.second) }
        val py = toUser(0f, 1f).let { Pair(it.first - o.first, it.second - o.second) }
        val cm = PdfMatrix(px.first, px.second, -py.first, -py.second, o.first, o.second)
        val unit = kotlin.math.sqrt(px.first * px.first + px.second * px.second) // user units per page point

        val r = ((m.color shr 16) and 0xFF) / 255f
        val g = ((m.color shr 8) and 0xFF) / 255f
        val bl = (m.color and 0xFF) / 255f

        val dict = COSDictionary()
        dict.setName(COSName.TYPE, "Annot")
        dict.setInt(COSName.F, 4) // print
        dict.setString(COSName.getPDFName("T"), "PDF Markup")
        dict.setString(COSName.getPDFName("NM"), "pdfmarkup-${m.id}")
        dict.setString(MARK_KEY, m.toJson().toString())
        dict.setItem(COSName.getPDFName("C"), COSArray().apply {
            add(COSFloat(r)); add(COSFloat(g)); add(COSFloat(bl))
        })

        val ap = PDAppearanceStream(doc)
        ap.setBBox(rect)
        ap.setResources(PDResources())

        when (m) {
            is InkMark -> {
                dict.setName(COSName.SUBTYPE, "Ink")
                val ink = COSArray()
                for (p in m.points) {
                    val u = toUser(p.x, p.y)
                    ink.add(COSFloat(u.first)); ink.add(COSFloat(u.second))
                }
                dict.setItem(COSName.getPDFName("InkList"), COSArray().apply { add(ink) })
                dict.setItem(COSName.getPDFName("BS"), COSDictionary().apply {
                    setFloat(COSName.getPDFName("W"), m.width * unit)
                    setName(COSName.getPDFName("S"), "S")
                })
                if (m.highlighter) dict.setString(COSName.getPDFName("Subj"), "Highlight")

                PDPageContentStream(doc, ap).use { cs ->
                    cs.saveGraphicsState()
                    if (m.highlighter) {
                        val gs = PDExtendedGraphicsState()
                        gs.setStrokingAlphaConstant(HIGHLIGHT_ALPHA)
                        gs.setNonStrokingAlphaConstant(HIGHLIGHT_ALPHA)
                        gs.setBlendMode(BlendMode.MULTIPLY)
                        cs.setGraphicsStateParameters(gs)
                    }
                    cs.transform(cm)
                    cs.setStrokingColor(r, g, bl)
                    cs.setLineWidth(m.width)
                    cs.setLineCapStyle(1)
                    cs.setLineJoinStyle(1)
                    val pts = m.points
                    cs.moveTo(pts[0].x, -pts[0].y)
                    if (pts.size == 1) {
                        cs.lineTo(pts[0].x + 0.01f, -pts[0].y)
                    } else if (pts.size == 2) {
                        cs.lineTo(pts[1].x, -pts[1].y)
                    } else {
                        // Same smoothing as on screen: quadratic curves between midpoints.
                        var cur = pts[0]
                        for (i in 1 until pts.size - 1) {
                            val c = pts[i]
                            val n = pts[i + 1]
                            val end = androidx.compose.ui.geometry.Offset((c.x + n.x) / 2f, (c.y + n.y) / 2f)
                            val c1x = cur.x + 2f / 3f * (c.x - cur.x)
                            val c1y = cur.y + 2f / 3f * (c.y - cur.y)
                            val c2x = end.x + 2f / 3f * (c.x - end.x)
                            val c2y = end.y + 2f / 3f * (c.y - end.y)
                            cs.curveTo(c1x, -c1y, c2x, -c2y, end.x, -end.y)
                            cur = end
                        }
                        cs.lineTo(pts.last().x, -pts.last().y)
                    }
                    cs.stroke()
                    cs.restoreGraphicsState()
                }
            }
            is TextMark -> {
                dict.setName(COSName.SUBTYPE, "FreeText")
                dict.setString(COSName.CONTENTS, m.text)
                dict.setString(COSName.DA, "%.3f %.3f %.3f rg /Helv %.1f Tf".format(java.util.Locale.US, r, g, bl, m.size * unit))
                dict.setItem(COSName.getPDFName("BS"), COSDictionary().apply {
                    setFloat(COSName.getPDFName("W"), 0f)
                })

                val pdFont = font.get()
                PDPageContentStream(doc, ap).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(cm)
                    cs.setNonStrokingColor(r, g, bl)
                    m.lines.forEachIndexed { i, line ->
                        val safe = font.sanitize(line)
                        if (safe.isNotEmpty()) {
                            cs.beginText()
                            cs.setFont(pdFont, m.size)
                            cs.newLineAtOffset(m.x + TextMark.TEXT_PAD, -m.baseline(i))
                            cs.showText(safe)
                            cs.endText()
                        }
                    }
                    cs.restoreGraphicsState()
                }
            }
        }

        val apDict = PDAppearanceDictionary()
        apDict.setNormalAppearance(ap)
        dict.setItem(COSName.AP, apDict.cosObject)

        val annot = PDAnnotation.createAnnotation(dict)
        annot.setRectangle(rect)
        annot.setPage(page)
        return annot
    }

    /** Embeds a Unicode system font for text notes, falling back to Helvetica. */
    private class AnnotFont(private val doc: PDDocument) {
        private var font: PDFont? = null
        private val cache = HashMap<Int, Boolean>()

        fun get(): PDFont {
            font?.let { return it }
            val candidates = listOf(
                "/system/fonts/Roboto-Regular.ttf",
                "/system/fonts/RobotoStatic-Regular.ttf",
                "/system/fonts/NotoSans-Regular.ttf",
                "/system/fonts/DroidSans.ttf",
            )
            for (path in candidates) {
                val f = File(path)
                if (!f.exists()) continue
                try {
                    val loaded = PDType0Font.load(doc, f)
                    loaded.encode("Ag") // make sure it really works
                    font = loaded
                    return loaded
                } catch (e: Throwable) {
                    Log.w(TAG, "Font $path unusable", e)
                }
            }
            return PDType1Font.HELVETICA.also { font = it }
        }

        /** Replaces characters the font can't encode with '?'. */
        fun sanitize(s: String): String {
            val f = get()
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val str = String(Character.toChars(cp))
                val ok = cache.getOrPut(cp) {
                    try { f.encode(str); true } catch (_: Throwable) { false }
                }
                sb.append(if (ok) str else if (cp == '\t'.code) " " else "?")
                i += Character.charCount(cp)
            }
            return sb.toString()
        }
    }
}

/** How a page's displayed (rotated, cropped) area maps onto PDF user space. */
class PageGeometry(
    private val llx: Float, private val lly: Float,
    private val w: Float, private val h: Float,
    private val rotation: Int,
) {
    val dispW: Float get() = if (rotation % 180 == 0) w else h
    val dispH: Float get() = if (rotation % 180 == 0) h else w

    /** Display point (top-left origin, y down, in points) to user space. */
    fun toUser(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
        90 -> Pair(llx + y, lly + x)
        180 -> Pair(llx + w - x, lly + y)
        270 -> Pair(llx + w - y, lly + h - x)
        else -> Pair(llx + x, lly + h - y)
    }
}

fun pageGeometry(page: PDPage): PageGeometry {
    val box = page.cropBox
    var rot = page.rotation % 360
    if (rot < 0) rot += 360
    rot = ((rot / 90f).roundToInt() * 90) % 360
    return PageGeometry(
        min(box.lowerLeftX, box.upperRightX), min(box.lowerLeftY, box.upperRightY),
        max(box.width, 1f), max(box.height, 1f), rot,
    )
}

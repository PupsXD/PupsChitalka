package com.ozvuchka.app.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.data.ParagraphStyle
import com.ozvuchka.app.importer.pdf.PdfBookLayout
import com.ozvuchka.app.importer.pdf.PdfChapter
import com.ozvuchka.app.importer.pdf.PdfGlyph
import com.ozvuchka.app.importer.pdf.PdfItemKind
import com.ozvuchka.app.importer.pdf.PdfOutlineEntry
import com.ozvuchka.app.importer.pdf.PdfPageInput
import com.ozvuchka.app.importer.pdf.PdfPageLayout
import com.ozvuchka.app.importer.pdf.PdfRect
import com.ozvuchka.app.importer.pdf.PdfShape
import com.ozvuchka.app.importer.pdf.ShapeKind
import com.ozvuchka.app.importer.pdf.analyzePage
import com.ozvuchka.app.importer.pdf.isBoldName
import com.ozvuchka.app.importer.pdf.isItalicName
import com.ozvuchka.app.importer.pdf.pagesToRecognize
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Vector
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private const val MAX_PDF_PAGES = 3_000

/**
 * Reads a PDF as a book: the text in reading order with headings, notes and captions, pictures
 * and tables cut out of the pages, chapters from the bookmarks, and OCR for scanned pages.
 */
internal suspend fun readPdf(
    context: Context,
    file: File,
    fallbackTitle: String,
    images: ImageSink,
    onProgress: (String) -> Unit,
): Book {
    PDFBoxResourceLoader.init(context.applicationContext)
    val importContext = currentCoroutineContext()
    PDDocument.load(file).use { document ->
        val pageCount = document.numberOfPages
        if (pageCount == 0) throw IOException("PDF не содержит страниц")
        if (pageCount > MAX_PDF_PAGES) throw IOException("PDF содержит больше 3000 страниц")
        if (!document.currentAccessPermission.canExtractContent()) {
            throw IOException("В PDF запрещено извлечение текста")
        }
        val title = document.documentInformation.title?.let(::normalizeText)
            ?.takeIf(String::isNotBlank) ?: fallbackTitle
        val author = document.documentInformation.author?.let(::normalizeText).orEmpty()
        val outline = runCatching { readOutline(document) }.getOrDefault(emptyList())

        // One pass of the text extractor over the whole book; each page is analyzed as it is read.
        val layouts = ArrayList<PdfPageLayout>(pageCount)
        var extractionErrors = 0
        val reader = PageReader { page, pageIndex, glyphs ->
            if (!importContext.isActive) throw CancellationException("Импорт отменён")
            val number = pageIndex + 1
            if (number == 1 || number % 5 == 0 || number == pageCount) onProgress("PDF: страница $number из $pageCount")
            val shapes = ShapeCollector(page)
            if (page.rotation % 360 == 0) {
                runCatching { shapes.processPage(page) }.onFailure { if (it is CancellationException) throw it }
            }
            val crop = page.cropBox
            layouts += analyzePage(PdfPageInput(pageIndex, crop.width, crop.height, glyphs, shapes.shapes, shapes.unreadableGlyphs))
        }
        try {
            reader.getText(document)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A broken page stops the extractor; the pages after it are read one by one.
            extractionErrors++
            for (index in layouts.size until pageCount) {
                if (!importContext.isActive) throw CancellationException("Импорт отменён")
                val single = PageReader(reader.onPage).apply {
                    startPage = index + 1
                    endPage = index + 1
                }
                val before = layouts.size
                runCatching { single.getText(document) }.onFailure { if (it is CancellationException) throw it }
                if (layouts.size == before) {
                    extractionErrors++
                    val crop = document.getPage(index).cropBox
                    layouts += analyzePage(PdfPageInput(index, crop.width, crop.height, emptyList(), emptyList()))
                }
            }
        }

        // OCR only where there is no usable text layer: a scanned book, or text in fonts without letters.
        var ocrPages = 0
        var ocrMissing = false
        val toRecognize = pagesToRecognize(layouts)
        if (toRecognize.isNotEmpty()) {
            val engine = runCatching { createOcr(context) }.getOrNull()
            val raster = runCatching { PageRasterizer(file) }.getOrNull()
            if (engine == null || raster == null) {
                ocrMissing = true
                engine?.recycle()
                raster?.close()
            } else {
                try {
                    raster.use {
                        for ((done, page) in toRecognize.withIndex()) {
                            if (!importContext.isActive) throw CancellationException("Импорт отменён")
                            onProgress("OCR: страница ${page.index + 1} (${done + 1} из ${toRecognize.size})")
                            val text = runCatching { recognize(raster, page.index, engine) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrDefault("")
                            val paragraphs = plainParagraphs(
                                text.lineSequence().filterNot { it.trim().matches(Regex("\\d{1,4}")) }.joinToString("\n"),
                            )
                            if (paragraphs.isNotEmpty()) {
                                page.ocrParagraphs = paragraphs
                                ocrPages++
                            }
                        }
                    }
                } finally {
                    engine.recycle()
                }
            }
        }

        onProgress("Раскладываем текст по главам…")
        val chapters = PdfBookLayout(layouts, outline, title).chapters()
        if (chapters.isEmpty()) {
            throw IOException("Из PDF не удалось получить текст; проверьте качество скана и языковые данные OCR")
        }

        // Pictures and tables are cut out of their pages.
        val figures = chapters.sumOf { chapter -> chapter.items.count { it.kind == PdfItemKind.FIGURE } }
        var drawn = 0
        var failedPictures = 0
        val bookChapters = runCatching { PageRasterizer(file) }.getOrNull().let { raster ->
            try {
                chapters.map { chapter ->
                    toChapter(chapter) { page, region ->
                        if (!importContext.isActive) throw CancellationException("Импорт отменён")
                        drawn++
                        if (drawn == 1 || drawn % 10 == 0 || drawn == figures) onProgress("Иллюстрации: $drawn из $figures")
                        val style = raster?.let { renderPicture(it, page, region, document.getPage(page).rotation, images) }
                        if (style == null) failedPictures++
                        style
                    }
                }
            } finally {
                raster?.close()
            }
        }.filter { it.paragraphs.isNotEmpty() }
        if (bookChapters.isEmpty()) throw IOException("Из PDF не удалось получить текст")

        val warnings = buildList {
            if (ocrPages > 0) add("Распознавание OCR использовано на $ocrPages страницах; возможны ошибки в словах и знаках")
            if (ocrMissing) add("Страницы без текстового слоя не распознаны: OCR недоступен")
            if (extractionErrors > 0) add("Часть страниц PDF прочитана с ошибками ($extractionErrors)")
            if (failedPictures > 0) add("Не удалось сохранить иллюстраций: $failedPictures")
        }
        return Book(title = title, author = author, format = "PDF", chapters = bookChapters, warnings = warnings)
    }
}

/** A PDF chapter as book paragraphs; [picture] cuts a region of a page out as a picture. */
private inline fun toChapter(chapter: PdfChapter, picture: (Int, PdfRect) -> ParagraphStyle?): Chapter {
    val paragraphs = ArrayList<String>(chapter.items.size)
    val styles = HashMap<Int, ParagraphStyle>()
    for (item in chapter.items) {
        val kind = when (item.kind) {
            PdfItemKind.BODY -> null
            PdfItemKind.HEADING -> ParagraphKind.HEADING
            PdfItemKind.SUBHEADING -> ParagraphKind.SUBHEADING
            PdfItemKind.NOTE -> ParagraphKind.NOTE
            PdfItemKind.CAPTION -> ParagraphKind.CAPTION
            PdfItemKind.FIGURE -> {
                val region = item.region ?: continue
                val style = picture(item.page, region) ?: continue
                styles[paragraphs.size] = style
                paragraphs += ""
                continue
            }
        }
        val text = normalizeText(item.text)
        if (text.isEmpty()) continue
        if (kind != null) styles[paragraphs.size] = ParagraphStyle(kind)
        paragraphs += text
    }
    return Chapter(chapter.title, paragraphs, styles = styles)
}

// ---------------------------------------------------------------- reading pages

/** Hands over the glyphs of each page as the text extractor finishes it. */
private class PageReader(val onPage: (PDPage, Int, List<PdfGlyph>) -> Unit) : PDFTextStripper() {
    private val invisible = IdentityHashMap<TextPosition, Boolean>()

    override fun processTextPosition(text: TextPosition) {
        val mode = graphicsState.textState.renderingMode
        if (mode == RenderingMode.NEITHER || mode == RenderingMode.NEITHER_CLIP) invisible[text] = true
        super.processTextPosition(text)
    }

    override fun writePage() {
        val glyphs = ArrayList<PdfGlyph>()
        for (article in charactersByArticle) {
            for (position in article) glyphOf(position)?.let(glyphs::add)
        }
        invisible.clear()
        onPage(currentPage, currentPageNo - 1, glyphs)
    }

    private fun glyphOf(position: TextPosition): PdfGlyph? {
        // Vertical and rotated text (tabs in the margin, spine labels) is not part of the reading.
        if (abs(position.dir) > 0.5f) return null
        val text = position.unicode ?: return null
        val font: PDFont? = position.font
        val name = font?.name.orEmpty().substringAfter('+')
        val descriptor = runCatching { font?.fontDescriptor }.getOrNull()
        val size = abs(position.textMatrix.scalingFactorY).takeIf { it > 0.5f } ?: position.fontSizeInPt
        return PdfGlyph(
            text = text,
            x = position.xDirAdj,
            baseline = position.yDirAdj,
            width = position.widthDirAdj,
            size = size,
            font = name,
            bold = isBoldName(name) || descriptor?.isForceBold == true || (descriptor?.fontWeight ?: 0f) >= 600f,
            italic = isItalicName(name) || descriptor?.isItalic == true,
            invisible = invisible[position] == true,
        )
    }
}

/** Bounds of everything drawn that is not readable text, clipped to what is visible. */
private class ShapeCollector(page: PDPage) : PDFGraphicsStreamEngine(page) {
    private val crop = page.cropBox
    val shapes = ArrayList<PdfShape>()

    /** Glyphs with no letters behind them, like labels set in a font without a Unicode map. */
    var unreadableGlyphs = 0
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var current = PointF()
    private var clip: PdfRect? = null
    private val clips = ArrayDeque<PdfRect?>()
    private val readable = HashMap<PDFont, HashMap<Int, Boolean>>()

    private fun extend(x: Float, y: Float) {
        minX = min(minX, x)
        minY = min(minY, y)
        maxX = max(maxX, x)
        maxY = max(maxY, y)
    }

    private fun reset() {
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
    }

    /** PDF space (y up, from the media box origin) to the visible page (y down, from its corner). */
    private fun pageRect(x0: Float, y0: Float, x1: Float, y1: Float) =
        PdfRect(x0 - crop.lowerLeftX, crop.upperRightY - y1, x1 - crop.lowerLeftX, crop.upperRightY - y0)

    private fun currentPathRect(): PdfRect? = if (minX > maxX) null else pageRect(minX, minY, maxX, maxY)

    private fun add(rect: PdfRect?, kind: ShapeKind) {
        var box = rect ?: return
        clip?.let { box = box.intersect(it) }
        if (box.right < box.left || box.bottom < box.top) return
        if (shapes.size < 20_000) shapes += PdfShape(box, kind)
    }

    /** White paint on white paper is invisible: an eraser, not a picture. */
    private fun paints(stroke: Boolean): Boolean = runCatching {
        val rgb = (if (stroke) graphicsState.strokingColor else graphicsState.nonStrokingColor).toRGB()
        !((rgb shr 16) and 0xFF > 245 && (rgb shr 8) and 0xFF > 245 && rgb and 0xFF > 245)
    }.getOrDefault(true)

    override fun saveGraphicsState() {
        super.saveGraphicsState()
        clips.addLast(clip)
    }

    override fun restoreGraphicsState() {
        super.restoreGraphicsState()
        if (clips.isNotEmpty()) clip = clips.removeLast()
    }

    override fun showForm(form: PDFormXObject) {
        val saved = clip
        super.showForm(form)
        clip = saved
    }

    override fun showTransparencyGroup(form: PDTransparencyGroup) {
        val saved = clip
        super.showTransparencyGroup(form)
        clip = saved
    }

    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
        for (p in arrayOf(p0, p1, p2, p3)) extend(p.x, p.y)
        current = PointF(p0.x, p0.y)
    }

    override fun drawImage(pdImage: PDImage) {
        val m = graphicsState.currentTransformationMatrix
        val corners = arrayOf(m.transformPoint(0f, 0f), m.transformPoint(1f, 0f), m.transformPoint(0f, 1f), m.transformPoint(1f, 1f))
        add(pageRect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y }), ShapeKind.IMAGE)
    }

    override fun clip(fillType: Path.FillType) {
        val rect = currentPathRect() ?: return
        clip = clip?.intersect(rect) ?: rect
    }

    override fun moveTo(x: Float, y: Float) {
        extend(x, y)
        current = PointF(x, y)
    }

    override fun lineTo(x: Float, y: Float) {
        extend(x, y)
        current = PointF(x, y)
    }

    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        extend(x1, y1)
        extend(x2, y2)
        extend(x3, y3)
        current = PointF(x3, y3)
    }

    override fun getCurrentPoint(): PointF = current

    override fun closePath() = Unit

    override fun endPath() = reset()

    override fun strokePath() {
        if (paints(stroke = true)) add(currentPathRect(), ShapeKind.PATH)
        reset()
    }

    override fun fillPath(fillType: Path.FillType) {
        if (paints(stroke = false)) add(currentPathRect(), ShapeKind.PATH)
        reset()
    }

    override fun fillAndStrokePath(fillType: Path.FillType) {
        if (paints(stroke = false) || paints(stroke = true)) add(currentPathRect(), ShapeKind.PATH)
        reset()
    }

    override fun shadingFill(shadingName: COSName) = add(clip, ShapeKind.PATH)

    // Text is read by the text extractor; here only glyphs it cannot read count, as ink.
    override fun showFontGlyph(textRenderingMatrix: com.tom_roush.pdfbox.util.Matrix, font: PDFont, code: Int, displacement: Vector) =
        glyphInk(textRenderingMatrix, font, code, displacement)

    override fun showType3Glyph(textRenderingMatrix: com.tom_roush.pdfbox.util.Matrix, font: PDType3Font, code: Int, displacement: Vector) =
        glyphInk(textRenderingMatrix, font, code, displacement)

    private fun glyphInk(matrix: com.tom_roush.pdfbox.util.Matrix, font: PDFont, code: Int, displacement: Vector) {
        val known = readable.getOrPut(font) { HashMap() }.getOrPut(code) {
            val unicode = runCatching { font.toUnicode(code) }.getOrNull()
            unicode != null && unicode.any { it >= ' ' && it !in ''..'' && it != '�' }
        }
        if (known) return
        unreadableGlyphs++
        val a = matrix.transformPoint(0f, -0.2f)
        val b = matrix.transformPoint(displacement.x.coerceAtLeast(0.3f), 0.8f)
        add(pageRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)), ShapeKind.PATH)
    }
}

/** The bookmarks as chapter starts, in reading order, with the page and height they point to. */
private fun readOutline(document: PDDocument): List<PdfOutlineEntry> {
    val root = document.documentCatalog.documentOutline ?: return emptyList()
    val result = ArrayList<PdfOutlineEntry>()
    fun visit(node: PDOutlineNode, level: Int) {
        var item = node.firstChild
        var guard = 0
        while (item != null && guard++ < 10_000 && result.size < 5_000) {
            val target = runCatching { item.destination ?: (item.action as? PDActionGoTo)?.destination }.getOrNull()
            val destination = when (target) {
                is PDPageDestination -> target
                is PDNamedDestination -> runCatching { document.documentCatalog.findNamedDestinationPage(target) }.getOrNull()
                else -> null
            }
            val pageIndex = destination?.let { d ->
                d.retrievePageNumber().takeIf { it >= 0 } ?: d.page?.let { document.pages.indexOf(it) }
            } ?: -1
            if (pageIndex >= 0) {
                val page = document.getPage(pageIndex)
                val top = (destination as? PDPageXYZDestination)?.top?.takeIf { it > 0 }
                    ?.let { page.cropBox.upperRightY - it } ?: 0f
                result += PdfOutlineEntry(item.title.orEmpty(), level, pageIndex, top.coerceAtLeast(0f))
            }
            if (level < 6) visit(item, level + 1)
            item = item.nextSibling
        }
    }
    visit(root, 1)
    return result
}

// ---------------------------------------------------------------- pictures and OCR

/** Draws pages and parts of pages with the system's PDF renderer. */
private class PageRasterizer(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (error: Exception) {
        descriptor.close()
        throw error
    }

    /**
     * Draws [region] of a page (in points of the unrotated page, from its top-left corner; the whole
     * page when null) [scale] pixels per point, on white.
     */
    fun render(pageIndex: Int, region: PdfRect?, rotation: Int, scale: Float, maxPixels: Int): Bitmap {
        renderer.openPage(pageIndex).use { page ->
            val turned = ((rotation % 360) + 360) % 360
            // The renderer measures and draws the page turned as it is shown.
            val width = page.width.toFloat()
            val height = page.height.toFloat()
            val area = region?.let { rotate(it, turned, width, height) } ?: PdfRect(0f, 0f, width, height)
            var pixelScale = scale
            val pixels = area.width * area.height * pixelScale * pixelScale
            if (pixels > maxPixels) pixelScale *= kotlin.math.sqrt(maxPixels / pixels)
            val bitmap = Bitmap.createBitmap(
                (area.width * pixelScale).roundToInt().coerceIn(1, 8_000),
                (area.height * pixelScale).roundToInt().coerceIn(1, 8_000),
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(Color.WHITE)
            // Opaque: the picture is stored without an alpha channel, smaller.
            bitmap.setHasAlpha(false)
            val matrix = Matrix().apply {
                setScale(pixelScale, pixelScale)
                postTranslate(-area.left * pixelScale, -area.top * pixelScale)
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    /** A rectangle of the unrotated page where it lies on the page shown turned ([width] × [height]). */
    private fun rotate(rect: PdfRect, turned: Int, width: Float, height: Float): PdfRect = when (turned) {
        90 -> PdfRect(width - rect.bottom, rect.left, width - rect.top, rect.right)
        180 -> PdfRect(width - rect.right, height - rect.bottom, width - rect.left, height - rect.top)
        270 -> PdfRect(rect.top, height - rect.right, rect.bottom, height - rect.left)
        else -> rect
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }
}

/** Cuts a picture or a table out of its page and stores it with the book. */
private fun renderPicture(raster: PageRasterizer, page: Int, region: PdfRect, rotation: Int, images: ImageSink): ParagraphStyle? = try {
    // Sharp on a phone held upright: about a thousand pixels across a full-width picture.
    val scale = (1_100f / region.width.coerceAtLeast(1f)).coerceIn(1.5f, 3f)
    val bitmap = raster.render(page, region, rotation, scale, maxPixels = 3_000_000)
    try {
        val bytes = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bitmap.compress(format, 88, bytes)
        images.store(bytes.toByteArray())
    } finally {
        bitmap.recycle()
    }
} catch (error: Exception) {
    if (error is CancellationException) throw error
    null
} catch (_: OutOfMemoryError) {
    null
}

private fun recognize(raster: PageRasterizer, pageIndex: Int, engine: TessBaseAPI): String {
    // About 300 dpi, within twelve megapixels.
    val bitmap = raster.render(pageIndex, null, 0, scale = 300f / 72f, maxPixels = 12_000_000)
    return try {
        engine.setImage(bitmap)
        engine.getUTF8Text().orEmpty()
    } finally {
        engine.clear()
        bitmap.recycle()
    }
}

private fun createOcr(context: Context): TessBaseAPI? {
    val dataDirectory = File(context.filesDir, "ocr")
    val tessdata = File(dataDirectory, "tessdata")
    if (!tessdata.exists() && !tessdata.mkdirs()) return null
    for (language in listOf("rus", "eng")) {
        val name = "$language.traineddata"
        val destination = File(tessdata, name)
        if (!destination.exists() || destination.length() < 1_024) {
            val staging = File(tessdata, "$name.part")
            try {
                context.assets.open("tessdata/$name").use { source ->
                    staging.outputStream().use { target -> source.copyTo(target) }
                }
                if (!staging.renameTo(destination)) {
                    throw IOException("Не удалось подготовить OCR-модель $language")
                }
            } finally {
                staging.delete()
            }
        }
    }
    val engine = TessBaseAPI()
    if (!engine.init(dataDirectory.absolutePath, "rus+eng")) {
        engine.recycle()
        return null
    }
    engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
    return engine
}

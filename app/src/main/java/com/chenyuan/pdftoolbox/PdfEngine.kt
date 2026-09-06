package com.chenyuan.pdftoolbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.ByteArrayOutputStream

enum class WatermarkPosition { DIAGONAL, HEADER, FOOTER }

object PdfEngine {

    fun fileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx) ?: uri.lastPathSegment ?: "file"
            }
        return uri.lastPathSegment ?: "file"
    }

    fun fileSize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getLong(idx)
        }
        return 0L
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun merge(context: Context, sources: List<Uri>, output: Uri) {
        require(sources.size >= 2) { "Select at least two PDF files" }
        val resolver = context.contentResolver
        val merger = PDFMergerUtility()
        sources.forEach { uri ->
            val ins = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")
            merger.addSource(ins)
        }
        val outs = resolver.openOutputStream(output) ?: throw IllegalStateException("Cannot write output")
        merger.destinationStream = outs
        // Mixed mode spills to temp files for large PDFs instead of risking OOM.
        merger.mergeDocuments(MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
        outs.close()
    }

    fun pageCount(context: Context, source: Uri): Int {
        context.contentResolver.openInputStream(source)!!.use { ins ->
            PDDocument.load(ins).use { doc -> return doc.numberOfPages }
        }
    }

    /** Renders up to [maxPages] page thumbnails at the given width (white background). */
    fun renderPageThumbnails(context: Context, source: Uri, maxPages: Int, widthPx: Int = 320): List<Bitmap> {
        context.contentResolver.openFileDescriptor(source, "r")!!.use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                val count = minOf(renderer.pageCount, maxPages)
                return (0 until count).map { index ->
                    val page = renderer.openPage(index)
                    try {
                        val height = (widthPx * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        }
    }

    /** Parses "1-3,5" (1-based, order-insensitive) into 0-based page indices. */
    fun parsePageIndices(input: String, pageCount: Int): List<Int> {
        val pages = sortedSetOf<Int>()
        input.split(",").forEach { part ->
            val p = part.trim()
            if (p.isEmpty()) return@forEach
            val range = Regex("(\\d+)\\s*-\\s*(\\d+)").matchEntire(p)
            if (range != null) {
                var a = range.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("\"$p\" is not a valid range")
                var b = range.groupValues[2].toIntOrNull() ?: throw IllegalArgumentException("\"$p\" is not a valid range")
                if (a > b) { val t = a; a = b; b = t }
                require(a >= 1) { "Page numbers start at 1" }
                for (i in a..minOf(b, pageCount)) pages.add(i - 1)
            } else {
                val n = p.toIntOrNull() ?: throw IllegalArgumentException("\"$p\" is not a page number")
                require(n in 1..pageCount) { "Page $n is out of range (1-$pageCount)" }
                pages.add(n - 1)
            }
        }
        require(pages.isNotEmpty()) { "No pages selected" }
        return pages.toList()
    }

    fun extractPages(context: Context, source: Uri, output: Uri, pageIndices: List<Int>) {
        val resolver = context.contentResolver
        resolver.openInputStream(source)!!.use { ins ->
            PDDocument.load(ins).use { src ->
                val dest = PDDocument()
                pageIndices.forEach { idx -> dest.importPage(src.getPage(idx)) }
                dest.documentInformation.title = "Extracted pages"
                resolver.openOutputStream(output)!!.use { outs -> dest.save(outs) }
                dest.close()
            }
        }
    }

    fun imagesToPdf(context: Context, images: List<Uri>, output: Uri) {
        require(images.isNotEmpty()) { "Select at least one image" }
        val resolver = context.contentResolver
        val doc = PdfDocument()
        try {
            images.forEachIndexed { index, uri ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)!!.use { ins ->
                    BitmapFactory.decodeStream(ins, null, bounds)
                }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot decode image: ${fileName(context, uri)}" }

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 2000 && bounds.outHeight / (sample * 2) >= 2000) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = resolver.openInputStream(uri)!!.use { ins ->
                    BitmapFactory.decodeStream(ins, null, opts)
                } ?: throw IllegalStateException("Cannot decode image: ${fileName(context, uri)}")

                val page = doc.startPage(
                    PdfDocument.PageInfo.Builder(bounds.outWidth, bounds.outHeight, index + 1).create()
                )
                page.canvas.drawBitmap(
                    bitmap, null,
                    android.graphics.RectF(0f, 0f, bounds.outWidth.toFloat(), bounds.outHeight.toFloat()),
                    null
                )
                doc.finishPage(page)
                bitmap.recycle()
            }
            resolver.openOutputStream(output)!!.use { outs -> doc.writeTo(outs) }
        } finally {
            doc.close()
        }
    }

    /**
     * Shrinks a PDF by rasterizing pages at [scale]x and re-encoding them as JPEG
     * at [jpegQuality]. Text becomes part of the image (not selectable), which is
     * the trade-off for the size reduction. Returns (originalSize, newSize).
     */
    fun compressPdf(
        context: Context, source: Uri, output: Uri, scale: Float, jpegQuality: Int
    ): Pair<Long, Long> {
        val originalSize = fileSize(context, source)
        val outDoc = PDDocument()
        try {
            context.contentResolver.openFileDescriptor(source, "r")!!.use { pfd ->
                val renderer = PdfRenderer(pfd)
                try {
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val pageW = page.width.toFloat()
                        val pageH = page.height.toFloat()
                        val bmp: Bitmap = try {
                            val w = (pageW * scale).toInt().coerceAtLeast(1)
                            val h = (pageH * scale).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            }
                        } finally {
                            page.close()
                        }
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                        bmp.recycle()
                        val img = PDImageXObject.createFromByteArray(outDoc, baos.toByteArray(), "p$i.jpg")
                        val pdPage = PDPage(PDRectangle(pageW, pageH))
                        outDoc.addPage(pdPage)
                        val cs = PDPageContentStream(outDoc, pdPage)
                        cs.drawImage(img, 0f, 0f, pageW, pageH)
                        cs.close()
                    }
                } finally {
                    renderer.close()
                }
            }
            context.contentResolver.openOutputStream(output)!!.use { o -> outDoc.save(o) }
        } finally {
            outDoc.close()
        }
        return originalSize to fileSize(context, output)
    }

    /** Password-protects a PDF. [userPassword] is required to open the file. */
    fun protectPdf(
        context: Context, source: Uri, output: Uri, userPassword: String, ownerPassword: String
    ) {
        context.contentResolver.openInputStream(source)!!.use { ins ->
            PDDocument.load(ins).use { doc ->
                val permissions = AccessPermission().apply {
                    setCanPrint(true)
                    setCanExtractContent(false)
                }
                val policy = StandardProtectionPolicy(
                    ownerPassword.ifBlank { userPassword },
                    userPassword,
                    permissions
                )
                policy.encryptionKeyLength = 128
                doc.protect(policy)
                context.contentResolver.openOutputStream(output)!!.use { o -> doc.save(o) }
            }
        }
    }

    /** Stamps [text] on every page. Rendered by Android (supports any language). */
    fun addWatermark(
        context: Context, source: Uri, output: Uri, text: String, position: WatermarkPosition
    ) {
        context.contentResolver.openInputStream(source)!!.use { ins ->
            PDDocument.load(ins).use { doc ->
                for (page in doc.pages) {
                    val w = page.mediaBox.width
                    val h = page.mediaBox.height
                    val scale = 2
                    val bmp = Bitmap.createBitmap(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bmp)
                    canvas.scale(scale.toFloat(), scale.toFloat())
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(70, 110, 110, 125)
                        textAlign = Paint.Align.CENTER
                        textSize = if (text.length > 20) w * 0.05f else w * 0.12f
                    }
                    when (position) {
                        WatermarkPosition.DIAGONAL -> {
                            canvas.rotate(-32f, w / 2f, h / 2f)
                            canvas.drawText(text, w / 2f, h / 2f, paint)
                        }
                        WatermarkPosition.HEADER -> canvas.drawText(text, w / 2f, h * 0.06f + paint.textSize, paint)
                        WatermarkPosition.FOOTER -> canvas.drawText(text, w / 2f, h * 0.96f, paint)
                    }
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    bmp.recycle()
                    val img = PDImageXObject.createFromByteArray(doc, baos.toByteArray(), "wm.png")
                    val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                    cs.drawImage(img, 0f, 0f, w, h)
                    cs.close()
                }
                context.contentResolver.openOutputStream(output)!!.use { o -> doc.save(o) }
            }
        }
    }
}

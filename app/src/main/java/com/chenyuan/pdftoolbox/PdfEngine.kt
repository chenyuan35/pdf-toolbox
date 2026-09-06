package com.chenyuan.pdftoolbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting

object PdfEngine {

    fun fileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx) ?: uri.lastPathSegment ?: "file"
            }
        return uri.lastPathSegment ?: "file"
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
}

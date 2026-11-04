package app.attune.attunenative.focus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.Semaphore
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Call from the bridge's background AsyncFunction, never the UI thread. No save/system action.
 * OCR uses the bundled Latin model; text/bitmaps are transient and never written to logs.
 */
class AndroidTimetableImporter(context: Context) {
  private val context = context.applicationContext

  fun importUri(uri: Uri): Map<String, Any?> {
    var name = "Timetable"
    var source = "unknown"
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("content", "file") ||
      (uri.scheme.equals("file", true) && !uri.authority.isNullOrEmpty())) {
      return response(TimetableParser.result(emptyList(), listOf("Choose a local file or content URI; network URLs are not accepted.")), name, source)
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return response(TimetableParser.result(emptyList(), listOf("Import must run on a background worker. Nothing was read or saved.")), name, source)
    }
    if (!importGate.tryAcquire()) {
      return response(TimetableParser.result(emptyList(), listOf("Another timetable is being read. Wait for it to finish before importing another file.")), name, source)
    }
    return try {
      name = sourceName(uri)
      val mime = context.contentResolver.getType(uri)?.substringBefore(';')?.lowercase(Locale.ROOT)
      source = when {
        mime == "application/pdf" || name.endsWith(".pdf", true) -> "pdf"
        mime == "text/calendar" || name.endsWith(".ics", true) -> "ics"
        mime in setOf("text/csv", "application/csv", "application/vnd.ms-excel") || name.endsWith(".csv", true) -> "csv"
        mime?.startsWith("image/") == true || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp") -> "image"
        else -> "unknown"
      }
      val parsed = when (source) {
        "csv", "ics" -> {
          val bytes = open(uri).use { readBounded(it, MAX_TEXT_BYTES) }
          val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
          if (source == "csv") TimetableParser.parseCsv(text) else TimetableParser.parseIcs(text)
        }
        "image" -> {
          val bytes = open(uri).use { readBounded(it, MAX_FILE_BYTES) }
          val bitmap = decodeSampled(bytes)
          try {
            val rotation = exifRotation(bytes)
            recognize(bitmap, rotation)
          } finally { bitmap.recycle() }
        }
        "pdf" -> readPdf(uri)
        else -> TimetableParser.result(emptyList(), listOf("Supported uploads are photos/screenshots, PDF, UTF-8 CSV and .ics calendars."))
      }
      response(parsed, name, source)
    } catch (_: SecurityException) {
      response(TimetableParser.result(emptyList(), listOf("File permission was denied, or the PDF is password-protected. Select an accessible, unlocked local file.")), name, source)
    } catch (_: ImportLimitException) {
      response(TimetableParser.result(emptyList(), listOf("File exceeds the local import limit (25 MiB; text 512 KiB). Use a smaller file.")), name, source)
    } catch (_: OutOfMemoryError) {
      response(TimetableParser.result(emptyList(), listOf("Not enough memory to read this file safely. Crop or reduce it and try again.")), name, source)
    } catch (_: Exception) {
      // Never return exception/provider messages: they may contain raw document text or paths.
      response(TimetableParser.result(emptyList(), listOf("This file could not be read locally. It may be corrupt, unavailable, unsupported or unreadable by OCR.")), name, source)
    } finally { importGate.release() }
  }

  private fun response(parsed: TimetableParseResult, name: String, source: String): Map<String, Any?> = mapOf(
    "entries" to parsed.entries.map { it.toMap() }, "warnings" to parsed.warnings,
    "sourceName" to name, "source" to source
  )

  private fun sourceName(uri: Uri): String {
    var name: String? = null
    if (uri.scheme.equals("content", true)) {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) name = cursor.getString(column)
      }
    } else name = uri.lastPathSegment
    return name?.substringAfterLast('/')?.substringAfterLast('\\')?.filterNot { it.isISOControl() }?.take(160)?.takeIf { it.isNotBlank() } ?: "Timetable"
  }

  private fun open(uri: Uri): InputStream = context.contentResolver.openInputStream(uri) ?: error("Unavailable input")

  private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
      val count = input.read(buffer)
      if (count == -1) break
      if (count == 0) throw java.io.IOException("Invalid input stream")
      if (count > limit - total) throw ImportLimitException()
      output.write(buffer, 0, count)
      total += count
    }
    return output.toByteArray()
  }

  private fun decodeSampled(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while ((bounds.outWidth.toLong() + sample - 1) / sample * ((bounds.outHeight.toLong() + sample - 1) / sample) > MAX_PIXELS ||
      (maxOf(bounds.outWidth, bounds.outHeight).toLong() + sample - 1) / sample > MAX_EDGE) {
      require(sample <= 1 shl 28)
      sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Cannot decode image")
    if (decoded.width.toLong() * decoded.height > MAX_PIXELS || maxOf(decoded.width, decoded.height) > MAX_EDGE) {
      decoded.recycle()
      error("Image exceeds sampled bounds")
    }
    // BitmapFactory ignores EXIF mirroring. Honor it in a bounded bitmap, not by changing OCR text.
    val orientation = exifOrientation(bytes)
    if (orientation in setOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL,
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)) {
      val matrix = Matrix().apply { setScale(-1f, 1f) }
      return try { Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) }
      finally { decoded.recycle() }
    }
    return decoded
  }

  private fun exifOrientation(bytes: ByteArray): Int = try {
    ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
  } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }

  private fun exifRotation(bytes: ByteArray): Int = when (exifOrientation(bytes)) {
    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE -> 90
    ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE -> 270
    else -> 0
  }

  private fun recognize(bitmap: Bitmap, rotation: Int = 0): TimetableParseResult {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
      // This method is background-only. Wait for completion before closing/recycling its input.
      val recognized = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotation)))
      val cells = recognized.textBlocks.flatMap { it.lines }.flatMap { line -> ocrCells(line) }.take(1501)
      val positions = TimetableParser.parsePositions(cells)
      if (positions.entries.isNotEmpty()) return positions
      val explicit = TimetableParser.parseText(recognized.text)
      return TimetableParser.result(explicit.entries, positions.warnings + explicit.warnings +
        "Only explicit day/time lines were used when table geometry was unclear. OCR may omit classes; check against the original.")
    } finally { recognizer.close() }
  }

  private fun ocrCells(line: Text.Line): List<TimetableTextCell> {
    fun cell(text: String, bounds: android.graphics.Rect) = TimetableTextCell(text, bounds.left, bounds.top, bounds.right, bounds.bottom)
    val box = line.boundingBox ?: return emptyList()
    if (TimetableParser.parseDay(line.text) != null || TimetableParser.parseRange(line.text) != null) return listOf(cell(line.text, box))
    val elements = line.elements
    val result = mutableListOf<TimetableTextCell>()
    var index = 0
    // OCR sometimes places several header cells in one line. Recover only exact weekday/range tokens.
    while (index < elements.size) {
      val element = elements[index]
      val day = TimetableParser.parseDay(element.text)
      if (day != null && element.text.any { it.isLetter() }) {
        element.boundingBox?.let { result += cell(element.text, it) }
        index++
        continue
      }
      var consumed = 0
      for (count in 1..min(7, elements.size - index)) {
        val span = elements.subList(index, index + count)
        val text = span.joinToString(" ") { it.text }
        if (TimetableParser.parseRange(text) != null) {
          val bounds = span.mapNotNull { it.boundingBox }
          if (bounds.size == span.size) result += TimetableTextCell(text, bounds.minOf { it.left }, bounds.minOf { it.top }, bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
          consumed = count
          break
        }
      }
      if (consumed > 0) index += consumed else {
        // Keep adjacent title words together unless a large horizontal gap signals another cell.
        val start = index++
        while (index < elements.size) {
          val previous = elements[index - 1].boundingBox ?: break
          val next = elements[index].boundingBox ?: break
          if (TimetableParser.parseDay(elements[index].text) != null || next.left - previous.right > maxOf(previous.height(), next.height()) * 2) break
          index++
        }
        val span = elements.subList(start, index)
        val bounds = span.mapNotNull { it.boundingBox }
        if (bounds.size == span.size) result += TimetableTextCell(span.joinToString(" ") { it.text }, bounds.minOf { it.left }, bounds.minOf { it.top }, bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
      }
    }
    return result.ifEmpty { listOf(cell(line.text, box)) }
  }

  private fun readPdf(uri: Uri): TimetableParseResult {
    val file = File.createTempFile("attune-timetable-", ".pdf", context.cacheDir)
    val warnings = mutableListOf<String>()
    val entries = mutableListOf<TimetableEntry>()
    var descriptor: ParcelFileDescriptor? = null
    try {
      val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
      descriptor = opened
      // Unlink the empty file BEFORE copying. Even process death during copy leaves no raw path.
      if (!file.delete()) throw java.io.IOException("Cannot remove transient copy")
      open(uri).use { input ->
        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(opened.fileDescriptor)).use { output ->
          val buffer = ByteArray(8192)
          var total = 0
          while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) throw java.io.IOException("Invalid input stream")
            if (count > MAX_FILE_BYTES - total) throw ImportLimitException()
            output.write(buffer, 0, count)
            total += count
          }
        }
      }
      val renderer = PdfRenderer(opened)
      descriptor = null // PdfRenderer now owns the original descriptor.
      try {
        if (renderer.pageCount > MAX_PDF_PAGES) warnings += "Only the first $MAX_PDF_PAGES PDF pages were read; upload remaining pages separately."
        for (pageIndex in 0 until min(renderer.pageCount, MAX_PDF_PAGES)) {
          val page = renderer.openPage(pageIndex)
          try {
            require(page.width > 0 && page.height > 0)
            val scale = min(MAX_EDGE.toDouble() / maxOf(page.width, page.height), sqrt(MAX_PIXELS.toDouble() / (page.width.toDouble() * page.height)))
            val width = floor(page.width * scale).toInt().coerceAtLeast(1)
            val height = floor(page.height * scale).toInt().coerceAtLeast(1)
            require(width.toLong() * height <= MAX_PIXELS && maxOf(width, height) <= MAX_EDGE)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
              bitmap.eraseColor(Color.WHITE)
              page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              val parsed = recognize(bitmap)
              entries += parsed.entries
              warnings += parsed.warnings.map { if (it == TimetableParser.REVIEW_WARNING) it else "Page ${pageIndex + 1}: $it" }
            } finally { bitmap.recycle() }
          } finally { page.close() }
        }
      } finally { renderer.close() }
    } finally {
      descriptor?.close()
      if (file.exists() && !file.delete()) throw java.io.IOException("Transient copy removal failed")
    }
    return TimetableParser.result(entries, warnings)
  }

  private class ImportLimitException : java.io.IOException()

  companion object {
    private val importGate = Semaphore(1)
    private const val MAX_FILE_BYTES = 25 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 512 * 1024
    private const val MAX_PIXELS = 3_000_000L
    private const val MAX_EDGE = 2400
    private const val MAX_PDF_PAGES = 6
  }
}

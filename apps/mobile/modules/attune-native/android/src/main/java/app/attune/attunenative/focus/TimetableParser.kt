package app.attune.attunenative.focus

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** Review candidates only. Date bounds are ISO local dates, inclusive, never inferred. */
data class TimetableEntry(
  val id: String,
  val dayOfWeek: Int,
  val startMinute: Int,
  val endMinute: Int,
  val title: String,
  val startDate: String? = null,
  val endDate: String? = null
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "id" to id, "dayOfWeek" to dayOfWeek, "startMinute" to startMinute,
    "endMinute" to endMinute, "title" to title, "startDate" to startDate, "endDate" to endDate
  )
}

data class TimetableParseResult(val entries: List<TimetableEntry>, val warnings: List<String>)

/** Pure, bounded parsing shared by OCR, document imports and reviewed-entry validation. */
object TimetableParser {
  const val MAX_ENTRIES = 200
  const val MAX_TEXT_CHARS = 262_144
  const val MAX_TITLE_CHARS = 160
  const val REVIEW_WARNING = "Review every extracted day, time, title and date before using this timetable."

  private val days = listOf(
    setOf("mon", "monday", "mo"), setOf("tue", "tues", "tuesday", "tu"),
    setOf("wed", "wednesday", "we"), setOf("thu", "thur", "thurs", "thursday", "th"),
    setOf("fri", "friday", "fr"), setOf("sat", "saturday", "sa"), setOf("sun", "sunday", "su")
  )
  private const val CLOCK = "(?:[0-9]{1,2}:[0-9]{2}\\s*(?:[AaPp][Mm])?|[0-9]{1,2}\\s*[AaPp][Mm])"
  internal val rangePattern = Regex("(?<![\\w:])($CLOCK)\\s*(?:[-–—]|(?i:to))\\s*($CLOCK)(?![\\w:])")
  private val clockPattern = Regex("^([0-9]{1,2})(?::([0-9]{2}))?\\s*([AaPp][Mm])?$")

  fun parseDay(value: String): Int? {
    val normalized = value.trim().removeSuffix(".").lowercase(Locale.ROOT)
    normalized.toIntOrNull()?.let { return it.takeIf { day -> day in 1..7 } }
    return days.indexOfFirst { normalized in it }.takeIf { it >= 0 }?.plus(1)
  }

  /** No guessed AM/PM, missing minutes, decimal clocks, OCR substitutions or rollover. */
  fun parseTime(value: String, allowEndOfDay: Boolean = false): Int? {
    val match = clockPattern.matchEntire(value.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minuteText = match.groupValues[2]
    val suffix = match.groupValues[3].lowercase(Locale.ROOT)
    if (minuteText.isEmpty() && suffix.isEmpty()) return null
    val minute = if (minuteText.isEmpty()) 0 else minuteText.toIntOrNull() ?: return null
    if (minute !in 0..59) return null
    if (suffix.isNotEmpty()) {
      if (hour !in 1..12) return null
      return (hour % 12 + if (suffix == "pm") 12 else 0) * 60 + minute
    }
    if (allowEndOfDay && hour == 24 && minute == 0) return 1440
    return if (hour in 0..23) hour * 60 + minute else null
  }

  internal fun parseRange(value: String): Pair<Int, Int>? {
    val match = rangePattern.matchEntire(value.trim()) ?: return null
    // A suffix on only one endpoint is ambiguous (e.g. 09:00-10:00 PM).
    val first = match.groupValues[1]
    val second = match.groupValues[2]
    if (Regex("(?i)[ap]m$").containsMatchIn(first) != Regex("(?i)[ap]m$").containsMatchIn(second)) return null
    val start = parseTime(first) ?: return null
    val end = parseTime(second, allowEndOfDay = true) ?: return null
    return (start to end).takeIf { end > start && end - start <= 720 }
  }

  fun validate(entry: TimetableEntry): Boolean {
    if (entry.id.isBlank() || entry.id.length > 200 || entry.dayOfWeek !in 1..7 ||
      entry.startMinute !in 0..1439 || entry.endMinute !in 1..1440 ||
      entry.endMinute <= entry.startMinute || entry.endMinute - entry.startMinute > 720 ||
      entry.title.isBlank() || entry.title.length > MAX_TITLE_CHARS || entry.title.any { it.isISOControl() }) return false
    return try {
      val start = entry.startDate?.let { strictDate(it) ?: return false }
      val end = entry.endDate?.let { strictDate(it) ?: return false }
      if (start != null && end != null && end < start) return false
      // A date-only occurrence cannot claim a different weekday.
      if (start != null && start == end && start.dayOfWeek.value != entry.dayOfWeek) return false
      true
    } catch (_: IllegalArgumentException) {
      false
    }
  }

  internal fun strictDate(value: String): LocalDate? = try {
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) null else LocalDate.parse(value)
  } catch (_: java.time.DateTimeException) {
    null
  }

  internal fun candidate(day: Int, range: Pair<Int, Int>, title: String, startDate: String? = null, endDate: String? = null): TimetableEntry? {
    val cleanTitle = title.trim().replace(Regex("\\s+"), " ")
    val key = "$day|${range.first}|${range.second}|$cleanTitle|$startDate|$endDate"
    val entry = TimetableEntry(UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString(), day,
      range.first, range.second, cleanTitle, startDate, endDate)
    return entry.takeIf { validate(it) }
  }

  internal fun result(entries: List<TimetableEntry>, warnings: List<String>): TimetableParseResult {
    val valid = entries.filter { validate(it) }.distinctBy { listOf(it.dayOfWeek, it.startMinute, it.endMinute, it.title, it.startDate, it.endDate) }
    val notes = warnings.toMutableList()
    if (valid.size > MAX_ENTRIES) notes += "Only the first $MAX_ENTRIES entries were extracted; split this timetable into smaller files."
    if (valid.isEmpty()) notes += "No unambiguous timetable entries were found. Enter the schedule manually or upload a clearer file."
    return TimetableParseResult(valid.take(MAX_ENTRIES), notes.filter { it != REVIEW_WARNING }.distinct().take(49) + REVIEW_WARNING)
  }

  fun parseCsv(text: String): TimetableParseResult {
    if (text.length > MAX_TEXT_CHARS) return result(emptyList(), listOf("CSV exceeds the local text size limit."))
    val rows = csvRows(text.removePrefix("\uFEFF"))
      ?: return result(emptyList(), listOf("Malformed CSV quoting, too many fields, or oversized rows; nothing was imported."))
    val header = rows.firstOrNull()?.map { it.trim().lowercase(Locale.ROOT) } ?: emptyList()
    val required = listOf("day", "start", "end", "title")
    if (!header.containsAll(required) || header.distinct().size != header.size) {
      return result(emptyList(), listOf("CSV needs unique day,start,end,title column headers."))
    }
    val warnings = mutableListOf<String>()
    val entries = mutableListOf<TimetableEntry>()
    for ((index, row) in rows.drop(1).take(MAX_ENTRIES).withIndex()) {
      fun field(name: String): String? = header.indexOf(name).takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim() }
      val day = field("day")?.let { parseDay(it) }
      val start = field("start")?.let { parseTime(it) }
      val end = field("end")?.let { parseTime(it, true) }
      val entry = if (row.size == header.size && day != null && start != null && end != null) {
        candidate(day, start to end, field("title").orEmpty(), field("startdate")?.takeIf { it.isNotEmpty() }, field("enddate")?.takeIf { it.isNotEmpty() })
      } else null
      if (entry != null) entries += entry else warnings += "CSV row ${index + 2} has an invalid day, time, title or date and was skipped."
    }
    if (rows.size > MAX_ENTRIES + 1) warnings += "Only the first $MAX_ENTRIES CSV rows were checked; split larger files."
    return result(entries, warnings)
  }

  private fun csvRows(text: String): List<List<String>>? {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var closedQuote = false
    var index = 0
    fun pushField(): Boolean {
      row += field.toString()
      field.setLength(0)
      closedQuote = false
      return row.size <= 16
    }
    fun pushRow(): Boolean {
      if (!pushField()) return false
      if (row.any { it.isNotBlank() }) rows += row
      row = mutableListOf()
      return true
    }
    while (index < text.length) {
      val char = text[index]
      if (quoted) {
        if (char == '"') {
          if (index + 1 < text.length && text[index + 1] == '"') { field.append('"'); index++ }
          else { quoted = false; closedQuote = true }
        } else field.append(char)
      } else when (char) {
        '"' -> if (field.isEmpty() && !closedQuote) quoted = true else return null
        ',' -> if (!pushField()) return null
        '\r', '\n' -> {
          if (!pushRow()) return null
          if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
          if (rows.size > MAX_ENTRIES + 1) return rows
        }
        else -> { if (closedQuote) return null; field.append(char) }
      }
      if (field.length > 4096) return null
      index++
    }
    if (quoted) return null
    if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) if (!pushRow()) return null
    return rows
  }

  fun parseIcs(text: String, localZone: ZoneId = ZoneId.systemDefault()): TimetableParseResult = TimetableIcsParser.parse(text, localZone)

  /** Only self-contained lines; a bare day above a list of times is not enough evidence. */
  fun parseText(text: String): TimetableParseResult {
    if (text.length > MAX_TEXT_CHARS) return result(emptyList(), listOf("Recognized text exceeds the local size limit."))
    val entries = mutableListOf<TimetableEntry>()
    var skipped = 0
    val lines = text.lineSequence().take(2001).toList()
    for (line in lines.take(2000)) {
      if (line.length > 1024) { skipped++; continue }
      val clean = line.trim()
      if (Regex("(?i)\\b(cancelled|canceled|holiday|no class)\\b").containsMatchIn(clean)) { skipped++; continue }
      val dayToken = Regex("^([A-Za-z]+)\\.?\\s*[:|,]?\\s+").find(clean)
      val day = dayToken?.groupValues?.get(1)?.let { parseDay(it) }
      val ranges = rangePattern.findAll(clean).toList()
      if (day == null || ranges.size != 1) { if (ranges.isNotEmpty()) skipped++; continue }
      val rangeMatch = ranges.single()
      if (rangeMatch.range.first < dayToken!!.range.last) { skipped++; continue }
      val range = parseRange(rangeMatch.value)
      val title = clean.substring(dayToken.range.last + 1, rangeMatch.range.first) + " " + clean.substring(rangeMatch.range.last + 1)
      val entry = range?.let { candidate(day, it, title.trim(' ', '|', ',', ':', '-')) }
      if (entry == null) skipped++ else entries += entry
    }
    val warnings = mutableListOf<String>()
    if (skipped > 0) warnings += "$skipped ambiguous text lines were skipped; days and time ranges must be explicit on the same line."
    if (lines.size > 2000) warnings += "Only the first 2000 text lines were checked."
    return result(entries, warnings)
  }

  fun parsePositions(cells: List<TimetableTextCell>): TimetableParseResult = TimetableGridParser.parse(cells)
}

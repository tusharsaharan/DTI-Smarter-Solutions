package app.attune.attunenative.focus

import kotlin.math.abs

/** Pixel positions from one OCR page. Never mix coordinates from different pages. */
data class TimetableTextCell(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
  internal val x: Double get() = (left.toDouble() + right) / 2
  internal val y: Double get() = (top.toDouble() + bottom) / 2
  internal val height: Int get() = bottom - top
}

/** Flat table recognition, deliberately not a free-form timetable reconstruction model. */
internal object TimetableGridParser {
  private data class Day(val cell: TimetableTextCell, val day: Int)
  private data class Slot(val cell: TimetableTextCell, val range: Pair<Int, Int>)

  fun parse(input: List<TimetableTextCell>): TimetableParseResult {
    if (input.size > 1500) return TimetableParser.result(emptyList(), listOf("OCR table has too many regions; use a cropped page."))
    val cells = input.filter { it.text.isNotBlank() && it.text.length <= 1024 && it.left >= 0 && it.top >= 0 && it.right > it.left && it.bottom > it.top }
    val days = cells.mapNotNull { cell ->
      if (!Regex("[A-Za-z]+\\.?").matches(cell.text.trim())) null
      else TimetableParser.parseDay(cell.text)?.let { Day(cell, it) }
    }
    val slots = cells.mapNotNull { cell -> TimetableParser.parseRange(cell.text)?.let { Slot(cell, it) } }
    if (days.size < 2 || slots.size < 2 || days.size > 7 || slots.size > 24 || days.map { it.day }.distinct().size != days.size) {
      return TimetableParser.result(emptyList(), listOf("Table needs distinct weekday labels and at least two explicit time-range headers; ambiguous layouts were not guessed."))
    }
    val tolerance = cells.map { it.height }.sorted().let { it[it.size / 2] }.coerceAtLeast(4) * 1.5
    fun aligned(values: List<Int>) = values.maxOrNull()!! - values.minOrNull()!! <= tolerance
    val dayRows = aligned(days.map { it.cell.left }) && aligned(slots.map { it.cell.top }) &&
      slots.maxOf { it.cell.bottom } < days.minOf { it.cell.top } &&
      days.maxOf { it.cell.right } < slots.minOf { it.cell.left }
    val dayColumns = aligned(days.map { it.cell.top }) && aligned(slots.map { it.cell.left }) &&
      days.maxOf { it.cell.bottom } < slots.minOf { it.cell.top } &&
      slots.maxOf { it.cell.right } < days.minOf { it.cell.left }
    if (dayRows == dayColumns) return TimetableParser.result(emptyList(), listOf("Could not confidently identify day rows or day columns; use CSV or a clearer, straight table image."))
    val orderedDays = days.sortedBy { if (dayRows) it.cell.y else it.cell.x }
    val orderedSlots = slots.sortedBy { if (dayRows) it.cell.x else it.cell.y }
    if (orderedSlots.zipWithNext().any { (a, b) -> a.range.second > b.range.first }) {
      return TimetableParser.result(emptyList(), listOf("Table time headers overlap or are out of order; their meaning is ambiguous."))
    }
    val dayAxis = orderedDays.map { if (dayRows) it.cell.y else it.cell.x }
    val slotAxis = orderedSlots.map { if (dayRows) it.cell.x else it.cell.y }
    if (dayAxis.zipWithNext().any { (a, b) -> b - a <= tolerance } || slotAxis.zipWithNext().any { (a, b) -> b - a <= tolerance }) {
      return TimetableParser.result(emptyList(), listOf("Table headers are too close or merged to assign classes safely."))
    }
    fun bounds(axis: List<Double>, index: Int): Pair<Double, Double> {
      val center = axis[index]
      val lower = if (index == 0) center - (axis[1] - center) / 2 else (axis[index - 1] + center) / 2
      val upper = if (index == axis.lastIndex) center + (center - axis[index - 1]) / 2 else (center + axis[index + 1]) / 2
      return lower to upper
    }
    fun axisIndex(axis: List<Double>, center: Double, lowerEdge: Int, upperEdge: Int): Int? {
      val nearest = axis.indices.minByOrNull { abs(axis[it] - center) } ?: return null
      val (lower, upper) = bounds(axis, nearest)
      // A region spanning two cells might represent a merged lecture or a bad OCR line.
      return nearest.takeIf { lowerEdge >= lower && upperEdge <= upper }
    }
    val headerCells = (days.map { it.cell } + slots.map { it.cell }).toSet()
    val grouped = linkedMapOf<Pair<Int, Int>, MutableList<TimetableTextCell>>()
    var ambiguous = 0
    var cancellations = 0
    for (cell in cells) {
      if (cell in headerCells || Regex("(?i)^(day|time|period|timetable|schedule|[-–—]|n/?a)$").matches(cell.text.trim())) continue
      // Ignore page headings, but never salvage an uncertain body cell using nearest alone.
      val inBody = if (dayRows) cell.top > slots.maxOf { it.cell.bottom } && cell.left > days.maxOf { it.cell.right }
      else cell.top > days.maxOf { it.cell.bottom } && cell.left > slots.maxOf { it.cell.right }
      if (!inBody) continue
      val dayIndex = if (dayRows) axisIndex(dayAxis, cell.y, cell.top, cell.bottom) else axisIndex(dayAxis, cell.x, cell.left, cell.right)
      val slotIndex = if (dayRows) axisIndex(slotAxis, cell.x, cell.left, cell.right) else axisIndex(slotAxis, cell.y, cell.top, cell.bottom)
      if (dayIndex == null || slotIndex == null || TimetableParser.rangePattern.containsMatchIn(cell.text)) { ambiguous++; continue }
      grouped.getOrPut(dayIndex to slotIndex) { mutableListOf() } += cell
    }
    val entries = grouped.mapNotNull { (key, regions) ->
      val title = regions.sortedWith(compareBy<TimetableTextCell> { it.top }.thenBy { it.left }).joinToString(" ") { it.text }
      if (Regex("(?i)\\b(cancelled|canceled|holiday|no class|free|break|lunch)\\b").containsMatchIn(title)) { cancellations++; null }
      else TimetableParser.candidate(orderedDays[key.first].day, orderedSlots[key.second].range, title).also { if (it == null) ambiguous++ }
    }
    val warnings = mutableListOf("Image/PDF OCR is an estimate. Check recognition, merged cells and semester dates; date bounds were not inferred.")
    if (ambiguous > 0) warnings += "$ambiguous ambiguous table regions were skipped. Merged cells and uncertain boundaries require manual entry."
    if (cancellations > 0) warnings += "$cancellations break, free or cancelled cells were excluded."
    return TimetableParser.result(entries, warnings)
  }
}

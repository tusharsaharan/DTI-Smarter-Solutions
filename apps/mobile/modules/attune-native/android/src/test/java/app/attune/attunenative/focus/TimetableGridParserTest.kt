package app.attune.attunenative.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableGridParserTest {
  private fun cell(text: String, left: Int, top: Int, width: Int = 80, height: Int = 20) = TimetableTextCell(text, left, top, left + width, top + height)
  private fun rows() = listOf(cell("Monday", 10, 100), cell("Tuesday", 10, 200),
    cell("09:00-10:00", 160, 20, 100), cell("10:00-11:00", 360, 20, 100))
  private fun columns() = listOf(cell("Monday", 170, 10), cell("Tuesday", 370, 10),
    cell("09:00-10:00", 10, 100, 100), cell("10:00-11:00", 10, 200, 100))

  @Test fun dayRowsAndTimeColumnsUsePositionsNotOcrOrder() {
    val cells = rows() + listOf(cell("Physics", 370, 200), cell("Math", 170, 100), cell("Chemistry", 370, 100))
    val result = TimetableParser.parsePositions(cells.reversed())
    assertEquals(3, result.entries.size)
    assertEquals(1, result.entries.single { it.title == "Math" }.dayOfWeek)
    assertEquals(540, result.entries.single { it.title == "Math" }.startMinute)
    assertEquals(2, result.entries.single { it.title == "Physics" }.dayOfWeek)
    assertEquals(600, result.entries.single { it.title == "Physics" }.startMinute)
    assertTrue(result.entries.all { it.startDate == null && it.endDate == null })
  }

  @Test fun dayColumnsAndTimeRowsAreSupported() {
    val result = TimetableParser.parsePositions(columns() + listOf(cell("Math", 170, 100), cell("Physics", 370, 200)))
    assertEquals(2, result.entries.size)
    assertEquals(1, result.entries.single { it.title == "Math" }.dayOfWeek)
    assertEquals(600, result.entries.single { it.title == "Physics" }.startMinute)
  }

  @Test fun mergedCellsAbstainRatherThanAssigningOneOrTwoGuessedPeriods() {
    val result = TimetableParser.parsePositions(rows() + listOf(cell("Merged lab", 170, 100, 300), cell("Math", 170, 200)))
    assertEquals(listOf("Math"), result.entries.map { it.title })
    assertTrue(result.warnings.any { it.contains("ambiguous table") })
  }

  @Test fun ambiguousHeadersMissingTimesAndDuplicatedDaysAbstain() {
    assertTrue(TimetableParser.parsePositions(listOf(cell("Monday", 10, 100), cell("Math", 170, 100))).entries.isEmpty())
    assertTrue(TimetableParser.parsePositions(rows() + cell("Monday", 10, 300) + cell("Math", 170, 100)).entries.isEmpty())
    val shifted = rows().map { if (it.text == "Tuesday") it.copy(left = 300, right = 380) else it }
    assertTrue(TimetableParser.parsePositions(shifted + cell("Math", 170, 100)).entries.isEmpty())
  }

  @Test fun overlapAndReversedTimeHeadersAreNotReinterpreted() {
    val swapped = rows().map { if (it.text.startsWith("09:")) it.copy(text = "11:00-12:00") else it }
    assertTrue(TimetableParser.parsePositions(swapped + cell("Math", 170, 100)).entries.isEmpty())
    val overlap = rows().map { if (it.text.startsWith("09:")) it.copy(text = "09:00-10:30") else it }
    assertTrue(TimetableParser.parsePositions(overlap + cell("Math", 170, 100)).entries.isEmpty())
  }

  @Test fun multilineTitlesStayInTheirCellAndCancelledCellsAreExcluded() {
    val result = TimetableParser.parsePositions(rows() + listOf(cell("Physics", 170, 190), cell("Lab", 170, 214),
      cell("Math cancelled", 360, 100, 100), cell("Lunch break", 360, 200, 100)))
    assertEquals(listOf("Physics Lab"), result.entries.map { it.title })
    assertTrue(result.warnings.any { it.contains("cancelled cells") })
  }

  @Test fun coordinatesOutsideGridOrAtAmbiguousBoundariesDoNotBecomeClasses() {
    val result = TimetableParser.parsePositions(rows() + listOf(cell("Footer", 170, 500), cell("Boundary", 270, 100),
      cell("Bad", -20, 100), cell("Math", 170, 200)))
    assertEquals(listOf("Math"), result.entries.map { it.title })
  }

  @Test fun tooManyRegionsAreRejectedBeforeGeometryWork() {
    assertTrue(TimetableParser.parsePositions((0..1500).map { cell("Math", 170, 100) }).entries.isEmpty())
  }
}

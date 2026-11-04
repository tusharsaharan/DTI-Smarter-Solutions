package app.attune.attunenative.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableParserTest {
  @Test fun acceptsExplicitAmPmAnd24HourClocks() {
    assertEquals(0, TimetableParser.parseTime("12:00 AM"))
    assertEquals(720, TimetableParser.parseTime("12 PM"))
    assertEquals(815, TimetableParser.parseTime("1:35 pm"))
    assertEquals(570, TimetableParser.parseTime("09:30"))
    assertEquals(1440, TimetableParser.parseTime("24:00", true))
    assertNull(TimetableParser.parseTime("24:00"))
  }

  @Test fun rejectsMalformedOrGuessedClocks() {
    for (value in listOf("9", "9.30", "9:3", "9:60", "25:00", "00 AM", "13 PM", "O9:30", "09:00:30", "-1:00", "09:00 PM junk", "24:01")) {
      assertNull(value, TimetableParser.parseTime(value, true))
    }
    assertNull(TimetableParser.parseRange("09:00-10:00 PM"))
    assertNull(TimetableParser.parseRange("11:00-01:00"))
    assertEquals(780 to 840, TimetableParser.parseRange("1 PM – 2 PM"))
  }

  @Test fun csvRequiresHeadersAndExplicitDay() {
    assertTrue(TimetableParser.parseCsv("Monday,09:00,10:00,Math").entries.isEmpty())
    assertTrue(TimetableParser.parseCsv("day,start,end,title\nFunday,09:00,10:00,Math").entries.isEmpty())
    assertTrue(TimetableParser.parseCsv("day,start,end,title,title\n1,09:00,10:00,A,A").entries.isEmpty())
    val result = TimetableParser.parseCsv("\uFEFFtitle,end,day,start\r\nMath,10:00,Monday,9:00\r\nPhysics,2 PM,5,1 PM")
    assertEquals(listOf(1, 5), result.entries.map { it.dayOfWeek })
    assertEquals(listOf(540, 780), result.entries.map { it.startMinute })
    assertTrue(result.warnings.contains(TimetableParser.REVIEW_WARNING))
  }

  @Test fun csvHandlesQuotedCommasEscapesAndMultilineTitles() {
    val result = TimetableParser.parseCsv("day,start,end,title\nMon,09:00,10:00,\"Math, \"\"A\"\"\"\nTue,11:00,12:00,\"Physics\nLab\"")
    assertEquals(listOf("Math, \"A\"", "Physics Lab"), result.entries.map { it.title })
  }

  @Test fun malformedQuotingAndFieldCountsNeverBecomeEntries() {
    for (csv in listOf("day,start,end,title\nMon,09:00,10:00,\"Math", "day,start,end,title\nMon,09:00,10:00,Ma\"th", "day,start,end,title\nMon,09:00,10:00,\"Math\"oops")) {
      assertTrue(TimetableParser.parseCsv(csv).entries.isEmpty())
    }
    val mixed = TimetableParser.parseCsv("day,start,end,title\nMon,09:00,10:00,Math,extra\nTue,10:00,11:00,Physics")
    assertEquals(1, mixed.entries.size)
    assertTrue(mixed.warnings.any { it.contains("row 2") })
  }

  @Test fun validationEnforcesMinuteDurationDayAndDateBounds() {
    val base = TimetableEntry("id", 1, 540, 600, "Math")
    assertTrue(TimetableParser.validate(base))
    for (entry in listOf(base.copy(dayOfWeek = 0), base.copy(dayOfWeek = 8), base.copy(startMinute = -1),
      base.copy(startMinute = 1440, endMinute = 1441), base.copy(endMinute = 540), base.copy(endMinute = 539),
      base.copy(startMinute = 0, endMinute = 721), base.copy(title = ""), base.copy(title = "A".repeat(161)),
      base.copy(title = "Math\u0000"), base.copy(startDate = "2026-02-30"), base.copy(startDate = "26-11-02"),
      base.copy(startDate = "2026-11-09", endDate = "2026-11-02"), base.copy(startDate = "2026-11-03", endDate = "2026-11-03"))) {
      assertFalse(entry.toString(), TimetableParser.validate(entry))
    }
    assertTrue(TimetableParser.validate(base.copy(startMinute = 1380, endMinute = 1440)))
    assertTrue(TimetableParser.validate(base.copy(startDate = "2026-11-02", endDate = "2026-11-09")))
  }

  @Test fun csvOptionalIsoDatesStayInclusiveAndMalformedDatesAreNotDropped() {
    val result = TimetableParser.parseCsv("day,start,end,title,startDate,endDate\nMon,9:00,10:00,Math,2026-11-02,2026-11-30\nTue,9:00,10:00,Bad,2026-02-30,2026-11-30")
    assertEquals(1, result.entries.size)
    assertEquals("2026-11-30", result.entries.single().endDate)
    assertEquals("2026-11-02", result.entries.single().toMap()["startDate"])
  }

  @Test fun textDoesNotInventDayDurationOrMeridiem() {
    val result = TimetableParser.parseText("Monday\n09:00-10:00 Math\nTuesday 09:00 Math\nWednesday 9-10 Math\nThursday 09:00-10:00 PM Math\nFriday 11:00-10:00 Math\nMonday 09:00-10:00 Mathematics\nTue Physics 1 PM to 2 PM")
    assertEquals(listOf("Mathematics", "Physics"), result.entries.map { it.title })
    assertEquals(listOf(1, 2), result.entries.map { it.dayOfWeek })
    assertTrue(result.warnings.any { it.contains("ambiguous") })
  }

  @Test fun explicitCancellationLinesDoNotBecomeWeeklyClasses() {
    assertTrue(TimetableParser.parseText("Monday 09:00-10:00 Math cancelled\nTuesday 10:00-11:00 No class").entries.isEmpty())
  }

  @Test fun deduplicationIsStableAndInputIsBounded() {
    val csv = "day,start,end,title\n" + (1..210).joinToString("\n") { "Mon,09:00,10:00,Class $it" }
    val bounded = TimetableParser.parseCsv(csv)
    assertEquals(200, bounded.entries.size)
    assertTrue(bounded.warnings.any { it.contains("first 200") })
    val duplicate = TimetableParser.parseCsv("day,start,end,title\nMon,09:00,10:00,Math\n1,09:00,10:00,Math")
    assertEquals(1, duplicate.entries.size)
    assertEquals(duplicate.entries.single().id, TimetableParser.parseText("Monday 09:00-10:00 Math").entries.single().id)
    assertTrue(TimetableParser.parseCsv("A".repeat(TimetableParser.MAX_TEXT_CHARS + 1)).entries.isEmpty())
    assertTrue(TimetableParser.parseText("A".repeat(TimetableParser.MAX_TEXT_CHARS + 1)).entries.isEmpty())
  }

  @Test fun reviewWarningSurvivesManySkippedRows() {
    val csv = "day,start,end,title\n" + (1..150).joinToString("\n") { "Bad,9:99,10:00,Class" }
    val result = TimetableParser.parseCsv(csv)
    assertTrue(result.entries.isEmpty())
    assertTrue(result.warnings.size <= 50)
    assertEquals(TimetableParser.REVIEW_WARNING, result.warnings.last())
  }
}

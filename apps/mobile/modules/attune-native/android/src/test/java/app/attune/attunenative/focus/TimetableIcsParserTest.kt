package app.attune.attunenative.focus

import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableIcsParserTest {
  private fun event(extra: String = "", start: String = "DTSTART:20261026T090000Z", end: String = "DTEND:20261026T100000Z", uid: String = "math") =
    "BEGIN:VEVENT\nUID:$uid\n$start\n$end\nSUMMARY:Math\n$extra\nEND:VEVENT"
  private fun calendar(events: String, extra: String = "") = "BEGIN:VCALENDAR\nVERSION:2.0\n$extra\n$events\nEND:VCALENDAR"
  private fun parse(events: String, zone: ZoneId = ZoneOffset.UTC) = TimetableParser.parseIcs(calendar(events), zone)

  @Test fun standaloneEventsStayDatedRatherThanRepeatingForever() {
    val entry = parse(event()).entries.single()
    assertEquals("2026-10-26", entry.startDate)
    assertEquals(entry.startDate, entry.endDate)
    assertEquals(1, entry.dayOfWeek)
    assertEquals(540, entry.startMinute)
  }

  @Test fun countBoundedWeeklyExpansionPreservesDatesAndExclusions() {
    val result = parse(event("RRULE:FREQ=WEEKLY;COUNT=4\nEXDATE:20261102T090000Z"))
    assertEquals(listOf("2026-10-26", "2026-11-09", "2026-11-16"), result.entries.map { it.startDate })
    assertTrue(result.entries.all { it.startDate == it.endDate })
    assertTrue(result.warnings.any { it.contains("exclusions") })
  }

  @Test fun untilIsInclusiveAndDoesNotCreateLaterOrEarlierOccurrences() {
    assertEquals(listOf("2026-10-26", "2026-11-02", "2026-11-09"), parse(event("RRULE:FREQ=WEEKLY;UNTIL=20261109T090000Z")).entries.map { it.startDate })
    assertEquals(2, parse(event("RRULE:FREQ=WEEKLY;UNTIL=20261109T085900Z")).entries.size)
    assertTrue(parse(event("RRULE:FREQ=WEEKLY;UNTIL=20261001T090000Z")).entries.isEmpty())
  }

  @Test fun byDayCountIncludesStartAndCountIsBeforeExclusions() {
    val result = parse(event("RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6\nEXDATE:20261026T090000Z,20261104T090000Z"))
    assertEquals(listOf("2026-10-28", "2026-11-02", "2026-11-09", "2026-11-11"), result.entries.map { it.startDate })
    assertEquals(listOf(3, 1, 1, 3), result.entries.map { it.dayOfWeek })
  }

  @Test fun intervalAndWeekStartDoNotShiftRecurrenceWeeks() {
    val result = parse(event("RRULE:FREQ=WEEKLY;BYDAY=MO,WE;INTERVAL=2;WKST=SU;COUNT=3", "DTSTART:20261028T090000Z", "DTEND:20261028T100000Z"))
    assertEquals(listOf("2026-10-28", "2026-11-09", "2026-11-11"), result.entries.map { it.startDate })
  }

  @Test fun explicitTimezoneConversionAlsoConvertsWeekdayAndDate() {
    val result = parse(event(start = "DTSTART:20261101T230000Z", end = "DTEND:20261102T000000Z"), ZoneId.of("Asia/Kolkata"))
    val entry = result.entries.single()
    assertEquals("2026-11-02", entry.startDate)
    assertEquals(1, entry.dayOfWeek)
    assertEquals(270, entry.startMinute)
    assertEquals(330, entry.endMinute)
    assertTrue(result.warnings.any { it.contains("converted") })
  }

  @Test fun zonedWeeklyClassesHonorDstRatherThanFixedUtcTime() {
    val result = parse(event("RRULE:FREQ=WEEKLY;COUNT=2", "DTSTART;TZID=America/New_York:20261026T090000", "DTEND;TZID=America/New_York:20261026T100000"))
    assertEquals(listOf(780, 840), result.entries.map { it.startMinute })
    assertEquals(listOf("2026-10-26", "2026-11-02"), result.entries.map { it.startDate })
  }

  @Test fun floatingTimesAreExplicitlyLabeledAndRemainDateBounded() {
    val result = parse(event("RRULE:FREQ=WEEKLY;UNTIL=20261102T090000", "DTSTART:20261026T090000", "DTEND:20261026T100000"), ZoneId.of("Asia/Kolkata"))
    assertEquals(listOf(540, 540), result.entries.map { it.startMinute })
    assertTrue(result.warnings.any { it.contains("Floating") })
  }

  @Test fun cancelledOccurrenceOverridesDoNotResurrectOriginalClass() {
    val cancellation = "BEGIN:VEVENT\nUID:math\nRECURRENCE-ID:20261102T090000Z\nSTATUS:CANCELLED\nEND:VEVENT"
    val result = parse(event("RRULE:FREQ=WEEKLY;COUNT=3") + "\n" + cancellation)
    assertEquals(listOf("2026-10-26", "2026-11-09"), result.entries.map { it.startDate })
    assertTrue(parse(event("STATUS:CANCELLED")).entries.isEmpty())
  }

  @Test fun changedOrAmbiguousOverridesRejectEntireOriginalSeries() {
    val original = event("RRULE:FREQ=WEEKLY;COUNT=3")
    for (override in listOf(event("RECURRENCE-ID:20261102T090000Z"),
      "BEGIN:VEVENT\nUID:math\nRECURRENCE-ID;RANGE=THISANDFUTURE:20261102T090000Z\nSTATUS:CANCELLED\nEND:VEVENT",
      "BEGIN:VEVENT\nUID:math\nRECURRENCE-ID:bad\nSTATUS:CANCELLED\nEND:VEVENT")) {
      assertTrue(parse(original + "\n" + override).entries.isEmpty())
    }
    assertTrue(parse(original + "\n" + original).entries.isEmpty())
  }

  @Test fun unsupportedRecurrenceRulesAbstainInsteadOfBroadening() {
    for (rule in listOf("FREQ=DAILY;COUNT=3", "FREQ=WEEKLY", "FREQ=WEEKLY;COUNT=201", "FREQ=WEEKLY;COUNT=2;UNTIL=20261109T090000Z",
      "FREQ=WEEKLY;COUNT=2;BYDAY=1MO", "FREQ=WEEKLY;COUNT=2;BYDAY=TU", "FREQ=WEEKLY;COUNT=2;BYHOUR=9",
      "FREQ=WEEKLY;COUNT=2;COUNT=3", "FREQ=WEEKLY;INTERVAL=0;COUNT=2", "FREQ=WEEKLY;COUNT=0", "FREQ=WEEKLY;COUNT=abc",
      "FREQ=WEEKLY;UNTIL=20281026T090000Z", "FREQ=WEEKLY;COUNT=60")) {
      val result = parse(event("RRULE:$rule"))
      assertTrue(rule, result.entries.isEmpty())
      assertTrue(result.warnings.contains(TimetableParser.REVIEW_WARNING))
    }
  }

  @Test fun rDatesExRulesAllDayAndInvalidExclusionsRejectWholeEvent() {
    for (extra in listOf("RDATE:20261101T090000Z", "EXRULE:FREQ=WEEKLY;COUNT=2", "EXDATE;VALUE=DATE:20261102", "EXDATE:20260230T090000Z", "EXDATE;TZID=Unknown/Zone:20261102T090000")) {
      assertTrue(extra, parse(event("RRULE:FREQ=WEEKLY;COUNT=3\n$extra")).entries.isEmpty())
    }
    assertTrue(parse(event(start = "DTSTART;VALUE=DATE:20261026", end = "DTEND;VALUE=DATE:20261027")).entries.isEmpty())
  }

  @Test fun malformedTimesMinutePrecisionAndOvernightAreNotSilentlyRounded() {
    for ((start, end) in listOf("20260230T090000Z" to "20260230T100000Z", "20261026T090030Z" to "20261026T100000Z",
      "20261026T240000Z" to "20261027T010000Z", "20261026T100000Z" to "20261026T090000Z",
      "20261026T090000Z" to "20261026T220000Z", "20261026T230000Z" to "20261027T010000Z")) {
      assertTrue(parse(event(start = "DTSTART:$start", end = "DTEND:$end")).entries.isEmpty())
    }
    assertEquals(1440, parse(event(start = "DTSTART:20261026T230000Z", end = "DTEND:20261027T000000Z")).entries.single().endMinute)
  }

  @Test fun dstGapAndOverlapAbstainInsteadOfChoosingAnOffset() {
    assertTrue(parse(event(start = "DTSTART;TZID=America/New_York:20260308T023000", end = "DTEND;TZID=America/New_York:20260308T033000")).entries.isEmpty())
    assertTrue(parse(event(start = "DTSTART;TZID=America/New_York:20261101T013000", end = "DTEND;TZID=America/New_York:20261101T023000")).entries.isEmpty())
    // Later recurring occurrences must be checked too; no partial series leaks through.
    assertTrue(parse(event("RRULE:FREQ=WEEKLY;COUNT=2", "DTSTART;TZID=America/New_York:20261025T013000", "DTEND;TZID=America/New_York:20261025T023000")).entries.isEmpty())
  }

  @Test fun embeddedTimezoneDefinitionsAndMismatchedUntilFormsAreRefused() {
    val timezone = "BEGIN:VTIMEZONE\nTZID:Asia/Kolkata\nEND:VTIMEZONE"
    val events = event(start = "DTSTART;TZID=Asia/Kolkata:20261026T090000", end = "DTEND;TZID=Asia/Kolkata:20261026T100000")
    assertTrue(TimetableParser.parseIcs(calendar(timezone + "\n" + events), ZoneOffset.UTC).entries.isEmpty())
    assertTrue(parse(event("RRULE:FREQ=WEEKLY;UNTIL=20261102T090000")).entries.isEmpty())
    assertTrue(parse(event("RRULE:FREQ=WEEKLY;UNTIL=20261102T090000Z", "DTSTART:20261026T090000", "DTEND:20261026T100000")).entries.isEmpty())
  }

  @Test fun foldingAndEscapedTitleCharactersArePreserved() {
    val events = event().replace("SUMMARY:Math", "SUMMARY:Math\\, Algorithms\\;\n Lab")
    assertEquals("Math, Algorithms;Lab", parse(events).entries.single().title)
  }

  @Test fun malformedStructureAndCancellationCalendarCreateNothing() {
    assertTrue(TimetableParser.parseIcs(calendar(event()).removeSuffix("END:VCALENDAR"), ZoneOffset.UTC).entries.isEmpty())
    assertTrue(TimetableParser.parseIcs(calendar(event(), "METHOD:CANCEL"), ZoneOffset.UTC).entries.isEmpty())
    assertTrue(TimetableParser.parseIcs("A".repeat(TimetableParser.MAX_TEXT_CHARS + 1), ZoneOffset.UTC).entries.isEmpty())
  }

  @Test fun excessiveTotalOccurrencesRejectOverflowingSeriesWithWarning() {
    val events = (1..3).joinToString("\n") { event("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;COUNT=100", uid = "class-$it").replace("SUMMARY:Math", "SUMMARY:Class $it") }
    val result = parse(events)
    assertEquals(200, result.entries.size)
    assertTrue(result.warnings.any { it.contains("exceeds 200") })
  }
}

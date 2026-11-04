package app.attune.attunenative.focus

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Small strict RFC 5545 subset. Unsupported recurrence semantics reject the whole series. */
internal object TimetableIcsParser {
  private const val MAX_SPAN_DAYS = 366L
  private val formatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
  private val weekDays = mapOf("MO" to 1, "TU" to 2, "WE" to 3, "TH" to 4, "FR" to 5, "SA" to 6, "SU" to 7)
  private data class Property(val name: String, val params: Map<String, String>, val value: String)
  private data class Stamp(val local: LocalDateTime, val zone: ZoneId, val floating: Boolean) {
    fun resolved(): ZonedDateTime {
      val offsets = zone.rules.getValidOffsets(local)
      require(offsets.size == 1) { "Ambiguous or nonexistent daylight-saving time." }
      return ZonedDateTime.ofStrict(local, offsets.single(), zone)
    }
  }
  private data class Rule(val days: Set<Int>, val interval: Int, val weekStart: Int, val count: Int?, val until: Instant?)

  fun parse(text: String, localZone: ZoneId): TimetableParseResult {
    if (text.length > TimetableParser.MAX_TEXT_CHARS) return TimetableParser.result(emptyList(), listOf("Calendar exceeds the local text size limit."))
    val events = mutableListOf<List<Property>>()
    val calendar = mutableListOf<Property>()
    val embeddedZones = mutableSetOf<String>()
    try {
      val unfolded = mutableListOf<String>()
      for (line in text.removePrefix("\uFEFF").replace("\r\n", "\n").split('\n')) {
        if (line.startsWith(' ') || line.startsWith('\t')) {
          require(unfolded.isNotEmpty())
          unfolded[unfolded.lastIndex] += line.drop(1)
        } else if (line.isNotEmpty()) unfolded += line
        require(unfolded.size <= 10_000 && (unfolded.lastOrNull()?.length ?: 0) <= 8192)
      }
      val stack = mutableListOf<String>()
      var event: MutableList<Property>? = null
      var calendars = 0
      for (line in unfolded) {
        val prop = property(line)
        if (prop.name == "BEGIN") {
          val component = prop.value.uppercase(Locale.ROOT)
          if (component == "VCALENDAR") { require(stack.isEmpty()); calendars++ }
          else require(stack.isNotEmpty())
          if (component == "VEVENT") { require(stack == listOf("VCALENDAR")); event = mutableListOf() }
          stack += component
        } else if (prop.name == "END") {
          require(stack.isNotEmpty() && stack.last() == prop.value.uppercase(Locale.ROOT))
          if (stack.last() == "VEVENT") { events += event!!; event = null; require(events.size <= TimetableParser.MAX_ENTRIES) }
          stack.removeAt(stack.lastIndex)
        } else {
          require(stack.isNotEmpty())
          when {
            stack == listOf("VCALENDAR") -> calendar += prop
            stack.last() == "VEVENT" -> event!!.add(prop)
            stack.last() == "VTIMEZONE" && prop.name == "TZID" -> embeddedZones += prop.value
          }
        }
      }
      require(calendars == 1 && stack.isEmpty() && calendar.singleOrNull { it.name == "VERSION" }?.value == "2.0")
    } catch (_: Exception) {
      return TimetableParser.result(emptyList(), listOf("Malformed, incomplete or oversized iCalendar; nothing was imported."))
    }
    val method = calendar.filter { it.name == "METHOD" }
    if (method.size > 1 || method.firstOrNull()?.value?.uppercase(Locale.ROOT) !in setOf(null, "PUBLISH", "REQUEST")) {
      return TimetableParser.result(emptyList(), listOf("Calendar cancellation/reply methods are not timetables; no schedule was created or changed."))
    }
    if (calendar.any { it.name == "CALSCALE" && !it.value.equals("GREGORIAN", true) }) {
      return TimetableParser.result(emptyList(), listOf("Only Gregorian calendars are supported."))
    }
    val warnings = mutableListOf<String>()
    val entries = mutableListOf<TimetableEntry>()
    val groups = events.groupBy { it.singleOrNull { p -> p.name == "UID" }?.value }
    for ((uid, group) in groups) {
      val label = "Calendar event ${events.indexOf(group.first()) + 1}"
      if (uid.isNullOrBlank()) { warnings += "$label has no unique identifier and was skipped."; continue }
      try {
        val masters = group.filter { props -> props.none { it.name == "RECURRENCE-ID" } }
        require(masters.size == 1) { "Duplicate revisions or missing original event; series skipped." }
        val master = masters.single()
        fun single(name: String, required: Boolean = false): Property? {
          val values = master.filter { it.name == name }
          require(values.size <= 1 && (!required || values.size == 1)) { "Missing or duplicate $name." }
          return values.firstOrNull()
        }
        val status = single("STATUS")?.value?.uppercase(Locale.ROOT)
        if (status == "CANCELLED") { warnings += "$label is cancelled and was excluded."; continue }
        require(status == null || status == "CONFIRMED") { "Tentative or unsupported event status; skipped." }
        require(master.none { it.name in setOf("RDATE", "EXRULE", "DURATION") }) { "RDATE, EXRULE and DURATION are unsupported; series skipped." }
        val startProp = single("DTSTART", true)!!
        val start = stamp(startProp, localZone, embeddedZones)
        val end = stamp(single("DTEND", true)!!, localZone, embeddedZones)
        require(start.zone == end.zone && start.floating == end.floating) { "Start/end timezone forms differ; skipped." }
        val startZoned = start.resolved()
        val duration = Duration.between(startZoned.toInstant(), end.resolved().toInstant())
        require(duration.seconds in 60..43_200 && duration.seconds % 60 == 0L) { "Event must be 1 minute to 12 hours with exact minute precision." }
        val title = unescape(single("SUMMARY", true)!!.value)
        require(title.isNotBlank() && title.length <= TimetableParser.MAX_TITLE_CHARS) { "Missing or oversized title." }
        val rule = single("RRULE")?.let { rule(it, start) }
        val exclusions = mutableSetOf<Instant>()
        for (excluded in master.filter { it.name == "EXDATE" }) {
          val values = excluded.value.split(',')
          require(values.size <= TimetableParser.MAX_ENTRIES && exclusions.size + values.size <= TimetableParser.MAX_ENTRIES) { "Too many exclusions." }
          for (value in values) {
            val ex = stamp(excluded.copy(value = value), localZone, embeddedZones)
            require(ex.floating == start.floating) { "Exclusion timezone form does not match DTSTART." }
            exclusions += ex.resolved().toInstant()
          }
        }
        for (override in group.filter { props -> props.any { it.name == "RECURRENCE-ID" } }) {
          require(override.singleOrNull { it.name == "STATUS" }?.value?.equals("CANCELLED", true) == true) {
            "Changed recurrence overrides are unsupported; original series also skipped to avoid stale times."
          }
          val recurrence = override.singleOrNull { it.name == "RECURRENCE-ID" } ?: error("Duplicate recurrence identifier.")
          require(recurrence.params.keys.all { it in setOf("TZID", "VALUE") }) { "RANGE or custom recurrence override is unsupported." }
          val exclusion = stamp(recurrence, localZone, embeddedZones)
          require(exclusion.floating == start.floating) { "Cancellation timezone does not match DTSTART." }
          exclusions += exclusion.resolved().toInstant()
        }
        val occurrences = if (rule == null) listOf(startZoned) else expand(start, rule)
        val eventEntries = occurrences.filter { it.toInstant() !in exclusions }.map { occurrence ->
          val convertedStart = occurrence.withZoneSameInstant(localZone)
          val convertedEnd = occurrence.toInstant().plus(duration).atZone(localZone)
          val date = convertedStart.toLocalDate()
          val endMinute = if (convertedEnd.toLocalDate() == date) convertedEnd.hour * 60 + convertedEnd.minute
          else if (convertedEnd.toLocalDate() == date.plusDays(1) && convertedEnd.toLocalTime() == LocalTime.MIDNIGHT) 1440
          else error("An occurrence crosses local midnight; series skipped rather than losing its date.")
          TimetableParser.candidate(date.dayOfWeek.value, (convertedStart.hour * 60 + convertedStart.minute) to endMinute,
            title, date.toString(), date.toString()) ?: error("Invalid converted occurrence; series skipped.")
        }
        require(entries.size + eventEntries.size <= TimetableParser.MAX_ENTRIES) { "Calendar exceeds 200 occurrences; split it into smaller date-bounded files." }
        entries += eventEntries
        if (start.floating) warnings += "Floating calendar times were interpreted in ${localZone.id}; review this timezone."
        else if (start.zone != localZone) warnings += "Calendar times were converted from ${start.zone.id} to ${localZone.id}. Re-import if the device timezone changes."
        if (exclusions.isNotEmpty()) warnings += "$label exclusions/cancelled occurrences were honored; existing saved schedules were not changed."
      } catch (error: IllegalArgumentException) {
        warnings += "$label: ${error.message ?: "Unsupported values; series skipped."}"
      } catch (error: IllegalStateException) {
        warnings += "$label: ${error.message ?: "Unsupported recurrence; series skipped."}"
      } catch (_: java.time.DateTimeException) {
        warnings += "$label has an invalid date or unknown timezone; series skipped."
      }
    }
    return TimetableParser.result(entries, warnings)
  }

  private fun property(line: String): Property {
    var quoted = false
    var colon = -1
    for (index in line.indices) {
      if (line[index] == '"') quoted = !quoted
      if (line[index] == ':' && !quoted) { colon = index; break }
    }
    require(colon > 0 && !quoted)
    val pieces = line.substring(0, colon).split(';')
    val name = pieces.first().uppercase(Locale.ROOT)
    require(Regex("[A-Z0-9-]+").matches(name))
    val params = linkedMapOf<String, String>()
    for (piece in pieces.drop(1)) {
      val equal = piece.indexOf('=')
      require(equal > 0)
      val key = piece.substring(0, equal).uppercase(Locale.ROOT)
      val value = piece.substring(equal + 1).removeSurrounding("\"")
      require(value.isNotEmpty() && !params.containsKey(key))
      params[key] = value
    }
    return Property(name, params, line.substring(colon + 1))
  }

  private fun stamp(prop: Property, localZone: ZoneId, embeddedZones: Set<String>): Stamp {
    require(prop.params.keys.all { it in setOf("TZID", "VALUE") }) { "Custom date/time parameters are unsupported." }
    require(prop.params["VALUE"]?.uppercase(Locale.ROOT) in setOf(null, "DATE-TIME")) { "All-day/date-only calendar events or exclusions are unsupported." }
    val utc = prop.value.endsWith('Z')
    require(Regex("[0-9]{8}T[0-9]{6}Z?").matches(prop.value)) { "Dates need explicit YYYYMMDDTHHMMSS with optional Z." }
    val tzid = prop.params["TZID"]
    require(!(utc && tzid != null)) { "UTC values cannot also have TZID." }
    require(tzid !in embeddedZones) { "Embedded VTIMEZONE definitions are unsupported; export UTC dates instead." }
    val local = LocalDateTime.parse(prop.value.removeSuffix("Z"), formatter)
    require(local.year in 1900..2200 && local.second == 0) { "Date outside supported years or nonzero seconds; skipped." }
    val zone = if (utc) ZoneOffset.UTC else if (tzid != null) ZoneId.of(tzid) else localZone
    return Stamp(local, zone, !utc && tzid == null)
  }

  private fun rule(prop: Property, start: Stamp): Rule {
    require(prop.params.isEmpty()) { "RRULE parameters are unsupported." }
    val values = linkedMapOf<String, String>()
    for (part in prop.value.uppercase(Locale.ROOT).split(';')) {
      val pair = part.split('=')
      require(pair.size == 2 && pair[0] !in values && pair[1].isNotEmpty()) { "Malformed or duplicate recurrence fields." }
      values[pair[0]] = pair[1]
    }
    require(values.keys.all { it in setOf("FREQ", "INTERVAL", "BYDAY", "WKST", "COUNT", "UNTIL") } && values["FREQ"] == "WEEKLY") {
      "Only simple weekly RRULE with BYDAY/INTERVAL/WKST and COUNT or UNTIL is supported; series skipped."
    }
    val interval = values["INTERVAL"]?.let { positiveInt(it) } ?: 1
    require(interval in 1..52) { "Recurrence interval is unsupported." }
    val days = values["BYDAY"]?.split(',')?.map { weekDays[it] ?: error("Ordinal or unknown BYDAY is unsupported.") }?.toSet()
      ?: setOf(start.local.dayOfWeek.value)
    require(start.local.dayOfWeek.value in days) { "DTSTART does not match BYDAY; no recurrence was guessed." }
    val weekStart = values["WKST"]?.let { weekDays[it] ?: error("Invalid WKST.") } ?: 1
    require(values.containsKey("COUNT") xor values.containsKey("UNTIL")) { "Weekly recurrences require exactly one explicit COUNT or UNTIL bound." }
    val count = values["COUNT"]?.let { positiveInt(it) }
    require(count == null || count in 1..TimetableParser.MAX_ENTRIES) { "Recurrence COUNT exceeds 200." }
    val until = values["UNTIL"]?.let { value ->
      val mustUtc = !start.floating
      require(value.endsWith('Z') == mustUtc) { "UNTIL must be UTC for zoned DTSTART, or local for floating DTSTART." }
      val parsed = stamp(Property("UNTIL", emptyMap(), value), start.zone, emptySet()).resolved().toInstant()
      require(parsed >= start.resolved().toInstant() && Duration.between(start.resolved().toInstant(), parsed).toDays() <= MAX_SPAN_DAYS) {
        "UNTIL precedes DTSTART or spans more than 366 days."
      }
      parsed
    }
    return Rule(days, interval, weekStart, count, until)
  }

  private fun positiveInt(value: String): Int {
    require(Regex("[0-9]{1,4}").matches(value)) { "Malformed recurrence number." }
    return value.toInt()
  }

  private fun expand(start: Stamp, rule: Rule): List<ZonedDateTime> {
    val date = start.local.toLocalDate()
    val firstWeek = date.minusDays(((date.dayOfWeek.value - rule.weekStart + 7) % 7).toLong())
    val occurrences = mutableListOf<ZonedDateTime>()
    for (offset in 0..MAX_SPAN_DAYS.toInt()) {
      val day = date.plusDays(offset.toLong())
      val week = ChronoUnit.DAYS.between(firstWeek, day) / 7
      if (week % rule.interval != 0L || day.dayOfWeek.value !in rule.days) continue
      val occurrence = start.copy(local = LocalDateTime.of(day, start.local.toLocalTime())).resolved()
      if (rule.until != null && occurrence.toInstant() > rule.until) return occurrences
      occurrences += occurrence
      require(occurrences.size <= TimetableParser.MAX_ENTRIES) { "Recurrence exceeds 200 occurrences; series skipped." }
      if (rule.count != null && occurrences.size == rule.count) return occurrences
    }
    if (rule.until != null && rule.until <= date.plusDays(MAX_SPAN_DAYS).plusDays(1).atStartOfDay(start.zone).toInstant()) return occurrences
    error("Recurrence cannot finish within the 366-day expansion limit; series skipped.")
  }

  private fun unescape(value: String): String {
    val result = StringBuilder()
    var index = 0
    while (index < value.length) {
      val char = value[index++]
      if (char != '\\') result.append(char)
      else {
        require(index < value.length) { "Malformed title escape." }
        when (val escaped = value[index++]) {
          '\\', ',', ';' -> result.append(escaped)
          'n', 'N' -> result.append(' ')
          else -> error("Unsupported title escape.")
        }
      }
    }
    return result.toString()
  }
}

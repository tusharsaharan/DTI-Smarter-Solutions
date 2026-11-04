package app.attune.attunenative.context

/** CalendarContract availability/status constants, kept platform-free for boundary tests. */
object CalendarBusyWindow {
  fun isBusyNow(
    nowMs: Long,
    beginMs: Long,
    endMs: Long,
    allDay: Boolean,
    availability: Int,
    status: Int,
    selfAttendeeStatus: Int,
    deleted: Boolean
  ): Boolean = beginMs <= nowMs && nowMs < endMs && endMs > beginMs &&
    !allDay && !deleted && availability != 1 /* AVAILABILITY_FREE */ &&
    status != 2 /* STATUS_CANCELED */ && selfAttendeeStatus != 2 /* ATTENDEE_STATUS_DECLINED */
}

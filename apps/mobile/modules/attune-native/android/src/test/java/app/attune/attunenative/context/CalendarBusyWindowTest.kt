package app.attune.attunenative.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarBusyWindowTest {
  private fun busy(now: Long = 100, begin: Long = 100, end: Long = 200,
    allDay: Boolean = false, availability: Int = 0, status: Int = 1,
    attendee: Int = 1, deleted: Boolean = false) =
    CalendarBusyWindow.isBusyNow(now, begin, end, allDay, availability, status, attendee, deleted)

  @Test fun onlyAnOngoingEventIsBusy() {
    assertTrue(busy())
    assertFalse(busy(begin = 101))
    assertFalse(busy(now = 200))
    assertFalse(busy(end = 100))
  }

  @Test fun cancelledDeclinedFreeDeletedAndAllDayEventsDoNotSuppress() {
    assertFalse(busy(status = 2))
    assertFalse(busy(attendee = 2))
    assertFalse(busy(availability = 1))
    assertFalse(busy(deleted = true))
    assertFalse(busy(allDay = true))
  }
}

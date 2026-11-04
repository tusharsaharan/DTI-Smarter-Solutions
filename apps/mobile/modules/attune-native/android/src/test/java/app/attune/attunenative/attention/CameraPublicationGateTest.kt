package app.attune.attunenative.attention

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CameraPublicationGateTest {
  @Test fun stopWinsOverAlreadyCheckedConfigurationRejectionAndExpiryCallbacks() {
    for (callback in listOf("configured", "rejected", "expired", "inferred")) {
      val gate = CameraPublicationGate()
      val generation = gate.advance()
      val status = AtomicReference("running")
      val checked = CountDownLatch(1)
      val resume = CountDownLatch(1)
      val error = AtomicReference<Throwable?>()
      val worker = Thread {
        try {
          assertTrue(gate.isCurrent(generation))
          checked.countDown()
          assertTrue(resume.await(5, TimeUnit.SECONDS))
          assertFalse(gate.publish(generation) { status.set(callback) })
        } catch (failure: Throwable) { error.set(failure) }
      }.also { it.start() }
      try {
        assertTrue(checked.await(5, TimeUnit.SECONDS))
        gate.advance { status.set("stopped") }
      } finally { resume.countDown() }
      worker.join(5_000)
      assertFalse(worker.isAlive)
      error.get()?.let { throw AssertionError(it) }
      assertEquals("stopped", status.get())
    }
  }

  @Test fun oldFailureCannotInvalidateOrPublishIntoRestartedSession() {
    val gate = CameraPublicationGate()
    val old = gate.advance()
    gate.advance()
    val restarted = gate.advance()
    assertFalse(gate.invalidate(old) { fail("Old failure was published") })
    assertFalse(gate.publish(old) { fail("Old result was published") })
    assertTrue(gate.publish(restarted) {})
    assertTrue(gate.invalidate(restarted) {})
    assertFalse(gate.publish(restarted) { fail("Stopped session was published") })
  }
}

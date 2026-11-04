package app.attune.attunenative.focus

import app.attune.attunenative.dnd.DndNotificationPolicy
import app.attune.attunenative.dnd.DndPlatform
import app.attune.attunenative.dnd.DndPolicyBackend
import app.attune.attunenative.dnd.DndPolicyCoordinator
import app.attune.attunenative.dnd.DndPolicySnapshot
import app.attune.attunenative.dnd.DndPolicySnapshotStore
import app.attune.attunenative.dnd.DndRequest
import org.junit.Assert.*
import org.junit.Test

/** Exercises Focus ownership validation through the actual native policy write coordinator. */
class FocusDndTransactionIntegrationTest {
  private val original = DndNotificationPolicy(0, 0, 0)
  private val active = DndNotificationPolicy(12, 2, 2)
  private fun key(policy: DndNotificationPolicy) = listOf(policy.priorityCategories, policy.priorityCallSenders,
    policy.priorityMessageSenders, policy.suppressedVisualEffects, policy.priorityConversationSenders).joinToString(",")

  @Test fun capturedExternalFiltersNeverAuthorizeRestorationOrManualExceptionWrites() {
    for (enabled in listOf(false, true)) for (filter in listOf(1, 3, 4)) {
      val backend = Backend(filter, active)
      val store = Store(DndPolicySnapshot(1, original, 100, "session"))
      val own = FocusOwnedSession("session", "manual", 0, 100, phase = "active", appliedPolicyKey = key(active))
      // Simulates a user change after the first observation but before baseline capture.
      val capturedFilter = backend.filter
      val capturedPolicy = backend.policy
      val result = DndPolicyCoordinator(backend, store).apply(DndRequest(enabled, false, false, own.id, capturedFilter)) {
        !FocusOwnershipSafety.baselineOverride(own, false, capturedFilter, key(capturedPolicy), key(original)) &&
          backend.filter == capturedFilter
      }
      assertEquals("policy_write_guard_rejected", result.reason)
      assertTrue(backend.writes.isEmpty())
      assertEquals(filter, backend.filter)
      assertEquals(active, backend.policy)
      assertEquals("session", store.snapshot?.ownerId)
    }
  }

  @Test fun capturedExternalPolicyIsPreservedDespiteAnUnchangedPriorityFilter() {
    for (enabled in listOf(false, true)) {
      val external = DndNotificationPolicy(64, 1, 1)
      val backend = Backend(2, external)
      val store = Store(DndPolicySnapshot(1, original, 100, "session"))
      val own = FocusOwnedSession("session", "manual", 0, 100, phase = "active", appliedPolicyKey = key(active))
      val result = DndPolicyCoordinator(backend, store).apply(DndRequest(enabled, false, false, own.id, 2)) {
        !FocusOwnershipSafety.baselineOverride(own, false, 2, key(external), key(original))
      }
      assertFalse(result.applied)
      assertTrue(backend.writes.isEmpty())
      assertEquals(external, backend.policy)
      assertNotNull(store.snapshot)
    }
  }

  @Test fun deferredOffCannotAdoptTheOriginalSnapshotPolicyAsANewManualBaseline() {
    val backend = Backend(2, original) // User chose Q while the no-write Off intent was pending.
    val store = Store(DndPolicySnapshot(1, original, 100, "session"))
    val own = FocusOwnedSession("session", "manual", 0, 100, phase = "active", appliedPolicyKey = key(active))
    val prepared = FocusChangeSafety.begin(FocusRestorationSafety.prepare(own), "restoring", 1, key(original),
      null, null, 100, "deferred-off", false)
    val noWrite = FocusRestorationSafety.afterAttempt(prepared, false)
    val retry = FocusChangeSafety.begin(FocusRestorationSafety.prepare(noWrite), "restoring", 1, key(original),
      backend.filter, key(backend.policy), 200, "retry-off", false)
    val result = DndPolicyCoordinator(backend, store).apply(DndRequest(false, ownerId = own.id, expectedFilter = 2)) {
      !FocusOwnershipSafety.baselineOverride(retry, false, backend.filter, key(backend.policy), key(original))
    }
    assertFalse(result.restored)
    assertTrue(backend.writes.isEmpty())
    assertEquals(2, backend.filter)
    assertEquals(original, backend.policy)
    assertNotNull(store.snapshot)
  }

  @Test fun throwingOffPolicyWriteRetainsThePartiallyUpdatedPolicyForARealRetry() {
    val partiallyUpdated = DndNotificationPolicy(16, 1, 1)
    val backend = Backend(2, partiallyUpdated).apply { failNextPolicyWrite = true }
    val store = Store(DndPolicySnapshot(1, original, 100, "session"))
    val own = FocusOwnedSession("session", "manual", 0, 100, phase = "updating",
      appliedPolicyKey = key(active), updatePolicyKey = key(partiallyUpdated))
    val writing = FocusChangeSafety.begin(FocusRestorationSafety.prepare(own), "restoring", 1, key(original),
      2, key(partiallyUpdated), 100, "off", false)
    val beforePolicy = key(backend.policy)
    val first = DndPolicyCoordinator(backend, store).apply(DndRequest(false, ownerId = own.id, expectedFilter = 2)) {
      !FocusOwnershipSafety.baselineOverride(writing, false, 2, key(backend.policy), key(original))
    }
    assertFalse(first.restored)
    assertTrue(first.policyWriteAttempted)
    assertEquals(partiallyUpdated, backend.policy) // Setter threw without mutation.
    val settled = FocusRestorationSafety.afterAttempt(writing, first.policyWriteAttempted, beforePolicy)
    assertFalse(FocusOwnershipSafety.baselineOverride(settled, false, 2, key(partiallyUpdated), key(original)))
    val retry = DndPolicyCoordinator(backend, store).apply(DndRequest(false, ownerId = own.id, expectedFilter = 2)) {
      backend.filter == 2 && backend.policy in listOf(partiallyUpdated, original)
    }
    assertTrue(retry.restored)
    assertEquals(original, backend.policy)
    assertEquals(1, backend.filter)
    assertNull(store.snapshot)
  }

  @Test fun verifiedOwnedBaselineCanStillRestoreItsOriginalPolicyAndClearItsSnapshot() {
    val backend = Backend(2, active)
    val store = Store(DndPolicySnapshot(1, original, 100, "session"))
    val own = FocusOwnedSession("session", "manual", 0, 100, phase = "active", appliedPolicyKey = key(active))
    val authorized = !FocusOwnershipSafety.baselineOverride(own, false, 2, key(active), key(original))
    val result = DndPolicyCoordinator(backend, store).apply(DndRequest(false, ownerId = own.id, expectedFilter = 2)) {
      authorized && backend.filter == 2 && backend.policy in listOf(active, original)
    }
    assertTrue(result.restored)
    assertEquals(listOf("policy", "filter"), backend.writes)
    assertEquals(1, backend.filter)
    assertEquals(original, backend.policy)
    assertNull(store.snapshot)
  }

  private class Store(var snapshot: DndPolicySnapshot?) : DndPolicySnapshotStore {
    override fun load() = snapshot
    override fun save(snapshot: DndPolicySnapshot): Boolean { this.snapshot = snapshot; return true }
    override fun clear(): Boolean { snapshot = null; return true }
  }

  private class Backend(var filter: Int, var policy: DndNotificationPolicy) : DndPolicyBackend {
    override val automaticZenRuleMode = DndPlatform.GLOBAL_INTERRUPTION_FILTER
    val writes = mutableListOf<String>()
    var failNextPolicyWrite = false
    override fun hasPolicyAccess() = true
    override fun readCurrentState() = DndPolicySnapshot(filter, policy, 100)
    override fun currentFilter() = filter
    override fun writePolicy(policy: DndNotificationPolicy) {
      writes += "policy"
      if (failNextPolicyWrite) { failNextPolicyWrite = false; error("policy setter unavailable") }
      this.policy = policy
    }
    override fun writeInterruptionFilter(filter: Int) { writes += "filter"; this.filter = filter }
  }
}

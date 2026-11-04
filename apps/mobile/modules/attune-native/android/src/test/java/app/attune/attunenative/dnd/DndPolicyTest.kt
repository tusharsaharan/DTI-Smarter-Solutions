package app.attune.attunenative.dnd

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun defaultFakeSnapshot() = DndPolicySnapshot(
  interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
  policy = DndNotificationPolicy(0, 0, 0),
  capturedAtMs = 0L
)

class DndPolicyTest {
  @Test
  fun deniedAccessDoesNotReadOrWritePolicy() {
    val backend = FakeBackend(hasAccess = false)
    val store = FakeStore()

    val result = DndPolicyCoordinator(backend, store, nowMs = { 10L }).apply(
      DndRequest(enabled = true)
    )

    assertFalse(result.applied)
    assertFalse(result.hasAccess)
    assertEquals("policy_access_required", result.reason)
    assertTrue(backend.writes.isEmpty())
    assertNull(store.snapshot)
  }

  @Test
  fun enablingCapturesExactStateAndDisablingRestoresIt() {
    val original = snapshot(
      filter = NotificationManager.INTERRUPTION_FILTER_ALL,
      categories = NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS,
      calls = NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS,
      messages = NotificationManager.Policy.PRIORITY_SENDERS_ANY,
      suppressedVisualEffects = 42,
      conversations = 7
    )
    val backend = FakeBackend(initial = original)
    val store = FakeStore()
    val coordinator = DndPolicyCoordinator(backend, store, nowMs = { 123L })

    val enabled = coordinator.apply(DndRequest(enabled = true))

    assertTrue(enabled.applied)
    assertTrue(enabled.snapshotCaptured)
    assertEquals(original.copy(capturedAtMs = 123L), store.snapshot)
    assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, backend.state.interruptionFilter)
    assertTrue(
      (backend.state.policy.priorityCategories and
        NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS) != 0
    )
    assertEquals(NotificationManager.Policy.PRIORITY_SENDERS_STARRED, backend.state.policy.priorityCallSenders)
    assertEquals(NotificationManager.Policy.PRIORITY_SENDERS_STARRED, backend.state.policy.priorityMessageSenders)

    val disabled = coordinator.apply(DndRequest(enabled = false))

    assertTrue(disabled.applied)
    assertTrue(disabled.restored)
    assertEquals("restored", disabled.reason)
    assertEquals(original, backend.state)
    assertNull(store.snapshot)
  }

  @Test
  fun disablingWithoutAnAttuneSnapshotLeavesUserDndUntouched() {
    val original = snapshot(filter = NotificationManager.INTERRUPTION_FILTER_ALARMS)
    val backend = FakeBackend(initial = original)
    val store = FakeStore()

    val result = DndPolicyCoordinator(backend, store).apply(DndRequest(enabled = false))

    assertFalse(result.applied)
    assertEquals("not_active_no_snapshot", result.reason)
    assertTrue(backend.writes.isEmpty())
    assertEquals(original, backend.state)
  }

  @Test
  fun partialWriteRollsBackAndReleasesNewSnapshot() {
    val original = snapshot(filter = NotificationManager.INTERRUPTION_FILTER_ALL)
    val backend = FakeBackend(initial = original, failNextFilterWrite = true)
    val store = FakeStore()

    val result = DndPolicyCoordinator(backend, store).apply(DndRequest(enabled = true))

    assertFalse(result.applied)
    assertEquals("policy_write_rejected", result.reason)
    assertTrue(result.rollbackAttempted)
    assertEquals(original, backend.state)
    assertNull(store.snapshot)
  }

  @Test
  fun policyMapperOnlyChangesAttuneManagedCategories() {
    val current = DndNotificationPolicy(
      priorityCategories = NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS or
        NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
        NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS,
      priorityCallSenders = NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS,
      priorityMessageSenders = NotificationManager.Policy.PRIORITY_SENDERS_ANY,
      suppressedVisualEffects = 9,
      priorityConversationSenders = 4
    )

    val mapped = FocusPolicyMapper.forRequest(
      current,
      DndRequest(enabled = true, allowPrioritySenders = false, allowRepeatCallers = false)
    )

    assertEquals(NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS, mapped.priorityCategories)
    assertEquals(NotificationManager.Policy.PRIORITY_SENDERS_ANY, mapped.priorityCallSenders)
    assertEquals(NotificationManager.Policy.PRIORITY_SENDERS_ANY, mapped.priorityMessageSenders)
    assertEquals(current.suppressedVisualEffects, mapped.suppressedVisualEffects)
    assertEquals(current.priorityConversationSenders, mapped.priorityConversationSenders)
  }

  @Test
  fun android15ModeExplainsPlatformManagedAutomaticZenRuleBehavior() {
    assertEquals(
      DndPlatform.PLATFORM_MANAGED_AUTOMATIC_ZEN_RULE,
      DndPlatform.modeForApi(35)
    )
    assertTrue(
      DndPlatform.limitationsForApi(35).any { it.contains("AutomaticZenRule") }
    )
    assertEquals(DndPlatform.GLOBAL_INTERRUPTION_FILTER, DndPlatform.modeFor(35, 34))
    assertEquals(
      DndPlatform.PLATFORM_MANAGED_AUTOMATIC_ZEN_RULE,
      DndPlatform.modeFor(35, 35)
    )
    assertTrue(DndPlatform.limitationsForApi(34).isEmpty())
  }

  private val owner = "6685f38d-281c-4a3d-a007-df3c461054ef"
  private val otherOwner = "6685f38d-281c-4a3d-a007-df3c461054e0"

  private fun ownedRequest(enabled: Boolean = true, filter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
    ownerId: String = owner) = DndRequest(enabled = enabled, ownerId = ownerId, expectedFilter = filter)

  @Test fun ownedAcquisitionAndRestorationUseExactUuid() {
    val backend = FakeBackend()
    val store = FakeStore()
    val coordinator = DndPolicyCoordinator(backend, store, nowMs = { 123L })
    val enabled = coordinator.apply(ownedRequest())
    assertTrue(enabled.applied)
    assertTrue(enabled.snapshotCaptured)
    assertEquals(owner, store.snapshot?.ownerId)
    assertEquals(123L, store.snapshot?.capturedAtMs)
    val restored = coordinator.apply(ownedRequest(false, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
    assertTrue(restored.restored)
    assertFalse(restored.recoveryRequired)
    assertNull(store.snapshot)
    assertEquals(1, store.saveCount)
    assertEquals(1, store.clearCount)
  }

  @Test fun ownedRequestsNeverReuseOrClearLegacyOrAnotherUuid() {
    for (snapshotOwner in listOf(null, otherOwner)) for (enabled in listOf(true, false)) {
      val original = defaultFakeSnapshot().copy(ownerId = snapshotOwner)
      val backend = FakeBackend()
      val store = FakeStore(original)
      val result = DndPolicyCoordinator(backend, store).apply(ownedRequest(enabled))
      assertFalse(result.applied)
      assertEquals("policy_snapshot_owner_mismatch", result.reason)
      assertTrue(result.snapshotPresent)
      assertFalse(result.recoveryRequired)
      assertEquals(original, store.snapshot)
      assertEquals(0, store.saveCount)
      assertEquals(0, store.clearCount)
      assertTrue(backend.writes.isEmpty())
    }
  }

  @Test fun omittingOwnerCannotBypassOwnedSnapshotIdentity() {
    val store = FakeStore(defaultFakeSnapshot().copy(ownerId = owner))
    val backend = FakeBackend()
    for (enabled in listOf(true, false)) {
      val result = DndPolicyCoordinator(backend, store).apply(DndRequest(enabled))
      assertEquals("policy_snapshot_owner_mismatch", result.reason)
      assertFalse(result.applied)
    }
    assertEquals(owner, store.snapshot?.ownerId)
    assertTrue(backend.writes.isEmpty())
    assertEquals(0, store.clearCount)
  }

  @Test fun newOwnedAcquisitionRequiresAllAndOwnedRequestsRequireExpectedFilter() {
    val backend = FakeBackend()
    val store = FakeStore()
    val coordinator = DndPolicyCoordinator(backend, store)
    assertEquals("policy_expected_filter_required", coordinator.apply(DndRequest(true, ownerId = owner)).reason)
    assertEquals("policy_acquisition_requires_all", coordinator.apply(ownedRequest(filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY)).reason)
    assertEquals("policy_expected_filter_invalid", coordinator.apply(ownedRequest(filter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN)).reason)
    assertTrue(backend.writes.isEmpty())
    assertEquals(0, store.saveCount)
  }

  @Test fun slowSnapshotStorageCannotOverwriteAnExternalFilter() {
    val backend = FakeBackend()
    val store = FakeStore(onSave = { backend.state = backend.state.copy(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE) })
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest())
    assertEquals("policy_filter_changed", result.reason)
    assertTrue(result.snapshotCaptured)
    assertFalse(result.snapshotPresent)
    assertFalse(result.recoveryRequired)
    assertFalse(result.rollbackAttempted)
    assertTrue(backend.writes.isEmpty())
    assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, backend.state.interruptionFilter)
    assertNull(store.snapshot)
  }

  @Test fun expiryDuringDurablePreparationAbortsAndCleansOnlyNewSnapshot() {
    var live = true
    val backend = FakeBackend()
    val store = FakeStore(onSave = { live = false })
    var guardCalls = 0
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest()) {
      guardCalls++
      assertEquals(owner, store.snapshot?.ownerId) // Guard runs after durable preparation.
      live
    }
    assertEquals("policy_write_guard_rejected", result.reason)
    assertEquals(1, guardCalls)
    assertTrue(result.snapshotCaptured)
    assertFalse(result.policyWriteAttempted)
    assertFalse(result.filterWriteAttempted)
    assertFalse(result.recoveryRequired)
    assertTrue(backend.writes.isEmpty())
    assertNull(store.snapshot)
  }

  @Test fun throwingGuardFailsClosedWithoutSystemWrites() {
    val backend = FakeBackend()
    val store = FakeStore()
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest()) { error("expired") }
    assertEquals("policy_write_guard_rejected", result.reason)
    assertFalse(result.applied)
    assertTrue(backend.writes.isEmpty())
    assertNull(store.snapshot)
  }

  @Test fun filterIsRecheckedAfterBlockingGuard() {
    val backend = FakeBackend()
    val store = FakeStore()
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest()) {
      backend.state = backend.state.copy(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS)
      true
    }
    assertEquals("policy_filter_changed", result.reason)
    assertTrue(backend.writes.isEmpty())
    assertNull(store.snapshot)
    assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, backend.state.interruptionFilter)
  }

  @Test fun permissionRevocationDuringStorageBlocksAllSystemWrites() {
    val backend = FakeBackend()
    val store = FakeStore(onSave = { backend.hasAccess = false })
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest())
    assertEquals("policy_access_required", result.reason)
    assertFalse(result.hasAccess)
    assertTrue(backend.writes.isEmpty())
    assertNull(store.snapshot)
  }

  @Test fun policyChangeDuringPreparationIsNotSilentlyRestoredLater() {
    val backend = FakeBackend()
    val edited = DndNotificationPolicy(NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS, 1, 1)
    val store = FakeStore(onSave = { backend.state = backend.state.copy(policy = edited) })
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest())
    assertEquals("policy_state_changed", result.reason)
    assertEquals(edited, backend.state.policy)
    assertTrue(backend.writes.isEmpty())
    assertNull(store.snapshot)
  }

  @Test fun filterStageGuardFailureDoesNotRunFullRollback() {
    var live = true
    val backend = FakeBackend(afterPolicyWrite = { live = false })
    val store = FakeStore()
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest()) { live }
    assertFalse(result.applied)
    assertEquals("policy_write_guard_rejected", result.reason)
    assertTrue(result.policyWriteAttempted)
    assertFalse(result.filterWriteAttempted)
    assertFalse(result.rollbackAttempted)
    assertTrue(result.recoveryRequired)
    assertTrue(result.snapshotPresent)
    assertEquals(owner, store.snapshot?.ownerId)
    assertEquals(listOf("policy"), backend.writes)
    assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, backend.state.interruptionFilter)
  }

  @Test fun externalFilterChangeAfterPolicyWritePreservesFilterAndRecoveryProof() {
    val backend = FakeBackend()
    backend.afterPolicyWrite = { backend.state = backend.state.copy(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE) }
    val store = FakeStore()
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest())
    assertEquals("policy_filter_changed", result.reason)
    assertEquals(listOf("policy"), backend.writes)
    assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, backend.state.interruptionFilter)
    assertFalse(result.rollbackAttempted)
    assertTrue(result.recoveryRequired)
    assertTrue(result.snapshotPresent)
  }

  @Test fun restoreGuardFailureBeforeWritesRetainsActiveOwnershipWithoutPartialWrite() {
    val backend = FakeBackend(initial = snapshot(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
    val original = defaultFakeSnapshot().copy(ownerId = owner)
    val store = FakeStore(original)
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest(false, NotificationManager.INTERRUPTION_FILTER_PRIORITY)) { false }
    assertEquals("policy_write_guard_rejected", result.reason)
    assertFalse(result.restored)
    assertFalse(result.recoveryRequired)
    assertTrue(result.snapshotPresent)
    assertEquals(original, store.snapshot)
    assertTrue(backend.writes.isEmpty())
  }

  @Test fun restoreFilterStageGuardFailureKeepsSnapshotAndDoesNotForceAll() {
    var live = true
    val backend = FakeBackend(initial = snapshot(NotificationManager.INTERRUPTION_FILTER_PRIORITY), afterPolicyWrite = { live = false })
    val original = defaultFakeSnapshot().copy(ownerId = owner)
    val store = FakeStore(original)
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest(false, NotificationManager.INTERRUPTION_FILTER_PRIORITY)) { live }
    assertEquals("policy_write_guard_rejected", result.reason)
    assertFalse(result.restored)
    assertTrue(result.recoveryRequired)
    assertEquals(listOf("policy"), backend.writes)
    assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, backend.state.interruptionFilter)
    assertEquals(original, store.snapshot)
  }

  @Test fun failedManualUpdateRollsBackActualActiveStateNotOriginalAllSnapshot() {
    val backend = FakeBackend(initial = snapshot(NotificationManager.INTERRUPTION_FILTER_ALL,
      categories = NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS))
    val store = FakeStore()
    val coordinator = DndPolicyCoordinator(backend, store)
    assertTrue(coordinator.apply(ownedRequest()).applied)
    val beforeUpdate = backend.state
    val originalSnapshot = store.snapshot
    backend.failNextFilterWrite = true
    val result = coordinator.apply(DndRequest(true, false, false, owner, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
    assertEquals("policy_write_rejected", result.reason)
    assertTrue(result.rollbackAttempted)
    assertTrue(result.rollbackSucceeded)
    assertFalse(result.recoveryRequired)
    assertEquals(beforeUpdate, backend.state)
    assertEquals(originalSnapshot, store.snapshot)
    assertEquals(1, store.saveCount)
    assertEquals(0, store.clearCount)
    assertTrue(backend.filterWrites.all { it == NotificationManager.INTERRUPTION_FILTER_PRIORITY })
  }

  @Test fun callbackCanCheckLiveAuthorizationAfterOwnPolicyWriteWithoutOldPolicyKey() {
    val backend = FakeBackend()
    val store = FakeStore()
    val policiesSeen = mutableListOf<DndNotificationPolicy>()
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest()) {
      policiesSeen += backend.state.policy
      true // Caller checks clocks/visibility, not equality with its stale policy key.
    }
    assertTrue(result.applied)
    assertEquals(2, policiesSeen.size)
    assertFalse(policiesSeen.first() == policiesSeen.last())
    assertTrue(result.policyWriteAttempted)
    assertTrue(result.filterWriteAttempted)
    assertFalse(result.recoveryRequired)
  }

  @Test fun writeExceptionAfterGuardExpiryDoesNotAuthorizeRollback() {
    var live = true
    val backend = FakeBackend(failNextFilterWrite = true, beforeFilterWrite = { live = false })
    val store = FakeStore()
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest()) { live }
    assertEquals("policy_write_rejected", result.reason)
    assertFalse(result.rollbackAttempted)
    assertFalse(result.rollbackSucceeded)
    assertTrue(result.filterWriteAttempted)
    assertTrue(result.recoveryRequired)
    assertEquals(listOf("policy", "filter"), backend.writes)
    assertEquals(owner, store.snapshot?.ownerId)
  }

  @Test fun throwingFilterWriteThatChangesFilterCannotForceRollbackOverIt() {
    val backend = FakeBackend(failNextFilterWrite = true)
    backend.beforeFilterWrite = {
      backend.state = backend.state.copy(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }
    val store = FakeStore()
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest())
    assertFalse(result.rollbackAttempted)
    assertTrue(result.recoveryRequired)
    assertEquals(listOf("policy", "filter"), backend.writes)
    assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, backend.state.interruptionFilter)
    assertTrue(result.snapshotPresent)
  }

  @Test fun rollbackRechecksGuardBeforeItsFilterWrite() {
    var live = true
    var policyWrites = 0
    val backend = FakeBackend(failNextFilterWrite = true, afterPolicyWrite = {
      policyWrites++
      if (policyWrites == 2) live = false
    })
    val store = FakeStore()
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest()) { live }
    assertTrue(result.rollbackAttempted)
    assertFalse(result.rollbackSucceeded)
    assertTrue(result.recoveryRequired)
    assertEquals(listOf("policy", "filter", "policy"), backend.writes)
    assertTrue(result.snapshotPresent)
  }

  @Test fun failedCleanupAfterExpiryRetainsExactRecoveryProof() {
    val backend = FakeBackend()
    val store = FakeStore(clearSucceeds = false)
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest()) { false }
    assertFalse(result.applied)
    assertFalse(result.policyWriteAttempted)
    assertTrue(result.recoveryRequired)
    assertTrue(result.snapshotPresent)
    assertEquals(owner, store.snapshot?.ownerId)
    assertTrue(backend.writes.isEmpty())
  }

  @Test fun snapshotReplacementDuringPreparationCannotBeReusedOrCleared() {
    val backend = FakeBackend()
    val store = FakeStore()
    val unrelated = defaultFakeSnapshot().copy(ownerId = otherOwner)
    store.onSave = { store.snapshot = unrelated }
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest())
    assertEquals("policy_snapshot_owner_mismatch", result.reason)
    assertFalse(result.applied)
    assertFalse(result.recoveryRequired)
    assertEquals(unrelated, store.snapshot)
    assertEquals(0, store.clearCount)
    assertTrue(backend.writes.isEmpty())
  }

  @Test fun restorationCleanupDoesNotDeleteReplacementOwner() {
    val backend = FakeBackend(initial = snapshot(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
    val store = FakeStore(defaultFakeSnapshot().copy(ownerId = owner))
    val unrelated = defaultFakeSnapshot().copy(ownerId = otherOwner)
    backend.afterFilterWrite = { store.snapshot = unrelated }
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest(false, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
    assertTrue(result.restored)
    assertEquals("restored_snapshot_but_clear_failed", result.reason)
    assertTrue(result.snapshotPresent)
    assertTrue(result.recoveryRequired)
    assertEquals(unrelated, store.snapshot)
    assertEquals(0, store.clearCount)
  }

  @Test fun unreadableSnapshotStoreCannotAuthorizeNewOwnedAcquisition() {
    val backend = FakeBackend()
    val store = FakeStore(loadFails = true)
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest())
    assertEquals("policy_snapshot_unavailable", result.reason)
    assertFalse(result.applied)
    assertEquals(0, store.saveCount)
    assertTrue(backend.writes.isEmpty())
  }

  @Test fun recoveryResultFlagsAreExposedToNativeCaller() {
    val backend = FakeBackend()
    var live = true
    backend.afterPolicyWrite = { live = false }
    val result = DndPolicyCoordinator(backend, FakeStore()).apply(ownedRequest()) { live }
    val wire = result.toMap()
    assertEquals(true, wire["policyWriteAttempted"])
    assertEquals(false, wire["filterWriteAttempted"])
    assertEquals(false, wire["rollbackSucceeded"])
    assertEquals(true, wire["recoveryRequired"])
  }

  @Test fun failedSnapshotCommitNeverWritesSystemAndReportsFailedCleanup() {
    for (canClear in listOf(true, false)) {
      val backend = FakeBackend()
      val store = FakeStore(clearSucceeds = canClear, saveSucceeds = false)
      val result = DndPolicyCoordinator(backend, store).apply(ownedRequest())
      assertEquals("policy_snapshot_persist_failed", result.reason)
      assertFalse(result.snapshotCaptured) // No durable preparation was acknowledged.
      assertFalse(result.policyWriteAttempted)
      assertEquals(!canClear, result.snapshotPresent)
      assertEquals(!canClear, result.recoveryRequired)
      assertTrue(backend.writes.isEmpty())
    }
  }

  @Test fun expiredManualUpdateBeforeWritesPreservesItsExistingActiveSession() {
    val backend = FakeBackend()
    val store = FakeStore()
    val coordinator = DndPolicyCoordinator(backend, store)
    assertTrue(coordinator.apply(ownedRequest()).applied)
    val beforeUpdate = backend.state
    val originalSnapshot = store.snapshot
    backend.writes.clear()
    val result = coordinator.apply(ownedRequest(filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY)) { false }
    assertEquals("policy_write_guard_rejected", result.reason)
    assertFalse(result.snapshotCaptured)
    assertFalse(result.recoveryRequired)
    assertTrue(result.snapshotPresent)
    assertEquals(beforeUpdate, backend.state)
    assertEquals(originalSnapshot, store.snapshot)
    assertEquals(0, store.clearCount)
    assertTrue(backend.writes.isEmpty())
  }

  @Test fun restorationExpectedFilterMismatchPreservesExternalDndAndSnapshot() {
    val backend = FakeBackend(initial = snapshot(NotificationManager.INTERRUPTION_FILTER_NONE))
    val original = defaultFakeSnapshot().copy(ownerId = owner)
    val store = FakeStore(original)
    val result = DndPolicyCoordinator(backend, store).apply(ownedRequest(false, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
    assertEquals("policy_filter_changed", result.reason)
    assertFalse(result.restored)
    assertFalse(result.recoveryRequired)
    assertEquals(original, store.snapshot)
    assertTrue(backend.writes.isEmpty())
    assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, backend.state.interruptionFilter)
  }

  private fun snapshot(
    filter: Int,
    categories: Int = 0,
    calls: Int = NotificationManager.Policy.PRIORITY_SENDERS_ANY,
    messages: Int = NotificationManager.Policy.PRIORITY_SENDERS_ANY,
    suppressedVisualEffects: Int = 0,
    conversations: Int = 0
  ) = DndPolicySnapshot(
    interruptionFilter = filter,
    policy = DndNotificationPolicy(
      priorityCategories = categories,
      priorityCallSenders = calls,
      priorityMessageSenders = messages,
      suppressedVisualEffects = suppressedVisualEffects,
      priorityConversationSenders = conversations
    ),
    capturedAtMs = 1L
  )

  private class FakeStore(var snapshot: DndPolicySnapshot? = null,
    var onSave: (() -> Unit)? = null, private val clearSucceeds: Boolean = true,
    private val loadFails: Boolean = false, private val saveSucceeds: Boolean = true) : DndPolicySnapshotStore {
    var saveCount = 0
    var clearCount = 0
    override fun load(): DndPolicySnapshot? {
      if (loadFails) error("unavailable")
      return snapshot
    }
    override fun save(snapshot: DndPolicySnapshot): Boolean {
      saveCount++
      this.snapshot = snapshot
      onSave?.invoke()
      return saveSucceeds
    }
    override fun clear(): Boolean {
      clearCount++
      if (!clearSucceeds) return false
      snapshot = null
      return true
    }
  }

  private class FakeBackend(
    private val initial: DndPolicySnapshot = defaultFakeSnapshot(),
    var hasAccess: Boolean = true,
    var failNextFilterWrite: Boolean = false,
    var afterPolicyWrite: (() -> Unit)? = null,
    var beforeFilterWrite: ((Int) -> Unit)? = null,
    var afterFilterWrite: (() -> Unit)? = null
  ) : DndPolicyBackend {
    override val automaticZenRuleMode: String = DndPlatform.GLOBAL_INTERRUPTION_FILTER
    var state: DndPolicySnapshot = initial
    val writes = mutableListOf<String>()
    val filterWrites = mutableListOf<Int>()

    override fun hasPolicyAccess(): Boolean = hasAccess
    override fun readCurrentState(): DndPolicySnapshot = state
    override fun writePolicy(policy: DndNotificationPolicy) {
      writes += "policy"
      state = state.copy(policy = policy)
      afterPolicyWrite?.invoke()
    }
    override fun writeInterruptionFilter(filter: Int) {
      writes += "filter"
      filterWrites += filter
      beforeFilterWrite?.invoke(filter)
      if (failNextFilterWrite) {
        failNextFilterWrite = false
        throw IllegalStateException("simulated filter write failure")
      }
      state = state.copy(interruptionFilter = filter)
      afterFilterWrite?.invoke()
    }
    override fun currentFilter(): Int = state.interruptionFilter
  }
}

package app.attune.attunenative.focus

import org.junit.Assert.*
import org.junit.Test

class FocusTransactionSafetyTest {
  private val own = FocusOwnedSession("session", "manual", 0, 100,
    appliedPolicyKey = "before", updatePolicyKey = "after", phase = "updating")

  @Test fun explicitOffDoesNotDependOnFeedbackPersistence() {
    assertEquals(FocusManualAction.END_OWNED, FocusManualSafety.action(false, false))
    assertEquals(FocusManualAction.END_OWNED, FocusManualSafety.action(false, true))
    assertEquals(FocusManualAction.BLOCK_START, FocusManualSafety.action(true, false))
    assertEquals(FocusManualAction.START_IF_SAVED, FocusManualSafety.action(true, true))
  }

  @Test fun deferredOrRejectedOffRetainsItsOriginalAuthorizationNotItsNewRestorationTarget() {
    for (phase in listOf("active", "updating")) {
      val original = own.copy(phase = phase)
      val deferred = FocusChangeSafety.begin(FocusRestorationSafety.prepare(original), "restoring", 1, "original",
        null, null, 100, "off", false)
      val noWrite = FocusRestorationSafety.afterAttempt(deferred, false)
      assertEquals("restoring", noWrite.phase) // Off remains retriable, including an in-memory-only intent.
      assertEquals(phase, noWrite.restoreAuthorization?.phase)
      assertEquals("before", noWrite.restoreAuthorization?.appliedPolicyKey)
      assertEquals("after", noWrite.restoreAuthorization?.updatePolicyKey)
      assertTrue(FocusOwnershipSafety.baselineOverride(noWrite, false, 2, "original", "original"))
      assertTrue(FocusOwnershipSafety.baselineOverride(noWrite, false, 1, "before", "original"))
      val retry = FocusChangeSafety.begin(FocusRestorationSafety.prepare(noWrite), "restoring", 1, "original",
        2, "before", 200, "retry-off", false)
      assertEquals(noWrite.restoreAuthorization, retry.restoreAuthorization)
      assertTrue(FocusOwnershipSafety.baselineOverride(retry, false, 2, "original", "original"))
    }
  }

  @Test fun actualRestorationWriteAttemptAllowsOnlyKnownPartialRecoveryStates() {
    val writing = FocusChangeSafety.begin(FocusRestorationSafety.prepare(own), "restoring", 1, "original",
      2, "before", 100, "off", false)
    val attempted = FocusRestorationSafety.afterAttempt(writing, true)
    assertNull(attempted.restoreAuthorization)
    assertFalse(FocusOwnershipSafety.baselineOverride(attempted, false, 2, "original", "original"))
    assertFalse(FocusOwnershipSafety.baselineOverride(attempted, false, 1, "original", "original"))
    assertTrue(FocusOwnershipSafety.baselineOverride(attempted, false, 2, "external", "original"))
    assertTrue(FocusOwnershipSafety.baselineOverride(attempted, false, 3, "original", "original"))
  }

  @Test fun attemptedOffRetainsTheVerifiedPolicyOfAPartiallyUpdatedManualSession() {
    val writing = FocusChangeSafety.begin(FocusRestorationSafety.prepare(own), "restoring", 1, "original",
      2, "after", 100, "off", false)
    val attempted = FocusRestorationSafety.afterAttempt(writing, true, "after")
    assertNull(attempted.restoreAuthorization)
    assertEquals("after", attempted.appliedPolicyKey)
    assertEquals("original", attempted.updatePolicyKey)
    assertFalse(FocusOwnershipSafety.baselineOverride(attempted, false, 2, "after", "original"))
    assertFalse(FocusOwnershipSafety.baselineOverride(attempted, false, 2, "original", "original"))
    assertTrue(FocusOwnershipSafety.baselineOverride(attempted, false, 2, "external", "original"))
  }

  @Test fun cleanRejectedOrRolledBackExceptionEditRetainsActiveManualDnd() {
    val next = FocusManualSafety.afterUpdate(own, false, false, "policy_write_rejected")
    assertEquals("active", next.phase)
    assertEquals("manual", next.origin)
    assertEquals("before", next.appliedPolicyKey)
    assertTrue(next.policyUpdatePending)
    assertEquals("policy_write_rejected", next.policyUpdateReason)
  }

  @Test fun failedRepairUsesItsVerifiedStartingPolicyNotThePreFailurePolicy() {
    val repairing = own.copy(appliedPolicyKey = "before-first-attempt", updatePolicyKey = "after")
    val next = FocusManualSafety.afterUpdate(repairing, false, false, "policy_write_rejected", "after")
    assertEquals("active", next.phase)
    assertEquals("after", next.appliedPolicyKey)
    assertTrue(next.policyUpdatePending)
    assertFalse(FocusOwnershipSafety.policyOverride(next.phase, false, next.appliedPolicyKey, "after", next.updatePolicyKey))
    assertTrue(FocusOwnershipSafety.policyOverride(next.phase, false, next.appliedPolicyKey, "external", next.updatePolicyKey))
  }

  @Test fun uncertainRepairKeepsTheRequestedEditAndOriginalSessionIdentity() {
    val next = FocusManualSafety.afterUpdate(own, false, true, "policy_write_guard_rejected", "after")
    assertEquals("updating", next.phase)
    assertEquals(own.id, next.id)
    assertEquals(own.snapshotAtMs, next.snapshotAtMs)
    assertEquals(own.origin, next.origin)
    assertEquals("after", next.updatePolicyKey)
    assertTrue(next.policyUpdatePending)
  }

  @Test fun uncertainExceptionEditRecoversTheUpdateRatherThanEndingManualDnd() {
    val next = FocusManualSafety.afterUpdate(own, false, true, "policy_write_guard_rejected")
    assertEquals("updating", next.phase)
    assertNotEquals("restoring", next.phase)
    assertEquals("before", next.appliedPolicyKey)
    assertTrue(next.policyUpdatePending)
    val success = FocusManualSafety.afterUpdate(next, true, false, "applied")
    assertEquals("active", success.phase)
    assertEquals("after", success.appliedPolicyKey)
    assertFalse(success.policyUpdatePending)
  }

  @Test fun noOpUpdateCannotEraseDelayedAcquisitionReceipts() {
    val acquisition = FocusChangeSafety.begin(own, "prepared", 2, "before", 1, "original", 100, "acquire", false)
    val active = acquisition.copy(phase = "active")
    val noOp = FocusChangeSafety.begin(active, "updating", 2, "before", 2, "before", 200, "update", false)
    assertEquals(acquisition.receipts, noOp.receipts)
    assertTrue(FocusChangeSafety.receiptIndex(noOp, false, "2", 250) >= 0)
    assertTrue(FocusChangeSafety.receiptIndex(noOp, true, "before", 250) >= 0)
  }

  @Test fun laterTransactionsKeepIndependentOutstandingPolicyReceipts() {
    val first = FocusChangeSafety.begin(own, "active", 2, "first", 2, "before", 100, "first-write", false)
    val second = FocusChangeSafety.begin(first, "updating", 2, "second", 2, "first", 200, "second-write", false)
    assertTrue(FocusChangeSafety.receiptIndex(second, true, "first", 250) >= 0)
    assertTrue(FocusChangeSafety.receiptIndex(second, true, "second", 250) >= 0)
    assertEquals(-1, FocusChangeSafety.receiptIndex(second, false, "second", 250))
  }

  @Test fun queuedBroadcastKeepsItsValidReceiptAfterProcessingTimeExpiry() {
    val first = FocusChangeSafety.begin(own, "prepared", 2, "first", 1, "before", 100, "first-write", false)
    val capturedWithinTtl = 30_100L
    val later = FocusChangeSafety.begin(first, "updating", 2, "second", 2, "first", 40_100, "update", false)
    val settled = FocusChangeSafety.settle(later, true, false, false, "first", false, 40_200)
    assertTrue(FocusChangeSafety.receiptIndex(settled, false, "2", capturedWithinTtl) >= 0)
    assertTrue(FocusChangeSafety.receiptIndex(settled, true, "first", capturedWithinTtl) >= 0)
    assertEquals(-1, FocusChangeSafety.receiptIndex(settled, true, "first", capturedWithinTtl + 1))
  }

  @Test fun retainedQueuedReceiptsRemainBoundedWithoutExtendingTheirAttributionWindow() {
    var ledger = own.copy(receipts = emptyList())
    repeat(100) { index ->
      ledger = FocusChangeSafety.begin(ledger, "updating", 2, "policy-$index", 2, "before",
        100L + index * 40_000L, "transaction-$index", false)
    }
    assertEquals(FocusChangeSafety.MAX_RECEIPTS, ledger.receipts.orEmpty().size)
    assertTrue(ledger.receipts.orEmpty().all { it.untilMs - it.notBeforeMs == FocusChangeSafety.RECEIPT_TTL_MS })
    val oldest = ledger.receipts.orEmpty().first()
    assertTrue(FocusChangeSafety.receiptIndex(ledger, true, oldest.value, oldest.untilMs) >= 0)
    assertEquals(-1, FocusChangeSafety.receiptIndex(ledger, true, oldest.value, oldest.untilMs + 1))
  }

  @Test fun unknownFilterBroadcastIsUnavailableNotAnExternalOverride() {
    assertFalse(FocusChangeSafety.observationAvailable(false, null))
    assertFalse(FocusChangeSafety.observationAvailable(false, "0"))
    assertFalse(FocusChangeSafety.observationAvailable(false, "unreadable"))
    for (filter in listOf("1", "2", "3", "4")) assertTrue(FocusChangeSafety.observationAvailable(false, filter))
    assertFalse(FocusChangeSafety.observationAvailable(true, null))
    assertTrue(FocusChangeSafety.observationAvailable(true, "0,0,0,0,0"))
  }

  @Test fun abortedWritesRemoveOnlyTheirOwnUnattemptedReceipts() {
    val first = FocusChangeSafety.begin(own, "prepared", 2, "first", 1, "before", 100, "first-write", false)
    val update = FocusChangeSafety.begin(first, "updating", 2, "second", 2, "first", 200, "update", false)
    val abort = FocusChangeSafety.settle(update, false, false, false, "first", false)
    assertEquals(first.receipts, abort.receipts)
    val partial = FocusChangeSafety.settle(first, true, false, false, "before", false)
    assertEquals(-1, FocusChangeSafety.receiptIndex(partial, false, "2", 250))
    assertTrue(FocusChangeSafety.receiptIndex(partial, true, "first", 250) >= 0)
  }

  @Test fun rollbackPolicyChangesHaveTheirOwnReceipt() {
    val writing = FocusChangeSafety.begin(own, "updating", 2, "after", 2, "before", 100, "update", false)
    val rolledBack = FocusChangeSafety.settle(writing, true, true, true, "before", false)
    assertTrue(FocusChangeSafety.receiptIndex(rolledBack, true, "after", 250) >= 0)
    assertTrue(FocusChangeSafety.receiptIndex(rolledBack, true, "before", 250) >= 0)
    assertEquals(-1, FocusChangeSafety.receiptIndex(rolledBack, false, "2", 250))
  }

  @Test fun expiredPreWriteAndUnmatchedExternalReceiptsNeverConfirmOwnership() {
    val writing = FocusChangeSafety.begin(own, "prepared", 2, "after", 1, "before", 100, "write", false)
    assertEquals(-1, FocusChangeSafety.receiptIndex(writing, false, "2", 99))
    assertEquals(-1, FocusChangeSafety.receiptIndex(writing, false, "2", 30_101))
    assertEquals(-1, FocusChangeSafety.receiptIndex(writing, false, "1", 250))
    val implicit = FocusChangeSafety.begin(own, "active", 2, "after", 1, "before", 100, "write", true)
    assertTrue(implicit.receipts.orEmpty().isEmpty())
  }

  @Test fun slowPreparationCannotAuthorizeEvenAManualWrite() {
    val writing = FocusChangeSafety.begin(own, "updating", 2, "after", 2, "before", 100, "update", false)
    assertFalse(FocusChangeSafety.writeWindowOpen(writing, 99))
    assertTrue(FocusChangeSafety.writeWindowOpen(writing, 100))
    assertTrue(FocusChangeSafety.writeWindowOpen(writing, 30_100))
    assertFalse(FocusChangeSafety.writeWindowOpen(writing, 30_101))
    assertFalse(FocusChangeSafety.writeWindowOpen(writing, 120_100))
  }

  @Test fun attemptedReceiptsRemainAvailableForThirtySecondsAfterSettlement() {
    val writing = FocusChangeSafety.begin(own, "prepared", 2, "after", 1, "before", 100, "write", false)
    val settled = FocusChangeSafety.settle(writing, true, true, false, "before", false, 25_100)
    assertTrue(FocusChangeSafety.receiptIndex(settled, false, "2", 55_100) >= 0)
    assertTrue(FocusChangeSafety.receiptIndex(settled, true, "after", 55_100) >= 0)
    assertEquals(-1, FocusChangeSafety.receiptIndex(settled, false, "2", 55_101))
    assertEquals(-1, FocusChangeSafety.receiptIndex(settled, true, "after", 55_101))
  }

  @Test fun receiptSettlementCannotExtendOlderTransactionsOrOutliveMaximumSpan() {
    val first = FocusChangeSafety.begin(own, "prepared", 2, "first", 1, "before", 100, "first", false)
    val update = FocusChangeSafety.begin(first, "updating", 2, "second", 2, "first", 200, "update", false)
    val settled = FocusChangeSafety.settle(update, true, false, true, "first", false, 115_200)
    assertEquals(first.receipts, settled.receipts.orEmpty().filter { it.transactionId == "first" })
    val current = settled.receipts.orEmpty().filter { it.transactionId == "update" }
    assertEquals(2, current.size)
    assertTrue(current.all { it.untilMs == 120_200L })
    assertTrue(FocusChangeSafety.receiptIndex(settled, true, "second", 120_200) >= 0)
    assertTrue(FocusChangeSafety.receiptIndex(settled, true, "first", 120_200) >= 0)
    assertEquals(-1, FocusChangeSafety.receiptIndex(settled, true, "first", 120_201))
  }
}

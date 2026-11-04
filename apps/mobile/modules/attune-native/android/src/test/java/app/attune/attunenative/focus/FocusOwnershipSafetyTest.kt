package app.attune.attunenative.focus

import org.junit.Assert.*
import org.junit.Test

class FocusOwnershipSafetyTest {
  @Test fun activeOwnershipRequiresExactSnapshotIdentityNotGlobalFilter() {
    assertTrue(FocusOwnershipSafety.matches(100_000, 100_100, "active", 100_100, 1))
    assertFalse(FocusOwnershipSafety.matches(100_000, 100_100, "active", 100_101, 1))
    assertFalse(FocusOwnershipSafety.matches(100_000, 100_100, "active", null, 1))
    assertFalse(FocusOwnershipSafety.matches(100_000, 100_100, "active", 100_100, 2))
  }

  @Test fun crashRecoveryOnlyAcceptsBoundedWriteAheadCaptureWindow() {
    assertTrue(FocusOwnershipSafety.matches(100_000, 0, "prepared", 100_100, 1))
    assertFalse(FocusOwnershipSafety.matches(100_000, 0, "prepared", 99_999, 1))
    assertFalse(FocusOwnershipSafety.matches(100_000, 0, "prepared", 105_001, 1))
    assertFalse(FocusOwnershipSafety.matches(100_000, 0, "active", 100_100, 1))
  }

  @Test fun missingPermissionFilterDoesNotDiscardOwnedRestorationEvidence() {
    assertFalse(FocusOwnershipSafety.userOverride("active", false, null))
    assertFalse(FocusOwnershipSafety.userOverride("active", false, 0))
  }

  @Test fun unavailablePreflightEvidenceDoesNotInventAnExternalOverrideInAnyPhase() {
    for (phase in listOf("prepared", "active", "updating", "restoring")) {
      assertFalse(FocusOwnershipSafety.userOverride(phase, false, null))
      assertFalse(FocusOwnershipSafety.userOverride(phase, false, 0))
      assertFalse(FocusOwnershipSafety.policyOverride(phase, false, "before", null, "after", "original"))
    }
  }

  @Test fun capturedExternalBaselineCannotBeAdoptedForRestorationOrExceptionUpdate() {
    for (phase in listOf("active", "updating", "prepared", "restoring")) {
      val own = FocusOwnedSession("session", "manual", 0, 100, phase = phase,
        appliedPolicyKey = "before", updatePolicyKey = "after")
      assertFalse(FocusOwnershipSafety.baselineOverride(own, false, 2, "before", "original"))
      assertTrue(FocusOwnershipSafety.baselineOverride(own, false, 3, "before", "original"))
      assertTrue(FocusOwnershipSafety.baselineOverride(own, false, 4, "before", "original"))
      assertTrue(FocusOwnershipSafety.baselineOverride(own, false, 2, "external", "original"))
      assertFalse(FocusOwnershipSafety.baselineOverride(own, true, 3, "external", "original"))
    }
  }

  @Test fun baselineValidationUsesOriginalPhaseBeforeAnOffIntentChangesIt() {
    val active = FocusOwnedSession("session", "manual", 0, 100, phase = "active", appliedPolicyKey = "before")
    assertTrue(FocusOwnershipSafety.baselineOverride(active, false, 1, "before", "original"))
    assertTrue(FocusOwnershipSafety.baselineOverride(active, false, 2, "original", "original"))
    assertFalse(FocusOwnershipSafety.baselineOverride(active.copy(phase = "restoring"), false, 1, "original", "original"))
    assertFalse(FocusOwnershipSafety.baselineOverride(active, false, null, null, "original"))
  }

  @Test fun globalUserOffNoneAndAlarmsOverridesArePreserved() {
    assertTrue(FocusOwnershipSafety.userOverride("active", false, 1))
    assertTrue(FocusOwnershipSafety.userOverride("active", false, 3))
    assertTrue(FocusOwnershipSafety.userOverride("prepared", false, 3))
    assertTrue(FocusOwnershipSafety.userOverride("restoring", false, 4))
    assertFalse(FocusOwnershipSafety.userOverride("active", false, 2))
  }

  @Test fun android35GlobalFilterNeverProvesImplicitRuleOwnershipOrUserOverride() {
    for (filter in listOf(1, 2, 3, 4)) assertFalse(FocusOwnershipSafety.userOverride("active", true, filter))
    assertFalse(FocusOwnershipSafety.matches(100_000, 100_100, "active", null, 1))
  }

  @Test fun partialWriteAtGlobalAllMayRestoreOnlyOwnedSnapshotPolicy() {
    assertFalse(FocusOwnershipSafety.userOverride("prepared", false, 1))
    assertFalse(FocusOwnershipSafety.userOverride("restoring", false, 1))
    assertTrue(FocusOwnershipSafety.matches(100_000, 100_100, "restoring", 100_100, 1))
  }

  @Test fun globalPolicyChangesArePreservedWithoutClaimingOtherImplicitRules() {
    assertTrue(FocusOwnershipSafety.policyOverride("active", false, "old", "user_changed"))
    assertFalse(FocusOwnershipSafety.policyOverride("active", false, "same", "same"))
    assertFalse(FocusOwnershipSafety.policyOverride("active", false, "old", null))
    assertFalse(FocusOwnershipSafety.policyOverride("active", true, "old", "other_rule"))
    assertTrue(FocusOwnershipSafety.policyOverride("prepared", false, null, "unknown_write"))
    assertFalse(FocusOwnershipSafety.policyOverride("prepared", false, null, "partial_write", "partial_write", "original"))
  }

  @Test fun uuidOwnershipSurvivesSlowPreparationWithoutAdoptingAnotherSnapshot() {
    assertTrue(FocusOwnershipSafety.matches(100_000, 0, "prepared", 160_000, 1, "session", "session"))
    assertFalse(FocusOwnershipSafety.matches(100_000, 160_000, "active", 160_000, 1, "session", "other"))
    assertFalse(FocusOwnershipSafety.matches(100_000, 0, "prepared", 100_100, 1, "session", null))
  }

  @Test fun updatingCrashRecognizesOwnNewPolicyButStillPreservesExternalPolicy() {
    assertFalse(FocusOwnershipSafety.policyOverride("updating", false, "old", "new", "new"))
    assertFalse(FocusOwnershipSafety.policyOverride("updating", false, "old", "old", "new"))
    assertTrue(FocusOwnershipSafety.policyOverride("updating", false, "old", "external", "new"))
  }

  @Test fun identicalExternalOffOnReceiptIsNotProofOfOurWrite() {
    assertTrue(FocusChangeSafety.expected(1, 100, 200, "2", "2"))
    assertFalse(FocusChangeSafety.expected(0, 100, 200, "2", "2"))
    assertFalse(FocusChangeSafety.expected(1, 100, 200, "2", "1"))
    assertFalse(FocusChangeSafety.expected(1, 201, 200, "2", "2"))
  }

  @Test fun recoveryAcceptsOnlyKnownBeforeAfterAndSnapshotPolicies() {
    for (phase in listOf("prepared", "restoring")) {
      assertFalse(FocusOwnershipSafety.policyOverride(phase, false, "before", "before", "after", "original"))
      assertFalse(FocusOwnershipSafety.policyOverride(phase, false, "before", "after", "after", "original"))
      assertFalse(FocusOwnershipSafety.policyOverride(phase, false, "before", "original", "after", "original"))
      assertTrue(FocusOwnershipSafety.policyOverride(phase, false, "before", "external", "after", "original"))
    }
    assertTrue(FocusOwnershipSafety.policyOverride("updating", false, "before", "original", "after", "original"))
    assertTrue(FocusOwnershipSafety.userOverride("updating", false, 1))
  }

  @Test fun everyGlobalRecoveryPhaseRelinquishesAcrossObservationGaps() {
    for (phase in listOf("prepared", "active", "updating", "restoring")) {
      val own = FocusOwnedSession("session", "manual", 0, 100, phase = phase)
      assertTrue(FocusOwnershipSafety.relinquishAfterObservationGap(false, own))
      assertFalse(FocusOwnershipSafety.relinquishAfterObservationGap(true, own))
    }
    assertFalse(FocusOwnershipSafety.relinquishAfterObservationGap(false, null))
  }
}

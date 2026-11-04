package app.attune.attunenative.dnd

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

data class DndRequest(
  val enabled: Boolean,
  val allowPrioritySenders: Boolean = true,
  val allowRepeatCallers: Boolean = true,
  val ownerId: String? = null,
  val expectedFilter: Int? = null
)

/** A platform-independent copy of NotificationManager.Policy for safe persistence and tests. */
data class DndNotificationPolicy(
  val priorityCategories: Int,
  val priorityCallSenders: Int,
  val priorityMessageSenders: Int,
  val suppressedVisualEffects: Int = 0,
  val priorityConversationSenders: Int = 0
)

/** The exact filter and policy that existed before Attune started managing DND. */
data class DndPolicySnapshot(
  val interruptionFilter: Int,
  val policy: DndNotificationPolicy,
  val capturedAtMs: Long,
  val ownerId: String? = null
)

data class DndResult(
  val applied: Boolean,
  val hasAccess: Boolean,
  val currentFilter: Int?,
  val reason: String,
  val restored: Boolean = false,
  val snapshotCaptured: Boolean = false,
  /** Presence alone is NOT proof that the requesting UUID owns the snapshot. */
  val snapshotPresent: Boolean = false,
  val rollbackAttempted: Boolean = false,
  val automaticZenRuleMode: String = DndPlatform.modeForApi(Build.VERSION.SDK_INT),
  val limitations: List<String> = DndPlatform.limitationsForApi(Build.VERSION.SDK_INT),
  /** Main transaction attempts; throwing writes may have changed Android. Rollback is separate. */
  val policyWriteAttempted: Boolean = false,
  val filterWriteAttempted: Boolean = false,
  val rollbackSucceeded: Boolean = false,
  /** Partial/uncertain writes or an uncleared new snapshot require guarded reconciliation. */
  val recoveryRequired: Boolean = false
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "applied" to applied,
    "hasAccess" to hasAccess,
    "currentFilter" to currentFilter,
    "reason" to reason,
    "restored" to restored,
    "snapshotCaptured" to snapshotCaptured,
    "snapshotPresent" to snapshotPresent,
    "rollbackAttempted" to rollbackAttempted,
    "policyWriteAttempted" to policyWriteAttempted,
    "filterWriteAttempted" to filterWriteAttempted,
    "rollbackSucceeded" to rollbackSucceeded,
    "recoveryRequired" to recoveryRequired,
    "automaticZenRuleMode" to automaticZenRuleMode,
    "limitations" to limitations,
    "status" to mapOf(
      "ownedState" to snapshotPresent,
      "accessRequired" to !hasAccess,
      "platformMode" to automaticZenRuleMode,
      "recoveryRequired" to recoveryRequired
    )
  )
}

interface DndPolicyController {
  fun hasPolicyAccess(): Boolean
  fun settingsIntent(): Intent
  fun currentFilter(): Int?
  /**
   * canWrite is fail-closed and rechecked before policy/filter writes, including rollback.
   * It must check live authorization, clocks and visibility, not an old policy key: later
   * invocations occur AFTER our policy write. Android offers no atomic compare-and-set filter.
   */
  fun apply(request: DndRequest, canWrite: () -> Boolean = { true }): DndResult
}

/** Storage is injectable so snapshot ownership can be tested without Android framework state. */
interface DndPolicySnapshotStore {
  /** Return null only when absent; throw on unreadable state so acquisition fails closed. */
  fun load(): DndPolicySnapshot?
  fun save(snapshot: DndPolicySnapshot): Boolean
  fun clear(): Boolean
}

/** The write boundary is injectable so denied access and partial-write behavior are testable. */
interface DndPolicyBackend {
  val automaticZenRuleMode: String

  fun hasPolicyAccess(): Boolean
  fun readCurrentState(): DndPolicySnapshot?
  fun writePolicy(policy: DndNotificationPolicy)
  fun writeInterruptionFilter(filter: Int)
  fun currentFilter(): Int?
}

/**
 * Coordinates one owned DND session. It never disables a pre-existing user DND state when
 * Attune has no snapshot proving that Attune enabled it.
 */
class DndPolicyCoordinator(
  private val backend: DndPolicyBackend,
  private val store: DndPolicySnapshotStore,
  private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
  fun apply(request: DndRequest, canWrite: () -> Boolean = { true }): DndResult = synchronized(LOCK) {
    if (!safeAccess()) return@synchronized failure("policy_access_required")
    if (request.ownerId != null && request.ownerId.isBlank()) return@synchronized failure("policy_owner_id_invalid")
    if (request.ownerId != null && request.expectedFilter == null) return@synchronized failure("policy_expected_filter_required")
    if (request.expectedFilter != null && request.expectedFilter !in VALID_FILTERS) {
      return@synchronized failure("policy_expected_filter_invalid")
    }
    // Null means genuinely absent; an unreadable store must not authorize a new acquisition.
    val snapshot = runCatching { store.load() }.getOrElse {
      return@synchronized failure("policy_snapshot_unavailable")
    }
    if (snapshot != null && snapshot.interruptionFilter !in VALID_FILTERS) {
      return@synchronized failure("policy_snapshot_unavailable")
    }
    // Null-owner callers retain legacy compatibility, but cannot bypass UUID ownership.
    if (snapshot != null && snapshot.ownerId != request.ownerId) {
      return@synchronized failure("policy_snapshot_owner_mismatch")
    }
    if (request.enabled) enable(request, snapshot, canWrite) else disable(request, snapshot, canWrite)
  }

  private fun enable(request: DndRequest, existing: DndPolicySnapshot?, canWrite: () -> Boolean): DndResult {
    if (existing == null && request.ownerId != null && request.expectedFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
      return failure("policy_acquisition_requires_all")
    }
    val beforePreparation = safeCurrentState() ?: return failure("policy_snapshot_unavailable")
    if (request.expectedFilter != null && beforePreparation.interruptionFilter != request.expectedFilter) {
      return failure("policy_filter_changed")
    }
    val captured = existing == null
    val snapshot = existing ?: beforePreparation.copy(capturedAtMs = nowMs(), ownerId = request.ownerId)
    if (captured && !runCatching { store.save(snapshot) }.getOrDefault(false)) {
      // SharedPreferences can update its in-memory map even when commit reports failure.
      clearMatched(request, snapshot)
      return failure("policy_snapshot_persist_failed", recoveryRequired = safeLoad() == snapshot)
    }

    // Storage may block. Read the actual pre-update policy/filter AFTER durable preparation;
    // rollback of an update must preserve its active session, not restore the original ALL.
    val beforeWrite = safeCurrentState()
      ?: return abort(request, snapshot, captured, "policy_snapshot_unavailable")
    if (captured && beforeWrite.policy != snapshot.policy) {
      return abort(request, snapshot, captured, "policy_state_changed")
    }
    boundaryFailure(request, snapshot, request.expectedFilter, canWrite)?.let {
      return abort(request, snapshot, captured, it)
    }
    val desiredPolicy = FocusPolicyMapper.forRequest(beforeWrite.policy, request)
    var policyAttempted = false
    var filterAttempted = false
    return try {
      policyAttempted = true
      backend.writePolicy(desiredPolicy)
      boundaryFailure(request, snapshot, request.expectedFilter, canWrite)?.let {
        // Do not roll back through a cancelled/expired guard or an external filter change.
        return abort(request, snapshot, captured, it, policyAttempted = true)
      }
      filterAttempted = true
      backend.writeInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
      result(
        applied = true, hasAccess = safeAccess(), currentFilter = safeCurrentFilter(),
        reason = if (backend.automaticZenRuleMode == DndPlatform.PLATFORM_MANAGED_AUTOMATIC_ZEN_RULE)
          "applied_platform_managed_automatic_zen_rule" else "applied",
        snapshotCaptured = captured, snapshotPresent = safeLoad() != null,
        policyWriteAttempted = true, filterWriteAttempted = true
      )
    } catch (_: Throwable) {
      val rollback = rollbackUpdate(request, snapshot, beforeWrite, desiredPolicy, canWrite)
      if (captured && rollback.succeeded) clearMatched(request, snapshot)
      failure("policy_write_rejected", snapshotCaptured = captured,
        policyWriteAttempted = policyAttempted, filterWriteAttempted = filterAttempted,
        rollbackAttempted = rollback.attempted, rollbackSucceeded = rollback.succeeded,
        recoveryRequired = !rollback.succeeded || (captured && safeLoad() == snapshot))
    }
  }

  private fun disable(request: DndRequest, snapshot: DndPolicySnapshot?, canWrite: () -> Boolean): DndResult {
    snapshot ?: return failure("not_active_no_snapshot")
    boundaryFailure(request, snapshot, request.expectedFilter, canWrite)?.let { return failure(it) }
    var policyAttempted = false
    var filterAttempted = false
    return try {
      policyAttempted = true
      backend.writePolicy(snapshot.policy)
      boundaryFailure(request, snapshot, request.expectedFilter, canWrite)?.let {
        return failure(it, policyWriteAttempted = true, recoveryRequired = true)
      }
      filterAttempted = true
      backend.writeInterruptionFilter(snapshot.interruptionFilter)
      val cleared = clearMatched(request, snapshot)
      result(
        applied = true, hasAccess = safeAccess(), currentFilter = safeCurrentFilter(),
        reason = when {
          !cleared -> "restored_snapshot_but_clear_failed"
          backend.automaticZenRuleMode == DndPlatform.PLATFORM_MANAGED_AUTOMATIC_ZEN_RULE ->
            "restored_platform_managed_automatic_zen_rule"
          else -> "restored"
        },
        restored = true, snapshotPresent = safeLoad() != null,
        policyWriteAttempted = true, filterWriteAttempted = true, recoveryRequired = !cleared
      )
    } catch (_: Throwable) {
      // A failed restoration is not authorization to re-enable DND. Retain exact recovery proof.
      failure("policy_restore_rejected", policyWriteAttempted = policyAttempted,
        filterWriteAttempted = filterAttempted, recoveryRequired = true)
    }
  }

  private data class Rollback(val attempted: Boolean = false, val succeeded: Boolean = false)

  private fun rollbackUpdate(request: DndRequest, snapshot: DndPolicySnapshot,
    beforeWrite: DndPolicySnapshot, desiredPolicy: DndNotificationPolicy,
    canWrite: () -> Boolean): Rollback {
    val current = safeCurrentState() ?: return Rollback()
    // A throwing filter write may have partially succeeded, but a changed filter cannot be
    // attributed to us safely. Preserve it for guarded recovery rather than force a rollback.
    if (current.interruptionFilter != beforeWrite.interruptionFilter) return Rollback()
    if (current.policy != beforeWrite.policy && current.policy != desiredPolicy) return Rollback()
    if (boundaryFailure(request, snapshot, current.interruptionFilter, canWrite) != null) return Rollback()
    return try {
      backend.writePolicy(beforeWrite.policy)
      if (boundaryFailure(request, snapshot, current.interruptionFilter, canWrite) != null) Rollback(attempted = true)
      else {
        backend.writeInterruptionFilter(beforeWrite.interruptionFilter)
        Rollback(attempted = true, succeeded = true)
      }
    } catch (_: Throwable) { Rollback(attempted = true) }
  }

  /** Called after all blocking snapshot preparation, and again after our own policy write. */
  private fun boundaryFailure(request: DndRequest, snapshot: DndPolicySnapshot,
    expectedFilter: Int?, canWrite: () -> Boolean): String? {
    val current = runCatching { store.load() }.getOrElse { return "policy_snapshot_unavailable" }
    if (current != null && current.ownerId != request.ownerId) return "policy_snapshot_owner_mismatch"
    if (current != snapshot) return "policy_snapshot_changed"
    if (!safeAccess()) return "policy_access_required"
    if (expectedFilter != null && safeCurrentFilter() != expectedFilter) return "policy_filter_changed"
    if (!runCatching(canWrite).getOrDefault(false)) return "policy_write_guard_rejected"
    // The callback may itself block. Never use the filter value captured before it ran.
    if (!safeAccess()) return "policy_access_required"
    if (expectedFilter != null && safeCurrentFilter() != expectedFilter) return "policy_filter_changed"
    return null
  }

  private fun abort(request: DndRequest, snapshot: DndPolicySnapshot, captured: Boolean, reason: String,
    policyAttempted: Boolean = false): DndResult {
    if (captured && !policyAttempted) clearMatched(request, snapshot)
    return failure(reason, snapshotCaptured = captured, policyWriteAttempted = policyAttempted,
      recoveryRequired = policyAttempted || (captured && safeLoad() == snapshot))
  }

  private fun clearMatched(request: DndRequest, expected: DndPolicySnapshot): Boolean = runCatching {
    val current = store.load() ?: return true // Already cleared, not an unrelated snapshot.
    current.ownerId == request.ownerId && current == expected && store.clear()
  }.getOrDefault(false)

  private fun safeAccess(): Boolean = runCatching { backend.hasPolicyAccess() }.getOrDefault(false)
  private fun safeCurrentState(): DndPolicySnapshot? = runCatching { backend.readCurrentState() }.getOrNull()
    ?.takeIf { it.interruptionFilter in VALID_FILTERS }

  private fun safeLoad(): DndPolicySnapshot? = runCatching { store.load() }.getOrNull()

  private fun safeCurrentFilter(): Int? = runCatching { backend.currentFilter() }.getOrNull()

  private fun failure(reason: String, snapshotCaptured: Boolean = false,
    policyWriteAttempted: Boolean = false, filterWriteAttempted: Boolean = false,
    rollbackAttempted: Boolean = false, rollbackSucceeded: Boolean = false,
    recoveryRequired: Boolean = false): DndResult = result(
      applied = false, hasAccess = safeAccess(), currentFilter = safeCurrentFilter(), reason = reason,
      snapshotCaptured = snapshotCaptured, snapshotPresent = safeLoad() != null,
      policyWriteAttempted = policyWriteAttempted, filterWriteAttempted = filterWriteAttempted,
      rollbackAttempted = rollbackAttempted, rollbackSucceeded = rollbackSucceeded,
      recoveryRequired = recoveryRequired
    )

  private fun result(
    applied: Boolean,
    hasAccess: Boolean,
    currentFilter: Int?,
    reason: String,
    restored: Boolean = false,
    snapshotCaptured: Boolean = false,
    snapshotPresent: Boolean = false,
    rollbackAttempted: Boolean = false,
    policyWriteAttempted: Boolean = false,
    filterWriteAttempted: Boolean = false,
    rollbackSucceeded: Boolean = false,
    recoveryRequired: Boolean = false
  ): DndResult = DndResult(
    applied = applied,
    hasAccess = hasAccess,
    currentFilter = currentFilter,
    reason = reason,
    restored = restored,
    snapshotCaptured = snapshotCaptured,
    snapshotPresent = snapshotPresent,
    rollbackAttempted = rollbackAttempted,
    automaticZenRuleMode = backend.automaticZenRuleMode,
    limitations = DndPlatform.limitationsForMode(backend.automaticZenRuleMode),
    policyWriteAttempted = policyWriteAttempted,
    filterWriteAttempted = filterWriteAttempted,
    rollbackSucceeded = rollbackSucceeded,
    recoveryRequired = recoveryRequired
  )

  companion object {
    private val LOCK = Any()
    private val VALID_FILTERS = setOf(NotificationManager.INTERRUPTION_FILTER_ALL,
      NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_NONE,
      NotificationManager.INTERRUPTION_FILTER_ALARMS)
  }
}

object FocusPolicyMapper {
  fun forRequest(current: DndNotificationPolicy, request: DndRequest): DndNotificationPolicy {
    val managedCategories = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
      NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or
      NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
    val untouchedCategories = current.priorityCategories and managedCategories.inv()
    val requestedCategories = (if (request.allowPrioritySenders) {
      NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
        NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES
    } else {
      0
    }) or if (request.allowRepeatCallers) {
      NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
    } else {
      0
    }

    val senderMode = if (request.allowPrioritySenders) {
      NotificationManager.Policy.PRIORITY_SENDERS_STARRED
    } else {
      NotificationManager.Policy.PRIORITY_SENDERS_ANY
    }
    return current.copy(
      priorityCategories = untouchedCategories or requestedCategories,
      priorityCallSenders = senderMode,
      priorityMessageSenders = senderMode
    )
  }
}

object DndPlatform {
  const val GLOBAL_INTERRUPTION_FILTER = "global_interruption_filter"
  const val PLATFORM_MANAGED_AUTOMATIC_ZEN_RULE = "platform_managed_automatic_zen_rule"

  fun modeForApi(apiLevel: Int): String = modeFor(apiLevel, apiLevel)

  fun modeFor(apiLevel: Int, targetSdk: Int): String = if (apiLevel >= 35 && targetSdk >= 35) {
    PLATFORM_MANAGED_AUTOMATIC_ZEN_RULE
  } else {
    GLOBAL_INTERRUPTION_FILTER
  }

  fun limitationsForApi(apiLevel: Int): List<String> = limitationsForMode(modeForApi(apiLevel))

  fun limitationsForMode(mode: String): List<String> = if (
    mode == PLATFORM_MANAGED_AUTOMATIC_ZEN_RULE
  ) {
    listOf(
      "Android 15+ maps setNotificationPolicy and setInterruptionFilter to an app-owned AutomaticZenRule.",
      "Attune does not claim ownership of or modify the user's other automatic rules."
    )
  } else {
    emptyList()
  }
}

private class AndroidDndPolicyBackend(
  private val manager: NotificationManager,
  targetSdk: Int
) : DndPolicyBackend {
  override val automaticZenRuleMode: String = DndPlatform.modeFor(Build.VERSION.SDK_INT, targetSdk)

  override fun hasPolicyAccess(): Boolean = manager.isNotificationPolicyAccessGranted

  override fun readCurrentState(): DndPolicySnapshot {
    val policy = manager.notificationPolicy
    return DndPolicySnapshot(
      interruptionFilter = manager.currentInterruptionFilter,
      policy = policy.toDndPolicy(),
      capturedAtMs = System.currentTimeMillis()
    )
  }

  override fun writePolicy(policy: DndNotificationPolicy) {
    manager.notificationPolicy = policy.toAndroidPolicy()
  }

  override fun writeInterruptionFilter(filter: Int) {
    manager.setInterruptionFilter(filter)
  }

  override fun currentFilter(): Int = manager.currentInterruptionFilter
}

private fun NotificationManager.Policy.toDndPolicy(): DndNotificationPolicy = DndNotificationPolicy(
  priorityCategories = priorityCategories,
  priorityCallSenders = priorityCallSenders,
  priorityMessageSenders = priorityMessageSenders,
  suppressedVisualEffects = if (Build.VERSION.SDK_INT >= 24) suppressedVisualEffects else 0,
  priorityConversationSenders = if (Build.VERSION.SDK_INT >= 30) priorityConversationSenders else 0
)

private fun DndNotificationPolicy.toAndroidPolicy(): NotificationManager.Policy = when {
  Build.VERSION.SDK_INT >= 30 -> NotificationManager.Policy(
    priorityCategories,
    priorityCallSenders,
    priorityMessageSenders,
    suppressedVisualEffects,
    priorityConversationSenders
  )
  Build.VERSION.SDK_INT >= 24 -> NotificationManager.Policy(
    priorityCategories,
    priorityCallSenders,
    priorityMessageSenders,
    suppressedVisualEffects
  )
  else -> NotificationManager.Policy(
    priorityCategories,
    priorityCallSenders,
    priorityMessageSenders
  )
}

private class SharedPreferencesDndPolicySnapshotStore(context: Context) : DndPolicySnapshotStore {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  override fun load(): DndPolicySnapshot? {
    if (!preferences.contains(KEY_FILTER)) return null
    return DndPolicySnapshot(
      interruptionFilter = preferences.getInt(KEY_FILTER, NotificationManager.INTERRUPTION_FILTER_UNKNOWN),
      policy = DndNotificationPolicy(
        priorityCategories = preferences.getInt(KEY_CATEGORIES, 0),
        priorityCallSenders = preferences.getInt(KEY_CALL_SENDERS, NotificationManager.Policy.PRIORITY_SENDERS_ANY),
        priorityMessageSenders = preferences.getInt(KEY_MESSAGE_SENDERS, NotificationManager.Policy.PRIORITY_SENDERS_ANY),
        suppressedVisualEffects = preferences.getInt(KEY_SUPPRESSED_EFFECTS, 0),
        priorityConversationSenders = preferences.getInt(KEY_CONVERSATION_SENDERS, 0)
      ),
      capturedAtMs = preferences.getLong(KEY_CAPTURED_AT, 0L),
      ownerId = preferences.getString(KEY_OWNER_ID, null)
    )
  }

  override fun save(snapshot: DndPolicySnapshot): Boolean = runCatching {
    preferences.edit()
      .putInt(KEY_FILTER, snapshot.interruptionFilter)
      .putInt(KEY_CATEGORIES, snapshot.policy.priorityCategories)
      .putInt(KEY_CALL_SENDERS, snapshot.policy.priorityCallSenders)
      .putInt(KEY_MESSAGE_SENDERS, snapshot.policy.priorityMessageSenders)
      .putInt(KEY_SUPPRESSED_EFFECTS, snapshot.policy.suppressedVisualEffects)
      .putInt(KEY_CONVERSATION_SENDERS, snapshot.policy.priorityConversationSenders)
      .putLong(KEY_CAPTURED_AT, snapshot.capturedAtMs)
      .putString(KEY_OWNER_ID, snapshot.ownerId)
      .commit()
  }.getOrDefault(false)

  override fun clear(): Boolean = runCatching {
    preferences.edit()
      .remove(KEY_FILTER)
      .remove(KEY_CATEGORIES)
      .remove(KEY_CALL_SENDERS)
      .remove(KEY_MESSAGE_SENDERS)
      .remove(KEY_SUPPRESSED_EFFECTS)
      .remove(KEY_CONVERSATION_SENDERS)
      .remove(KEY_CAPTURED_AT)
      .remove(KEY_OWNER_ID)
      .commit()
  }.getOrDefault(false)

  private companion object {
    const val PREFERENCES_NAME = "attune_dnd_policy_snapshot"
    const val KEY_FILTER = "interruption_filter"
    const val KEY_CATEGORIES = "priority_categories"
    const val KEY_CALL_SENDERS = "priority_call_senders"
    const val KEY_MESSAGE_SENDERS = "priority_message_senders"
    const val KEY_SUPPRESSED_EFFECTS = "suppressed_visual_effects"
    const val KEY_CONVERSATION_SENDERS = "priority_conversation_senders"
    const val KEY_CAPTURED_AT = "captured_at_ms"
    const val KEY_OWNER_ID = "owner_id"
  }
}

class AndroidDndPolicyController(private val context: Context) : DndPolicyController {
  private val manager: NotificationManager?
    get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

  override fun hasPolicyAccess(): Boolean = runCatching {
    manager?.isNotificationPolicyAccessGranted == true
  }.getOrDefault(false)

  override fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

  override fun currentFilter(): Int? = runCatching { manager?.currentInterruptionFilter }.getOrNull()

  override fun apply(request: DndRequest, canWrite: () -> Boolean): DndResult {
    val notificationManager = manager
      ?: return DndResult(false, false, null, "notification_manager_unavailable")
    return DndPolicyCoordinator(
      backend = AndroidDndPolicyBackend(
        manager = notificationManager,
        targetSdk = context.applicationInfo.targetSdkVersion
      ),
      store = SharedPreferencesDndPolicySnapshotStore(context)
    ).apply(request, canWrite)
  }
}

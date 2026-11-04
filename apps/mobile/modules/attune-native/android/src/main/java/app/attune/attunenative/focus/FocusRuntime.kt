package app.attune.attunenative.focus

import android.app.NotificationManager
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.attune.attunenative.AttuneEventHub
import app.attune.attunenative.context.AndroidContextSnapshotProvider
import app.attune.attunenative.context.ContextSnapshot
import app.attune.attunenative.dnd.AndroidDndPolicyController
import app.attune.attunenative.dnd.DndPlatform
import app.attune.attunenative.dnd.DndRequest
import app.attune.attunenative.dnd.DndResult
import app.attune.attunenative.dnd.DndNotificationPolicy
import app.attune.attunenative.dnd.FocusPolicyMapper
import app.attune.attunenative.history.NativeHistoryStore
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Single-process transaction owner for reviewed timetable, local training, warning and DND.
 * All public/receiver/timer paths use the same lock. Pending warnings are NEVER persisted;
 * a durable occurrence suppression is written before posting, preventing crash/reboot replay.
 *
 * Android docs: developer.android.com/develop/background-work/services/alarms/schedule
 * and developer.android.com/reference/android/app/NotificationManager#setInterruptionFilter(int).
 * Android 15+ activation/restoration is the app's implicit zen rule, not the global filter.
 */
class FocusRuntime private constructor(private val context: Context) {
  private val store = FocusStore(context)
  private var state = store.load()
  private val platform = FocusPlatform(context)
  private val controller = AndroidDndPolicyController(context)
  private val provider = AndroidContextSnapshotProvider(context)
  private val history by lazy { NativeHistoryStore(context.filesDir.resolve("attune_history.json")) }
  private val handler = Handler(Looper.getMainLooper())
  private var deadlineTask: Runnable? = null
  private var warningCheckTask: Runnable? = null
  private var endTask: Runnable? = null
  private var scheduledEndAtMs = 0L
  private var scheduledEndToken: String? = null
  private var restoreRetryAtMs = 0L
  private var pending: FocusPending? = null
  private var posting: FocusPending? = null
  private var latestPrediction: FocusPrediction? = null
  private var capturedAtMs = 0L
  private var runtimeStatus = "not_evaluated"
  private var reason = if (store.readFailed) "encrypted_store_unavailable" else "not_evaluated"
  private var storageFailed = store.readFailed
  private var lastWallMs = System.currentTimeMillis()
  private var lastElapsedMs = SystemClock.elapsedRealtime()
  private var observationGapOwner: String? = null

  init {
    // Old notifications/alarms are not evidence that this process showed a fresh warning.
    platform.cancelWarning()
    platform.cancel(FocusPlatform.DEADLINE)
    // API 29+ also delivers these protected broadcasts to the manifest receiver with DND access.
    // Older devices need an application-scoped observer, independent of the UI lifecycle.
    if (Build.VERSION.SDK_INT < 29) runCatching {
      context.registerReceiver(FocusDndChangeReceiver(), IntentFilter().apply {
        addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
      })
    }
    // A dead process may have missed an external Off->On sequence on the global API. Never
    // restore across that observation gap solely because the final filter looks unchanged.
    state.owned?.takeIf { FocusOwnershipSafety.relinquishAfterObservationGap(implicitZenMode(), it) }?.let { own ->
      // Even failed cleanup cannot authorize a system write through an unobserved ownership gap.
      observationGapOwner = own.id
      if (clearMatchedSnapshot(own)) persist(state.copy(owned = null,
        cooldownUntilMs = System.currentTimeMillis() + SKIP_COOLDOWN_MS))
      reason = "dnd_observation_gap_preserved"
    }
  }

  fun status(): Map<String, Any?> = synchronized(LOCK) {
    evaluateLocked()
    statusMap()
  }

  fun configure(enabled: Boolean, allowPrioritySenders: Boolean = true,
    allowRepeatCallers: Boolean = true): Boolean = synchronized(LOCK) {
    if (state.enabled == enabled && state.allowPrioritySenders == allowPrioritySenders &&
      state.allowRepeatCallers == allowRepeatCallers && state.owned?.policyUpdatePending != true) {
      evaluateLocked()
      return@synchronized !storageFailed
    }
    val exceptionsChanged = state.allowPrioritySenders != allowPrioritySenders || state.allowRepeatCallers != allowRepeatCallers
    invalidatePending("focus_rules_changed")
    val saved = persist(state.copy(enabled = enabled, allowPrioritySenders = allowPrioritySenders,
      allowRepeatCallers = allowRepeatCallers, ruleRevision = newId()))
    // Disabling/updating automation does not turn off an explicit manual DND session.
    if (state.owned?.origin == "automatic") restoreOwned("focus_rules_changed")
    if (!saved) { cancelFutureEvaluation(); publish(); return@synchronized false }
    val manualRulesApplied = if (state.owned?.origin == "manual" && (exceptionsChanged || state.owned?.policyUpdatePending == true))
      applyManualExceptionRules() else true
    val manualFailureReason = reason
    evaluateLocked()
    if (!manualRulesApplied) { runtimeStatus = "blocked"; reason = manualFailureReason }
    publish()
    manualRulesApplied
  }

  fun saveTimetable(entries: List<TimetableEntry>, validFrom: String,
    validUntil: String): Map<String, Any?> = synchronized(LOCK) {
    val schedule = runCatching { FocusSchedule.create(entries, validFrom, validUntil,
      ZoneId.systemDefault().id, newId()) }.getOrElse {
      return@synchronized statusMap() + mapOf("saved" to false, "error" to (it.message ?: "invalid_timetable"))
    }
    invalidatePending("timetable_changed")
    val now = System.currentTimeMillis()
    val rows = FocusLearning.usable(state.samples.filter { it.source != FocusLabelSource.TIMETABLE_PRIOR } +
      FocusLearning.priors(schedule, now), schedule, now)
    val saved = persist(state.copy(schedule = schedule, timeZone = schedule.timeZone, ruleRevision = newId(), samples = rows,
      model = FocusModel.train(rows, now)))
    if (state.owned?.origin == "automatic") restoreOwned("timetable_changed")
    if (saved) evaluateLocked() else cancelFutureEvaluation()
    publish()
    statusMap() + mapOf("saved" to saved)
  }

  /** Deletes timetable + all learning, disables automation, and preserves explicit manual DND. */
  fun clearLearning(): Boolean = synchronized(LOCK) {
    invalidatePending("focus_data_cleared")
    cancelFutureEvaluation()
    latestPrediction = null
    capturedAtMs = System.currentTimeMillis()
    val saved = persist(FocusDeletionSafety.reset(state, newId(), ZoneId.systemDefault().id))
    if (!saved) state = state.copy(enabled = false) // Fail closed in this process even if deletion could not be committed.
    if (FocusDeletionSafety.restoreAutomaticOnly(state.owned)) restoreOwned("focus_data_cleared", force = true)
    val complete = FocusDeletionSafety.succeeded(saved, state.owned)
    runtimeStatus = if (!complete) "blocked" else if (state.owned != null) "active" else "disabled"
    reason = if (complete) "focus_data_cleared" else if (!saved) "focus_data_delete_failed" else "dnd_restore_pending"
    // No evaluation/retraining here: a delete must never regenerate priors or arm a warning.
    scheduleOwnedEnd(capturedAtMs)
    publish()
    complete
  }

  fun skipPending(id: String): Boolean = synchronized(LOCK) {
    val warning = pending ?: posting
    val now = System.currentTimeMillis()
    val elapsed = SystemClock.elapsedRealtime()
    if (!FocusCountdownSafety.canSkip(warning, id, now, elapsed)) return@synchronized false
    warning ?: return@synchronized false
    // Skip confirms the warned context, not whatever happens to be on screen one minute later.
    val sample = FocusSample(newId(), now, warning.prediction.features, false, FocusLabelSource.USER_SKIP)
    invalidatePending("prediction_skipped")
    val rows = FocusLearning.usable(state.samples + sample, state.schedule, now)
    val saved = persist(state.copy(samples = rows, model = FocusModel.train(rows, now),
      cooldownUntilMs = max(state.cooldownUntilMs, now + SKIP_COOLDOWN_MS),
      suppressions = suppress(warning.prediction.occurrenceKey, warning.prediction.endAtMs, now)))
    reason = if (saved) "prediction_skipped" else "skip_cancelled_but_feedback_save_failed"
    runtimeStatus = if (saved) "skipped" else "blocked"
    scheduleNext(now)
    publish()
    // The pending action is cancelled even if storage fails; false means feedback was not saved.
    saved
  }

  fun manualDnd(enabled: Boolean, allowPrioritySenders: Boolean? = null,
    allowRepeatCallers: Boolean? = null): Map<String, Any?> = synchronized(LOCK) {
    invalidatePending("manual_dnd_choice")
    val snapshot = freshContext()
    val now = System.currentTimeMillis()
    val zone = state.schedule?.timeZone ?: state.timeZone
    val features = FocusFeatures.at(now, zone, FocusTimetable.current(state.schedule, now) != null, snapshot)
    val sample = FocusSample(newId(), now, features, enabled,
      if (enabled) FocusLabelSource.USER_ON else FocusLabelSource.USER_OFF)
    val rows = FocusLearning.usable(state.samples + sample, state.schedule, now)
    val rulesChanged = (allowPrioritySenders != null && allowPrioritySenders != state.allowPrioritySenders) ||
      (allowRepeatCallers != null && allowRepeatCallers != state.allowRepeatCallers)
    val saved = persist(state.copy(samples = rows, model = FocusModel.train(rows, now),
      allowPrioritySenders = allowPrioritySenders ?: state.allowPrioritySenders,
      allowRepeatCallers = allowRepeatCallers ?: state.allowRepeatCallers,
      ruleRevision = if (rulesChanged) newId() else state.ruleRevision,
      cooldownUntilMs = if (!enabled) max(state.cooldownUntilMs, now + SKIP_COOLDOWN_MS) else state.cooldownUntilMs))
    val result = if (FocusManualSafety.action(enabled, saved) == FocusManualAction.END_OWNED) {
      restoreOwned("manual_dnd_off", force = true) ?: rejected("not_owned_preserving_user_dnd")
    } else if (!saved) rejected("focus_state_persist_failed") else {
      val own = state.owned
      if (own != null && ownedSnapshotMatches(own)) {
        if (persist(state.copy(owned = own.copy(origin = "manual", untilMs = 0, phase = "active", restoreAuthorization = null,
            snapshotAtMs = own.snapshotAtMs.takeIf { it > 0 } ?: sharedSnapshotAt() ?: 0,
            reason = "manual_dnd_on")))) {
          if (applyManualExceptionRules()) DndResult(true, controller.hasPolicyAccess(), controller.currentFilter(), "manual_dnd_on",
            snapshotPresent = true) else rejected(reason)
        } else rejected("focus_state_persist_failed")
      } else acquire("manual", 0, "manual_dnd_on")
    }
    reason = result.reason
    runtimeStatus = if (state.owned != null) "active" else if (state.enabled) "ready" else "disabled"
    scheduleNext(now)
    publish()
    result.toMap() + mapOf("focus" to statusMap(), "feedbackSaved" to saved)
  }

  fun evaluate() { synchronized(LOCK) { evaluateLocked(); publish() } }

  internal fun onAlarm(slot: String, token: String) { synchronized(LOCK) {
    when (slot) {
      FocusPlatform.DEADLINE -> {
        // Never reconstruct an old countdown from the alarm's extras after process death.
        if (pending?.id == token) deadlineLocked(token)
        else { evaluateLocked(); publish() }
      }
      FocusPlatform.END -> if (state.owned?.id == token) { evaluateLocked(); publish() }
      FocusPlatform.EVALUATE -> if (state.enabled && state.ruleRevision == token) { evaluateLocked(); publish() }
    }
  } }

  internal fun onClockOrBootChanged(action: String) { synchronized(LOCK) {
    invalidatePending("clock_or_boot_changed")
    lastWallMs = System.currentTimeMillis()
    lastElapsedMs = SystemClock.elapsedRealtime()
    if (state.owned?.origin == "automatic") restoreOwned("clock_or_boot_changed")
    // The reviewed timezone stays fixed; a timezone broadcast never reinterprets a timetable.
    // Restoration/recovery is independent of automation being enabled, including manual Off.
    evaluateLocked()
    reason = if (storageFailed) reason else if (pending == null && state.owned == null) "rescheduled_after_${action.substringAfterLast('.').lowercase()}" else reason
    publish()
  } }

  private fun evaluateLocked() {
    var now = System.currentTimeMillis()
    var elapsed = SystemClock.elapsedRealtime()
    capturedAtMs = now
    latestPrediction = null
    if (abs((now - lastWallMs) - (elapsed - lastElapsedMs)) > 2_000L) {
      invalidatePending("clock_changed")
      if (state.owned?.origin == "automatic") restoreOwned("clock_changed")
    }
    lastWallMs = now; lastElapsedMs = elapsed
    reconcileOwned(now)
    if (storageFailed) {
      invalidatePending("focus_storage_unavailable")
      runtimeStatus = "blocked"
      cancelFutureEvaluation()
      scheduleOwnedEnd(now)
      return
    }
    refreshLearning(now)
    if (storageFailed) { runtimeStatus = "blocked"; cancelFutureEvaluation(); return }
    if (!state.enabled) {
      invalidatePending("focus_disabled")
      runtimeStatus = if (state.owned?.policyUpdatePending == true) "blocked" else if (state.owned != null) "active" else "disabled"
      reason = if (state.owned?.policyUpdatePending == true) state.owned?.policyUpdateReason ?: "manual_dnd_policy_update_pending" else
        if (state.owned?.origin == "manual") "manual_dnd_owned" else "focus_disabled"
      cancelFutureEvaluation(); scheduleOwnedEnd(now)
      return
    }
    if (state.owned != null) {
      invalidatePending("owned_dnd_active")
      runtimeStatus = if (state.owned?.policyUpdatePending == true) "blocked" else "active"
      reason = if (state.owned?.phase == "restoring") "dnd_restore_pending" else
        if (state.owned?.policyUpdatePending == true) state.owned?.policyUpdateReason ?: "manual_dnd_policy_update_pending" else
          state.owned?.reason ?: "owned_dnd_active"
      scheduleNext(now)
      return
    }
    val snapshot = freshContext()
    now = System.currentTimeMillis()
    elapsed = SystemClock.elapsedRealtime()
    capturedAtMs = now
    val zone = state.schedule?.timeZone ?: state.timeZone
    val prediction = FocusPredictor.predict(state.schedule, state.model, now, zone, snapshot)
    latestPrediction = prediction
    if (posting != null) {
      runtimeStatus = "warning_posting"; reason = "confirming_visible_warning"
      scheduleNext(now)
      return
    }
    val warning = pending
    if (warning != null) {
      // notify() queues work in system_server. Do not cancel a fresh warning on an immediate
      // UI refresh before the one-shot presence check; no grace applies at the action deadline.
      val visible = platform.warningVisible(warning.id) ||
        (elapsed - warning.createdElapsedMs in 0L..999L && platform.warningAvailable())
      when (FocusCountdownSafety.check(warning, warning.id, now, elapsed, state.enabled,
          visible, controller.hasPolicyAccess(), state.ruleRevision, prediction)) {
        FocusDeadline.APPLY -> { deadlineLocked(warning.id); return }
        FocusDeadline.DROP -> invalidatePending("prediction_or_warning_changed")
        FocusDeadline.WAIT -> {
          runtimeStatus = "pending"; reason = warning.prediction.reason
          scheduleNext(now)
          return // Repeated status/evaluation never reposts, retrains a prediction, or resets the timer.
        }
      }
    }
    val blocked = when {
      !controller.hasPolicyAccess() -> "policy_access_required"
      !platform.warningAvailable() -> "visible_focus_warning_required"
      controller.currentFilter() != NotificationManager.INTERRUPTION_FILTER_ALL -> "preexisting_dnd_preserved"
      sharedSnapshotAt() != null -> "unrelated_dnd_snapshot_preserved"
      else -> null
    }
    if (blocked != null) {
      runtimeStatus = "blocked"; reason = blocked
    } else if (now < state.cooldownUntilMs || (prediction != null && state.suppressions.any {
        it.key == prediction.occurrenceKey && now < it.untilMs })) {
      runtimeStatus = "skipped"; reason = "occurrence_or_choice_cooldown"
    } else if (prediction != null) {
      arm(prediction, now)
    } else {
      val schedule = state.schedule
      runtimeStatus = if (schedule != null && now >= schedule.expiresAtMs()) "schedule_expired" else
        if (state.model.trainedAtMs == 0L) "learning" else "ready"
      reason = if (runtimeStatus == "schedule_expired") "review_new_timetable_dates" else "no_supported_focus_prediction"
    }
    scheduleNext(now)
  }

  private fun freshContext(): ContextSnapshot = runCatching { provider.snapshot() }
    .getOrElse { ContextSnapshot(System.currentTimeMillis(), captureWarnings = listOf("context_capture_failed")) }

  private fun refreshLearning(now: Long) {
    val rows = FocusLearning.usable(state.samples.filter { it.source != FocusLabelSource.TIMETABLE_PRIOR } +
      FocusLearning.priors(state.schedule, now), state.schedule, now)
    val suppressions = state.suppressions.filter { it.untilMs > now && it.untilMs - now <= 2 * DAY_MS }.take(32)
    if (rows != state.samples || suppressions != state.suppressions) {
      persist(state.copy(samples = rows, model = if (rows != state.samples) FocusModel.train(rows, now) else state.model,
        suppressions = suppressions))
    }
  }

  private fun arm(prediction: FocusPrediction, now: Long) {
    if (!FocusCountdownSafety.canArm(prediction, now)) {
      runtimeStatus = "ready"; reason = "focus_occurrence_too_short_for_warning"; return
    }
    // Fail closed BEFORE notification or system action, including durable replay protection.
    if (!persist(state.copy(suppressions = suppress(prediction.occurrenceKey, prediction.endAtMs, now)))) return
    // Encryption/storage latency must not eat into the user's sixty-second visible countdown.
    val warning = FocusPending(newId(), System.currentTimeMillis(), SystemClock.elapsedRealtime(), prediction, state.ruleRevision, confirmed = false)
    posting = warning
    if (!platform.postWarning(warning)) {
      invalidatePending("visible_warning_or_deadline_unavailable")
      runtimeStatus = "blocked"
      return
    }
    val verify = Runnable { FocusReceiverExecutor.executor.execute { synchronized(LOCK) {
      warningCheckTask = null
      if (posting?.id != warning.id) return@synchronized
      val snapshot = freshContext()
      val confirmedNow = System.currentTimeMillis()
      val confirmedElapsed = SystemClock.elapsedRealtime()
      val current = FocusPredictor.predict(state.schedule, state.model, confirmedNow,
        state.schedule?.timeZone ?: state.timeZone, snapshot)
      if (!platform.warningVisible(warning.id) || !state.enabled || storageFailed ||
        warning.ruleRevision != state.ruleRevision || current == null || current.signature() != prediction.signature() ||
        current.endAtMs != prediction.endAtMs || !FocusCountdownSafety.canArm(current, confirmedNow) ||
        abs((confirmedNow - warning.createdAtMs) - (confirmedElapsed - warning.createdElapsedMs)) > 2_000L) {
        invalidatePending("focus_warning_not_posted")
        runtimeStatus = "blocked"; scheduleNext(System.currentTimeMillis()); publish()
        return@synchronized
      }
      // Start the full sixty seconds only AFTER actual system notification presence is confirmed.
      val confirmed = warning.copy(createdAtMs = System.currentTimeMillis(), createdElapsedMs = SystemClock.elapsedRealtime(), confirmed = true)
      posting = null; pending = confirmed
      if (!platform.postWarning(confirmed) || !platform.schedule(FocusPlatform.DEADLINE, confirmed.deadlineAtMs, confirmed.id)) {
        invalidatePending("visible_warning_or_deadline_unavailable"); runtimeStatus = "blocked"; publish()
        return@synchronized
      }
      val task = Runnable { FocusReceiverExecutor.executor.execute { synchronized(LOCK) { deadlineLocked(confirmed.id) } } }
      deadlineTask = task
      handler.postDelayed(task, (confirmed.deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
      runtimeStatus = "pending"; reason = prediction.reason; publish()
    } } }
    warningCheckTask = verify
    handler.postDelayed(verify, 1_000L)
    runtimeStatus = "warning_posting"; reason = "confirming_visible_warning"
    publish()
  }

  private fun deadlineLocked(token: String) {
    val warning = pending ?: return
    if (warning.id != token) return
    val snapshot = freshContext()
    val now = System.currentTimeMillis()
    val elapsed = SystemClock.elapsedRealtime()
    val prediction = FocusPredictor.predict(state.schedule, state.model, now,
      state.schedule?.timeZone ?: state.timeZone, snapshot)
    val check = FocusCountdownSafety.check(warning, token, now, elapsed,
      state.enabled && !storageFailed, platform.warningVisible(token), controller.hasPolicyAccess(), state.ruleRevision, prediction)
    if (check == FocusDeadline.WAIT) {
      // Handler is uptime-based and can fire around sleep boundaries; elapsed is authoritative.
      deadlineTask?.let { handler.removeCallbacks(it); handler.postDelayed(it, warning.deadlineElapsedMs - elapsed) }
      return
    }
    if (check == FocusDeadline.DROP) {
      invalidatePending("stale_or_changed_focus_deadline")
      runtimeStatus = "ready"; scheduleNext(now); publish(); return
    }
    // Visibility/access/clock/source checks above happen before removing the warning and writing.
    val result = acquire("automatic", warning.prediction.endAtMs, warning.prediction.reason) {
      val decisionNow = System.currentTimeMillis()
      val current = FocusPredictor.predict(state.schedule, state.model, decisionNow,
        state.schedule?.timeZone ?: state.timeZone, snapshot)
      pending?.id == token && FocusCountdownSafety.check(warning, token, decisionNow, SystemClock.elapsedRealtime(),
        state.enabled && !storageFailed, platform.warningVisible(token), controller.hasPolicyAccess(), state.ruleRevision, current) == FocusDeadline.APPLY
    }
    invalidatePending(if (result.applied) warning.prediction.reason else result.reason)
    runtimeStatus = if (result.applied) "active" else "blocked"
    latestPrediction = prediction
    scheduleNext(now); publish()
  }

  private fun acquire(origin: String, untilMs: Long, predictionReason: String, canWrite: () -> Boolean = { true }): DndResult {
    if (storageFailed) return rejected("focus_state_persist_failed")
    if (!controller.hasPolicyAccess()) return rejected("policy_access_required")
    // ALL is a necessary precondition, not proof of ownership. Acquisition also requires no
    // pre-existing Attune snapshot and a persisted intent followed by its exact snapshot identity.
    if (controller.currentFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) return rejected("preexisting_dnd_preserved")
    if (sharedSnapshotAt() != null || state.owned != null) return rejected("unrelated_dnd_snapshot_preserved")
    val targetPolicy = desiredPolicyKey() ?: return rejected("policy_snapshot_unavailable")
    val own = writeExpectation(FocusOwnedSession(newId(), origin, untilMs, System.currentTimeMillis(), reason = predictionReason),
      "prepared", NotificationManager.INTERRUPTION_FILTER_PRIORITY, targetPolicy)
    if (!persist(state.copy(owned = own))) return rejected("focus_ownership_persist_failed")
    val beforePolicy = currentPolicyKey()
    val result = controller.apply(request(true, own.id, NotificationManager.INTERRUPTION_FILTER_ALL)) {
      FocusChangeSafety.writeWindowOpen(own, System.currentTimeMillis()) && canWrite()
    }
    val settled = settleExpectation(own, result, beforePolicy)
    val at = sharedSnapshotAt()
    if (at != null && ownedSnapshotMatches(own)) {
      // On partial failure retain ownership of the rollback snapshot, so restoration can retry.
      val next = settled.copy(snapshotAtMs = at, appliedPolicyKey = if (result.applied) currentPolicyKey() ?: targetPolicy else null,
        phase = if (result.applied) "active" else "restoring")
      persist(state.copy(owned = next))
    } else if (!result.snapshotPresent) {
      persist(state.copy(owned = null))
    } else {
      reason = "dnd_snapshot_identity_uncertain"; runtimeStatus = "blocked"
    }
    log(true, result, predictionReason)
    return result
  }

  private fun reconcileOwned(now: Long) {
    val own = state.owned ?: return
    if (!ownedSnapshotMatches(own)) {
      // Someone else's snapshot, or a crash before a system write: never call disable on it.
      persist(state.copy(owned = null))
      reason = "owned_snapshot_no_longer_present"
      return
    }
    if (own.phase == "updating" && own.origin == "manual") {
      // Completing a requested exception edit must not restore ALL and end a manual session.
      if (now >= restoreRetryAtMs) {
        if (applyManualExceptionRules()) restoreRetryAtMs = 0 else restoreRetryAtMs = now + RESTORE_RETRY_MS
      }
    } else if (own.phase == "prepared" || own.phase == "restoring" ||
      (own.origin == "automatic" && (own.untilMs <= now || !state.enabled || state.schedule?.let {
        own.reason == "reviewed_timetable_prediction" && !it.validAt(now)
      } == true))) {
      restoreOwned("focus_session_ended")
    } else if (userOverrode(own)) {
      // Global API: a user's NONE/ALARMS/ALL override must not be overwritten at event end.
      // Remove only the shared snapshot whose exact identity belongs to this ledger.
      if (clearMatchedSnapshot(own)) persist(state.copy(owned = null))
      reason = "user_dnd_override_preserved"
    }
  }

  private fun restoreOwned(restoreReason: String, force: Boolean = false): DndResult? {
    val own = state.owned ?: return null
    if (!ownedSnapshotMatches(own)) {
      persist(state.copy(owned = null))
      return rejected("not_owned_preserving_user_dnd")
    }
    if (userOverrode(own)) {
      if (clearMatchedSnapshot(own)) persist(state.copy(owned = null))
      return rejected("user_dnd_override_preserved")
    }
    if (!force && System.currentTimeMillis() < restoreRetryAtMs) return rejected("dnd_restore_pending")
    // A failed ledger write must not discard the old owned snapshot. Restoration is allowed
    // even when storage failed: reducing our own DND effect is not a new automatic action.
    val beforeFilter = controller.currentFilter()
    val beforePolicy = currentPolicyKey()
    val restorePolicy = sharedPolicy()?.let(::policyKey)
    // The earlier observation can race a user change. Validate these exact write baselines
    // against the original phase, before "restoring" broadens the accepted recovery states.
    if (userOverrode(own, beforeFilter, beforePolicy, restorePolicy)) {
      if (clearMatchedSnapshot(own)) persist(state.copy(owned = null))
      return rejected("user_dnd_override_preserved")
    }
    val writing = writeExpectation(FocusRestorationSafety.prepare(own.copy(
      snapshotAtMs = own.snapshotAtMs.takeIf { it > 0 } ?: sharedSnapshotAt() ?: 0)),
      "restoring", NotificationManager.INTERRUPTION_FILTER_ALL, restorePolicy)
    // Restoring reduces our owned effect. Retain its intent in memory even when storage fails;
    // new acquisition/update writes still require durable preparation.
    if (!persist(state.copy(owned = writing))) state = state.copy(owned = writing)
    if (beforeFilter == null || beforePolicy == null || restorePolicy == null) {
      reason = "dnd_restore_pending"
      restoreRetryAtMs = System.currentTimeMillis() + RESTORE_RETRY_MS
      return rejected("dnd_restore_pending")
    }
    val result = controller.apply(request(false, own.id, beforeFilter)) {
      FocusChangeSafety.writeWindowOpen(writing, System.currentTimeMillis()) &&
        ownedSnapshotMatches(own) && controller.currentFilter() == beforeFilter &&
        currentPolicyKey().let { it == beforePolicy || it == restorePolicy }
    }
    val settled = FocusRestorationSafety.afterAttempt(settleExpectation(writing, result, beforePolicy),
      result.policyWriteAttempted, beforePolicy)
    if (result.restored && !result.snapshotPresent) {
      restoreRetryAtMs = 0
      persist(state.copy(owned = null))
    } else {
      if (!persist(state.copy(owned = settled))) state = state.copy(owned = settled)
      restoreRetryAtMs = System.currentTimeMillis() + RESTORE_RETRY_MS
    }
    log(false, result, restoreReason)
    if (!result.restored || result.snapshotPresent) reason = "dnd_restore_pending"
    return result
  }

  private fun request(enabled: Boolean, ownerId: String? = state.owned?.id, expectedFilter: Int? = null) =
    DndRequest(enabled, state.allowPrioritySenders, state.allowRepeatCallers, ownerId, expectedFilter)
  private fun rejected(code: String) = DndResult(false, controller.hasPolicyAccess(), controller.currentFilter(), code)

  /** An explicit exception edit updates the owned manual policy without adding training labels. */
  private fun applyManualExceptionRules(): Boolean {
    val own = state.owned ?: return true
    if (own.origin != "manual" || own.phase !in setOf("active", "updating") || !ownedSnapshotMatches(own)) {
      return markManualUpdatePending(own, "owned_manual_dnd_policy_unavailable")
    }
    if (userOverrode(own)) {
      if (clearMatchedSnapshot(own)) persist(state.copy(owned = null))
      reason = "user_dnd_override_preserved"
      return false
    }
    val beforeFilter = controller.currentFilter()
    val beforePolicy = currentPolicyKey()
    val targetPolicy = desiredPolicyKey()
    if (beforeFilter == null || beforePolicy == null || targetPolicy == null)
      return markManualUpdatePending(own, "owned_manual_dnd_policy_unavailable")
    if (userOverrode(own, beforeFilter, beforePolicy)) {
      if (clearMatchedSnapshot(own)) persist(state.copy(owned = null))
      reason = "user_dnd_override_preserved"; return false
    }
    if (!persist(state.copy(owned = writeExpectation(own.copy(policyUpdatePending = true, policyUpdateReason = null),
        "updating", NotificationManager.INTERRUPTION_FILTER_PRIORITY, targetPolicy)))) return false
    val writing = state.owned ?: return false
    val result = controller.apply(request(true, own.id, beforeFilter)) {
      FocusChangeSafety.writeWindowOpen(writing, System.currentTimeMillis()) &&
        ownedSnapshotMatches(own) && controller.currentFilter() == beforeFilter &&
        currentPolicyKey().let { it == beforePolicy || it == targetPolicy }
    }
    log(true, result, "manual_dnd_exceptions_updated")
    val next = FocusManualSafety.afterUpdate(settleExpectation(writing, result, beforePolicy),
      result.applied, result.recoveryRequired, result.reason, beforePolicy)
    val saved = persist(state.copy(owned = next))
    if (!result.applied) reason = result.reason
    return result.applied && saved
  }

  private fun markManualUpdatePending(own: FocusOwnedSession, code: String): Boolean {
    val next = own.copy(policyUpdatePending = true, policyUpdateReason = code)
    if (!persist(state.copy(owned = next))) state = state.copy(owned = next)
    reason = code
    return false
  }

  private fun sharedSnapshotAt(): Long? = runCatching {
    val prefs = context.getSharedPreferences("attune_dnd_policy_snapshot", Context.MODE_PRIVATE)
    if (prefs.contains("interruption_filter") && prefs.contains("captured_at_ms")) prefs.getLong("captured_at_ms", 0L) else null
  }.getOrNull()

  private fun ownedSnapshotMatches(own: FocusOwnedSession): Boolean = runCatching {
    val prefs = context.getSharedPreferences("attune_dnd_policy_snapshot", Context.MODE_PRIVATE)
    FocusOwnershipSafety.matches(own.attemptedAtMs, own.snapshotAtMs, own.phase, sharedSnapshotAt(),
      prefs.getInt("interruption_filter", NotificationManager.INTERRUPTION_FILTER_UNKNOWN), own.id, prefs.getString("owner_id", null))
  }.getOrDefault(false)

  private fun clearMatchedSnapshot(own: FocusOwnedSession): Boolean {
    if (!ownedSnapshotMatches(own)) return false
    return runCatching {
      val editor = context.getSharedPreferences("attune_dnd_policy_snapshot", Context.MODE_PRIVATE).edit()
      listOf("interruption_filter", "priority_categories", "priority_call_senders", "priority_message_senders",
        "suppressed_visual_effects", "priority_conversation_senders", "captured_at_ms", "owner_id").forEach(editor::remove)
      editor.commit()
    }.getOrDefault(false)
  }

  private fun implicitZenMode(): Boolean = DndPlatform.modeFor(Build.VERSION.SDK_INT,
    context.applicationInfo.targetSdkVersion) == DndPlatform.PLATFORM_MANAGED_AUTOMATIC_ZEN_RULE

  private fun currentPolicyKey(): String? = runCatching {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
    val policy = manager.notificationPolicy
    listOf(policy.priorityCategories, policy.priorityCallSenders, policy.priorityMessageSenders,
      if (Build.VERSION.SDK_INT >= 24) policy.suppressedVisualEffects else 0,
      if (Build.VERSION.SDK_INT >= 30) policy.priorityConversationSenders else 0).joinToString(",")
  }.getOrNull()

  private fun userOverrode(own: FocusOwnedSession, filter: Int? = controller.currentFilter(),
    policy: String? = currentPolicyKey(), priorPolicy: String? = sharedPolicy()?.let(::policyKey)): Boolean =
    observationGapOwner == own.id || FocusOwnershipSafety.baselineOverride(own, implicitZenMode(), filter, policy, priorPolicy)

  private fun sharedPolicy(): DndNotificationPolicy? = runCatching {
    val prefs = context.getSharedPreferences("attune_dnd_policy_snapshot", Context.MODE_PRIVATE)
    if (!prefs.contains("interruption_filter")) return null
    DndNotificationPolicy(prefs.getInt("priority_categories", 0), prefs.getInt("priority_call_senders", 0),
      prefs.getInt("priority_message_senders", 0), prefs.getInt("suppressed_visual_effects", 0), prefs.getInt("priority_conversation_senders", 0))
  }.getOrNull()

  private fun policyKey(policy: DndNotificationPolicy): String = listOf(policy.priorityCategories, policy.priorityCallSenders,
    policy.priorityMessageSenders, policy.suppressedVisualEffects, policy.priorityConversationSenders).joinToString(",")

  private fun desiredPolicyKey(): String? = runCatching {
    val policy = sharedPolicy() ?: (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notificationPolicy?.let {
      DndNotificationPolicy(it.priorityCategories, it.priorityCallSenders, it.priorityMessageSenders,
        if (Build.VERSION.SDK_INT >= 24) it.suppressedVisualEffects else 0, if (Build.VERSION.SDK_INT >= 30) it.priorityConversationSenders else 0)
    } ?: return null
    policyKey(FocusPolicyMapper.forRequest(policy, request(true)))
  }.getOrNull()

  private fun writeExpectation(own: FocusOwnedSession, phase: String, filter: Int, policy: String?): FocusOwnedSession =
    FocusChangeSafety.begin(own, phase, filter, policy, controller.currentFilter(), currentPolicyKey(), System.currentTimeMillis(), newId(), implicitZenMode())

  private fun settleExpectation(own: FocusOwnedSession, result: DndResult, beforePolicy: String?): FocusOwnedSession =
    FocusChangeSafety.settle(own, result.policyWriteAttempted, result.filterWriteAttempted,
      result.rollbackAttempted, beforePolicy, implicitZenMode(), System.currentTimeMillis())

  /** Receipts capture state before queuing, so Off followed by identical On cannot disappear. */
  internal fun onDndChanged(policyChanged: Boolean, observed: String?, receivedAtMs: Long) = synchronized(LOCK) {
    if (implicitZenMode()) return@synchronized // Other rules' global changes are not ours.
    val own = state.owned ?: return@synchronized
    if (receivedAtMs < own.attemptedAtMs) return@synchronized
    if (!FocusChangeSafety.observationAvailable(policyChanged, observed)) {
      reason = "dnd_observation_unavailable"; publish(); return@synchronized
    }
    val index = FocusChangeSafety.receiptIndex(own, policyChanged, observed, receivedAtMs)
    if (index >= 0) {
      persist(state.copy(owned = own.copy(receipts = own.receipts.orEmpty().filterIndexed { i, _ -> i != index })))
    } else if (clearMatchedSnapshot(own)) {
      persist(state.copy(owned = null, cooldownUntilMs = System.currentTimeMillis() + SKIP_COOLDOWN_MS))
      invalidatePending("user_dnd_override_preserved")
    }
    scheduleOwnedEnd(System.currentTimeMillis()); publish()
  }

  private fun persist(next: FocusState): Boolean {
    if (!store.save(next)) {
      storageFailed = true; reason = "focus_state_persist_failed"; runtimeStatus = "blocked"
      return false
    }
    state = next
    storageFailed = false
    return true
  }

  private fun suppress(key: String, until: Long, now: Long): List<FocusSuppression> =
    (listOf(FocusSuppression(key, until.coerceAtMost(now + 2 * DAY_MS))) +
      state.suppressions.filter { it.key != key && it.untilMs > now }).take(32)

  private fun invalidatePending(code: String) {
    val token = pending?.id ?: posting?.id
    pending = null; posting = null
    deadlineTask?.let(handler::removeCallbacks); deadlineTask = null
    warningCheckTask?.let(handler::removeCallbacks); warningCheckTask = null
    platform.cancel(FocusPlatform.DEADLINE); platform.cancelWarning(token)
    reason = code
  }

  private fun scheduleNext(now: Long) {
    if (state.enabled && !storageFailed) {
      val next = FocusTimetable.nextBoundary(state.schedule, now, state.model.positiveFeedbackCount >= 3,
        state.schedule?.timeZone ?: state.timeZone)
      if (next != null) {
        if (!platform.schedule(FocusPlatform.EVALUATE, next, state.ruleRevision)) {
          invalidatePending("native_wakeup_unavailable"); runtimeStatus = "blocked"
        }
      } else platform.cancel(FocusPlatform.EVALUATE)
    } else platform.cancel(FocusPlatform.EVALUATE)
    scheduleOwnedEnd(now)
  }

  private fun scheduleOwnedEnd(now: Long) {
    val own = state.owned
    if (own == null || (own.origin == "manual" && own.phase == "active")) {
      endTask?.let(handler::removeCallbacks); endTask = null
      scheduledEndAtMs = 0; scheduledEndToken = null
      platform.cancel(FocusPlatform.END); return
    }
    val at = if (own.phase == "restoring" || own.untilMs <= now)
      max(now + 1_000L, restoreRetryAtMs.takeIf { it > 0 } ?: (now + RESTORE_RETRY_MS)) else own.untilMs
    if (scheduledEndAtMs == at && scheduledEndToken == own.id && endTask != null) return
    endTask?.let(handler::removeCallbacks); endTask = null
    scheduledEndAtMs = at; scheduledEndToken = own.id
    platform.schedule(FocusPlatform.END, at, own.id)
    val task = Runnable { FocusReceiverExecutor.executor.execute { synchronized(LOCK) {
      endTask = null
      if (state.owned?.id == own.id) { evaluateLocked(); publish() }
    } } }
    endTask = task
    handler.postDelayed(task, (at - now).coerceAtLeast(1_000L))
  }

  private fun cancelFutureEvaluation() { platform.cancel(FocusPlatform.EVALUATE) }

  private fun log(enabled: Boolean, result: DndResult, code: String) {
    runCatching { history.recordDnd(enabled, result.copy(reason = if (result.applied) code else result.reason), System.currentTimeMillis()) }
  }

  private fun statusMap(): Map<String, Any?> = mapOf(
    "enabled" to state.enabled, "status" to runtimeStatus, "reason" to reason,
    "source" to (pending?.prediction?.source ?: latestPrediction?.source ?: if (state.model.feedbackCount > 0)
      "personalized_local_gbdt" else if (state.model.priorCount > 0) "timetable_prior_local_gbdt" else "untrained"),
    "capturedAtMs" to capturedAtMs, "pending" to (pending ?: posting)?.toMap(),
    "activeUntilMs" to state.owned?.untilMs?.takeIf { it > 0 },
    "entries" to state.schedule?.entries.orEmpty().map(TimetableEntry::toMap),
    "validFrom" to state.schedule?.validFrom, "validUntil" to state.schedule?.validUntil,
    "timeZone" to (state.schedule?.timeZone ?: state.timeZone),
    "trainingSamples" to state.samples.size, "feedbackSamples" to state.model.feedbackCount,
    "trainedAtMs" to state.model.trainedAtMs, "allowPrioritySenders" to state.allowPrioritySenders,
    "allowRepeatCallers" to state.allowRepeatCallers,
    "sourceCounts" to FocusLabelSource.values().associate { source -> source.name.lowercase() to state.samples.count { it.source == source } },
    "ownsDnd" to (state.owned != null), "dndOrigin" to state.owned?.origin,
    "dndPhase" to state.owned?.phase, "policyUpdatePending" to (state.owned?.policyUpdatePending == true),
    "policyUpdateReason" to state.owned?.policyUpdateReason,
    "confidenceKind" to "estimated_not_calibrated",
    "limitations" to (listOf(
      "Confidence is an evidence-strength estimate, not calibrated probability or measured attendance.",
      "Timetable labels are reviewed expectations; only explicit On, Off and Skip choices are feedback.",
      "Outside the timetable, automatic predictions require repeated choices in the same weekly time bin or repeated busy-calendar confirmations.",
      "Inexact Android wakeups and Doze may delay warnings/restoration; deadlines over 15 seconds late are dropped.",
      "Process death, reboot and clock changes discard pending countdowns; force-stop prevents alarms until the app is reopened.",
      "On older global-DND Android APIs, an observation gap after process death relinquishes ownership without changing Android DND; manage that state in Android settings.",
      "Manual DND is never ended by timetable automation. Pre-existing DND and unrelated snapshots are preserved.",
      "Calendar and foreground signals are permission-dependent; no microphone or camera is used.",
      "Timetable, derived samples and model stay encrypted in private no-backup storage on this phone."
    ) + DndPlatform.limitationsForMode(DndPlatform.modeFor(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)) +
      (if (implicitZenMode()) listOf("Ownership is recorded by this runtime and snapshot identity, not Android's global filter. User changes to other zen rules remain untouched.") else emptyList()) +
      (if ((state.schedule?.timeZone ?: state.timeZone) != ZoneId.systemDefault().id)
        listOf("Focus still uses its saved timezone; review and save a timetable again to change it.") else emptyList()) +
      (if (storageFailed) listOf("Local storage is unavailable. New automatic DND actions are blocked; owned restoration can retry.") else emptyList()) +
      (if (store.readFailed) listOf("Unreadable ownership cannot be safely restored. Manage existing Do Not Disturb in Android settings.") else emptyList()))
  ) + FocusStatusMetrics.from(pending?.prediction ?: latestPrediction)

  private fun publish() { runCatching { AttuneEventHub.publish("onNativeStatusChanged",
    mapOf("focusChanged" to true, "focus" to statusMap(), "focusStatus" to statusMap())) } }

  companion object {
    private val LOCK = Any()
    @Volatile private var instance: FocusRuntime? = null
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val SKIP_COOLDOWN_MS = 60 * 60_000L
    private const val RESTORE_RETRY_MS = 15 * 60_000L
    fun get(context: Context): FocusRuntime = instance ?: synchronized(LOCK) {
      instance ?: FocusRuntime(context.applicationContext).also { instance = it }
    }
    private fun newId(): String = UUID.randomUUID().toString()
  }
}

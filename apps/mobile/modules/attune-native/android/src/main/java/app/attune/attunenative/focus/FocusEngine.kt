package app.attune.attunenative.focus

import app.attune.attunenative.context.ContextSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** Reviewed expectations, not observations of attendance. All date bounds are inclusive. */
data class FocusSchedule(
  val entries: List<TimetableEntry>,
  val validFrom: String,
  val validUntil: String,
  val timeZone: String,
  val revision: String
) {
  fun validAt(nowMs: Long): Boolean = runCatching {
    val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(timeZone)).toLocalDate()
    today >= LocalDate.parse(validFrom) && today <= LocalDate.parse(validUntil)
  }.getOrDefault(false)

  fun expiresAtMs(): Long = LocalDate.parse(validUntil).plusDays(1)
    .atStartOfDay(ZoneId.of(timeZone)).toInstant().toEpochMilli()

  companion object {
    fun create(entries: List<TimetableEntry>, from: String, until: String, zone: String,
      revision: String): FocusSchedule {
      require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(from) && Regex("\\d{4}-\\d{2}-\\d{2}").matches(until))
      val start = LocalDate.parse(from)
      val end = LocalDate.parse(until)
      require(end >= start && ChronoUnit.DAYS.between(start, end) < 180) { "validity_must_be_1_to_180_days" }
      ZoneId.of(zone)
      require(entries.size in 1..200 && entries.all(TimetableParser::validate)) { "invalid_entries" }
      require(entries.map { it.id }.distinct().size == entries.size) { "duplicate_entry_id" }
      require(entries.all {
        (it.startDate == null || LocalDate.parse(it.startDate) >= start) &&
          (it.startDate == null || LocalDate.parse(it.startDate) <= end) &&
          (it.endDate == null || LocalDate.parse(it.endDate) >= start) &&
          (it.endDate == null || LocalDate.parse(it.endDate) <= end)
      }) { "entry_dates_outside_reviewed_validity" }
      return FocusSchedule(entries.toList(), from, until, zone, revision)
    }
  }
}

data class FocusOccurrence(val key: String, val startAtMs: Long, val endAtMs: Long)

object FocusTimetable {
  fun occurrences(schedule: FocusSchedule?, date: LocalDate): List<FocusOccurrence> {
    if (schedule == null || date < LocalDate.parse(schedule.validFrom) || date > LocalDate.parse(schedule.validUntil)) return emptyList()
    val zone = ZoneId.of(schedule.timeZone)
    return schedule.entries.filter { entry ->
      entry.dayOfWeek == date.dayOfWeek.value &&
        (entry.startDate == null || date >= LocalDate.parse(entry.startDate)) &&
        (entry.endDate == null || date <= LocalDate.parse(entry.endDate))
    }.mapNotNull { entry ->
      // Resolve DST in local civil time; an inverted/empty gap occurrence is not actionable.
      val start = date.atStartOfDay().plusMinutes(entry.startMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
      val end = date.atStartOfDay().plusMinutes(entry.endMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
      if (end <= start) null else FocusOccurrence("t:${schedule.revision}:${entry.id}:$date", start, end)
    }.sortedBy { it.startAtMs }
  }

  fun current(schedule: FocusSchedule?, nowMs: Long): FocusOccurrence? {
    if (schedule == null || !schedule.validAt(nowMs)) return null
    val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(schedule.timeZone)).toLocalDate()
    return occurrences(schedule, date).firstOrNull { nowMs >= it.startAtMs && nowMs < it.endAtMs }
  }

  /** One next wake, never a repeating polling alarm. A learned model uses half-hour bins. */
  fun nextBoundary(schedule: FocusSchedule?, nowMs: Long, learned: Boolean, zone: String): Long? {
    val candidates = mutableListOf<Long>()
    if (schedule != null && nowMs < schedule.expiresAtMs()) {
      val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(schedule.timeZone)).toLocalDate()
      for (offset in 0L..7L) occurrences(schedule, date.plusDays(offset)).forEach {
        if (it.startAtMs > nowMs) candidates += it.startAtMs
        if (it.endAtMs > nowMs) candidates += it.endAtMs
      }
      if (schedule.expiresAtMs() > nowMs) candidates += schedule.expiresAtMs()
      val begins = LocalDate.parse(schedule.validFrom).atStartOfDay(ZoneId.of(schedule.timeZone)).toInstant().toEpochMilli()
      if (begins > nowMs) candidates += begins
    }
    if (learned) {
      val local = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(zone))
      candidates += local.withSecond(0).withNano(0).minusMinutes((local.minute % 30).toLong())
        .plusMinutes(30).toInstant().toEpochMilli()
    }
    return candidates.filter { it > nowMs }.minOrNull()
  }
}

/** No raw calendar title, package, SSID, location, audio or camera data is retained. */
data class FocusFeatures(val values: List<Double>) {
  fun valid(): Boolean = values.size == SIZE && values.all { it.isFinite() && it in 0.0..1.0 }

  companion object {
    const val SIZE = 14
    fun at(nowMs: Long, zone: String, scheduled: Boolean, context: ContextSnapshot?): FocusFeatures {
      val local = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(zone))
      val fresh = context?.takeIf { nowMs - it.capturedAtMs in 0L..120_000L }
      return FocusFeatures((1..7).map { if (local.dayOfWeek.value == it) 1.0 else 0.0 } + listOf(
        (local.hour * 60 + local.minute) / 1440.0,
        if (scheduled) 1.0 else 0.0,
        if (fresh?.calendarAccessGranted == true && fresh.calendarBusy && "calendar_read_failed" !in fresh.captureWarnings) 1.0 else 0.0,
        if (fresh?.calendarAccessGranted == true && "calendar_read_failed" !in fresh.captureWarnings) 1.0 else 0.0,
        if (fresh?.headphonesConnected == true) 1.0 else 0.0,
        if (fresh?.charging == true) 1.0 else 0.0,
        if (fresh?.usageAccessGranted == true && !fresh.foregroundPackage.isNullOrBlank()) 1.0 else 0.0
      ))
    }
  }
}

enum class FocusLabelSource { TIMETABLE_PRIOR, USER_ON, USER_OFF, USER_SKIP }

data class FocusSample(
  val id: String,
  val atMs: Long,
  val features: FocusFeatures,
  val focused: Boolean,
  val source: FocusLabelSource,
  val scheduleRevision: String? = null
)

object FocusLearning {
  const val MAX_SAMPLES = 256
  const val RETENTION_MS = 45L * 24 * 60 * 60 * 1000

  fun usable(samples: List<FocusSample>, schedule: FocusSchedule?, nowMs: Long): List<FocusSample> =
    samples.asSequence().filter {
      it.features.valid() && it.atMs > 0 && nowMs - it.atMs in 0L..RETENTION_MS &&
        (it.source != FocusLabelSource.TIMETABLE_PRIOR ||
          (schedule?.validAt(nowMs) == true && it.scheduleRevision == schedule.revision))
    }.distinctBy { it.id }.sortedWith(compareBy<FocusSample> {
      if (it.source == FocusLabelSource.TIMETABLE_PRIOR) 1 else 0
    }.thenByDescending { it.atMs }).take(MAX_SAMPLES).toList()

  /** At most 128 weak prior rows, all <= now; predictions never enter this function as labels. */
  fun priors(schedule: FocusSchedule?, nowMs: Long): List<FocusSample> {
    if (schedule == null || !schedule.validAt(nowMs)) return emptyList()
    val zone = ZoneId.of(schedule.timeZone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val positive = mutableListOf<FocusSample>()
    val negative = mutableListOf<FocusSample>()
    for (offset in 0L..27L) {
      val date = today.minusDays(offset)
      val occurrences = FocusTimetable.occurrences(schedule, date)
      occurrences.forEach { occurrence ->
        val at = occurrence.startAtMs
        if (at <= nowMs) positive += FocusSample("p:${occurrence.key}", at,
          FocusFeatures.at(at, schedule.timeZone, true, null), true, FocusLabelSource.TIMETABLE_PRIOR, schedule.revision)
      }
      // Reviewed schedule is a weak non-focus expectation outside entries, not measured behavior.
      if (date >= LocalDate.parse(schedule.validFrom) && date <= LocalDate.parse(schedule.validUntil)) {
        for (hour in listOf(7, 12, 17, 21)) {
          val at = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
          if (at <= nowMs && occurrences.none { at >= it.startAtMs && at < it.endAtMs }) {
            negative += FocusSample("n:${schedule.revision}:$date:$hour", at,
              FocusFeatures.at(at, schedule.timeZone, false, null), false, FocusLabelSource.TIMETABLE_PRIOR, schedule.revision)
          }
        }
      }
    }
    return positive.take(64) + negative.take(64)
  }
}

data class FocusStump(val feature: Int, val threshold: Double, val left: Double, val right: Double) {
  fun value(x: FocusFeatures): Double = if (x.values[feature] <= threshold) left else right
}

/** Small logistic gradient-boosted classifier: bounded CPU-only fitting, 24 depth-one trees. */
data class FocusModel(val intercept: Double, val trees: List<FocusStump>, val trainedAtMs: Long,
  val priorCount: Int, val feedbackCount: Int, val positiveFeedbackCount: Int,
  val positiveTimeBins: Map<String, Int> = emptyMap(), val positiveCalendarFeedbackCount: Int = 0) {
  fun probability(x: FocusFeatures): Double = if (!x.valid()) 0.0 else sigmoid(intercept + trees.sumOf { it.value(x) })

  companion object {
    fun train(samples: List<FocusSample>, nowMs: Long): FocusModel {
      val rows = samples.filter { it.features.valid() && it.atMs > 0 && nowMs - it.atMs in 0L..FocusLearning.RETENTION_MS }
        .take(FocusLearning.MAX_SAMPLES)
      if (rows.isEmpty()) return FocusModel(-4.0, emptyList(), 0, 0, 0, 0)
      // One explicit user choice outweighs many weak imported expectations. Recent choices win.
      val weights = rows.map {
        if (it.source == FocusLabelSource.TIMETABLE_PRIOR) 0.35 else
          24.0 * exp(-((nowMs - it.atMs).coerceAtLeast(0L) / (14.0 * 24 * 60 * 60 * 1000)))
      }
      val initial = ((rows.indices.sumOf { if (rows[it].focused) weights[it] else 0.0 } + 1.0) /
        (weights.sum() + 2.0)).coerceIn(0.02, 0.98)
      val intercept = ln(initial / (1 - initial))
      val scores = DoubleArray(rows.size) { intercept }
      val trees = mutableListOf<FocusStump>()
      repeat(24) {
        val gradients = DoubleArray(rows.size)
        val hessians = DoubleArray(rows.size)
        rows.indices.forEach { i ->
          val p = sigmoid(scores[i])
          gradients[i] = weights[i] * ((if (rows[i].focused) 1.0 else 0.0) - p)
          hessians[i] = weights[i] * p * (1 - p)
        }
        var best: FocusStump? = null
        var bestGain = Double.NEGATIVE_INFINITY
        for (feature in 0 until FocusFeatures.SIZE) {
          val values = rows.map { it.features.values[feature] }.distinct().sorted()
          // Max 49 thresholds per feature (half-hour boundaries for the time feature).
          val thresholds = if (feature == 7) (0..48).map { it / 48.0 } else
            values.zipWithNext { a, b -> (a + b) / 2 }.ifEmpty { listOf(0.5) }
          for (threshold in thresholds) {
            var gl = 0.0; var hl = 0.0; var gr = 0.0; var hr = 0.0
            rows.indices.forEach { i ->
              if (rows[i].features.values[feature] <= threshold) { gl += gradients[i]; hl += hessians[i] }
              else { gr += gradients[i]; hr += hessians[i] }
            }
            val gain = gl * gl / (hl + 1.0) + gr * gr / (hr + 1.0)
            if (gain > bestGain) {
              bestGain = gain
              best = FocusStump(feature, threshold, 0.25 * (gl / (hl + 1.0)).coerceIn(-2.0, 2.0),
                0.25 * (gr / (hr + 1.0)).coerceIn(-2.0, 2.0))
            }
          }
        }
        best?.let { stump -> trees += stump; rows.indices.forEach { scores[it] += stump.value(rows[it].features) } }
      }
      val positiveChoices = rows.filter { it.source == FocusLabelSource.USER_ON && it.focused }
      val bins = positiveChoices.groupingBy {
        "${it.features.values.take(7).indexOfFirst { value -> value == 1.0 } + 1}:${(it.features.values[7] * 48 + 1e-9).toInt().coerceIn(0, 47)}"
      }.eachCount()
      return FocusModel(intercept, trees, nowMs, rows.count { it.source == FocusLabelSource.TIMETABLE_PRIOR },
        rows.count { it.source != FocusLabelSource.TIMETABLE_PRIOR },
        positiveChoices.size, bins, positiveChoices.count { it.features.values[9] == 1.0 })
    }
  }
}

private fun sigmoid(value: Double): Double = 1.0 / (1.0 + exp(-value.coerceIn(-20.0, 20.0)))

data class FocusPrediction(val occurrenceKey: String, val reason: String, val source: String,
  val confidence: Double, val probability: Double, val endAtMs: Long, val features: FocusFeatures) {
  /** Source/evidence changes invalidate the warning even when the output probability is close. */
  fun signature(): String = "$occurrenceKey|$source|${features.values.joinToString(",") { if (it > 0.5) "1" else "0" }}"
}

object FocusStatusMetrics {
  /** Absence is unknown, never a fabricated zero-probability/high-interruptibility result. */
  fun from(prediction: FocusPrediction?): Map<String, Any?> = mapOf(
    "probability" to prediction?.probability, "confidence" to prediction?.confidence
  )
}

object FocusPredictor {
  fun predict(schedule: FocusSchedule?, model: FocusModel, nowMs: Long, zone: String,
    context: ContextSnapshot): FocusPrediction? {
    if (nowMs - context.capturedAtMs !in 0L..120_000L || model.trainedAtMs <= 0 ||
      nowMs - model.trainedAtMs !in 0L..FocusLearning.RETENTION_MS) return null
    val occurrence = FocusTimetable.current(schedule, nowMs)
    val features = FocusFeatures.at(nowMs, zone, occurrence != null, context)
    val probability = model.probability(features)
    if (probability < 0.72) return null
    // No extrapolated automatic action from only timetable labels outside reviewed occurrences.
    if (occurrence == null && model.positiveFeedbackCount < 3) return null
    val local = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(zone))
    val calendar = features.values[9] == 1.0
    // A single-class dataset can fit a high constant score. Never turn three On choices at
    // one time into an all-day routine: require support in this weekly bin, or repeated real
    // busy-calendar confirmations, before extrapolating outside a reviewed occurrence.
    if (occurrence == null && (model.positiveTimeBins["${local.dayOfWeek.value}:${(local.hour * 60 + local.minute) / 30}"] ?: 0) < 3 &&
      !(calendar && model.positiveCalendarFeedbackCount >= 3)) return null
    val binMinutes = if (calendar) 15 else 30
    val binStart = local.withSecond(0).withNano(0).minusMinutes((local.minute % binMinutes).toLong()).toInstant().toEpochMilli()
    val source = when {
      model.feedbackCount > 0 && occurrence != null -> "personalized_timetable_local_gbdt"
      occurrence != null -> "timetable_prior_local_gbdt"
      calendar -> "personalized_calendar_local_gbdt"
      else -> "personalized_time_local_gbdt"
    }
    val end = occurrence?.endAtMs ?: (binStart + binMinutes * 60_000L)
    if (end <= nowMs) return null
    // A bounded evidence-strength estimate, deliberately not a calibrated statistical certainty.
    val confidence = (abs(probability - 0.5) * 1.5 + min(model.feedbackCount, 10) * 0.02).coerceIn(0.0, 0.90)
    return FocusPrediction(occurrence?.key ?: "${if (calendar) "c" else "b"}:$binStart", if (occurrence != null) "reviewed_timetable_prediction" else
      if (calendar) "learned_busy_calendar_prediction" else "learned_focus_time_prediction", source, confidence, probability, end, features)
  }
}

data class FocusPending(val id: String, val createdAtMs: Long, val createdElapsedMs: Long,
  val prediction: FocusPrediction, val ruleRevision: String, val confirmed: Boolean = true) {
  val deadlineAtMs: Long get() = createdAtMs + COUNTDOWN_MS
  val deadlineElapsedMs: Long get() = createdElapsedMs + COUNTDOWN_MS
  val expiresAtMs: Long get() = deadlineAtMs + MAX_LATENESS_MS
  fun toMap(): Map<String, Any?> = mapOf("id" to id, "createdAtMs" to createdAtMs,
    "deadlineAtMs" to deadlineAtMs, "expiresAtMs" to expiresAtMs, "reason" to prediction.reason,
    "source" to prediction.source, "confidence" to prediction.confidence, "endAtMs" to prediction.endAtMs,
    "confirmed" to confirmed)

  companion object { const val COUNTDOWN_MS = 60_000L; const val MAX_LATENESS_MS = 15_000L }
}

enum class FocusDeadline { WAIT, APPLY, DROP }

object FocusCountdownSafety {
  fun canArm(prediction: FocusPrediction, nowMs: Long): Boolean =
    prediction.endAtMs > nowMs + FocusPending.COUNTDOWN_MS + FocusPending.MAX_LATENESS_MS

  fun check(pending: FocusPending, token: String, nowMs: Long, elapsedMs: Long,
    enabled: Boolean, warningVisible: Boolean, policyAccess: Boolean, ruleRevision: String,
    current: FocusPrediction?): FocusDeadline {
    if (token != pending.id || !enabled || !warningVisible || !policyAccess ||
      ruleRevision != pending.ruleRevision || current == null || current.signature() != pending.prediction.signature() ||
      nowMs < pending.createdAtMs || elapsedMs < pending.createdElapsedMs ||
      abs((nowMs - pending.createdAtMs) - (elapsedMs - pending.createdElapsedMs)) > 2_000L ||
      elapsedMs > pending.deadlineElapsedMs + FocusPending.MAX_LATENESS_MS ||
      nowMs >= pending.prediction.endAtMs || current.endAtMs != pending.prediction.endAtMs) return FocusDeadline.DROP
    return if (!pending.confirmed || elapsedMs < pending.deadlineElapsedMs) FocusDeadline.WAIT else FocusDeadline.APPLY
  }

  fun canSkip(pending: FocusPending?, token: String, nowMs: Long, elapsedMs: Long): Boolean =
    pending != null && pending.id == token && nowMs >= pending.createdAtMs && nowMs <= pending.expiresAtMs &&
      elapsedMs >= pending.createdElapsedMs && elapsedMs <= pending.deadlineElapsedMs + FocusPending.MAX_LATENESS_MS &&
      abs((nowMs - pending.createdAtMs) - (elapsedMs - pending.createdElapsedMs)) <= 2_000L
}

/** Pure deletion transaction plan: platform restoration is only needed for automatic ownership. */
object FocusDeletionSafety {
  fun reset(state: FocusState, ruleRevision: String, timeZone: String): FocusState = state.copy(
    enabled = false, schedule = null, samples = emptyList(), model = FocusModel(-4.0, emptyList(), 0, 0, 0, 0),
    ruleRevision = ruleRevision, timeZone = timeZone, suppressions = emptyList(), cooldownUntilMs = 0
  )
  fun restoreAutomaticOnly(owned: FocusOwnedSession?): Boolean = owned?.origin == "automatic"
  fun succeeded(saved: Boolean, remainingOwned: FocusOwnedSession?): Boolean =
    saved && !restoreAutomaticOnly(remainingOwned)
}

/** Android constants copied as values so ownership decisions can be tested without the SDK. */
object FocusOwnershipSafety {
  fun matches(attemptedAtMs: Long, capturedAtMs: Long, phase: String,
    sharedCapturedAtMs: Long?, sharedPriorFilter: Int?, ownerId: String? = null, sharedOwnerId: String? = null): Boolean {
    if (sharedCapturedAtMs == null || sharedCapturedAtMs <= 0 || sharedPriorFilter != 1) return false // ALL
    if (ownerId != null) return ownerId == sharedOwnerId
    return if (capturedAtMs > 0) sharedCapturedAtMs == capturedAtMs else
      phase == "prepared" && sharedCapturedAtMs in attemptedAtMs..(attemptedAtMs + 5_000L)
  }

  fun userOverride(phase: String, implicitZen: Boolean, currentFilter: Int?): Boolean {
    if (implicitZen || currentFilter == null || currentFilter == 0) return false
    // Global NONE/ALARMS are user overrides even during crash recovery. ALL during an active
    // session means the user turned it off. ALL during partial-write recovery still permits
    // restoring the owned policy snapshot, without changing an unrelated active user filter.
    return currentFilter == 3 || currentFilter == 4 || (currentFilter == 1 && phase in setOf("active", "updating"))
  }

  fun policyOverride(phase: String, implicitZen: Boolean, expected: String?, current: String?,
    updating: String? = null, prior: String? = null): Boolean {
    if (implicitZen || current == null) return false
    val known = when (phase) {
      "active" -> listOfNotNull(expected)
      "updating" -> listOfNotNull(expected, updating)
      "prepared", "restoring" -> listOfNotNull(expected, updating, prior)
      else -> emptyList()
    }
    return current !in known
  }

  /** A fresh baseline must still belong to the original phase; equality alone is not ownership. */
  fun baselineOverride(owned: FocusOwnedSession, implicitZen: Boolean, currentFilter: Int?,
    currentPolicy: String?, priorPolicy: String? = null): Boolean {
    val authorization = owned.restoreAuthorization
    val phase = authorization?.phase ?: owned.phase
    return userOverride(phase, implicitZen, currentFilter) || policyOverride(phase, implicitZen,
      if (authorization != null) authorization.appliedPolicyKey else owned.appliedPolicyKey, currentPolicy,
      if (authorization != null) authorization.updatePolicyKey else owned.updatePolicyKey, priorPolicy)
  }

  // No global phase can prove ownership across a process-death observation gap.
  fun relinquishAfterObservationGap(implicitZen: Boolean, owned: FocusOwnedSession?): Boolean = !implicitZen && owned != null
}

/** Retain the original authorization until Android's policy write has actually been attempted. */
object FocusRestorationSafety {
  fun prepare(owned: FocusOwnedSession): FocusOwnedSession = owned.copy(restoreAuthorization =
    owned.restoreAuthorization ?: FocusRestoreAuthorization(owned.phase, owned.appliedPolicyKey, owned.updatePolicyKey))

  fun afterAttempt(owned: FocusOwnedSession, policyWriteAttempted: Boolean,
    verifiedBeforePolicy: String? = null): FocusOwnedSession =
    if (policyWriteAttempted) owned.copy(restoreAuthorization = null,
      appliedPolicyKey = verifiedBeforePolicy ?: owned.appliedPolicyKey) else owned
}

enum class FocusManualAction { END_OWNED, START_IF_SAVED, BLOCK_START }

/** Feedback storage is never a prerequisite for reducing our own DND effect. */
object FocusManualSafety {
  fun action(enabled: Boolean, feedbackSaved: Boolean): FocusManualAction = when {
    !enabled -> FocusManualAction.END_OWNED
    feedbackSaved -> FocusManualAction.START_IF_SAVED
    else -> FocusManualAction.BLOCK_START
  }

  fun afterUpdate(owned: FocusOwnedSession, applied: Boolean, recoveryRequired: Boolean, failureReason: String,
    verifiedBeforePolicy: String? = null): FocusOwnedSession =
    owned.copy(phase = if (applied || !recoveryRequired) "active" else "updating",
      appliedPolicyKey = if (applied) owned.updatePolicyKey else if (!recoveryRequired)
        verifiedBeforePolicy ?: owned.appliedPolicyKey else owned.appliedPolicyKey,
      policyUpdatePending = !applied, policyUpdateReason = if (applied) null else failureReason)
}

/** A broadcast is not proof of our write merely because the final filter looks identical. */
object FocusChangeSafety {
  const val RECEIPT_TTL_MS = 30_000L
  const val RECEIPT_MAX_SPAN_MS = 120_000L
  const val MAX_RECEIPTS = 64
  fun observationAvailable(policyChanged: Boolean, observed: String?): Boolean =
    observed != null && (policyChanged || observed in setOf("1", "2", "3", "4"))
  fun expected(pendingCount: Int, receivedAtMs: Long, untilMs: Long, expected: String?, observed: String?): Boolean =
    pendingCount > 0 && receivedAtMs <= untilMs && expected != null && expected == observed

  fun begin(owned: FocusOwnedSession, phase: String, filter: Int, policy: String?, currentFilter: Int?,
    currentPolicy: String?, nowMs: Long, transactionId: String, implicitZen: Boolean): FocusOwnedSession {
    // A broadcast captured within its TTL can still be queued behind this transaction.
    // Keep a bounded ledger; receiptIndex checks capture time, never processing time.
    val receipts = owned.receipts.orEmpty().toMutableList()
    if (!implicitZen) {
      if (currentFilter != filter) receipts += FocusDndReceipt(transactionId, false, filter.toString(), nowMs, nowMs + RECEIPT_TTL_MS)
      if (policy != null && currentPolicy != policy) receipts += FocusDndReceipt(transactionId, true, policy, nowMs, nowMs + RECEIPT_TTL_MS)
    }
    return owned.copy(phase = phase, updatePolicyKey = policy, expectedFilter = filter,
      writeTransactionId = transactionId, writeStartedAtMs = nowMs, receipts = receipts.takeLast(MAX_RECEIPTS))
  }

  fun settle(owned: FocusOwnedSession, policyAttempted: Boolean, filterAttempted: Boolean,
    rollbackAttempted: Boolean, beforePolicy: String?, implicitZen: Boolean,
    completedAtMs: Long = owned.writeStartedAtMs): FocusOwnedSession {
    val receipts = owned.receipts.orEmpty().filterNot {
      it.transactionId == owned.writeTransactionId && (if (it.policyChanged) !policyAttempted else !filterAttempted)
    }.map { if (it.transactionId == owned.writeTransactionId) it.copy(untilMs =
      (completedAtMs + RECEIPT_TTL_MS).coerceAtLeast(it.untilMs).coerceAtMost(it.notBeforeMs + RECEIPT_MAX_SPAN_MS)) else it }.toMutableList()
    // Rollback filter writes require an unchanged pre-update filter, so they are no-ops.
    // Its policy write may change the policy back and has an independently expected receipt.
    if (!implicitZen && rollbackAttempted && beforePolicy != null && beforePolicy != owned.updatePolicyKey) {
      owned.writeTransactionId?.let { receipts += FocusDndReceipt(it, true, beforePolicy,
        owned.writeStartedAtMs, (completedAtMs + RECEIPT_TTL_MS).coerceAtMost(owned.writeStartedAtMs + RECEIPT_MAX_SPAN_MS)) }
    }
    return owned.copy(receipts = receipts.takeLast(MAX_RECEIPTS))
  }

  fun receiptIndex(owned: FocusOwnedSession, policyChanged: Boolean, observed: String?, receivedAtMs: Long): Int =
    owned.receipts.orEmpty().indexOfFirst { it.policyChanged == policyChanged && it.value == observed &&
      receivedAtMs in it.notBeforeMs..it.untilMs }

  // Manual writes can wait on storage too. Expired preparation is never permission to mutate.
  fun writeWindowOpen(owned: FocusOwnedSession, nowMs: Long): Boolean = nowMs - owned.writeStartedAtMs in 0..RECEIPT_TTL_MS
}

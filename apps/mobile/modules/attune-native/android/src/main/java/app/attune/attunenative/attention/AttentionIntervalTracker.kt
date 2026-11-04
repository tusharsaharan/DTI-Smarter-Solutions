package app.attune.attunenative.attention

data class AttentionTick(
  val elapsedMs: Long,
  val wallMs: Long,
  val dayStartMs: Long,
  val foreground: Boolean,
  val classification: AttentionClassification
)

data class AttentionInterval(val dayStartMs: Long, val durationMs: Long, val classification: AttentionClassification)

/** Never extrapolate across service gaps, clock changes, screen locks, or uncertain transitions. */
class AttentionIntervalTracker {
  private var previous: AttentionTick? = null

  fun reset() { previous = null }

  fun advance(tick: AttentionTick): AttentionInterval? {
    val prior = previous
    previous = tick
    if (prior == null || !prior.foreground || !tick.foreground) return null
    val elapsed = tick.elapsedMs - prior.elapsedMs
    if (elapsed !in 1L..2_500L || kotlin.math.abs((tick.wallMs - prior.wallMs) - elapsed) > 1_000L) return null
    if (prior.dayStartMs != tick.dayStartMs) return null
    val same = prior.classification.label == tick.classification.label && prior.classification.source == tick.classification.source
    val classification = if (same) tick.classification.copy(confidence = minOf(prior.classification.confidence, tick.classification.confidence))
      else AttentionClassification(AttentionLabel.UNKNOWN, 0f, "transition", "attention_changed_between_samples")
    return AttentionInterval(tick.dayStartMs, elapsed, classification)
  }
}

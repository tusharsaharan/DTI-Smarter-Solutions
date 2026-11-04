package app.attune.attunenative.notification

import kotlin.math.exp
import kotlin.math.sqrt

/** Frozen encoder + small locally trained intent head, with independent uncertainty gates. */
class SemanticIntentHead(
  private val classes: List<NotificationIntent>,
  private val weights: List<FloatArray>,
  private val bias: FloatArray,
  private val temperature: Float,
  private val minimumConfidence: Float,
  private val minimumMargin: Float,
  private val minimumOODSimilarity: Float,
  private val supportVectors: List<FloatArray>
) {
  init {
    require(classes.size >= 2 && classes.distinct().size == classes.size)
    require(weights.size == classes.size && bias.size == classes.size)
    require(temperature.isFinite() && temperature > 0f)
    require(minimumConfidence in 0f..1f && minimumMargin in 0f..1f && minimumOODSimilarity in -1f..1f)
    val dimensions = weights.first().size
    require(dimensions > 0 && weights.all { it.size == dimensions && it.all(Float::isFinite) })
    require(bias.all(Float::isFinite) && supportVectors.isNotEmpty())
    require(supportVectors.all { it.size == dimensions && it.all(Float::isFinite) })
  }

  fun match(input: FloatArray): SemanticIntentMatch? {
    if (input.size != weights.first().size || input.any { !it.isFinite() }) return null
    val norm = sqrt(input.sumOf { it.toDouble() * it }).toFloat()
    if (!norm.isFinite() || norm < 1e-6f) return null
    val vector = FloatArray(input.size) { input[it] / norm }
    fun dot(other: FloatArray): Float = vector.indices.sumOf { vector[it].toDouble() * other[it] }.toFloat()
    if (supportVectors.maxOf(::dot) < minimumOODSimilarity) return null
    val logits = weights.indices.map { (dot(weights[it]) + bias[it]) / temperature }
    val maximum = logits.max()
    val unnormalized = logits.map { exp((it - maximum).toDouble()) }
    val denominator = unnormalized.sum()
    if (!denominator.isFinite() || denominator <= 0.0) return null
    val probabilities = unnormalized.map { (it / denominator).toFloat() }
    val order = probabilities.indices.sortedByDescending { probabilities[it] }
    val best = probabilities[order[0]]
    val margin = best - probabilities[order[1]]
    return if (best >= minimumConfidence && margin >= minimumMargin)
      SemanticIntentMatch(classes[order[0]], best, margin) else null
  }

  /**
   * Builds a bounded conversation representation without allowing old messages to outweigh the
   * newest one. The caller supplies context newest-first; all vectors remain on-device.
   */
  fun matchContext(current: FloatArray, contextNewestFirst: List<FloatArray>): SemanticIntentMatch? {
    if (current.size != weights.first().size || current.any { !it.isFinite() }) return null
    val bounded = contextNewestFirst.take(3).filter { it.size == current.size && it.all(Float::isFinite) }
    if (bounded.isEmpty()) return match(current)
    val combined = FloatArray(current.size)
    addWeighted(combined, current, 0.68f)
    val totalContextWeight = 0.32f
    val denominator = bounded.indices.sumOf { index -> (bounded.size - index).toDouble() }
      .toFloat()
      .coerceAtLeast(1f)
    bounded.forEachIndexed { index, vector ->
      val weight = totalContextWeight * (bounded.size - index).toFloat() / denominator
      addWeighted(combined, vector, weight)
    }
    return match(combined)?.copy(contextual = true)
  }

  private fun addWeighted(target: FloatArray, value: FloatArray, weight: Float) {
    value.indices.forEach { index -> target[index] += value[index] * weight }
  }
}

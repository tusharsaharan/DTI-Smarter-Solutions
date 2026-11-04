package app.attune.attunenative.ambient

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Context that may safely affect the volume decision. It deliberately contains no calendar
 * title, location name, app content, or speaker identity.
 */
data class AmbientContext(
  val calendarBusy: Boolean = false,
  val focusActive: Boolean = false,
  val foregroundPackage: String? = null
) {
  val protectsSpeech: Boolean
    get() = calendarBusy || focusActive || foregroundPackage.isCommunicationApp()

  private fun String?.isCommunicationApp(): Boolean {
    val packageName = this?.lowercase() ?: return false
    return packageName in setOf(
      "com.google.android.apps.meetings",
      "com.google.android.apps.tachyon",
      "com.microsoft.teams",
      "us.zoom.videomeetings",
      "com.whatsapp",
      "org.telegram.messenger"
    )
  }
}

data class AmbientClassification(
  val backgroundDb: Float,
  val environment: String,
  val speechDetected: Boolean,
  val protectedContext: Boolean,
  val confidence: Float,
  val reason: String,
  val source: String = "microphone_acoustic_features"
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "backgroundDb" to backgroundDb,
    "environment" to environment,
    "speechDetected" to speechDetected,
    "protectedContext" to protectedContext,
    "confidence" to confidence,
    "reason" to reason,
    "source" to source
  )
}

private data class FrameFeatures(
  val db: Float,
  val zeroCrossingRate: Float,
  val periodicity: Float
)

private data class SpectrumFeatures(
  val lowRatio: Float,
  val voiceRatio: Float,
  val highRatio: Float,
  val harmonicity: Float
)

/**
 * Small, deterministic acoustic front-end for the one-second microphone window.
 *
 * This follows the useful part of a hands-free/noise-suppression pipeline without pretending a
 * phone with one exported channel can do speaker identification: short-time voice activity,
 * periodicity, spectral bands, and a robust non-voice noise floor. The PCM window is owned by the
 * caller and is not retained here.
 */
object AmbientAcousticClassifier {
  private const val FRAME_MS = 20
  private const val QUIET_DB = 42f
  private const val CONVERSATION_DB = 50f
  private const val STREET_DB = 58f
  private const val TRANSIT_DB = 68f

  fun classify(
    samples: ShortArray,
    sampleRate: Int,
    context: AmbientContext = AmbientContext()
  ): AmbientClassification {
    if (samples.isEmpty() || sampleRate <= 0) {
      return AmbientClassification(
        backgroundDb = Float.NaN,
        environment = "unknown",
        speechDetected = false,
        protectedContext = context.protectsSpeech,
        confidence = 0f,
        reason = "audio_window_invalid"
      )
    }

    val frameSize = (sampleRate * FRAME_MS / 1_000).coerceAtLeast(80)
    val frames = samples.indices
      .step(frameSize)
      .mapNotNull { start ->
        val end = min(samples.size, start + frameSize)
        if (end - start < frameSize / 2) return@mapNotNull null
        frameFeatures(samples, start, end, sampleRate)
      }
    if (frames.isEmpty()) {
      return AmbientClassification(
        backgroundDb = Float.NaN,
        environment = "unknown",
        speechDetected = false,
        protectedContext = context.protectsSpeech,
        confidence = 0f,
        reason = "audio_window_too_short"
      )
    }

    val loudFrames = frames.filter { it.db >= 35f }
    val voicedFrames = loudFrames.filter { isSpeechLike(it) }
    val speechRatio = voicedFrames.size.toFloat() / frames.size.toFloat()
    // A lone voiced frame is not enough to call someone speaking; this avoids reacting to a tap,
    // cough, or a short notification sound.
    val speechDetected = voicedFrames.size >= 2 && speechRatio >= 0.08f

    // Use the non-voice frames for the ambient floor. When speech fills the whole window, the
    // lower quartile is the least contaminated estimate of the room rather than the voice peak.
    val nonVoiceFrames = frames.filterNot { isSpeechLike(it) }
    val floorFrames = nonVoiceFrames.ifEmpty { frames }
    val rawBackgroundDb = percentile(floorFrames.map { it.db }, 0.25f)
    // If the entire second is a stable voice, the true room floor is unobservable. Treat it as
    // quiet rather than using the voice peak to make the ringer louder; later windows can recover
    // as soon as non-speech evidence appears.
    val speechDominatesWindow = speechDetected && voicedFrames.size >= frames.size / 2 && nonVoiceFrames.size < 3
    val backgroundDb = if (speechDominatesWindow) {
      min(rawBackgroundDb, QUIET_DB - 1f)
    } else {
      rawBackgroundDb
    }
    val overallDb = percentile(frames.map { it.db }, 0.75f)
    val spectrum = spectrumFeatures(samples, sampleRate)
    val stableHarmonicSignal = spectrum.harmonicity >= 0.56f && spectrum.voiceRatio >= 0.38f

    val environment: String
    val kindReason: String
    when {
      // Calendar/Focus is the strongest available explanation for a single lecturer or presenter.
      // We do not need (and must not attempt) to recognize that person's voice.
      speechDetected && context.protectsSpeech && backgroundDb < STREET_DB -> {
        environment = "quiet"
        kindReason = "speech_masked_in_protected_context"
      }
      backgroundDb < QUIET_DB -> {
        environment = "quiet"
        kindReason = if (speechDetected) "speech_masked_from_quiet_floor" else "low_background_floor"
      }
      stableHarmonicSignal && overallDb >= CONVERSATION_DB -> {
        environment = "music"
        kindReason = "stable_harmonic_spectrum"
      }
      backgroundDb >= TRANSIT_DB && spectrum.lowRatio >= 0.28f -> {
        environment = "transit"
        kindReason = "high_low_frequency_background"
      }
      speechDetected && backgroundDb < STREET_DB -> {
        environment = "conversation"
        kindReason = "speech_and_elevated_background_floor"
      }
      backgroundDb >= STREET_DB -> {
        environment = "street"
        kindReason = if (spectrum.highRatio >= 0.24f) "broadband_high_frequency_background" else "elevated_background_floor"
      }
      backgroundDb >= CONVERSATION_DB -> {
        environment = "conversation"
        kindReason = "moderate_background_floor"
      }
      else -> {
        environment = "unknown"
        kindReason = "ambiguous_acoustic_scene"
      }
    }

    val margin = when (environment) {
      "quiet" -> (QUIET_DB - backgroundDb).coerceAtLeast(0f)
      "conversation" -> (backgroundDb - CONVERSATION_DB).coerceAtLeast(0f)
      "street" -> (backgroundDb - STREET_DB).coerceAtLeast(0f)
      "transit" -> (backgroundDb - TRANSIT_DB).coerceAtLeast(0f)
      else -> 0f
    }
    val confidenceBase = if (environment == "unknown") 0.18f else 0.42f
    val confidence = (
      confidenceBase + (margin / 28f).coerceIn(0f, 0.30f) +
        (if (speechDetected) 0.12f else 0f) +
        (if (context.protectsSpeech && speechDetected) 0.10f else 0f)
      ).coerceIn(0f, 0.92f)

    return AmbientClassification(
      backgroundDb = backgroundDb,
      environment = environment,
      speechDetected = speechDetected,
      protectedContext = context.protectsSpeech,
      confidence = confidence,
      reason = kindReason
    )
  }

  private fun frameFeatures(samples: ShortArray, start: Int, end: Int, sampleRate: Int): FrameFeatures {
    var sumSquares = 0.0
    var crossings = 0
    var previous = samples[start].toFloat()
    for (index in start until end) {
      val value = samples[index].toDouble() / Short.MAX_VALUE.toDouble()
      sumSquares += value * value
      if (index > start) {
        val current = samples[index].toFloat()
        if ((current >= 0f && previous < 0f) || (current < 0f && previous >= 0f)) crossings += 1
        previous = current
      }
    }
    val count = (end - start).coerceAtLeast(1)
    val rms = sqrt(sumSquares / count).coerceAtLeast(1e-7)
    val db = (20.0 * kotlin.math.log10(rms) + 90.0).toFloat()
    val zcr = crossings.toFloat() / (count - 1).coerceAtLeast(1).toFloat()
    val periodicity = autocorrelationPeak(samples, start, end, sampleRate)
    return FrameFeatures(db, zcr, periodicity)
  }

  private fun isSpeechLike(frame: FrameFeatures): Boolean =
    frame.db >= 38f && frame.zeroCrossingRate in 0.015f..0.32f && frame.periodicity >= 0.22f

  private fun autocorrelationPeak(samples: ShortArray, start: Int, end: Int, sampleRate: Int): Float {
    val minLag = (sampleRate / 280).coerceAtLeast(2)
    val maxLag = (sampleRate / 80).coerceAtMost((end - start) / 2)
    if (maxLag <= minLag) return 0f
    var energy = 0.0
    for (index in start until end) {
      val value = samples[index].toDouble() / Short.MAX_VALUE.toDouble()
      energy += value * value
    }
    if (energy <= 1e-8) return 0f
    var best = 0.0
    var lag = minLag
    while (lag <= maxLag) {
      var correlation = 0.0
      var delayedEnergy = 0.0
      for (index in start until end - lag) {
        val left = samples[index].toDouble() / Short.MAX_VALUE.toDouble()
        val right = samples[index + lag].toDouble() / Short.MAX_VALUE.toDouble()
        correlation += left * right
        delayedEnergy += right * right
      }
      val normalized = correlation / sqrt(energy * delayedEnergy.coerceAtLeast(1e-8))
      best = max(best, normalized)
      lag += 2
    }
    return best.coerceIn(0.0, 1.0).toFloat()
  }

  private fun spectrumFeatures(samples: ShortArray, sampleRate: Int): SpectrumFeatures {
    val frequencies = intArrayOf(125, 250, 500, 1_000, 2_000, 3_000, 4_000, 6_000)
    val energies = frequencies.map { frequency -> goertzelEnergy(samples, sampleRate, frequency) }
    val total = energies.sum().coerceAtLeast(1e-9)
    val low = energies[0] + energies[1]
    val voice = energies[2] + energies[3] + energies[4] + energies[5]
    val high = energies[6] + energies[7]
    val harmonicity = energies.maxOrNull()?.div(total)?.coerceIn(0.0, 1.0) ?: 0.0
    return SpectrumFeatures(
      lowRatio = (low / total).toFloat(),
      voiceRatio = (voice / total).toFloat(),
      highRatio = (high / total).toFloat(),
      harmonicity = harmonicity.toFloat()
    )
  }

  private fun goertzelEnergy(samples: ShortArray, sampleRate: Int, frequency: Int): Double {
    if (frequency >= sampleRate / 2) return 0.0
    val coefficient = 2.0 * cos(2.0 * Math.PI * frequency.toDouble() / sampleRate.toDouble())
    var q1 = 0.0
    var q2 = 0.0
    // A bounded stride keeps the one-second sample cheap on lower-end phones while retaining the
    // relative band shape needed for the scene decision.
    val stride = max(1, samples.size / 4_000)
    var index = 0
    while (index < samples.size) {
      val q0 = coefficient * q1 - q2 + samples[index].toDouble() / Short.MAX_VALUE.toDouble()
      q2 = q1
      q1 = q0
      index += stride
    }
    return (q1 * q1 + q2 * q2 - coefficient * q1 * q2).coerceAtLeast(0.0)
  }

  private fun percentile(values: List<Float>, fraction: Float): Float {
    if (values.isEmpty()) return Float.NaN
    val sorted = values.filter { it.isFinite() }.sorted()
    if (sorted.isEmpty()) return Float.NaN
    val position = (sorted.lastIndex * fraction.coerceIn(0f, 1f)).toDouble()
    val lower = position.toInt()
    val upper = min(sorted.lastIndex, lower + 1)
    val weight = position - lower
    return (sorted[lower] * (1.0 - weight) + sorted[upper] * weight).toFloat()
  }
}

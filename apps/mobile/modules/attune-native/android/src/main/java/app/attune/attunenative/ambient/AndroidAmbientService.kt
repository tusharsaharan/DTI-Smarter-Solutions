package app.attune.attunenative.ambient

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import app.attune.attunenative.AttuneEventHub
import app.attune.attunenative.context.AndroidContextSnapshotProvider
import app.attune.attunenative.focus.FocusRuntime
import app.attune.attunenative.history.NativeHistoryStore
import kotlin.math.roundToInt

interface SystemVolumeController {
  fun minRingVolume(): Int = 0
  fun currentRingVolume(): Int
  fun maxRingVolume(): Int
  fun headphonesConnected(): Boolean
  fun ringerMode(): AmbientRingerMode = AmbientRingerMode.UNKNOWN
  fun setRingVolume(volume: Int, respectHeadphones: Boolean = true): Boolean
}

class AndroidSystemVolumeController(private val context: Context) : SystemVolumeController {
  private val audio: AudioManager?
    get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

  override fun minRingVolume(): Int {
    val manager = audio ?: return 0
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      manager.getStreamMinVolume(AudioManager.STREAM_RING)
    } else {
      0
    }
  }

  override fun currentRingVolume(): Int = audio?.getStreamVolume(AudioManager.STREAM_RING) ?: 0

  override fun maxRingVolume(): Int = audio?.getStreamMaxVolume(AudioManager.STREAM_RING) ?: 0

  override fun headphonesConnected(): Boolean = AndroidContextSnapshotProvider(context).snapshot().headphonesConnected

  override fun ringerMode(): AmbientRingerMode = when (audio?.ringerMode) {
    AudioManager.RINGER_MODE_NORMAL -> AmbientRingerMode.NORMAL
    AudioManager.RINGER_MODE_SILENT -> AmbientRingerMode.SILENT
    AudioManager.RINGER_MODE_VIBRATE -> AmbientRingerMode.VIBRATE
    else -> AmbientRingerMode.UNKNOWN
  }

  override fun setRingVolume(volume: Int, respectHeadphones: Boolean): Boolean {
    val manager = audio ?: return false
    if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL || (respectHeadphones && headphonesConnected())) return false
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_RING)
    val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      manager.getStreamMinVolume(AudioManager.STREAM_RING)
    } else {
      0
    }
    val bounded = volume.coerceIn(min, max)
    return runCatching {
      manager.setStreamVolume(AudioManager.STREAM_RING, bounded, 0)
      manager.getStreamVolume(AudioManager.STREAM_RING) == bounded
    }.getOrDefault(false)
  }
}

data class AmbientUserSettings(
  val minVolumeFraction: Float = 0.15f,
  val maxVolumeFraction: Float = 0.9f,
  val leaveHeadphonesAlone: Boolean = true
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "minVolumeFraction" to minVolumeFraction,
    "maxVolumeFraction" to maxVolumeFraction,
    "leaveHeadphonesAlone" to leaveHeadphonesAlone
  )
}

class AmbientUserSettingsStore(context: Context) {
  private val preferences = context.getSharedPreferences("attune_ambient_settings", Context.MODE_PRIVATE)

  fun read(): AmbientUserSettings = AmbientUserSettings(
    minVolumeFraction = preferences.getFloat("min_volume_fraction", 0.15f).coerceIn(0f, 1f),
    maxVolumeFraction = preferences.getFloat("max_volume_fraction", 0.9f).coerceIn(0f, 1f),
    leaveHeadphonesAlone = preferences.getBoolean("leave_headphones_alone", true)
  ).let { settings ->
    if (settings.maxVolumeFraction >= settings.minVolumeFraction) settings
    else settings.copy(maxVolumeFraction = (settings.minVolumeFraction + 0.05f).coerceAtMost(1f))
  }

  fun write(settings: AmbientUserSettings): Boolean {
    val min = settings.minVolumeFraction.coerceIn(0f, 0.95f)
    val max = settings.maxVolumeFraction.coerceIn(min + 0.05f, 1f)
    return preferences.edit()
      .putFloat("min_volume_fraction", min)
      .putFloat("max_volume_fraction", max)
      .putBoolean("leave_headphones_alone", settings.leaveHeadphonesAlone)
      .commit()
  }

  fun clear(): Boolean = preferences.edit().clear().commit()
}

object AmbientRuntime {
  @Volatile var running: Boolean = false
  @Volatile var lastDb: Float? = null
  @Volatile var lastDecision: AmbientVolumeDecision? = null
  @Volatile var environment: String? = null
  @Volatile var source: String? = null
  @Volatile var confidence: Float? = null
  @Volatile var lastReason: String? = null
  @Volatile var speechDetected: Boolean = false
  @Volatile var protectedContext: Boolean = false
  @Volatile var error: String? = null

  fun markUnavailable(reason: String) {
    val status = AmbientStatusFactory.unavailable(reason)
    running = status.running
    lastDb = status.db
    lastDecision = null
    environment = status.environment
    source = status.source
    confidence = status.confidence
    lastReason = null
    speechDetected = false
    protectedContext = false
    error = status.error
  }
}

class AmbientSampler(
  private val context: Context,
  private val volumeController: SystemVolumeController = AndroidSystemVolumeController(context),
  private val decisionController: AmbientVolumeController = AmbientVolumeController()
) {
  private val historyStore by lazy { NativeHistoryStore(context.filesDir.resolve("attune_history.json")) }
  private val sampleRate = 16_000
  private val recorderLock = Any()
  private val settingsStore = AmbientUserSettingsStore(context)
  private val contextProvider = AndroidContextSnapshotProvider(context)
  @Volatile private var stopRequested = false
  private var recorder: AudioRecord? = null

  fun hasMicrophonePermission(): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  fun shouldStopSampling(): Boolean = stopRequested

  fun sampleOnce(): AmbientVolumeDecision? = synchronized(recorderLock) {
    if (stopRequested) return@synchronized null
    if (!hasMicrophonePermission()) {
      AmbientRuntime.markUnavailable("microphone_permission_required")
      stopRequested = true
      return@synchronized null
    }
    val minBuffer = AudioRecord.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )
    if (minBuffer <= 0) {
      AmbientRuntime.markUnavailable("audio_record_unavailable")
      stopRequested = true
      return@synchronized null
    }
    // Keep the one-second privacy boundary explicit. The buffer is released after classification.
    val buffer = ShortArray(sampleRate.coerceAtLeast(minBuffer / 2))
    val localRecorder = runCatching {
      AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        buffer.size * 2
      )
    }.getOrElse {
      AmbientRuntime.markUnavailable("audio_record_create_failed")
      stopRequested = true
      return@synchronized null
    }
    recorder = localRecorder
    try {
      localRecorder.startRecording()
      val read = localRecorder.read(buffer, 0, buffer.size)
      if (read <= 0) {
        AmbientRuntime.markUnavailable("audio_sample_empty")
        stopRequested = true
        return@synchronized null
      }
      val contextSnapshot = contextProvider.snapshot()
      val focusActive = runCatching {
        FocusRuntime.get(context).status()["ownsDnd"] == true
      }.getOrDefault(false)
      val classification = AmbientAcousticClassifier.classify(
        samples = buffer.copyOf(read),
        sampleRate = sampleRate,
        context = AmbientContext(
          calendarBusy = contextSnapshot.calendarBusy,
          focusActive = focusActive,
          foregroundPackage = contextSnapshot.foregroundPackage
        )
      )
      val db = classification.backgroundDb
      if (!db.isFinite()) {
        AmbientRuntime.markUnavailable("audio_signal_unclassifiable")
        stopRequested = true
        return@synchronized null
      }
      val settings = settingsStore.read()
      val deviceMin = volumeController.minRingVolume()
      val deviceMax = volumeController.maxRingVolume()
      val deviceRange = (deviceMax - deviceMin).coerceAtLeast(0)
      val configuredMin = deviceMin + (deviceRange * settings.minVolumeFraction).roundToInt()
      val configuredMax = deviceMin + (deviceRange * settings.maxVolumeFraction).roundToInt()
      val headphonesConnected = volumeController.headphonesConnected()
      val current = volumeController.currentRingVolume()
      val measuredDecision = decisionController.decide(
        db = db,
        currentVolume = current,
        minVolume = configuredMin,
        maxVolume = configuredMax.coerceAtLeast(configuredMin),
        headphonesConnected = headphonesConnected && settings.leaveHeadphonesAlone,
        ringerMode = volumeController.ringerMode()
      )
      // A protected calendar/Focus context keeps the current ringer policy untouched; speech alone
      // can never make it louder or trigger a write.
      val decision = AmbientVolumePolicy.protectSpeech(measuredDecision, classification, current)
      val applied = decision.changed && volumeController.setRingVolume(decision.targetVolume, settings.leaveHeadphonesAlone)
      val finalDecision = if (applied || !decision.changed) {
        decision
      } else {
        decision.copy(targetVolume = current, changed = false, reason = "volume_write_rejected")
      }
      AmbientRuntime.lastDb = db
      AmbientRuntime.lastDecision = finalDecision
      AmbientRuntime.environment = classification.environment
      AmbientRuntime.source = classification.source
      AmbientRuntime.confidence = classification.confidence
      AmbientRuntime.lastReason = classification.reason
      AmbientRuntime.speechDetected = classification.speechDetected
      AmbientRuntime.protectedContext = classification.protectedContext
      AmbientRuntime.error = null
      val actualVolume = volumeController.currentRingVolume()
      val minVolume = volumeController.minRingVolume()
      val maxVolume = volumeController.maxRingVolume()
      val normalizedVolume = if (maxVolume > minVolume) {
        ((actualVolume - minVolume).toFloat() / (maxVolume - minVolume).toFloat()).coerceIn(0f, 1f)
      } else {
        0f
      }
      val atMs = System.currentTimeMillis()
      val reason = listOf(classification.reason, finalDecision.reason)
        .distinct()
        .joinToString("|")
      historyStore.recordAmbient(
        atMs = atMs,
        db = db,
        applied = applied,
        targetVolume = actualVolume,
        ringVolume = normalizedVolume,
        reason = reason,
        environment = classification.environment,
        source = classification.source,
        confidence = classification.confidence
      )
      AttuneEventHub.publish(
        "onAmbientSample",
        mapOf(
          "atMs" to atMs,
          "db" to db,
          "applied" to applied,
          "targetVolume" to actualVolume,
          "ringVolume" to normalizedVolume,
          "reason" to reason,
          "environment" to classification.environment,
          "source" to classification.source,
          "confidence" to classification.confidence,
          "speechDetected" to classification.speechDetected,
          "protectedContext" to classification.protectedContext
        )
      )
      finalDecision
    } catch (_: Throwable) {
      AmbientRuntime.markUnavailable("audio_sample_failed")
      stopRequested = true
      null
    } finally {
      runCatching { localRecorder.stop() }
      runCatching { localRecorder.release() }
      if (recorder === localRecorder) recorder = null
    }
  }

  fun stop() = synchronized(recorderLock) {
    stopRequested = true
    recorder?.let { local ->
      runCatching { local.stop() }
      runCatching { local.release() }
    }
    recorder = null
    AmbientRuntime.running = false
    AmbientRuntime.speechDetected = false
    AmbientRuntime.protectedContext = false
  }

}

class AttuneAmbientAudioService : Service() {
  private var sampler: AmbientSampler? = null
  private var worker: Thread? = null
  private val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)

  override fun onCreate() {
    super.onCreate()
    createChannel()
    val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    })
      .setContentTitle(getString(app.attune.attunenative.R.string.attune_service_name))
      .setContentText("Ambient volume sensing is on-device")
      .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
      .setOngoing(true)
      .build()
    val foregroundStarted = runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    }.isSuccess
    if (!foregroundStarted) {
      AmbientRuntime.markUnavailable("foreground_microphone_start_rejected")
      stopSelf()
      return
    }
    val localSampler = AmbientSampler(this)
    if (!localSampler.hasMicrophonePermission()) {
      AmbientRuntime.markUnavailable("microphone_permission_required")
      stopSelf()
      return
    }
    sampler = localSampler
    stopRequested.set(false)
    AmbientRuntime.running = true
    worker = Thread {
      while (!Thread.currentThread().isInterrupted && !stopRequested.get()) {
        val result = sampler?.sampleOnce()
        if (result == null && sampler?.shouldStopSampling() == true) break
        try {
          Thread.sleep(SAMPLE_INTERVAL_MS)
        } catch (_: InterruptedException) {
          break
        }
      }
      if (!stopRequested.get()) AmbientRuntime.running = false
    }.also {
      it.start()
    }
  }

  override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

  override fun onDestroy() {
    stopRequested.set(true)
    AmbientRuntime.running = false
    worker?.interrupt()
    worker = null
    sampler?.stop()
    sampler = null
    runCatching { stopForeground(true) }
    super.onDestroy()
  }

  override fun onBind(intent: android.content.Intent?): IBinder? = null

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(app.attune.attunenative.R.string.attune_sensing_channel), NotificationManager.IMPORTANCE_LOW))
    }
  }

  companion object {
    private const val CHANNEL_ID = "attune-sensing"
    private const val NOTIFICATION_ID = 7402
    private const val SAMPLE_INTERVAL_MS = 25_000L
  }
}

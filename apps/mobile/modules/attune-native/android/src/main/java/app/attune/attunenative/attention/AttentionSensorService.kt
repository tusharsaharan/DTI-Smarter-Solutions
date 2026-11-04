package app.attune.attunenative.attention

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Separate from camera FGS so camera denial/revocation does not disable the motion fallback. */
class AttuneAttentionSensorService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var source: AndroidAttentionSensorSource? = null
  private var recorder: AttentionMeasurementRecorder? = null
  private var workerThread: HandlerThread? = null
  private var worker: Handler? = null
  private val samplePending = AtomicBoolean(false)
  private val active = AtomicBoolean(false)
  private var recordingScreenOn = false // Worker-confined.
  private val tick = object : Runnable {
    override fun run() {
      val localSource = source ?: return
      localSource.refreshSnapshot()
      // UsageEvents queries and history writes must not block Android's main/sensor thread.
      if (samplePending.compareAndSet(false, true)) worker?.post {
        try {
          if (active.get()) runCatching {
            if (AttentionSensorRuntime.snapshot().screenOn) {
              recorder?.sample()
              recordingScreenOn = true
            } else if (recordingScreenOn) {
              // Flush once, then avoid UsageStats/file queries for the entire screen-off period.
              recorder?.reset()
              recordingScreenOn = false
            }
          }.onFailure {
            AttentionSensorRuntime.status = AttentionSensorRuntime.status.copy(error = "attention_measurement_failed")
          }
        } finally {
          samplePending.set(false)
        }
      }
      handler.postDelayed(this, 1_000L)
    }
  }

  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Attune sensing", NotificationManager.IMPORTANCE_LOW))
    }
    val notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }).setContentTitle(getString(app.attune.attunenative.R.string.attune_service_name))
      .setContentText("Screen-time estimates use motion sensors on this phone")
      .setSmallIcon(android.R.drawable.ic_menu_view)
      .setOngoing(true)
      .build()
    if (!runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else startForeground(NOTIFICATION_ID, notification)
    }.isSuccess) {
      AttentionSensorRuntime.status = AttentionSensorStatus(error = "foreground_sensor_start_rejected")
      stopSelf()
      return
    }
    val localSource = AndroidAttentionSensorSource(this)
    source = localSource
    active.set(true)
    val thread = HandlerThread("attune-attention-accounting").also { it.start() }
    workerThread = thread
    worker = Handler(thread.looper).also { handler ->
      handler.post { recorder = AttentionMeasurementRecorder(this) }
    }
    // Keep the usage recorder alive even on devices without an accelerometer.
    localSource.start()
    handler.post(tick)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

  override fun onDestroy() {
    handler.removeCallbacks(tick)
    active.set(false)
    // All recorder access stays on one worker, including its final buffered-history flush.
    worker?.post {
      runCatching { recorder?.reset() }
      recorder = null
    }
    workerThread?.quitSafely()
    worker = null
    workerThread = null
    source?.stop()
    source = null
    runCatching { stopForeground(true) }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val CHANNEL_ID = "attune-attention-sensors"
    private const val NOTIFICATION_ID = 7403
  }
}

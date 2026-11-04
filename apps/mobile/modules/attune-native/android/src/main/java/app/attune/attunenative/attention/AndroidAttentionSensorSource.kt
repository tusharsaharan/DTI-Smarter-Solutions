package app.attune.attunenative.attention

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

data class AttentionSensorStatus(
  val running: Boolean = false,
  val state: String = "stopped",
  val accelerometerAvailable: Boolean = false,
  val gyroscopeAvailable: Boolean = false,
  val proximityAvailable: Boolean = false,
  val error: String? = null
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "running" to running,
    "state" to state,
    "accelerometerAvailable" to accelerometerAvailable,
    "gyroscopeAvailable" to gyroscopeAvailable,
    "proximityAvailable" to proximityAvailable,
    "error" to error,
    "source" to "sensor_fusion",
    "limitation" to "motion_estimate_not_gaze"
  )
}

object AttentionSensorRuntime {
  @Volatile var status = AttentionSensorStatus()
  @Volatile internal var latest = AttentionMotionSnapshot(reliable = false)

  fun snapshot(nowElapsedMs: Long = SystemClock.elapsedRealtime()): AttentionMotionSnapshot {
    val value = latest
    val fresh = value.fresh && nowElapsedMs >= value.sampledAtElapsedMs &&
      nowElapsedMs - value.sampledAtElapsedMs <= AttentionMotionReducer.FRESHNESS_MS
    return value.copy(fresh = fresh, reliable = value.reliable && fresh)
  }
}

/** Low-rate sensors need no CAMERA or ACTIVITY_RECOGNITION permission. Raw vectors stay in RAM. */
class AndroidAttentionSensorSource(private val context: Context) : SensorEventListener {
  private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
  private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
  private val reducer = AttentionMotionReducer()
  private val handler = Handler(Looper.getMainLooper())
  private var active = false
  private var registered = false
  private var receiverRegistered = false
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (!active) return
      if (intent?.action == Intent.ACTION_SCREEN_OFF) suspendSensors() else updateRegistration()
      refreshSnapshot()
    }
  }

  fun start(): Boolean {
    if (active) return true
    val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val gyroscope = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val proximity = manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    AttentionSensorRuntime.status = AttentionSensorStatus(
      running = accelerometer != null,
      state = if (accelerometer != null) "starting" else "unavailable",
      accelerometerAvailable = accelerometer != null,
      gyroscopeAvailable = gyroscope != null,
      proximityAvailable = proximity != null,
      error = if (accelerometer == null) "accelerometer_unavailable" else null
    )
    if (accelerometer == null) return false
    active = true
    return runCatching {
      val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
      }
      receiverRegistered = true
      updateRegistration()
      refreshSnapshot()
      AttentionSensorRuntime.status.running
    }.getOrElse {
      stop()
      AttentionSensorRuntime.status = AttentionSensorRuntime.status.copy(state = "unavailable", error = "sensor_start_failed")
      false
    }
  }

  fun refreshSnapshot(): AttentionMotionSnapshot {
    val screenOn = screenUsable()
    if (!screenOn && registered) suspendSensors()
    // Refresh lock state even when Android sends no sensor events (for example privacy toggles).
    AttentionSensorRuntime.latest = reducer.snapshot(SystemClock.elapsedRealtime(), screenOn)
    return AttentionSensorRuntime.snapshot()
  }

  fun stop() {
    active = false
    suspendSensors()
    if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
    receiverRegistered = false
    AttentionSensorRuntime.latest = AttentionMotionSnapshot(screenOn = screenUsable())
    AttentionSensorRuntime.status = AttentionSensorRuntime.status.copy(running = false, state = "stopped")
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (!active || !registered) return
    if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
      reducer.reset()
      AttentionSensorRuntime.latest = AttentionMotionSnapshot(screenOn = screenUsable())
      return
    }
    val at = event.timestamp / 1_000_000L
    val values = event.values
    when (event.sensor.type) {
      Sensor.TYPE_ACCELEROMETER -> if (values.size >= 3) reducer.accelerometer(at, values[0], values[1], values[2])
      Sensor.TYPE_GYROSCOPE -> if (values.size >= 3) reducer.gyroscope(at, values[0], values[1], values[2])
      Sensor.TYPE_PROXIMITY -> if (values.isNotEmpty() && values[0].isFinite()) {
        reducer.proximity(at, values[0] < event.sensor.maximumRange && values[0] < 5f)
      }
    }
    AttentionSensorRuntime.latest = reducer.snapshot(SystemClock.elapsedRealtime(), screenUsable())
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  private fun screenUsable(): Boolean = power?.isInteractive == true && keyguard?.isKeyguardLocked != true

  private fun updateRegistration() {
    if (!active || !screenUsable()) {
      suspendSensors()
      return
    }
    if (registered) return
    reducer.reset()
    val sensorManager = manager ?: return
    val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    val accelerationRegistered = sensorManager.registerListener(this, accelerometer, SAMPLE_PERIOD_US, handler)
    if (!accelerationRegistered) {
      AttentionSensorRuntime.status = AttentionSensorRuntime.status.copy(running = false, state = "unavailable", error = "sensor_registration_failed")
      return
    }
    registered = true
    val gyroRegistered = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
      sensorManager.registerListener(this, it, SAMPLE_PERIOD_US, handler)
    } ?: false
    val proximityRegistered = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
      sensorManager.registerListener(this, it, SAMPLE_PERIOD_US, handler)
    } ?: false
    AttentionSensorRuntime.status = AttentionSensorRuntime.status.copy(
      running = true, state = "running", error = null,
      gyroscopeAvailable = gyroRegistered, proximityAvailable = proximityRegistered
    )
  }

  private fun suspendSensors() {
    manager?.unregisterListener(this)
    registered = false
    reducer.reset()
    AttentionSensorRuntime.latest = AttentionMotionSnapshot(screenOn = screenUsable())
    if (active) AttentionSensorRuntime.status = AttentionSensorRuntime.status.copy(state = "screen_off")
  }

  companion object {
    private const val SAMPLE_PERIOD_US = 200_000 // 5 Hz, no high-rate permission or wake lock.
  }
}

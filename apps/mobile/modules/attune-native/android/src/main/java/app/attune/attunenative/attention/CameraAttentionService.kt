package app.attune.attunenative.attention

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import app.attune.attunenative.AttuneEventHub
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

private const val CAMERA_SAMPLE_INTERVAL_MS = 500L
private const val CAMERA_STALE_MS = 2_000L

data class CameraRuntimeStatus(
  val running: Boolean = false,
  val permissionGranted: Boolean = false,
  val frontCameraAvailable: Boolean = false,
  val framesObserved: Long = 0L,
  val error: String? = null,
  val state: String = "stopped",
  val classification: AttentionClassification? = null,
  val observationElapsedMs: Long? = null
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "running" to running, "state" to state, "permissionGranted" to permissionGranted,
    "frontCameraAvailable" to frontCameraAvailable, "framesObserved" to framesObserved,
    "error" to error, "status" to if (classification == null) "gaze_unavailable" else "on_device_gaze",
    "source" to "camera", "confidence" to classification?.confidence,
    "label" to classification?.label?.name, "reason" to classification?.reason,
    "observationElapsedMs" to observationElapsedMs
  )
}

object CameraAttentionRuntime {
  @Volatile var status: CameraRuntimeStatus = CameraRuntimeStatus()

  fun currentClassification(nowElapsedMs: Long = SystemClock.elapsedRealtime()): AttentionClassification? {
    val snapshot = status
    val observed = snapshot.observationElapsedMs ?: return null
    if (!snapshot.running || snapshot.error != null || nowElapsedMs < observed || nowElapsedMs - observed > CAMERA_STALE_MS) return null
    return snapshot.classification
  }
}

/** A raw frame never claims gaze; only completed inference can produce an attention classification. */
data class CameraFrameObservation(
  val timestampMs: Long,
  val frameNumber: Long,
  val source: String = "camera_frame",
  val confidence: Float? = null,
  val status: String = "no_gaze_model"
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "timestampMs" to timestampMs, "frameNumber" to frameNumber, "source" to source,
    "confidence" to confidence, "status" to status, "environment" to null
  )
}

object CameraFrameBoundary {
  fun observe(timestampMs: Long, frameNumber: Long): CameraFrameObservation = CameraFrameObservation(timestampMs, frameNumber)
}

interface CameraAttentionSource { fun start(): Boolean; fun stop() }

/** Samples single Camera2 captures, never a repeating video stream; RGB buffers exist only during inference. */
class AndroidCameraAttentionSource(private val context: Context, private val onUnavailable: () -> Unit = {}) : CameraAttentionSource {
  private val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
  private val lock = Any()
  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private var camera: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var reader: ImageReader? = null
  private var landmarker: FaceLandmarker? = null
  private var generation = 0L
  private var started = false
  private var sensorOrientation = 0
  private var lastRotation: Int? = null
  private val classifier = GazeClassifier()

  override fun start(): Boolean = try {
    startInternal()
  } catch (_: Exception) {
    stop()
    CameraAttentionRuntime.status = CameraAttentionRuntime.status.copy(error = "camera_start_failed")
    false
  }

  private fun startInternal(): Boolean = synchronized(lock) {
    if (started) return true
    if ((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return false
    val permission = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (!permission) {
      CameraAttentionRuntime.status = CameraRuntimeStatus(error = "camera_permission_required")
      return false
    }
    val cameraManager = manager ?: run {
      CameraAttentionRuntime.status = CameraRuntimeStatus(permissionGranted = true, error = "camera_manager_unavailable")
      return false
    }
    val cameraId = runCatching { cameraManager.cameraIdList.firstOrNull { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT } }.getOrNull()
    if (cameraId == null) {
      CameraAttentionRuntime.status = CameraRuntimeStatus(permissionGranted = true, error = "front_camera_unavailable")
      return false
    }
    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
    val size = sizes?.filter { it.width in 480..1280 && it.height in 360..960 }?.minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) }
      ?: sizes?.minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) }
    if (size == null) {
      CameraAttentionRuntime.status = CameraRuntimeStatus(permissionGranted = true, frontCameraAvailable = true, error = "camera_format_unavailable")
      return false
    }
    generation += 1
    val requestGeneration = generation
    started = true
    classifier.reset()
    lastRotation = null
    val cameraThread = HandlerThread("attune-gaze-$requestGeneration").also { it.start() }
    thread = cameraThread
    val cameraHandler = Handler(cameraThread.looper)
    handler = cameraHandler
    val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
    reader = imageReader
    imageReader.setOnImageAvailableListener({ source ->
      val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
      try {
        synchronized(lock) {
          if (!current(requestGeneration)) return@synchronized
          runCatching { infer(image) }.onFailure { fail(requestGeneration, "gaze_inference_failed") }
          if (current(requestGeneration)) cameraHandler.postDelayed({ capture(requestGeneration) }, CAMERA_SAMPLE_INTERVAL_MS)
        }
      } finally { image.close() }
    }, cameraHandler)
    CameraAttentionRuntime.status = CameraRuntimeStatus(permissionGranted = true, frontCameraAvailable = true, state = "starting")
    cameraHandler.post {
      synchronized(lock) {
        if (!current(requestGeneration)) return@synchronized
        runCatching {
          landmarker = FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO).setNumFaces(2)
            .setMinFaceDetectionConfidence(0.65f).setMinFacePresenceConfidence(0.65f)
            .setMinTrackingConfidence(0.65f).setOutputFaceBlendshapes(true).build())
          cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = synchronized(lock) {
              if (!current(requestGeneration)) { device.close(); return@synchronized }
              camera = device
              runCatching {
                device.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                  override fun onConfigured(configured: CameraCaptureSession) = synchronized(lock) {
                    if (!current(requestGeneration)) { configured.close(); return@synchronized }
                    session = configured
                    CameraAttentionRuntime.status = CameraAttentionRuntime.status.copy(running = true, state = "running")
                    capture(requestGeneration)
                  }
                  override fun onConfigureFailed(failed: CameraCaptureSession) { failed.close(); fail(requestGeneration, "camera_session_failed") }
                }, cameraHandler)
              }.onFailure { fail(requestGeneration, "camera_session_failed") }
              Unit
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); fail(requestGeneration, "camera_disconnected") }
            override fun onError(device: CameraDevice, error: Int) { device.close(); fail(requestGeneration, "camera_error_$error") }
          }, cameraHandler)
        }.onFailure { fail(requestGeneration, "camera_or_model_start_failed") }
      }
    }
    true
  }

  private fun capture(requestGeneration: Long) = synchronized(lock) {
    if (!current(requestGeneration)) return@synchronized
    if (!(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive || (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { stop(); return@synchronized }
    runCatching {
      val device = camera ?: return@synchronized
      val output = reader?.surface ?: return@synchronized
      session?.capture(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(output) }.build(), null, handler)
    }.onFailure { fail(requestGeneration, "camera_request_failed") }
    Unit
  }

  private fun infer(image: Image) {
    val unrotated = yuvBitmap(image)
    val rotation = rotationDegrees()
    if (lastRotation != rotation) { classifier.reset(); lastRotation = rotation }
    val bitmap = if (rotation == 0) unrotated else Bitmap.createBitmap(unrotated, 0, 0, unrotated.width, unrotated.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    try {
      val mpImage = BitmapImageBuilder(bitmap).build()
      try {
        val now = SystemClock.elapsedRealtime()
        val result = landmarker!!.detectForVideo(mpImage, now)
        val face = result.faceLandmarks().firstOrNull().orEmpty()
        val scale = maxOf(bitmap.width, bitmap.height).toFloat()
        val points = face.map { GazePoint(it.x() * bitmap.width / scale, it.y() * bitmap.height / scale, it.z()) }
        val blend = result.faceBlendshapes().orElse(emptyList()).firstOrNull().orEmpty().associate { it.categoryName() to it.score() }
        val classification = classifier.classify(GazeGeometry.frame(points, result.faceLandmarks().size, blend), now)
        val previous = CameraAttentionRuntime.status
        CameraAttentionRuntime.status = previous.copy(framesObserved = previous.framesObserved + 1, classification = classification, observationElapsedMs = now)
        AttuneEventHub.publish("onAttentionFrame", classification.toMap() + mapOf("timestampMs" to System.currentTimeMillis(), "frameNumber" to previous.framesObserved + 1, "status" to "on_device_gaze"))
      } finally { mpImage.close() }
    } finally {
      if (bitmap !== unrotated) bitmap.recycle()
      unrotated.recycle()
    }
  }

  @Suppress("DEPRECATION")
  private fun rotationDegrees(): Int {
    val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    val degrees = when (displayRotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    // Front-camera input rotation, without mirroring (Google's camera2 ML input convention).
    return (sensorOrientation + degrees) % 360
  }

  override fun stop() = synchronized(lock) {
    generation += 1
    closeResources()
    CameraAttentionRuntime.status = CameraAttentionRuntime.status.copy(running = false, state = "stopped", classification = null, observationElapsedMs = null)
  }

  private fun current(requestGeneration: Long) = started && generation == requestGeneration
  private fun fail(requestGeneration: Long, error: String) = synchronized(lock) {
    if (!current(requestGeneration)) return@synchronized
    generation += 1
    closeResources()
    CameraAttentionRuntime.status = CameraAttentionRuntime.status.copy(running = false, state = "stopped", error = error, classification = null, observationElapsedMs = null)
    onUnavailable()
  }

  private fun closeResources() {
    started = false
    handler?.removeCallbacksAndMessages(null)
    runCatching { session?.close() }; runCatching { camera?.close() }; runCatching { reader?.close() }; runCatching { landmarker?.close() }
    thread?.quitSafely()
    session = null; camera = null; reader = null; landmarker = null; thread = null; handler = null
    classifier.reset()
  }

  /** Supports padded rows and interleaved or planar UV; no JPEG encoding or disk files. */
  private fun yuvBitmap(image: Image): Bitmap {
    val planes = image.planes
    val buffers = planes.map { it.buffer.duplicate() }
    val origins = buffers.map { it.position() }
    fun value(plane: Int, x: Int, y: Int) = buffers[plane].get(origins[plane] + y * planes[plane].rowStride + x * planes[plane].pixelStride).toInt() and 255
    val pixels = IntArray(image.width * image.height)
    for (y in 0 until image.height) for (x in 0 until image.width) {
      val yy = (value(0, x, y) - 16).coerceAtLeast(0)
      val u = value(1, x / 2, y / 2) - 128
      val v = value(2, x / 2, y / 2) - 128
      val r = ((298 * yy + 409 * v + 128) shr 8).coerceIn(0, 255)
      val g = ((298 * yy - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
      val b = ((298 * yy + 516 * u + 128) shr 8).coerceIn(0, 255)
      pixels[y * image.width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
  }
}

class AttuneAttentionCameraService : Service() {
  private var source: AndroidCameraAttentionSource? = null
  private var receiverRegistered = false
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == Intent.ACTION_SCREEN_OFF) source?.stop()
      if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
        if (source?.start() == false && CameraAttentionRuntime.status.error != null) stopSelf()
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(app.attune.attunenative.R.string.attune_sensing_channel), NotificationManager.IMPORTANCE_LOW))
    val notification = Notification.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(app.attune.attunenative.R.string.attune_service_name))
      .setContentText("On-device eye and head sensing; no images saved")
      .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
    val started = runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
      else startForeground(NOTIFICATION_ID, notification)
    }.isSuccess
    if (!started) { CameraAttentionRuntime.status = CameraRuntimeStatus(error = "foreground_camera_start_rejected"); stopSelf(); return }
    source = AndroidCameraAttentionSource(this) {
      Handler(mainLooper).post { if (CameraAttentionRuntime.status.error != null) stopSelf() }
    }
    val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
    receiverRegistered = true
    if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
      if (source?.start() == false && CameraAttentionRuntime.status.error != null) stopSelf()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
  override fun onDestroy() {
    if (receiverRegistered) { runCatching { unregisterReceiver(screenReceiver) }; receiverRegistered = false }
    source?.stop(); source = null
    @Suppress("DEPRECATION")
    runCatching { stopForeground(true) }
    super.onDestroy()
  }
  override fun onBind(intent: Intent?): IBinder? = null
  companion object { private const val CHANNEL_ID = "attune-sensing"; private const val NOTIFICATION_ID = 7401 }
}

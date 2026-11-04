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
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicLong

private const val CAMERA_SAMPLE_INTERVAL_MS = 500L
private const val CAMERA_START_TIMEOUT_MS = 10_000L

data class CameraRuntimeStatus(
  val running: Boolean = false,
  val permissionGranted: Boolean = false,
  val frontCameraAvailable: Boolean = false,
  val framesObserved: Long = 0L,
  val error: String? = null,
  val state: String = "stopped",
  val classification: AttentionClassification? = null,
  val observationElapsedMs: Long? = null,
  val completionElapsedMs: Long? = null,
  val validUntilElapsedMs: Long? = null,
  val timestampBasis: String? = null,
  val sessionId: Long = 0L,
  val framesRejected: Long = 0L,
  val lastRejection: String? = null,
  val inferenceMs: Long? = null,
  val captureToResultMs: Long? = null,
  val rotationDegrees: Int? = null,
  val gazeDiagnostics: Map<String, Float?>? = null,
  val cameraKey: String? = null,
  val calibrationRevision: Long? = null,
  val calibrationStatus: String = "uncalibrated"
) {
  fun rejected(reason: String, time: CameraEvidenceTime? = null, completed: Long? = null, latencyMs: Long? = null): CameraRuntimeStatus = copy(
    classification = null, observationElapsedMs = time?.observedElapsedMs,
    completionElapsedMs = completed, validUntilElapsedMs = time?.validUntilElapsedMs,
    timestampBasis = time?.basis, inferenceMs = latencyMs,
    captureToResultMs = if (time != null && completed != null) completed - time.observedElapsedMs else null,
    rotationDegrees = null, gazeDiagnostics = null, framesRejected = framesRejected + 1, lastRejection = reason
  )

  fun stopped(failure: String? = error): CameraRuntimeStatus = copy(
    running = false, state = "stopped", error = failure, classification = null,
    observationElapsedMs = null, completionElapsedMs = null, validUntilElapsedMs = null,
    timestampBasis = null, inferenceMs = null, captureToResultMs = null, rotationDegrees = null, gazeDiagnostics = null
  )

  fun freshClassification(nowElapsedMs: Long): AttentionClassification? {
    val observed = observationElapsedMs ?: return null
    val completed = completionElapsedMs ?: return null
    val expires = validUntilElapsedMs ?: return null
    if (calibrationRevision != null && calibrationRevision != GazeCalibrationRuntime.revision) return null
    if (!running || error != null || observed < 0 || completed < observed || nowElapsedMs < completed ||
      nowElapsedMs > expires || expires - observed != CameraEvidenceClock.MAX_AGE_MS) return null
    return classification
  }

  fun toMap(nowElapsedMs: Long = SystemClock.elapsedRealtime(), diagnostics: Boolean = false): Map<String, Any?> {
    val fresh = freshClassification(nowElapsedMs)
    return mapOf(
     "running" to running, "state" to state, "permissionGranted" to permissionGranted,
     "frontCameraAvailable" to frontCameraAvailable, "framesObserved" to framesObserved,
    "error" to error, "status" to if (fresh == null) "gaze_unavailable" else "on_device_gaze",
    "source" to "camera", "confidence" to fresh?.confidence,
    "label" to (fresh?.label ?: AttentionLabel.UNKNOWN).name,
    "reason" to (fresh?.reason ?: error ?: if (!running) "camera_stopped" else lastRejection ?: "camera_evidence_expired"),
    "observationElapsedMs" to observationElapsedMs, "completionElapsedMs" to completionElapsedMs,
    "validUntilElapsedMs" to validUntilElapsedMs, "atElapsedMs" to nowElapsedMs,
    "timestampBasis" to timestampBasis, "sessionId" to sessionId,
    "framesRejected" to framesRejected, "lastRejection" to lastRejection,
    "inferenceMs" to inferenceMs, "captureToResultMs" to captureToResultMs,
    "rotationDegrees" to rotationDegrees, "classifierVersion" to GazeClassifier.VERSION,
    "calibrationStatus" to calibrationStatus, "confidenceMeaning" to "heuristic_evidence_strength"
    ) + if (diagnostics) mapOf("gazeDiagnostics" to if (fresh != null) gazeDiagnostics else null,
      "calibration" to GazeCalibrationRuntime.diagnostics()) else emptyMap()
  }
}

object CameraAttentionRuntime {
  private val sessions = AtomicLong()
  @Volatile var status: CameraRuntimeStatus = CameraRuntimeStatus()
  fun nextSessionId(): Long = sessions.incrementAndGet()

  fun currentClassification(nowElapsedMs: Long = SystemClock.elapsedRealtime()): AttentionClassification? {
    return status.freshClassification(nowElapsedMs)
  }

  fun event(nowElapsedMs: Long = SystemClock.elapsedRealtime(), nowWallMs: Long = System.currentTimeMillis()): Map<String, Any?> {
    val snapshot = status
    val fresh = snapshot.freshClassification(nowElapsedMs)
    val age = if (fresh != null) nowElapsedMs - snapshot.observationElapsedMs!! else 0L
    return snapshot.toMap(nowElapsedMs) + mapOf(
      "timestampMs" to nowWallMs - age,
      "validUntilMs" to if (fresh != null) nowWallMs + (snapshot.validUntilElapsedMs!! - nowElapsedMs) else nowWallMs,
      "frameNumber" to snapshot.framesObserved
    )
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
  private val publications = CameraPublicationGate()
  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private var camera: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var reader: ImageReader? = null
  private var landmarker: FaceLandmarker? = null
  private var started = false
  private var sensorOrientation = 0
  private var realtimeSensorClock = false
  private var lastRotation: Int? = null
  private var lastImageTimestampNs: Long? = null
  private var pendingCapture: PendingCapture? = null
  private val expiryHandler = Handler(context.mainLooper)
  private var cameraGeometryKey = ""

  private data class PendingCapture(
    val generation: Long,
    val requestedElapsedMs: Long,
    val rotation: Int,
    var sensorTimestampNs: Long? = null,
    var image: Image? = null,
    var receivedElapsedMs: Long? = null
  )

  override fun start(): Boolean = try {
    startInternal()
  } catch (_: Exception) {
    stopWithError("camera_start_failed")
    false
  }

  private fun startInternal(): Boolean = synchronized(lock) {
    if (started) return true
    val requestGeneration = publications.advance()
    if ((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return false
    val permission = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (!permission) {
      publications.publish(requestGeneration) {
        CameraAttentionRuntime.status = CameraRuntimeStatus(error = "camera_permission_required")
        publishStatus()
      }
      return false
    }
    val cameraManager = manager ?: run {
      publications.publish(requestGeneration) {
        CameraAttentionRuntime.status = CameraRuntimeStatus(permissionGranted = true, error = "camera_manager_unavailable")
        publishStatus()
      }
      return false
    }
    val cameraId = runCatching { cameraManager.cameraIdList.firstOrNull { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT } }.getOrNull()
    if (cameraId == null) {
      publications.publish(requestGeneration) {
        CameraAttentionRuntime.status = CameraRuntimeStatus(permissionGranted = true, error = "front_camera_unavailable")
        publishStatus()
      }
      return false
    }
    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    realtimeSensorClock = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
    val size = sizes?.filter { it.width in 480..1280 && it.height in 360..960 }?.minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) }
      ?: sizes?.minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) }
    if (size == null) {
      publications.publish(requestGeneration) {
        CameraAttentionRuntime.status = CameraRuntimeStatus(permissionGranted = true, frontCameraAvailable = true, error = "camera_format_unavailable")
        publishStatus()
      }
      return false
    }
    started = true
    GazeCalibrationRuntime.initialize(context)
    GazeCalibrationRuntime.resetTemporal()
    cameraGeometryKey = "$cameraId:${size.width}x${size.height}:$sensorOrientation"
    lastRotation = null
    lastImageTimestampNs = null
    val cameraThread = HandlerThread("attune-gaze-$requestGeneration").also { it.start() }
    thread = cameraThread
    val cameraHandler = Handler(cameraThread.looper)
    handler = cameraHandler
    val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
    reader = imageReader
    imageReader.setOnImageAvailableListener({ source ->
      val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
      synchronized(lock) {
        val pending = pendingCapture
        if (!current(requestGeneration) || pending == null || pending.image != null) {
          image.close()
          return@synchronized
        }
        pending.image = image
        pending.receivedElapsedMs = SystemClock.elapsedRealtime()
        processCapturedFrame(pending)
      }
    }, cameraHandler)
    if (!publications.publish(requestGeneration) {
      CameraAttentionRuntime.status = CameraRuntimeStatus(permissionGranted = true, frontCameraAvailable = true,
        state = "starting", sessionId = CameraAttentionRuntime.nextSessionId())
      publishStatus()
    }) return false
    cameraHandler.postDelayed({ synchronized(lock) {
      if (current(requestGeneration) && session == null) fail(requestGeneration, "camera_start_timeout")
    } }, CAMERA_START_TIMEOUT_MS)
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
                    publications.publish(requestGeneration) {
                      CameraAttentionRuntime.status = CameraAttentionRuntime.status.copy(running = true, state = "running")
                      publishStatus()
                    }
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
        }.onFailure {
          // Startup has no camera frames; preserve the library/configuration failure for diagnosis.
          android.util.Log.e("AttuneCamera", "Camera/model initialization failed", it)
          fail(requestGeneration, "camera_or_model_start_failed")
        }
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
      val captureSession = session ?: return@synchronized
      if (pendingCapture != null) return@synchronized
      var requested = 0L
      if (!publications.publish(requestGeneration) { requested = GazeCalibrationRuntime.captureStarted() }) return@synchronized
      val pending = PendingCapture(requestGeneration, requested, rotationDegrees())
      pendingCapture = pending
      val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(output)
        setTag(pending)
      }.build()
      captureSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) = synchronized(lock) {
          if (current(requestGeneration) && pendingCapture === pending && request.tag === pending) {
            pending.sensorTimestampNs = timestamp
            processCapturedFrame(pending)
          }
        }
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = synchronized(lock) {
          if (current(requestGeneration) && pendingCapture === pending && request.tag === pending) {
            pending.sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
            processCapturedFrame(pending)
          }
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
          fail(requestGeneration, "camera_capture_failed")
        }
      }, handler)
      handler?.postDelayed({ synchronized(lock) {
        if (current(requestGeneration) && pendingCapture === pending) fail(requestGeneration, "camera_capture_timeout")
      } }, CameraEvidenceClock.MAX_AGE_MS + 1)
    }.onFailure { fail(requestGeneration, "camera_request_failed") }
    Unit
  }

  private fun processCapturedFrame(pending: PendingCapture) {
    val image = pending.image ?: return
    val captureTimestamp = pending.sensorTimestampNs ?: return
    pending.image = null
    pendingCapture = null
    try {
      val timing = CameraEvidenceClock.resolve(pending.requestedElapsedMs, pending.receivedElapsedMs!!,
        captureTimestamp, image.timestamp, realtimeSensorClock, lastImageTimestampNs)
      val time = timing.time
      if (time == null) {
        rejectFrame(pending.generation, timing.rejection!!)
      } else if (rotationDegrees() != pending.rotation) {
        rejectFrame(pending.generation, "camera_rotation_changed")
      } else {
        lastImageTimestampNs = image.timestamp
        runCatching { infer(image, time, pending) }.onFailure { fail(pending.generation, "gaze_inference_failed") }
      }
    } finally { image.close() }
    if (current(pending.generation)) handler?.postDelayed({ capture(pending.generation) }, CAMERA_SAMPLE_INTERVAL_MS)
  }

  private fun infer(image: Image, time: CameraEvidenceTime, pending: PendingCapture) {
    if (!time.isFresh(SystemClock.elapsedRealtime())) { rejectFrame(pending.generation, "camera_capture_expired"); return }
    val unrotated = yuvBitmap(image)
    val rotation = pending.rotation
    if (lastRotation != rotation) { GazeCalibrationRuntime.resetTemporal(); lastRotation = rotation }
    var bitmap = unrotated
    try {
      if (rotation != 0) bitmap = Bitmap.createBitmap(unrotated, 0, 0, unrotated.width, unrotated.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
      val mpImage = BitmapImageBuilder(bitmap).build()
      try {
        val inferenceStarted = SystemClock.elapsedRealtime()
        val result = landmarker!!.detectForVideo(mpImage, time.imageTimestampNs / 1_000_000L)
        val completed = SystemClock.elapsedRealtime()
        if (!current(pending.generation)) return
        val rejection = when {
          !AttentionMeasurementRecorder.screenUsable(context) -> "screen_off_or_locked"
          rotationDegrees() != rotation -> "camera_rotation_changed"
          !time.isFresh(completed) -> "gaze_inference_expired"
          else -> null
        }
        if (rejection != null) {
          rejectFrame(pending.generation, rejection, time, completed, completed - inferenceStarted)
          return
        }
        val face = result.faceLandmarks().firstOrNull().orEmpty()
        val scale = maxOf(bitmap.width, bitmap.height).toFloat()
        val points = face.map { GazePoint(it.x() * bitmap.width / scale, it.y() * bitmap.height / scale, it.z()) }
        val blend = result.faceBlendshapes().orElse(emptyList()).firstOrNull().orEmpty().associate { it.categoryName() to it.score() }
        val frame = GazeGeometry.frame(points, result.faceLandmarks().size, blend)
        val key = "$cameraGeometryKey:$rotation"
        publications.publish(pending.generation) {
          val previous = CameraAttentionRuntime.status
          val decision = GazeCalibrationRuntime.classify(context, frame, time.observedElapsedMs, completed, key,
            previous.sessionId, pending.requestedElapsedMs)
          CameraAttentionRuntime.status = previous.copy(framesObserved = previous.framesObserved + 1,
            classification = decision.classification, observationElapsedMs = time.observedElapsedMs,
            completionElapsedMs = completed, validUntilElapsedMs = time.validUntilElapsedMs,
            timestampBasis = time.basis, inferenceMs = completed - inferenceStarted, lastRejection = null,
            captureToResultMs = completed - time.observedElapsedMs, rotationDegrees = rotation,
            gazeDiagnostics = frame.diagnostics(), cameraKey = key, calibrationRevision = decision.revision,
            calibrationStatus = decision.calibrationStatus)
          AttuneEventHub.publish("onAttentionFrame", CameraAttentionRuntime.event())
          expiryHandler.removeCallbacksAndMessages(null)
          expiryHandler.postDelayed({
            publications.publish(pending.generation) {
              val snapshot = CameraAttentionRuntime.status
              if (snapshot.observationElapsedMs == time.observedElapsedMs &&
                snapshot.freshClassification(SystemClock.elapsedRealtime()) == null) {
                CameraAttentionRuntime.status = snapshot.copy(inferenceMs = null, captureToResultMs = null)
                publishStatus()
              }
            }
          }, time.validUntilElapsedMs - completed + 1)
        }
      } finally { mpImage.close() }
    } finally {
      if (bitmap !== unrotated) bitmap.recycle()
      unrotated.recycle()
    }
  }

  private fun rejectFrame(requestGeneration: Long, reason: String, time: CameraEvidenceTime? = null,
    completed: Long? = null, latencyMs: Long? = null) {
    publications.publish(requestGeneration) {
      GazeCalibrationRuntime.rejected()
      expiryHandler.removeCallbacksAndMessages(null)
      CameraAttentionRuntime.status = CameraAttentionRuntime.status.rejected(reason, time, completed, latencyMs)
      publishStatus()
    }
  }

  private fun publishStatus() {
    AttuneEventHub.publish("onAttentionFrame", CameraAttentionRuntime.event())
    AttuneEventHub.publish("onNativeStatusChanged", mapOf("camera" to CameraAttentionRuntime.status.toMap()))
  }

  @Suppress("DEPRECATION")
  private fun rotationDegrees(): Int {
    val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    val degrees = when (displayRotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    // Front-camera input rotation, without mirroring (Google's camera2 ML input convention).
    return CameraImageGeometry.frontRotation(sensorOrientation, degrees)
  }

  override fun stop() {
    stopWithError()
  }

  private fun stopWithError(error: String? = null) {
    // Invalidate in-flight inference before waiting for resource cleanup's lock.
    publications.advance {
      GazeCalibrationRuntime.interrupt()
      val snapshot = CameraAttentionRuntime.status
      CameraAttentionRuntime.status = snapshot.stopped(error ?: snapshot.error)
      publishStatus()
    }
    synchronized(lock) { closeResources() }
  }

  private fun current(requestGeneration: Long) = started && publications.isCurrent(requestGeneration)
  private fun fail(requestGeneration: Long, error: String) = synchronized(lock) {
    if (!publications.invalidate(requestGeneration) {
      GazeCalibrationRuntime.interrupt()
      CameraAttentionRuntime.status = CameraAttentionRuntime.status.stopped(error)
      publishStatus()
    }) return@synchronized
    closeResources()
    onUnavailable()
  }

  private fun closeResources() {
    started = false
    expiryHandler.removeCallbacksAndMessages(null)
    handler?.removeCallbacksAndMessages(null)
    runCatching { pendingCapture?.image?.close() }
    pendingCapture = null
    runCatching { session?.close() }; runCatching { camera?.close() }; runCatching { reader?.close() }; runCatching { landmarker?.close() }
    thread?.quitSafely()
    session = null; camera = null; reader = null; landmarker = null; thread = null; handler = null
    GazeCalibrationRuntime.resetTemporal()
  }

  /** Supports padded rows and interleaved or planar UV; no JPEG encoding or disk files. */
  private fun yuvBitmap(image: Image): Bitmap {
    val crop = image.cropRect
    val pixels = CameraImageGeometry.argb(image.width, image.height,
      image.planes.map { CameraYuvPlane(it.buffer, it.rowStride, it.pixelStride) },
      crop.left, crop.top, crop.width(), crop.height())
    return Bitmap.createBitmap(pixels, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
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
  override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
    // Android's DUMP permission protects this derived-only diagnostic surface.
    writer.println("ATTUNE_CAMERA_STATUS " + com.google.gson.Gson().toJson(CameraAttentionRuntime.status.toMap(diagnostics = true)))
  }
  companion object { private const val CHANNEL_ID = "attune-sensing"; private const val NOTIFICATION_ID = 7401 }
}

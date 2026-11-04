package app.attune.attunenative.attention

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PersonalGazeDecision(val classification: AttentionClassification, val revision: Long, val calibrationStatus: String)

/** One synchronized owner for explicit collection, profile changes and temporal decisions.
 * Storage is app-private and excluded from Android backup; no individual observations are saved. */
object GazeCalibrationRuntime {
  @Volatile var revision = 0L; private set
  private var loaded = false
  private var saved: PersonalGazeProfile? = null
  private var session: GazeCalibrationSession? = null
  private var classifier = GazeClassifier()
  private var classifierKey: String? = null
  private var pendingCapture: Long? = null

  @Synchronized fun initialize(context: Context) {
    if (loaded) return
    loaded = true
    saved = runCatching {
      val file = profileFile(context)
      require(file.baseFile.length() <= 8_192)
      val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
      require(json.getString("version") == GazeClassifier.VERSION)
      fun values(name: String): List<Float> {
        val a = json.getJSONArray(name)
        require(a.length() == PersonalGazeProfile.DIMENSIONS)
        return List(a.length()) { a.getDouble(it).toFloat() }
      }
      PersonalGazeProfile(json.getString("cameraKey"), json.getLong("createdAtMs"), values("centers"), values("radii"))
        .takeIf { it.valid(System.currentTimeMillis()) }
    }.getOrNull()
    if (saved == null) profileFile(context).delete()
    changed()
  }

  @Synchronized fun command(context: Context, action: String, foreground: Boolean): Map<String, Any?> {
    initialize(context)
    val now = SystemClock.elapsedRealtime()
    if (!foreground) interrupt() else session?.heartbeat(now)
    expire(context)
    val camera = CameraAttentionRuntime.status
    val ready = foreground && camera.running && camera.error == null && camera.cameraKey != null &&
      camera.observationElapsedMs?.let { now - it in 0..CameraEvidenceClock.MAX_AGE_MS } == true
    when (action) {
      "begin" -> {
        session = GazeCalibrationSession(camera.cameraKey.orEmpty(), camera.sessionId, now)
        if (!ready) session!!.fail("camera_unavailable")
        changed()
      }
      "collect" -> if (ready) {
        session?.collect(now)
        pendingCapture?.let { session?.captureStarted(it) }
      } else session?.fail("camera_unavailable")
      "confirm" -> {
        if (!ready) session?.fail("camera_unavailable")
        else session?.confirm(now, System.currentTimeMillis())
        val candidate = session?.takeIf { it.state == "complete" }?.profile
        if (candidate != null) {
          if (save(context, candidate)) saved = candidate else session?.fail("save_failed")
          changed()
        }
      }
      "cancel" -> { if (session?.active == true) { session!!.fail("interrupted"); changed() } }
      "delete" -> {
        profileFile(context).delete()
        saved = null; session = null; changed()
      }
      "status" -> Unit
      else -> throw IllegalArgumentException("Unknown gaze calibration action")
    }
    return status(now, ready)
  }

  @Synchronized fun interrupt() {
    if (session?.active == true) { session!!.fail("interrupted"); changed() }
  }

  @Synchronized fun active(): Boolean = session?.active == true
  @Synchronized fun resetTemporal() { classifier.reset() }
  @Synchronized fun captureStarted(): Long {
    // Timestamp and registration share the command lock: confirmation cannot overtake
    // an unregistered request whose exposure could belong to the scoring window.
    val now = SystemClock.elapsedRealtime()
    pendingCapture = now
    session?.captureStarted(now)
    return now
  }

  @Synchronized fun rejected() {
    classifier.reset()
    session?.rejected()
    pendingCapture = null
  }

  @Synchronized fun classify(context: Context, frame: GazeFrame, observed: Long, completed: Long, key: String,
    cameraSession: Long, requested: Long): PersonalGazeDecision {
    expire(context)
    val delivered = SystemClock.elapsedRealtime()
    session?.observe(frame, observed, completed, key, cameraSession, delivered)
    if (pendingCapture == requested) pendingCapture = null
    session?.captureFinished(requested, delivered)
    val progress = session?.active == true
    val profile = saved?.takeIf { it.valid(System.currentTimeMillis()) && it.cameraKey == key }
    val policyKey = "$revision:$progress:${profile != null}:$key"
    if (classifierKey != policyKey) {
      classifier = if (profile != null) GazeClassifier(profile::classify) else GazeClassifier()
      classifierKey = policyKey
    }
    val result = if (progress) AttentionClassification(AttentionLabel.UNKNOWN, 0f, "camera", "gaze_calibration_in_progress")
      else if (saved != null && profile == null) AttentionClassification(AttentionLabel.UNKNOWN, 0f, "camera", "gaze_calibration_position_or_age_changed")
      else classifier.classify(frame, observed)
    return PersonalGazeDecision(result, revision, if (progress) "checking" else if (profile != null) "personal_checked" else "uncalibrated")
  }

  @Synchronized fun diagnostics(): Map<String, Any?> = mapOf(
    "state" to (session?.state ?: "idle"), "step" to session?.let { GazeCalibrationSession.STEPS[it.stepIndex] },
    "reason" to session?.reason, "validationCounts" to session?.validationCounts,
    "profileSaved" to (saved != null), "revision" to revision
  )

  private fun changed() { revision++; classifier.reset(); classifierKey = null }

  private fun expire(context: Context) {
    if (saved?.valid(System.currentTimeMillis()) == false) {
      profileFile(context).delete()
      saved = null
      changed()
    }
  }

  private fun status(now: Long, ready: Boolean): Map<String, Any?> {
    val s = session
    return mapOf("state" to (s?.state ?: "idle"), "step" to GazeCalibrationSession.STEPS[s?.stepIndex ?: 0],
      "stepIndex" to (s?.stepIndex ?: 0), "stepCount" to GazeCalibrationSession.STEPS.size,
      "remainingMs" to (s?.remaining(now) ?: 0L), "samples" to (s?.sampleCount() ?: 0),
      "reason" to s?.reason, "calibrated" to (saved?.valid(System.currentTimeMillis()) == true), "cameraReady" to ready)
  }

  private fun profileFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, "attune_gaze_profile.json"))

  private fun save(context: Context, profile: PersonalGazeProfile): Boolean {
    val json = JSONObject().put("version", GazeClassifier.VERSION).put("cameraKey", profile.cameraKey)
      .put("createdAtMs", profile.createdAtMs).put("centers", JSONArray(profile.centers)).put("radii", JSONArray(profile.radii))
    val file = profileFile(context)
    val stream = runCatching { file.startWrite() }.getOrNull() ?: return false
    return try {
      stream.write(json.toString().toByteArray(Charsets.UTF_8))
      file.finishWrite(stream)
      true
    } catch (_: Exception) { file.failWrite(stream); false }
  }
}

package app.attune.attunenative.notification

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class SemanticIntentMatch(
  val intent: NotificationIntent,
  val similarity: Float,
  val margin: Float,
  /** True when the match used the bounded preceding conversation window. */
  val contextual: Boolean = false
)
object NotificationSemanticRuntime {
  @Volatile var state: String = "not_loaded"
  @Volatile var error: String? = null
  fun toMap(): Map<String, Any?> = mapOf(
    "state" to state, "error" to error, "source" to "on_device_multilingual_encoder",
    "model" to "distiluse-base-multilingual-cased-v2", "remoteInference" to false,
    "limitation" to "synthetic_training_bounded_thread_context_and_abstention_not_perfect_understanding"
  )
}

/** Worker-confined, CPU int8 inference. No network, text logging, or raw-message disk writes. */
class AndroidNotificationSemanticModel(private val context: Context) : AutoCloseable {
  private var session: OrtSession? = null
  private var options: OrtSession.SessionOptions? = null
  private var tokenizer: BertWordPieceTokenizer? = null
  private var intentHead: SemanticIntentHead? = null
  private var attempted = false
  private val environment by lazy { OrtEnvironment.getEnvironment() }

  fun prepare(): Boolean {
    if (session != null) return true
    if (attempted) return false
    attempted = true
    NotificationSemanticRuntime.state = "loading"
    return try {
      val manifest = JSONObject(readAsset("manifest.json"))
      require(manifest.getInt("maxLength") == 128 && manifest.getInt("dimensions") == 512)
      val files = manifest.getJSONObject("files")
      val modelInfo = files.getJSONObject("encoder.onnx")
      val expectedHash = modelInfo.getString("sha256")
      require(expectedHash.matches(Regex("[a-f0-9]{64}")))
      val directory = File(context.cacheDir, "notification-model").apply { mkdirs() }
      val modelFile = File(directory, "encoder-${expectedHash.take(16)}.onnx")
      if (!modelFile.exists() || modelFile.length() != modelInfo.getLong("bytes")) {
        val temporary = File(directory, "encoder.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("$ASSETS/encoder.onnx").use { input -> temporary.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
          }
        } }
        require(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash)
        require(temporary.length() == modelInfo.getLong("bytes"))
        if (modelFile.exists()) require(modelFile.delete())
        require(temporary.renameTo(modelFile))
      }
      val vocab = verifiedAsset("vocab.txt", files)
      tokenizer = BertWordPieceTokenizer(vocab.lineSequence().toList())
      val head = JSONObject(verifiedAsset("intent_head.json", files))
      fun floats(array: JSONArray) = FloatArray(array.length()) { array.getDouble(it).toFloat() }
      fun vectors(array: JSONArray) = (0 until array.length()).map { floats(array.getJSONArray(it)) }
      val classes = head.getJSONArray("classes")
      intentHead = SemanticIntentHead(
        (0 until classes.length()).map { NotificationIntent.valueOf(classes.getString(it)) },
        vectors(head.getJSONArray("weights")), floats(head.getJSONArray("bias")),
        head.getDouble("temperature").toFloat(), head.getDouble("minimumConfidence").toFloat(),
        head.getDouble("minimumMargin").toFloat(), head.getDouble("minimumOODSimilarity").toFloat(),
        vectors(head.getJSONArray("supportVectors"))
      )
      options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      }
      session = environment.createSession(modelFile.absolutePath, options)
      require(session!!.inputNames == setOf("input_ids", "attention_mask"))
      NotificationSemanticRuntime.state = "ready"
      NotificationSemanticRuntime.error = null
      true
    } catch (_: Exception) {
      closeResources()
      NotificationSemanticRuntime.state = "unavailable"
      NotificationSemanticRuntime.error = "offline_model_unavailable"
      false
    } catch (_: OutOfMemoryError) {
      closeResources()
      NotificationSemanticRuntime.state = "unavailable"
      NotificationSemanticRuntime.error = "offline_model_memory_limit"
      false
    } catch (_: LinkageError) {
      closeResources()
      NotificationSemanticRuntime.state = "unavailable"
      NotificationSemanticRuntime.error = "offline_runtime_unavailable_for_device"
      false
    }
  }

  fun infer(text: String): SemanticIntentMatch? {
    return inferEmbedding(text)?.let { intentHead?.match(it) }
  }

  /**
   * Embeds the current message with a small, newest-first context window. The current message has
   * the largest weight; context can resolve a short continuation, but cannot replace a complete
   * current cancellation or other latest-message rule decision.
   */
  fun inferContextual(text: String, precedingMessages: List<String>): SemanticIntentMatch? {
    if (precedingMessages.isEmpty()) return infer(text)
    if (!prepare()) return null
    val current = inferEmbedding(text) ?: return null
    val context = precedingMessages.asReversed().take(MAX_CONTEXT_MESSAGES).mapNotNull { message ->
      inferEmbedding(message)
    }
    if (context.isEmpty()) return intentHead?.match(current)
    return intentHead?.matchContext(current, context)?.copy(contextual = true)
  }

  private fun inferEmbedding(text: String): FloatArray? {
    if (text.isBlank() || text.length > 2_048 || !prepare()) return null
    val ids = tokenizer!!.encode(text)
    // A truncated prefix may omit a cancellation or new action at the end. Abstain instead.
    if (ids.size >= 128) return null
    return try {
      OnnxTensor.createTensor(environment, arrayOf(ids)).use { tokens ->
        OnnxTensor.createTensor(environment, arrayOf(LongArray(ids.size) { 1L })).use { mask ->
          session!!.run(mapOf("input_ids" to tokens, "attention_mask" to mask)).use { result ->
            @Suppress("UNCHECKED_CAST")
            val embedding = (result.get("sentence_embedding").get().value as Array<FloatArray>)[0]
            embedding
          }
        }
      }
    } catch (_: Exception) {
      NotificationSemanticRuntime.error = "offline_inference_failed"
      null
    }
  }

  private fun readAsset(name: String) = context.assets.open("$ASSETS/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
  private fun verifiedAsset(name: String, files: JSONObject): String {
    val text = readAsset(name)
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    require(digest == files.getJSONObject(name).getString("sha256"))
    return text
  }
  private fun closeResources() {
    runCatching { session?.close() }
    runCatching { options?.close() }
    session = null; options = null; tokenizer = null; intentHead = null
  }
  override fun close() {
    closeResources()
    NotificationSemanticRuntime.state = "stopped"
  }
  companion object {
    private const val ASSETS = "notification_semantics"
    private const val MAX_CONTEXT_MESSAGES = 3
  }
}

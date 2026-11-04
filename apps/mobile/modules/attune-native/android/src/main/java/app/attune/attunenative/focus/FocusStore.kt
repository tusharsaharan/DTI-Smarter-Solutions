package app.attune.attunenative.focus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Durable write-ahead intent + exact shared DND snapshot identity, never global-filter ownership. */
data class FocusOwnedSession(
  val id: String,
  val origin: String,
  val untilMs: Long,
  val attemptedAtMs: Long,
  val snapshotAtMs: Long = 0L,
  val appliedPolicyKey: String? = null,
  val policyUpdatePending: Boolean = false,
  val policyUpdateReason: String? = null,
  val updatePolicyKey: String? = null,
  val expectedFilter: Int? = null,
  val writeTransactionId: String? = null,
  val writeStartedAtMs: Long = 0,
  val receipts: List<FocusDndReceipt>? = emptyList(),
  val restoreAuthorization: FocusRestoreAuthorization? = null,
  val phase: String = "prepared",
  val reason: String = "manual_choice"
)

/** A pending Off is an intent, not evidence that the restoration policy has been written. */
data class FocusRestoreAuthorization(val phase: String, val appliedPolicyKey: String?, val updatePolicyKey: String?)

data class FocusDndReceipt(val transactionId: String, val policyChanged: Boolean, val value: String,
  val notBeforeMs: Long, val untilMs: Long)

data class FocusSuppression(val key: String, val untilMs: Long)

data class FocusState(
  val version: Int = 1,
  val enabled: Boolean = false,
  val allowPrioritySenders: Boolean = true,
  val allowRepeatCallers: Boolean = true,
  val ruleRevision: String = "initial",
  val timeZone: String = java.time.ZoneId.systemDefault().id,
  val schedule: FocusSchedule? = null,
  val samples: List<FocusSample> = emptyList(),
  val model: FocusModel = FocusModel(-4.0, emptyList(), 0, 0, 0, 0),
  val owned: FocusOwnedSession? = null,
  val suppressions: List<FocusSuppression> = emptyList(),
  val cooldownUntilMs: Long = 0
)

/** Same Keystore AES-GCM foundation as keyboard learning, with atomic, no-backup storage. */
class FocusStore(context: Context) {
  private val file = AtomicFile(File(context.noBackupFilesDir, "attune_focus_v1.bin"))
  private val gson = Gson()
  var readFailed: Boolean = false
    private set

  fun load(): FocusState {
    if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) return FocusState()
    return runCatching {
      require(file.baseFile.length() <= MAX_BYTES)
      val encoded = file.readFully()
      require(encoded.size in 32..MAX_BYTES)
      val buffer = ByteBuffer.wrap(encoded)
      require(buffer.int == MAGIC)
      val ivLength = buffer.int
      require(ivLength == 12 && buffer.remaining() >= ivLength + 16)
      val iv = ByteArray(ivLength).also(buffer::get)
      val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
      cipher.updateAAD(AAD)
      val state = gson.fromJson(cipher.doFinal(ciphertext).toString(Charsets.UTF_8), FocusState::class.java)
      require(state.version == 1 && state.samples.size <= FocusLearning.MAX_SAMPLES && state.suppressions.size <= 32)
      require(state.samples.all { it.features.valid() } && state.ruleRevision.length in 1..200)
      java.time.ZoneId.of(state.timeZone)
      require(state.model.intercept.isFinite() && state.model.trees.size <= 24 && state.model.trees.all {
        it.feature in 0 until FocusFeatures.SIZE && it.threshold.isFinite() && it.left.isFinite() && it.right.isFinite()
      })
      require(state.model.positiveTimeBins.size <= FocusLearning.MAX_SAMPLES && state.model.positiveTimeBins.values.all { it in 1..FocusLearning.MAX_SAMPLES })
      state.schedule?.let { FocusSchedule.create(it.entries, it.validFrom, it.validUntil, it.timeZone, it.revision) }
      state.owned?.let {
        require(it.origin in setOf("automatic", "manual") && it.phase in setOf("prepared", "active", "updating", "restoring"))
        it.restoreAuthorization?.let { authorization ->
          require(authorization.phase in setOf("prepared", "active", "updating", "restoring"))
        }
        require(it.receipts.orEmpty().size <= FocusChangeSafety.MAX_RECEIPTS && it.receipts.orEmpty().all { receipt ->
          receipt.value.length in 1..512 && receipt.notBeforeMs >= it.attemptedAtMs &&
            receipt.untilMs - receipt.notBeforeMs in 0..FocusChangeSafety.RECEIPT_MAX_SPAN_MS
        })
      }
      state
    }.getOrElse {
      // Do not discard an unreadable ownership ledger or manufacture a replacement ownership.
      // DND remains untouched; the UI reports that automatic actions are blocked.
      readFailed = true
      FocusState()
    }
  }

  fun save(state: FocusState): Boolean {
    if (readFailed) return false
    var output: java.io.FileOutputStream? = null
    return runCatching {
      val plaintext = gson.toJson(state).toByteArray(Charsets.UTF_8)
      require(plaintext.size < MAX_BYTES - 64)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key())
      cipher.updateAAD(AAD)
      val ciphertext = cipher.doFinal(plaintext)
      val encoded = ByteBuffer.allocate(8 + cipher.iv.size + ciphertext.size).apply {
        putInt(MAGIC); putInt(cipher.iv.size); put(cipher.iv); put(ciphertext)
      }.array()
      file.baseFile.parentFile?.mkdirs()
      output = file.startWrite()
      output!!.write(encoded)
      output!!.fd.sync()
      file.finishWrite(output)
      output = null
      // AtomicFile's finishWrite logs some rename failures rather than throwing. Verify the
      // committed ciphertext before letting the runtime act on a supposedly durable intent.
      require(file.readFully().contentEquals(encoded)) { "focus_atomic_commit_failed" }
      true
    }.getOrElse {
      output?.let { runCatching { file.failWrite(it) } }
      false
    }
  }

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
    return generator.generateKey()
  }

  companion object {
    private const val ALIAS = "attune_focus_v1"
    private const val MAGIC = 0x41544631
    private const val MAX_BYTES = 512 * 1024
    private val AAD = "attune-focus-local-v1".toByteArray(Charsets.UTF_8)
  }
}

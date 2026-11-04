package app.attune.attunenative.notification

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SemanticIntentHeadTest {
  private fun floats(array: JsonArray) = FloatArray(array.size()) { array[it].asFloat }
  private fun vectors(array: JsonArray) = array.map { floats(it.asJsonArray) }

  @Test fun packagedHeadMatchesIndependentCpuGoldenVectors() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.take(12)
      .first { File(it, "models/notification/intent_head_golden.json").exists() }
    val headFile = File(root, "apps/mobile/modules/attune-native/android/src/main/assets/notification_semantics/intent_head.json")
    val head = JsonParser.parseString(headFile.readText()).asJsonObject
    val goldens = JsonParser.parseString(File(root, "models/notification/intent_head_golden.json").readText()).asJsonObject
    assertEquals(goldens["head_sha256"].asString, MessageDigest.getInstance("SHA-256").digest(headFile.readBytes()).joinToString("") { "%02x".format(it) })
    val classifier = SemanticIntentHead(
      head["classes"].asJsonArray.map { NotificationIntent.valueOf(it.asString) },
      vectors(head["weights"].asJsonArray), floats(head["bias"].asJsonArray), head["temperature"].asFloat,
      head["minimumConfidence"].asFloat, head["minimumMargin"].asFloat, head["minimumOODSimilarity"].asFloat,
      vectors(head["supportVectors"].asJsonArray)
    )
    val cases = goldens["cases"].asJsonArray
    assertEquals(8, cases.size())
    cases.forEach { sample ->
      val item = sample.asJsonObject
      val prediction = classifier.match(floats(item["vector"].asJsonArray))
      if (item["expectedIntent"].asString == "UNCERTAIN") assertNull(item["id"].asString, prediction)
      else {
        assertEquals(item["id"].asString, item["expectedIntent"].asString, prediction?.intent?.name)
        assertEquals(item["confidence"].asFloat, prediction!!.similarity, .0001f)
        assertEquals(item["margin"].asFloat, prediction.margin, .0001f)
      }
    }
  }

  private fun smallHead() = SemanticIntentHead(
    listOf(NotificationIntent.ACTION_REQUIRED, NotificationIntent.INFORMATIONAL),
    listOf(floatArrayOf(3f, 0f), floatArrayOf(-3f, 0f)), floatArrayOf(0f, 0f), 1f,
    .6f, .2f, .5f, listOf(floatArrayOf(1f, 0f))
  )

  @Test fun invalidAndUnsupportedInputsAbstainEvenWhenClassifierCouldBeConfident() {
    val head = smallHead()
    assertNull(head.match(floatArrayOf(0f, 0f)))
    assertNull(head.match(floatArrayOf(Float.NaN, 1f)))
    assertNull(head.match(floatArrayOf(1f)))
    assertNull(head.match(floatArrayOf(-1f, 0f)))
    assertEquals(NotificationIntent.ACTION_REQUIRED, head.match(floatArrayOf(1f, 0f))?.intent)
  }

  @Test fun newestMessageDominatesButRecentContextCanResolveAContinuation() {
    val current = floatArrayOf(-0.2f, 0f)
    val prior = listOf(floatArrayOf(1f, 0f))

    val contextual = smallHead().matchContext(current, prior)

    assertEquals(NotificationIntent.ACTION_REQUIRED, contextual?.intent)
    assertTrue(contextual?.contextual == true)
  }
}

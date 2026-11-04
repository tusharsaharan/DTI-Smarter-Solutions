package app.attune.attunenative.notification

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BertWordPieceTokenizerTest {
  private data class GoldenEncoding(val text: String, val ids: List<Long>)
  // Special IDs deliberately differ from the released vocabulary; IDs must come from line order.
  private val vocab = listOf(
    "[UNK]", "[CLS]", "[PAD]", "[SEP]", "[MASK]", "Hello", "hello", "world", "!", ",",
    "un", "##aff", "##able", "café", "cafe", "##\u0301", "中", "文", "𠀀", "😀", "##😀",
    "foo", "bar", "foobar", "。", "$", "नमस्ते", "##जी", "’", "s", "a", "##b"
  )
  private val tokenizer = BertWordPieceTokenizer(vocab)

  private fun expect(text: String, vararg tokens: String, maxLength: Int = 128) {
    val expected = listOf("[CLS]") + tokens + "[SEP]"
    assertArrayEquals(expected.map { vocab.indexOf(it).toLong() }.toLongArray(), tokenizer.encode(text, maxLength))
  }

  @Test
  fun casePunctuationAndVocabularyLineIdsArePreserved() {
    expect("Hello, hello world!", "Hello", ",", "hello", "world", "!")
    expect("HELLO", "[UNK]")
    assertEquals(2L, tokenizer.padTokenId)
  }

  @Test
  fun wordPieceUsesGreedyLongestMatchAndContinuingPrefixes() {
    expect("unaffable foobar", "un", "##aff", "##able", "foobar")
    expect("ab", "a", "##b")
  }

  @Test
  fun unknownSuffixDiscardsAllPartialPiecesForTheWord() {
    expect("unaffxyz hello", "[UNK]", "hello")
  }

  @Test
  fun casedNormalizerDoesNotStripAccentsOrComposeDecomposedUnicode() {
    expect("café cafe\u0301", "café", "cafe", "##\u0301")
    expect("नमस्तेजी", "नमस्ते", "##जी")
  }

  @Test
  fun punctuationIncludesAsciiSymbolsAndUnicodePunctuation() {
    expect("Hello\$world。hello’s", "Hello", "$", "world", "。", "hello", "’", "s")
  }

  @Test
  fun chineseCharactersAreSplitIncludingSupplementaryCodePoints() {
    expect("Hello中文𠀀world", "Hello", "中", "文", "𠀀", "world")
  }

  @Test
  fun whitespaceSeparatesButRemovedControlsJoinAdjacentText() {
    expect("Hello\tworld\r\nhello\u00A0world\u2028Hello", "Hello", "world", "hello", "world", "Hello")
    expect("foo\u0000\u000B\uFFFD\u200D\uE000bar", "foobar")
  }

  @Test
  fun specialTokensAreIsolatedBeforeCleaningEvenWhenAdjacentToWords() {
    expect("foo[CLS]bar[MASK][UNK][PAD][SEP]", "foo", "[CLS]", "bar", "[MASK]", "[UNK]", "[PAD]", "[SEP]")
    // Cleaning must not synthesize an added token from text that only resembles one.
    val specialOnly = BertWordPieceTokenizer(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "[", "CLS", "]"))
    assertArrayEquals(longArrayOf(2L, 5L, 6L, 7L, 3L), specialOnly.encode("[CL\u0000S]"))
  }

  @Test
  fun wordLengthLimitCountsUnicodeCodePointsRatherThanUtf16Units() {
    val encoded = tokenizer.encode("😀".repeat(60))
    assertEquals(62, encoded.size)
    assertEquals(vocab.indexOf("😀").toLong(), encoded[1])
    assertTrue(encoded.slice(2 until encoded.lastIndex).all { it == vocab.indexOf("##😀").toLong() })
    expect("😀".repeat(101), "[UNK]")
  }

  @Test
  fun truncationReservesSepAndCanStopWithinASplitWord() {
    expect("unaffable hello world", "un", "##aff", maxLength = 4)
    expect("hello", maxLength = 2)
    val maximum = tokenizer.encode("hello ".repeat(200))
    assertEquals(128, maximum.size)
    assertEquals(vocab.indexOf("[SEP]").toLong(), maximum.last())
  }

  @Test
  fun emptyOrControlOnlyInputHasOnlyBoundariesAndNoPadding() {
    expect("")
    expect("\u0000\uFFFD\u200D\n")
  }

  @Test
  fun packagedFullVocabularyMatchesPinnedHuggingFaceFastTokenizerGoldens() {
    // Gradle starts JVM tests in the module project directory; IDEs may start in the repo root.
    val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
      .take(10)
      .firstOrNull { directory ->
        File(directory, "models/notification/tokenizer_golden.json").isFile &&
          File(directory, "apps/mobile/modules/attune-native/android/src/main/assets/notification_semantics/vocab.txt").isFile
      } ?: error("Pinned tokenizer vocabulary and golden fixture must be present in this checkout")
    val vocabularyFile = File(root, "apps/mobile/modules/attune-native/android/src/main/assets/notification_semantics/vocab.txt")
    val fullVocabulary = vocabularyFile.readLines(Charsets.UTF_8)
    assertEquals(119_547, fullVocabulary.size)
    assertEquals("[PAD]", fullVocabulary[0])
    assertEquals(listOf("[UNK]", "[CLS]", "[SEP]", "[MASK]"), fullVocabulary.subList(100, 104))
    val fullTokenizer = BertWordPieceTokenizer(fullVocabulary)
    assertEquals(0L, fullTokenizer.padTokenId)
    // Gson is the real JVM JSON parser, unlike android.org.json's unit-test stubs.
    val goldens = File(root, "models/notification/tokenizer_golden.json").reader(Charsets.UTF_8).use { reader ->
      Gson().fromJson(reader, Array<GoldenEncoding>::class.java)
    }
    assertTrue("Golden corpus must include the exported Unicode and special-token cases", goldens.size >= 9)
    goldens.forEachIndexed { index, golden ->
      assertArrayEquals("Fast tokenizer golden case $index", golden.ids.toLongArray(), fullTokenizer.encode(golden.text))
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsAModelSequenceLengthBeyondTheRuntimeContract() {
    tokenizer.encode("hello", 129)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsVocabularyWithoutRequiredSpecialTokens() {
    BertWordPieceTokenizer(listOf("hello"))
  }
}

/*
 * BERT/WordPiece algorithm: Copyright 2018 The Google AI Language Team Authors
 * and The HuggingFace Inc. team. Apache License 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Kotlin implementation of Hugging Face tokenizers 0.22.1 BertNormalizer,
 * BertPreTokenizer and WordPiece. References:
 * https://github.com/huggingface/tokenizers/tree/v0.22.1/tokenizers/src
 */
package app.attune.attunenative.notification

/**
 * Matches distiluse-base-multilingual-cased-v2's fast tokenizer: preserve case and
 * accents, clean controls, split Chinese characters/punctuation, then greedy WordPiece.
 * No NFC/NFD normalization: the fast BERT normalizer preserves decomposed accents.
 * All IDs come from the exact vocabulary line positions. Raw input is never retained.
 */
class BertWordPieceTokenizer(vocab: List<String>) {
  private val ids = HashMap<String, Long>(vocab.size).apply {
    vocab.forEachIndexed { index, token -> put(token, index.toLong()) }
  }
  private val clsId = requiredId("[CLS]")
  private val sepId = requiredId("[SEP]")
  private val unkId = requiredId("[UNK]")
  val padTokenId: Long = requiredId("[PAD]")
  private val specialTokens = listOf("[UNK]", "[SEP]", "[PAD]", "[CLS]", "[MASK]")
    .filter { ids.containsKey(it) }

  /** Unpadded IDs, including CLS/SEP. Right truncation always reserves the final SEP. */
  fun encode(text: String, maxLength: Int = MAX_LENGTH): LongArray {
    require(maxLength in 2..MAX_LENGTH) { "BERT sequence length must be between 2 and $MAX_LENGTH" }
    val output = ArrayList<Long>(maxLength)
    output.add(clsId)
    val word = StringBuilder()
    var wordCodePoints = 0

    fun flushWord() {
      if (wordCodePoints == 0) return
      if (output.size < maxLength - 1) {
        val pieces = if (wordCodePoints > MAX_WORD_CODE_POINTS) listOf(unkId) else wordPieces(word.toString())
        for (piece in pieces) {
          if (output.size == maxLength - 1) break
          output.add(piece)
        }
      }
      word.setLength(0)
      wordCodePoints = 0
    }

    var offset = 0
    while (offset < text.length && output.size < maxLength - 1) {
      // Added special tokens are isolated before normalization, even when adjacent to text.
      val special = if (text[offset] == '[') specialTokens.firstOrNull { text.startsWith(it, offset) } else null
      if (special != null) {
        flushWord()
        if (output.size < maxLength - 1) output.add(ids.getValue(special))
        offset += special.length
        continue
      }
      val cp = text.codePointAt(offset)
      offset += Character.charCount(cp)
      if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
      when {
        isWhitespace(cp) -> flushWord()
        isChinese(cp) || isPunctuation(cp) -> {
          flushWord()
          if (output.size < maxLength - 1) {
            output.add(ids[String(Character.toChars(cp))] ?: unkId)
          }
        }
        else -> {
          wordCodePoints += 1
          // Oversized words become one UNK. Avoid retaining an arbitrarily large input word.
          if (wordCodePoints <= MAX_WORD_CODE_POINTS) word.appendCodePoint(cp)
        }
      }
    }
    flushWord()
    output.add(sepId)
    return output.toLongArray()
  }

  private fun wordPieces(word: String): List<Long> {
    val offsets = ArrayList<Int>(MAX_WORD_CODE_POINTS + 1)
    var index = 0
    while (index < word.length) {
      offsets.add(index)
      index += Character.charCount(word.codePointAt(index))
    }
    offsets.add(word.length)
    val pieces = ArrayList<Long>()
    var start = 0
    while (start < offsets.lastIndex) {
      var end = offsets.lastIndex
      var piece: Long? = null
      while (end > start) {
        val candidate = (if (start == 0) "" else "##") + word.substring(offsets[start], offsets[end])
        piece = ids[candidate]
        if (piece != null) break
        end -= 1
      }
      // A partial prefix is not a valid encoding when a later suffix has no vocabulary match.
      if (piece == null) return listOf(unkId)
      pieces.add(piece)
      start = end
    }
    return pieces
  }

  private fun requiredId(token: String): Long = requireNotNull(ids[token]) { "BERT vocabulary is missing $token" }

  private fun isWhitespace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

  private fun isControl(cp: Int): Boolean {
    if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
    // Fast tokenizers uses Unicode's whole Other category, including private/unassigned values.
    return when (Character.getType(cp)) {
      Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.PRIVATE_USE.toInt(),
      Character.UNASSIGNED.toInt(), Character.SURROGATE.toInt() -> true
      else -> false
    }
  }

  private fun isPunctuation(cp: Int): Boolean {
    if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
    return when (Character.getType(cp)) {
      Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
      Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
      Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
      Character.OTHER_PUNCTUATION.toInt() -> true
      else -> false
    }
  }

  private fun isChinese(cp: Int): Boolean = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
    cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F ||
    cp in 0x2B920..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

  companion object {
    const val MAX_LENGTH = 128
    private const val MAX_WORD_CODE_POINTS = 100
  }
}

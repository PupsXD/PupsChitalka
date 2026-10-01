package com.ozvuchka.app.speech

/**
 * Turns text into the inputs of Vosk TTS 0.10 (model type `multistream_v1`): BERT token ids, and for
 * every phoneme five ids (phoneme, its punctuation, inside quotes, last punctuation, sentence end)
 * plus the BERT row of its word. A port of `Synth.get_word_bert` and `Synth.g2p_multistream` from
 * vosk-tts 0.3.61 (Apache 2.0); app/src/test/resources/vosk holds what the Python code gives.
 *
 * Two departures, both for text the Python code crashes on: [sanitize] keeps only characters the
 * model knows, and a phoneme missing from the model's table is dropped instead of raising KeyError.
 */
internal class VoskFrontend(
    private val phonemeIds: Map<String, Int>,
    private val tokenizer: BertWordPiece,
    private val lookup: (String) -> String?,
) {
    class Prepared(
        val tokenIds: LongArray,
        /** Rows of the BERT output that stand for words: the n-th row of the word embeddings. */
        val wordTokens: IntArray,
        /** One row per phoneme: phoneme, punctuation, in quotes, last punctuation, sentence end. */
        val phonemes: Array<IntArray>,
        /** Index into [wordTokens] for each phoneme. */
        val bertRows: IntArray,
    )

    fun prepare(text: String): Prepared {
        val clean = sanitize(text)
        val tokens = tokenizer.encode(clean.replace("+", ""))
        val wordTokens = tokens.indices.filter { i ->
            val token = tokens[i].text
            token[0] != '#' && token[0] !in "-,.?!;:\""
        }.toIntArray()
        val (phonemes, rows) = multistream(clean)
        val lastRow = (wordTokens.size - 1).coerceAtLeast(0)
        return Prepared(
            tokenIds = LongArray(tokens.size) { tokens[it].id.toLong() },
            wordTokens = wordTokens,
            phonemes = phonemes,
            bertRows = IntArray(rows.size) { rows[it].coerceAtMost(lastRow) },
        )
    }

    private class Phone(val symbol: String, val punctuation: List<String>, val inQuote: Int, val word: Int)

    private fun multistream(text: String): Pair<Array<IntArray>, IntArray> {
        val phones = ArrayList<Phone>()
        phones += Phone("^", emptyList(), 0, 0)
        var inQuote = 0
        var punctuation = ArrayList<String>()
        var word = 1
        for (piece in split(text.replace(" -", "- ").lowercase())) {
            when {
                piece.isEmpty() -> Unit
                piece == "\"" -> inQuote = 1 - inQuote
                piece == "- " || piece == "-" -> punctuation += "-"
                piece != " " && startsWithSeparator(piece) -> punctuation += piece
                piece == " " -> {
                    phones += Phone(" ", punctuation, inQuote, word)
                    punctuation = ArrayList()
                }
                else -> {
                    val symbols = (lookup(piece) ?: convert(piece)).split(' ').filter { it.isNotEmpty() }
                    for (symbol in symbols) phones += Phone(symbol, emptyList(), inQuote, word)
                    punctuation = ArrayList()
                    word++
                }
            }
        }
        phones += Phone(" ", punctuation, inQuote, word)
        phones += Phone("$", emptyList(), 0, word)

        val rows = ArrayList<IntArray>(phones.size)
        val bert = ArrayList<Int>(phones.size)
        var lastPunctuation = " "
        var sentenceEnd = " "
        for (phone in phones.asReversed()) {
            val marks = phone.punctuation
            when {
                "..." in marks -> sentenceEnd = "..."
                "." in marks -> sentenceEnd = "."
                "!" in marks -> sentenceEnd = "!"
                "?" in marks -> sentenceEnd = "?"
                "-" in marks -> sentenceEnd = "-"
            }
            if (marks.isNotEmpty()) lastPunctuation = marks[0]
            val symbol = phonemeIds[phone.symbol] ?: continue // the Python code raises KeyError here
            rows += intArrayOf(
                symbol,
                phonemeIds.getValue(marks.firstOrNull() ?: "_"),
                phone.inQuote,
                phonemeIds.getValue(lastPunctuation),
                phonemeIds.getValue(sentenceEnd),
            )
            bert += phone.word
        }
        rows.reverse()
        bert.reverse()
        return rows.toTypedArray() to bert.toIntArray()
    }

    companion object {
        /** `(\.\.\.|- |[ ,.?!;:"()])`, the separators of `re.split` in g2p_multistream. */
        private val SEPARATOR = Regex("""(\.\.\.|- |[ ,.?!;:"()])""")
        private const val SEPARATOR_CHARS = " ,.?!;:\"()"

        private fun startsWithSeparator(piece: String) =
            piece.startsWith("...") || piece.startsWith("- ") || piece[0] in SEPARATOR_CHARS

        /** Python's `re.split` with one capturing group: the text between matches and the matches. */
        internal fun split(text: String): List<String> {
            val result = ArrayList<String>()
            var cursor = 0
            for (match in SEPARATOR.findAll(text)) {
                result += text.substring(cursor, match.range.first)
                result += match.value
                cursor = match.range.last + 1
            }
            result += text.substring(cursor)
            return result
        }

        private val QUOTES = Regex("[«»„“”]")
        private val DASHES = Regex("[–—‒]")
        private val APOSTROPHES = Regex("[’`]")
        private val UNKNOWN = Regex("""[^А-Яа-яЁёA-Za-zÄÖäö'+ ,.?!;:"()\-]""")
        private val SPACES = Regex(" +")

        /** Only characters the model's dictionary, letter rules and punctuation know. */
        fun sanitize(text: String): String = text
            .replace(QUOTES, "\"")
            .replace("…", "...")
            .replace(DASHES, "-")
            .replace(APOSTROPHES, "'")
            .replace(UNKNOWN, " ")
            .replace(SPACES, " ")
            .trim()

        // vosk_tts/g2p.py: letter-to-phoneme rules for words the dictionary lacks; «+» marks the stress.
        private const val SOFT_LETTERS = "яёюиье"
        private const val SYLLABLE_STARTS = "#ъьаяоёуюэеиы-"
        private val OTHERS = setOf("#", "+", "-", "ь", "ъ")
        private val SOFT_HARD = mapOf(
            'б' to "b", 'в' to "v", 'г' to "g", 'Г' to "g", 'д' to "d", 'з' to "z", 'к' to "k", 'л' to "l",
            'м' to "m", 'н' to "n", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'ф' to "f", 'х' to "h",
        )
        private val OTHER_CONSONANTS = mapOf('ж' to "zh", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'й' to "j")
        private val VOWELS = mapOf(
            'а' to "a", 'я' to "a", 'у' to "u", 'ю' to "u", 'о' to "o", 'ё' to "o", 'э' to "e", 'е' to "e",
            'и' to "i", 'ы' to "y",
        )

        fun convert(word: String): String {
            // (symbol, stress) for "#word#", the «+» folded into the next letter's stress
            val letters = ArrayList<Pair<String, Int>>()
            var stress = 0
            for (c in "#$word#") {
                if (c == '+') {
                    stress = 1
                } else {
                    letters += c.toString() to stress
                    stress = 0
                }
            }
            for (i in 0 until letters.size - 1) {
                val c = letters[i].first[0]
                SOFT_HARD[c]?.let { hard ->
                    letters[i] = (if (letters[i + 1].first[0] in SOFT_LETTERS) hard + "j" else hard) to 0
                }
                OTHER_CONSONANTS[c]?.let { letters[i] = it to 0 }
            }
            val phones = ArrayList<String>()
            var previous = ""
            for ((symbol, accent) in letters) {
                val c = symbol[0]
                if (previous in SYLLABLE_STARTS && previous.length == 1 && symbol.length == 1 && c in "яюеё") phones += "j"
                phones += VOWELS[c]?.takeIf { symbol.length == 1 }?.let { it + accent } ?: symbol
                previous = symbol
            }
            return phones.filter { it !in OTHERS }.joinToString(" ")
        }
    }
}

/**
 * HuggingFace `BertWordPieceTokenizer(lowercase=False)` as Vosk uses it with ruBert-base: whitespace
 * split, every punctuation mark a token of its own, greedy longest WordPiece match, [CLS] … [SEP].
 */
internal class BertWordPiece(private val vocab: Map<String, Int>) {
    class Token(val text: String, val id: Int)

    private val unknown = vocab.getValue("[UNK]")

    fun encode(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        tokens += Token("[CLS]", vocab.getValue("[CLS]"))
        for (word in preTokenize(clean(text))) tokens += wordPieces(word)
        tokens += Token("[SEP]", vocab.getValue("[SEP]"))
        return tokens
    }

    private fun clean(text: String) = buildString(text.length) {
        for (c in text) {
            when {
                c == '\u0000' || c == '�' -> Unit
                c == '\t' || c == '\n' || c == '\r' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt() -> append(' ')
                Character.getType(c).toByte() in CONTROL_TYPES -> Unit
                else -> append(c)
            }
        }
    }

    private fun preTokenize(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) words += current.toString()
            current.clear()
        }
        for (c in text) {
            when {
                c == ' ' -> flush()
                isPunctuation(c) -> {
                    flush()
                    words += c.toString()
                }
                else -> current.append(c)
            }
        }
        flush()
        return words
    }

    private fun wordPieces(word: String): List<Token> {
        if (word.length > 100) return listOf(Token("[UNK]", unknown))
        val pieces = ArrayList<Token>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Token? = null
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + word.substring(start, end)
                vocab[piece]?.let { found = Token(piece, it) }
                if (found != null) break
                end--
            }
            val token = found ?: return listOf(Token("[UNK]", unknown))
            pieces += token
            start = end
        }
        return pieces
    }

    private fun isPunctuation(c: Char): Boolean {
        val code = c.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    companion object {
        /** Unicode categories C*, which the BERT normalizer removes. */
        private val CONTROL_TYPES = setOf(
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
        )

        /** vocab.txt: one token per line, the line number is its id; a repeated line keeps the last id. */
        fun load(lines: Sequence<String>): BertWordPiece {
            val vocab = HashMap<String, Int>()
            lines.forEachIndexed { index, line -> vocab[line.trimEnd()] = index }
            return BertWordPiece(vocab)
        }
    }
}

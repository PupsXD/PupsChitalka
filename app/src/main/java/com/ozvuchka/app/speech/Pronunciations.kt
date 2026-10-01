package com.ozvuchka.app.speech

import android.content.Context
import org.json.JSONObject
import kotlin.math.max

/**
 * How an engine takes a stress mark: RuVoice reads «з+амок», other engines a combining acute.
 * [MARKED] is «з+амок» for the reader's own words only: Vosk looks every other word up in its own
 * dictionary, which also knows how a word sounds («что» as «што»), and a «+» would bypass it.
 */
enum class StressStyle { PLUS, MARKED, ACUTE, NONE }

internal fun stressStyleFor(voice: VoiceChoice): StressStyle = when {
    voice.engine == VoiceEngine.VOSK -> StressStyle.MARKED
    voice.engine != VoiceEngine.SYSTEM -> StressStyle.NONE
    voice.enginePackage == VoiceCatalog.RUVOICE_PACKAGE -> StressStyle.PLUS
    else -> StressStyle.ACUTE
}

private const val VOWELS = "аеёиоуыэюяАЕЁИОУЫЭЮЯaeiouyAEIOUY"
private const val ACUTE = '́'

/**
 * Text sent to a synthesizer together with the original index of each of its characters, so word
 * ranges an engine reports can be mapped back onto the book.
 */
internal class Rewrite(val text: String, private val sourceIndex: IntArray) {
    /** The original range for [start, end) in [text]. */
    fun originalRange(start: Int, end: Int): IntRange {
        val first = sourceIndex[start.coerceIn(0, sourceIndex.lastIndex)]
        val last = sourceIndex[(end - 1).coerceIn(0, sourceIndex.lastIndex)]
        return first..max(first, last)
    }

    companion object {
        fun identity(text: String) = Rewrite(text, IntArray(text.length + 1) { it })
    }
}

private class RewriteBuilder(private val sourceLength: Int) {
    private val text = StringBuilder()
    private val index = ArrayList<Int>()

    fun copy(source: String, from: Int, to: Int) {
        for (position in from until to) {
            text.append(source[position])
            index += position
        }
    }

    /** Characters of a replaced word map proportionally onto the word they replace. */
    fun replace(replacement: String, from: Int, to: Int) {
        val length = to - from
        replacement.forEachIndexed { offset, c ->
            text.append(c)
            index += from + minOf(offset * length / max(1, replacement.length), length - 1)
        }
    }

    fun build(): Rewrite {
        index += sourceLength
        return Rewrite(text.toString(), index.toIntArray())
    }
}

/**
 * The reader's own pronunciations: a word maps to how it should be said, with «+» before the stressed
 * vowel («з+амок») or as a different spelling. Keys ignore case and «ё».
 */
class PronunciationDictionary(
    entries: Map<String, String>,
    private val defaultStresses: ((String) -> String?)? = null,
) {
    private val entries: Map<String, String> = entries.mapKeys { normalizeWord(it.key) }

    val isEmpty: Boolean get() = entries.isEmpty() && defaultStresses == null

    internal fun apply(text: String, style: StressStyle): Rewrite {
        if (entries.isEmpty() && (style != StressStyle.PLUS || defaultStresses == null)) return Rewrite.identity(text)
        val builder = RewriteBuilder(text.length)
        var cursor = 0
        var changed = false
        for (match in WORD.findAll(text)) {
            val spoken = entries[normalizeWord(match.value)] ?: if (
                style == StressStyle.PLUS && text.getOrNull(match.range.last + 1) != ACUTE
            ) defaultStresses?.invoke(match.value.lowercase()) else null
            if (spoken == null) continue
            builder.copy(text, cursor, match.range.first)
            builder.replace(styled(matchCase(spoken, match.value), style), match.range.first, match.range.last + 1)
            cursor = match.range.last + 1
            changed = true
        }
        if (!changed) return Rewrite.identity(text)
        builder.copy(text, cursor, text.length)
        return builder.build()
    }

    companion object {
        val EMPTY = PronunciationDictionary(emptyMap())
        private val WORD = Regex("\\p{L}+(?:[-'’]\\p{L}+)*")

        fun normalizeWord(word: String): String = word.lowercase().replace('ё', 'е').replace("+", "")

        /** «з+амок» as a reader sees it: «за́мок». */
        fun display(spoken: String): String = styled(spoken, StressStyle.ACUTE)

        internal fun styled(spoken: String, style: StressStyle): String = buildString {
            var index = 0
            while (index < spoken.length) {
                val c = spoken[index]
                val vowel = spoken.getOrNull(index + 1)
                if (c == '+' && vowel != null && vowel in VOWELS) {
                    when (style) {
                        StressStyle.PLUS, StressStyle.MARKED -> append('+').append(vowel)
                        StressStyle.ACUTE -> append(vowel).append(ACUTE)
                        StressStyle.NONE -> append(vowel)
                    }
                    index += 2
                } else {
                    append(c)
                    index++
                }
            }
        }

        /** Capitals follow the book: «Замок» at a sentence start stays capitalised. */
        private fun matchCase(spoken: String, original: String): String {
            val letters = original.filter { it.isLetter() }
            return when {
                letters.length > 1 && letters.all { it.isUpperCase() } -> spoken.uppercase()
                original.first().isUpperCase() -> {
                    val first = spoken.indexOfFirst { it.isLetter() }
                    if (first < 0) spoken else spoken.substring(0, first) + spoken[first].uppercaseChar() + spoken.substring(first + 1)
                }
                else -> spoken
            }
        }

        /** Indices of the vowels in a word, for picking the stress. */
        fun vowelIndices(word: String): List<Int> = word.indices.filter { word[it] in VOWELS }

        /** Puts the stress before the vowel at [vowelIndex] of [word]. */
        fun stressed(word: String, vowelIndex: Int): String =
            word.lowercase().let { it.substring(0, vowelIndex) + "+" + it.substring(vowelIndex) }
    }
}

/** Pronunciations for all books and for one book; a book's own entry wins. */
object PronunciationStore {
    private const val PREFS = "pronunciations"
    private const val GLOBAL = "global"

    private fun key(bookId: String?) = if (bookId == null) GLOBAL else "book:$bookId"

    fun entries(context: Context, bookId: String?): Map<String, String> {
        val raw = prefs(context).getString(key(bookId), null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun load(context: Context, bookId: String?): PronunciationDictionary {
        val global = entries(context, null)
        val own = if (bookId != null) entries(context, bookId) else emptyMap()
        val appContext = context.applicationContext
        return PronunciationDictionary(global + own) { word -> RussianStressLexicon.find(appContext, word) }
    }

    /** The saved entry for [word] and whether it belongs to the book (false: every book). */
    fun find(context: Context, bookId: String?, word: String): Pair<String, Boolean>? {
        val key = PronunciationDictionary.normalizeWord(word)
        if (bookId != null) entries(context, bookId)[key]?.let { return it to true }
        return entries(context, null)[key]?.let { it to false }
    }

    /** Saves [spoken] for [word] in this book or, with [everyBook], in all books; one place per word. */
    fun put(context: Context, bookId: String?, word: String, spoken: String, everyBook: Boolean) {
        remove(context, bookId, word)
        val key = PronunciationDictionary.normalizeWord(word)
        update(context, if (everyBook || bookId == null) null else bookId) { it[key] = spoken.trim() }
    }

    /** Forgets [word] in the book and in the shared list. */
    fun remove(context: Context, bookId: String?, word: String) {
        val key = PronunciationDictionary.normalizeWord(word)
        update(context, null) { it.remove(key) }
        if (bookId != null) update(context, bookId) { it.remove(key) }
    }

    /** Drops a deleted book's own pronunciations. */
    fun clearBook(context: Context, bookId: String) {
        prefs(context).edit().remove(key(bookId)).apply()
    }

    private fun update(context: Context, bookId: String?, change: (MutableMap<String, String>) -> Unit) {
        val current = entries(context, bookId).toMutableMap()
        change(current)
        val editor = prefs(context).edit()
        if (current.isEmpty()) editor.remove(key(bookId)) else editor.putString(key(bookId), JSONObject(current as Map<*, *>).toString())
        editor.apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

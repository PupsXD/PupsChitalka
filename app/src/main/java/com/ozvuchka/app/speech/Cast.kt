package com.ozvuchka.app.speech

import android.content.Context
import com.ozvuchka.app.data.Book
import org.json.JSONObject

/** A character of a book as the reader sees them in the list: name, gender and how often they appear. */
data class CastMember(
    /** As the book writes it: «Цзян Яньли». */
    val name: String,
    val gender: SpeechRole?,
    val mentions: Int,
    /** The reader set this gender by hand. */
    val chosen: Boolean = false,
)

/**
 * The characters of a book and their genders, learned from the whole text: «сказала Цзян Яньли»,
 * «Лань Ванцзи кивнул», «Вэнь Цин выходит во двор. Она…», “Mary put down her cup”. It tells who
 * speaks when the author's words give only a name — «говорит Вэнь Цин», “said Grace” — and which
 * capitalised words are names at all («Лань» in a Chinese novel, not the doe).
 */
class Cast internal constructor(
    private val learned: Map<String, SpeechRole>,
    /** Words the book writes with a capital in the middle of a sentence: parts of names. */
    private val nameWords: Set<String>,
    /** Capitalised words the book mostly writes in lowercase: «Тут», «Тишина». */
    private val commonWords: Set<String>,
    /** Names by how often they appear, spelled as in the book. */
    private val mentions: Map<String, Pair<String, Int>>,
    private val chosen: Map<String, SpeechRole> = emptyMap(),
) {
    /** The gender of a name, spelled in any case the book uses: «Вэнь Цин», «голос Маши», “Mary’s”. */
    fun genderOf(name: String): SpeechRole? {
        val key = normalize(name)
        if (key.isEmpty()) return null
        lookup(key)?.let { return it }
        val parts = key.split(' ')
        // «госпожа Юй»: the name without the title; Chinese names put the family name first, so the
        // given name at the end tells more.
        for (part in parts.reversed()) lookup(part)?.let { return it }
        val variants = nominatives(key)
        for (variant in variants) lookup(variant)?.let { return it }
        // What the book does not say, the name may: «Марина Сергеевна», «Галя», «Линь-сюн».
        for (part in parts.reversed()) nameGender(part)?.let { return it }
        for (variant in variants) nameGender(variant.substringAfterLast(' '))?.let { return it }
        return null
    }

    private fun lookup(key: String): SpeechRole? = chosen[key] ?: learned[key]

    /** The genders the reader set, by name; lets narration notice a change. */
    internal val choices: Map<String, SpeechRole> get() = chosen

    internal fun knowsName(key: String): Boolean = key in nameWords

    internal fun isCommon(key: String): Boolean = key in commonWords

    /** The book's characters, most mentioned first; «Маши» and «Машу» count for «Маша». */
    fun members(minMentions: Int = 3): List<CastMember> {
        val counts = HashMap<String, Int>()
        for ((key, value) in mentions) {
            val base = nominatives(key).firstOrNull { variant -> (mentions[variant]?.second ?: 0) > value.second } ?: key
            counts.merge(base, value.second, Int::plus)
        }
        return counts.entries
            .filter { (key, count) -> count >= minMentions || key in chosen }
            .sortedByDescending { it.value }
            .map { (key, count) -> CastMember(mentions[key]?.first ?: key, lookup(key), count, chosen = key in chosen) }
    }

    /** The same cast with genders the reader set by hand; they win over what the text suggests. */
    fun withChoices(choices: Map<String, SpeechRole>): Cast =
        if (choices.isEmpty() && chosen.isEmpty()) this
        else Cast(learned, nameWords, commonWords, mentions, choices.mapKeys { normalize(it.key) })

    companion object {
        val EMPTY = Cast(emptyMap(), emptySet(), emptySet(), emptyMap())

        internal fun normalize(name: String): String =
            name.trim().lowercase().replace('ё', 'е').replace(STRESS_MARK.toString(), "")
                .split(Regex("\\s+")).joinToString(" ") { it.removeSuffix("’s").removeSuffix("'s") }

        /**
         * «Маши» → «маша», «Ивана» → «иван», «Лань Чжаня» → «лань чжань», and the short forms of
         * address «Кость» → «костя», «Валь» → «валя»: nominatives to look up.
         */
        private fun nominatives(key: String): List<String> {
            val head = key.substringBeforeLast(' ', "")
            val last = key.substringAfterLast(' ')
            if (last.length < 3) return emptyList()
            val stems = mutableListOf<String>()
            for (ending in listOf("ами", "ями", "ой", "ей", "ом", "ем", "ою", "а", "я", "у", "ю", "е", "ы", "и")) {
                if (last.endsWith(ending) && last.length - ending.length >= 2) {
                    val stem = last.dropLast(ending.length)
                    stems += listOf(stem, stem + "а", stem + "я", stem + "ь", stem + "й")
                }
            }
            if (last.last() !in "аеёиоуыэюя") {
                val stem = last.removeSuffix("ь")
                stems += listOf(stem + "а", stem + "я")
            }
            return stems.distinct().filter { it != last }.map { if (head.isEmpty()) it else "$head $it" }
        }

        /** Reads the whole book twice: once to see how it writes each word, once to learn the names. */
        fun learn(paragraphs: List<String>): Cast {
            // Pass 1: how the book writes each word — in lowercase, with a capital mid-sentence, or
            // with a capital only where a sentence starts.
            val lower = HashMap<String, Int>()
            val capital = HashMap<String, Int>()
            val opening = HashSet<String>()
            for (paragraph in paragraphs) {
                for (word in words(paragraph)) {
                    when {
                        !word.capital -> lower.merge(word.key, 1, Int::plus)
                        !word.opensSentence -> capital.merge(word.key, 1, Int::plus)
                        else -> opening += word.key
                    }
                }
            }
            val nameWords = capital.filter { (key, count) ->
                key !in COMMON_RU && key !in COMMON_EN && (lower[key] ?: 0) * 2 <= count
            }.keys
            // «Тут», «Тишина»: capitals at a sentence start of words the book otherwise writes in lowercase.
            val commonWords = opening.filterTo(HashSet()) { key ->
                val inLower = lower[key] ?: 0
                key !in nameWords && inLower > 0 && inLower >= (capital[key] ?: 0)
            }
            lower.clear()
            val partial = Cast(emptyMap(), nameWords, commonWords, emptyMap())

            // Pass 2: names next to what tells their gender.
            val votes = HashMap<String, IntArray>()
            val spelled = HashMap<String, Pair<String, Int>>()
            fun vote(name: List<Word>, gender: SpeechRole, weight: Int) {
                val keys = listOf(name.joinToString(" ") { it.key }) + if (name.size > 1) name.map { it.key } else emptyList()
                for (key in keys) {
                    val tally = votes.getOrPut(key) { IntArray(2) }
                    tally[if (gender == SpeechRole.FEMALE) 0 else 1] += weight
                }
            }
            for (paragraph in paragraphs) {
                val english = detectSpeechLanguage(paragraph, "ru") == "en"
                val words = words(paragraph)
                val common = if (english) COMMON_EN else COMMON_RU
                var index = 0
                while (index < words.size) {
                    val name = nameRun(words, index, partial, common)
                    if (name.isEmpty()) {
                        index++
                        continue
                    }
                    val key = name.joinToString(" ") { it.key }
                    val shown = name.joinToString(" ") { it.text.removeSuffix("’s").removeSuffix("'s") }
                    spelled[key] = (spelled[key]?.first ?: shown) to ((spelled[key]?.second ?: 0) + 1)
                    if (name.size <= 3) {
                        val evidence = if (english) englishEvidence(words, index, name.size, partial)
                        else russianEvidence(words, index, name.size, partial)
                        evidence?.let { (gender, weight) -> vote(name, gender, weight) }
                    }
                    index += name.size
                }
            }
            val learned = HashMap<String, SpeechRole>()
            for ((key, tally) in votes) {
                val (female, male) = tally[0] to tally[1]
                when {
                    female > 0 && female >= male * 3 -> learned[key] = SpeechRole.FEMALE
                    male > 0 && male >= female * 3 -> learned[key] = SpeechRole.MALE
                }
            }
            return Cast(learned, nameWords, commonWords, spelled)
        }

        private fun nameRun(words: List<Word>, index: Int, cast: Cast, common: Set<String>): List<Word> {
            val first = words[index]
            if (!first.capital || first.key in common) return emptyList()
            val isNameWord = { word: Word ->
                word.capital && word.key !in common && (cast.knowsName(word.key) ||
                    (!cast.isCommon(word.key) && pastGender(word.key) == null && word.key !in PERSONS &&
                        word.key !in PERSONS_EN && word.key !in VOICE_NOUNS))
            }
            if (!isNameWord(first)) return emptyList()
            var end = index + 1
            while (end < words.size && end - index < 4 && !words[end].afterPunctuation && isNameWord(words[end])) end++
            return words.subList(index, end)
        }

        /** «Маша улыбнулась», «Цзян Яньли тихо сказала», «— …, — кивнул Лань Чжань», «Вэнь Цин выходит. Она…». */
        private fun russianEvidence(words: List<Word>, index: Int, length: Int, cast: Cast): Pair<SpeechRole, Int>? {
            val first = words[index]
            val before = words.getOrNull(index - 1)
            // «надзиратель Очумелов», «купец Пичугин», «госпожа Юй»: the word for the person tells.
            if (before != null && !first.afterPunctuation && before.key !in BEFORE_OTHERS_NAME) {
                PERSONS[before.key]?.let { return it to 2 }
            }
            // The name opens its clause, or follows a title: «госпожа Юй кивнула», not «брат Цзян Яньли».
            val opensClause = before == null || first.afterPunctuation || first.opensSentence ||
                (before.key !in BEFORE_OTHERS_NAME && (before.key in COMMON_RU || before.key in PERSONS))
            if (opensClause) {
                // «Маша улыбнулась», «Цзян Яньли тихо сказала», «Маша его обняла».
                var at = index + length
                while (at < words.size && at < index + length + 3 && !words[at].afterPunctuation && !words[at].capital) {
                    pastGender(words[at].key)?.let { return it to 2 }
                    at++
                }
            }
            // A verb at the start of a clause, then the name: «— Нет, — кивнула Цзян Яньли».
            var verbAt = index - 1
            while (verbAt >= 0 && !words[verbAt + 1].afterPunctuation && words[verbAt].key in PRONOUN_OBJECTS) verbAt--
            if (verbAt >= 0 && !words[verbAt + 1].afterPunctuation && (verbAt == 0 || words[verbAt].afterPunctuation)) {
                pastGender(words[verbAt].key)?.let { return it to 2 }
            }
            // «Вэнь Цин выходит во двор. Она кутается в плащ.» A weak hint, so only for words the book
            // also capitalises mid-sentence: «Погоди, сейчас скажу. Она…» is not about someone called Погоди.
            val known = words.subList(index, index + length).any { cast.knowsName(it.key) }
            if (known && first.opensSentence && (index == 0 || first.afterSentenceEnd)) {
                var at = index + length
                while (at < words.size && !words[at].afterSentenceEnd) {
                    if (words[at].capital && words[at].key !in COMMON_RU) return null
                    at++
                }
                when (words.getOrNull(at)?.key) {
                    "она" -> return SpeechRole.FEMALE to 1
                    "он" -> return SpeechRole.MALE to 1
                }
            }
            return null
        }

        /** “Mrs Hale”, “Mary put down her cup”, “Tom leaned forward. He…”. */
        private fun englishEvidence(words: List<Word>, index: Int, length: Int, cast: Cast): Pair<SpeechRole, Int>? {
            words.getOrNull(index - 1)?.let { before -> TITLES_EN[before.key]?.let { return it to 3 } }
            // The name leads its sentence, or comes right after a line: “…,” John replied.
            val leads = words[index].opensSentence || words[index].gap.any { it in "”\"" }
            if (!leads) return null
            var at = index + length
            var female = 0
            var male = 0
            // Only the name's own clause: in “Barnaby said, and she folded her arms” the arms are someone else's.
            var ownClause = true
            while (at < words.size && !words[at].afterSentenceEnd) {
                val word = words[at]
                if (word.capital && word.key !in COMMON_EN && !cast.isCommon(word.key)) return null
                if (word.gap.contains(';') || word.gap.contains(',') && word.key in CLAUSE_OPENERS_EN) ownClause = false
                if (ownClause) {
                    when (word.key) {
                        "herself" -> female += 2
                        "himself" -> male += 2
                        "his" -> male++
                        "her" -> if (words.getOrNull(at + 1)?.key !in AFTER_HER_OBJECT) female++
                    }
                }
                at++
            }
            // Whoever the next sentence starts with is most often the one just named.
            if (female + male == 0 && !words.subList(index, index + length).any { cast.knowsName(it.key) }) return null
            when (words.getOrNull(at)?.key) {
                "she" -> female++
                "he" -> male++
            }
            return when {
                female > 0 && male == 0 -> SpeechRole.FEMALE to minOf(female, 2)
                male > 0 && female == 0 -> SpeechRole.MALE to minOf(male, 2)
                else -> null
            }
        }

        private val PRONOUN_OBJECTS = setOf("ему", "ей", "им", "его", "ее", "их", "мне", "тебе", "нам", "вам", "себе")

        private val CLAUSE_OPENERS_EN = setOf("and", "but", "while", "as", "so", "then", "because", "when", "who", "which", "she", "he")

        private val AFTER_HER_OBJECT = setOf("to", "and", "that", "the", "a", "an", "in", "on", "at", "with", "for", "from", "as", "if", "again", "back", "up", "down", "off", "out", "away", null)

        private val TITLES_EN = mapOf(
            "mr" to SpeechRole.MALE, "mister" to SpeechRole.MALE, "sir" to SpeechRole.MALE, "lord" to SpeechRole.MALE,
            "king" to SpeechRole.MALE, "prince" to SpeechRole.MALE, "uncle" to SpeechRole.MALE, "master" to SpeechRole.MALE,
            "mrs" to SpeechRole.FEMALE, "ms" to SpeechRole.FEMALE, "miss" to SpeechRole.FEMALE, "lady" to SpeechRole.FEMALE,
            "queen" to SpeechRole.FEMALE, "princess" to SpeechRole.FEMALE, "aunt" to SpeechRole.FEMALE,
            "madam" to SpeechRole.FEMALE, "madame" to SpeechRole.FEMALE, "mistress" to SpeechRole.FEMALE,
        )
    }
}

/**
 * The character a word of the text belongs to: a long press on «Цин» in «…сказала Вэнь Цин» finds
 * Вэнь Цин, «Маши» finds Маша. Among several Ланей the one named in [sentence] wins.
 */
fun List<CastMember>.memberFor(word: String, sentence: String): CastMember? {
    val key = Cast.normalize(word)
    if (key.length < 2 || ' ' in key) return null
    fun matches(part: String): Boolean {
        if (part == key) return true
        // Case endings: «Маша» — «Маши», «Иван» — «Ивана».
        if (part.length < 3 || key.length < 3 || kotlin.math.abs(part.length - key.length) > 2) return false
        val shared = part.commonPrefixWith(key).length
        return shared >= 3 && shared >= minOf(part.length, key.length) - 2
    }
    val candidates = filter { member -> Cast.normalize(member.name).split(' ').any(::matches) }
    val text = Cast.normalize(sentence)
    return candidates.firstOrNull { Cast.normalize(it.name) in text } ?: candidates.singleOrNull()
}

/**
 * Casts of the books read lately. Learning reads the whole book, so the reader starts it in the
 * background when a book opens and narration usually finds it ready.
 */
object BookCasts {
    private class Learned(val chapters: Int, val cast: Cast)

    private val learned = object : LinkedHashMap<String, Learned>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Learned>?) = size > 3
    }

    /** One book is read at a time, so the reader and narration never learn the same book twice. */
    private val learning = Any()

    /** The book's characters with the reader's choices; learns them now if nobody has yet. */
    fun forBook(context: Context, book: Book): Cast = learnedFor(book).withChoices(CastStore.choices(context, book.id))

    /** Learns the book's characters unless they are known already. Call off the main thread. */
    fun prepare(book: Book) {
        learnedFor(book)
    }

    fun forget(bookId: String) {
        synchronized(learned) { learned.remove(bookId) }
    }

    private fun learnedFor(book: Book): Cast {
        known(book)?.let { return it }
        synchronized(learning) {
            known(book)?.let { return it }
            val cast = Cast.learn(book.chapters.flatMap { it.paragraphs })
            synchronized(learned) { learned[book.id] = Learned(book.chapters.size, cast) }
            return cast
        }
    }

    private fun known(book: Book): Cast? = synchronized(learned) {
        learned[book.id]?.takeIf { it.chapters == book.chapters.size }?.cast
    }
}

/** Genders the reader set for a book's characters. */
object CastStore {
    private const val PREFS = "characters"

    fun choices(context: Context, bookId: String): Map<String, SpeechRole> {
        val raw = prefs(context).getString(bookId, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { name ->
                when (json.getString(name)) {
                    "f" -> name to SpeechRole.FEMALE
                    "m" -> name to SpeechRole.MALE
                    else -> null
                }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Sets the gender of [name] in the book; null goes back to what the text suggests. */
    fun choose(context: Context, bookId: String, name: String, gender: SpeechRole?) {
        val current = choices(context, bookId).toMutableMap()
        val key = Cast.normalize(name)
        when (gender) {
            SpeechRole.FEMALE, SpeechRole.MALE -> current[key] = gender
            else -> current.remove(key)
        }
        val editor = prefs(context).edit()
        if (current.isEmpty()) {
            editor.remove(bookId)
        } else {
            val json = JSONObject()
            current.forEach { (key, value) -> json.put(key, if (value == SpeechRole.FEMALE) "f" else "m") }
            editor.putString(bookId, json.toString())
        }
        editor.apply()
    }

    fun clearBook(context: Context, bookId: String) {
        prefs(context).edit().remove(bookId).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

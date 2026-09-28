package com.ozvuchka.app.speech

/** A stretch of a paragraph read by one voice: a character's line or the author's words. */
internal data class VoicePart(val start: Int, val end: Int, val speech: Boolean)

private val dialogueDashes = setOf('—', '–', '―')
private const val SPEECH_END = ",.!?…:;"

/**
 * Splits a paragraph into characters' lines and author's words, or returns null for plain
 * narration. Russian dialogue opens with a dash and switches between speech and author at a
 * spaced dash that follows punctuation («— Конечно, — ответил он. — Пойдём.»); translated novels
 * often put the author's words first («Сюэ Ян рассмеялся. — Даочжан!»). Quoted speech is
 * recognised in «ёлочках» and “quotes” when punctuation or a colon marks it as speech rather than
 * a title («Война и мир»); a story told over several paragraphs opens each with a quote and closes
 * only the last.
 */
internal fun dialogueParts(paragraph: String, language: String): List<VoicePart>? {
    val parts = dashDialogue(paragraph)
        ?: quotedDialogue(paragraph, '«', '»')
        ?: quotedDialogue(paragraph, '“', '”')
        ?: if (language == "en") straightQuotedDialogue(paragraph) else null
    val trimmed = parts?.mapNotNull { trimPart(paragraph, it) }
    return trimmed?.takeIf { list -> list.any { it.speech } }
}

private fun dashDialogue(text: String): List<VoicePart>? {
    var index = 0
    while (index < text.length && text[index].isWhitespace()) index++
    if (index >= text.length) return null
    val opener = text[index]
    val opensWithDash = opener in dialogueDashes || (opener == '-' && text.getOrNull(index + 1) == ' ')
    if (opensWithDash) return alternate(text, index + 1, text.length, mutableListOf())
    val dash = dashAfterNarration(text) ?: return null
    return alternate(text, dash + 1, text.length, mutableListOf(VoicePart(0, dash, speech = false)))
}

/**
 * Speech from [from] to [until], then the author's words and speech in turn at each dash that
 * follows punctuation.
 */
private fun alternate(text: String, from: Int, until: Int, parts: MutableList<VoicePart>): List<VoicePart> {
    var speech = true
    var partStart = from
    for (position in from until until) {
        val c = text[position]
        if (c !in dialogueDashes && c != '-') continue
        val spaced = text.getOrNull(position - 1)?.isWhitespace() == true && text.getOrNull(position + 1)?.isWhitespace() == true
        if (!spaced) continue
        var before = position - 1
        while (before >= 0 && text[before].isWhitespace()) before--
        // «Москва — столица» keeps going: only a dash after punctuation turns speech into author's words.
        if (before < 0 || text[before] !in SPEECH_END) continue
        parts += VoicePart(partStart, position, speech)
        speech = !speech
        partStart = position + 1
    }
    parts += VoicePart(partStart, until, speech)
    return parts
}

/**
 * «А-Цин фыркнула: — Оба хороши.»: a spaced dash after a full stop or a colon, followed by a
 * capital, opens a line after the author's words. A question or an exclamation before the dash is
 * the author's own figure («Кто виноват? — Никто.»), and a paragraph that opens with a quote is
 * quoted speech («Пойдём». — Он встал.).
 */
private fun dashAfterNarration(text: String): Int? {
    if (text.trimStart().firstOrNull()?.let { it in "«“„\"" } == true) return null
    for (position in text.indices) {
        if (text[position] !in dialogueDashes) continue
        if (text.getOrNull(position - 1)?.isWhitespace() != true || text.getOrNull(position + 1)?.isWhitespace() != true) continue
        var before = position - 1
        while (before >= 0 && text[before].isWhitespace()) before--
        if (before < 0 || text[before] !in ".:") continue
        var after = position + 1
        while (after < text.length && text[after].isWhitespace()) after++
        val first = text.getOrNull(after) ?: continue
        if (first.isUpperCase() || first in "«“\"") return position
    }
    return null
}

private fun quotedDialogue(text: String, open: Char, close: Char): List<VoicePart>? {
    val parts = mutableListOf<VoicePart>()
    var cursor = 0
    var search = 0
    while (true) {
        val opening = text.indexOf(open, search)
        if (opening < 0) break
        val closing = text.indexOf(close, opening + 1)
        if (closing < 0) {
            // «Было это давно…» with no closing quote: the story goes on in the next paragraph.
            if (opening == text.indexOfFirst { !it.isWhitespace() }) {
                alternate(text, opening + 1, text.length, parts)
                cursor = text.length
            }
            break
        }
        search = closing + 1
        if (!looksLikeSpeech(text, opening, closing)) continue
        if (opening > cursor) parts += VoicePart(cursor, opening, speech = false)
        // «Если она здесь без мужа, — соображал Гуров, — то…»: the author's words inside the quotes.
        alternate(text, opening + 1, closing, parts)
        cursor = closing + 1
    }
    if (parts.isEmpty()) return null
    if (cursor < text.length) parts += VoicePart(cursor, text.length, speech = false)
    return parts
}

/** English books often use straight quotes; a pair opens at a word start and closes after punctuation. */
private fun straightQuotedDialogue(text: String): List<VoicePart>? {
    val marks = text.indices.filter { text[it] == '"' }
    if (marks.isEmpty()) return null
    val parts = mutableListOf<VoicePart>()
    var cursor = 0
    var index = 0
    while (index + 1 < marks.size) {
        val opening = marks[index]
        val closing = marks[index + 1]
        index += 2
        if (!looksLikeSpeech(text, opening, closing)) continue
        if (opening > cursor) parts += VoicePart(cursor, opening, speech = false)
        parts += VoicePart(opening + 1, closing, speech = true)
        cursor = closing + 1
    }
    // An odd quote in a paragraph that opens with one: the speech runs on into the next paragraph.
    if (marks.size % 2 == 1 && marks.first() == text.indexOfFirst { !it.isWhitespace() }) {
        val opening = marks.last()
        if (opening > cursor) parts += VoicePart(cursor, opening, speech = false)
        parts += VoicePart(opening + 1, text.length, speech = true)
        cursor = text.length
    }
    if (parts.isEmpty()) return null
    if (cursor < text.length) parts += VoicePart(cursor, text.length, speech = false)
    return parts
}

/**
 * Quoted words are speech when they end with punctuation («Привет!», “Of course,”), follow a colon
 * or open the paragraph, or are followed by punctuation and a dash («Привет», — сказал он).
 */
private fun looksLikeSpeech(text: String, opening: Int, closing: Int): Boolean {
    val content = text.substring(opening + 1, closing).trim()
    if (content.none { it.isLetter() }) return false
    if (content.last() in ".!?…,—–") return true
    var before = opening - 1
    while (before >= 0 && text[before].isWhitespace()) before--
    if (before < 0 || text[before] == ':' || text[before] in dialogueDashes) return true
    val after = text.substring(closing + 1).trimStart()
    return Regex("^[,!?…]+\\s*[—–-]").containsMatchIn(after)
}

/** Drops the dashes, spaces and leftover punctuation around a part; null when nothing speakable is left. */
private fun trimPart(text: String, part: VoicePart): VoicePart? {
    var start = part.start
    var end = part.end
    while (start < end && (text[start].isWhitespace() || text[start] in dialogueDashes || text[start] in "-,.;:!?…")) start++
    while (end > start && (text[end - 1].isWhitespace() || text[end - 1] in dialogueDashes || text[end - 1] == '-')) end--
    if (end <= start || text.substring(start, end).none { it.isLetterOrDigit() }) return null
    return VoicePart(start, end, part.speech)
}

/**
 * The author's words go on the sentence of the line before them: «— Нет, — Лань Чжань отвернулся»,
 * «— Стой! — крикнула она». «— Нет. — Повисла тишина» is a new sentence.
 */
internal fun attributes(paragraph: String, line: VoicePart, author: VoicePart): Boolean {
    val joint = paragraph.substring((line.end - 1).coerceAtLeast(0), author.start)
    return paragraph[author.start].isLowerCase() || ',' in joint
}

private fun opensWithQuote(paragraph: String): Boolean = paragraph.trimStart().firstOrNull()?.let { it in "«“\"" } == true

/**
 * The paragraph opens a quote and leaves it open: its speaker goes on in the next paragraph. Only
 * the opening kind counts: „inner quotes“ close with the “ that opens English ones.
 */
private fun leavesQuoteOpen(paragraph: String): Boolean = when (paragraph.trimStart().firstOrNull()) {
    '«' -> paragraph.lastIndexOf('«') > paragraph.lastIndexOf('»')
    '“' -> paragraph.lastIndexOf('“') > paragraph.lastIndexOf('”')
    '"' -> paragraph.count { it == '"' } % 2 == 1
    else -> false
}

/**
 * Follows a conversation through a chapter and decides who says each line. The author's words next
 * to a line decide first («сказала она», «Лань Си Чэнь покачал головой», «её голос дрогнул»), then
 * the speaker's own words («я пришла», «я рада»), then the narration just before the line
 * («Цзян Яньли поставила на стол миску»). Without any of these, people take turns: a question goes
 * to whom it asked («Сестрица, ты идёшь?» — «Конечно»), a long story goes on with its teller, and
 * a line is never given to the one it calls by name. When the text truly leaves it open — three
 * people of both sexes talking at once — the line stays with the narrator rather than risk the
 * wrong voice.
 */
internal class SpeakerTracker(private val cast: Cast = Cast.EMPTY) {
    /** Someone in the conversation; the name is known when the author gave it. */
    private class Speaker(var gender: SpeechRole?, var name: String?)

    private enum class Source { ATTRIBUTION, SELF, BEAT, STORY }

    /** Who said each line of the conversation, the latest last; null for a line nobody could place. */
    private val turns = ArrayList<Speaker?>()

    /** The gender of whom the last line spoke to: they answer next. */
    private var addressed: SpeechRole? = null
    private var lastLength = 0
    private var lastAsked = false
    private var quoteOpen = false

    /** Who acted in the narration just before a line. */
    private var beat: Clue? = null

    /** Who, in that narration, kept silent: «Она ничего не ответила». */
    private var silent: SpeechRole? = null

    /** Who, in that narration, did not react: «Артём не двинулся с места» — not the answer the last line asked for. */
    private var still: SpeechRole? = null

    /** The people that narration named, by gender: «она» in the next line's author's words is likely them. */
    private val mentioned = HashMap<SpeechRole, String>()

    /**
     * Someone the narration brought in whose line has not come yet: «у окна сидел мужчина» — «Ещё кофе?
     * — спросила Люба» — the next line is likely his.
     */
    private var introduced: Clue? = null

    fun rolesFor(paragraph: String, parts: List<VoicePart>, language: String): List<SpeechRole> {
        val speech = parts.filter { it.speech }.joinToString(" ") { paragraph.substring(it.start, it.end) }
        val authorWords = parts.filterNot { it.speech }.joinToString(" ") { paragraph.substring(it.start, it.end) }
        val calls = vocatives(speech, language, cast)
        val speaker = placed(paragraph, parts, speech, language, calls) ?: guessed(speech, language, calls)
        turns += speaker
        if (turns.size > MAX_TURNS) turns.removeAt(0)
        // Whom the line spoke to answers next: «Сестрица, ты идёшь?», «Громов повернулся к Коваленко».
        addressed = addresseeGender(speech, language, cast, calls) ?: turnedTo(authorWords, language, cast)
        lastLength = speech.length
        lastAsked = speech.trimEnd().trimEnd('.', '!').endsWith('?')
        quoteOpen = leavesQuoteOpen(paragraph)
        // The one the narration brought in still has not spoken: they may answer next.
        introduced = beat?.takeIf { clue -> clue.gender != null && speaker?.gender != null && clue.gender != speaker.gender }
        beat = null
        silent = null
        still = null
        mentioned.clear()
        val role = speaker?.gender ?: SpeechRole.SPEECH
        return parts.map { part -> if (part.speech) role else SpeechRole.NARRATOR }
    }

    /** Narration between lines: who acts in it may speak next; a long stretch ends the conversation. */
    fun narration(paragraph: String, language: String) {
        // «* * *» or a heading: a new scene.
        if (paragraph.length > 200 || paragraph.none { it.isLetter() }) sceneBreak()
        val clue = beatClue(paragraph, language, cast)
        beat = clue?.takeIf { it.tells && !it.silent && !it.still }
        silent = clue?.takeIf { it.silent }?.gender
        still = clue?.takeIf { it.still }?.gender
        quoteOpen = false
        introduced = null
        // «Отец посмотрел на маму. Мама смотрела в тарелку.» — «спросила она» is the mother.
        mentioned.clear()
        for (sentence in sentencesOf(paragraph).takeLast(3)) {
            val subject = subjectClue(sentence, language, cast, attribution = false) ?: continue
            if (subject.gender != null && subject.name != null) mentioned[subject.gender] = subject.name
        }
    }

    /** A heading or a scene break: whoever talked before is not talking now. */
    fun sceneBreak() {
        turns.clear()
        addressed = null
        lastLength = 0
        lastAsked = false
        beat = null
        silent = null
        still = null
        quoteOpen = false
        introduced = null
        mentioned.clear()
    }

    /** The speaker when the text tells who it is, strongest evidence first. */
    private fun placed(paragraph: String, parts: List<VoicePart>, speech: String, language: String, calls: List<Vocative>): Speaker? {
        val clues = ArrayList<Pair<Clue, Source>>()
        // The author's words right after the first line: «— Нет, — сказала она», “said Grace”.
        var sentenceAfter: Clue? = null
        val after = parts.indices.firstOrNull { it > 0 && !parts[it].speech && parts[it - 1].speech }
        if (after != null) {
            val words = paragraph.substring(parts[after].start, parts[after].end)
            val attribution = attributes(paragraph, parts[after - 1], parts[after])
            val clue = subjectClue(words, language, cast, attribution)
            if (attribution) clue?.let { clues += it to Source.ATTRIBUTION } else sentenceAfter = clue
        }
        selfGender(speech, language)?.let { clues += Clue(it) to Source.SELF }
        sentenceAfter?.let { clues += it to Source.BEAT }
        // The author's words before the line: «А-Цин фыркнула: — Оба хороши.», “She shrugged. “The river…””
        val before = parts.indices.firstOrNull { !parts[it].speech && parts.getOrNull(it + 1)?.speech == true }
        if (before != null) {
            beatClue(paragraph.substring(parts[before].start, parts[before].end), language, cast)
                ?.takeIf { !it.silent }?.let { clues += it to Source.BEAT }
        }
        // A story in quotes goes on from the last paragraph.
        val teller = turns.lastOrNull()
        if (quoteOpen && opensWithQuote(paragraph) && teller != null) clues += Clue(teller.gender, teller.name) to Source.STORY
        // The narration before the line, unless the line calls someone of that sex who may be the one
        // who acted: then they are listening («Цзян Яньли покачала головой. — Шицзе!»). Two different
        // names are two people: «Выглянула Света. — Галь Петровна, отпустите его».
        beat?.takeIf { clue ->
            calls.none { call ->
                val otherPerson = call.proper && clue.name != null && clue.name !in PERSONS && call.name != clue.name
                call.gender != null && call.gender == clue.gender && !otherPerson
            }
        }?.let { clues += it to Source.BEAT }

        // «Лань Ванцзи стоит внизу.» — «Лань Чжань, иди сюда, — зовёт он»: the one named is being called, not calling.
        if (calls.any { call -> call.gender != null && mentioned[call.gender] != null }) {
            calls.forEach { call -> call.gender?.let(mentioned::remove) }
        }
        var (clue, source) = clues.firstOrNull { it.first.tells } ?: return null
        val self = clues.firstOrNull { it.second == Source.SELF }?.first
        if (clue.gender == null) {
            // A name the book does not explain: the speaker's own words may still tell.
            self?.let { clue = clue.copy(gender = it.gender) }
        } else if (source == Source.ATTRIBUTION && clue.weak && self != null && self.gender != clue.gender) {
            // «— Я и оглянуться не успел, — пожаловалась жертва»: his own words over the grammar of «жертва».
            clue = self
            source = Source.SELF
        }
        return speakerFor(clue, source)
    }

    private fun speakerFor(found: Clue, source: Source): Speaker {
        // «кричит кто-то из толпы»: someone new, not anyone in the conversation.
        if (found.anonymous) return Speaker(found.gender, null)
        // «Мама смотрела в тарелку.» — «…, — спросила она»: the one the narration just named.
        val clue = if (found.name == null && found.gender != null && (source == Source.ATTRIBUTION || source == Source.SELF)) {
            found.copy(name = mentioned[found.gender])
        } else {
            found
        }
        val previous = turns.lastOrNull()
        clue.name?.let { name ->
            turns.lastOrNull { it?.name == name }?.let { known ->
                if (known.gender == null) known.gender = clue.gender
                return known
            }
        }
        val gender = clue.gender
        val sameAsBefore = previous?.takeIf { gender != null && it.gender == gender && (clue.name == null || it.name == null) }
        val other = gender?.let { recent(it, except = previous) }?.takeIf { clue.name == null || it.name == null }
        val speaker = when {
            gender == null -> null
            // «я пришла», «продолжила она», an open quote: the same person goes on.
            source == Source.SELF || source == Source.STORY || clue.continues -> sameAsBefore ?: other
            // «огрызнулся тот»: someone else answers.
            clue.reply -> other
            // A new name is a new person rather than the one who just spoke.
            else -> other ?: sameAsBefore?.takeIf { clue.name == null }
        } ?: Speaker(gender, clue.name)
        if (speaker.name == null) speaker.name = clue.name
        return speaker
    }

    /** No words tell who speaks: follow the conversation. */
    private fun guessed(speech: String, language: String, calls: List<Vocative>): Speaker? {
        val previous = turns.lastOrNull()
        val candidates = ArrayList<Speaker>()
        // She kept silent, so he goes on: «Она ничего не ответила. — Ты знала и молчала!»
        silent?.let { quiet -> previous?.takeIf { it.gender != null && it.gender != quiet }?.let(candidates::add) }
        // A story goes on: «И ещё…», «Во-вторых…» after a line that asked nothing, or a long line after a long one.
        val asks = speech.trimEnd().trimEnd('.', '!').endsWith('?')
        val story = !lastAsked && (continuesStory(speech, language) && !asks || lastLength >= LONG_LINE && speech.length >= LONG_LINE)
        if (previous != null && story) candidates += previous
        // «Сестрица, ты тоже идёшь?» is answered by her — unless the narration says she did not react.
        addressed?.takeIf { it != still }?.let { gender -> candidates += recent(gender, except = previous) ?: Speaker(gender, null) }
        // The one the narration brought in, who has not spoken yet.
        introduced?.let { candidates += speakerFor(it, Source.BEAT) }
        // Otherwise the one who spoke before the last speaker.
        alternation(previous)?.let(candidates::add)
        // The one the line calls by name is not the one speaking: «Вэнь Цин! Ты тоже пришла?»
        val called = calls.mapNotNull { it.name }.toSet()
        return candidates.firstOrNull { it.name == null || it.name !in called }
    }

    /**
     * The one who spoke before the last speaker. With three or more people of both sexes talking in
     * turn, the text does not say who is next, and a guess would often pick the wrong voice.
     */
    private fun alternation(previous: Speaker?): Speaker? {
        // After a line nobody could place, taking turns is a guess upon a guess.
        if (previous == null) return null
        val before = turns.lastOrNull { it != null && it !== previous } ?: return null
        val recentTurns = turns.takeLast(RECENT_TURNS).filterNotNull().filter { it !== previous }
        val active = recentTurns.distinct().filter { speaker -> speaker === before || recentTurns.count { it === speaker } >= 2 }
        return if (active.mapNotNull { it.gender }.toSet().size > 1) null else before
    }

    /** The latest speaker of [gender] other than [except]. */
    private fun recent(gender: SpeechRole, except: Speaker?): Speaker? =
        turns.lastOrNull { it != null && it !== except && it.gender == gender }

    private companion object {
        const val MAX_TURNS = 24
        /** Lines this long tell a story rather than answer. */
        const val LONG_LINE = 150
        /** How far back the people taking part in a conversation are counted. */
        const val RECENT_TURNS = 6
    }
}

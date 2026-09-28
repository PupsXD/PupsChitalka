package com.ozvuchka.app.speech

/** A stretch of a paragraph read by one voice: a character's line or the author's words. */
internal data class VoicePart(val start: Int, val end: Int, val speech: Boolean)

private val dialogueDashes = setOf('—', '–', '―')
private const val SPEECH_END = ",.!?…:;"

/**
 * Splits a paragraph into characters' lines and author's words, or returns null for plain
 * narration. Russian dialogue opens with a dash and switches between speech and author at a
 * spaced dash that follows punctuation («— Конечно, — ответил он. — Пойдём.»); quoted speech is
 * recognised in «ёлочках» and “quotes” when punctuation or a colon marks it as speech rather than
 * a title («Война и мир»).
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
    if (!opensWithDash) return null
    val parts = mutableListOf<VoicePart>()
    var speech = true
    var partStart = index + 1
    for (position in partStart until text.length) {
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
    parts += VoicePart(partStart, text.length, speech)
    return parts
}

private fun quotedDialogue(text: String, open: Char, close: Char): List<VoicePart>? {
    val parts = mutableListOf<VoicePart>()
    var cursor = 0
    var search = 0
    while (true) {
        val opening = text.indexOf(open, search)
        if (opening < 0) break
        val closing = text.indexOf(close, opening + 1)
        if (closing < 0) break
        search = closing + 1
        if (!looksLikeSpeech(text, opening, closing)) continue
        if (opening > cursor) parts += VoicePart(cursor, opening, speech = false)
        parts += VoicePart(opening + 1, closing, speech = true)
        cursor = closing + 1
    }
    if (parts.isEmpty()) return null
    if (cursor < text.length) parts += VoicePart(cursor, text.length, speech = false)
    return parts
}

/** English books often use straight quotes; a pair opens at a word start and closes after punctuation. */
private fun straightQuotedDialogue(text: String): List<VoicePart>? {
    val marks = text.indices.filter { text[it] == '"' }
    if (marks.size < 2) return null
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
 * The gender of a line's speaker from the author's words next to it: «сказала она», «ответил он»,
 * «кивнула Маша», “she whispered”. Null when the words do not tell.
 */
internal fun speakerGender(authorWords: String, language: String): SpeechRole? {
    val words = Regex("\\p{L}+").findAll(authorWords.lowercase()).map { it.value }.take(4).toList()
    if (language == "en") {
        for (word in words.take(3)) {
            when (word) {
                "she" -> return SpeechRole.FEMALE
                "he" -> return SpeechRole.MALE
                in englishFemale -> return SpeechRole.FEMALE
                in englishMale -> return SpeechRole.MALE
            }
        }
        return null
    }
    for (word in words) {
        when (word) {
            "она" -> return SpeechRole.FEMALE
            "он" -> return SpeechRole.MALE
        }
    }
    // Past-tense verbs carry gender: «сказала», «улыбнулась» against «сказал», «улыбнулся».
    for (word in words.take(3)) {
        if (word.length >= 4 && (word.endsWith("ла") || word.endsWith("лась"))) return SpeechRole.FEMALE
        if (word.length >= 3 && (word.endsWith("л") || word.endsWith("лся"))) return SpeechRole.MALE
    }
    return null
}

private val englishMale = setOf(
    "father", "dad", "man", "boy", "king", "prince", "sir", "lord", "brother", "son", "husband", "uncle",
    "grandfather", "grandpa", "gentleman", "mister", "mr",
)
private val englishFemale = setOf(
    "mother", "mom", "mum", "woman", "girl", "queen", "princess", "lady", "sister", "daughter", "wife", "aunt",
    "grandmother", "grandma", "madam", "miss", "mrs", "ms",
)

/**
 * Follows a conversation through a chapter. A line without author's words is usually the reply of
 * the person who spoke two lines earlier, so it takes that speaker's gender; otherwise it stays a
 * line of unknown gender.
 */
internal class SpeakerTracker {
    private val recent = ArrayDeque<SpeechRole?>()

    fun rolesFor(paragraph: String, parts: List<VoicePart>, language: String): List<SpeechRole> {
        val attributed = parts.withIndex()
            .filter { (index, part) -> !part.speech && index > 0 && parts[index - 1].speech }
            .firstNotNullOfOrNull { (_, part) -> speakerGender(paragraph.substring(part.start, part.end), language) }
            ?: parts.withIndex()
                .filter { (index, part) -> !part.speech && parts.getOrNull(index + 1)?.speech == true }
                .firstNotNullOfOrNull { (_, part) -> speakerGender(paragraph.substring(part.start, part.end), language) }
        val speaker = attributed ?: recent.takeIf { it.size >= 2 }?.let { it[it.size - 2] }
        recent.addLast(speaker)
        while (recent.size > 2) recent.removeFirst()
        return parts.map { part -> if (part.speech) speaker ?: SpeechRole.SPEECH else SpeechRole.NARRATOR }
    }

    /** A long stretch of narration ends the conversation. */
    fun narration(paragraph: String) {
        if (paragraph.length > 200) recent.clear()
    }
}

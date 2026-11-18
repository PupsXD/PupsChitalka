package com.ozvuchka.app.speech

/**
 * Stress for the few Russian homographs whose meaning — and so whose reading — is changed by
 * stress: «за́мок» a castle against «замо́к» a lock, «сто́ит» costs against «стои́т» stands,
 * «му́ка» torment against «мука́» flour. A word is marked only when a neighbouring word names
 * the reading; without such a sign the model's own dictionary keeps its answer. The reader's
 * explicit «+» always wins, so a word that already carries one is never touched.
 *
 * This is the layer between the reader's dictionary and the model: [VoskTts] applies it to Vosk
 * text only, and [VoskFrontend] treats the mark like any other «+» — it bypasses the dictionary
 * on purpose, because the dictionary knows one reading of these words.
 */
internal object RussianHomographStress {
    /** Readings and the vowel each one stresses, per written form. */
    private class Reading(val firstVowel: Int, val lastVowel: Int)

    private val readings: Map<String, Reading> = mapOf(
        // за́мок (castle) against замо́к (lock)
        "замок" to Reading(1, 3), "замка" to Reading(1, 3), "замку" to Reading(1, 3),
        "замком" to Reading(1, 4), "замке" to Reading(1, 4),
        // сто́ит / сто́ят (cost, worth) against стои́т / стоя́т (stand)
        "стоит" to Reading(2, 3), "стоят" to Reading(2, 3),
        // му́ка (torment) against мука́ (flour)
        "мука" to Reading(1, 3), "муки" to Reading(1, 3), "муке" to Reading(1, 3),
        "муку" to Reading(1, 3), "мукой" to Reading(1, 4),
    )

    private val castleMarkers = setOf(
        "средневеков", "старинн", "рыцарск", "королевск", "феодальн", "воздушн", "каменн", "древн",
    )
    private val lockMarkers = setOf(
        "двер", "ключ", "висяч", "кодов", "амбарн", "навесн", "врезн", "запереть", "отпереть",
        "открыть", "закрыть", "щелк",
    )

    /** Cost and worth: «сколько стоит», «стоит отметить». */
    private val costMarkers = setOf(
        "сколько", "дорого", "дешево", "дёшево", "ничего", "этого", "того", "многого", "вполне",
    )
    /** The verbs that follow «сто́ит» in its «worth doing» sense. */
    private val worthNextMarkers = setOf(
        "отметить", "сказать", "попробовать", "посмотреть", "подумать", "помнить", "заметить",
        "упомянуть", "внимания", "признать", "ожидать",
    )
    /** The prepositions and adverbs that make «стои́т» a standing person or thing. */
    private val standNextMarkers = setOf(
        "на", "у", "в", "во", "перед", "за", "под", "над", "между", "рядом", "около", "возле",
        "посреди", "среди", "молча", "неподвижно", "впереди", "позади", "прямо", "здесь", "там",
    )

    private val tormentMarkers = setOf(
        "страшн", "адск", "невыносим", "ужасн", "творческ", "любовн", "душевн", "бесконечн",
    )
    private val flourMarkers = setOf(
        "пшеничн", "ржан", "кукурузн", "овсян", "близн", "просеян", "мешок", "пачка", "килограмм",
        "мучн", "тесто", "пирог", "хлеб", "сорт",
    )

    /**
     * [text] with a «+» before the stressed vowel of every homograph whose neighbours name its
     * reading; every other word, including one the reader already marked, stays as it was.
     */
    fun apply(text: String): String {
        if (text.isEmpty()) return text
        val words = WORD.findAll(text).toList()
        if (words.isEmpty()) return text
        val lowered = words.map { it.value.lowercase() }
        val builder = StringBuilder(text)
        var shift = 0
        words.forEachIndexed { index, match ->
            if ('+' in match.value) return@forEachIndexed
            val reading = readings[lowered[index]] ?: return@forEachIndexed
            val previous = lowered.getOrNull(index - 1)
            val next = lowered.getOrNull(index + 1)
            val vowel = stressedVowel(lowered[index], reading, previous, next) ?: return@forEachIndexed
            builder.insert(match.range.first + shift + vowel, '+')
            shift++
        }
        return builder.toString()
    }

    private fun stressedVowel(word: String, reading: Reading, previous: String?, next: String?): Int? =
        when (word) {
            "замок", "замка", "замку", "замком", "замке" -> when {
                matches(previous, castleMarkers) || matches(next, castleMarkers) -> reading.firstVowel
                matches(previous, lockMarkers) || matches(next, lockMarkers) -> reading.lastVowel
                else -> null
            }
            "стоит", "стоят" -> when {
                matches(previous, costMarkers) -> reading.firstVowel
                matches(next, worthNextMarkers) -> reading.firstVowel
                matches(next, standNextMarkers) -> reading.lastVowel
                previous == "не" -> reading.firstVowel
                else -> null
            }
            "мука", "муки", "муке", "муку", "мукой" -> when {
                matches(previous, tormentMarkers) || matches(next, tormentMarkers) -> reading.firstVowel
                matches(previous, flourMarkers) || matches(next, flourMarkers) -> reading.lastVowel
                else -> null
            }
            else -> null
        }

    private fun matches(word: String?, markers: Set<String>): Boolean =
        word != null && markers.any { word.startsWith(it) }

    private val WORD = Regex("\\p{L}+(?:[-'’]\\p{L}+)*")
}

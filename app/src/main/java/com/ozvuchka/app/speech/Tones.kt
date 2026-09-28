package com.ozvuchka.app.speech

import kotlin.math.pow

/** Pace, pitch and loudness of a segment relative to plain reading. */
data class Prosody(val rate: Float = 1f, val pitch: Float = 1f, val gain: Float = 1f) {
    companion object {
        val NEUTRAL = Prosody()
    }
}

/**
 * How a character's line is said. The voice itself raises a question or an exclamation by the mark
 * at the end of the sentence; a tone adds what the author's words and the line's own marks tell:
 * «прошептала», «рявкнул», «неуверенно», «Я… я не знаю», «П-простите», «НЕТ!». It changes the pace,
 * the pitch and the loudness of the line: at full strength by [rate], [pitch] and [gainDb].
 */
enum class SpeechTone(private val rate: Float, private val pitch: Float, private val gainDb: Float) {
    NEUTRAL(1f, 1f, 0f),

    /** «Стой!», «воскликнул», «громко». */
    EXCLAIM(1.05f, 1.04f, 2f),

    /** «рявкнул», «закричала», «зло», «Стоять!!», «НЕТ!». */
    ANGRY(1.1f, 1.07f, 5f),

    /** «процедил сквозь зубы», «прошипела», «холодно», «насмешливо», «проворчал». */
    COLD(0.9f, 0.94f, -1f),

    /** «Я… я не знаю», «П-простите», «Э-э», «неуверенно», «пробормотал». */
    UNSURE(0.87f, 0.98f, -2f),

    /** «тихо», «вполголоса», «понизив голос». */
    QUIET(0.95f, 0.98f, -3f),

    /** «прошептала», «шёпотом». */
    WHISPER(0.92f, 0.96f, -6f),

    /** «всхлипнула», «грустно», «сквозь слёзы», «голос дрогнул». */
    SAD(0.87f, 0.94f, -2.5f),

    /** «рассмеялся», «радостно», «весело». */
    JOY(1.07f, 1.07f, 1.5f),

    /** «испуганно», «в ужасе», «вскрикнула». */
    FEAR(1.1f, 1.08f, -1f),

    /** «ласково», «мягко», «нежно». */
    TENDER(0.92f, 1.03f, -2f),

    /** «удивлённо», «Что?!». */
    SURPRISE(1f, 1.09f, 1.5f),
    ;

    /** This tone at [strength]: 0 is plain reading, 1 the full tone. */
    fun prosody(strength: Float): Prosody {
        if (this == NEUTRAL || strength <= 0f) return Prosody.NEUTRAL
        return Prosody(
            rate = 1f + (rate - 1f) * strength,
            pitch = 1f + (pitch - 1f) * strength,
            gain = 10f.pow(gainDb * strength / 20f),
        )
    }
}

/**
 * The tone of each part of a paragraph cut by [dialogueParts]: for a character's line, what the
 * author's words next to it say about how it is said — «— Уходи, — прошептала она», «Сюэ Ян
 * рассмеялся. — Даочжан!»; null for the author's words and for a line they say nothing about.
 */
internal fun lineTones(paragraph: String, parts: List<VoicePart>, language: String): List<SpeechTone?> =
    parts.indices.map { index -> if (parts[index].speech) lineTone(paragraph, parts, index, language) else null }

private fun lineTone(paragraph: String, parts: List<VoicePart>, index: Int, language: String): SpeechTone? {
    val manner = if (language == "en") ENGLISH_MANNER else RUSSIAN_MANNER
    // The author's words right after the line: «— Уходи, — прошептала она», «— Стой! — Лань Чжань крикнул».
    parts.getOrNull(index + 1)?.takeIf { !it.speech }?.let { author ->
        val words = words(sentencesOf(paragraph.substring(author.start, author.end)).firstOrNull().orEmpty())
        val cue = manner.find(words, firstClause(words))
        // A new sentence after the line is about the line only when its verb tells how the line came out: «— Нет.
        // — Она всхлипнула». «— Уходи. — Она тихо вышла» is about leaving.
        if (cue != null && (attributes(paragraph, parts[index], author) || cue.speaks && cue.position < 5)) return cue.tone
    }
    val author = parts.getOrNull(index - 1)?.takeIf { !it.speech } ?: return null
    val text = paragraph.substring(author.start, author.end)
    // Between two halves of a line only the words about its first half: «— Уходи, — прошептала она, — пока
    // не поздно», «— Тише, — прошептала сестра, косясь на дверь. — Матушка не в духе». The second half goes
    // on the same way — unless the speaker did something else in between: «— Разговорчики! — прикрикнула
    // Карина и повернулась к участковому. — Давайте я вам лёд принесу».
    val previous = parts.getOrNull(index - 2)?.takeIf { it.speech }
    if (previous != null && attributes(paragraph, previous, author) && sentencesOf(text).size == 1) {
        val words = words(text)
        if (words.count { isFiniteVerb(it.key, language) } <= 1) return manner.find(words, firstClause(words))?.tone
    }
    // The words before it: «Сюэ Ян рассмеялся. — Даочжан!», «— Нет, — сказала она. Потом добавила шёпотом: — Уходи».
    // Only their end leads into the line: «Она весело болтала весь вечер, потом устала и замолчала» does not.
    val words = words(lastSentence(text))
    val clause = lastClause(words, language)
    return manner.find(words, maxOf(clause.first, words.size - LEAD_IN_WORDS) until words.size)?.tone
}

/** A verb that has a subject of its own: «сказала», «повернулся», “turned”. */
private fun isFiniteVerb(key: String, language: String): Boolean =
    if (language == "en") key.endsWith("ed") && key.length > 3 || key in ENGLISH_IRREGULAR_PAST else pastGender(key) != null

private val ENGLISH_IRREGULAR_PAST = setOf(
    "said", "told", "spoke", "went", "came", "took", "gave", "made", "got", "put", "saw", "felt", "left", "held", "stood", "sat",
    "ran", "shook", "threw",
)

/** How many last words of the author's words before a line lead into it. */
private const val LEAD_IN_WORDS = 6

/**
 * The tone a sentence of a line shows by itself: shouting («НЕТ!», «Стоять!!»), a startled question
 * («Что?!»), an exclamation, or hesitation — a word broken off («Я… я не знаю», «Хотя... давайте»),
 * a stutter («П-простите») or a filler («Э-э», «Хм»). [goesOn]: more of the line follows, so an
 * ellipsis at the end is a pause in the middle of the line, not a line trailing off.
 */
internal fun sentenceTone(sentence: String, goesOn: Boolean): SpeechTone? {
    val text = sentence.trim()
    if ('!' in text) {
        return when {
            SHOUTING.containsMatchIn(text) || CAPITALS.containsMatchIn(text) -> SpeechTone.ANGRY
            STARTLED.containsMatchIn(text) -> SpeechTone.SURPRISE
            else -> SpeechTone.EXCLAIM
        }
    }
    val hesitates = STUTTER.containsMatchIn(text) || FILLER.containsMatchIn(text) || BROKEN_OFF.containsMatchIn(text) ||
        goesOn && TRAILING_OFF.containsMatchIn(text)
    return if (hesitates) SpeechTone.UNSURE else null
}

private val SHOUTING = Regex("!\\s*!")
private val CAPITALS = Regex("(?<![\\p{L}-])\\p{Lu}{3,}(?![\\p{L}-])")
private val STARTLED = Regex("\\?\\s*!|!\\s*\\?")
/** «П-простите», «Н-нет», «W-what»: a consonant said twice. */
private val STUTTER = Regex("(?iu)(?<![\\p{L}-])([бвгджзйклмнпрстфхцчшщbcdfghjklmnpqrstvwxz]{1,2})-\\1")
private val FILLER = Regex("(?iu)(?<![\\p{L}-])(я-я|i-i|э-э+|э{2,}|эм+|хм+|um+|uh+|erm?)(?![\\p{L}])")
/** «Я… я не знаю»: an ellipsis between words. */
private val BROKEN_OFF = Regex("\\p{L}\\s*(…|\\.{3})\\s*\\p{L}")
private val TRAILING_OFF = Regex("\\p{L}\\s*(…|\\.{2,})[»”\"]?$")

// ---------------------------------------------------------------- the author's words

/** A cue found: its tone, whether it is a verb for how the line came out («прошептала», «всхлипнула»), its word index. */
private class Cue(val tone: SpeechTone, val speaks: Boolean, val position: Int)

/**
 * Words that tell how a line is said. A word ending in «*» stands for every word that starts so:
 * «прошепта*» is «прошептал», «прошептала», «прошептав». [verbs] tell how the line came out («крикнула»,
 * «всхлипнула», “whispered”), [words] describe it («зло», «сквозь зубы», “coldly”); [pairs] are two words within three of each other.
 * [blockers] just before a cue take it away from the line: «не повышая голоса», «без злости», «стало тихо».
 */
private class MannerTable(
    rows: List<Triple<SpeechTone, String, String>>,
    private val pairs: List<Triple<String, String, SpeechTone>>,
    private val blockers: Set<String>,
) {
    private val exact = HashMap<String, Pair<SpeechTone, Boolean>>()
    private val stems = ArrayList<Triple<String, SpeechTone, Boolean>>()

    init {
        for ((tone, verbs, words) in rows) {
            for ((list, speaks) in listOf(verbs to true, words to false)) {
                for (entry in list.split(' ').filter { it.isNotEmpty() }) {
                    if (entry.endsWith('*')) stems += Triple(entry.dropLast(1), tone, speaks) else exact[entry] = tone to speaks
                }
            }
        }
    }

    /** The first cue among [words] in [range] with no blocker in the two words before it: «не очень громко». */
    fun find(words: List<Word>, range: IntRange): Cue? {
        for (index in range) {
            val blocked = (index - 1 downTo maxOf(0, index - 2)).any { before ->
                words[before].key in blockers && (before + 1..index).none { words[it].afterPunctuation }
            }
            val key = words[index].key
            val pair = pairs.firstOrNull { (first, second, _) ->
                matches(key, first) && (index + 1..minOf(index + 3, range.last)).any { matches(words[it].key, second) }
            }
            if (pair != null) {
                if (!blocked) return Cue(pair.third, speaks = false, position = index)
                continue
            }
            val found = exact[key] ?: stems.firstOrNull { key.startsWith(it.first) }?.let { it.second to it.third } ?: continue
            if (!blocked) return Cue(found.first, found.second, index)
        }
        return null
    }

    private fun matches(key: String, pattern: String) =
        if (pattern.endsWith('*')) key.startsWith(pattern.dropLast(1)) else key == pattern
}

/**
 * The words about the line in the author's words after it: up to a clause about something else —
 * «сказал он, и она заплакала», «ответила она, вспоминая, как он кричал». A clause about the voice
 * still counts: «сказала она, и голос её дрогнул».
 */
private fun firstClause(words: List<Word>): IntRange {
    for (index in 1 until words.size) {
        if (!words[index].gap.contains(',') || words[index].key !in CLAUSE_OPENERS) continue
        val aboutVoice = (index + 1..minOf(index + 3, words.lastIndex)).any { words[it].key in VOICE_WORDS }
        if (!aboutVoice) return 0 until index
    }
    return words.indices
}

/**
 * The words about the line in the author's words before it: after the last clause about something
 * else — «Он закричал, и она вздрогнула», «Когда он закричал, она вздрогнула».
 */
private fun lastClause(words: List<Word>, language: String): IntRange {
    var start = skipOpeningClause(words, if (language == "en") SUBORDINATE_EN else SUBORDINATE_RU)
    for (index in start + 1 until words.size) {
        if (words[index].gap.contains(',') && words[index].key in CLAUSE_OPENERS) start = index
    }
    return start until words.size
}

private val CLAUSE_OPENERS = setOf(
    "и", "а", "но", "да", "как", "что", "чтобы", "когда", "пока", "если", "хотя", "будто", "словно", "потому", "где",
    "куда", "откуда", "который", "которая", "которое", "которые", "которого", "которой", "которым", "которую", "чей", "чья",
    "and", "but", "as", "while", "when", "because", "who", "which", "that", "though", "although", "until", "so", "then",
)

private val VOICE_WORDS = setOf(
    "голос", "голоса", "голосе", "голосом", "голосок", "тон", "тоне", "тоном", "интонации", "интонация", "voice", "tone",
)

private val RUSSIAN_MANNER = MannerTable(
    rows = listOf(
        Triple(
            SpeechTone.ANGRY,
            "крикн* выкрикн* закрич* накрич* прокрич* раскрич* прикрикн* покрикив* крича* кричи* заор* проор* наор* " +
                "орал орала орали орет орут оря рявкн* гаркн* рыкн* взрев* рыча* рычит прорыч* зарыч* огрызн* огрыза* " +
                "вопил* вопит вопят вопя завоп* возоп* вспыли* возмути* рассерди*",
            "гневн* гнева гневе гневом яростн* ярости яростью разъярен* взбешен* свиреп* злобн* зло злости злостью злым рассерж* " +
                "сердит* возмущ* негодующ* негодова* раздраж* грозн* громогласн* крик крике криком",
        ),
        Triple(
            SpeechTone.COLD,
            "прошипе* зашипе* шипел шипела шипя шипит процеди* цеди* цедя отчекани* съязви* проворч* ворч* пробурч* бурч* буркну*",
            "холодно холоднее холодным холодный ледян* угрожающ* угрозой угроза угрюм* мрачн* суров* строг* жестк* сухо " +
                "презрительн* презрением надменн* высокомерн* ядовит* язвительн* ехидн* насмешлив* издевательск* издевкой " +
                "саркастич*",
        ),
        Triple(
            SpeechTone.UNSURE,
            "замял* запнул* пробормот* бормот* бормоч* промямли* мямли* пролепета* лепета* лепеч* выдави*",
            "неуверенн* нерешительн* робк* робост* смущенн* смущени* растерянн* запинаясь заикаясь сбивчив* неловко " +
                "виновато стыдлив* застенчив* осторожно помедлив поколебавшись дрожащ* дрожал дрожала дрожа",
        ),
        Triple(
            SpeechTone.QUIET,
            "",
            "тихо тихим тише тихонько вполголоса негромко беззвучно приглушенн* слабо слабым",
        ),
        Triple(
            SpeechTone.WHISPER,
            "прошепта* шепну* зашепта* шепта* шепча шепчет шепчут",
            "шепотом шепот полушепотом",
        ),
        Triple(
            SpeechTone.SAD,
            "всхлипн* всхлипыва* простон* стон* заплака* плакал плакала плакали плачет плачут зарыда* рыда* шмыгн*",
            "грустн* грустью печальн* печалью тоской тоскл* горько уныл* устало усталым обреченн* всхлип слезами слезах " +
                "плача плачущ* дрогну* упавшим глухо горестн* скорбн* жалобн*",
        ),
        Triple(
            SpeechTone.JOY,
            "рассмея* засмея* смеял* смеется смеются хохотну* расхохота* хохота* хохоч* хихикн* хихика* прысну*",
            "радостн* радостью весело веселым веселее восторженн* восторгом воодушевленн* восхищенн* обрадова* просия* " +
                "ликующ* ликова* смеясь смехом счастлив* оживленн* оживи* игрив* беззаботн* бодро",
        ),
        Triple(
            SpeechTone.FEAR,
            "вскрикн* взвизгн* пискну*",
            "испуганн* испугал* перепуганн* ужасе ужасом панике паническ* страхе страхом встревоженн* тревожн* тревогой " +
                "взволнованн* нервн* затравленн* опаслив*",
        ),
        Triple(
            SpeechTone.TENDER,
            "",
            "ласков* ласкающ* нежн* мягко мягким теплым теплотой успокаивающ* утешающ* ободряющ* заботлив* бережн*",
        ),
        Triple(
            SpeechTone.SURPRISE,
            "ахну* опеши* изумил* удивил*",
            "удивленн* удивлением изумленн* изумлением пораженн* ошеломленн* недоуменн* недоумением",
        ),
        Triple(
            SpeechTone.EXCLAIM,
            "воскликн* восклица* вскрича*",
            "громко громче",
        ),
    ),
    pairs = listOf(
        Triple("сквозь", "зуб*", SpeechTone.COLD),
        Triple("сквозь", "слез*", SpeechTone.SAD),
        Triple("тяжело", "вздох*", SpeechTone.SAD),
        Triple("пониз*", "голос*", SpeechTone.QUIET),
        Triple("еле", "слышн*", SpeechTone.QUIET),
        Triple("едва", "слышн*", SpeechTone.QUIET),
        Triple("чуть", "слышн*", SpeechTone.QUIET),
        Triple("одними", "губами", SpeechTone.WHISPER),
        Triple("повыс*", "голос*", SpeechTone.ANGRY),
        Triple("повыша*", "голос*", SpeechTone.ANGRY),
        Triple("не", "веря", SpeechTone.SURPRISE),
    ),
    blockers = setOf("не", "ни", "без", "ничуть", "нисколько", "нимало", "было", "стало", "становилось", "будет", "казалось"),
)

private val ENGLISH_MANNER = MannerTable(
    rows = listOf(
        Triple(
            SpeechTone.ANGRY,
            "shouted shouting shouts yelled yelling yells screamed screaming screams roared bellowed snarled growled " +
                "barked snapped thundered raged fumed hollered",
            "angrily furiously fiercely harshly heatedly indignantly rage anger fury",
        ),
        Triple(
            SpeechTone.COLD,
            "hissed sneered spat",
            "coldly icily coolly sternly grimly menacingly dryly drily flatly contemptuously sarcastically mockingly " +
                "scornfully curtly",
        ),
        Triple(
            SpeechTone.UNSURE,
            "stammered stuttered mumbled muttered faltered",
            "hesitantly uncertainly timidly shyly awkwardly sheepishly haltingly tentatively meekly",
        ),
        Triple(SpeechTone.QUIET, "murmured", "quietly softly"),
        Triple(SpeechTone.WHISPER, "whispered whispering whispers", "whisper"),
        Triple(
            SpeechTone.SAD,
            "sobbed sobbing wept weeping sniffled wailed",
            "sadly miserably tearfully wearily glumly mournfully bitterly sorrowfully dejectedly forlornly",
        ),
        Triple(
            SpeechTone.JOY,
            "laughed laughing giggled chuckled beamed",
            "happily cheerfully brightly gleefully excitedly merrily delightedly joyfully gaily enthusiastically",
        ),
        Triple(
            SpeechTone.FEAR,
            "shrieked squeaked",
            "fearfully anxiously frantically nervously worriedly terror panic frightened panicked trembling",
        ),
        Triple(SpeechTone.TENDER, "", "gently tenderly kindly warmly soothingly affectionately sweetly lovingly"),
        Triple(SpeechTone.SURPRISE, "gasped", "surprised astonished incredulously amazed disbelief bewildered"),
        Triple(SpeechTone.EXCLAIM, "exclaimed", "loudly"),
    ),
    pairs = listOf(
        Triple("gritted", "teeth", SpeechTone.COLD),
        Triple("clenched", "teeth", SpeechTone.COLD),
        Triple("low", "voice", SpeechTone.QUIET),
        Triple("under", "breath", SpeechTone.QUIET),
        Triple("raised", "voice", SpeechTone.ANGRY),
    ),
    blockers = setOf("not", "without", "never", "no", "didn’t", "didn't"),
)

package com.ozvuchka.app.speech

import com.ozvuchka.app.speech.RussianNumbers.Case
import com.ozvuchka.app.speech.RussianNumbers.Gender

/**
 * Turns book text into what the built-in neural voices should pronounce: digits, dates, times,
 * Roman numerals, units and common abbreviations become words. System TTS engines get the
 * original text, since they carry their own (usually better) normalizers.
 */
object SpeechNormalizer {
    fun normalize(text: String, language: String, forSupertonic: Boolean = false): String {
        val cleaned = cleanup(text)
        var spoken = if (language == "ru") russian(cleaned) else english(cleaned)
        if (forSupertonic) {
            // Supertonic has no pause token for a spaced dash; a comma gives the same breath.
            spoken = spoken.replace(Regex("^\\s*[—–-]\\s*"), "")
                .replace(Regex("(?<=[.!?…,;:])\\s*[—–]\\s+"), " ")
                .replace(Regex("\\s+[—–]\\s+"), ", ")
                .replace(Regex("[«»„“”]"), "")
                .replace(Regex(",\\s*,"), ",")
        }
        return spoken.replace(Regex("[ \\t\\u00A0]{2,}"), " ").trim()
    }

    /** Characters outside the BMP (emoji) break modified UTF-8 in JNI, and footnote marks are noise. */
    private fun cleanup(text: String): String {
        val withoutEmoji = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val c = text[index]
                when {
                    Character.isHighSurrogate(c) -> index++
                    Character.isLowSurrogate(c) -> Unit
                    c == '­' || c == '​' || c == '﻿' -> Unit
                    c in '︀'..'️' -> Unit
                    else -> append(c)
                }
                index++
            }
        }
        return withoutEmoji
            .replace(Regex("\\[(\\d{1,3}|\\*)]"), "")
            .replace(Regex("(?<=[\\p{L}.,!?»”])\\*+"), "")
            .replace(Regex("[\\u2009\\u202F]"), " ")
    }

    // ---------------------------------------------------------------- Russian

    private val ruAbbreviations: List<Pair<Regex, String>> = listOf(
        "(?<![\\p{L}])т\\.\\s?е\\." to "то есть",
        "(?<![\\p{L}])т\\.\\s?д\\." to "так далее",
        "(?<![\\p{L}])т\\.\\s?п\\." to "тому подобное",
        "(?<![\\p{L}])т\\.\\s?к\\." to "так как",
        "(?<![\\p{L}])т\\.\\s?н\\." to "так называемый",
        "(?<![\\p{L}])и\\s+др\\." to "и другие",
        "(?<![\\p{L}])и\\s+пр\\." to "и прочие",
        "(?<![\\p{L}])до\\s+н\\.\\s?э\\." to "до нашей эры",
        "(?<![\\p{L}])н\\.\\s?э\\." to "нашей эры",
        "(?<![\\p{L}])см\\.(?=\\s)" to "смотри",
        "(?<![\\p{L}])ср\\.(?=\\s)" to "сравни",
        "(?<![\\p{L}])стр\\.(?=\\s)" to "страница",
        "(?<![\\p{L}])рис\\.(?=\\s)" to "рисунок",
        "(?<![\\p{L}])ул\\.(?=\\s)" to "улица",
        "(?<![\\p{L}])им\\.(?=\\s)" to "имени",
        "(?<![\\p{L}])проф\\.(?=\\s)" to "профессор",
        "(?<![\\p{L}])акад\\.(?=\\s)" to "академик",
        "(?<![\\p{L}])тов\\.(?=\\s)" to "товарищ",
        "(?<![\\p{L}])г\\.(?=\\s+\\p{Lu})" to "город",
    ).map { (pattern, replacement) -> Regex(pattern, RegexOption.IGNORE_CASE) to replacement }

    private class MeasureUnit(val gender: Gender, val one: String, val few: String, val many: String)

    private val ruUnits = mapOf(
        "км" to MeasureUnit(Gender.MASCULINE, "километр", "километра", "километров"),
        "м" to MeasureUnit(Gender.MASCULINE, "метр", "метра", "метров"),
        "см" to MeasureUnit(Gender.MASCULINE, "сантиметр", "сантиметра", "сантиметров"),
        "мм" to MeasureUnit(Gender.MASCULINE, "миллиметр", "миллиметра", "миллиметров"),
        "кг" to MeasureUnit(Gender.MASCULINE, "килограмм", "килограмма", "килограммов"),
        "гр" to MeasureUnit(Gender.MASCULINE, "грамм", "грамма", "граммов"),
        "л" to MeasureUnit(Gender.MASCULINE, "литр", "литра", "литров"),
        "мл" to MeasureUnit(Gender.MASCULINE, "миллилитр", "миллилитра", "миллилитров"),
        "ч" to MeasureUnit(Gender.MASCULINE, "час", "часа", "часов"),
        "мин" to MeasureUnit(Gender.FEMININE, "минута", "минуты", "минут"),
        "сек" to MeasureUnit(Gender.FEMININE, "секунда", "секунды", "секунд"),
        "руб" to MeasureUnit(Gender.MASCULINE, "рубль", "рубля", "рублей"),
        "р" to MeasureUnit(Gender.MASCULINE, "рубль", "рубля", "рублей"),
        "коп" to MeasureUnit(Gender.FEMININE, "копейка", "копейки", "копеек"),
        "тыс" to MeasureUnit(Gender.FEMININE, "тысяча", "тысячи", "тысяч"),
        "млн" to MeasureUnit(Gender.MASCULINE, "миллион", "миллиона", "миллионов"),
        "млрд" to MeasureUnit(Gender.MASCULINE, "миллиард", "миллиарда", "миллиардов"),
        "°c" to MeasureUnit(Gender.MASCULINE, "градус Цельсия", "градуса Цельсия", "градусов Цельсия"),
        "°" to MeasureUnit(Gender.MASCULINE, "градус", "градуса", "градусов"),
        "%" to MeasureUnit(Gender.MASCULINE, "процент", "процента", "процентов"),
        "$" to MeasureUnit(Gender.MASCULINE, "доллар", "доллара", "долларов"),
        "€" to MeasureUnit(Gender.MASCULINE, "евро", "евро", "евро"),
        "₽" to MeasureUnit(Gender.MASCULINE, "рубль", "рубля", "рублей"),
    )

    private val months = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    private fun russian(input: String): String {
        var text = input
        text = text.replace(Regex("№\\s*(?=\\d)"), "номер ")
        text = text.replace(Regex("(?<=\\d)[\\u00A0](?=\\d{3}(?!\\d))"), "")

        // 05.12.2020 → «пятого декабря две тысячи двадцатого года»
        text = Regex("(?<![\\d.])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?![\\d.])(\\s*г\\.)?").replace(text) { match ->
            val day = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val year = match.groupValues[3].toLong()
            if (day !in 1..31 || month !in 1..12) return@replace match.value
            RussianNumbers.ordinal(day.toLong(), Case.GENITIVE, Gender.NEUTER) + " " + months[month - 1] + " " +
                RussianNumbers.ordinal(year, Case.GENITIVE) + " года"
        }

        // 12 апреля → «двенадцатого апреля»; «к 12 апреля» → «к двенадцатому», «на 12 апреля» → «на двенадцатое»;
        // «с 1 по 5 мая» → «с первого по пятое мая».
        val beforeDays = text
        text = Regex("(?iu)(?<![\\p{L}\\d])(\\d{1,2})(?:(\\s*[—–-]\\s*|\\s+по\\s+)(\\d{1,2}))?(?=[\\s\\u00A0]+(?:${months.joinToString("|")})(?![\\p{L}]))")
            .replace(beforeDays) { match ->
                val first = match.groupValues[1].toLong()
                val last = match.groupValues[3].toLongOrNull()
                if (first !in 1..31 || (last != null && last !in 1..31)) return@replace match.value
                val case = when (previousWord(beforeDays, match.range.first)) {
                    in dativePrepositions -> Case.DATIVE
                    "на", "по", "про", "за", "через" -> Case.ACCUSATIVE
                    else -> Case.GENITIVE
                }
                val day = RussianNumbers.ordinal(first, case, Gender.NEUTER)
                when {
                    last == null -> day
                    match.groupValues[2].trim() == "по" -> day + " по " + RussianNumbers.ordinal(last, Case.ACCUSATIVE, Gender.NEUTER)
                    else -> day + " — " + RussianNumbers.ordinal(last, case, Gender.NEUTER)
                }
            }

        // в 1941—1945 гг. → «в тысяча девятьсот сорок первом — тысяча девятьсот сорок пятом годах»
        text = Regex("(?iu)(?<![\\p{L}\\d])(во?\\s+)?(\\d{3,4})\\s*[—–-]\\s*(\\d{3,4})(\\s*)(годах|годы|годов|гг\\.)(?![\\p{L}])")
            .replace(text) { match ->
                val preposition = match.groupValues[1]
                val (case, noun) = when (match.groupValues[5].lowercase()) {
                    "годах" -> Case.PREPOSITIONAL to "годах"
                    "годов" -> Case.GENITIVE to "годов"
                    else -> if (preposition.isNotBlank()) Case.PREPOSITIONAL to "годах" else Case.NOMINATIVE to "годы"
                }
                preposition + RussianNumbers.ordinal(match.groupValues[2].toLong(), case) + " — " +
                    RussianNumbers.ordinal(match.groupValues[3].toLong(), case) + " " + noun
            }

        // 1917 году / в 1917 г. → ordinal year in the matching case.
        text = Regex("(?iu)(?<![\\p{L}\\d])(во?\\s+)?(\\d{1,4})(\\s*)(году|года|годом|годах|годы|годов|год|гг\\.|г\\.)(?![\\p{L}])")
            .replace(text) { match ->
                val preposition = match.groupValues[1]
                val year = match.groupValues[2].toLong()
                val word = match.groupValues[4].lowercase()
                // «3 года», «5 годов» are durations and stay cardinal; «в 45 году» is always a year.
                val durationForm = word == "год" || word == "года" || word == "годы" || word == "годов"
                if (durationForm && year < 1000) return@replace match.value
                val (case, plural, noun) = when (word) {
                    "году" -> Triple(Case.PREPOSITIONAL, false, "году")
                    "года" -> Triple(Case.GENITIVE, false, "года")
                    "годом" -> Triple(Case.INSTRUMENTAL, false, "годом")
                    "годах" -> Triple(Case.PREPOSITIONAL, true, "годах")
                    "годов" -> Triple(Case.GENITIVE, true, "годов")
                    "годы", "гг." -> Triple(Case.NOMINATIVE, true, "годы")
                    "г." -> if (preposition.isNotBlank()) {
                        Triple(Case.PREPOSITIONAL, false, "году")
                    } else {
                        Triple(Case.GENITIVE, false, "года")
                    }
                    else -> Triple(Case.NOMINATIVE, false, "год")
                }
                preposition + RussianNumbers.ordinal(year, case, Gender.MASCULINE, plural) + " " + noun
            }

        // Глава IV / главе 5 / том II → ordinal agreeing with the heading word.
        text = Regex("(?iu)(?<![\\p{L}])(глава|главе|главы|главу|главой|часть|части|частью|книга|книге|книгу|книги|том|тома|томе|томом)\\s+([IVXLCDM]+|\\d{1,4})(?![\\p{L}\\d])")
            .replace(text) { match ->
                val word = match.groupValues[1]
                val number = match.groupValues[2].toLongOrNull()
                    ?: RussianNumbers.parseRoman(match.groupValues[2])?.toLong()
                    ?: return@replace match.value
                val lower = word.lowercase()
                val gender = if (lower.startsWith("том")) Gender.MASCULINE else Gender.FEMININE
                val case = when (lower) {
                    "глава", "часть", "книга", "том" -> Case.NOMINATIVE
                    "главу", "книгу" -> Case.ACCUSATIVE
                    "главой", "частью", "томом" -> Case.INSTRUMENTAL
                    "главе", "книге", "томе" -> Case.PREPOSITIONAL
                    else -> Case.GENITIVE
                }
                word + " " + RussianNumbers.ordinal(number, case, gender)
            }

        // XIX век / в XX веке / XVIII в.
        text = Regex("(?<![\\p{L}])([IVXLC]{1,7})(\\s*)(веков|веками|веком|веке|веку|века|век|вв\\.|в\\.)(?![\\p{L}])")
            .replace(text) { match ->
                val number = RussianNumbers.parseRoman(match.groupValues[1]) ?: return@replace match.value
                val word = match.groupValues[3]
                val (case, plural, noun) = when (word) {
                    "века" -> Triple(Case.GENITIVE, false, "века")
                    "веке" -> Triple(Case.PREPOSITIONAL, false, "веке")
                    "веку" -> Triple(Case.DATIVE, false, "веку")
                    "веком" -> Triple(Case.INSTRUMENTAL, false, "веком")
                    "веков" -> Triple(Case.GENITIVE, true, "веков")
                    "веками" -> Triple(Case.INSTRUMENTAL, true, "веками")
                    "вв." -> Triple(Case.NOMINATIVE, true, "века")
                    else -> Triple(Case.NOMINATIVE, false, "век")
                }
                RussianNumbers.ordinal(number.toLong(), case, Gender.MASCULINE, plural) + " " + noun
            }

        // Пётр I, при Николае II → regnal ordinals; the case follows the name's ending.
        text = Regex("(?<![\\p{L}])(\\p{Lu}[а-яё]+)\\s+([IVX]{1,5})(?![\\p{L}\\d])").replace(text) { match ->
            val name = match.groupValues[1]
            val number = RussianNumbers.parseRoman(match.groupValues[2]) ?: return@replace match.value
            if (name.first() !in 'А'..'Я' && name.first() != 'Ё') return@replace match.value
            val case = when {
                name.endsWith("ом") || name.endsWith("ем") || name.endsWith("ём") -> Case.INSTRUMENTAL
                name.endsWith("е") -> Case.PREPOSITIONAL
                name.endsWith("у") || name.endsWith("ю") -> Case.DATIVE
                name.endsWith("а") || name.endsWith("я") -> Case.GENITIVE
                else -> Case.NOMINATIVE
            }
            name + " " + RussianNumbers.ordinal(number.toLong(), case)
        }

        // 5-й, 3-я, 90-х, 21-го… and the colloquial «2-х», «5-ти» that mean «двух», «пяти».
        text = Regex("(?<![\\d\\p{L}])(\\d{1,6})\\s?-\\s?(ыми|ими|ми|ти|ого|его|го|ому|ему|му|ый|ий|ой|ая|ое|ые|ых|их|ую|й|я|е|х|м|ю)(?![\\p{L}])(?=(?:[\\s\\u00A0]+(\\p{L}+))?)")
            .replace(text) { match ->
                val number = match.groupValues[1].toLong()
                val suffix = match.groupValues[2]
                val next = match.groupValues[3].lowercase()
                // «90-е годы», «в 1990-е и 2000-е»: decades are plural unless a neuter noun follows,
                // as in «20-е число» or «20-е мая»; «5-е место» is always neuter.
                val neuterNext = next.length > 3 && (next.endsWith("о") || next.endsWith("е") || next.endsWith("ё") ||
                    next.endsWith("мя") || next in months)
                val decade = next.startsWith("год") || next == "гг" || (number >= 20 && number % 10 == 0L && !neuterNext)
                if (suffix == "е" && decade) return@replace RussianNumbers.ordinal(number, Case.NOMINATIVE, plural = true)
                val colloquialGenitive = suffix == "ти" || suffix == "ми" ||
                    (suffix == "х" && number < 100 && number % 10 != 0L)
                if (colloquialGenitive) return@replace RussianNumbers.cardinalGenitive(number)
                val (case, gender, plural) = when (suffix) {
                    "й", "ый", "ий" -> Triple(Case.NOMINATIVE, Gender.MASCULINE, false)
                    "ой" -> Triple(Case.GENITIVE, Gender.FEMININE, false)
                    "я", "ая" -> Triple(Case.NOMINATIVE, Gender.FEMININE, false)
                    "е", "ое" -> Triple(Case.NOMINATIVE, Gender.NEUTER, false)
                    "ые" -> Triple(Case.NOMINATIVE, Gender.MASCULINE, true)
                    "го", "ого", "его" -> Triple(Case.GENITIVE, Gender.MASCULINE, false)
                    "му", "ому", "ему" -> Triple(Case.DATIVE, Gender.MASCULINE, false)
                    "м" -> Triple(Case.PREPOSITIONAL, Gender.MASCULINE, false)
                    "х", "ых", "их" -> Triple(Case.GENITIVE, Gender.MASCULINE, true)
                    "ю", "ую" -> Triple(Case.ACCUSATIVE, Gender.FEMININE, false)
                    else -> Triple(Case.INSTRUMENTAL, Gender.MASCULINE, true)
                }
                RussianNumbers.ordinal(number, case, gender, plural)
            }

        // 21:30 → «двадцать один тридцать», «к двадцати одному тридцати»
        val beforeTime = text
        text = Regex("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])").replace(beforeTime) { match ->
            val case = when (previousWord(beforeTime, match.range.first)) {
                in dativePrepositions -> Case.DATIVE
                in genitivePrepositions, "с", "со" -> Case.GENITIVE
                in prepositionalPrepositions -> Case.PREPOSITIONAL
                else -> Case.NOMINATIVE
            }
            val hours = RussianNumbers.cardinal(match.groupValues[1].toLong(), Gender.MASCULINE, case)
            val minutesText = match.groupValues[2]
            val minutes = when {
                minutesText == "00" -> "ноль ноль"
                minutesText.startsWith("0") -> "ноль " + RussianNumbers.cardinal(minutesText.toLong(), Gender.MASCULINE, case)
                else -> RussianNumbers.cardinal(minutesText.toLong(), Gender.MASCULINE, case)
            }
            "$hours $minutes"
        }

        // A score or ratio that is not a time: «2:1» → «два — один».
        text = text.replace(Regex("(?<![\\d:])(\\d{1,3}):(\\d{1,3})(?![\\d:])"), "$1 — $2")

        // Numbers with units and currencies: 3 км, 5 руб., 10 %, $20, −5 °C.
        text = Regex("([$€₽])\\s?(\\d+)").replace(text) { match -> match.groupValues[2] + " " + match.groupValues[1] }
        val beforeUnits = text
        text = Regex("(?iu)(?<![\\d\\p{L}])(−?)(\\d+(?:[.,]\\d+)?)\\s?(°c|°|%|\\$|€|₽|км|мм|см|мл|кг|млрд|млн|тыс|руб|коп|мин|сек|гр|м|л|ч|р)(\\.?)(?![\\p{L}])")
            .replace(beforeUnits) { match ->
                val unit = ruUnits[match.groupValues[3].lowercase()] ?: return@replace match.value
                val sign = if (match.groupValues[1].isNotEmpty()) "минус " else ""
                val number = match.groupValues[2]
                val trailingDot = match.groupValues[4]
                val genitive = previousWord(beforeUnits, match.range.first) in genitivePrepositions
                val spoken = when {
                    number.contains(',') || number.contains('.') -> decimal(number) + " " + unit.few
                    genitive -> {
                        // «около трёх километров», «более одного процента»: every count takes the genitive.
                        val value = number.toLong()
                        val singular = value % 10 == 1L && value % 100 != 11L
                        RussianNumbers.cardinal(value, unit.gender, Case.GENITIVE) + " " + if (singular) unit.few else unit.many
                    }
                    else -> {
                        val value = number.toLong()
                        RussianNumbers.cardinal(value, unit.gender) + " " +
                            RussianNumbers.plural(value, unit.one, unit.few, unit.many)
                    }
                }
                // Keep a sentence-final period that the abbreviation dot swallowed.
                sign + spoken + if (trailingDot.isNotEmpty() && match.range.last + 1 >= beforeUnits.length) "." else ""
            }

        for ((regex, replacement) in ruAbbreviations) text = regex.replace(text, replacement)

        // 3,5 → «три целых пять десятых»
        text = Regex("(?<![\\d.,])(\\d+)[,.](\\d{1,3})(?![\\d.,]\\d)").replace(text) { match -> decimal(match.value) }

        // Remaining integers agree with their noun: «1 книга», «с 2 чашек», «между 3 домами»,
        // or become ordinals when only an ordinal fits the noun: «на 3 этаже», «в 21 веке».
        val beforeIntegers = text
        text = Regex("(?<![\\d\\p{L}])(−?)(\\d{1,15})(?![\\d])(?=(?:[\\s\\u00A0]+(\\p{L}+))?)")
            .replace(beforeIntegers) { match ->
                val negative = match.groupValues[1].isNotEmpty()
                val value = match.groupValues[2].toLongOrNull() ?: return@replace match.value
                // «счёт 3 — 2 в пользу»: a preposition or conjunction after the number is not its noun.
                val noun = match.groupValues[3].lowercase().takeUnless { it in functionWords }.orEmpty()
                val previous = previousWord(beforeIntegers, match.range.first)
                val ordinal = if (negative) null else ordinalReading(value, previous, noun)
                if (ordinal != null) return@replace ordinal
                // «в 2 ночи» counts hours, so only the preposition sets the case: «в два ночи»,
                // «до двух ночи», and one o'clock is «в час ночи».
                if (noun in timesOfDay && !negative) {
                    val hourCase = casesAfter(previous).firstOrNull { it != Case.PREPOSITIONAL } ?: Case.NOMINATIVE
                    return@replace if (value == 1L) hourForms.getValue(hourCase) else RussianNumbers.cardinal(value, Gender.MASCULINE, hourCase)
                }
                val case = caseOf(value, previous, noun)
                (if (negative) "минус " else "") + RussianNumbers.cardinal(value, genderOf(value, case, noun), case)
            }

        // «в вторую» → «во вторую», «с ста» → «со ста», «о одном» → «об одном».
        text = text.replace(Regex("(?<![\\p{L}])([вВкК]) (?=втор)"), "$1о ")
            .replace(Regex("(?<![\\p{L}])([сС]) (?=втор|ст[ао](?![\\p{L}]))"), "$1о ")
            .replace(Regex("(?<![\\p{L}])([оО]) (?=одн|одиннадцат)"), "$1б ")

        return text
    }

    private fun decimal(value: String): String {
        val parts = value.split(',', '.')
        val whole = parts[0].toLongOrNull() ?: return value
        val fraction = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return RussianNumbers.cardinal(whole)
        val fractionValue = fraction.toLong()
        val wholeWords = RussianNumbers.cardinal(whole, Gender.FEMININE) + " " +
            RussianNumbers.plural(whole, "целая", "целых", "целых")
        val denominator = when (fraction.length) {
            1 -> RussianNumbers.plural(fractionValue, "десятая", "десятых", "десятых")
            2 -> RussianNumbers.plural(fractionValue, "сотая", "сотых", "сотых")
            else -> RussianNumbers.plural(fractionValue, "тысячная", "тысячных", "тысячных")
        }
        return wholeWords + " " + RussianNumbers.cardinal(fractionValue, Gender.FEMININE) + " " + denominator
    }

    private val genitivePrepositions = setOf(
        "до", "от", "из", "без", "для", "около", "после", "у", "против", "кроме", "вместо", "вокруг",
        "возле", "среди", "более", "менее", "свыше", "больше", "меньше", "ради", "мимо", "сверх",
    )
    private val dativePrepositions = setOf("к", "ко", "благодаря", "согласно", "вопреки", "навстречу")
    private val prepositionalPrepositions = setOf("о", "об", "обо", "при")
    private val instrumentalPrepositions = setOf("над", "под", "перед", "между", "меж")
    private val accusativePrepositions = setOf("в", "во", "на", "за", "через", "про", "сквозь", "спустя")

    private class LabelNoun(val gender: Gender, val cases: List<Case>, val alwaysOrdinal: Boolean)

    /**
     * Singular forms of nouns that are numbered in order: «на 3 этаже», «с 5 страницы», «в 21 веке».
     * Each entry lists nominative, genitive, dative, accusative, instrumental and prepositional; a
     * dash marks a form left out («дня» also means «of the day» in «в 5 дня»). The second group
     * reads as an ordinal only where a cardinal cannot agree: «за 1 минуту» is «одну минуту».
     */
    private val labelNouns: Map<String, LabelNoun> = buildMap {
        val order = listOf(Case.NOMINATIVE, Case.GENITIVE, Case.DATIVE, Case.ACCUSATIVE, Case.INSTRUMENTAL, Case.PREPOSITIONAL)
        fun add(gender: Gender, alwaysOrdinal: Boolean, entries: List<String>) = entries.forEach { entry ->
            val forms = entry.split(' ')
            forms.distinct().filter { it != "-" }.forEach { form ->
                val cases = order.filterIndexed { index, _ -> forms[index] == form }
                put(form, LabelNoun(gender, cases, alwaysOrdinal))
            }
        }
        add(
            Gender.MASCULINE, true,
            listOf(
                "этаж этажа этажу этаж этажом этаже", "класс класса классу класс классом классе",
                "курс курса курсу курс курсом курсе", "том тома тому том томом томе",
                "век века веку век веком веке", "раздел раздела разделу раздел разделом разделе",
                "параграф параграфа параграфу параграф параграфом параграфе", "пункт пункта пункту пункт пунктом пункте",
                "абзац абзаца абзацу абзац абзацем абзаце", "эпизод эпизода эпизоду эпизод эпизодом эпизоде",
                "сезон сезона сезону сезон сезоном сезоне", "раунд раунда раунду раунд раундом раунде",
                "тур тура туру тур туром туре", "акт акта акту акт актом акте", "этап этапа этапу этап этапом этапе",
                "уровень уровня уровню уровень уровнем уровне", "вагон вагона вагону вагон вагоном вагоне",
                "подъезд подъезда подъезду подъезд подъездом подъезде", "корпус корпуса корпусу корпус корпусом корпусе",
                "квартал квартала кварталу квартал кварталом квартале", "канал канала каналу канал каналом канале",
                "урок урока уроку урок уроком уроке", "съезд съезда съезду съезд съездом съезде",
                "ряд ряда ряду ряд рядом ряду", "путь пути пути путь путём пути", "номер номера номеру номер номером номере",
            ),
        )
        add(
            Gender.FEMININE, true,
            listOf(
                "страница страницы странице страницу страницей странице", "глава главы главе главу главой главе",
                "серия серии серии серию серией серии", "строка строки строке строку строкой строке",
                "линия линии линии линию линией линии", "палата палаты палате палату палатой палате",
                "школа школы школе школу школой школе", "аудитория аудитории аудитории аудиторию аудиторией аудитории",
                "часть части части часть частью части", "группа группы группе группу группой группе",
            ),
        )
        add(Gender.MASCULINE, false, listOf("день - дню день днём дне", "месяц месяца месяцу месяц месяцем месяце"))
        add(
            Gender.FEMININE, false,
            listOf(
                "неделя недели неделе неделю неделей неделе", "минута минуты минуте минуту минутой минуте",
                "квартира квартиры квартире квартиру квартирой квартире", "комната комнаты комнате комнату комнатой комнате",
            ),
        )
        add(Gender.NEUTER, false, listOf("место места месту место местом месте"))
    }

    /** The cases a preposition allows, most likely first. */
    private fun casesAfter(previous: String?): List<Case> = when (previous) {
        "в", "во", "на" -> listOf(Case.PREPOSITIONAL, Case.ACCUSATIVE)
        "за" -> listOf(Case.ACCUSATIVE, Case.INSTRUMENTAL)
        "с", "со" -> listOf(Case.GENITIVE, Case.INSTRUMENTAL)
        "через", "про", "сквозь", "спустя" -> listOf(Case.ACCUSATIVE)
        in genitivePrepositions -> listOf(Case.GENITIVE)
        in dativePrepositions -> listOf(Case.DATIVE)
        in prepositionalPrepositions -> listOf(Case.PREPOSITIONAL)
        in instrumentalPrepositions -> listOf(Case.INSTRUMENTAL)
        else -> emptyList()
    }

    private val functionWords = setOf(
        "в", "во", "на", "с", "со", "к", "ко", "о", "об", "у", "и", "а", "но", "до", "от", "из", "за", "по",
        "под", "над", "при", "для", "без", "или", "же", "ли", "не", "ни", "то",
    )
    private val timesOfDay = setOf("утра", "дня", "вечера", "ночи")
    private val hourForms = mapOf(
        Case.NOMINATIVE to "час", Case.ACCUSATIVE to "час", Case.GENITIVE to "часа", Case.DATIVE to "часу",
        Case.INSTRUMENTAL to "часом", Case.PREPOSITIONAL to "часе",
    )

    private val masculineDatives = setOf(
        "человеку", "другу", "брату", "отцу", "сыну", "мужу", "врачу", "учителю", "ребёнку", "ребенку",
        "мальчику", "гостю", "рублю", "доллару", "году", "дню", "месяцу", "часу",
    )

    /** Masculine nouns with a stressed locative in «-у»: «в 10 часу», «в 3 ряду». */
    private val masculineLocatives = setOf("году", "часу", "ряду", "кругу", "шагу", "бою", "углу")

    private val feminineLocatives = setOf(
        "странице", "главе", "строке", "строчке", "сцене", "минуте", "секунде", "неделе", "улице", "квартире",
        "комнате", "палате", "парте", "полке", "книге", "картине", "игре", "платформе", "дороге", "точке",
        "клетке", "камере", "школе", "группе", "команде", "роте", "бригаде", "колонне", "задаче", "статье",
        "поправке", "остановке", "передаче", "программе", "схеме", "попытке", "волне", "высоте", "отметке",
        "половине", "паре", "смене", "букве", "цифре", "лиге", "ступеньке", "полосе", "версте", "миле",
        "фазе", "зоне", "песне",
    )

    /** Prepositional and dative singular in «-е» belong to both genders; the ordinal ending differs. */
    private fun looksFeminine(noun: String) = noun in feminineLocatives || noun.endsWith("ице")

    /** «в 5 серии» (feminine) against «в 5 издании», «в 3 столетии» (neuter) and «на 5 пути». */
    private fun genderOfLocativeInI(noun: String): Gender = when {
        noun == "пути" -> Gender.MASCULINE
        listOf("ении", "ании", "етии", "ятии", "итии", "ытии").any { noun.endsWith(it) } -> Gender.NEUTER
        else -> Gender.FEMININE
    }

    /**
     * An ordinal reading when the noun's form cannot agree with a cardinal: «на 3 этаже» (a
     * cardinal would need «этажах»), «на 3 минуту», «ко 2 числу». Returns null for the usual
     * cardinal phrases: «в 5 часов», «в 2 раза», «на 3 части».
     */
    private fun ordinalReading(value: Long, previous: String?, noun: String): String? {
        if (value <= 0 || noun.isEmpty()) return null
        val lastDigit = value % 10
        val teen = value % 100 in 11..19
        val one = !teen && lastDigit == 1L
        val few = !teen && lastDigit in 2..4
        labelNouns[noun]?.let { label ->
            if (one && !label.alwaysOrdinal) return null
            val allowed = casesAfter(previous)
            // «2 этажа», «на 3 части» are cardinal phrases wherever a nominative or accusative can
            // stand; «с 3 страницы» and «к 3 части» are not, nor «во 2 части», written for «второй».
            val cardinalPosition = allowed.isEmpty() || Case.ACCUSATIVE in allowed
            if (few && Case.GENITIVE in label.cases && cardinalPosition && !(previous == "во" && value == 2L)) return null
            val case = allowed.firstOrNull { it in label.cases } ?: label.cases.first()
            return RussianNumbers.ordinal(value, case, label.gender)
        }
        val singularOblique = noun.length > 2 && !noun.endsWith("ие")
        val reading = when (previous) {
            "в", "во", "на" -> when {
                noun in masculineLocatives -> RussianNumbers.ordinal(value, Case.PREPOSITIONAL)
                singularOblique && noun.endsWith("е") && !one -> RussianNumbers.ordinal(
                    value, Case.PREPOSITIONAL, if (looksFeminine(noun)) Gender.FEMININE else Gender.MASCULINE,
                )
                noun.length > 3 && noun.endsWith("и") && noun != "ночи" && !one && !few ->
                    RussianNumbers.ordinal(value, Case.PREPOSITIONAL, genderOfLocativeInI(noun))
                singularOblique && (noun.endsWith("у") || noun.endsWith("ю")) && !one ->
                    RussianNumbers.ordinal(value, Case.ACCUSATIVE, Gender.FEMININE)
                else -> null
            }
            // «за 2 партой», «под 3 номером»: a cardinal would need «партами».
            "за", "под", "над", "перед", "между", "с", "со" -> when {
                one || noun.length <= 3 -> null
                noun.endsWith("ом") || noun.endsWith("ём") || noun.endsWith("ем") -> RussianNumbers.ordinal(value, Case.INSTRUMENTAL)
                noun.endsWith("ой") || noun.endsWith("ью") -> RussianNumbers.ordinal(value, Case.INSTRUMENTAL, Gender.FEMININE)
                else -> null
            }
            "к", "ко" -> when {
                noun.endsWith("ам") || noun.endsWith("ям") || one -> null
                singularOblique && (noun.endsWith("у") || noun.endsWith("ю")) -> RussianNumbers.ordinal(value, Case.DATIVE)
                singularOblique && noun.endsWith("е") -> RussianNumbers.ordinal(value, Case.DATIVE, Gender.FEMININE)
                else -> null
            }
            else -> null
        }
        if (reading != null || previous != "во" || value != 2L) return reading
        // «во 2 части»: the author already wrote the preposition for «второй».
        return when {
            noun.endsWith("и") -> RussianNumbers.ordinal(value, Case.PREPOSITIONAL, genderOfLocativeInI(noun))
            noun.endsWith("е") -> RussianNumbers.ordinal(value, Case.PREPOSITIONAL, if (looksFeminine(noun)) Gender.FEMININE else Gender.MASCULINE)
            noun.endsWith("у") || noun.endsWith("ю") -> RussianNumbers.ordinal(value, Case.ACCUSATIVE, Gender.FEMININE)
            else -> RussianNumbers.ordinal(value, Case.ACCUSATIVE)
        }
    }

    private fun previousWord(text: String, index: Int): String? =
        Regex("(\\p{L}+)[\\s\\u00A0]*$").find(text.substring((index - 40).coerceAtLeast(0), index))
            ?.groupValues?.get(1)?.lowercase()

    /**
     * The case a numeral takes, from the preposition before it and the ending of the noun after
     * it. Plural endings such as «-ами» or «-ах» are unambiguous; a noun that cannot follow a
     * nominative numeral («1 книги», «2 чашек») marks the genitive of a coordinated phrase.
     */
    private fun caseOf(value: Long, previous: String?, noun: String): Case {
        if (noun.endsWith("ами") || noun.endsWith("ями") || noun.endsWith("ьми")) return Case.INSTRUMENTAL
        if (noun.length > 3 && (noun.endsWith("ах") || noun.endsWith("ях"))) return Case.PREPOSITIONAL
        if (noun.length > 3 && (noun.endsWith("ам") || noun.endsWith("ям"))) return Case.DATIVE
        val singular = value % 10 == 1L && value % 100 != 11L
        val singularInstrumental = listOf("ом", "ем", "ём", "ой", "ей", "ью").any { noun.endsWith(it) }
        when (previous) {
            // «с 21 другом», «между 1 домом»: singular instrumental after one.
            in instrumentalPrepositions, "с", "со" -> if (singular && singularInstrumental) return Case.INSTRUMENTAL
        }
        when (previous) {
            in genitivePrepositions, "с", "со" -> return Case.GENITIVE
            in dativePrepositions -> return Case.DATIVE
            in prepositionalPrepositions -> return Case.PREPOSITIONAL
            in instrumentalPrepositions -> return Case.INSTRUMENTAL
        }
        // «в 1 доме», «на 21 странице»; «на 1 неделю», «за 21 минуту».
        if ((previous == "в" || previous == "во" || previous == "на") && noun.length > 2 && noun.endsWith("е") && !noun.endsWith("ие")) {
            return Case.PREPOSITIONAL
        }
        if (previous in accusativePrepositions && (noun.endsWith("у") || noun.endsWith("ю"))) return Case.ACCUSATIVE
        if (singular && previous == "по") return Case.DATIVE
        if (singular && casesAfter(previous).isEmpty()) {
            // «купил 21 книгу»: after one, «-у» is usually a feminine accusative, bar a few datives.
            if (noun in masculineDatives) return Case.DATIVE
            if (noun.endsWith("у") || noun.endsWith("ю")) return Case.ACCUSATIVE
            // «и 1 квартире»: «одной» serves every oblique feminine case.
            if (looksFeminine(noun)) return Case.PREPOSITIONAL
        }
        if (noun.isEmpty() || noun.first() !in 'а'..'я' && noun.first() != 'ё') return Case.NOMINATIVE
        val last = value % 10
        val lastTwo = value % 100
        if (lastTwo in 11..14) return Case.NOMINATIVE
        return when (last) {
            1L -> if (noun.endsWith("ы") || noun.endsWith("и")) Case.GENITIVE else Case.NOMINATIVE
            2L, 3L, 4L -> if (noun.last() in "аяиыеь") Case.NOMINATIVE else Case.GENITIVE
            else -> Case.NOMINATIVE
        }
    }

    /**
     * Gender only matters for numbers ending in 1 or 2, and the noun's ending in the chosen
     * case gives it away: «книга/книги/книгой» are feminine, «окно» neuter after 1.
     */
    private fun genderOf(value: Long, case: Case, noun: String): Gender {
        if (noun.isEmpty() || value % 100 in 11..14) return Gender.MASCULINE
        val last = value % 10
        return when (case) {
            Case.NOMINATIVE, Case.ACCUSATIVE -> when (last) {
                1L -> when {
                    noun.endsWith("а") || noun.endsWith("я") -> Gender.FEMININE
                    case == Case.ACCUSATIVE && (noun.endsWith("у") || noun.endsWith("ю")) -> Gender.FEMININE
                    noun.endsWith("о") || noun.endsWith("е") -> Gender.NEUTER
                    else -> Gender.MASCULINE
                }
                2L -> if (noun.endsWith("ы") || noun.endsWith("и")) Gender.FEMININE else Gender.MASCULINE
                else -> Gender.MASCULINE
            }
            Case.GENITIVE -> if (noun.endsWith("ы") || noun.endsWith("и")) Gender.FEMININE else Gender.MASCULINE
            Case.DATIVE -> if (noun.endsWith("е")) Gender.FEMININE else Gender.MASCULINE
            Case.INSTRUMENTAL -> if (noun.endsWith("ой") || noun.endsWith("ей") || noun.endsWith("ью")) Gender.FEMININE else Gender.MASCULINE
            Case.PREPOSITIONAL -> if (noun.endsWith("и") || looksFeminine(noun)) Gender.FEMININE else Gender.MASCULINE
        }
    }

    // ---------------------------------------------------------------- English

    private val enAbbreviations: List<Pair<Regex, String>> = listOf(
        "\\bMr\\." to "Mister",
        "\\bMrs\\." to "Missus",
        "\\bMs\\." to "Miz",
        "\\bDr\\.(?=\\s+\\p{Lu})" to "Doctor",
        "\\bSt\\.(?=\\s+\\p{Lu})" to "Saint",
        "\\bProf\\." to "Professor",
        "\\bvs\\." to "versus",
        "\\betc\\." to "et cetera",
        "\\be\\.g\\." to "for example",
        "\\bi\\.e\\." to "that is",
        "\\bNo\\.(?=\\s*\\d)" to "number",
    ).map { (pattern, replacement) -> Regex(pattern) to replacement }

    private fun english(input: String): String {
        var text = input
        for ((regex, replacement) in enAbbreviations) text = regex.replace(text, replacement)
        text = text.replace(Regex("(?<=\\d),(?=\\d{3}(?!\\d))"), "")
        text = Regex("([$£€])\\s?(\\d+(?:\\.\\d{1,2})?)").replace(text) { match ->
            val currency = when (match.groupValues[1]) {
                "$" -> "dollar"
                "£" -> "pound"
                else -> "euro"
            }
            val amount = match.groupValues[2]
            val whole = amount.substringBefore('.').toLong()
            val cents = amount.substringAfter('.', "").padEnd(2, '0').takeIf { amount.contains('.') }?.toLong()
            val main = EnglishNumbers.cardinal(whole) + " " + currency + if (whole == 1L || currency == "euro") "" else "s"
            if (cents != null && cents > 0) main + " and " + EnglishNumbers.cardinal(cents) + if (cents == 1L) " cent" else " cents" else main
        }
        text = Regex("(\\d+(?:\\.\\d+)?)\\s?%").replace(text) { match -> match.groupValues[1] + " percent" }
        text = Regex("\\b(\\d{1,6})(st|nd|rd|th)\\b").replace(text) { match -> EnglishNumbers.ordinal(match.groupValues[1].toLong()) }
        text = Regex("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])").replace(text) { match ->
            val hours = EnglishNumbers.cardinal(match.groupValues[1].toLong())
            val minutes = match.groupValues[2]
            when {
                minutes == "00" -> "$hours o'clock"
                minutes.startsWith("0") -> "$hours oh ${EnglishNumbers.cardinal(minutes.toLong())}"
                else -> "$hours ${EnglishNumbers.cardinal(minutes.toLong())}"
            }
        }
        text = Regex("(?<![\\d.])(\\d+)\\.(\\d+)(?![\\d.])").replace(text) { match ->
            EnglishNumbers.cardinal(match.groupValues[1].toLong()) + " point " +
                match.groupValues[2].map { EnglishNumbers.cardinal((it - '0').toLong()) }.joinToString(" ")
        }
        text = Regex("(?<![\\d\\p{L}])(\\d{1,15})(?![\\d])(s\\b)?").replace(text) { match ->
            val value = match.groupValues[1].toLongOrNull() ?: return@replace match.value
            val plural = match.groupValues[2].isNotEmpty()
            val words = if (match.groupValues[1].length == 4 && value in 1100..2099 && value % 1000 >= 10 || value in 1100..1999) {
                EnglishNumbers.year(value)
            } else {
                EnglishNumbers.cardinal(value)
            }
            if (plural) EnglishNumbers.pluralize(words) else words
        }
        return text
    }
}

/** English numerals for text normalization. */
internal object EnglishNumbers {
    private val small = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf(
        1_000_000_000_000L to "trillion",
        1_000_000_000L to "billion",
        1_000_000L to "million",
        1_000L to "thousand",
    )

    fun cardinal(value: Long): String {
        if (value < 0) return "minus " + cardinal(-value)
        if (value < 20) return small[value.toInt()]
        val parts = mutableListOf<String>()
        var rest = value
        for ((scale, name) in scales) {
            if (rest >= scale) {
                parts += cardinal(rest / scale) + " " + name
                rest %= scale
            }
        }
        if (rest >= 100) {
            parts += small[(rest / 100).toInt()] + " hundred"
            rest %= 100
        }
        if (rest > 0) {
            parts += when {
                rest < 20 -> small[rest.toInt()]
                rest % 10 == 0L -> tens[(rest / 10).toInt()]
                else -> tens[(rest / 10).toInt()] + "-" + small[(rest % 10).toInt()]
            }
        }
        return parts.joinToString(" ")
    }

    /** 1984 → «nineteen eighty-four», 2005 → «two thousand five», 1900 → «nineteen hundred». */
    fun year(value: Long): String {
        val high = value / 100
        val low = value % 100
        return when {
            value in 2000..2009 -> cardinal(value)
            low == 0L -> cardinal(high) + " hundred"
            low < 10 -> cardinal(high) + " oh " + cardinal(low)
            else -> cardinal(high) + " " + cardinal(low)
        }
    }

    fun ordinal(value: Long): String {
        val words = cardinal(value)
        val irregular = mapOf(
            "one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
            "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
        )
        val lastWord = words.split(' ', '-').last()
        val ordinalWord = irregular[lastWord] ?: when {
            lastWord.endsWith("y") -> lastWord.dropLast(1) + "ieth"
            else -> lastWord + "th"
        }
        return words.dropLast(lastWord.length) + ordinalWord
    }

    fun pluralize(words: String): String = when {
        words.endsWith("y") -> words.dropLast(1) + "ies"
        words.endsWith("x") || words.endsWith("s") -> words + "es"
        else -> words + "s"
    }
}

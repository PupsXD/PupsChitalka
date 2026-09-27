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
        val spoken = if (language == "ru") russian(cleaned, forSupertonic) else english(cleaned)
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

    private fun russian(input: String, forSupertonic: Boolean): String {
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
        text = Regex("(?<![\\d\\p{L}])(\\d{1,6})\\s?-\\s?(ыми|ими|ми|ти|ого|его|го|ому|ему|му|ый|ий|ой|ая|ое|ые|ых|их|ую|й|я|е|х|м|ю)(?![\\p{L}])")
            .replace(text) { match ->
                val number = match.groupValues[1].toLong()
                val suffix = match.groupValues[2]
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

        // 21:30 → «двадцать один тридцать»
        text = Regex("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])").replace(text) { match ->
            val hours = RussianNumbers.cardinal(match.groupValues[1].toLong())
            val minutesText = match.groupValues[2]
            val minutes = when {
                minutesText == "00" -> "ноль ноль"
                minutesText.startsWith("0") -> "ноль " + RussianNumbers.cardinal(minutesText.toLong())
                else -> RussianNumbers.cardinal(minutesText.toLong())
            }
            "$hours $minutes"
        }

        // Numbers with units and currencies: 3 км, 5 руб., 10 %, $20, −5 °C.
        text = Regex("([$€₽])\\s?(\\d+)").replace(text) { match -> match.groupValues[2] + " " + match.groupValues[1] }
        text = Regex("(?iu)(?<![\\d\\p{L}])(−?)(\\d+(?:[.,]\\d+)?)\\s?(°c|°|%|\\$|€|₽|км|мм|см|мл|кг|млрд|млн|тыс|руб|коп|мин|сек|гр|м|л|ч|р)(\\.?)(?![\\p{L}])")
            .replace(text) { match ->
                val unit = ruUnits[match.groupValues[3].lowercase()] ?: return@replace match.value
                val sign = if (match.groupValues[1].isNotEmpty()) "минус " else ""
                val number = match.groupValues[2]
                val trailingDot = match.groupValues[4]
                val spoken = if (number.contains(',') || number.contains('.')) {
                    decimal(number) + " " + unit.few
                } else {
                    val value = number.toLong()
                    RussianNumbers.cardinal(value, unit.gender) + " " +
                        RussianNumbers.plural(value, unit.one, unit.few, unit.many)
                }
                // Keep a sentence-final period that the abbreviation dot swallowed.
                sign + spoken + if (trailingDot.isNotEmpty() && match.range.last + 1 >= text.length) "." else ""
            }

        for ((regex, replacement) in ruAbbreviations) text = regex.replace(text, replacement)

        // 3,5 → «три целых пять десятых»
        text = Regex("(?<![\\d.,])(\\d+)[,.](\\d{1,3})(?![\\d.,]\\d)").replace(text) { match -> decimal(match.value) }

        // Remaining integers, with gender taken from the noun that follows: «1 книга», «2 окна».
        text = Regex("(?<![\\d\\p{L}])([−]?)(\\d{1,15})(?![\\d])(\\s+)?(\\p{L}+)?").replace(text) { match ->
            val sign = if (match.groupValues[1].isNotEmpty()) "минус " else ""
            val value = match.groupValues[2].toLongOrNull() ?: return@replace match.value
            val space = match.groupValues[3]
            val noun = match.groupValues[4]
            sign + RussianNumbers.cardinal(value, genderFromNoun(value, noun)) + space + noun
        }

        if (forSupertonic) {
            // Supertonic has no pause token for a spaced dash; a comma gives the same breath.
            text = text.replace(Regex("^\\s*[—–-]\\s*"), "")
                .replace(Regex("\\s+[—–]\\s+"), ", ")
                .replace(Regex("[«»„“”]"), "")
                .replace(Regex(",\\s*,"), ",")
        }
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

    /**
     * Gender only matters for numbers ending in 1 or 2. The noun after a number is in the
     * nominative singular after 1 and in the genitive singular after 2–4, so its ending is a
     * good hint: «книга/книги» are feminine, «окно/окна» neuter only after 1.
     */
    private fun genderFromNoun(value: Long, noun: String): Gender {
        if (noun.isEmpty()) return Gender.MASCULINE
        val lastTwo = value % 100
        val last = value % 10
        if (lastTwo in 11..14) return Gender.MASCULINE
        val lower = noun.lowercase()
        return when (last) {
            1L -> when {
                lower.endsWith("а") || lower.endsWith("я") -> Gender.FEMININE
                lower.endsWith("о") || lower.endsWith("е") -> Gender.NEUTER
                else -> Gender.MASCULINE
            }
            2L -> if (lower.endsWith("ы") || lower.endsWith("и")) Gender.FEMININE else Gender.MASCULINE
            else -> Gender.MASCULINE
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

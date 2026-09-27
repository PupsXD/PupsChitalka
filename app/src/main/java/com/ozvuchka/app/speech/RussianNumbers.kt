package com.ozvuchka.app.speech

/** Russian numerals for text normalization: cardinals with gender and case-aware ordinals. */
internal object RussianNumbers {
    enum class Gender { MASCULINE, FEMININE, NEUTER }
    enum class Case { NOMINATIVE, GENITIVE, DATIVE, ACCUSATIVE, INSTRUMENTAL, PREPOSITIONAL }

    private val units = arrayOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val teens = arrayOf(
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
        "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val tens = arrayOf(
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто",
    )
    private val hundreds = arrayOf(
        "", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот",
    )

    private class Scale(val value: Long, val gender: Gender, val forms: Array<String>, val ordinalStem: String)

    private val scales = listOf(
        Scale(1_000_000_000_000L, Gender.MASCULINE, arrayOf("триллион", "триллиона", "триллионов"), "триллионн"),
        Scale(1_000_000_000L, Gender.MASCULINE, arrayOf("миллиард", "миллиарда", "миллиардов"), "миллиардн"),
        Scale(1_000_000L, Gender.MASCULINE, arrayOf("миллион", "миллиона", "миллионов"), "миллионн"),
        Scale(1_000L, Gender.FEMININE, arrayOf("тысяча", "тысячи", "тысяч"), "тысячн"),
    )

    /** Chooses the noun form for a count: 1 книга, 2 книги, 5 книг. */
    fun plural(count: Long, one: String, few: String, many: String): String {
        val n = kotlin.math.abs(count)
        val lastTwo = n % 100
        val last = n % 10
        return when {
            lastTwo in 11..14 -> many
            last == 1L -> one
            last in 2..4 -> few
            else -> many
        }
    }

    fun cardinal(value: Long, gender: Gender = Gender.MASCULINE): String {
        if (value == 0L) return "ноль"
        if (value < 0) return "минус " + cardinal(-value, gender)
        val parts = mutableListOf<String>()
        var rest = value
        for (scale in scales) {
            val count = rest / scale.value
            if (count > 0) {
                // «тысяча девятьсот», not «одна тысяча девятьсот»: that is how books are read aloud.
                if (count != 1L) parts += cardinal(count, scale.gender)
                parts += plural(count, scale.forms[0], scale.forms[1], scale.forms[2])
                rest %= scale.value
            }
        }
        if (rest > 0) parts += belowThousand(rest.toInt(), gender)
        return parts.joinToString(" ")
    }

    private fun belowThousand(value: Int, gender: Gender): String {
        val parts = mutableListOf<String>()
        val h = value / 100
        val rest = value % 100
        if (h > 0) parts += hundreds[h]
        when {
            rest in 10..19 -> parts += teens[rest - 10]
            rest > 0 -> {
                if (rest >= 20) parts += tens[rest / 10]
                val unit = rest % 10
                if (unit > 0) parts += unitWord(unit, gender)
            }
        }
        return parts.joinToString(" ")
    }

    /** Oblique forms indexed by digit: genitive, dative, instrumental, prepositional. */
    private val obliqueUnits = arrayOf(
        arrayOf("", "одного", "двух", "трёх", "четырёх", "пяти", "шести", "семи", "восьми", "девяти"),
        arrayOf("", "одному", "двум", "трём", "четырём", "пяти", "шести", "семи", "восьми", "девяти"),
        arrayOf("", "одним", "двумя", "тремя", "четырьмя", "пятью", "шестью", "семью", "восемью", "девятью"),
        arrayOf("", "одном", "двух", "трёх", "четырёх", "пяти", "шести", "семи", "восьми", "девяти"),
    )
    private val obliqueFeminineOne = arrayOf("одной", "одной", "одной", "одной")
    private val obliqueTens = arrayOf(
        arrayOf("", "", "двадцати", "тридцати", "сорока", "пятидесяти", "шестидесяти", "семидесяти", "восьмидесяти", "девяноста"),
        arrayOf("", "", "двадцати", "тридцати", "сорока", "пятидесяти", "шестидесяти", "семидесяти", "восьмидесяти", "девяноста"),
        arrayOf("", "", "двадцатью", "тридцатью", "сорока", "пятьюдесятью", "шестьюдесятью", "семьюдесятью", "восемьюдесятью", "девяноста"),
        arrayOf("", "", "двадцати", "тридцати", "сорока", "пятидесяти", "шестидесяти", "семидесяти", "восьмидесяти", "девяноста"),
    )
    private val obliqueHundreds = arrayOf(
        arrayOf("", "ста", "двухсот", "трёхсот", "четырёхсот", "пятисот", "шестисот", "семисот", "восьмисот", "девятисот"),
        arrayOf("", "ста", "двумстам", "трёмстам", "четырёмстам", "пятистам", "шестистам", "семистам", "восьмистам", "девятистам"),
        arrayOf("", "ста", "двумястами", "тремястами", "четырьмястами", "пятьюстами", "шестьюстами", "семьюстами", "восемьюстами", "девятьюстами"),
        arrayOf("", "ста", "двухстах", "трёхстах", "четырёхстах", "пятистах", "шестистах", "семистах", "восьмистах", "девятистах"),
    )
    // Scale nouns in oblique cases: [case][0] after one, [case][1] after two and more.
    private val obliqueScales = mapOf(
        1_000L to arrayOf(arrayOf("тысячи", "тысяч"), arrayOf("тысяче", "тысячам"), arrayOf("тысячей", "тысячами"), arrayOf("тысяче", "тысячах")),
        1_000_000L to arrayOf(arrayOf("миллиона", "миллионов"), arrayOf("миллиону", "миллионам"), arrayOf("миллионом", "миллионами"), arrayOf("миллионе", "миллионах")),
        1_000_000_000L to arrayOf(arrayOf("миллиарда", "миллиардов"), arrayOf("миллиарду", "миллиардам"), arrayOf("миллиардом", "миллиардами"), arrayOf("миллиарде", "миллиардах")),
    )

    /**
     * A cardinal in any case: «двух чашек», «двумя друзьями», «о пяти домах». Nominative and
     * accusative (for inanimate nouns) share the nominative form.
     */
    fun cardinal(value: Long, gender: Gender, case: Case): String {
        if (case == Case.NOMINATIVE || case == Case.ACCUSATIVE || value <= 0 || value >= 1_000_000_000_000L) {
            val nominative = cardinal(value, gender)
            // «одну книгу»: feminine «одна» is the only inanimate numeral with its own accusative.
            return if (case == Case.ACCUSATIVE && nominative.endsWith("одна")) nominative.dropLast(1) + "у" else nominative
        }
        val slot = when (case) {
            Case.GENITIVE -> 0
            Case.DATIVE -> 1
            Case.INSTRUMENTAL -> 2
            else -> 3
        }
        val parts = mutableListOf<String>()
        var rest = value
        for (scale in listOf(1_000_000_000L, 1_000_000L, 1_000L)) {
            val count = rest / scale
            if (count > 0) {
                val scaleGender = if (scale == 1_000L) Gender.FEMININE else Gender.MASCULINE
                if (count != 1L) parts += obliqueBelowThousand(count.toInt(), scaleGender, slot)
                parts += obliqueScales.getValue(scale)[slot][if (count == 1L) 0 else 1]
                rest %= scale
            }
        }
        if (rest > 0) parts += obliqueBelowThousand(rest.toInt(), gender, slot)
        return parts.joinToString(" ")
    }

    private fun obliqueBelowThousand(value: Int, gender: Gender, slot: Int): String {
        val parts = mutableListOf<String>()
        val h = value / 100
        val rest = value % 100
        if (h > 0) parts += obliqueHundreds[slot][h]
        when {
            rest in 10..19 -> parts += teens[rest - 10].dropLast(1) + if (slot == 2) "ью" else "и"
            rest > 0 -> {
                if (rest >= 20) parts += obliqueTens[slot][rest / 10]
                val unit = rest % 10
                if (unit == 1 && gender == Gender.FEMININE) parts += obliqueFeminineOne[slot]
                else if (unit > 0) parts += obliqueUnits[slot][unit]
            }
        }
        return parts.joinToString(" ")
    }

    /** Genitive cardinal: «двух», «двадцати пяти». */
    fun cardinalGenitive(value: Long): String = cardinal(value, Gender.MASCULINE, Case.GENITIVE)

    private fun unitWord(unit: Int, gender: Gender): String = when {
        unit == 1 && gender == Gender.FEMININE -> "одна"
        unit == 1 && gender == Gender.NEUTER -> "одно"
        unit == 2 && gender == Gender.FEMININE -> "две"
        else -> units[unit]
    }

    private enum class StemKind { HARD, STRESSED, THIRD }

    private class Stem(val stem: String, val kind: StemKind = StemKind.HARD)

    private val ordinalUnits = arrayOf(
        Stem(""), Stem("перв"), Stem("втор", StemKind.STRESSED), Stem("трет", StemKind.THIRD), Stem("четвёрт"),
        Stem("пят"), Stem("шест", StemKind.STRESSED), Stem("седьм", StemKind.STRESSED), Stem("восьм", StemKind.STRESSED),
        Stem("девят"),
    )
    private val ordinalTeens = arrayOf(
        "десят", "одиннадцат", "двенадцат", "тринадцат", "четырнадцат",
        "пятнадцат", "шестнадцат", "семнадцат", "восемнадцат", "девятнадцат",
    )
    private val ordinalTens = arrayOf(
        Stem(""), Stem(""), Stem("двадцат"), Stem("тридцат"), Stem("сороков", StemKind.STRESSED), Stem("пятидесят"),
        Stem("шестидесят"), Stem("семидесят"), Stem("восьмидесят"), Stem("девяност"),
    )
    private val ordinalHundreds = arrayOf(
        "", "сот", "двухсот", "трёхсот", "четырёхсот", "пятисот", "шестисот", "семисот", "восьмисот", "девятисот",
    )
    private val compoundPrefix = arrayOf("", "", "двух", "трёх", "четырёх", "пяти", "шести", "семи", "восьми", "девяти")

    /** Only the last word of a compound ordinal inflects: «тысяча девятьсот семнадцатом». */
    fun ordinal(value: Long, case: Case, gender: Gender = Gender.MASCULINE, plural: Boolean = false): String {
        if (value <= 0L) return cardinal(value, gender)
        val lastTwo = value % 100
        val (prefixValue, stem) = when {
            lastTwo in 1..9 -> value - lastTwo to ordinalUnits[lastTwo.toInt()]
            lastTwo in 10..19 -> value - lastTwo to Stem(ordinalTeens[(lastTwo - 10).toInt()])
            lastTwo >= 20 && lastTwo % 10 != 0L -> value - lastTwo % 10 to ordinalUnits[(lastTwo % 10).toInt()]
            lastTwo >= 20 -> value - lastTwo to ordinalTens[(lastTwo / 10).toInt()]
            value % 1000 != 0L -> {
                val h = (value % 1000 / 100).toInt()
                value - h * 100L to Stem(ordinalHundreds[h])
            }
            else -> {
                val scale = scales.firstOrNull { value % it.value == 0L && value / it.value in 1..9 }
                    ?: return cardinal(value, gender)
                val count = (value / scale.value).toInt()
                // 2000 → «двухтысячный», 1000 → «тысячный».
                0L to Stem(compoundPrefix[count] + scale.ordinalStem)
            }
        }
        val word = inflect(stem, case, gender, plural)
        return if (prefixValue > 0) cardinal(prefixValue) + " " + word else word
    }

    private fun inflect(stem: Stem, case: Case, gender: Gender, plural: Boolean): String {
        if (stem.kind == StemKind.THIRD) return "трет" + thirdEnding(case, gender, plural)
        val ending = when {
            plural -> when (case) {
                Case.NOMINATIVE, Case.ACCUSATIVE -> "ые"
                Case.GENITIVE, Case.PREPOSITIONAL -> "ых"
                Case.DATIVE -> "ым"
                Case.INSTRUMENTAL -> "ыми"
            }
            gender == Gender.FEMININE -> when (case) {
                Case.NOMINATIVE -> "ая"
                Case.ACCUSATIVE -> "ую"
                else -> "ой"
            }
            else -> when (case) {
                Case.NOMINATIVE, Case.ACCUSATIVE -> when {
                    gender == Gender.NEUTER -> "ое"
                    stem.kind == StemKind.STRESSED -> "ой"
                    else -> "ый"
                }
                Case.GENITIVE -> "ого"
                Case.DATIVE -> "ому"
                Case.INSTRUMENTAL -> "ым"
                Case.PREPOSITIONAL -> "ом"
            }
        }
        return stem.stem + ending
    }

    private fun thirdEnding(case: Case, gender: Gender, plural: Boolean): String = when {
        plural -> when (case) {
            Case.NOMINATIVE, Case.ACCUSATIVE -> "ьи"
            Case.GENITIVE, Case.PREPOSITIONAL -> "ьих"
            Case.DATIVE -> "ьим"
            Case.INSTRUMENTAL -> "ьими"
        }
        gender == Gender.FEMININE -> when (case) {
            Case.NOMINATIVE -> "ья"
            Case.ACCUSATIVE -> "ью"
            else -> "ьей"
        }
        else -> when (case) {
            Case.NOMINATIVE, Case.ACCUSATIVE -> if (gender == Gender.NEUTER) "ье" else "ий"
            Case.GENITIVE -> "ьего"
            Case.DATIVE -> "ьему"
            Case.INSTRUMENTAL -> "ьим"
            Case.PREPOSITIONAL -> "ьем"
        }
    }

    /** Parses Roman numerals up to 3999; returns null for anything that is not a canonical numeral. */
    fun parseRoman(value: String): Int? {
        if (value.isEmpty() || value.length > 15) return null
        val digits = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (index in value.indices) {
            val current = digits[value[index]] ?: return null
            val next = value.getOrNull(index + 1)?.let { digits[it] ?: return null } ?: 0
            total += if (current < next) -current else current
        }
        if (total <= 0 || total > 3999) return null
        return total.takeIf { toRoman(it) == value }
    }

    private fun toRoman(number: Int): String {
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var rest = number
        return buildString {
            values.forEachIndexed { index, amount ->
                while (rest >= amount) {
                    append(symbols[index])
                    rest -= amount
                }
            }
        }
    }
}

package com.ozvuchka.app.speech

import kotlin.math.min

/** A word of a text with the marks and spaces before it. */
internal class Word(
    val text: String,
    val start: Int,
    /** Everything between the previous word and this one: spaces, commas, dashes, quotes. */
    val gap: String,
    /** First word of the text or of a sentence, or right after a colon, a quote or a dash, where any word may take a capital. */
    val opensSentence: Boolean,
) {
    /** Lowercase with «ё» as «е», the way the word lists spell it; an English «’s» is dropped. */
    val key: String = text.lowercase().replace('ё', 'е').removeSuffix("’s").removeSuffix("'s")
    val capital: Boolean get() = text[0].isUpperCase()

    /** A sentence ends before this word. */
    val afterSentenceEnd: Boolean get() = gap.any { it in ".!?…" }

    /** Any punctuation stands before this word, so it starts a new phrase. */
    val afterPunctuation: Boolean get() = gap.any { !it.isWhitespace() }
}

/**
 * Splits a text into words. Hyphens and apostrophes between letters stay inside a word: «кто-то»,
 * «А-Цин», «Ханьгуан-цзюнь», “don’t”.
 */
internal fun words(text: String): List<Word> {
    val result = ArrayList<Word>()
    var index = 0
    var previousEnd = 0
    while (index < text.length) {
        if (!text[index].isLetter()) {
            index++
            continue
        }
        val start = index
        while (index < text.length) {
            val c = text[index]
            if (c.isLetter() || c == STRESS_MARK) {
                index++
            } else if ((c == '-' || c == '\'' || c == '’') && index + 1 < text.length && text[index + 1].isLetter() && index > start) {
                index++
            } else {
                break
            }
        }
        val gap = text.substring(previousEnd, start)
        val opens = result.isEmpty() || gap.any { it in SENTENCE_OPENERS }
        result += Word(text.substring(start, index).replace(STRESS_MARK.toString(), ""), start, gap, opens)
        previousEnd = index
    }
    return result
}

private const val SENTENCE_OPENERS = ".!?…:«“„\"—–("

/** A combining acute: «за́мок» in books with stresses marked. */
internal const val STRESS_MARK = '\u0301'

/**
 * What a text says about who speaks: the gender, the name when there is one, and how the words
 * relate the speaker to the conversation.
 */
internal data class Clue(
    val gender: SpeechRole?,
    val name: String? = null,
    /** «Она ничего не ответила»: this person kept silent. */
    val silent: Boolean = false,
    /** «огрызнулся», «возразила», “replied”: someone else answers the last speaker. */
    val reply: Boolean = false,
    /** «добавила», «продолжил», “went on”: the last speaker goes on. */
    val continues: Boolean = false,
    /** «кричит кто-то из толпы», «голос из-за двери»: someone new whom the author does not name. */
    val anonymous: Boolean = false,
    /** «пожаловалась жертва»: the verb agrees with a word, not with the person; their own words weigh more. */
    val weak: Boolean = false,
    /** «Артём не двинулся с места»: this person did not react — whoever expected their answer goes on. */
    val still: Boolean = false,
) {
    val tells: Boolean get() = gender != null || name != null || anonymous
}

// ---------------------------------------------------------------- Russian grammar

/**
 * The gender of a Russian past-tense verb: «сказала», «улыбнулась», «пришла», «замерла» are hers,
 * «сказал», «улыбнулся», «произнёс», «замер» his. Nouns that end the same way («стола», «генерал»,
 * «угол») are known exceptions; plural and neuter forms tell nothing.
 */
internal fun pastGender(key: String): SpeechRole? {
    if (key.length < 3 || key in NOT_VERBS) return null
    if (key.endsWith("лась")) return if (key.length >= 6) SpeechRole.FEMALE else null
    if (key.endsWith("лся")) return if (key.length >= 5) SpeechRole.MALE else null
    if (key.endsWith("ла") && key[key.length - 3] in BEFORE_FEMININE_LA) return SpeechRole.FEMALE
    if (key.endsWith("л") && key[key.length - 2] in RUSSIAN_VOWELS) return SpeechRole.MALE
    if (key.removeSuffix("ся") in IRREGULAR_MASCULINE) return SpeechRole.MALE
    return null
}

/** A verb form without a subject of its own: a gendered or plural past tense, or an infinitive. */
private fun looksLikeVerb(key: String): Boolean =
    pastGender(key) != null || key.endsWith("ли") && key.length >= 4 || key.endsWith("лись") ||
        key.endsWith("ть") || key.endsWith("ться") || key.endsWith("чь")

private const val RUSSIAN_VOWELS = "аеиоуыэюяё"

/** «сказала», «пришла», «могла», «замерла», «несла», «везла», «пекла», «окрепла», «погибла», «затихла», «прочла». */
private const val BEFORE_FEMININE_LA = "аеиоуыэюяёшгрсзкпбхч"

/** Words that end like a past-tense verb but are nouns or particles. */
private val NOT_VERBS = setOf(
    // -ла
    "игла", "мгла", "угла", "орла", "посла", "осла", "весла", "масла", "числа", "кресла", "смысла", "замысла",
    "промысла", "умысла", "вымысла", "козла", "узла", "пепла", "тепла", "дупла", "стекла", "русла", "ремесла",
    "сила", "скала", "стрела", "пчела", "школа", "могила", "юла", "дела", "тела", "зала", "стола", "пола", "бала",
    "вала", "сала", "генерала", "адмирала", "маршала", "канала", "сигнала", "журнала", "материала", "финала",
    "идеала", "капитала", "мула", "стула", "гула", "аула", "ангела", "дьявола", "апостола", "престола", "символа",
    "глагола", "ствола", "вокзала", "бокала", "кинжала", "зеркала", "одеяла", "покрывала", "шакала", "овала",
    "кардинала", "скандала", "подвала", "перевала", "причала", "штурвала", "арсенала", "персонала", "оригинала",
    "пиала", "шкала", "похвала", "хвала", "зола", "смола", "кабала", "тыла", "пыла", "ила", "удела", "предела",
    "раздела", "отдела", "прицела", "обстрела", "караула", "крокодила", "подола", "котла", "дятла", "седла",
    "вилла", "горилла", "текила", "гондола", "парабола", "крыла", "чела",
    // People of either sex: «ты вон какой здоровила».
    "здоровила", "громила", "верзила", "кутила", "страшила", "воротила", "заправила", "мазила", "зубрила", "чудила",
    // Plural nouns that end like «ушли».
    "мысли", "земли", "рубли", "короли", "угли", "петли", "сопли", "вопли", "дали", "сабли", "цапли", "кегли",
    // -л
    "мол", "стол", "пол", "зал", "бал", "вал", "генерал", "адмирал", "маршал", "канал", "сигнал", "журнал",
    "материал", "финал", "идеал", "капитал", "интервал", "скандал", "подвал", "перевал", "причал", "штурвал",
    "арсенал", "персонал", "оригинал", "криминал", "вокзал", "бокал", "кинжал", "зеркал", "одеял", "покрывал",
    "шакал", "овал", "минерал", "кардинал", "трибунал", "терминал", "ангел", "дьявол", "апостол", "престол",
    "символ", "глагол", "ствол", "посол", "осел", "орел", "козел", "котел", "узел", "угол", "пепел", "дятел",
    "мул", "стул", "гул", "аул", "ил", "тыл", "пыл", "вол", "кол", "дел", "тел", "сил", "вил", "могил", "стрел",
    "пчел", "скал", "гол", "футбол", "провал", "обвал", "завал", "развал", "запал", "накал", "оскал", "укол",
    "раскол", "протокол", "частокол", "камзол", "рассол", "подол", "идол", "караул", "разгул", "прогул",
    "крокодил", "удел", "предел", "раздел", "отдел", "передел", "прицел", "обстрел", "расстрел", "пострел",
    "аврал", "пенал", "вокал", "портал", "капрал", "фингал",
)

/** «ушли», «переглянулись»: several people act, so the words name no single speaker. */
private fun pluralPast(key: String): Boolean =
    key.length >= 4 && (key.endsWith("ли") || key.endsWith("лись")) && key !in NOT_VERBS

/** Masculine past tense without the final «л»: «произнёс», «замер», «помог», «осёкся». */
private val IRREGULAR_MASCULINE = setOf(
    "нес", "принес", "унес", "донес", "отнес", "вознес", "перенес", "поднес", "внес", "занес", "вынес", "понес",
    "пронес", "произнес", "снес", "нанес", "обнес", "разнес",
    "вез", "привез", "увез", "отвез", "подвез", "завез", "вывез", "довез", "перевез", "повез",
    "изрек", "обрек", "нарек", "предрек", "отрек", "рек",
    "влек", "увлек", "привлек", "отвлек", "завлек", "извлек", "облек", "повлек",
    "пресек", "рассек", "высек", "отсек", "осек", "иссек",
    "пек", "испек", "запек", "тек", "утек", "истек", "протек", "потек", "вытек",
    "мог", "смог", "помог", "занемог", "изнемог", "превозмог",
    "лег", "прилег", "слег", "залег", "налег", "полег", "улег",
    "жег", "сжег", "зажег", "поджег", "обжег", "выжег", "прожег",
    "берег", "сберег", "уберег", "поберег", "стерег", "остерег", "подстерег", "пренебрег",
    "умер", "замер", "обмер", "помер", "запер", "отпер", "подпер", "упер", "опер",
    "тер", "стер", "вытер", "утер", "отер", "протер", "растер", "натер", "обтер", "простер", "распростер",
    "рос", "вырос", "подрос", "зарос", "оброс", "врос", "пророс", "дорос", "перерос", "прирос",
    "лез", "влез", "залез", "вылез", "пролез", "полез", "перелез", "исчез", "грыз", "загрыз",
    "затих", "утих", "притих", "смолк", "замолк", "умолк", "примолк",
    "промок", "взмок", "намок", "вымок", "оглох", "заглох", "издох", "засох", "высох", "иссох", "усох", "обсох",
    "окреп", "ослеп", "осип", "охрип", "прилип", "залип", "влип",
    "поник", "сник", "проник", "возник", "вник", "привык", "отвык", "свык", "достиг", "постиг", "настиг",
    "погиб", "ушиб", "зашиб", "вышиб", "продрог", "спас", "тряс", "потряс", "затряс", "вытряс",
)

/** Short forms that tell the gender of «я» or «ты»: «я рада», «ты уверен». */
private val SHORT_FORMS: Map<String, SpeechRole> = run {
    val female = "рада готова должна уверена согласна счастлива больна занята свободна одна сама виновата права " +
        "удивлена влюблена обязана благодарна знакома способна довольна спокойна похожа нужна жива голодна здорова " +
        "одинока беременна замужем поражена ранена убеждена обижена расстроена напугана испугана смущена тронута " +
        "потрясена измучена утомлена разочарована оскорблена польщена возмущена взволнована растеряна красива умна " +
        "глупа стара молода слаба сильна грустна одета раздета вынуждена намерена склонна неправа недовольна " +
        "несчастна честна серьезна рождена создана воспитана привязана спасена прощена приглашена обречена заперта " +
        "наказана обручена помолвлена уставшая"
    val male = "рад готов должен уверен согласен счастлив болен занят свободен один сам виноват прав удивлен " +
        "влюблен обязан благодарен знаком способен доволен спокоен похож нужен жив голоден здоров одинок женат " +
        "поражен ранен убежден обижен расстроен напуган испуган смущен тронут потрясен измучен утомлен " +
        "разочарован оскорблен польщен возмущен взволнован растерян красив умен глуп стар молод слаб силен " +
        "грустен одет раздет вынужден намерен склонен неправ недоволен несчастен честен серьезен рожден создан " +
        "воспитан привязан спасен прощен приглашен обречен заперт наказан обручен помолвлен уставший"
    female.split(' ').associateWith { SpeechRole.FEMALE } + male.split(' ').associateWith { SpeechRole.MALE }
}

/** Full adjectives in -ой that describe a man: «я такой», «ты другой». */
private val MASCULINE_OI = setOf(
    "такой", "другой", "простой", "живой", "больной", "молодой", "плохой", "злой", "глухой", "слепой", "немой",
    "чужой", "родной", "никакой", "дорогой", "крутой", "святой", "седой", "пустой", "худой", "слепой", "смешной",
    "смелый", "последний", "лишний", "третий",
)

/** Words that may stand between «я» and a predicate adjective: «я совсем не такая». */
private val INTENSIFIERS = setOf(
    "не", "ни", "такая", "такой", "очень", "совсем", "уже", "ведь", "же", "ж", "тоже", "также", "все", "просто",
    "вовсе", "еще", "слишком", "настолько", "так", "самая", "самый", "теперь", "всегда", "тогда", "бы", "вот",
)

/** Words set off by commas that do not end a clause: «я, кажется, опоздала». */
private val PARENTHETICAL = setOf(
    "кажется", "наверное", "наверно", "конечно", "похоже", "видимо", "пожалуй", "правда", "честно", "может",
    "признаться", "знаешь", "знаете", "кстати", "впрочем", "представляешь", "представь", "понимаешь", "понимаете",
    "веришь", "слышишь", "вообще-то", "по-моему", "по-твоему", "бывало", "говорят", "оказывается", "однако",
    "значит", "короче", "например", "естественно", "разумеется", "безусловно", "действительно",
)

/** Words after which the predicate of «я» no longer follows: a new subject or a new clause. */
private val PREDICATE_STOPS = setOf(
    "ты", "он", "она", "оно", "мы", "вы", "они", "что", "чтобы", "как", "когда", "если", "потому", "а", "но",
    "или", "хотя", "пока", "где", "куда", "будто", "словно", "который", "которая", "которое", "которые", "кто",
    "чем", "зачем", "почему", "ли",
)

private val PREPOSITIONS = setOf(
    "в", "во", "на", "с", "со", "к", "ко", "у", "о", "об", "обо", "от", "ото", "до", "из", "изо", "за", "по",
    "под", "подо", "над", "перед", "передо", "при", "про", "для", "без", "через", "сквозь", "среди", "между",
    "против", "после", "около", "возле", "вокруг", "мимо", "ради", "из-за", "из-под", "вдоль", "внутри",
    "насчет", "позади", "впереди", "напротив", "вместо", "кроме",
)

/** Words that open a new clause after a comma: «сказал Иван, и она отвернулась». */
private val CLAUSE_WORDS = setOf(
    "и", "а", "но", "да", "или", "когда", "пока", "что", "чтобы", "как", "если", "хотя", "потому", "поэтому",
    "где", "куда", "откуда", "словно", "будто", "точно", "пусть", "ибо", "раз", "едва", "чем", "отчего",
    // «Ширли уже мало волновало, какие тайны он хранит».
    "какой", "какая", "какое", "какие", "каких", "каким", "какую", "каков", "сколько", "почему", "зачем", "кто",
)

private val COORDINATING = setOf("и", "а", "но", "да", "или")

private val RELATIVE = setOf(
    "который", "которая", "которое", "которые", "которого", "которой", "которому", "которым", "которую",
    "которых", "которыми", "чей", "чья", "чье", "чьи",
)

/** Nouns for people whose gender is part of the word: «мать», «старик», «шицзе». */
internal val PERSONS: Map<String, SpeechRole> = run {
    val female = "мама мать матушка мамочка мамаша бабушка бабуля бабка старуха старушка тетя тетка тетушка " +
        "тетенька сестра сестрица сестренка сестричка дочь дочка доченька девочка девушка девчонка девица дева " +
        "барышня госпожа леди мадам мадемуазель мисс миссис фрау сеньора синьора сударыня дама королева принцесса " +
        "княгиня княжна царица царевна императрица графиня герцогиня баронесса маркиза женщина жена супруга " +
        "невеста вдова хозяйка служанка горничная няня нянька кормилица учительница наставница ученица подруга " +
        "подружка соседка незнакомка гостья красавица ведьма колдунья волшебница чародейка фея богиня жрица " +
        "монахиня настоятельница медсестра сиделка продавщица официантка секретарша начальница помощница мачеха " +
        "падчерица свекровь теща невестка внучка племянница кузина малышка девчушка целительница травница " +
        "воительница шицзе шимэй шинян цзецзе мэймэй гунян гуньян сяоцзе фужэнь ваньфэй хуанхоу няннян гуйфэй " +
        "наложница барыня кухарка прачка швея модистка помещица купчиха генеральша попадья послушница игуменья " +
        "крестьянка горожанка старушонка хозяюшка певица актриса танцовщица студентка школьница"
    val male = "папа отец батюшка папочка папаша дедушка дед дедуля старик старичок старец дядя дядька дядюшка " +
        "брат братец братишка братик сын сынок сыночек мальчик мальчишка паренек парень юноша юнец мужчина муж " +
        "супруг жених вдовец господин сэр лорд милорд мистер герр сеньор синьор месье мсье сударь барин король " +
        "принц князь княжич царь царевич император граф герцог барон маркиз хозяин слуга наставник ученик друг " +
        "приятель товарищ сосед незнакомец гость красавец колдун волшебник чародей бог жрец монах настоятель " +
        "священник генерал солдат стражник страж воин командир офицер сержант лейтенант полковник майор капитан " +
        "следователь инспектор продавец официант кузнец охотник рыцарь оруженосец мудрец отшельник торговец купец " +
        "крестьянин мужик отчим пасынок свекор тесть зять внук племянник кузен малыш лекарь целитель травник " +
        "разбойник атаман вождь шаман паж шут шисюн шиди гэгэ диди дагэ эргэ гунцзы ванье даочжан надзиратель " +
        "городовой полицейский урядник исправник пристав унтер фельдфебель ефрейтор прапорщик поручик ротмистр " +
        "есаул казак помещик лакей кучер извозчик дворник писарь чиновник портной сапожник мельник лавочник " +
        "трактирщик дьячок дьякон послушник игумен студент школьник певец актер танцор юнкер кадет гусар " +
        "джентльмен епископ архиепископ боцман матрос моряк"
    female.split(' ').associateWith { SpeechRole.FEMALE } + male.split(' ').associateWith { SpeechRole.MALE }
}

/** «голос отца», «смех девушки»: people named in the genitive after a voice. */
private val PERSONS_GENITIVE: Map<String, SpeechRole> = run {
    val female = "матери мамы сестры дочери девушки девочки женщины старухи жены бабушки тети госпожи хозяйки " +
        "подруги невесты шицзе шимэй"
    val male = "отца папы брата сына мальчика мужчины старика мужа дедушки дяди господина хозяина друга жениха " +
        "шисюна шиди"
    female.split(' ').associateWith { SpeechRole.FEMALE } + male.split(' ').associateWith { SpeechRole.MALE }
}

/** Things a verb may agree with instead of the speaker: «голос дрогнул», «рука легла». */
internal val VOICE_NOUNS = setOf(
    "голос", "голосок", "голосе", "голосом", "тон", "тоне", "шепот", "крик", "вскрик", "возглас", "смех", "смешок",
    "хохот", "вздох", "всхлип", "рык", "рев", "окрик", "оклик", "взгляд", "взор", "лицо", "улыбка", "усмешка",
    "губы", "глаза", "рука", "руки", "ладонь", "пальцы", "плечи", "брови", "голова", "сердце", "слова", "ответ",
    "речь", "интонация", "интонации", "вопль", "визг", "писк", "стон", "плач",
)

/** Kinship and voices before a name: «брат Цзян Яньли», «голос Маши» — the name is someone else's. */
internal val BEFORE_OTHERS_NAME = setOf(
    "брат", "сестра", "отец", "мать", "сын", "дочь", "муж", "жена", "друг", "подруга", "слуга", "служанка",
    "ученик", "ученица", "невеста", "жених", "сестрица", "братец", "внук", "внучка", "племянник", "племянница",
    "мама", "папа", "дед", "бабушка", "дедушка", "сосед", "соседка", "шисюн", "шиди", "шицзе", "шимэй", "гэгэ",
    "цзецзе", "мэймэй", "диди", "голос", "взгляд", "лицо", "глаза", "рука", "руки", "улыбка", "смех", "крик",
    "мать", "отца", "дядя", "тетя", "у", "к", "от", "для", "с", "со", "без", "о", "об", "про", "на", "в", "за",
)

/** «кто-то», «чей-то голос»: a speaker the author does not name. */
private val INDEFINITE = setOf(
    "кто-то", "кто-нибудь", "кто-либо", "некто", "чей-то", "чья-то", "чье-то", "чьи-то", "чей-нибудь", "чья-нибудь",
)

/** «донеслось из кухни», «раздался её голос»: a sound agrees with the sound, not with whoever made it. */
private val SOUND_VERBS = setOf(
    "донеслось", "раздалось", "послышалось", "прозвучало", "донесся", "раздался", "послышался", "прозвучал",
    "донеслась", "раздалась", "послышалась", "прозвучала", "донеслись", "раздались", "послышались", "прозвучали",
    "долетело", "долетел", "долетела",
)

/** «закричал женский голос», «ответил мужской». */
private val VOICE_ADJECTIVES = mapOf(
    "женский" to SpeechRole.FEMALE, "женским" to SpeechRole.FEMALE, "женского" to SpeechRole.FEMALE,
    "девичий" to SpeechRole.FEMALE, "мужской" to SpeechRole.MALE, "мужским" to SpeechRole.MALE,
    "мужского" to SpeechRole.MALE,
)

/** «повернулся к мужу», «обратилась к дочери»: whom the author's words turn to. */
private val PERSONS_DATIVE: Map<String, SpeechRole> = run {
    val female = "матери маме дочери дочке жене сестре подруге девушке женщине бабушке тете госпоже хозяйке старухе девочке"
    val male = "отцу папе сыну мужу брату другу мужчине дедушке дяде господину хозяину старику мальчику"
    female.split(' ').associateWith { SpeechRole.FEMALE } + male.split(' ').associateWith { SpeechRole.MALE }
}

/** «слушала, как старейшина…», “listened”: in narration, someone else is talking. */
private val LISTENING = setOf(
    "слушал", "слушала", "слушает", "прислушивался", "прислушивалась", "внимал", "внимала", "listened", "listening",
)

/** «слышит вдруг Очумелов»: the one named listens, someone else speaks. */
private val PERCEPTION = setOf(
    "слышит", "слышат", "слышал", "слышала", "услышал", "услышала", "услыхал", "услыхала", "расслышал", "расслышала",
)

/** «промолчал»: the one who kept silent is not the next to speak. */
private val SILENCE = setOf("промолчал", "промолчала", "смолчал", "смолчала", "отмолчался", "отмолчалась")

/** Speech verbs whose negation means silence: «ничего не ответила». */
private val SPEECH_STEMS = listOf("ответ", "сказа", "произн", "пророн", "отозва", "отклик", "возраз", "вымолв", "молв")

/** Someone answers the last speaker. */
private val REPLY_STEMS = listOf(
    "ответ", "отвеч", "возраз", "огрызн", "огрыза", "парир", "отозва", "отзыва", "отклик", "перебил", "перебива",
    "отрезал", "осадил",
)

/** The last speaker goes on. */
private val CONTINUE_STEMS = listOf("продолж", "добав", "закончи", "договори", "повтори", "уточни", "поясни")

/** Capitalised words that start sentences but are not names. */
internal val COMMON_RU = setOf(
    "и", "а", "но", "да", "нет", "не", "ни", "же", "ведь", "вот", "вон", "тут", "там", "здесь", "где", "куда",
    "когда", "тогда", "потом", "затем", "сначала", "наконец", "вдруг", "сразу", "снова", "опять", "уже", "еще",
    "только", "лишь", "даже", "почти", "совсем", "очень", "так", "как", "что", "кто", "чем", "чего", "почему",
    "зачем", "откуда", "если", "хотя", "пока", "чтобы", "будто", "словно", "ну", "ох", "ах", "эх", "ой", "эй",
    "хм", "ага", "угу", "конечно", "кажется", "наверное", "может", "пожалуй", "впрочем", "однако", "итак", "зато",
    "поэтому", "потому", "все", "это", "этот", "эта", "эти", "тот", "та", "те", "то", "такой", "такая", "такие",
    "сам", "сама", "сами", "каждый", "никто", "ничто", "ничего", "никогда", "нигде", "всегда", "иногда", "теперь",
    "сейчас", "сегодня", "вчера", "завтра", "утром", "вечером", "ночью", "днем", "долго", "медленно", "тихо",
    "громко", "осторожно", "я", "ты", "он", "она", "оно", "мы", "вы", "они", "меня", "тебя", "его", "ее", "их",
    "мне", "тебе", "ему", "ей", "им", "нам", "вам", "нас", "вас", "себя", "мой", "моя", "мое", "мои", "твой",
    "твоя", "наш", "наша", "ваш", "ваша", "свой", "своя", "ладно", "хорошо", "спасибо", "пожалуйста", "простите",
    "извините", "привет", "здравствуйте", "прости", "извини", "стой", "постой", "смотри", "слушай", "иди",
    "сначала", "после", "перед", "потом", "вместе", "один", "одна", "двое", "трое", "оба", "обе", "несколько",
) + PREPOSITIONS

// ---------------------------------------------------------------- English

internal val PERSONS_EN: Map<String, SpeechRole> = run {
    val female = "woman girl mother mom mum mommy mummy mama sister daughter wife aunt auntie grandmother grandma " +
        "granny queen princess lady madam ma’am ma'am mrs ms miss mistress duchess countess baroness empress nun " +
        "maid waitress actress hostess stepmother niece granddaughter bride widow fiancée girlfriend lass gal " +
        "heroine witch goddess priestess"
    val male = "man boy father dad daddy papa brother son husband uncle grandfather grandpa king prince lord sir " +
        "gentleman mister mr duke count baron emperor monk priest waiter actor host stepfather nephew grandson " +
        "groom widower fiancé boyfriend lad fellow guy hero wizard god"
    female.split(' ').associateWith { SpeechRole.FEMALE } + male.split(' ').associateWith { SpeechRole.MALE }
}

private val VOICE_NOUNS_EN = setOf(
    "voice", "tone", "whisper", "words", "eyes", "face", "lips", "hand", "hands", "smile", "laugh", "gaze", "mouth",
)

/** Words after «her» that show it is an object, not a possessive: “told her to wait”. */
private val AFTER_OBJECT_HER = setOf(
    "to", "and", "that", "the", "a", "an", "in", "on", "at", "with", "for", "from", "as", "if", "when", "but", "or",
    "again", "back", "up", "down", "off", "out", "away", "over", "into", "onto", "about", "before", "after", "then",
    "so", "too", "very", "just", "now", "this", "what", "how", "why", "where", "gently", "softly", "quietly",
)

private val REPLY_EN = setOf("replied", "answered", "retorted", "countered", "snapped", "responded")
private val CONTINUE_EN = setOf("continued", "added", "went")

internal val COMMON_EN = setOf(
    "the", "a", "an", "and", "but", "or", "so", "then", "when", "while", "as", "if", "he", "she", "it", "they",
    "we", "you", "i", "his", "her", "their", "my", "your", "our", "its", "this", "that", "these", "those", "there",
    "here", "what", "who", "why", "how", "where", "yes", "no", "oh", "ah", "well", "now", "just", "still", "even",
    "maybe", "perhaps", "after", "before", "at", "in", "on", "with", "for", "from", "by", "to", "of", "into",
    "over", "under", "again", "all", "some", "one", "not", "please", "thank", "thanks", "sorry", "hey", "hello",
    "okay", "ok", "come", "look", "listen", "wait", "stop", "go", "let", "don’t", "don't", "i’m", "i'm", "it’s",
    "it's", "that’s", "that's", "there’s", "there's", "every", "each", "nobody", "somebody", "everyone",
    "someone", "nothing", "something", "everything", "never", "always", "sometimes", "suddenly", "finally",
    "meanwhile", "later", "soon", "slowly", "quietly", "outside", "inside", "behind", "because", "though",
    "although", "once", "until", "since", "without", "through", "across", "around", "about", "above", "below",
)

// ---------------------------------------------------------------- author's words

/**
 * Who the author's words point at, read from their start: «сказала она», «Лань Си Чэнь покачал
 * головой», «её голос дрогнул», «вырвалось у неё», “said her father”, “Grace said”. With
 * [attribution] the words continue the line's sentence («— Нет, — …») and a verb may come first;
 * a new sentence after a line or in narration needs someone doing it, so «Повисла тишина» tells
 * nothing.
 */
internal fun subjectClue(text: String, language: String, cast: Cast, attribution: Boolean): Clue? {
    val words = words(text)
    if (words.isEmpty()) return null
    return if (language == "en") englishSubject(words, cast, attribution) else russianSubject(words, cast, attribution)
}

/**
 * Who acts in the last sentence of a narration: «Цзян Яньли поставила на стол миску». «Он» takes
 * the name from the sentence before («Явился Цзян Чэн. Он швырнул на лавку рыбу»). When the last
 * two sentences show a man and a woman acting («Она щёлкнула выключателем. Дима зажмурился»), it
 * is not clear who speaks next, and the narration tells nothing.
 */
internal fun beatClue(narration: String, language: String, cast: Cast): Clue? {
    val text = narration.trimEnd()
    val last = lastSentence(text)
    if (text.endsWith(':')) introduction(last, language, cast)?.let { return it }
    val found = subjectClue(last, language, cast, attribution = false) ?: return null
    // «…сидела прямо и слушала, как старейшина Хоу…»: the one listening is not the one to speak.
    val clue = if (words(last).any { it.key in LISTENING }) found.copy(silent = true) else found
    val rest = text.removeSuffix(last).trimEnd()
    if (rest.isEmpty() || clue.gender == null) return clue
    val before = subjectClue(lastSentence(rest), language, cast, attribution = false) ?: return clue
    if (before.gender != null && before.gender != clue.gender && !before.silent && !before.still) return null
    if (clue.name == null && before.name != null && before.gender == clue.gender) return clue.copy(name = before.name)
    return clue
}

/**
 * A sentence that leads into a line with a colon says who speaks, whoever acted in the sentences
 * before it. The last part of it with a verb in the past tells, read like the author's words after a
 * line: «Уголки губ Тириана дрогнули, прежде чем он услышал, как сестра продолжила:», «Дункан
 * встал и пошёл к выходу, а Ширли воскликнула:», «Молодая кондуктор распахнула окно и закричала:».
 * A part about someone else is passed over — «взглянул на Нину, которая ела напротив него:»,
 * «вспоминала, что говорил учитель:», «прежде чем она успела закончить:», «отчего Ширли вздрогнула:»
 * — and so is a verb that may agree with a thing, «к нему пришла смелая идея:», unless it speaks or
 * a name before it agrees: «и Элис, хотя и чувствовала свою неправоту, могла лишь кивнуть:».
 * Otherwise the subject of the sentence tells. «Дункан не сдержался и улыбнулся:» is no silence.
 */
private fun introduction(sentence: String, language: String, cast: Cast): Clue? {
    fun usable(clue: Clue?) = clue?.takeIf { it.gender != null && !it.silent && !it.still }
    if (language != "en") {
        val parts = sentence.split(',', ';')
        for (index in parts.indices.reversed()) {
            val part = words(parts[index])
            if (part.none { pastGender(it.key) != null } || part.first().key in ABOUT_SOMEONE_ELSE) continue
            val found = subjectClue(parts[index], language, cast, attribution = true) ?: continue
            // «…а затем снизу донёсся девичий голос:», «…как вдруг услышал слабый голос:»: someone new.
            if (found.anonymous) return found
            val clue = usable(found) ?: continue
            if (!clue.weak) return clue
            // Only a verb tells. Its subject may stand in a part before it — «Пёс несколько секунд
            // колебался, а затем, наконец, сказал:», «и он, не удержавшись, вопросил:» — and a verb of
            // speaking has a speaker for its subject; any other may agree with a thing: «висевшая над
            // дверью вывеска перекосилась».
            val subject = (index - 1 downTo 0).firstNotNullOfOrNull { before ->
                subjectClue(parts[before], language, cast, attribution = false)?.takeIf { it.gender != null || it.name != null }
            }
            if (subject?.gender == clue.gender) return clue.copy(name = subject?.name, weak = false)
            if (part.any { word -> pastGender(word.key) != null && SPEAKING_STEMS.any { word.key.startsWith(it) } }) return clue
        }
    }
    return usable(subjectClue(sentence, language, cast, attribution = false))
}

/** Parts of a sentence about someone other than who speaks next. */
private val ABOUT_SOMEONE_ELSE = RELATIVE + setOf("что", "чтобы", "чем", "прежде", "отчего", "хотя")

/** Verbs of speaking: the speaker is whoever does them. */
private val SPEAKING_STEMS = listOf(
    "сказ", "говор", "спрос", "вопрос", "ответ", "отвеч", "крикн", "крич", "произн", "прошепт", "шепн", "шепта", "воскликн",
    "бормот", "пробормот", "позва", "окликн", "отозва", "добав", "продолж", "повтор", "выдохн", "рявкн", "прорыч",
    "взмол", "попрос", "объясн", "заяв", "усмехн", "фыркн", "выпал", "пискн", "буркн", "огрызн", "возраз",
    "перебил", "прервал", "вздохн", "молв", "пропел", "протянул", "заговор", "проговор",
)

/** The sentences of a text, split after «.», «!», «?» and «…» followed by a space. */
internal fun sentencesOf(text: String): List<String> {
    val sentences = ArrayList<String>()
    var start = 0
    for (index in text.indices) {
        if (text[index] in ".!?…" && (index + 1 == text.length || text[index + 1].isWhitespace())) {
            sentences += text.substring(start, index + 1)
            start = index + 1
        }
    }
    if (start < text.length) sentences += text.substring(start)
    return sentences.map { it.trim() }.filter { sentence -> sentence.any { it.isLetter() } }
}

internal fun lastSentence(text: String): String {
    var end = text.length
    while (end > 0 && !text[end - 1].isLetterOrDigit()) end--
    var cut = end - 1
    while (cut > 0 && !(text[cut] in ".!?…" && text[cut + 1].isWhitespace())) cut--
    return if (cut <= 0) text else text.substring(cut + 1)
}

/**
 * Whom the author's words turn to: «Громов повернулся к Коваленко», «обратилась к мужу», «подошёл
 * к ней». They are likely to answer next.
 */
internal fun turnedTo(text: String, language: String, cast: Cast): SpeechRole? {
    if (language == "en") return null
    val words = words(text)
    for (index in 0 until words.size - 1) {
        if (words[index].key != "к" && words[index].key != "ко") continue
        val next = words[index + 1]
        if (next.afterPunctuation) continue
        when (next.key) {
            "ней" -> return SpeechRole.FEMALE
            "нему" -> return SpeechRole.MALE
        }
        PERSONS_DATIVE[next.key]?.let { return it }
        if (next.capital) {
            val name = nameAt(words, index + 1, cast, COMMON_RU)
            if (name.isNotEmpty()) cast.genderOf(name.spelled())?.let { return it }
        }
    }
    return null
}

private fun isName(word: Word, cast: Cast, common: Set<String>): Boolean {
    if (!word.capital || word.key in common) return false
    if (cast.knowsName(word.key)) return true
    if (!word.opensSentence) return true
    // A capital that only marks a sentence start: a name when the book never writes the word in lowercase.
    if (cast.isCommon(word.key)) return false
    return pastGender(word.key) == null && word.key !in PERSONS && word.key !in VOICE_NOUNS
}

/** The name starting at [index]: «Лань Си Чэнь», «А-Цин», «Mary». */
private fun nameAt(words: List<Word>, index: Int, cast: Cast, common: Set<String>): List<Word> {
    val name = ArrayList<Word>()
    var at = index
    while (at < words.size && name.size < 4 && isName(words[at], cast, common) && (at == index || !words[at].afterPunctuation)) {
        name += words[at]
        at++
    }
    return name
}

private fun List<Word>.spelled(): String = joinToString(" ") { it.key }

private fun russianSubject(words: List<Word>, cast: Cast, attribution: Boolean): Clue? {
    var name: String? = null
    // Every name before the verb, in order, and where the last of them ends.
    val names = ArrayList<String>()
    var afterNames = 0
    // Someone named before the verb: a pronoun, a noun for a person or a name.
    var subjectGender: SpeechRole? = null
    var subject = false
    // «голос дрогнул»: the next verb agrees with the voice, not with the speaker.
    var voice = false
    var sound = false
    var afterPreposition = false
    var personNoun: String? = null
    var index = skipOpeningClause(words, SUBORDINATE_RU)
    while (index < words.size && index < 16) {
        val word = words[index]
        val key = word.key
        if (index > 0) {
            if (word.afterSentenceEnd) break
            if (word.gap.contains(',') || word.gap.contains(';')) {
                if (key in RELATIVE) {
                    // «Иван, который стоял рядом, покачал головой»: skip to the end of the aside.
                    index++
                    while (index < words.size && !words[index].gap.contains(',') && !words[index].afterSentenceEnd) index++
                    continue
                }
                if (key in CLAUSE_WORDS) break
            }
        }
        val next = words.getOrNull(index + 1)?.takeIf { !it.afterPunctuation }
        val preposition = afterPreposition
        afterPreposition = false
        when {
            // Once someone is named, «его руки» or «у неё» belong to someone else: «Маша, глядя на его руки, покачала головой».
            // «у неё дрогнул голос», «вырвалось у него».
            !subject && key == "у" && next != null && (next.key == "нее" || next.key == "него") ->
                return Clue(if (next.key == "нее") SpeechRole.FEMALE else SpeechRole.MALE)
            // «её голос», «в его тоне», «её отец».
            !subject && (key == "ее" || key == "его") && next != null && !looksLikeVerb(next.key) && next.key !in PREPOSITIONS &&
                !next.capital && next.key !in COMMON_RU -> {
                PERSONS[next.key]?.let { return Clue(it, next.key) }
                return Clue(if (key == "ее") SpeechRole.FEMALE else SpeechRole.MALE)
            }
            // «кричит кто-то из толпы», «чей-то голос», «крикнула одна из девушек».
            key in INDEFINITE -> return Clue(null, anonymous = true)
            (key == "один" || key == "одна") && next?.key == "из" ->
                return Clue(if (key == "одна") SpeechRole.FEMALE else SpeechRole.MALE, anonymous = true)
            key in PERCEPTION -> return Clue(null, anonymous = true)
            // «закричал женский голос», «ответил мужской»: someone new, but the gender is known.
            !subject && key in VOICE_ADJECTIVES -> return Clue(VOICE_ADJECTIVES[key], anonymous = true)
            // «донеслось из кухни», «раздался её голос»: the sound's own gender tells nothing.
            key in SOUND_VERBS -> sound = true
            !subject && key in VOICE_NOUNS -> {
                // «голос Маши», «голос её», «смех девушки»; «голос из толпы» is nobody we know.
                if (next != null) {
                    if (next.key == "из") return Clue(null, anonymous = true)
                    if (next.key == "ее" || next.key == "его") return Clue(if (next.key == "ее") SpeechRole.FEMALE else SpeechRole.MALE)
                    PERSONS_GENITIVE[next.key]?.let { return Clue(it) }
                    if (next.capital) {
                        val owner = nameAt(words, index + 1, cast, COMMON_RU)
                        if (owner.isNotEmpty()) return Clue(cast.genderOf(owner.spelled()), owner.spelled())
                    }
                }
                voice = !preposition
            }
            key in PREPOSITIONS -> afterPreposition = true
            preposition -> Unit
            key == "он" || key == "она" -> {
                if (subjectGender == null) subjectGender = if (key == "он") SpeechRole.MALE else SpeechRole.FEMALE
                subject = true
            }
            (key == "тот" || key == "та") && (index == 0 || pastGender(words[index - 1].key) != null) && next?.let { PERSONS[it.key] } == null -> {
                if (subjectGender == null) subjectGender = if (key == "тот") SpeechRole.MALE else SpeechRole.FEMALE
                subject = true
            }
            key == "я" -> subject = true
            key == "мы" || key == "они" || key == "оба" || key == "обе" -> if (!subject) return null
            key in PERSONS && !(word.capital && !word.opensSentence) -> {
                if (subjectGender == null) {
                    subjectGender = PERSONS[key]
                    // «мама», «старик» stand for a person in the conversation, unless a name follows: «госпожа Юй».
                    personNoun = key
                }
                subject = true
            }
            word.capital && isName(word, cast, COMMON_RU) -> {
                val found = nameAt(words, index, cast, COMMON_RU)
                // «госпожа Юй Цзыюань» is her; in «брат Цзян Яньли» the name is someone else's.
                if (words.getOrNull(index - 1)?.key !in BEFORE_OTHERS_NAME) {
                    if (name == null) name = found.spelled()
                    names += found.spelled()
                    afterNames = index + found.size
                }
                subject = true
                index += found.size
                continue
            }
            else -> {
                // «рявкнули сверху», «позвали его»: someone unnamed; several people acting in narration tell nothing.
                if (pluralPast(key)) return if (!subject && attribution) Clue(null, anonymous = true) else null
                if (REPLY_STEMS.any { key.startsWith(it) } && pastGender(key) == null && subjectGender == null && name == null) {
                    // «отвечает Лань Ванцзи»: a present-tense answer, the name follows.
                    val found = words.getOrNull(index + 1)?.takeIf { !it.afterPunctuation }
                        ?.let { nameAt(words, index + 1, cast, COMMON_RU) }.orEmpty()
                    if (found.isNotEmpty()) return Clue(cast.genderOf(found.spelled()), found.spelled(), reply = true)
                }
                val gender = pastGender(key)
                if (gender != null) {
                    if (voice) {
                        voice = false
                    } else {
                        // A verb before its subject: «кивнула Цзян Яньли», «сказал ему отец».
                        var ahead = index + 1
                        while (ahead < words.size && !words[ahead].afterPunctuation && words[ahead].key in OBJECT_PRONOUNS) ahead++
                        val following = words.getOrNull(ahead)?.takeIf { !it.afterPunctuation }
                        val followingName = following?.let { nameAt(words, ahead, cast, COMMON_RU) }.orEmpty()
                        val followingSubject = followingName.isNotEmpty() || following?.key in PERSONS ||
                            following?.key == "он" || following?.key == "она"
                        // «раздался голос», «крикнул кто-то»: the verb agrees with them, the next words decide.
                        val agreesWithFollowing = !subject && (following?.key in VOICE_NOUNS || following?.key in INDEFINITE ||
                            following?.key in VOICE_ADJECTIVES || following?.key == "один" || following?.key == "одна")
                        if (!agreesWithFollowing && (attribution || subject || followingSubject)) {
                            var verbGender: SpeechRole = gender
                            if (subjectGender == null) {
                                // The verb agrees with its subject: «В Соборе Ванна протянула», «Дункана
                                // окликнула Нина». A verb that agrees with none of the names agrees with a
                                // thing, and the one name tells who: «В голове Дункана всплыла догадка».
                                val after = followingName.takeIf { it.isNotEmpty() }?.spelled()
                                val known = names.filter { cast.genderOf(it) != null }
                                val agreeing = names.lastOrNull { cast.genderOf(it) == gender }
                                    ?: after?.takeIf { cast.genderOf(it) == gender }
                                // «…на плече Дункана и немного удивилась»: a verb joined on by «и» has its subject further back.
                                val joined = words.subList(afterNames, index).any { it.key in COORDINATING }
                                when {
                                    agreeing != null -> name = agreeing
                                    known.size == 1 && after == null && !joined -> {
                                        name = known.single()
                                        verbGender = cast.genderOf(known.single()) ?: gender
                                    }
                                }
                                // «— …, — сказал старушка»: a word for a person tells more than a misprinted verb.
                                if (name == null && after == null) following?.key?.let { PERSONS[it] }?.let { verbGender = it }
                            }
                            if (name == null && followingName.isNotEmpty() && subjectGender == null) name = followingName.spelled()
                            val negated = index > 0 && words[index - 1].key == "не"
                            // «Артём не двинулся с места»: whether he speaks next is not said, only that he did not react.
                            if (negated && !attribution && SPEECH_STEMS.none { key.startsWith(it) }) {
                                return Clue(subjectGender ?: verbGender, name ?: personNoun, still = true)
                            }
                            return Clue(
                                gender = subjectGender ?: verbGender,
                                name = name ?: personNoun ?: following?.key?.takeIf { it in PERSONS },
                                // «ничего не ответила», «промолчал»: this person does not answer.
                                silent = key in SILENCE || negated,
                                reply = REPLY_STEMS.any { key.startsWith(it) },
                                continues = CONTINUE_STEMS.any { key.startsWith(it) },
                                // Only the verb tells: its subject is some word, «пожаловалась жертва», or none is given.
                                weak = subjectGender == null && name == null && !subject && !followingSubject,
                            )
                        }
                    }
                }
            }
        }
        index++
    }
    if (subjectGender != null) return Clue(subjectGender, name ?: personNoun)
    name?.let { return Clue(cast.genderOf(it), it) }
    // «— Ты пришла? — донеслось из кухни»: a voice from somewhere, nobody we can place.
    return if (sound && attribution) Clue(null, anonymous = true) else null
}

/**
 * «И когда она кивнула, он спросил», “When she nodded, he asked”: a clause that sets the time comes
 * first and is about someone else; the index of the word after it, or 0.
 */
internal fun skipOpeningClause(words: List<Word>, openers: Set<String>): Int {
    var at = 0
    if (words.getOrNull(at)?.key in setOf("и", "а", "но", "and", "but")) at++
    if (words.getOrNull(at)?.key !in openers) return 0
    var end = at + 1
    while (end < words.size && !words[end].gap.contains(',') && !words[end].afterSentenceEnd) end++
    return if (end < words.size && words[end].gap.contains(',')) end else 0
}

internal val SUBORDINATE_RU = setOf("когда", "если", "пока", "хотя", "едва", "лишь", "после", "прежде", "раз", "поскольку", "покуда")
internal val SUBORDINATE_EN = setOf("when", "if", "as", "after", "before", "while", "once", "since", "because", "although", "though")

/** Words that may stand between a verb and its subject: «сказал ему отец», «тихо сказала Маша». */
private val OBJECT_PRONOUNS = setOf("ему", "ей", "им", "его", "ее", "их", "мне", "тебе", "нам", "вам", "себе", "меня", "тебя", "нас", "вас")

private val CLAUSE_WORDS_EN = setOf("and", "but", "while", "as", "when", "because", "then", "who", "which", "though")

/** “didn’t answer”, “said nothing”: in narration, this person does not answer. */
private val NEGATIONS_EN = setOf(
    "didn’t", "didn't", "never", "not", "doesn’t", "doesn't", "wasn’t", "wasn't", "couldn’t", "couldn't",
    "wouldn’t", "wouldn't", "nothing",
)
private val SPEECH_EN = setOf("answer", "answered", "reply", "replied", "say", "said", "speak", "spoke", "respond", "responded")

/** “she said”, “said her father”, “Grace said”, “her voice broke”: the first person the words name. */
private fun englishSubject(words: List<Word>, cast: Cast, attribution: Boolean): Clue? {
    val clause = ArrayList<Word>()
    for (word in words.drop(skipOpeningClause(words, SUBORDINATE_EN))) {
        if (clause.isNotEmpty() && (word.afterSentenceEnd || word.gap.contains(',') && word.key in CLAUSE_WORDS_EN)) break
        clause += word
        if (clause.size >= 12) break
    }
    var subject: Clue? = null
    var index = 0
    while (subject == null && index < clause.size) {
        val word = clause[index]
        val key = word.key
        val next = clause.getOrNull(index + 1)?.takeIf { !it.afterPunctuation }
        subject = when {
            key == "he" -> Clue(SpeechRole.MALE)
            key == "she" -> Clue(SpeechRole.FEMALE)
            key == "i" || key == "they" || key == "we" || key == "you" -> return null
            key == "his" && next != null -> PERSONS_EN[next.key]?.let { Clue(it, next.key) } ?: Clue(SpeechRole.MALE)
            // “her voice”, “her mother”; “told her to wait” is an object.
            key == "her" && next != null && next.key !in AFTER_OBJECT_HER ->
                PERSONS_EN[next.key]?.let { Clue(it, next.key) } ?: Clue(SpeechRole.FEMALE).takeIf { next.key in VOICE_NOUNS_EN }
            key in PERSONS_EN -> Clue(PERSONS_EN[key], key)
            // “Barnaby’s sister”: the sister speaks, not Barnaby.
            word.capital && word.text.lowercase().let { it.endsWith("’s") || it.endsWith("'s") } && next?.key in PERSONS_EN ->
                Clue(PERSONS_EN[next!!.key], word.key + " " + next.key)
            word.capital && isName(word, cast, COMMON_EN) -> {
                val found = nameAt(clause, index, cast, COMMON_EN)
                Clue(cast.genderOf(found.spelled()), found.spelled())
            }
            else -> null
        }
        index++
    }
    if (subject == null) return null
    val verbs = clause.take(6).map { it.key }
    val negated = !attribution && clause.take(8).any { it.key in NEGATIONS_EN }
    return when {
        // “She said nothing”: she does not answer. “He didn’t get up”: only that he did not react.
        negated && clause.take(8).any { it.key in SPEECH_EN } -> subject.copy(silent = true)
        negated -> subject.copy(still = true)
        verbs.any { it in REPLY_EN } -> subject.copy(reply = true)
        verbs.any { it in CONTINUE_EN } -> subject.copy(continues = true)
        else -> subject
    }
}

// ---------------------------------------------------------------- the line itself

/**
 * The speaker's gender from their own words: «я пришла», «я уже всё сделал», «я рада», «я сама»,
 * «Видела я таких». Words quoted inside the line belong to someone else and are skipped.
 */
internal fun selfGender(speech: String, language: String): SpeechRole? {
    val words = words(withoutQuotes(speech))
    var female = 0
    var male = 0
    fun count(gender: SpeechRole?) {
        when (gender) {
            SpeechRole.FEMALE -> female++
            SpeechRole.MALE -> male++
            else -> Unit
        }
    }
    for (index in words.indices) {
        val key = words[index].key
        val next = words.getOrNull(index + 1)?.takeIf { !it.afterPunctuation }?.key
        // «Это Миша, мой муж», “my wife and I”.
        if (next != null) SPOUSES[key to next]?.let(::count)
        if (language == "en") continue
        when {
            key == "я" || key == "я-то" -> count(predicateGender(words, index + 1))
            // «Видела я таких», «Говорил я тебе».
            words.getOrNull(index + 1)?.let { it.key == "я" && !it.afterPunctuation } == true &&
                (index == 0 || words[index].afterPunctuation) -> count(pastGender(key))
            // «Сама знаю», «Сам разберусь».
            (key == "сама" || key == "сам") && words[index].opensSentence ->
                words.getOrNull(index + 1)?.takeIf { !it.afterPunctuation && firstPersonPresent(it.key) }?.let {
                    count(if (key == "сама") SpeechRole.FEMALE else SpeechRole.MALE)
                }
        }
    }
    return when {
        female > 0 && male == 0 -> SpeechRole.FEMALE
        male > 0 && female == 0 -> SpeechRole.MALE
        else -> null
    }
}

/** «мой муж» is said by a wife, «моя жена» by a husband. */
private val SPOUSES: Map<Pair<String, String>, SpeechRole> = buildMap {
    for (my in listOf("мой", "моего", "моему", "моим", "моем")) {
        for (husband in listOf("муж", "мужа", "мужу", "мужем", "муже")) put(my to husband, SpeechRole.FEMALE)
    }
    for (my in listOf("моя", "моей", "мою", "моею")) {
        for (wife in listOf("жена", "жены", "жене", "жену", "женой")) put(my to wife, SpeechRole.MALE)
    }
    put("my" to "husband", SpeechRole.FEMALE)
    put("my" to "wife", SpeechRole.MALE)
}

private fun firstPersonPresent(key: String) =
    key.length >= 3 && (key.endsWith("ю") || key.endsWith("у") || key.endsWith("юсь") || key.endsWith("усь"))

/**
 * The gender of the predicate after «я» or «ты»: «я уже сделала», «я, кажется, опоздала»,
 * «ты сама», «я совсем не такая», «я твоя жена».
 */
private fun predicateGender(words: List<Word>, from: Int): SpeechRole? {
    var aside = false
    var onlyIntensifiers = true
    for (index in from until min(words.size, from + 7)) {
        val word = words[index]
        val key = word.key
        // «Я — твоя мать».
        if (index == from && word.gap.any { it in "—–" }) {
            val noun = words.getOrNull(index + if (key in POSSESSIVES) 1 else 0)
            return noun?.let { PERSONS[it.key] }
        }
        if (word.gap.any { it in ".!?…;:—–«»“”\"()" }) return null
        if (word.gap.contains(',') && key !in PARENTHETICAL && !aside) return null
        aside = key in PARENTHETICAL
        if (aside) continue
        if (key in PREDICATE_STOPS) return null
        if (word.capital) {
            onlyIntensifiers = false
            continue
        }
        pastGender(key)?.let { return it }
        SHORT_FORMS[key]?.let { return it }
        if (onlyIntensifiers) {
            if (key.length >= 4 && (key.endsWith("ая") || key.endsWith("яя"))) return SpeechRole.FEMALE
            if (key.length >= 4 && key.endsWith("ый") || key in MASCULINE_OI) return SpeechRole.MALE
        }
        if (index == from || words[index - 1].key in POSSESSIVES) PERSONS[key]?.let { return it }
        if (key.endsWith("ли") && key.length >= 4 || key.endsWith("лись")) return null
        if (key !in INTENSIFIERS) onlyIntensifiers = false
    }
    return null
}

private val POSSESSIVES = setOf("твоя", "твой", "его", "ее", "ваша", "ваш", "их", "наша", "наш", "моя", "мой", "своя", "свой")

/** Drops quotations inside a line: «А он мне: "Я пришёл"» — those words are someone else's. */
private fun withoutQuotes(text: String): String =
    text.replace(Regex("«[^«»]*»|“[^“”]*”|„[^„“”]*[“”]|\"[^\"]*\""), " ")

/**
 * A form of address in a line: its gender when known, whom it calls — a name («лань чжань») or a
 * word for a person («шицзе», «мама») — and whether it opens the line.
 */
internal class Vocative(val gender: SpeechRole?, val name: String?, val opening: Boolean, val proper: Boolean)

/** «Сестрица, …», «…, мама!», «Лань Чжань, иди сюда», «Госпожа Юй, …», “Mom, …”: phrases that only address someone. */
internal fun vocatives(speech: String, language: String, cast: Cast): List<Vocative> {
    val words = words(withoutQuotes(speech))
    val english = language == "en"
    val persons = if (english) PERSONS_EN else PERSONS
    val common = if (english) COMMON_EN else COMMON_RU
    val address = if (english) ADDRESS_EN else ADDRESS
    val found = ArrayList<Vocative>()
    fun vocative(from: Int, until: Int) {
        val phrase = words.subList(from, until)
        if (phrase.isEmpty() || phrase.size > 4) return
        var gender: SpeechRole? = null
        var person: String? = null
        val name = ArrayList<Word>()
        for (word in phrase) {
            val key = word.key
            when {
                key in address -> {
                    gender = gender ?: address[key]
                    person = person ?: ADDRESSED_PERSON[key]
                }
                key in persons -> {
                    gender = gender ?: persons[key]
                    person = person ?: key
                }
                key in POSSESSIVES || key == "my" -> Unit
                word.capital && isName(word, cast, common) -> name += word
                else -> return
            }
        }
        val spelled = name.takeIf { it.isNotEmpty() }?.spelled()
        val known = gender ?: spelled?.let { cast.genderOf(it) }
        if (known != null || spelled != null) found += Vocative(known, spelled ?: person, opening = from == 0, proper = spelled != null)
    }
    var phraseStart = 0
    for (index in words.indices) {
        val word = words[index]
        if (index > 0 && word.afterPunctuation) {
            // A form of address stands apart, between the start of a sentence or a comma and a comma or its end.
            val opens = words[phraseStart].opensSentence || words[phraseStart].gap.contains(',')
            if (opens && (word.afterSentenceEnd || word.gap.any { it in ",!?" })) vocative(phraseStart, index)
            phraseStart = index
        }
    }
    if (words.isNotEmpty() && (words[phraseStart].opensSentence || words[phraseStart].gap.contains(','))) {
        vocative(phraseStart, words.size)
    }
    return found
}

/**
 * Whom a line speaks to, when the words tell: «Ты пришла?», «Сестрица, …», «…, мама!», «Лань Чжань,
 * иди сюда», “Mom, …”.
 */
internal fun addresseeGender(
    speech: String,
    language: String,
    cast: Cast,
    calls: List<Vocative> = vocatives(speech, language, cast),
): SpeechRole? {
    var female = 0
    var male = 0
    fun count(gender: SpeechRole?) {
        when (gender) {
            SpeechRole.FEMALE -> female++
            SpeechRole.MALE -> male++
            else -> Unit
        }
    }
    if (language != "en") {
        val words = words(withoutQuotes(speech))
        words.indices.filter { words[it].key == "ты" }.forEach { count(predicateGender(words, it + 1)) }
        // «— Уверена?», «— Готов?»: a short question about the one asked.
        val first = words.firstOrNull()
        if (first != null && words.size <= 4 && speech.trimEnd().endsWith('?')) SHORT_FORMS[first.key]?.let(::count)
    }
    calls.forEach { count(it.gender) }
    return when {
        female > 0 && male == 0 -> SpeechRole.FEMALE
        male > 0 && female == 0 -> SpeechRole.MALE
        else -> null
    }
}

/** Words of address that are not nouns for people: «милая», «дорогой», «мам». */
private val ADDRESS: Map<String, SpeechRole> = run {
    val female = "милая дорогая любимая родная мам мамуль бабуль теть сестренка сестричка дочка доченька красавица " +
        "госпожа барышня девушка"
    val male = "милый дорогой любимый родной пап дядь братишка сынок старик приятель дружище господин"
    female.split(' ').associateWith { SpeechRole.FEMALE } + male.split(' ').associateWith { SpeechRole.MALE }
}

/** «Мам, …» calls the one the narration calls «мама». */
private val ADDRESSED_PERSON = mapOf(
    "мам" to "мама", "мамуль" to "мама", "пап" to "папа", "бабуль" to "бабушка", "дядь" to "дядя", "теть" to "тетя",
    "mom" to "mother", "mum" to "mother", "dad" to "father",
)

private val ADDRESS_EN: Map<String, SpeechRole> = mapOf(
    "mom" to SpeechRole.FEMALE, "mum" to SpeechRole.FEMALE, "mother" to SpeechRole.FEMALE, "ma’am" to SpeechRole.FEMALE,
    "ma'am" to SpeechRole.FEMALE, "madam" to SpeechRole.FEMALE, "miss" to SpeechRole.FEMALE, "lady" to SpeechRole.FEMALE,
    "sister" to SpeechRole.FEMALE, "grandma" to SpeechRole.FEMALE, "auntie" to SpeechRole.FEMALE,
    "dad" to SpeechRole.MALE, "father" to SpeechRole.MALE, "sir" to SpeechRole.MALE, "lord" to SpeechRole.MALE,
    "son" to SpeechRole.MALE, "brother" to SpeechRole.MALE, "grandpa" to SpeechRole.MALE, "uncle" to SpeechRole.MALE,
)

/** A line that carries on a story: «Во-вторых…», «А потом…», “And then…”. */
internal fun continuesStory(speech: String, language: String): Boolean {
    // «В—шестых»: some books join the words with a dash instead of a hyphen.
    val start = words(speech.replace(Regex("(?<=\\p{L})[—–](?=\\p{L})"), "-")).take(3).joinToString(" ") { it.key }
    val markers = if (language == "en") CONTINUE_MARKERS_EN else CONTINUE_MARKERS_RU
    return markers.any { start == it || start.startsWith("$it ") }
}

private val CONTINUE_MARKERS_RU = listOf(
    "во-вторых", "в-третьих", "в-четвертых", "в-пятых", "в-шестых", "в-седьмых", "в-восьмых", "кроме того",
    "более того", "к тому же", "а потом", "и потом", "после этого", "так вот", "итак", "а еще", "и еще", "а главное", "и главное", "а дальше",
    "и тогда", "и вот",
)

private val CONTINUE_MARKERS_EN = listOf(
    "and then", "after that", "besides", "moreover", "secondly", "thirdly", "and so", "anyway", "what's more",
    "what’s more",
)

/**
 * The gender of a line's speaker from the author's words next to it: «сказала она», «ответил он»,
 * «кивнула Маша», “she whispered”. Null when the words do not tell.
 */
internal fun speakerGender(authorWords: String, language: String): SpeechRole? =
    subjectClue(authorWords, language, Cast.EMPTY, attribution = true)?.gender

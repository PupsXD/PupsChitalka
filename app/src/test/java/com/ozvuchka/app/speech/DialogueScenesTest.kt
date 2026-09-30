package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenes in the styles people listen to — translated Chinese novels, Russian prose, monologues,
 * quoted speech, present tense, English — with the speaker's gender of every paragraph marked by
 * hand: M and F are characters' lines, N is narration, ? is a line whose speaker the text does not
 * reveal. The test reports how many lines get the right voice, the narrator's voice (unknown) or
 * the wrong one.
 */
class DialogueScenesTest {
    private class Score(
        var right: Int = 0,
        var unknown: Int = 0,
        var wrong: Int = 0,
        var missed: Int = 0,
        /** Narration read in a character's voice. */
        var narration: Int = 0,
    ) {
        operator fun plusAssign(other: Score) {
            right += other.right
            unknown += other.unknown
            wrong += other.wrong
            missed += other.missed
            narration += other.narration
        }

        override fun toString() = "right $right, narrator $unknown, wrong $wrong, not split $missed, narration voiced $narration"
    }

    private fun score(name: String, scene: String, report: StringBuilder): Score {
        val lines = scene.trimIndent().lines().filter { it.isNotBlank() }
        val labels = lines.map { it.substringBefore(' ') }
        val paragraphs = lines.map { it.substringAfter(' ') }
        // Names are learned from the scene the way narration learns them from the whole book.
        val segments = chapterSegments(0, null, paragraphs, splitDialogue = true, cast = Cast.learn(paragraphs))
        val score = Score()
        labels.forEachIndexed { index, label ->
            val roles = segments.filter { it.paragraph == index }.map { it.role }.filter { it != SpeechRole.NARRATOR }.toSet()
            val expected = when (label) {
                "M" -> SpeechRole.MALE
                "F" -> SpeechRole.FEMALE
                "N" -> {
                    if (roles.isNotEmpty()) {
                        score.narration++
                        report.appendLine("  $name #$index: narration voiced as $roles — ${paragraphs[index].take(60)}")
                    }
                    return@forEachIndexed
                }
                else -> return@forEachIndexed
            }
            when {
                roles.isEmpty() -> {
                    score.missed++
                    report.appendLine("  $name #$index: not split — ${paragraphs[index].take(60)}")
                }
                roles == setOf(expected) -> score.right++
                roles == setOf(SpeechRole.SPEECH) -> {
                    score.unknown++
                    report.appendLine("  $name #$index: narrator (expected $label) — ${paragraphs[index].take(60)}")
                }
                else -> {
                    score.wrong++
                    report.appendLine("  $name #$index: $roles (expected $label) — ${paragraphs[index].take(60)}")
                }
            }
        }
        return score
    }

    private fun total(scenes: List<Pair<String, String>>, title: String): Pair<Score, String> {
        val report = StringBuilder()
        val total = Score()
        scenes.forEach { (name, scene) -> total += score(name, scene, report) }
        println("$title: $total")
        println(report)
        return total to report.toString()
    }

    @Test
    fun scenes() {
        val (total, report) = total(scenes, "Dialogue scenes")
        assertEquals("Lines voiced with the wrong gender:\n$report", 0, total.wrong)
        assertEquals("Narration voiced as a character:\n$report", 0, total.narration)
    }

    /**
     * The scenes written apart from the rules. Where the text truly leaves it open the narrator
     * reads; the wrong voice stays rare. The earlier rules, which took the gender from the first
     * words after the dash and otherwise from the line two before, gave 195 of these lines the
     * right voice and 37 the wrong one.
     */
    @Test
    fun moreScenes() {
        val (total, report) = total(moreScenes, "More dialogue scenes")
        assertTrue("Too many lines with the wrong gender (${total.wrong}):\n$report", total.wrong <= 7)
        assertTrue("Too few lines with the right gender (${total.right}):\n$report", total.right >= 340)
        assertEquals("Narration voiced as a character:\n$report", 0, total.narration)
    }

    private val scenes = listOf(
        // A woman says one line in a men's argument; her action stands in its own paragraph.
        "lotus pier" to """
            N Во дворе Пристани Лотосов было шумно: младшие ученики гоняли по камням мяч.
            M — Хватит спорить, — сказал Цзян Чэн.
            M — Я и не спорю, — возразил Вэй Усянь. — Я просто говорю, что ты не прав.
            N Цзян Яньли поставила на стол миску с супом из корней лотоса.
            F — Ешьте, пока горячий.
            M — Шицзе! — Вэй Усянь просиял и первым схватил ложку.
            M — Опять ты его балуешь, — проворчал Цзян Чэн.
            F — А-Чэн, тебе тоже хватит, — мягко улыбнулась она.
            N Братья переглянулись и молча взялись за ложки.
        """,
        // A woman's story over several paragraphs, answered by a man.
        "investigator" to """
            M — Расскажите, как всё было, — попросил следователь.
            N Женщина долго молчала, теребя край платка.
            F — Я пришла домой около девяти. Муж ещё не вернулся с работы, и я поставила чайник, как обычно. Включила радио, чтобы не было так тихо, и начала резать хлеб к ужину. Всё было как всегда, понимаете? Ничего не предвещало беды.
            F — А потом раздался звонок в дверь. Я открыла, но на пороге никого не было, только на коврике лежал конверт. Обычный белый конверт, без марки и без адреса. Я подумала, что это соседи что-то перепутали.
            F — Внутри лежала фотография. Наша старая фотография, ещё со свадьбы, которую мы давно потеряли при переезде. Кто-то аккуратно вырезал из неё лицо мужа, понимаете? Аккуратно, ножницами, по контуру.
            M — Вы сохранили конверт?
            F — Да. Он в сумке.
            M — Покажите.
        """,
        // A daughter answers her father's question at length.
        "refusal" to """
            M — Ну и почему ты отказалась? — спросил отец.
            F — Во-первых, мне не нравится этот город. Там сыро, серо и нет ни одного знакомого лица, а начинать всё с нуля в тридцать лет не хочется.
            F — Во-вторых, зарплата там почти такая же, как здесь. Переезд съест всё, что я могла бы накопить за первый год, и ради чего?
            M — Ради опыта, например.
            F — Опыт можно получить и здесь.
        """,
        // Long Chinese names and titles push the verb far from the dash.
        "council" to """
            N Главы орденов собрались в зале Ясного Неба.
            M — Орден Вэнь не остановится, — мрачно произнёс Лань Цижэнь.
            M — Тогда мы должны ударить первыми, — Не Минцзюэ сжал кулак.
            M — Брат, не горячись, — Не Хуайсан нервно обмахнулся веером.
            F — Совет прав, — госпожа Юй Цзыюань холодно оглядела собравшихся. — Ждать больше нельзя.
            M — Госпожа Юй, — Цзян Фэнмянь покачал головой, — не стоит решать сгоряча.
            F — Сгоряча? — Её голос зазвенел от гнева. — Они сожгли Облачные Глубины!
        """,
        // Present tense: verbs do not tell the gender, names learned from the rest of the text do.
        "rooftop" to """
            N Вэй Ин лежит на крыше и смотрит в небо. Он давно не видел столько звёзд.
            N Лань Ванцзи стоит внизу. Он не любит высоту.
            M — Лань Чжань, иди сюда, — зовёт он.
            M — Спускайся, — отвечает Лань Ванцзи. — Скоро отбой.
            M — Ещё немного!
            N Вэнь Цин выходит во двор и останавливается под деревом. Она кутается в плащ.
            F — Вы оба невыносимы, — говорит Вэнь Цин.
            M — Вэнь Цин! Ты тоже пришла посмотреть на звёзды?
            F — Я пришла за вами.
        """,
        // A story in quotes over several paragraphs: only the last one closes the quote.
        "old man" to """
            N Старик помолчал, глядя в огонь, а потом заговорил:
            M «Было это давно, ещё до войны. Жили мы тогда в деревне у самой реки, и каждую весну вода подходила к порогу.
            M «Однажды ночью разбудил меня отец. Говорит: собирайся, уходим. Я спросонья ничего не понял.
            M «А утром от нашего дома осталась только печная труба».
            N Мальчик слушал, затаив дыхание.
            M «А дальше что?» — спросил он.
        """,
        // English names learn their gender from the pronouns around them.
        "rain" to """
            N Mary put down her cup and looked at the window.
            F “It’s going to rain,” she said.
            M “It always rains here,” John replied. He folded the newspaper.
            F “Then why did we come?”
            M “Because your mother asked us to.”
            N Mary sighed. She knew he was right.
            F “Fine. But I’m not staying for dinner.”
        """,
        // A woman tells the story herself.
        "first person" to """
            N Я вернулась домой поздно.
            M — Где ты была? — спросил брат, не отрываясь от телефона.
            F — Гуляла, — ответила я.
            M — До полуночи?
            F — А тебе какое дело?
            N Он наконец поднял голову.
            M — Мама звонила. Три раза.
            F — И что ты ей сказал?
            M — Что ты спишь.
        """,
        // He goes on after a pause; the next reply is hers again.
        "north" to """
            M — Я уезжаю, — сказал Андрей.
            F — Куда? — Лена отложила книгу.
            M — На север. Контракт на два года.
            N Он помолчал, разглядывая свои руки.
            M — Я хотел сказать раньше, но всё не находил момента.
            F — Два года, — повторила она. — Ты даже не спросил, что я думаю.
            M — А что ты думаешь?
            F — Я думаю, что ты трус.
        """,
        // Web-novel style: the author's words come first and the line follows a dash.
        "yi city" to """
            M Сюэ Ян рассмеялся. — Даочжан, ты слишком серьёзен!
            M — А ты слишком беспечен, — Сяо Синчэнь покачал головой.
            F А-Цин фыркнула: — Оба хороши.
            M — Ты подслушивала? — Сюэ Ян прищурился.
            F — Больно надо. Я просто мимо шла.
            M — Мимо, — усмехнулся Сяо Синчэнь. — Конечно.
        """,
        // An English monologue in quotes that stay open between paragraphs.
        "orchard" to """
            N The old woman settled into her chair.
            F “When I was a girl, this whole valley was orchards. Apples, mostly, and a few pears near the river.
            F “Every autumn the whole village came out for the harvest. We children were given baskets and told to pick up the windfalls.
            F “Those were good years.”
            N Tom leaned forward, resting his elbows on his knees.
            M “What happened to the orchards?” Tom asked.
            F She shrugged. “The river flooded, and then nobody wanted to replant.”
        """,
        // A question to «сестрица» is answered by her.
        "basket" to """
            M — Сестрица, ты тоже идёшь с нами? — спросил Вэй Усянь.
            F — Конечно.
            M — Тогда я понесу твою корзину!
            M — Размечтался, — фыркнул Цзян Чэн. — Корзину понесу я.
        """,
        // Author's words that name someone else, a voice or a hand rather than the speaker.
        "gate" to """
            N Маша стояла у калитки.
            M — Уходи, — сказал Иван, и она отвернулась.
            F — Постой! — у неё дрогнул голос.
            M — Что ещё? — Иван обернулся.
            F — Нет, ничего, — вырвалось у неё.
            M — Тогда до завтра, — его рука легла ей на плечо.
            F — До завтра, — голос Маши прозвучал совсем тихо.
        """,
        // A man's story in two paragraphs; the verb stands far from «я».
        "war" to """
            F — Расскажи мне про войну, — попросила Аня.
            M — Нечего рассказывать. Мы сидели в окопах, мёрзли и ждали приказа. Иногда стреляли, чаще просто ждали. Я за всю зиму ни разу не видел противника в лицо, представляешь?
            M — Один раз нас накрыло артиллерией. Я тогда потерял двоих друзей, с которыми вырос в одном дворе. После этого перестал запоминать имена новичков, чтобы не было так больно.
            F — Прости.
            M — Не за что.
        """,
        // Two men go on talking after a woman has left.
        "cards" to """
            F — Я пойду спать, — сказала Катя и вышла.
            M — Ну что, продолжим? — спросил Олег.
            M — Давай, — ответил Сергей.
            M — Твой ход.
            M — Знаю, — огрызнулся тот.
            M — Не злись.
            M — Я не злюсь.
        """,
        // She does not answer, so he goes on.
        "silence" to """
            M — Ты знала? — спросил он.
            N Она ничего не ответила.
            M — Ты знала и молчала!
            F — Я не могла сказать.
        """,
        // A woman's line with no author's words at all; only her own words tell.
        "tavern" to """
            N В трактире было людно и душно.
            M — Ещё вина! — крикнул Вэй Ин.
            M — Тебе хватит, — буркнул Цзян Чэн.
            F — Я бы тоже не отказалась.
            N Братья обернулись. У стойки стояла Вэнь Цин.
            M — Ты что здесь делаешь? — изумился Вэй Ин.
            F — То же, что и вы. Отдыхаю.
        """,
        // English names after «said».
        "letter" to """
            N Oliver pushed his glasses up and stared at the letter.
            N Grace leaned over her book, pretending not to look.
            M “It’s from the headmaster,” said Oliver.
            F “What does it say?” asked Grace.
            M “He wants to see me. Tonight.”
            F “Then you should go,” Grace said. She closed her book.
        """,
        // A girl and her uncle, the way translated novels write them: the narration before a colon
        // tells who speaks whoever acted a sentence earlier, the author's words after a line go on
        // into the next one, and a name once used in words about someone else keeps her voice.
        "uncle Wen Shu" to """
            N Вэнь Шу вернулся поздно и застал племянницу за учебниками.
            M — Опять до ночи сидишь? — спросил он.
            F — Завтра экзамен, — ответила Лянь Хуа, не поднимая головы.
            N Вэнь Шу погладил её по голове, а она не стала противиться. Она энергично помахала рукой и встретилась с ним взглядом:
            F — Просто мне в последнее время плохо спится.
            M — Кошмары? — нахмурился он.
            F — Не совсем, — покачала головой Лянь Хуа. Затем она отложила кисть, подвинула к себе чашку с остывшим чаем и надолго задумалась, разглядывая узор на фарфоре.
            F — Мне снится один и тот же двор, и там кто-то зовёт меня по имени.
            N В голове Вэнь Шу мгновенно всплыла догадка, и он, повернув голову, посмотрел на окно.
            M — Ты видела этот двор наяву.
            M — Ты устала, — сказал Вэнь Шу, взглянув на её бледное лицо. Он забрал у неё чашку и, помолчав, добавил:
            M — Завтра же пойдём к лекарю.
        """,
        // Words about someone else once gave her name to a man's voice, and every later line of hers
        // with her name went on in it.
        "secrets" to """
            N Цинь Лан долго рассказывал о своих странствиях, но так и не сказал, откуда у него шрам.
            M — Вот и всё, что было на юге.
            N Сун Юэ уже мало волновало, какие тайны он хранит.
            F — Можно я пойду спать?
            M — Иди, — кивнул Цинь Лан.
            F — Ах, нет-нет, — Сун Юэ торопливо замахала руками. — Посижу ещё.
        """,
        // Rules told one by one, «В—шестых» written with a dash: the list goes on with the same voice.
        "ship's rules" to """
            N Боцман Гао усадил новенькую на ящик и начал загибать пальцы.
            M — Во—первых, капитан всегда прав. Во—вторых, за штурвал без приказа не встают.
            F — А в—третьих? — спросила Мэй.
            M — В—третьих, в трюм по ночам не спускаются, что бы оттуда ни звали.
            M — В—четвёртых, за борт ничего не бросают.
            M — В—пятых, дверь капитанской каюты открывают только снаружи.
            M — В—шестых, правил на корабле ровно шесть.
            N Мэй нахмурилась, загибая пальцы вслед за ним, и вдруг сообразила, что что-то не сходится:
            F — Подождите, вы же сказали только что...
        """,
        // Quoted lines with the author's words after them; a book title is not a line.
        "evening" to """
            N Вечером отец читал вслух «Трёх мушкетёров».
            F «Можно ещё одну главу?» — попросила Соня.
            M «Завтра», — отец закрыл книгу.
            F «Ну пожалуйста!»
            M «Спать», — строго сказал он.
        """,
    )
}

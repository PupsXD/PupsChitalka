package com.ozvuchka.app.speech

/**
 * What a name says by itself, before the book says anything: a patronymic («Сергеевна», «Палыч»),
 * a common first name («Галя», «Андрей», “Grace”), a Chinese form of address joined to a name
 * («Линь-сюн», «Цзян-гунян»). Names worn by both sexes — Саша, Женя, Валя, Sam, Alex — tell nothing.
 */
internal fun nameGender(key: String): SpeechRole? {
    if (key.length < 2) return null
    FIRST_NAMES[key]?.let { return it }
    if ('-' in key) HONORIFICS[key.substringAfterLast('-')]?.let { return it }
    if (key.length >= 6) {
        if (FEMALE_PATRONYMIC.any { key.endsWith(it) }) return SpeechRole.FEMALE
        if (MALE_PATRONYMIC.any { key.endsWith(it) }) return SpeechRole.MALE
    }
    // «Палыч», «Саныч», «Ильич», «Кузьмич».
    if (key.length >= 5 && (key.endsWith("ыч") || key.endsWith("ьич") || key.endsWith("мич") || key.endsWith("кич"))) return SpeechRole.MALE
    return null
}

private val FEMALE_PATRONYMIC = listOf(
    "овна", "евна", "ична", "овны", "евны", "ичны", "овне", "евне", "ичне", "овну", "евну", "ичну", "овной", "евной", "ичной",
)
private val MALE_PATRONYMIC = listOf(
    "ович", "евич", "овича", "евича", "овичу", "евичу", "овичем", "евичем", "овиче", "евиче",
)

/** Chinese forms of address written after a name with a hyphen. */
private val HONORIFICS: Map<String, SpeechRole> = run {
    val female = "цзе цзецзе мэй мэймэй гунян гуньян фужэнь сяоцзе нян няннян шицзе шимэй шинян эцзе"
    val male = "сюн гэ гэгэ ди диди цзюнь гунцзы шушу бобо даочжан шисюн шиди дагэ эргэ саньгэ сюнди лан"
    female.split(' ').associateWith { SpeechRole.FEMALE } + male.split(' ').associateWith { SpeechRole.MALE }
}

private val FIRST_NAMES: Map<String, SpeechRole> = run {
    val female = "александра алена алина алиса алла анастасия ангелина анна антонина арина валентина валерия варвара " +
        "вера вероника виктория галина дарья диана ева евгения екатерина елена елизавета жанна зинаида зоя инна ирина " +
        "карина кира клавдия кристина ксения лариса лидия любовь людмила маргарита марина мария милана надежда наталья " +
        "наталия нина оксана олеся ольга полина раиса регина светлана снежана софья софия таисия тамара татьяна ульяна " +
        "юлия яна аля аленка анюта аня анька ася варя вика галя галочка даша дашка катя катька лена ленка леночка лиза " +
        "лизка лида люба люда маша машка маруся надя наташа наташка нинка оля олька поля рита света светка соня сонечка " +
        "таня танька тома уля юля юлька зина клава ксюша лера мила настя настенька ира ирка " +
        "mary patricia jennifer linda elizabeth barbara susan jessica sarah karen lisa nancy betty sandra margaret " +
        "ashley kimberly emily donna michelle carol amanda melissa deborah stephanie dorothy rebecca sharon laura " +
        "cynthia amy kathleen angela shirley brenda emma anna pamela nicole samantha katherine christine helen rachel " +
        "carolyn janet maria catherine heather diane olivia julie joyce victoria ruth virginia lauren christina joan " +
        "evelyn judith andrea hannah megan martha teresa gloria sara janice ann kathryn abigail sophia sophie frances " +
        "alice judy isabella julia grace amber denise danielle beverly charlotte natalie diana doris marie imogen nell " +
        "ellen eleanor clara lucy molly rose edith agnes florence harriet jane kate kitty lily maggie mabel peggy polly " +
        "sally susie violet"
    val male = "александр алексей альберт анатолий андрей антон аркадий арсений артем артур богдан борис вадим валерий " +
        "василий вениамин виктор виталий владимир владислав всеволод вячеслав геннадий георгий герман глеб григорий " +
        "давид даниил данила денис дмитрий евгений егор иван игорь илья иннокентий иосиф кирилл клим константин лев " +
        "леонид макар максим марк матвей михаил никита николай олег павел петр платон прохор роберт родион роман " +
        "ростислав руслан савелий святослав семен сергей станислав степан тарас тимофей тимур федор филипп эдуард юрий " +
        "яков ярослав саня леша лешка алеша андрюша антоша боря вадик вася витя вова володя вовка гоша гриша дима " +
        "димка ваня ванька илюша костя коля кирюша лева леня миша мишка паша петя петька рома ромка сережа сема степа " +
        "тима федя юра юрка яша гена жора толя эдик стас влад " +
        "james john robert michael william david richard joseph thomas charles christopher daniel matthew anthony mark " +
        "donald steven paul andrew joshua kenneth kevin brian george timothy ronald edward jason jeffrey ryan jacob gary " +
        "nicholas eric jonathan stephen larry justin scott brandon benjamin samuel gregory alexander frank patrick " +
        "raymond jack dennis jerry tyler aaron adam nathan henry douglas zachary peter kyle noah ethan jeremy walter " +
        "keith roger austin sean gerald carl harold dylan arthur lawrence bryan billy joe bruce gabriel logan albert " +
        "eugene russell vincent philip bobby johnny bradley roy ralph louis harry oliver tom tommy felix hugo barnaby " +
        "edmund ernest fred freddie jim jimmy ned ted teddy will bill ben dick"
    female.split(' ').associateWith { SpeechRole.FEMALE } + male.split(' ').associateWith { SpeechRole.MALE }
}

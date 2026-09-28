package com.ozvuchka.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ozvuchka.app.speech.DialogueMode
import com.ozvuchka.app.speech.DialogueVoices
import com.ozvuchka.app.speech.ModelInstallState
import com.ozvuchka.app.speech.SpeechModel
import com.ozvuchka.app.speech.SpeechRole
import com.ozvuchka.app.speech.SystemVoices
import com.ozvuchka.app.speech.VoiceCatalog
import com.ozvuchka.app.speech.VoiceChoice
import com.ozvuchka.app.speech.VoiceEngine
import com.ozvuchka.app.speech.VoicePreset
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsSheet(
    state: VoiceSettingsUi,
    initialLanguage: String,
    actions: VoiceSettingsActions,
    onDismiss: () -> Unit,
) {
    var language by rememberSaveable { mutableStateOf(initialLanguage) }
    ModalBottomSheet(
        onDismissRequest = {
            actions.stopPreview()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("Голоса и озвучка", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Язык каждой фразы определяется автоматически: русские фразы читает русский голос, английские — английский.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = language == "ru",
                        onClick = { language = "ru" },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Русский") }
                    SegmentedButton(
                        selected = language == "en",
                        onClick = { language = "en" },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("English") }
                }
            }
            val selected = if (language == "en") state.englishVoice else state.russianVoice
            item {
                CurrentVoiceCard(
                    label = if (language == "en") state.englishVoiceLabel else state.russianVoiceLabel,
                    voice = selected,
                    language = language,
                    state = state,
                    actions = actions,
                )
            }
            item { EnginePicker(state, language, selected, actions) }
            item { EngineVoices(state, language, selected, actions) }
            item { DialogueVoicesCard(state, language, selected, actions) }
            item {
                SettingsLabel("Темп · ${speechSpeedLabel(state.speed)}")
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: state.speed,
                    onValueChange = { dragging = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = {
                        dragging?.let(actions::setSpeed)
                        dragging = null
                    },
                    valueRange = 0.6f..2f,
                )
                Text(
                    "Темп меняет сама модель, поэтому высота голоса не искажается.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SettingsLabel("Паузы между фразами и абзацами")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.7f to "Короткие", 1f to "Обычные", 1.35f to "Длинные").forEach { (scale, title) ->
                        FilterChip(
                            selected = abs(state.pauseScale - scale) < 0.01f,
                            onClick = { actions.setPauseScale(scale) },
                            label = { Text(title) },
                        )
                    }
                }
            }
            item {
                ToggleRow(
                    title = "Полноточные модели",
                    subtitle = "Если скачаны обе версии модели, читать полноточной: звук чище, памяти нужно больше",
                    checked = state.preferFullModels,
                    onChange = actions::setPreferFullModels,
                )
            }
        }
    }
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun CardTitle(title: String, badge: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (badge != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CurrentVoiceCard(
    label: String,
    voice: VoiceChoice,
    language: String,
    state: VoiceSettingsUi,
    actions: VoiceSettingsActions,
) {
    SheetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (language == "en") "Английский текст читает" else "Русский текст читает",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (state.previewVoice == voice) {
                IconButton(onClick = actions::stopPreview) { Icon(Icons.Filled.Stop, contentDescription = "Остановить пример") }
            } else {
                IconButton(onClick = { actions.preview(language, voice) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Прослушать")
                }
            }
        }
    }
}

/** One synthesizer the reader can use for a language: a downloadable model or an Android TTS engine. */
private class EngineOption(
    val key: String,
    val title: String,
    val subtitle: String,
    val installed: Boolean,
    val choice: VoiceChoice,
    val recommended: Boolean = false,
)

private fun engineKey(voice: VoiceChoice): String = when (voice.engine) {
    VoiceEngine.SUPERTONIC -> "supertonic"
    VoiceEngine.KOKORO -> "kokoro"
    VoiceEngine.SYSTEM -> "system:${voice.enginePackage}"
}

private fun engineOptions(state: VoiceSettingsUi, language: String): List<EngineOption> = buildList {
    val models = state.installedModels
    if (language == "ru") {
        add(
            EngineOption(
                key = "system:${VoiceCatalog.RUVOICE_PACKAGE}",
                title = "RuVoice · Silero v5",
                subtitle = if (state.ruVoiceInstalled) "Живая интонация, ударения по контексту" else "Не установлен · нажмите, чтобы скачать",
                installed = state.ruVoiceInstalled,
                choice = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = VoiceCatalog.RUVOICE_PACKAGE),
                recommended = true,
            ),
        )
    } else {
        val kokoro = SpeechModel.KOKORO in models || SpeechModel.KOKORO_FULL in models
        add(
            EngineOption(
                key = "kokoro",
                title = "Kokoro v1.0",
                subtitle = if (kokoro) "Естественная английская речь" else "Нужно скачать модель, ≈ 350 МБ",
                installed = kokoro,
                choice = VoiceChoice(VoiceEngine.KOKORO, 3),
                recommended = true,
            ),
        )
    }
    val supertonic = SpeechModel.SUPERTONIC in models || SpeechModel.SUPERTONIC_FULL in models
    add(
        EngineOption(
            key = "supertonic",
            title = "Supertonic 3",
            subtitle = if (supertonic) "Встроенный, 10 голосов, очень быстрый" else "Нужно скачать модель, ≈ 130 МБ",
            installed = supertonic,
            choice = VoiceChoice(VoiceEngine.SUPERTONIC, 0),
        ),
    )
    state.systemEngines.filter { it.packageName != VoiceCatalog.RUVOICE_PACKAGE }.forEach { engine ->
        add(
            EngineOption(
                key = "system:${engine.packageName}",
                title = SystemVoices.engineTitle(engine.packageName, engine.label),
                subtitle = "Системный движок Android",
                installed = true,
                choice = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = engine.packageName),
            ),
        )
    }
}

@Composable
private fun EnginePicker(state: VoiceSettingsUi, language: String, selected: VoiceChoice, actions: VoiceSettingsActions) {
    SheetCard {
        CardTitle("Движок", null)
        val selectedKey = engineKey(selected)
        engineOptions(state, language).forEach { option ->
            val isSelected = option.key == selectedKey
            val onSelect = {
                when {
                    isSelected -> Unit
                    option.choice.enginePackage == VoiceCatalog.RUVOICE_PACKAGE && !option.installed -> actions.openRuVoicePage()
                    else -> {
                        actions.selectVoice(language, option.choice)
                        if (option.choice.engine == VoiceEngine.SYSTEM) actions.loadEngineVoices(option.choice.enginePackage)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = onSelect)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            option.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (option.recommended) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Filled.Star, contentDescription = "Рекомендуем", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        option.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (option.installed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Voices of the engine chosen for [language], with downloads for the built-in models. */
@Composable
private fun EngineVoices(state: VoiceSettingsUi, language: String, selected: VoiceChoice, actions: VoiceSettingsActions) {
    when (selected.engine) {
        VoiceEngine.SUPERTONIC -> ModelCard(
            title = "Голоса Supertonic 3",
            badge = null,
            description = "Очень быстрый многоязычный голос. Больше шагов синтеза — чище звук, но дольше подготовка.",
            models = listOf(SpeechModel.SUPERTONIC, SpeechModel.SUPERTONIC_FULL),
            presets = VoiceCatalog.supertonic,
            language = language,
            selected = selected,
            state = state,
            actions = actions,
            footer = {
                Text("Шаги синтеза", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6 to "Быстро", 10 to "Баланс", 16 to "Качество").forEach { (steps, title) ->
                        FilterChip(
                            selected = state.supertonicSteps == steps,
                            onClick = { actions.setSupertonicSteps(steps) },
                            label = { Text(title) },
                        )
                    }
                }
            },
        )
        VoiceEngine.KOKORO -> ModelCard(
            title = "Голоса Kokoro v1.0",
            badge = null,
            description = "82M параметров, естественная интонация, работает на устройстве. Полная версия звучит чище " +
                "и в замерах синтезирует в 2,5 раза быстрее компактной; компактная занимает меньше места.",
            models = listOf(SpeechModel.KOKORO_FULL, SpeechModel.KOKORO),
            presets = VoiceCatalog.kokoro,
            language = language,
            selected = selected,
            state = state,
            actions = actions,
        )
        VoiceEngine.SYSTEM -> SystemEngineVoices(state, language, selected, actions)
    }
}

@Composable
private fun SystemEngineVoices(state: VoiceSettingsUi, language: String, selected: VoiceChoice, actions: VoiceSettingsActions) {
    val enginePackage = selected.enginePackage
    LaunchedEffect(enginePackage) { actions.loadEngineVoices(enginePackage) }
    val engine = state.systemEngines.firstOrNull { it.packageName == enginePackage }
    val title = SystemVoices.engineTitle(enginePackage, engine?.label)
    val ruVoice = enginePackage == VoiceCatalog.RUVOICE_PACKAGE
    SheetCard {
        CardTitle("Голоса · $title", null)
        if (engine == null) {
            Text(
                if (ruVoice) {
                    "RuVoice ставится отдельным приложением (APK с GitHub). После установки вернитесь сюда — движок появится в списке."
                } else {
                    "Этот движок сейчас не установлен. Пока его нет, фразы читает Supertonic."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (ruVoice) Button(onClick = actions::openRuVoicePage) { Text("Скачать RuVoice") }
            return@SheetCard
        }
        Text(
            if (ruVoice) {
                "Нейросеть Silero v5: интонация, ударения и омографы по контексту, числа и сокращения. " +
                    "Словари ударений и дополнительные голоса — в приложении RuVoice."
            } else {
                "Голоса с пометкой «нужен интернет» отправляют текст фразы в сеть."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val engineDefault = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = enginePackage)
        VoiceRow(
            title = "По умолчанию",
            subtitle = "Голос, выбранный в настройках движка",
            selected = selected == engineDefault,
            enabled = true,
            previewing = state.previewVoice == engineDefault,
            onSelect = { actions.selectVoice(language, engineDefault) },
            onPreview = { actions.preview(language, engineDefault) },
            onStop = actions::stopPreview,
        )
        val voices = state.engineVoices[enginePackage]?.filter { it.language == language }
        when {
            voices == null || (voices.isEmpty() && enginePackage in state.loadingEngines) -> LoadingRow("Загружаем голоса…")
            voices.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Движок не сообщил отдельных голосов для этого языка.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions::refreshSystemVoices) { Text("Обновить") }
            }
            else -> voices.take(24).forEach { voice ->
                val choice = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = enginePackage, voiceName = voice.name)
                VoiceRow(
                    title = SystemVoices.describe(voice),
                    subtitle = null,
                    selected = selected == choice,
                    enabled = !voice.notInstalled,
                    previewing = state.previewVoice == choice,
                    onSelect = { actions.selectVoice(language, choice) },
                    onPreview = { actions.preview(language, choice) },
                    onStop = actions::stopPreview,
                )
            }
        }
        Row {
            TextButton(onClick = { actions.openEngineApp(enginePackage) }) { Text("Открыть $title") }
            TextButton(onClick = actions::openSystemTtsSettings) { Text("Синтез речи Android") }
        }
    }
}

@Composable
private fun ModelCard(
    title: String,
    badge: String?,
    description: String,
    models: List<SpeechModel>,
    presets: List<VoicePreset>,
    language: String,
    selected: VoiceChoice,
    state: VoiceSettingsUi,
    actions: VoiceSettingsActions,
    footer: (@Composable () -> Unit)? = null,
) {
    SheetCard {
        CardTitle(title, badge)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val installed = models.any { it in state.installedModels }
        models.forEach { model ->
            ModelStatusRow(model, suggested = model == models.first() && !installed, state = state, actions = actions)
        }
        if (installed) {
            presets.forEach { preset ->
                VoiceRow(
                    title = preset.title,
                    subtitle = preset.description,
                    selected = selected == preset.choice,
                    enabled = true,
                    recommended = preset.recommended,
                    previewing = state.previewVoice == preset.choice,
                    onSelect = { actions.selectVoice(language, preset.choice) },
                    onPreview = { actions.preview(language, preset.choice) },
                    onStop = actions::stopPreview,
                )
            }
            footer?.invoke()
        }
    }
}

@Composable
private fun ModelStatusRow(model: SpeechModel, suggested: Boolean, state: VoiceSettingsUi, actions: VoiceSettingsActions) {
    val install = state.installStates[model]
    val installed = model in state.installedModels
    val busy = install?.stage in setOf(
        ModelInstallState.Stage.QUEUED, ModelInstallState.Stage.DOWNLOADING, ModelInstallState.Stage.EXTRACTING,
    )
    val label = "${if (model.isFullPrecision) "Полная точность" else "Компактная INT8"} · ${model.sizeLabel}"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                val status = when {
                    installed -> "Установлена"
                    busy && install?.stage == ModelInstallState.Stage.EXTRACTING -> "Распаковка…"
                    busy && install?.stage == ModelInstallState.Stage.QUEUED -> "В очереди"
                    busy -> "Загрузка…"
                    install?.stage == ModelInstallState.Stage.FAILED -> install.message ?: "Ошибка загрузки"
                    else -> if (suggested) "Не скачана" else "Необязательно"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (install?.stage == ModelInstallState.Stage.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                busy -> TextButton(onClick = { actions.cancelDownload(model) }) { Text("Отмена") }
                installed -> IconButton(onClick = { actions.delete(model) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить модель", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                suggested -> Button(onClick = { actions.download(model) }) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Скачать")
                }
                else -> OutlinedButton(onClick = { actions.download(model) }) { Text("Скачать") }
            }
        }
        if (busy) {
            val fraction = install?.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (install != null && install.total > 0 && install.stage == ModelInstallState.Stage.DOWNLOADING) {
                Text(
                    "${install.completed / 1_048_576} из ${install.total / 1_048_576} МБ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A voice that can read characters' lines, with its gender when the voice's name tells it. */
private class CharacterVoice(val choice: VoiceChoice, val title: String, val gender: SpeechRole?)

/** Character voices come from the narrator's engine, so a scene never jumps between synthesizers. */
private fun characterVoices(state: VoiceSettingsUi, language: String, narrator: VoiceChoice): List<CharacterVoice> =
    when (narrator.engine) {
        VoiceEngine.SUPERTONIC -> VoiceCatalog.supertonic.map {
            CharacterVoice(it.choice, it.title, if (it.choice.speaker < 5) SpeechRole.FEMALE else SpeechRole.MALE)
        }
        VoiceEngine.KOKORO -> VoiceCatalog.kokoro.map {
            val gender = when {
                "female" in it.description -> SpeechRole.FEMALE
                "male" in it.description -> SpeechRole.MALE
                else -> null
            }
            CharacterVoice(it.choice, "${it.title} · ${it.description}", gender)
        }
        VoiceEngine.SYSTEM -> state.engineVoices[narrator.enginePackage].orEmpty()
            .filter { it.language == language && !it.notInstalled }
            .map { voice ->
                CharacterVoice(
                    VoiceChoice(VoiceEngine.SYSTEM, enginePackage = voice.enginePackage, voiceName = voice.name),
                    SystemVoices.describe(voice),
                    SystemVoices.gender(voice),
                )
            }
    }

/** Fills in voices for a newly chosen mode, keeping earlier picks that still belong to the engine. */
private fun withDefaults(dialogue: DialogueVoices, options: List<CharacterVoice>, narrator: VoiceChoice): DialogueVoices {
    fun valid(choice: VoiceChoice?) = choice?.takeIf { picked -> options.any { it.choice == picked } }
    fun pick(gender: SpeechRole?) = options.firstOrNull { (gender == null || it.gender == gender) && it.choice != narrator }?.choice
    return when (dialogue.mode) {
        DialogueMode.OFF -> dialogue
        DialogueMode.SINGLE -> dialogue.copy(single = valid(dialogue.single) ?: pick(null))
        DialogueMode.BY_GENDER -> dialogue.copy(
            male = valid(dialogue.male) ?: pick(SpeechRole.MALE),
            female = valid(dialogue.female) ?: pick(SpeechRole.FEMALE),
        )
    }
}

@Composable
private fun DialogueVoicesCard(state: VoiceSettingsUi, language: String, narrator: VoiceChoice, actions: VoiceSettingsActions) {
    val dialogue = if (language == "en") state.englishDialogue else state.russianDialogue
    val options = characterVoices(state, language, narrator)
    var picking by remember { mutableStateOf<String?>(null) }
    SheetCard {
        CardTitle("Голоса персонажей", null)
        Text(
            "Реплики в диалогах читает другой голос. По полу — мужские и женские роли разными голосами: " +
                "кто говорит, понятно по словам автора («сказала она», «ответил он»).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DialogueMode.OFF to "Как рассказчик",
                DialogueMode.SINGLE to "Свой голос",
                DialogueMode.BY_GENDER to "По полу",
            ).forEach { (mode, title) ->
                FilterChip(
                    selected = dialogue.mode == mode,
                    onClick = { actions.setDialogue(language, withDefaults(dialogue.copy(mode = mode), options, narrator)) },
                    label = { Text(title) },
                )
            }
        }
        fun titleOf(choice: VoiceChoice?): String =
            choice?.let { picked -> options.firstOrNull { it.choice == picked }?.title } ?: "как у рассказчика"
        when (dialogue.mode) {
            DialogueMode.OFF -> Unit
            DialogueMode.SINGLE -> VoicePickRow("Голос реплик", titleOf(dialogue.single)) { picking = "single" }
            DialogueMode.BY_GENDER -> {
                VoicePickRow("Мужские роли", titleOf(dialogue.male)) { picking = "male" }
                VoicePickRow("Женские роли", titleOf(dialogue.female)) { picking = "female" }
                Text(
                    "Если автор не подсказал, кто говорит, реплику читает рассказчик.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (dialogue.mode != DialogueMode.OFF) {
            if (options.isEmpty() && narrator.engine == VoiceEngine.SYSTEM) LoadingRow("Загружаем голоса движка…")
            if (state.dialoguePreviewing) {
                TextButton(onClick = actions::stopPreview) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Остановить")
                }
            } else {
                TextButton(onClick = { actions.previewDialogue(language) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Прослушать диалог")
                }
            }
        }
    }
    picking?.let { slot ->
        val current = when (slot) {
            "male" -> dialogue.male
            "female" -> dialogue.female
            else -> dialogue.single
        }
        AlertDialog(
            onDismissRequest = { picking = null },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Готово") } },
            title = { Text(if (slot == "male") "Мужские роли" else if (slot == "female") "Женские роли" else "Голос реплик") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(options.size) { index ->
                        val option = options[index]
                        VoiceRow(
                            title = option.title,
                            subtitle = when (option.gender) {
                                SpeechRole.MALE -> "мужской"
                                SpeechRole.FEMALE -> "женский"
                                else -> null
                            },
                            selected = current == option.choice,
                            enabled = true,
                            previewing = state.previewVoice == option.choice,
                            onSelect = {
                                val updated = when (slot) {
                                    "male" -> dialogue.copy(male = option.choice)
                                    "female" -> dialogue.copy(female = option.choice)
                                    else -> dialogue.copy(single = option.choice)
                                }
                                actions.setDialogue(language, updated)
                            },
                            onPreview = { actions.preview(language, option.choice) },
                            onStop = actions::stopPreview,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun VoicePickRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text("Выбрать", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun VoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    previewing: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onStop: () -> Unit,
    recommended: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (recommended) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Star, contentDescription = "Рекомендуем", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                }
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (previewing) {
            IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, contentDescription = "Остановить пример") }
        } else {
            IconButton(onClick = onPreview, enabled = enabled) { Icon(Icons.Filled.PlayArrow, contentDescription = "Прослушать") }
        }
    }
}

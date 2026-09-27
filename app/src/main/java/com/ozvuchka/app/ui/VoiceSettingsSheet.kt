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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Star
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
import com.ozvuchka.app.speech.ModelInstallState
import com.ozvuchka.app.speech.SpeechModel
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
    LaunchedEffect(Unit) { actions.refreshSystemVoices() }
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
            if (language == "ru") {
                item { RuVoiceCard(state, selected, actions) }
            } else {
                item {
                    ModelCard(
                        title = "Kokoro v1.0",
                        badge = "Лучший английский",
                        description = "82M параметров, естественная интонация, работает на устройстве. Полная версия звучит чище " +
                            "и в замерах синтезирует в 2,5 раза быстрее компактной; компактная занимает меньше места.",
                        models = listOf(SpeechModel.KOKORO_FULL, SpeechModel.KOKORO),
                        presets = VoiceCatalog.kokoro,
                        language = language,
                        selected = selected,
                        state = state,
                        actions = actions,
                    )
                }
            }
            item {
                ModelCard(
                    title = "Supertonic 3",
                    badge = if (language == "ru") "Встроенный" else null,
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
            }
            item { SystemVoicesCard(state, language, selected, actions) }
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
private fun RuVoiceCard(state: VoiceSettingsUi, selected: VoiceChoice, actions: VoiceSettingsActions) {
    SheetCard {
        CardTitle("Silero v5 · RuVoice", "Лучший русский")
        Text(
            "Нейросеть Silero v5 в системном движке RuVoice: живая интонация, ударения и омографы по контексту, " +
                "числа, даты и сокращения. Работает офлайн и очень быстро.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.ruVoiceInstalled) {
            Text(
                "RuVoice устанавливается отдельным приложением (APK из GitHub). После установки вернитесь сюда — голоса появятся в списке.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = actions::openRuVoicePage) { Text("Скачать RuVoice") }
            return@SheetCard
        }
        val voices = state.systemVoices.filter { it.enginePackage == VoiceCatalog.RUVOICE_PACKAGE && it.language == "ru" }
        when {
            voices.isEmpty() && state.systemVoicesLoading -> LoadingRow("Подключаемся к RuVoice…")
            voices.isEmpty() -> {
                Text(
                    "RuVoice установлен, но голоса не найдены. Откройте RuVoice и установите пакет голосов.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = actions::refreshSystemVoices) { Text("Обновить") }
            }
            else -> voices.forEach { voice ->
                val choice = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = voice.enginePackage, voiceName = voice.name)
                VoiceRow(
                    title = SystemVoices.describe(voice),
                    subtitle = null,
                    selected = selected == choice,
                    enabled = true,
                    previewing = state.previewVoice == choice,
                    onSelect = { actions.selectVoice("ru", choice) },
                    onPreview = { actions.preview("ru", choice) },
                    onStop = actions::stopPreview,
                )
            }
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

@Composable
private fun SystemVoicesCard(state: VoiceSettingsUi, language: String, selected: VoiceChoice, actions: VoiceSettingsActions) {
    SheetCard {
        CardTitle("Системные голоса", null)
        Text(
            "Голоса движков синтеза, установленных на телефоне (Google, Samsung и другие). " +
                "Голоса с пометкой «нужен интернет» отправляют текст фразы в сеть.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val voices = state.systemVoices.filter {
            it.language == language && it.enginePackage != VoiceCatalog.RUVOICE_PACKAGE
        }
        when {
            voices.isEmpty() && state.systemVoicesLoading -> LoadingRow("Ищем голоса…")
            voices.isEmpty() -> Text("Подходящих системных голосов не найдено.", style = MaterialTheme.typography.bodySmall)
            else -> voices.groupBy { it.engineLabel }.forEach { (engine, engineVoices) ->
                Text(engine, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                engineVoices.take(12).forEach { voice ->
                    val choice = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = voice.enginePackage, voiceName = voice.name)
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
        }
        TextButton(onClick = actions::openSystemTtsSettings) { Text("Настройки синтеза Android") }
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

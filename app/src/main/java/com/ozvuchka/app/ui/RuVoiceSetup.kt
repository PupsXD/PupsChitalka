package com.ozvuchka.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ozvuchka.app.speech.RuVoiceInstallState
import com.ozvuchka.app.speech.RuVoiceInstallState.Stage

/** What the reader can do about getting RuVoice. */
interface RuVoiceSetupActions {
    fun installRuVoice()
    fun cancelRuVoice()
    fun openRuVoicePage()
}

/** The size of the full RuVoice build, shown before its release has been looked up. */
private const val RUVOICE_SIZE_LABEL = "≈\u00A0230\u00A0МБ"

internal fun ruVoiceProgressLabel(state: RuVoiceInstallState): String? = when (state.stage) {
    Stage.LOOKING -> "Ищем свежую версию…"
    Stage.DOWNLOADING -> buildString {
        append("Скачиваем RuVoice")
        state.version?.let { append(' ').append(it) }
        append(": ").append(megabytes(state.completed))
        if (state.total > 0) append(" из ").append(megabytes(state.total))
        append(" МБ")
    }
    Stage.PREPARING -> "Проверяем файл и готовим установку…"
    Stage.CONFIRMING -> "Подтвердите установку в окне Android"
    else -> null
}

private fun megabytes(bytes: Long) = (bytes / (1024 * 1024)).toString()

/**
 * The steps of getting RuVoice from inside the app: an offer, the download with its progress, the
 * wait for Android's confirmation, or what went wrong with a way to retry or download by hand.
 */
@Composable
internal fun RuVoiceSetupBody(state: RuVoiceInstallState, actions: RuVoiceSetupActions, onDismiss: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val progress = ruVoiceProgressLabel(state)
        if (progress == null) {
            Text(
                "Нейросеть Silero v5: живая интонация, ударения и омографы по контексту, числа и сокращения. " +
                    "Работает без интернета.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Отдельное бесплатное приложение, $RUVOICE_SIZE_LABEL, лучше скачивать по Wi-Fi. Android попросит подтвердить " +
                    "установку, а в первый раз — разрешить установку из PupsChitalka.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(progress, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            when (state.stage) {
                Stage.DOWNLOADING -> state.fraction?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                Stage.LOOKING, Stage.PREPARING -> LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                Stage.CONFIRMING -> Text(
                    "Если Android попросит разрешить установку из PupsChitalka, включите переключатель и вернитесь назад.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
        }
        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.stage == Stage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state.stage) {
                Stage.LOOKING, Stage.DOWNLOADING, Stage.PREPARING -> OutlinedButton(onClick = actions::cancelRuVoice) { Text("Отмена") }
                Stage.CONFIRMING, Stage.DONE -> Unit
                Stage.FAILED -> {
                    Button(onClick = actions::installRuVoice) { Text("Повторить") }
                    TextButton(onClick = actions::openRuVoicePage) { Text("Скачать вручную") }
                }
                Stage.IDLE -> {
                    Button(onClick = actions::installRuVoice) { Text("Установить RuVoice") }
                    if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Не сейчас") }
                }
            }
        }
    }
}

/** The library's offer of RuVoice on a fresh install, until it is installed or put off. */
@Composable
internal fun RuVoiceOfferCard(state: RuVoiceInstallState, actions: RuVoiceSetupActions, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("РУССКИЙ ГОЛОС", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("RuVoice · Silero v5", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            RuVoiceSetupBody(state, actions, onDismiss)
        }
    }
}

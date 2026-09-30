package com.ozvuchka.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.SystemUpdate
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
import com.ozvuchka.app.update.AppUpdateState
import com.ozvuchka.app.update.AppUpdateState.Stage
import com.ozvuchka.app.update.AppUpdater

/** What the reader can do about a new version of the app. */
interface AppUpdateActions {
    fun installUpdate()
    fun cancelUpdate()
    fun checkUpdate()
    /** «Не сейчас» or «Закрыть»: the card goes away until there is something new to say. */
    fun dismissUpdate()
    fun openReleasesPage()
}

private fun megabytes(bytes: Long) = (bytes / (1024 * 1024)).toString()

internal fun appUpdateHeadline(state: AppUpdateState, versionDate: (Long) -> String = AppUpdater::versionDate): String = when (state.stage) {
    Stage.CHECKING -> "Проверяем, вышла ли новая версия…"
    Stage.UP_TO_DATE -> "У вас самая свежая версия" + if (state.installed > 0) " — от ${versionDate(state.installed)}" else ""
    Stage.AVAILABLE -> "Вышла новая версия от ${versionDate(state.latest)}" +
        if (state.size > 0) " (≈ ${megabytes(state.size)} МБ)" else ""
    Stage.DOWNLOADING -> buildString {
        append("Скачиваем обновление")
        if (state.completed > 0 || state.total > 0) append(": ").append(megabytes(state.completed))
        if (state.total > 0) append(" из ").append(megabytes(state.total))
        if (state.completed > 0 || state.total > 0) append(" МБ")
    }
    Stage.PREPARING -> "Проверяем файл и готовим установку…"
    Stage.CONFIRMING -> "Устанавливаем обновление…"
    Stage.CHECK_FAILED -> "Не получилось проверить обновления"
    Stage.INSTALL_FAILED -> "Не получилось обновить приложение"
    Stage.UNKNOWN -> "Обновления приложения"
}

/**
 * A new version of PupsChitalka, offered in the library: what it is, the download with its
 * progress, the wait for Android's confirmation, or what went wrong with a way to retry or to get
 * the file by hand. After a check the reader asked for, it also says that nothing new came out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppUpdateCard(state: AppUpdateState, actions: AppUpdateActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ОБНОВЛЕНИЕ", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("PupsChitalka", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(appUpdateHeadline(state), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            when (state.stage) {
                Stage.AVAILABLE -> Hint(
                    "Книги, закладки и настройки сохранятся. Лучше скачивать по Wi-Fi. Android может попросить подтвердить установку; " +
                        "чтобы заменить приложение, он его закроет — потом просто откройте PupsChitalka снова.",
                )
                Stage.DOWNLOADING -> state.fraction?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                Stage.CHECKING, Stage.PREPARING -> LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                Stage.CONFIRMING -> Hint(
                    "Если Android попросит подтвердить или разрешить установку из PupsChitalka, согласитесь и вернитесь назад. " +
                        "Чтобы обновиться, приложение закроется — потом откройте его снова.",
                )
                else -> Unit
            }
            state.message?.let { message ->
                val failed = state.stage == Stage.CHECK_FAILED || state.stage == Stage.INSTALL_FAILED
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state.stage) {
                    Stage.AVAILABLE -> {
                        Button(onClick = actions::installUpdate) { Text("Обновить") }
                        TextButton(onClick = actions::dismissUpdate) { Text("Не сейчас") }
                    }
                    Stage.DOWNLOADING, Stage.PREPARING -> OutlinedButton(onClick = actions::cancelUpdate) { Text("Отмена") }
                    Stage.CONFIRMING, Stage.CHECKING -> Unit
                    Stage.INSTALL_FAILED -> {
                        Button(onClick = actions::installUpdate) { Text("Повторить") }
                        TextButton(onClick = actions::openReleasesPage) { Text("Скачать вручную") }
                        TextButton(onClick = actions::dismissUpdate) { Text("Не сейчас") }
                    }
                    Stage.CHECK_FAILED -> {
                        Button(onClick = actions::checkUpdate) { Text("Повторить") }
                        TextButton(onClick = actions::dismissUpdate) { Text("Закрыть") }
                    }
                    Stage.UP_TO_DATE, Stage.UNKNOWN -> TextButton(onClick = actions::dismissUpdate) { Text("Закрыть") }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Whether the library shows the update card: a new version until the reader puts it off
 * ([dismissed] holds the newest one put off), an update under way or failed, and the answer to a
 * check the reader [asked] for.
 */
internal fun appUpdateShown(state: AppUpdateState, asked: Boolean, dismissed: Long): Boolean = when (state.stage) {
    Stage.AVAILABLE, Stage.INSTALL_FAILED -> asked || state.latest != dismissed
    Stage.DOWNLOADING, Stage.PREPARING, Stage.CONFIRMING -> true
    Stage.CHECKING, Stage.UP_TO_DATE, Stage.CHECK_FAILED -> asked
    Stage.UNKNOWN -> false
}

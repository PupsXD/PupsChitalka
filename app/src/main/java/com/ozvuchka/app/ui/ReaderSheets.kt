package com.ozvuchka.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ozvuchka.app.data.Annotation
import com.ozvuchka.app.data.AnnotationKind
import com.ozvuchka.app.data.SearchHit
import com.ozvuchka.app.speech.PronunciationDictionary
import com.ozvuchka.app.speech.splitForSpeech
import kotlinx.coroutines.delay
import kotlin.math.abs

/** The word under a long press and the sentence around it, in paragraph offsets. */
internal data class WordTarget(
    val paragraph: Int,
    val word: String,
    val wordStart: Int,
    val wordEnd: Int,
    val sentence: String,
    val sentenceStart: Int,
    val sentenceEnd: Int,
)

internal fun wordTargetAt(paragraphs: List<String>, paragraph: Int, offset: Int, language: String): WordTarget? {
    val text = paragraphs.getOrNull(paragraph)?.takeIf { it.isNotEmpty() } ?: return null
    fun inWord(index: Int): Boolean {
        val c = text[index]
        if (c.isLetterOrDigit()) return true
        // «кто-нибудь», «don't»: joiners inside a word.
        return c in "-'’" && index > 0 && index + 1 < text.length && text[index - 1].isLetter() && text[index + 1].isLetter()
    }
    var index = offset.coerceIn(0, text.lastIndex)
    if (!inWord(index) && index > 0 && inWord(index - 1)) index--
    var start = index
    var end = index
    if (inWord(index)) {
        while (start > 0 && inWord(start - 1)) start--
        while (end < text.length && inWord(end)) end++
    }
    val chunks = splitForSpeech(text, language)
    val sentence = chunks.firstOrNull { index >= it.start && index < it.end }
        ?: chunks.minByOrNull { abs(it.start - index) }
        ?: return null
    return WordTarget(paragraph, text.substring(start, end), start, end, sentence.text, sentence.start, sentence.end)
}

private fun hasLatin(text: String) = text.any { it in 'a'..'z' || it in 'A'..'Z' }
private fun hasCyrillic(text: String) = text.any { it in 'Ѐ'..'ӿ' }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WordActionsSheet(
    target: WordTarget,
    state: ReaderUiState,
    actions: ReaderActions,
    onPronunciation: () -> Unit,
    /** Opens the note editor; the flag tells whether the highlight is new. */
    onNote: (Annotation, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val chapter = state.chapterIndex
    val inSentence = { annotation: Annotation ->
        annotation.chapter == chapter && annotation.paragraph == target.paragraph &&
            annotation.start < target.sentenceEnd && annotation.end > target.sentenceStart
    }
    val bookmark = state.annotations.firstOrNull { it.kind == AnnotationKind.BOOKMARK && inSentence(it) }
    val highlight = state.annotations.firstOrNull { it.kind == AnnotationKind.HIGHLIGHT && inSentence(it) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                if (target.word.isNotBlank()) {
                    Text(target.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    target.sentence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            ActionRow(Icons.Filled.PlayArrow, "Читать вслух отсюда") {
                onDismiss()
                actions.readFrom(target.paragraph, target.sentenceStart)
            }
            if (target.word.isNotBlank() && target.word.any { it.isLetter() }) {
                ActionRow(Icons.Filled.RecordVoiceOver, "Как произносить «${target.word}»") {
                    onDismiss()
                    onPronunciation()
                }
            }
            if (hasLatin(target.word)) {
                ActionRow(Icons.Filled.Translate, "Перевести «${target.word}»") {
                    onDismiss()
                    actions.translate(target.word)
                }
            }
            if (hasLatin(target.sentence) && !hasCyrillic(target.sentence)) {
                ActionRow(Icons.Filled.Translate, "Перевести фразу") {
                    onDismiss()
                    actions.translate(target.sentence)
                }
            }
            if (bookmark == null) {
                ActionRow(Icons.Filled.BookmarkBorder, "Закладка") {
                    onDismiss()
                    actions.addAnnotation(
                        Annotation(
                            kind = AnnotationKind.BOOKMARK,
                            chapter = chapter,
                            paragraph = target.paragraph,
                            start = target.sentenceStart,
                            end = target.sentenceEnd,
                            text = target.sentence,
                        ),
                    )
                }
            } else {
                ActionRow(Icons.Filled.Bookmark, "Убрать закладку") {
                    onDismiss()
                    actions.removeAnnotation(bookmark.id)
                }
            }
            if (highlight == null) {
                ActionRow(Icons.Filled.Highlight, "Выделить фразу") {
                    onDismiss()
                    actions.addAnnotation(newHighlight(chapter, target))
                }
                ActionRow(Icons.Filled.EditNote, "Заметка к фразе") {
                    onDismiss()
                    onNote(newHighlight(chapter, target), true)
                }
            } else {
                ActionRow(Icons.Filled.EditNote, if (highlight.note.isBlank()) "Добавить заметку" else "Изменить заметку") {
                    onDismiss()
                    onNote(highlight, false)
                }
                ActionRow(Icons.Filled.Highlight, "Убрать выделение") {
                    onDismiss()
                    actions.removeAnnotation(highlight.id)
                }
            }
            ActionRow(Icons.Filled.ContentCopy, "Копировать фразу") {
                onDismiss()
                actions.copyText(target.sentence)
            }
            ActionRow(Icons.Filled.Share, "Поделиться цитатой") {
                onDismiss()
                actions.shareQuote(target.sentence)
            }
        }
    }
}

private fun newHighlight(chapter: Int, target: WordTarget) = Annotation(
    kind = AnnotationKind.HIGHLIGHT,
    chapter = chapter,
    paragraph = target.paragraph,
    start = target.sentenceStart,
    end = target.sentenceEnd,
    text = target.sentence,
)

@Composable
private fun ActionRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Pick the stressed vowel with a tap, or write how to read the word («Джон», «Гер-ми-она»). RuVoice and
 * system engines take the stress; Supertonic and Kokoro only the written form.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PronunciationDialog(
    target: WordTarget,
    language: String,
    actions: ReaderActions,
    onDismiss: () -> Unit,
) {
    val word = target.word
    val existing = remember(word) { actions.pronunciationOf(word) }
    val existingStress = existing?.spoken?.takeIf { PronunciationDictionary.normalizeWord(it) == PronunciationDictionary.normalizeWord(word) }
    var stress by remember(word) {
        mutableStateOf(existingStress?.indexOf('+')?.takeIf { it >= 0 })
    }
    var written by remember(word) { mutableStateOf(existing?.spoken?.takeIf { existingStress == null }.orEmpty()) }
    var everyBook by remember(word) { mutableStateOf(existing?.everyBook ?: false) }
    val spoken = when {
        written.isNotBlank() -> written.trim()
        stress != null -> PronunciationDictionary.stressed(word, stress!!)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Как произносить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    spoken?.let(PronunciationDictionary::display) ?: word,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val vowels = PronunciationDictionary.vowelIndices(word)
                if (vowels.isNotEmpty()) {
                    Text("Ударная гласная", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        vowels.forEach { index ->
                            FilterChip(
                                selected = stress == index && written.isBlank(),
                                onClick = {
                                    stress = index
                                    written = ""
                                },
                                label = { Text(word.lowercase().let { it.substring(0, index) + it[index].uppercaseChar() + it.substring(index + 1) }) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = written,
                    onValueChange = { written = it },
                    label = { Text("Или напишите, как читать") },
                    placeholder = { Text(if (hasLatin(word)) "Например: Джон" else "Например: Гер-ми-о+на") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Ударение понимают RuVoice и системные голоса; встроенные модели читают написанное. " +
                        "«+» перед гласной ставит ударение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Во всех книгах", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = everyBook, onCheckedChange = { everyBook = it })
                }
                Row {
                    TextButton(
                        onClick = { spoken?.let { actions.previewPronunciation(word, it, target.sentence, language) } },
                        enabled = spoken != null,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Прослушать")
                    }
                    if (existing != null) {
                        TextButton(onClick = {
                            actions.removePronunciation(word)
                            onDismiss()
                        }) { Text("Сбросить") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    spoken?.let { actions.savePronunciation(word, it, everyBook) }
                    onDismiss()
                },
                enabled = spoken != null,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun NoteDialog(annotation: Annotation, isNew: Boolean, actions: ReaderActions, onDismiss: () -> Unit) {
    var note by remember(annotation.id) { mutableStateOf(annotation.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Заметка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "«${annotation.text}»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Ваша мысль") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = annotation.copy(note = note.trim())
                if (isNew) actions.addAnnotation(updated) else actions.updateAnnotation(updated)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PronunciationListSheet(actions: ReaderActions, onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf(actions.pronunciations()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Произношение слов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Добавляются долгим нажатием на слово в книге. Работают при озвучке любым голосом.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text("Пока пусто.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn {
                items(entries, key = { it.word + it.everyBook }) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${entry.word} → ${PronunciationDictionary.display(entry.spoken)}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (entry.everyBook) "Во всех книгах" else "В этой книге",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            actions.removePronunciation(entry.word)
                            entries = actions.pronunciations()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSheet(state: ReaderUiState, actions: ReaderActions, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            hits = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        searching = true
        hits = actions.search(query)
        searching = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск по книге") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        query.trim().length < 2 -> "Введите хотя бы две буквы"
                        hits.isEmpty() && !searching -> "Ничего не найдено"
                        hits.size >= 300 -> "Показаны первые 300 совпадений"
                        else -> "Найдено: ${hits.size}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(hits) { hit ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable {
                                onDismiss()
                                actions.jumpTo(hit.chapter, hit.paragraph, hit.start, hit.start until hit.end)
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            state.chapterTitles.getOrNull(hit.chapter) ?: "Глава ${hit.chapter + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            buildAnnotatedString {
                                append(hit.snippet)
                                addStyle(SpanStyle(fontWeight = FontWeight.Bold), hit.snippetMatch.first, hit.snippetMatch.last + 1)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

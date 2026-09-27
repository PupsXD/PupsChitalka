package com.ozvuchka.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.abs

private fun speechSpeedLabel(speed: Float): String =
    String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

private data class ReadingColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val line: Color,
)

private fun ReaderTheme.colors(): ReadingColors = when (this) {
    ReaderTheme.LIGHT -> ReadingColors(
        background = Color(0xFFFCFBF8),
        surface = Color(0xFFFFFFFF),
        text = Color(0xFF272530),
        muted = Color(0xFF77717C),
        accent = Color(0xFF51449A),
        line = Color(0xFFE9E5EC),
    )
    ReaderTheme.DARK -> ReadingColors(
        background = Color(0xFF171821),
        surface = Color(0xFF22232E),
        text = Color(0xFFF2F0F3),
        muted = Color(0xFFB5AFBC),
        accent = Color(0xFFCCBFFF),
        line = Color(0xFF393845),
    )
    ReaderTheme.SEPIA -> ReadingColors(
        background = Color(0xFFF5EFE3),
        surface = Color(0xFFFBF6EC),
        text = Color(0xFF3C332C),
        muted = Color(0xFF84786C),
        accent = Color(0xFF785447),
        line = Color(0xFFE5D9C8),
    )
}

/** onReadingProgressChange reports progress across the whole book, from 0f to 1f. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onBack: () -> Unit,
    onChapterChange: (Int) -> Unit,
    onReadingProgressChange: (Float) -> Unit,
    onChromeVisibilityChange: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onSpeechSpeedChange: (Float) -> Unit,
    onVoiceChange: (Int) -> Unit,
    onPreviewVoice: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onSerifChange: (Boolean) -> Unit,
    onThemeChange: (ReaderTheme) -> Unit,
    onDownloadVoice: () -> Unit,
    onDownloadFullVoice: () -> Unit,
    onCancelVoiceDownload: () -> Unit,
    onQualityChange: (Boolean) -> Unit,
    onExport: (String) -> Unit,
    onImportNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = state.theme.colors()
    val progressCallback by rememberUpdatedState(onReadingProgressChange)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val chromeCallback by rememberUpdatedState(onChromeVisibilityChange)
    LaunchedEffect(chromeVisible) { chromeCallback(chromeVisible) }
    DisposableEffect(Unit) { onDispose { chromeCallback(true) } }
    var pageAnchor by remember(state.bookId, state.chapterIndex) { mutableStateOf(state.chapterProgress) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var pageIndex by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(1) }
    var requestedPage by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        topBar = {
            if (chromeVisible) ReaderTopBar(
                state, colors, onBack = onBack, onSettings = { showSettings = true },
                onHideControls = { chromeVisible = false },
            )
        },
        bottomBar = {
            if (chromeVisible) ReaderBottomBar(
                state = state,
                colors = colors,
                pageIndex = pageIndex,
                pageCount = pageCount,
                onPageChange = { requestedPage = it },
                onChapterChange = onChapterChange,
                onPlayPause = onPlayPause,
                onSpeechSpeedChange = onSpeechSpeedChange,
                onDownloadVoice = onDownloadVoice,
                onCancelVoiceDownload = onCancelVoiceDownload,
                onImportNextChapter = onImportNextChapter,
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val widthPx = with(density) { (maxWidth - 52.dp).roundToPx() }
                val heightPx = with(density) { maxHeight.roundToPx() }
                val pages = remember(
                    state.bookId, state.chapterIndex, state.paragraphs,
                    state.fontSizeSp, state.useSerif, widthPx, heightPx,
                ) {
                    paginateChapter(
                        state.paragraphs, state.chapterTitle, state.fontSizeSp,
                        state.useSerif, widthPx, heightPx, density, measurer,
                    )
                }
                key(state.bookId, state.chapterIndex, state.fontSizeSp, state.useSerif, widthPx, heightPx) {
                    val initialPage = pages.indexOfLast { it.startsAt <= pageAnchor + 0.0001f }
                        .coerceAtLeast(0)
                    val pager = rememberPagerState(initialPage = initialPage) { pages.size }
                    LaunchedEffect(pages.size) { pageCount = pages.size }
                    LaunchedEffect(pager.currentPage, pages) {
                        pageIndex = pager.currentPage
                        val start = pages[pager.currentPage].startsAt
                        pageAnchor = start
                        val overall = (state.chapterIndex + start) / state.chapterCount.coerceAtLeast(1)
                        progressCallback(overall)
                    }
                    LaunchedEffect(requestedPage) {
                        requestedPage?.let { target ->
                            if (target in pages.indices) pager.animateScrollToPage(target)
                            requestedPage = null
                        }
                    }
                    LaunchedEffect(state.narrationParagraphIndex, state.narrationTextOffset, pages) {
                        val paragraph = state.narrationParagraphIndex ?: return@LaunchedEffect
                        val length = state.paragraphs.getOrNull(paragraph)?.length?.coerceAtLeast(1) ?: 1
                        val position = (paragraph + state.narrationTextOffset.toFloat() / length) /
                            state.paragraphs.size.coerceAtLeast(1)
                        val target = pages.indexOfLast { it.startsAt <= position + 0.0001f }.coerceAtLeast(0)
                        if (target != pager.currentPage) pager.animateScrollToPage(target)
                    }
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
                        val page = pages[index]
                        val turn = (pager.currentPage - index) + pager.currentPageOffsetFraction
                        Column(
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                rotationY = -turn * 15f
                                transformOrigin = TransformOrigin(if (turn >= 0f) 0f else 1f, 0.5f)
                                cameraDistance = 24f * density.density
                                alpha = 1f - abs(turn).coerceIn(0f, 1f) * 0.08f
                            }.pointerInput(index, chromeVisible) {
                                detectTapGestures(onTap = {
                                    pageAnchor = page.startsAt
                                    chromeVisible = !chromeVisible
                                })
                            }.padding(
                                start = 26.dp, end = 26.dp, top = 27.dp, bottom = 27.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            if (page.hasHeading) {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text(
                                        "ГЛАВА ${state.chapterIndex + 1}  /  ${state.chapterCount}",
                                        color = colors.accent,
                                        style = readerLabelStyle,
                                    )
                                    Text(
                                        state.chapterTitle,
                                        color = colors.text,
                                        style = readerTitleStyle(state.fontSizeSp),
                                    )
                                    Box(Modifier.size(width = 42.dp, height = 2.dp).background(colors.accent))
                                }
                            }
                            page.blocks.forEach { block ->
                                val highlightStart = if (state.narrationParagraphIndex == block.paragraphIndex) {
                                    (state.narrationTextOffset - block.startOffset).coerceIn(0, block.text.length)
                                } else 0
                                val highlightEnd = if (state.narrationParagraphIndex == block.paragraphIndex) {
                                    (state.narrationTextOffset + state.narrationTextLength - block.startOffset)
                                        .coerceIn(0, block.text.length)
                                } else 0
                                val marked = buildAnnotatedString {
                                    append(block.text)
                                    if (highlightEnd > highlightStart) {
                                        addStyle(
                                            SpanStyle(background = colors.accent.copy(alpha = 0.24f)),
                                            highlightStart, highlightEnd,
                                        )
                                    }
                                }
                                Text(
                                    marked,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (block.text == "✦  ✦  ✦") colors.muted else colors.text,
                                    style = readerBodyStyle(state.fontSizeSp, state.useSerif),
                                    textAlign = if (block.text == "✦  ✦  ✦") TextAlign.Center else TextAlign.Start,
                                )
                            }
                            if (state.paragraphs.isEmpty()) {
                                Text("В этой главе пока нет доступного текста.", color = colors.muted)
                            }
                        }
                    }
                }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            state = state,
            onDismiss = { showSettings = false },
            onFontSizeChange = onFontSizeChange,
            onSerifChange = onSerifChange,
            onThemeChange = onThemeChange,
            onSpeechSpeedChange = onSpeechSpeedChange,
            onVoiceChange = onVoiceChange,
            onPreviewVoice = onPreviewVoice,
            onDownloadVoice = onDownloadVoice,
            onDownloadFullVoice = onDownloadFullVoice,
            onCancelVoiceDownload = onCancelVoiceDownload,
            onQualityChange = onQualityChange,
            onExport = onExport,
        )
    }
}

@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    colors: ReadingColors,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onHideControls: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(colors.surface).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
            ) {
                Text("←", fontSize = 28.sp)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    state.title,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.chapterTitle,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onHideControls,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
            ) { Text("Скрыть", fontSize = 12.sp) }
            TextButton(
                onClick = onSettings,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
            ) {
                Text("Аа", fontFamily = FontFamily.Serif, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        LinearProgressIndicator(
            progress = state.overallProgress.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = colors.accent,
            trackColor = colors.line,
        )
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    colors: ReadingColors,
    pageIndex: Int,
    pageCount: Int,
    onPageChange: (Int) -> Unit,
    onChapterChange: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onSpeechSpeedChange: (Float) -> Unit,
    onDownloadVoice: () -> Unit,
    onCancelVoiceDownload: () -> Unit,
    onImportNextChapter: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onPageChange(pageIndex - 1) },
                enabled = pageIndex > 0,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
            ) { Text("‹", fontSize = 30.sp) }
            Spacer(Modifier.weight(1f))
            Text(
                "СТРАНИЦА ${pageIndex + 1} / $pageCount",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onPageChange(pageIndex + 1) },
                enabled = pageIndex + 1 < pageCount,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
            ) { Text("›", fontSize = 30.sp) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { onChapterChange(state.chapterIndex - 1) },
                enabled = state.chapterIndex > 0,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
            ) { Text("← Глава") }
            Spacer(Modifier.weight(1f))
            Text(
                "${(state.overallProgress.coerceIn(0f, 1f) * 100).roundToInt()}%",
                color = colors.muted,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (state.chapterIndex + 1 < state.chapterCount) onChapterChange(state.chapterIndex + 1)
                    else onImportNextChapter()
                },
                enabled = state.chapterIndex + 1 < state.chapterCount || state.hasNextWebChapter,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
            ) { Text("Глава →") }
        }
        if (state.isVoiceReady) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPlayPause,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.surface),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 13.dp),
                ) {
                    Text(if (state.isPlaying) "❚❚  Пауза" else "▶  Слушать", fontSize = 16.sp)
                }
                OutlinedButton(
                    onClick = {
                        val next = when {
                            state.speechSpeed < 1f -> 1f
                            state.speechSpeed < 1.25f -> 1.25f
                            state.speechSpeed < 1.5f -> 1.5f
                            else -> 0.8f
                        }
                        onSpeechSpeedChange(next)
                    },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(speechSpeedLabel(state.speechSpeed))
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 13.dp)) {
                Text(
                    state.voiceStatus ?: "Скачайте голос для озвучки на устройстве",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                state.voiceDownloadProgress?.let { progress ->
                    Spacer(Modifier.height(7.dp))
                    LinearProgressIndicator(
                        progress = progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = if (state.voiceDownloadProgress == null) onDownloadVoice else onCancelVoiceDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.voiceDownloadProgress == null) "Загрузить голос" else "Отменить загрузку") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    state: ReaderUiState,
    onDismiss: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onSerifChange: (Boolean) -> Unit,
    onThemeChange: (ReaderTheme) -> Unit,
    onSpeechSpeedChange: (Float) -> Unit,
    onVoiceChange: (Int) -> Unit,
    onPreviewVoice: () -> Unit,
    onDownloadVoice: () -> Unit,
    onDownloadFullVoice: () -> Unit,
    onCancelVoiceDownload: () -> Unit,
    onQualityChange: (Boolean) -> Unit,
    onExport: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("Настройки чтения", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            item {
                SettingsLabel("Размер текста")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("А", fontSize = 16.sp)
                    Slider(
                        value = state.fontSizeSp.coerceIn(14f, 32f),
                        onValueChange = onFontSizeChange,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        valueRange = 14f..32f,
                    )
                    Text("А", fontSize = 29.sp)
                }
                Text("${state.fontSizeSp.roundToInt()} пт", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SettingsLabel("Шрифт")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = state.useSerif,
                        onClick = { onSerifChange(true) },
                        label = { Text("С засечками", fontFamily = FontFamily.Serif) },
                    )
                    FilterChip(
                        selected = !state.useSerif,
                        onClick = { onSerifChange(false) },
                        label = { Text("Без засечек", fontFamily = FontFamily.SansSerif) },
                    )
                }
            }
            item {
                SettingsLabel("Оформление")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeChoice("Светлая", ReaderTheme.LIGHT, state.theme, onThemeChange, Modifier.weight(1f))
                    ThemeChoice("Сепия", ReaderTheme.SEPIA, state.theme, onThemeChange, Modifier.weight(1f))
                    ThemeChoice("Тёмная", ReaderTheme.DARK, state.theme, onThemeChange, Modifier.weight(1f))
                }
            }
            item {
                SettingsLabel("Скорость озвучки")
                Slider(
                    value = state.speechSpeed.coerceIn(0.7f, 1.5f),
                    onValueChange = onSpeechSpeedChange,
                    valueRange = 0.7f..1.5f,
                )
                Text(speechSpeedLabel(state.speechSpeed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.isVoiceReady) {
                item {
                    SettingsLabel("Тембр голоса")
                    Text(
                        "Supertonic 3 · 10 вариантов для русского текста",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (id in 0 until 10) {
                            FilterChip(
                                selected = state.voiceId == id,
                                onClick = { onVoiceChange(id) },
                                label = { Text("Голос ${id + 1}") },
                            )
                        }
                    }
                    OutlinedButton(onClick = onPreviewVoice) { Text("Прослушать пример") }
                }
                item {
                    SettingsLabel("Качество модели")
                    if (state.isFullVoiceReady) {
                        Text(
                            "Полноточная модель установлена. Её можно переключить на быстрый вариант в любой момент.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(
                                selected = state.preferFullVoice,
                                onClick = { onQualityChange(true) },
                                label = { Text("Полноточное") },
                            )
                            FilterChip(
                                selected = !state.preferFullVoice,
                                onClick = { onQualityChange(false) },
                                label = { Text("Быстрое") },
                            )
                        }
                    } else {
                        Text(
                            "Полноточные веса Supertonic 3 без INT8-квантования — около 400 МБ. Разница на слух зависит от текста и голоса; подготовка речи может занять больше времени.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (state.voiceDownloadProgress != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(state.voiceStatus ?: "Загружаем модель", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(
                                progress = state.voiceDownloadProgress.coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = if (state.voiceDownloadProgress == null) onDownloadFullVoice else onCancelVoiceDownload,
                        ) {
                            Text(if (state.voiceDownloadProgress == null) "Загрузить полноточную модель" else "Отменить загрузку")
                        }
                    }
                }
            }
            if (!state.isVoiceReady) {
                item {
                    SettingsLabel("Голос")
                    Text(
                        state.voiceStatus ?: "Загрузите голосовую модель, чтобы слушать книгу офлайн.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Загрузка — около 129 МБ. После установки голос работает без интернета.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.voiceDownloadProgress?.let { progress ->
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = if (state.voiceDownloadProgress == null) onDownloadVoice else onCancelVoiceDownload,
                    ) {
                        Text(if (state.voiceDownloadProgress == null) "Загрузить голос" else "Отменить загрузку")
                    }
                }
            }
            item {
                SettingsLabel("Экспорт книги")
                Text(
                    "Сохраните распознанный текст в книжном формате.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onDismiss(); onExport("epub") }, modifier = Modifier.weight(1f)) { Text("EPUB") }
                    OutlinedButton(onClick = { onDismiss(); onExport("fb2") }, modifier = Modifier.weight(1f)) { Text("FB2") }
                }
            }
        }
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun ThemeChoice(
    title: String,
    theme: ReaderTheme,
    selected: ReaderTheme,
    onClick: (ReaderTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = theme.colors()
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .border(
                width = if (theme == selected) 2.dp else 1.dp,
                color = if (theme == selected) colors.accent else colors.line,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable { onClick(theme) }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Aa", fontFamily = FontFamily.Serif, fontSize = 25.sp, color = colors.text)
        Text(title, fontSize = 11.sp, color = colors.text, maxLines = 1)
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(if (theme == selected) colors.accent else colors.line),
        )
    }
}

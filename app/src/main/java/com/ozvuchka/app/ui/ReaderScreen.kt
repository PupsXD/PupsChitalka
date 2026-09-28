package com.ozvuchka.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.ozvuchka.app.data.Annotation
import com.ozvuchka.app.data.AnnotationKind
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ozvuchka.app.data.BookImages
import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.data.ParagraphStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun speechSpeedLabel(speed: Float): String =
    String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

internal val speedPresets = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.35f, 1.5f, 1.75f, 2f)

internal data class ReadingColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val line: Color,
    val highlight: Color,
    /** Drawn over [highlight] on the word being spoken. */
    val wordHighlight: Color,
    /** The reader's own highlights. */
    val marker: Color,
)

internal fun ReaderTheme.colors(): ReadingColors = when (this) {
    ReaderTheme.LIGHT -> ReadingColors(
        background = Color(0xFFFCFBF8),
        surface = Color(0xFFFFFFFF),
        text = Color(0xFF272530),
        muted = Color(0xFF77717C),
        accent = Color(0xFF51449A),
        line = Color(0xFFE9E5EC),
        highlight = Color(0x3351449A),
        wordHighlight = Color(0x4751449A),
        marker = Color(0x66FFD54F),
    )
    ReaderTheme.SEPIA -> ReadingColors(
        background = Color(0xFFF5EFE3),
        surface = Color(0xFFFBF6EC),
        text = Color(0xFF3C332C),
        muted = Color(0xFF84786C),
        accent = Color(0xFF785447),
        line = Color(0xFFE5D9C8),
        highlight = Color(0x33A0663F),
        wordHighlight = Color(0x47A0663F),
        marker = Color(0x66F2B84B),
    )
    ReaderTheme.DARK -> ReadingColors(
        background = Color(0xFF171821),
        surface = Color(0xFF22232E),
        text = Color(0xFFE9E6EC),
        muted = Color(0xFFA9A3B1),
        accent = Color(0xFFCCBFFF),
        line = Color(0xFF393845),
        highlight = Color(0x40CCBFFF),
        wordHighlight = Color(0x4DCCBFFF),
        marker = Color(0x4DFFD54F),
    )
    ReaderTheme.BLACK -> ReadingColors(
        background = Color(0xFF000000),
        surface = Color(0xFF121214),
        text = Color(0xFFD6D3DA),
        muted = Color(0xFF8C8794),
        accent = Color(0xFFB9A7FF),
        line = Color(0xFF26262B),
        highlight = Color(0x4DB9A7FF),
        wordHighlight = Color(0x59B9A7FF),
        marker = Color(0x4DFFC94D),
    )
}

private class BlockGeometry {
    var coordinates: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
}

/** Layout facts read only inside gesture handlers, so they are plain fields, not Compose state. */
private class PageGeometry {
    var container: LayoutCoordinates? = null
    val blocks = HashMap<Int, BlockGeometry>()
    val pictures = HashMap<ParagraphStyle, LayoutCoordinates>()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    actions: ReaderActions,
    pageTurns: Flow<Int>,
    modifier: Modifier = Modifier,
) {
    val colors = state.theme.colors()
    val currentActions by rememberUpdatedState(actions)
    val currentState by rememberUpdatedState(state)
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(chromeVisible) { currentActions.chromeVisibilityChanged(chromeVisible) }
    DisposableEffect(Unit) { onDispose { currentActions.chromeVisibilityChanged(true) } }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showContents by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showPronunciations by rememberSaveable { mutableStateOf(false) }
    var wordTarget by remember { mutableStateOf<WordTarget?>(null) }
    var pronunciationTarget by remember { mutableStateOf<WordTarget?>(null) }
    var noteTarget by remember { mutableStateOf<Pair<Annotation, Boolean>?>(null) }
    var searchMark by remember { mutableStateOf<ReaderJump?>(null) }
    // Where the visible page starts, and the share of the chapter it covers, for the bookmark button.
    var pageStart by remember { mutableStateOf(0 to 0) }
    var pageSpan by remember { mutableStateOf(0f to 0f) }
    val chapterBookmarks = state.annotations.filter { it.kind == AnnotationKind.BOOKMARK && it.chapter == state.chapterIndex }
    fun positionOf(paragraph: Int, offset: Int): Float {
        val length = state.paragraphs.getOrNull(paragraph)?.length?.coerceAtLeast(1) ?: 1
        return (paragraph + offset.toFloat() / length) / state.paragraphs.size.coerceAtLeast(1)
    }
    var pageAnchor by remember(state.bookId, state.chapterIndex) { mutableFloatStateOf(state.chapterProgress) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(1) }
    var charactersLeft by remember { mutableIntStateOf(0) }
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    /** A page of the pager (edge pages included) that a tap asked to turn to. */
    var requestedPageIndex by remember { mutableStateOf<Int?>(null) }
    var viewerPicture by remember { mutableStateOf<ParagraphStyle?>(null) }
    val layouts = remember(state.bookId) { PageCache() }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val haptics = LocalHapticFeedback.current
    val safeInsets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).asPaddingValues()
    val margin = state.typography.margin.horizontalDp.dp
    val footerHeight = 22.dp

    // Brightness follows a swipe up or down the left third of the page; below the screen's minimum a
    // veil dims further.
    var brightnessLevel by remember { mutableFloatStateOf(state.brightness ?: state.systemBrightness) }
    var brightnessShownUntil by remember { mutableLongStateOf(0L) }
    val veil = (-(state.brightness ?: 0f)).coerceIn(0f, 0.6f)
    Box(
        modifier.fillMaxSize().background(colors.background).drawWithContent {
            drawContent()
            if (state.warmLight > 0f) {
                drawRect(Color(0xFFFF8A3D), alpha = state.warmLight * 0.35f, blendMode = BlendMode.Multiply)
            }
            if (veil > 0f) drawRect(Color.Black, alpha = veil)
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val horizontalInset = safeInsets.calculateLeftPadding(layoutDirection) + safeInsets.calculateRightPadding(layoutDirection)
            val widthPx = with(density) { (maxWidth - horizontalInset - margin * 2).roundToPx() }
            val heightPx = with(density) {
                (maxHeight - safeInsets.calculateTopPadding() - safeInsets.calculateBottomPadding() -
                    PageVerticalPadding * 2 - footerHeight).roundToPx()
            }
            fun layout(view: ChapterView, maxPages: Int = Int.MAX_VALUE): List<ReaderPage> =
                layouts.pages(view, state.typography, widthPx, heightPx, maxPages) {
                    paginateChapter(
                        view.paragraphs, view.title, view.label, state.typography, view.language,
                        widthPx, heightPx, density, measurer, view.styles, maxPages,
                    )
                }
            val current = ChapterView(
                state.chapterIndex, state.chapterTitle, chapterLabel(state.chapterIndex, state.chapterCount),
                state.paragraphs, state.styles, state.language,
            )
            val pages = remember(state.bookId, state.chapterIndex, state.chapterCount, state.paragraphs, state.styles, state.typography, state.language, widthPx, heightPx) {
                layout(current)
            }
            // The chapters on either side appear as one more page at each end, so a swipe past the
            // last page opens the next chapter and a swipe back past the first page the previous one.
            val previous = state.previousChapter?.let { ChapterView(it.index, it.title, chapterLabel(it.index, state.chapterCount), it.paragraphs, it.styles, it.language) }
            key(state.bookId, state.chapterIndex, state.typography, widthPx, heightPx, pages) {
                val lead = if (previous != null) 1 else 0
                val initialPage = lead + pages.indexOfLast { it.startsAt <= pageAnchor + 0.0001f }.coerceAtLeast(0)
                val pager = rememberPagerState(initialPage = initialPage) {
                    lead + pages.size + if (currentState.nextChapter != null || currentState.hasNextWebChapter) 1 else 0
                }
                fun chapterPage(index: Int): Int? = (index - lead).takeIf { it in pages.indices }
                // Where the previous chapter's last page starts, once it is laid out.
                var previousLastStart by remember { mutableStateOf<Float?>(null) }
                val dragged by pager.interactionSource.collectIsDraggedAsState()
                var browsingUntil by remember { mutableLongStateOf(0L) }
                LaunchedEffect(dragged) { if (dragged) browsingUntil = System.currentTimeMillis() + 8_000 }
                LaunchedEffect(pages.size) { pageCount = pages.size }
                LaunchedEffect(pager.currentPage, pages) {
                    val chapterIndex = chapterPage(pager.currentPage) ?: return@LaunchedEffect
                    val page = pages[chapterIndex]
                    pageIndex = chapterIndex
                    val start = page.startsAt
                    pageAnchor = start
                    // A bookmark quotes the page's first words, not a picture above them.
                    pageStart = (page.blocks.firstOrNull { it.text.isNotBlank() } ?: page.blocks.firstOrNull())
                        ?.let { it.paragraphIndex to it.startOffset } ?: (0 to 0)
                    pageSpan = start to (pages.getOrNull(chapterIndex + 1)?.startsAt ?: 1.0001f)
                    charactersLeft = pages.drop(chapterIndex).sumOf { it.characters }
                    currentActions.readingProgressChanged((state.chapterIndex + start) / state.chapterCount.coerceAtLeast(1))
                }
                // Resting on an edge page opens that chapter where the swipe left off.
                LaunchedEffect(pager.settledPage, state.nextChapter?.index) {
                    val settled = pager.settledPage
                    when {
                        previous != null && settled == 0 -> currentActions.changeChapter(previous.index, previousLastStart ?: 1f)
                        settled == lead + pages.size -> {
                            val upcoming = currentState.nextChapter
                            if (upcoming != null) currentActions.changeChapter(upcoming.index, 0f)
                            else if (currentState.hasNextWebChapter) currentActions.importNextChapter()
                        }
                    }
                }
                LaunchedEffect(requestedPage) {
                    requestedPage?.let { target ->
                        if (target in pages.indices) pager.animateScrollToPage(lead + target)
                        requestedPage = null
                    }
                }
                LaunchedEffect(pageTurns) {
                    pageTurns.collect { direction ->
                        val target = (pager.currentPage + direction).coerceIn(0, pager.pageCount - 1)
                        if (target != pager.currentPage) pager.animateScrollToPage(target)
                    }
                }
                // A bookmark, quote or search hit asked to show a place in this chapter.
                LaunchedEffect(state.jump?.id) {
                    val jump = state.jump ?: return@LaunchedEffect
                    if (jump.chapter != state.chapterIndex) return@LaunchedEffect
                    val position = positionOf(jump.paragraph, jump.offset)
                    val target = pages.indexOfLast { it.startsAt <= position + 0.0001f }.coerceAtLeast(0)
                    browsingUntil = System.currentTimeMillis() + 8_000
                    pager.scrollToPage(lead + target)
                    searchMark = jump.takeIf { it.mark != null }
                }
                LaunchedEffect(searchMark) {
                    if (searchMark != null) {
                        kotlinx.coroutines.delay(5_000)
                        searchMark = null
                    }
                }
                // Follow narration, unless the reader is leafing through pages right now.
                // The spoken word, when known, turns the page in the middle of a long sentence.
                val followOffset = state.narration.wordOffset.takeIf { it >= 0 } ?: state.narration.textOffset
                LaunchedEffect(state.narration.paragraphIndex, followOffset, pages) {
                    val paragraph = state.narration.paragraphIndex ?: return@LaunchedEffect
                    if (System.currentTimeMillis() < browsingUntil) return@LaunchedEffect
                    val target = if (paragraph < 0) 0 else {
                        val length = state.paragraphs.getOrNull(paragraph)?.length?.coerceAtLeast(1) ?: 1
                        val position = (paragraph + followOffset.toFloat() / length) /
                            state.paragraphs.size.coerceAtLeast(1)
                        pages.indexOfLast { it.startsAt <= position + 0.0001f }.coerceAtLeast(0)
                    }
                    if (lead + target != pager.currentPage) pager.animateScrollToPage(lead + target)
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
                    val turn = (pager.currentPage - index) + pager.currentPageOffsetFraction
                    val pageModifier = Modifier.fillMaxSize()
                        .graphicsLayer {
                            rotationY = -turn * 12f
                            transformOrigin = TransformOrigin(if (turn >= 0f) 0f else 1f, 0.5f)
                            cameraDistance = 24f * density.density
                            alpha = 1f - abs(turn).coerceIn(0f, 1f) * 0.08f
                        }
                        .background(colors.background)
                    val chapterIndex = chapterPage(index)
                    if (chapterIndex == null) {
                        // An edge page: the neighbouring chapter's closest page, or a web chapter on its way.
                        val before = index < lead
                        val neighbour = if (before) previous else state.nextChapter?.let {
                            ChapterView(it.index, it.title, chapterLabel(it.index, state.chapterCount), it.paragraphs, it.styles, it.language)
                        }
                        val neighbourPage by produceState<ReaderPage?>(null, neighbour, widthPx, heightPx, state.typography) {
                            // A frame later, so opening a chapter is never held up by laying out the next one.
                            kotlinx.coroutines.yield()
                            value = neighbour?.let { view ->
                                if (before) layout(view).lastOrNull()?.also { previousLastStart = it.startsAt }
                                else layout(view, maxPages = 1).firstOrNull()
                            }
                        }
                        Box(
                            modifier = pageModifier
                                .swipesAreNotTaps()
                                .pointerInput(index) {
                                    detectTapGestures { offset ->
                                        val zone = offset.x / size.width
                                        when {
                                            zone < 0.28f && index > 0 -> requestedPageIndex = index - 1
                                            zone > 0.72f && index < pager.pageCount - 1 -> requestedPageIndex = index + 1
                                            else -> chromeVisible = !chromeVisible
                                        }
                                    }
                                }
                                .padding(safeInsets)
                                .padding(horizontal = margin, vertical = PageVerticalPadding),
                        ) {
                            val shown = neighbourPage
                            if (neighbour != null && shown != null) {
                                PageContent(
                                    page = shown,
                                    view = neighbour,
                                    state = state,
                                    colors = colors,
                                    live = false,
                                    searchMark = null,
                                    footer = "Глава ${neighbour.index + 1}",
                                    onPicture = { viewerPicture = it },
                                    onLayout = { _, _, _ -> },
                                )
                            } else {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (neighbour == null) {
                                        CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                                        Spacer(Modifier.height(14.dp))
                                        Text("Загружаем следующую главу…", color = colors.muted, style = MaterialTheme.typography.bodyMedium)
                                    } else {
                                        Text(neighbour.label, color = colors.accent, style = readerLabelStyle)
                                        Spacer(Modifier.height(ReaderRhythm.headingGap))
                                        Text(neighbour.title, color = colors.text, style = readerTitleStyle(state.typography.fontSizeSp), textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                        return@HorizontalPager
                    }
                    val page = pages[chapterIndex]
                    val geometry = remember(page) { PageGeometry() }
                    Box(
                        modifier = pageModifier
                            .onGloballyPositioned { geometry.container = it }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (down.position.x > size.width * BrightnessZone) return@awaitEachGesture
                                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                        ?: return@awaitEachGesture
                                    brightnessLevel = currentState.brightness ?: currentState.systemBrightness
                                    verticalDrag(drag.id) { change ->
                                        val delta = -change.positionChange().y / size.height * 1.6f
                                        brightnessLevel = (brightnessLevel + delta).coerceIn(-0.6f, 1f)
                                        brightnessShownUntil = System.currentTimeMillis() + 1_200
                                        currentActions.brightnessChanged(brightnessLevel, final = false)
                                        change.consume()
                                    }
                                    currentActions.brightnessChanged(brightnessLevel, final = true)
                                }
                            }
                            .swipesAreNotTaps()
                            .pointerInput(index, pages) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        val zone = offset.x / size.width
                                        when {
                                            zone < 0.28f && index > 0 -> requestedPageIndex = index - 1
                                            zone > 0.72f && index < pager.pageCount - 1 -> requestedPageIndex = index + 1
                                            else -> {
                                                pageAnchor = page.startsAt
                                                chromeVisible = !chromeVisible
                                            }
                                        }
                                    },
                                    onLongPress = { offset ->
                                        val container = geometry.container ?: return@detectTapGestures
                                        // A long press on a picture opens it full screen.
                                        val picture = geometry.pictures.entries.firstOrNull { (_, bounds) ->
                                            val coordinates = bounds.takeIf { it.isAttached } ?: return@firstOrNull false
                                            val local = coordinates.localPositionOf(container, offset)
                                            local.x in 0f..coordinates.size.width.toFloat() && local.y in 0f..coordinates.size.height.toFloat()
                                        }?.key
                                        if (picture != null) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewerPicture = picture
                                            return@detectTapGestures
                                        }
                                        val hit = page.blocks.withIndex().firstNotNullOfOrNull { (blockIndex, block) ->
                                            val info = geometry.blocks[blockIndex] ?: return@firstNotNullOfOrNull null
                                            val coordinates = info.coordinates?.takeIf { it.isAttached } ?: return@firstNotNullOfOrNull null
                                            val local = coordinates.localPositionOf(container, offset)
                                            val inside = local.y >= 0 && local.y <= coordinates.size.height &&
                                                local.x >= -24 && local.x <= coordinates.size.width + 24
                                            if (!inside) return@firstNotNullOfOrNull null
                                            val character = info.layout?.getOffsetForPosition(Offset(local.x.coerceAtLeast(0f), local.y)) ?: 0
                                            block.paragraphIndex to block.startOffset + character.coerceIn(0, block.visibleLength)
                                        }
                                        if (hit != null) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            wordTarget = wordTargetAt(state.paragraphs, hit.first, hit.second, state.language)
                                        }
                                    },
                                )
                            }
                            .padding(safeInsets)
                            .padding(horizontal = margin, vertical = PageVerticalPadding),
                    ) {
                        PageContent(
                            page = page,
                            view = current,
                            state = state,
                            colors = colors,
                            live = true,
                            searchMark = searchMark,
                            footer = "${chapterIndex + 1} / ${pages.size}",
                            bookmarked = run {
                                val until = pages.getOrNull(chapterIndex + 1)?.startsAt ?: 1.0001f
                                chapterBookmarks.any { positionOf(it.paragraph, it.start).let { at -> at >= page.startsAt && at < until } }
                            },
                            onPicture = { viewerPicture = it },
                            onLayout = { blockIndex, coordinates, layout ->
                                val info = geometry.blocks.getOrPut(blockIndex) { BlockGeometry() }
                                if (coordinates != null) info.coordinates = coordinates
                                if (layout != null) info.layout = layout
                                val style = page.blocks.getOrNull(blockIndex)?.style
                                if (coordinates != null && style?.kind == ParagraphKind.IMAGE) geometry.pictures[style] = coordinates
                            },
                        )
                    }
                }
                LaunchedEffect(requestedPageIndex) {
                    requestedPageIndex?.let { target ->
                        if (target in 0 until pager.pageCount) pager.animateScrollToPage(target)
                        requestedPageIndex = null
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val pageBookmarks = chapterBookmarks.filter { positionOf(it.paragraph, it.start).let { at -> at >= pageSpan.first && at < pageSpan.second } }
            ReaderTopBar(
                state = state,
                colors = colors,
                bookmarked = pageBookmarks.isNotEmpty(),
                onBack = actions::back,
                onContents = { showContents = true },
                onSettings = { showSettings = true },
                onSearch = { showSearch = true },
                onBookmark = {
                    if (pageBookmarks.isNotEmpty()) {
                        pageBookmarks.forEach { actions.removeAnnotation(it.id) }
                    } else {
                        val (paragraph, offset) = pageStart
                        val text = state.paragraphs.getOrNull(paragraph).orEmpty()
                        val excerpt = text.substring(offset.coerceIn(0, text.length)).take(120).trim()
                        actions.addAnnotation(
                            Annotation(
                                kind = AnnotationKind.BOOKMARK,
                                chapter = state.chapterIndex,
                                paragraph = paragraph,
                                start = offset,
                                end = (offset + excerpt.length).coerceAtMost(text.length),
                                text = excerpt,
                            ),
                        )
                    }
                },
            )
        }
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ReaderBottomPanel(
                state = state,
                colors = colors,
                pageIndex = pageIndex,
                pageCount = pageCount,
                charactersLeft = charactersLeft,
                onPageChange = { requestedPage = it },
                onContents = { showContents = true },
                actions = actions,
            )
        }
        AnimatedVisibility(
            visible = !chromeVisible && state.narration.active,
            modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(ChromeBottomInsets).padding(end = 14.dp, bottom = 10.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            MiniNarrationButton(state.narration, colors, onClick = actions::playPause)
        }
        var showBrightness by remember { mutableStateOf(false) }
        LaunchedEffect(brightnessShownUntil) {
            showBrightness = brightnessShownUntil > System.currentTimeMillis()
            if (showBrightness) {
                kotlinx.coroutines.delay(brightnessShownUntil - System.currentTimeMillis())
                showBrightness = false
            }
        }
        AnimatedVisibility(
            visible = showBrightness,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 28.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                if (brightnessLevel >= 0f) "☀ ${(brightnessLevel * 100).roundToInt()}%" else "☾ ниже минимума · ${(-brightnessLevel * 100 / 0.6f).roundToInt()}%",
                color = colors.text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.surface.copy(alpha = 0.92f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            state = state,
            onDismiss = { showSettings = false },
            onPronunciations = {
                showSettings = false
                showPronunciations = true
            },
            actions = actions,
        )
    }
    if (showContents) {
        ContentsSheet(
            state = state,
            onDismiss = { showContents = false },
            onSelect = { chapter ->
                showContents = false
                actions.changeChapter(chapter)
            },
            onJump = { annotation ->
                showContents = false
                actions.jumpTo(annotation.chapter, annotation.paragraph, annotation.start)
            },
            onRemove = { actions.removeAnnotation(it.id) },
            onEditNote = { annotation ->
                showContents = false
                noteTarget = annotation to false
            },
        )
    }
    if (showSearch) SearchSheet(state, actions) { showSearch = false }
    if (showPronunciations) PronunciationListSheet(actions) { showPronunciations = false }
    wordTarget?.let { target ->
        WordActionsSheet(
            target = target,
            state = state,
            actions = actions,
            onPronunciation = { pronunciationTarget = target },
            onNote = { annotation, isNew -> noteTarget = annotation to isNew },
            onDismiss = { wordTarget = null },
        )
    }
    pronunciationTarget?.let { target -> PronunciationDialog(target, state.language, actions) { pronunciationTarget = null } }
    noteTarget?.let { (annotation, isNew) -> NoteDialog(annotation, isNew, actions) { noteTarget = null } }
    viewerPicture?.let { picture -> PictureViewer(state.bookId, picture) { viewerPicture = null } }
}

private val PageVerticalPadding = 18.dp

/** Share of the page width, from the left, where a vertical swipe sets the brightness. */
private const val BrightnessZone = 1f / 3

/**
 * A tap is a touch that stays in place. Once the finger travels past the touch slop the gesture is
 * marked consumed, after the pager and the brightness swipe have seen it, so the page's taps and long
 * presses ignore it: a vertical swipe never turns the page or toggles the controls.
 */
private fun Modifier.swipesAreNotTaps() = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        do {
            val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (moved) change.consume()
        } while (change.pressed)
    }
}

// System bar sizes that do not change when the bars hide, so showing the reader chrome never
// resizes it mid-animation.
@OptIn(ExperimentalLayoutApi::class)
private val ChromeTopInsets: WindowInsets
    @Composable get() = WindowInsets.statusBarsIgnoringVisibility
        .union(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

@OptIn(ExperimentalLayoutApi::class)
private val ChromeBottomInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBarsIgnoringVisibility
        .union(WindowInsets.displayCutout.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))

/** A chapter as the reader lays it out: the open one, or a neighbour shown at the pager's edge. */
internal data class ChapterView(
    val index: Int,
    val title: String,
    val label: String,
    val paragraphs: List<String>,
    val styles: Map<Int, ParagraphStyle>,
    val language: String,
)

/**
 * Chapters laid out lately, so turning into the next chapter and back does not lay them out again.
 * Only whole chapters are kept; a look at a chapter's first page is not.
 */
private class PageCache {
    private data class Key(
        val chapter: Int,
        val title: String,
        val label: String,
        val paragraphs: Int,
        val typography: ReaderTypography,
        val language: String,
        val width: Int,
        val height: Int,
    )

    private val laidOut = LinkedHashMap<Key, List<ReaderPage>>()

    fun pages(
        view: ChapterView,
        typography: ReaderTypography,
        width: Int,
        height: Int,
        maxPages: Int,
        paginate: () -> List<ReaderPage>,
    ): List<ReaderPage> {
        val key = Key(view.index, view.title, view.label, view.paragraphs.size, typography, view.language, width, height)
        laidOut[key]?.let { return if (maxPages < it.size) it.take(maxPages) else it }
        val pages = paginate()
        if (maxPages == Int.MAX_VALUE) {
            laidOut[key] = pages
            while (laidOut.size > 4) laidOut.remove(laidOut.keys.first())
        }
        return pages
    }
}

/** What a page shows: the chapter heading on its first page, the blocks, and the page number. */
@Composable
private fun PageContent(
    page: ReaderPage,
    view: ChapterView,
    state: ReaderUiState,
    colors: ReadingColors,
    /** The open chapter: narration, bookmarks and search marks show on it. */
    live: Boolean,
    searchMark: ReaderJump?,
    footer: String,
    bookmarked: Boolean = false,
    onPicture: (ParagraphStyle) -> Unit,
    onLayout: (Int, LayoutCoordinates?, TextLayoutResult?) -> Unit,
) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (page.hasHeading) {
                ChapterHeading(view.title, colors, view.label, state.typography, highlighted = live && state.narration.paragraphIndex == -1)
            }
            page.blocks.forEachIndexed { blockIndex, block ->
                if (block.gapPx > 0) Spacer(Modifier.height(with(density) { block.gapPx.toDp() }))
                PageBlock(
                    block = block,
                    view = view,
                    state = state,
                    colors = colors,
                    live = live,
                    searchMark = searchMark,
                    onPicture = onPicture,
                    onLayout = { coordinates, layout -> onLayout(blockIndex, coordinates, layout) },
                )
            }
            if (view.paragraphs.isEmpty()) {
                Text("В этой главе пока нет доступного текста.", color = colors.muted)
            }
        }
        Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.BottomCenter) {
            if (bookmarked) {
                Icon(
                    Icons.Filled.Bookmark,
                    contentDescription = "Закладка на странице",
                    tint = colors.accent,
                    modifier = Modifier.align(Alignment.BottomEnd).size(16.dp),
                )
            }
            Text(
                footer,
                color = colors.muted.copy(alpha = 0.8f),
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun ChapterHeading(title: String, colors: ReadingColors, label: String, typography: ReaderTypography, highlighted: Boolean) {
    Column {
        Text(label, color = colors.accent, style = readerLabelStyle)
        Spacer(Modifier.height(ReaderRhythm.headingGap))
        Text(
            title,
            color = colors.text,
            style = readerTitleStyle(typography.fontSizeSp),
            modifier = if (highlighted) Modifier.background(colors.highlight, RoundedCornerShape(6.dp)) else Modifier,
        )
        Spacer(Modifier.height(ReaderRhythm.headingGap))
        Box(Modifier.size(width = 42.dp, height = ReaderRhythm.dividerHeight).background(colors.accent))
        Spacer(Modifier.height(ReaderRhythm.afterHeading))
    }
}

@Composable
private fun PageBlock(
    block: ReaderBlock,
    view: ChapterView,
    state: ReaderUiState,
    colors: ReadingColors,
    live: Boolean,
    searchMark: ReaderJump?,
    onPicture: (ParagraphStyle) -> Unit,
    onLayout: (LayoutCoordinates?, TextLayoutResult?) -> Unit,
) {
    val kind = block.style?.kind
    when (kind) {
        ParagraphKind.IMAGE -> {
            PagePicture(block, state.bookId, colors, onPicture, onLayout)
            return
        }
        ParagraphKind.TABLE_ROW, ParagraphKind.TABLE_HEADER -> {
            TableRow(block, view, state, colors, live)
            return
        }
        else -> Unit
    }
    val sceneBreak = block.text == SCENE_BREAK
    val marked = markedText(block, view, state, colors, live, searchMark)
    val textStyle = readerStyleFor(kind, state.typography, view.language, block.paragraphStart, centered = sceneBreak)
    val color = when {
        sceneBreak || kind == ParagraphKind.CAPTION -> colors.muted
        kind == ParagraphKind.HEADING -> colors.accent
        else -> colors.text
    }
    val text = @Composable { modifier: Modifier ->
        Text(
            marked,
            modifier = modifier.onGloballyPositioned { onLayout(it, null) },
            color = color,
            style = textStyle,
            maxLines = block.maxLines,
            overflow = TextOverflow.Clip,
            onTextLayout = { onLayout(null, it) },
        )
    }
    if (kind == ParagraphKind.NOTE) {
        // A note or a sidebar: set off by a bar on the left and a light tint.
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                .background(colors.accent.copy(alpha = 0.06f))
                .height(IntrinsicSize.Min),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accent.copy(alpha = 0.55f)))
            text(
                Modifier.weight(1f).padding(
                    start = ReaderInsets.noteStart - 3.dp,
                    end = ReaderInsets.noteEnd,
                    top = ReaderInsets.noteVertical,
                    bottom = ReaderInsets.noteVertical,
                ),
            )
        }
    } else {
        text(Modifier.fillMaxWidth())
    }
}

/** The block's text with bookmarks' highlights, the spoken sentence and word, and a search hit marked. */
private fun markedText(
    block: ReaderBlock,
    view: ChapterView,
    state: ReaderUiState,
    colors: ReadingColors,
    live: Boolean,
    searchMark: ReaderJump?,
): AnnotatedString {
    fun local(offset: Int) = (offset - block.startOffset).coerceIn(0, block.visibleLength)
    return buildAnnotatedString {
        append(block.text)
        state.annotations.forEach { annotation ->
            if (annotation.kind != AnnotationKind.HIGHLIGHT || annotation.chapter != view.index ||
                annotation.paragraph != block.paragraphIndex
            ) return@forEach
            val from = local(annotation.start)
            val to = local(annotation.end)
            if (to > from) {
                // A dotted line hints that the highlight carries a note.
                val decoration = if (annotation.note.isNotBlank()) TextDecoration.Underline else null
                addStyle(SpanStyle(background = colors.marker, textDecoration = decoration), from, to)
            }
        }
        if (!live) return@buildAnnotatedString
        val narration = state.narration
        if (narration.paragraphIndex == block.paragraphIndex) {
            val start = local(narration.textOffset)
            val end = local(narration.textOffset + narration.textLength)
            if (end > start) addStyle(SpanStyle(background = colors.highlight), start, end)
            if (narration.wordOffset >= 0) {
                val wordStart = local(narration.wordOffset)
                val wordEnd = local(narration.wordOffset + narration.wordLength)
                if (wordEnd > wordStart) addStyle(SpanStyle(background = colors.wordHighlight), wordStart, wordEnd)
            }
        }
        val mark = searchMark?.mark
        if (mark != null && searchMark.chapter == view.index && searchMark.paragraph == block.paragraphIndex) {
            val from = local(mark.first)
            val to = local(mark.last + 1)
            if (to > from) addStyle(SpanStyle(background = colors.wordHighlight, fontWeight = FontWeight.SemiBold), from, to)
        }
    }
}

/** A picture at the size pagination gave it; a long press or the corner button opens it full screen. */
@Composable
private fun PagePicture(
    block: ReaderBlock,
    bookId: String,
    colors: ReadingColors,
    onPicture: (ParagraphStyle) -> Unit,
    onLayout: (LayoutCoordinates?, TextLayoutResult?) -> Unit,
) {
    val style = block.style ?: return
    val name = style.image ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    val picture by produceState<ImageBitmap?>(null, bookId, name, block.widthPx) {
        value = withContext(Dispatchers.IO) {
            runCatching { BookImages.load(context, bookId, name, block.widthPx)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(with(density) { block.widthPx.toDp() }, with(density) { block.heightPx.toDp() })
                .clip(RoundedCornerShape(4.dp))
                .background(if (picture == null) colors.line else Color.White)
                .onGloballyPositioned { onLayout(it, null) },
        ) {
            picture?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Иллюстрация",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape)
                    .background(colors.surface.copy(alpha = 0.78f))
                    .clickable { onPicture(style) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ZoomIn, contentDescription = "Открыть иллюстрацию", tint = colors.accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A row of a table: cells side by side, the heading row in bold on a tint. */
@Composable
private fun TableRow(block: ReaderBlock, view: ChapterView, state: ReaderUiState, colors: ReadingColors, live: Boolean) {
    val density = LocalDensity.current
    val kind = block.style?.kind
    val cells = block.text.split('\t')
    val spoken = live && state.narration.paragraphIndex == block.paragraphIndex
    val cellStyle = readerStyleFor(kind, state.typography, view.language)
    Row(
        Modifier.fillMaxWidth()
            .height(with(density) { block.heightPx.toDp() })
            .background(
                when {
                    spoken -> colors.highlight
                    kind == ParagraphKind.TABLE_HEADER -> colors.accent.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .border(0.5.dp, colors.line),
    ) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(colors.line))
            Text(
                cell,
                modifier = Modifier.weight(1f).padding(horizontal = ReaderInsets.cellHorizontal, vertical = ReaderInsets.cellVertical),
                color = colors.text,
                style = cellStyle,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** A picture full screen: pinch or double tap to zoom, drag to look around. */
@Composable
private fun PictureViewer(bookId: String, picture: ParagraphStyle, onDismiss: () -> Unit) {
    val name = picture.image ?: return
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, bookId, name) {
        value = withContext(Dispatchers.IO) {
            runCatching { BookImages.load(context, bookId, name, 2_400)?.asImageBitmap() }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        val limitX = size.width * (scale - 1f) / 2f
                        val limitY = size.height * (scale - 1f) / 2f
                        offset = Offset(
                            (offset.x + pan.x).coerceIn(-limitX, limitX),
                            (offset.y + pan.y).coerceIn(-limitY, limitY),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            // Zoom toward the tapped point.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = (center - tap) * 1.5f
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            val shown = bitmap
            if (shown == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Image(
                    bitmap = shown,
                    contentDescription = "Иллюстрация",
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
            }
        }
    }
}

@Composable
private fun MiniNarrationButton(narration: ReaderNarrationUi, colors: ReadingColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape)
            .background(colors.surface.copy(alpha = 0.92f))
            .border(1.dp, colors.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (narration.preparing) {
            CircularProgressIndicator(Modifier.size(22.dp), color = colors.accent, strokeWidth = 2.dp)
        } else {
            Icon(
                if (narration.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (narration.playing) "Пауза" else "Продолжить",
                tint = colors.accent,
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    colors: ReadingColors,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onBookmark: () -> Unit,
) {
    Surface(color = colors.surface, shadowElevation = 3.dp) {
        // Padding for the status bar whether or not it is shown: the bar slides in over this band,
        // so the panel keeps its height while both animate.
        Column(Modifier.fillMaxWidth().windowInsetsPadding(ChromeTopInsets)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К библиотеке", tint = colors.accent)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
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
                IconButton(onClick = onBookmark) {
                    Icon(
                        if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (bookmarked) "Убрать закладку" else "Закладка на этой странице",
                        tint = colors.accent,
                    )
                }
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Поиск по книге", tint = colors.accent)
                }
                IconButton(onClick = onContents) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Оглавление", tint = colors.accent)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.TextFields, contentDescription = "Оформление", tint = colors.accent)
                }
            }
            LinearProgressIndicator(
                progress = { state.overallProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = colors.accent,
                trackColor = colors.line,
            )
        }
    }
}

@Composable
private fun ReaderBottomPanel(
    state: ReaderUiState,
    colors: ReadingColors,
    pageIndex: Int,
    pageCount: Int,
    charactersLeft: Int,
    onPageChange: (Int) -> Unit,
    onContents: () -> Unit,
    actions: ReaderActions,
) {
    Surface(color = colors.surface, shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(ChromeBottomInsets).padding(top = 10.dp, bottom = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Стр. ${pageIndex + 1} из $pageCount",
                    color = colors.text,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                val minutes = if (state.narration.active) {
                    charactersLeft / (850f * state.narration.speed)
                } else {
                    charactersLeft / 1_100f
                }
                Text(
                    remainingLabel(minutes, listening = state.narration.active),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (pageCount > 1) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: pageIndex.toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onPageChange(it.roundToInt()) }
                        dragging = null
                    },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    modifier = Modifier.padding(horizontal = 12.dp).height(34.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.line,
                    ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { actions.changeChapter(state.chapterIndex - 1) },
                    enabled = state.chapterIndex > 0,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                ) {
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
                    Text("Глава")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onContents, colors = ButtonDefaults.textButtonColors(contentColor = colors.muted)) {
                    Text("${state.chapterIndex + 1} / ${state.chapterCount}  ·  ${(state.overallProgress * 100).roundToInt()}%")
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        if (state.chapterIndex + 1 < state.chapterCount) actions.changeChapter(state.chapterIndex + 1)
                        else actions.importNextChapter()
                    },
                    enabled = state.chapterIndex + 1 < state.chapterCount || state.hasNextWebChapter,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                ) {
                    Text(if (state.chapterIndex + 1 < state.chapterCount) "Глава" else "Загрузить")
                    Icon(
                        if (state.chapterIndex + 1 < state.chapterCount) Icons.AutoMirrored.Filled.NavigateNext else Icons.Filled.Download,
                        contentDescription = null,
                    )
                }
            }
            HorizontalDivider(color = colors.line, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            NarrationControls(state, colors, actions)
            if (state.narration.voiceReady) VoiceCaption(state.narration, colors, actions::openVoices)
        }
    }
}

private fun remainingLabel(minutes: Float, listening: Boolean): String {
    val suffix = if (listening) "слушать" else "читать"
    return when {
        minutes < 1f -> "меньше минуты"
        minutes < 60f -> "≈ ${minutes.roundToInt()} мин $suffix"
        else -> "≈ ${(minutes / 60).toInt()} ч ${(minutes % 60).roundToInt()} мин $suffix"
    }
}

/** Which voice reads this chapter; a tap opens the engine and voice picker. */
@Composable
private fun VoiceCaption(narration: ReaderNarrationUi, colors: ReadingColors, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Читает: ${narration.voiceLabel}",
            color = colors.muted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("Сменить", color = colors.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NarrationControls(state: ReaderUiState, colors: ReadingColors, actions: ReaderActions) {
    val narration = state.narration
    if (!narration.voiceReady) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Озвучка на устройстве", color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Выберите и скачайте голос — дальше без интернета",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(onClick = actions::openVoices) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Голоса")
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions::previousSentence, enabled = narration.active) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущая фраза", tint = if (narration.active) colors.text else colors.line)
        }
        Box(
            modifier = Modifier.size(58.dp).clip(CircleShape).background(colors.accent).clickable(onClick = actions::playPause),
            contentAlignment = Alignment.Center,
        ) {
            if (narration.preparing) {
                CircularProgressIndicator(Modifier.size(26.dp), color = colors.surface, strokeWidth = 2.5.dp)
            } else {
                Icon(
                    if (narration.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (narration.playing) "Пауза" else "Слушать",
                    tint = colors.surface,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        IconButton(onClick = actions::nextSentence, enabled = narration.active) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Следующая фраза", tint = if (narration.active) colors.text else colors.line)
        }
        Spacer(Modifier.weight(1f))
        var speedMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { speedMenu = true }, colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) {
                Text(speechSpeedLabel(narration.speed), fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                speedPresets.forEach { speed ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                speechSpeedLabel(speed),
                                fontWeight = if (abs(speed - narration.speed) < 0.01f) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            speedMenu = false
                            actions.setSpeed(speed)
                        },
                    )
                }
            }
        }
        var sleepMenu by remember { mutableStateOf(false) }
        val sleepActive = narration.sleepEndsAt != null || narration.sleepAtChapterEnd
        Box {
            IconButton(onClick = { sleepMenu = true }, enabled = narration.active) {
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = "Таймер сна",
                    tint = when {
                        sleepActive -> colors.accent
                        narration.active -> colors.text
                        else -> colors.line
                    },
                )
            }
            DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                narration.sleepEndsAt?.let { endsAt ->
                    val left = ((endsAt - System.currentTimeMillis()) / 60_000f).roundToInt().coerceAtLeast(0)
                    DropdownMenuItem(text = { Text("Осталось ≈ $left мин", color = colors.muted) }, onClick = {}, enabled = false)
                }
                listOf(10, 20, 30, 45, 60).forEach { minutes ->
                    DropdownMenuItem(text = { Text("Через $minutes мин") }, onClick = {
                        sleepMenu = false
                        actions.setSleepTimer(minutes)
                    })
                }
                DropdownMenuItem(
                    text = { Text(if (narration.sleepAtChapterEnd) "В конце главы ✓" else "В конце главы") },
                    onClick = {
                        sleepMenu = false
                        actions.setSleepTimer(-1)
                    },
                )
                if (sleepActive) {
                    DropdownMenuItem(text = { Text("Выключить таймер") }, onClick = {
                        sleepMenu = false
                        actions.setSleepTimer(0)
                    })
                }
            }
        }
        if (narration.active) {
            IconButton(onClick = actions::stopNarration) {
                Icon(Icons.Filled.Stop, contentDescription = "Остановить озвучку", tint = colors.muted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
    state: ReaderUiState,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onJump: (Annotation) -> Unit,
    onRemove: (Annotation) -> Unit,
    onEditNote: (Annotation) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val ordered = state.annotations.sortedWith(compareBy({ it.chapter }, { it.paragraph }, { it.start }))
    val bookmarks = ordered.filter { it.kind == AnnotationKind.BOOKMARK }
    val quotes = ordered.filter { it.kind == AnnotationKind.HIGHLIGHT }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Главы", "Закладки · ${bookmarks.size}", "Цитаты · ${quotes.size}").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
            }
        }
        when (tab) {
            0 -> {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.chapterIndex - 2).coerceAtLeast(0))
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                    itemsIndexed(state.chapterTitles) { index, title ->
                        val current = index == state.chapterIndex
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onSelect(index) }
                                .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                modifier = Modifier.width(42.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                title.ifBlank { "Глава ${index + 1}" },
                                modifier = Modifier.weight(1f),
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (index < state.chapterIndex) {
                                Text("✓", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            else -> {
                val items = if (tab == 1) bookmarks else quotes
                if (items.isEmpty()) {
                    Text(
                        if (tab == 1) {
                            "Закладка ставится значком вверху страницы или долгим нажатием на фразу."
                        } else {
                            "Выделите фразу долгим нажатием — она появится здесь, можно добавить заметку."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(items, key = { it.id }) { annotation ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onJump(annotation) }.padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    state.chapterTitles.getOrNull(annotation.chapter)?.ifBlank { null } ?: "Глава ${annotation.chapter + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (annotation.kind == AnnotationKind.HIGHLIGHT) "«${annotation.text}»" else annotation.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (annotation.note.isNotBlank()) {
                                    Text(
                                        annotation.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (annotation.kind == AnnotationKind.HIGHLIGHT) {
                                IconButton(onClick = { onEditNote(annotation) }) {
                                    Icon(Icons.Filled.EditNote, contentDescription = "Заметка")
                                }
                            }
                            IconButton(onClick = { onRemove(annotation) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    state: ReaderUiState,
    onDismiss: () -> Unit,
    onPronunciations: () -> Unit,
    actions: ReaderActions,
) {
    val typography = state.typography
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Оформление", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            item {
                SettingsLabel("Тема")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice("Светлая", ReaderTheme.LIGHT, state.theme, actions::themeChanged, Modifier.weight(1f))
                    ThemeChoice("Сепия", ReaderTheme.SEPIA, state.theme, actions::themeChanged, Modifier.weight(1f))
                    ThemeChoice("Тёмная", ReaderTheme.DARK, state.theme, actions::themeChanged, Modifier.weight(1f))
                    ThemeChoice("Чёрная", ReaderTheme.BLACK, state.theme, actions::themeChanged, Modifier.weight(1f))
                }
            }
            item {
                SettingsLabel(if (state.warmLight > 0f) "Тёплый свет · ${(state.warmLight * 100).roundToInt()}%" else "Тёплый свет · выключен")
                Slider(
                    value = state.warmLight,
                    onValueChange = { actions.warmLightChanged((it * 20).roundToInt() / 20f) },
                    valueRange = 0f..1f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.brightness == null) "Яркость: как в системе. Проведите вверх или вниз по левой трети страницы"
                        else "Яркость своя: ${if (state.brightness >= 0f) "${(state.brightness * 100).roundToInt()}%" else "ниже минимума"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.brightness != null) {
                        TextButton(onClick = { actions.brightnessChanged(null, final = true) }) { Text("Как в системе") }
                    }
                }
            }
            item {
                SettingsLabel("Размер текста · ${typography.fontSizeSp.roundToInt()}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("А", fontSize = 15.sp)
                    Slider(
                        value = typography.fontSizeSp.coerceIn(14f, 32f),
                        onValueChange = { actions.typographyChanged(typography.copy(fontSizeSp = it.roundToInt().toFloat())) },
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        valueRange = 14f..32f,
                        steps = 17,
                    )
                    Text("А", fontSize = 27.sp)
                }
            }
            item {
                SettingsLabel("Шрифт")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReaderFont.entries.forEach { font ->
                        FilterChip(
                            selected = typography.font == font,
                            onClick = { actions.typographyChanged(typography.copy(font = font)) },
                            label = { Text(font.label, fontFamily = font.family) },
                        )
                    }
                }
            }
            item {
                SettingsLabel("Межстрочный интервал")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.35f to "Плотный", 1.55f to "Обычный", 1.8f to "Свободный").forEach { (value, title) ->
                        FilterChip(
                            selected = abs(typography.lineSpacing - value) < 0.01f,
                            onClick = { actions.typographyChanged(typography.copy(lineSpacing = value)) },
                            label = { Text(title) },
                        )
                    }
                }
            }
            item {
                SettingsLabel("Поля")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderMargin.entries.forEach { margin ->
                        FilterChip(
                            selected = typography.margin == margin,
                            onClick = { actions.typographyChanged(typography.copy(margin = margin)) },
                            label = { Text(margin.label) },
                        )
                    }
                }
            }
            item {
                ToggleRow(
                    title = "Выравнивание по ширине",
                    subtitle = "С переносами слов, как в бумажной книге",
                    checked = typography.justify,
                    onChange = { actions.typographyChanged(typography.copy(justify = it)) },
                )
                ToggleRow(
                    title = "Красная строка",
                    subtitle = "Отступ в начале абзаца вместо пустой строки",
                    checked = typography.paragraphIndent,
                    onChange = { actions.typographyChanged(typography.copy(paragraphIndent = it)) },
                )
                ToggleRow(
                    title = "Подсвечивать слово при озвучке",
                    subtitle = "Для голосов, которые сообщают границы слов, например RuVoice",
                    checked = state.highlightWords,
                    onChange = actions::highlightWordsChanged,
                )
                if (state.isWebBook) {
                    ToggleRow(
                        title = "Подгружать главы с сайта",
                        subtitle = "Следующая глава загружается заранее — чтение и озвучка идут без остановки",
                        checked = state.autoLoadWebChapters,
                        onChange = actions::autoLoadWebChaptersChanged,
                    )
                }
                ToggleRow(
                    title = "Листать кнопками громкости",
                    subtitle = "Во время озвучки кнопки меняют громкость",
                    checked = state.volumeKeysTurnPages,
                    onChange = actions::volumeKeysChanged,
                )
                ToggleRow(
                    title = "Не гасить экран",
                    subtitle = "Пока открыта книга",
                    checked = state.keepScreenOn,
                    onChange = actions::keepScreenOnChanged,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onPronunciations).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Произношение слов", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (state.pronunciationCount == 0) "Долгое нажатие на слово → «Как произносить»" else "Сохранено: ${state.pronunciationCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
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
                    OutlinedButton(onClick = { onDismiss(); actions.export("epub") }, modifier = Modifier.weight(1f)) { Text("EPUB") }
                    OutlinedButton(onClick = { onDismiss(); actions.export("fb2") }, modifier = Modifier.weight(1f)) { Text("FB2") }
                }
            }
        }
    }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun SettingsLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
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
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("Aa", fontFamily = FontFamily.Serif, fontSize = 22.sp, color = colors.text)
        Text(title, fontSize = 11.sp, color = colors.text, maxLines = 1, textAlign = TextAlign.Center)
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(if (theme == selected) colors.accent else colors.line),
        )
    }
}

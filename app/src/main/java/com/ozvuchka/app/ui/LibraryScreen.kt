package com.ozvuchka.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val libraryFilters = listOf("Все", "Читаю", "Прочитано", "PDF", "EPUB", "FB2")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<LibraryBookUi>,
    onOpenBook: (String) -> Unit,
    onImportFile: () -> Unit,
    onImportUrl: (String) -> Unit,
    onDeleteBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Все") }
    var showImportSheet by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val visibleBooks = remember(books, query, filter) {
        books.filter { book ->
            val matchesQuery = query.isBlank() || listOf(book.title, book.author, book.format)
                .any { it.contains(query.trim(), ignoreCase = true) }
            val matchesFilter = when (filter) {
                "Читаю" -> book.progress > 0f && book.progress < 0.995f
                "Прочитано" -> book.progress >= 0.995f
                "Все" -> true
                else -> book.format.equals(filter, ignoreCase = true)
            }
            matchesQuery && matchesFilter
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showImportSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text("+", fontSize = 30.sp, fontWeight = FontWeight.Light)
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 158.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 108.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibraryHeader(bookCount = books.size)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Название, автор или формат") },
                    leadingIcon = { Text("⌕", fontSize = 28.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(libraryFilters.size) { index ->
                        val name = libraryFilters[index]
                        FilterChip(
                            selected = filter == name,
                            onClick = { filter = name },
                            label = { Text(name) },
                        )
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (books.isEmpty()) "Начните свою коллекцию" else "На вашей полке",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (books.isNotEmpty()) {
                        Text(
                            "${visibleBooks.size} из ${books.size}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            if (visibleBooks.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryEmptyState(
                        isLibraryEmpty = books.isEmpty(),
                        onImportFile = onImportFile,
                        onImportUrl = { showImportSheet = true },
                    )
                }
            } else {
                items(visibleBooks, key = { it.id }) { book ->
                    LibraryBookCard(
                        book = book,
                        onClick = { onOpenBook(book.id) },
                        onRequestDelete = { pendingDeleteId = book.id },
                    )
                }
            }
        }
    }

    if (showImportSheet) {
        ModalBottomSheet(onDismissRequest = { showImportSheet = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Добавить историю", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Импортируйте книгу с устройства или вставьте ссылку на главу.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        showImportSheet = false
                        onImportFile()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 15.dp),
                ) {
                    Text("Выбрать файл", fontSize = 16.sp)
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ссылка на веб-главу") },
                    placeholder = { Text("https://...") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                OutlinedButton(
                    onClick = {
                        val input = url.trim()
                        if (input.isNotEmpty()) {
                            showImportSheet = false
                            url = ""
                            onImportUrl(input)
                        }
                    },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 15.dp),
                ) {
                    Text("Открыть ссылку", fontSize = 16.sp)
                }
            }
        }
    }

    books.firstOrNull { it.id == pendingDeleteId }?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Удалить книгу?") },
            text = {
                Text("«${book.title}» исчезнет из библиотеки. Чтобы вернуть книгу, её потребуется импортировать снова.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId = null
                        onDeleteBook(book.id)
                    },
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun LibraryHeader(bookCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "OZVUCHKA  ·  ВАША БИБЛИОТЕКА",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Text(
            "Истории всегда\nс вами.",
            fontFamily = FontFamily.Serif,
            fontSize = 38.sp,
            lineHeight = 42.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            if (bookCount == 0) "Тихое место для книг, глав и голосов."
            else "Читайте в своём ритме. Продолжайте слушать там, где остановились.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun LibraryBookCard(
    book: LibraryBookUi,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val palette = remember(book.id) {
        val palettes = listOf(
            listOf(Color(0xFF403969), Color(0xFF8773AB)),
            listOf(Color(0xFF21535E), Color(0xFF6A9D94)),
            listOf(Color(0xFF7A433D), Color(0xFFC68969)),
            listOf(Color(0xFF333F68), Color(0xFF7184B2)),
            listOf(Color(0xFF655057), Color(0xFFA98990)),
        )
        palettes[book.id.hashCode().ushr(1) % palettes.size]
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(9.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(176.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(palette)),
            ) {
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).size(135.dp)
                        .background(Color.White.copy(alpha = 0.08f), CircleShape),
                )
                Text(
                    book.title.take(1).uppercase(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp),
                    fontFamily = FontFamily.Serif,
                    fontSize = 112.sp,
                    lineHeight = 112.sp,
                    color = Color.White.copy(alpha = 0.17f),
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(15.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("OZVUCHKA", color = Color.White.copy(alpha = 0.78f), fontSize = 9.sp, letterSpacing = 2.sp)
                    Text(
                        book.title,
                        color = Color.White,
                        fontFamily = FontFamily.Serif,
                        fontSize = 21.sp,
                        lineHeight = 24.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    book.format.uppercase(),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color.Black.copy(alpha = 0.20f))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                book.title,
                modifier = Modifier.padding(horizontal = 5.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                minLines = 2,
                lineHeight = 20.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    book.author.ifBlank { "Автор не указан" },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Text("⋮", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Удалить книгу") },
                            onClick = {
                                showMenu = false
                                onRequestDelete()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            LinearProgressIndicator(
                progress = book.progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, top = 7.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (book.progress > 0f) "Продолжить" else "Открыть",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (book.progress > 0f) {
                    Text("${(book.progress.coerceIn(0f, 1f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    isLibraryEmpty: Boolean,
    onImportFile: () -> Unit,
    onImportUrl: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("◫", fontFamily = FontFamily.Serif, fontSize = 70.sp, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            if (isLibraryEmpty) "Пока здесь тихо" else "Ничего не найдено",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (isLibraryEmpty) "Добавьте книгу или ссылку на главу — и возвращайтесь к чтению в любой момент."
            else "Попробуйте другой запрос или выберите фильтр «Все».",
            modifier = Modifier.fillMaxWidth(0.83f),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isLibraryEmpty) {
            Spacer(Modifier.height(3.dp))
            Button(onClick = onImportFile) { Text("Выбрать файл") }
            OutlinedButton(onClick = onImportUrl) { Text("Добавить по ссылке") }
        }
    }
}

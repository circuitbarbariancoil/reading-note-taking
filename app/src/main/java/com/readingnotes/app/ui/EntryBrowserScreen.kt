package com.readingnotes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Entry
import com.readingnotes.app.model.HighlightColor
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

/** A browsable entry carrying its parent book's metadata. */
data class BrowsableEntry(
    val entry: Entry,
    val bookTitle: String,
    val bookUid: String,
)

enum class EntrySortMode(val label: String) {
    Time("时间"),
    Page("页序"),
}

/**
 * The unified full-screen entry browser. Book scope is just a pre-applied,
 * removable filter: entering from a book seeds the book filter pill, which the
 * user can clear to widen to all books. Supports search, sort, and color/tag filtering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryBrowserScreen(
    entries: List<BrowsableEntry>,
    books: List<Book>,
    filterBookUid: String? = null,
    colors: List<HighlightColor> = emptyList(),
    onBack: () -> Unit,
    onEntryClick: (BrowsableEntry) -> Unit = {},
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterBook by remember(filterBookUid) { mutableStateOf(filterBookUid) }
    var filterColor by remember { mutableStateOf<String?>(null) }
    var filterTag by remember { mutableStateOf<String?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }
    var bookSheetOpen by remember { mutableStateOf(false) }
    var bookSearch by remember { mutableStateOf("") }

    // Global scope: time is the only field; direction + grouping are the choices.
    var globalNewest by remember { mutableStateOf(true) }
    var groupByBook by remember { mutableStateOf(false) }
    // Book scope: field choice (time/page) with flippable direction.
    var bookSortMode by remember { mutableStateOf(EntrySortMode.Time) }
    var bookAsc by remember { mutableStateOf(false) }

    // All unique tags across entries
    val allTags = remember(entries) {
        entries.flatMap { it.entry.tags }.distinct().sorted()
    }
    val composeColors = remember(colors) {
        colors.associate { hc -> hc.name to runCatching { Color(android.graphics.Color.parseColor(hc.css)) }.getOrDefault(Accent) }
    }

    // Apply filters
    val filtered = remember(entries, searchQuery, filterBook, filterColor, filterTag, globalNewest, groupByBook, bookSortMode, bookAsc) {
        var list = entries.toList()
        if (filterBook != null) {
            list = list.filter { it.bookUid == filterBook }
        }
        if (filterColor != null) {
            list = list.filter { it.entry.kind.name == "highlight" && it.entry.text.contains("~={$filterColor}") }
        }
        if (filterTag != null) {
            list = list.filter { filterTag in it.entry.tags }
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            list = list.filter {
                it.entry.text.lowercase().contains(q) ||
                    it.entry.annotation.lowercase().contains(q) ||
                    it.bookTitle.lowercase().contains(q)
            }
        }
        if (filterBook != null) {
            val cmp = when (bookSortMode) {
                EntrySortMode.Time -> compareBy<BrowsableEntry> { it.entry.createdAt }
                EntrySortMode.Page -> compareBy { it.entry.page }
            }
            list.sortedWith(if (bookAsc) cmp else cmp.reversed())
        } else {
            val timeCmp = if (globalNewest) {
                compareByDescending<BrowsableEntry> { it.entry.createdAt }
            } else {
                compareBy { it.entry.createdAt }
            }
            if (groupByBook) {
                list.sortedWith(compareBy<BrowsableEntry> { it.bookTitle }.then(timeCmp))
            } else {
                list.sortedWith(timeCmp)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Paper)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                fontSize = 26.sp,
                color = SumiSoft,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 8.dp),
            )
        }

        // The trailing "#…" token in the query drives Obsidian-style tag suggestions.
        val tagToken = remember(searchQuery) {
            val hash = searchQuery.lastIndexOf('#')
            if (hash >= 0 && !searchQuery.substring(hash + 1).contains(' ')) searchQuery.substring(hash + 1) else null
        }
        val tagSuggestions = remember(tagToken, allTags, entries) {
            if (tagToken == null) emptyList()
            else allTags.filter { it.contains(tagToken, ignoreCase = true) }
                .map { tag -> tag to entries.count { tag in it.entry.tags } }
                .take(8)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索摘抄、批注，# 选标签…", fontSize = 13.sp, color = SumiSoft) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = tagSuggestions.isNotEmpty(),
                    onDismissRequest = {},
                    properties = PopupProperties(focusable = false),
                ) {
                    tagSuggestions.forEach { (tag, count) ->
                        DropdownMenuItem(
                            text = { Text("#$tag $count 条", fontSize = 13.sp) },
                            onClick = {
                                filterTag = tag
                                searchQuery = searchQuery.substring(0, searchQuery.lastIndexOf('#')).trimEnd()
                            },
                        )
                    }
                }
            }
            Box {
                Text(
                    "⇅",
                    fontSize = 18.sp,
                    color = Sumi,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sortExpanded = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                if (filterBook != null) {
                    BookSortMenu(
                        expanded = sortExpanded,
                        onDismiss = { sortExpanded = false },
                        mode = bookSortMode,
                        asc = bookAsc,
                        onSelect = { mode ->
                            if (mode == bookSortMode) {
                                bookAsc = !bookAsc
                            } else {
                                bookSortMode = mode
                                bookAsc = mode == EntrySortMode.Page
                            }
                            sortExpanded = false
                        },
                    )
                } else {
                    GlobalSortMenu(
                        expanded = sortExpanded,
                        onDismiss = { sortExpanded = false },
                        newest = globalNewest,
                        onNewest = { globalNewest = it; sortExpanded = false },
                        groupByBook = groupByBook,
                        onGroupToggle = { groupByBook = !groupByBook; sortExpanded = false },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (books.isNotEmpty()) {
                Box {
                    val activeBook = filterBook?.let { uid -> books.find { it.uid == uid } }
                    if (activeBook != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Accent)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                activeBook.title,
                                fontSize = 12.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { bookSheetOpen = true },
                            )
                            Text(
                                " ✕",
                                fontSize = 12.sp,
                                color = Color.White,
                                modifier = Modifier.clickable { filterBook = null },
                            )
                        }
                    } else {
                        FilterChip(
                            label = "书籍 ▾",
                            selected = false,
                            onClick = { bookSheetOpen = true },
                        )
                    }
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(Hairline),
                )
            }
            colors.filter { it.active }.forEach { hc ->
                val tint = runCatching { Color(android.graphics.Color.parseColor(hc.css)) }.getOrDefault(Accent)
                val selected = filterColor == hc.name
                Box(
                    modifier = Modifier
                        .size(if (selected) 24.dp else 20.dp)
                        .clip(CircleShape)
                        .background(tint)
                        .then(if (selected) Modifier.border(2.dp, Sumi, CircleShape) else Modifier)
                        .clickable { filterColor = if (selected) null else hc.name },
                )
            }
            if (colors.any { it.active } && allTags.isNotEmpty()) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(Hairline),
                )
            }
            allTags.take(10).forEach { tag ->
                FilterChip(
                    label = "#$tag",
                    selected = filterTag == tag,
                    onClick = { filterTag = if (filterTag == tag) null else tag },
                )
            }
        }

        // Count label
        val sortLabel = if (filterBook != null) {
            "${bookSortMode.label}${if (bookAsc) "↑" else "↓"}"
        } else {
            (if (globalNewest) "最新" else "最早") + (if (groupByBook) " · 按书" else "")
        }
        Text(
            "${filtered.size} 条 · $sortLabel",
            fontSize = 11.sp,
            color = SumiSoft,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        // Entry list
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("暂无条目", fontSize = 14.sp, color = SumiSoft)
            }
        } else {
            val grouped = filterBook == null && groupByBook
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                filtered.forEachIndexed { index, item ->
                    if (grouped && (index == 0 || filtered[index - 1].bookTitle != item.bookTitle)) {
                        item(key = "header-${item.bookUid}") {
                            Text(
                                item.bookTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Sumi,
                                modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp),
                            )
                        }
                    }
                    item(key = item.entry.id) {
                        EntryCard(item, showBook = filterBook == null && !grouped, onClick = { onEntryClick(item) }, colors = composeColors)
                    }
                }
            }
        }
    }

    if (bookSheetOpen) {
        ModalBottomSheet(onDismissRequest = { bookSheetOpen = false; bookSearch = "" }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = bookSearch,
                    onValueChange = { bookSearch = it },
                    placeholder = { Text("搜索书名…", fontSize = 13.sp, color = SumiSoft) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    val matches = books.filter { bookSearch.isBlank() || it.title.contains(bookSearch, ignoreCase = true) }
                    items(matches, key = { it.uid }) { book ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    filterBook = book.uid
                                    bookSheetOpen = false
                                    bookSearch = ""
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(book.title, fontSize = 15.sp, color = Sumi, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${book.entries.size} 条", fontSize = 12.sp, color = SumiSoft)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GlobalSortMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    newest: Boolean,
    onNewest: (Boolean) -> Unit,
    groupByBook: Boolean,
    onGroupToggle: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (newest) "✓ 最新" else "　最新", fontSize = 13.sp) },
            onClick = { onNewest(true) },
        )
        DropdownMenuItem(
            text = { Text(if (!newest) "✓ 最早" else "　最早", fontSize = 13.sp) },
            onClick = { onNewest(false) },
        )
        androidx.compose.material3.HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (groupByBook) "✓ 按书分组" else "　按书分组", fontSize = 13.sp) },
            onClick = onGroupToggle,
        )
    }
}

@Composable
private fun BookSortMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    mode: EntrySortMode,
    asc: Boolean,
    onSelect: (EntrySortMode) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        EntrySortMode.entries.forEach { m ->
            val selected = m == mode
            val arrow = if (selected) (if (asc) " ↑" else " ↓") else ""
            DropdownMenuItem(
                text = { Text((if (selected) "✓ " else "　") + m.label + arrow, fontSize = 13.sp) },
                onClick = { onSelect(m) },
            )
        }
    }
}

@Composable
private fun EntryCard(
    item: BrowsableEntry,
    showBook: Boolean,
    colors: Map<String, Color>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        // Header: book + page
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBook) {
                Text(
                    item.bookTitle,
                    fontSize = 11.sp,
                    color = Accent,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(" · ", fontSize = 11.sp, color = SumiSoft)
            }
            Text("p.${item.entry.page}", fontSize = 11.sp, color = SumiSoft)
            Spacer(Modifier.weight(1f))
            Text(
                item.entry.createdAt.take(10),
                fontSize = 10.sp,
                color = Hairline,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box {
            EntryHtmlWebView(
                excerpt = item.entry.text,
                annotation = item.entry.annotation,
                colors = colors,
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
            // WebView swallows touches; this transparent layer keeps the whole card tappable.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    ),
            )
        }
        // Tags
        if (item.entry.tags.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item.entry.tags.take(5).forEach { tag ->
                    Text(
                        "#$tag",
                        fontSize = 10.sp,
                        color = Accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Accent.copy(alpha = 0.08f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tint: Color = Accent,
) {
    Text(
        label,
        fontSize = 12.sp,
        color = if (selected) Color.White else Sumi,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) tint else Color(0xFFEDE6D6))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

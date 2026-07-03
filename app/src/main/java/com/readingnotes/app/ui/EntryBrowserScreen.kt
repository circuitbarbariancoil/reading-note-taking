package com.readingnotes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    Time("按时间"),
    Page("按页序"),
    Book("按书名"),
}

/**
 * A full-screen entry browser. Shows all entries across books (global mode) or
 * filtered to a single book. Supports search, sort, and color/tag filtering.
 */
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
    var sortMode by remember { mutableStateOf(EntrySortMode.Time) }
    var filterBook by remember(filterBookUid) { mutableStateOf(filterBookUid) }
    var filterColor by remember { mutableStateOf<String?>(null) }
    var filterTag by remember { mutableStateOf<String?>(null) }
    var sortExpanded by remember { mutableStateOf(false) }
    var bookExpanded by remember { mutableStateOf(false) }

    // All unique tags across entries
    val allTags = remember(entries) {
        entries.flatMap { it.entry.tags }.distinct().sorted()
    }
    val composeColors = remember(colors) {
        colors.associate { hc -> hc.name to runCatching { Color(android.graphics.Color.parseColor(hc.css)) }.getOrDefault(Accent) }
    }

    // Apply filters
    val filtered = remember(entries, searchQuery, sortMode, filterBook, filterColor, filterTag) {
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
        when (sortMode) {
            EntrySortMode.Time -> list.sortedByDescending { it.entry.createdAt }
            EntrySortMode.Page -> list.sortedWith(compareBy({ it.bookTitle }, { it.entry.page }))
            EntrySortMode.Book -> list.sortedWith(compareBy({ it.bookTitle }, { it.entry.createdAt }))
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Paper)) {
        ScreenHeader(
            if (filterBookUid != null) "本书条目" else "全部条目",
            onBack,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索摘抄、批注…", fontSize = 13.sp, color = SumiSoft) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box {
                FilterChip(
                    label = sortMode.label,
                    selected = false,
                    onClick = { sortExpanded = true },
                )
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    EntrySortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                sortMode = mode
                                sortExpanded = false
                            },
                        )
                    }
                }
            }
        }

        if (filterBookUid == null && books.isNotEmpty()) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilterChip(
                    label = filterBook?.let { uid ->
                        val book = books.find { it.uid == uid }
                        if (book != null) "${book.title} (${book.entries.size})" else "全部书"
                    } ?: "全部书",
                    selected = filterBook != null,
                    onClick = { bookExpanded = true },
                )
                DropdownMenu(expanded = bookExpanded, onDismissRequest = { bookExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("全部书") },
                        onClick = {
                            filterBook = null
                            bookExpanded = false
                        },
                    )
                    books.forEach { book ->
                        DropdownMenuItem(
                            text = { Text("${book.title} (${book.entries.size})") },
                            onClick = {
                                filterBook = book.uid
                                bookExpanded = false
                            },
                        )
                    }
                }
            }
        }

        Text(
            "筛选",
            fontSize = 11.sp,
            color = SumiSoft,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            colors.forEach { hc ->
                val tint = runCatching { Color(android.graphics.Color.parseColor(hc.css)) }.getOrDefault(Accent)
                FilterChip(
                    label = hc.name,
                    selected = filterColor == hc.name,
                    onClick = { filterColor = if (filterColor == hc.name) null else hc.name },
                    tint = tint,
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
        Text(
            "${filtered.size} 条",
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
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(filtered, key = { it.entry.id }) { item ->
                    EntryCard(item, showBook = filterBookUid == null, onClick = { onEntryClick(item) }, colors = composeColors)
                }
            }
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
        EntryHtmlWebView(
            excerpt = item.entry.text,
            annotation = item.entry.annotation,
            colors = colors,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        )
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

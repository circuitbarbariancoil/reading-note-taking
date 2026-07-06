package com.readingnotes.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.model.Entry
import com.readingnotes.app.model.HighlightColor
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

private val Danger = Color(0xFFB3524A)

/**
 * The notebook: a pure entry list. Long-press enters multi-select for batch
 * deletion (same interaction as the bookshelf / page list). The + FAB opens a
 * blank editor; the note is only created on save.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookScreen(
    entries: List<Entry>,
    colors: List<HighlightColor> = emptyList(),
    onBack: () -> Unit,
    onNewEntry: () -> Unit,
    onEntryClick: (Entry) -> Unit,
    onDeleteEntries: (List<String>) -> Unit,
) {
    val selectedIds = remember { mutableStateListOf<String>() }
    val selectMode = selectedIds.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }

    val composeColors = remember(colors) {
        colors.associate { hc -> hc.name to runCatching { Color(android.graphics.Color.parseColor(hc.css)) }.getOrDefault(Accent) }
    }

    fun toggleSelect(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectMode) {
                    Text(
                        "已选 ${selectedIds.size} 条",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        color = Sumi,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "取消",
                        fontSize = 13.sp,
                        color = SumiSoft,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { selectedIds.clear() }
                            .padding(8.dp),
                    )
                    Text(
                        "删除",
                        fontSize = 13.sp,
                        color = Danger,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { confirmDelete = true }
                            .padding(8.dp),
                    )
                } else {
                    Text(
                        "‹",
                        fontSize = 26.sp,
                        color = SumiSoft,
                        modifier = Modifier.clickable(onClick = onBack).padding(end = 8.dp),
                    )
                    Text(
                        "笔记本",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 22.sp,
                        color = Sumi,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${entries.size} 条", fontSize = 13.sp, color = SumiSoft)
                }
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("—", fontSize = 24.sp, color = Hairline)
                        Spacer(Modifier.height(8.dp))
                        Text("点击 ＋ 新建笔记，或从其他 app 分享文字", fontSize = 13.sp, color = SumiSoft)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries.sortedByDescending { it.createdAt }, key = { it.id }) { entry ->
                        NoteCard(
                            entry = entry,
                            colors = composeColors,
                            selected = entry.id in selectedIds,
                            selectMode = selectMode,
                            onClick = {
                                if (selectMode) toggleSelect(entry.id) else onEntryClick(entry)
                            },
                            onLongClick = { toggleSelect(entry.id) },
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }

        // FAB (hide in select mode)
        if (!selectMode) {
            FloatingActionButton(
                onClick = onNewEntry,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                containerColor = Accent,
                contentColor = Color.White,
            ) {
                Text("＋", fontSize = 22.sp)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除笔记", fontFamily = FontFamily.Serif) },
            text = { Text("删除选中的 ${selectedIds.size} 条笔记？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntries(selectedIds.toList())
                    selectedIds.clear()
                    confirmDelete = false
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = SumiSoft) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    entry: Entry,
    colors: Map<String, Color>,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .then(
                if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
    ) {
        // Header: date + selection mark
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("笔记", fontSize = 11.sp, color = SumiSoft)
            Spacer(Modifier.weight(1f))
            Text(entry.createdAt.take(10), fontSize = 10.sp, color = Hairline)
            if (selectMode) {
                Spacer(Modifier.size(8.dp))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (selected) Accent else Color(0xFFEDE6D6)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Text("✓", fontSize = 11.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box {
            EntryHtmlWebView(
                excerpt = entry.text,
                annotation = entry.annotation,
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
            )
            // WebView swallows touches; this transparent layer keeps the whole card tappable.
            Box(
                Modifier
                    .matchParentSize()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    ),
            )
        }
        // Tags
        if (entry.tags.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.tags.take(5).forEach { tag ->
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

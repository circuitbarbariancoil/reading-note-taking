package com.readingnotes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

@Composable
fun NotebookScreen(
    entries: List<Entry>,
    onBack: () -> Unit,
    onNewEntry: () -> Unit,
    onEntryClick: (Entry) -> Unit,
    onDeleteEntry: (Entry) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    fontSize = 20.sp,
                    color = Sumi,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${entries.size} 条",
                    fontSize = 13.sp,
                    color = SumiSoft,
                )
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("—", fontSize = 24.sp, color = Hairline)
                        Spacer(Modifier.height(8.dp))
                        Text("点击 ＋ 新建笔记", fontSize = 13.sp, color = SumiSoft)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries.reversed(), key = { it.id }) { entry ->
                        NoteCard(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                            onDelete = { onDeleteEntry(entry) },
                        )
                    }
                }
            }
        }

        // FAB for new note
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

@Composable
private fun NoteCard(
    entry: Entry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(
            entry.text,
            fontSize = 14.sp,
            color = Sumi,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.annotation.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                entry.annotation,
                fontSize = 12.sp,
                color = SumiSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.tags.isNotEmpty()) {
                Text(
                    entry.tags.joinToString(" ") { "#$it" },
                    fontSize = 11.sp,
                    color = Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                entry.createdAt.take(10),
                fontSize = 10.sp,
                color = Hairline,
            )
        }
    }
}

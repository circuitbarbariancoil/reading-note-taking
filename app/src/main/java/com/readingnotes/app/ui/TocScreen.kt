package com.readingnotes.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Section
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.PaperPanel
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

/**
 * 目录 navigation + entry screen. Shows the current outline (tap a row to jump
 * to that page) and routes to the unified outline editor ([onEdit]) or to AI
 * generation from transient photos ([onCapture]) / PDF pages ([onImportPdf]).
 */
@Composable
fun TocScreen(
    book: Book,
    generating: Boolean,
    errorText: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onCapture: () -> Unit,
    onImportPdf: () -> Unit,
    onJumpToPage: (Int) -> Unit,
) {
    val sections = book.sections
    var choosingSource by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = SumiSoft,
                    modifier = Modifier.clickable(onClick = onBack).padding(end = 8.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "目录",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        color = Sumi,
                    )
                    Text(
                        if (sections.isEmpty()) "还没有目录" else "${sections.size} 个章节",
                        fontSize = 12.sp,
                        color = SumiSoft,
                    )
                }
                if (sections.isNotEmpty()) {
                    Text(
                        "编辑",
                        fontSize = 14.sp,
                        color = Accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                            .clickable(onClick = onEdit)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            if (errorText != null) {
                Text(
                    errorText,
                    fontSize = 12.sp,
                    color = Color(0xFFB3524A),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (sections.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("这本书还没有目录", fontSize = 14.sp, color = SumiSoft)
                    Spacer(Modifier.size(6.dp))
                    Text("用 AI 从目录页生成，或手动编写", fontSize = 12.sp, color = SumiSoft)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    items(sections, key = { it.id }) { section ->
                        SectionRow(
                            section = section,
                            onClick = { onJumpToPage(section.startPage) },
                            onLongClick = onEdit,
                        )
                    }
                }
            }

            // Bottom action row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    "✨ AI 生成目录",
                    fontSize = 14.sp,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                        .clickable { choosingSource = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    if (sections.isEmpty()) "＋ 手动编写" else "＋ 编辑目录",
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Accent)
                        .clickable(onClick = onEdit)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (generating) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Paper)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("识别目录中…", fontSize = 13.sp, color = Sumi)
                }
            }
        }
    }

    if (choosingSource) {
        SourceChooserDialog(
            onDismiss = { choosingSource = false },
            onCapture = {
                choosingSource = false
                onCapture()
            },
            onImportPdf = {
                choosingSource = false
                onImportPdf()
            },
        )
    }
}

@Composable
private fun SourceChooserDialog(
    onDismiss: () -> Unit,
    onCapture: () -> Unit,
    onImportPdf: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Paper)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("目录页来源", fontFamily = FontFamily.Serif, fontSize = 16.sp, color = Sumi)
            Text(
                "拍照或选 PDF 中的目录页，AI 识别后图片即丢弃，不会存进书里。",
                fontSize = 12.sp,
                color = SumiSoft,
            )
            ChooserButton("📷 拍照目录页", onCapture)
            ChooserButton("📄 从 PDF 选目录页", onImportPdf)
            Text(
                "取消",
                fontSize = 13.sp,
                color = SumiSoft,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ChooserButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 15.sp,
        color = Sumi,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PaperPanel)
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionRow(
    section: Section,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val indent = ((section.level - 1).coerceAtLeast(0) * 16).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 8.dp + indent, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            section.title,
            fontSize = if (section.level == 1) 15.sp else 14.sp,
            fontWeight = if (section.level == 1) FontWeight.Medium else FontWeight.Normal,
            color = if (section.level == 1) Sumi else SumiSoft,
            modifier = Modifier.weight(1f),
        )
        Text("P${section.startPage}", fontSize = 12.sp, color = SumiSoft)
    }
}

package com.readingnotes.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Section
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.PaperPanel
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

private val LEVEL_LABELS = listOf("部", "章", "节")

private fun levelLabel(level: Int): String = LEVEL_LABELS.getOrElse(level - 1) { "级$level" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TocScreen(
    book: Book,
    repository: BookRepository,
    generating: Boolean,
    errorText: String?,
    onBack: () -> Unit,
    onSaveSections: (List<Section>) -> Unit,
    onGenerate: (List<Int>, List<String>) -> Unit,
    onJumpToPage: (Int) -> Unit,
) {
    val sections = book.sections
    var editing by remember { mutableStateOf<Section?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

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
                    Text("用 AI 从目录页生成，或手动添加章节", fontSize = 12.sp, color = SumiSoft)
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
                            onLongClick = { editing = section },
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
                        .clickable { picking = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    "＋ 添加章节",
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Accent)
                        .clickable { addingNew = true }
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

    if (picking) {
        TocSourcePicker(
            book = book,
            repository = repository,
            onCancel = { picking = false },
            onConfirm = { pageNumbers, captureIds ->
                picking = false
                onGenerate(pageNumbers, captureIds)
            },
        )
    }

    if (addingNew) {
        val defaultPage = (sections.maxOfOrNull { it.startPage } ?: 0) + 1
        SectionEditDialog(
            initial = null,
            defaultPage = defaultPage,
            onDismiss = { addingNew = false },
            onConfirm = { title, startPage, level ->
                addingNew = false
                onSaveSections(
                    sections + Section(
                        id = java.util.UUID.randomUUID().toString().take(8),
                        title = title,
                        startPage = startPage,
                        level = level,
                    ),
                )
            },
            onDelete = null,
        )
    }

    editing?.let { target ->
        SectionEditDialog(
            initial = target,
            defaultPage = target.startPage,
            onDismiss = { editing = null },
            onConfirm = { title, startPage, level ->
                editing = null
                onSaveSections(
                    sections.map {
                        if (it.id == target.id) it.copy(title = title, startPage = startPage, level = level) else it
                    },
                )
            },
            onDelete = {
                editing = null
                onSaveSections(sections.filterNot { it.id == target.id })
            },
        )
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TocSourcePicker(
    book: Book,
    repository: BookRepository,
    onCancel: () -> Unit,
    onConfirm: (List<Int>, List<String>) -> Unit,
) {
    val selectedPages = remember { mutableStateListOf<Int>() }
    val selectedCaptures = remember { mutableStateListOf<String>() }
    val pages = remember(book.pages) { book.pages.sortedBy { it.page } }
    val count = selectedPages.size + selectedCaptures.size

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
                    modifier = Modifier.clickable(onClick = onCancel).padding(end = 8.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "选择目录页",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        color = Sumi,
                    )
                    Text("勾选包含目录的页（可多选）", fontSize = 12.sp, color = SumiSoft)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pages.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Text("页", fontSize = 12.sp, color = SumiSoft, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(pages, key = { "p-${it.page}-${it.addedAt}" }) { page ->
                        val path = repository.archiveImagePath(book, page)
                        val selected = page.page in selectedPages
                        SourceThumbnail(
                            imagePath = path,
                            label = "P${page.page}",
                            selected = selected,
                            onClick = {
                                if (selected) selectedPages.remove(page.page) else selectedPages.add(page.page)
                            },
                        )
                    }
                }
                if (book.captures.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Text("未处理", fontSize = 12.sp, color = SumiSoft, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(book.captures, key = { "c-${it.id}" }) { capture ->
                        val selected = capture.id in selectedCaptures
                        SourceThumbnail(
                            imagePath = capture.imagePath,
                            label = null,
                            selected = selected,
                            onClick = {
                                if (selected) selectedCaptures.remove(capture.id) else selectedCaptures.add(capture.id)
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                val enabled = count > 0
                Text(
                    if (enabled) "生成目录 ($count)" else "生成目录",
                    fontSize = 14.sp,
                    color = if (enabled) Color.White else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (enabled) Accent else SumiSoft.copy(alpha = 0.4f))
                        .clickable(enabled = enabled) {
                            onConfirm(selectedPages.toList(), selectedCaptures.toList())
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceThumbnail(
    imagePath: String?,
    label: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bitmap = remember(imagePath) {
        if (imagePath == null) null else runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(8.dp))
            .background(PaperPanel)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Accent else Hairline,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        if (label != null) {
            Text(
                label,
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (selected) {
            Text(
                "✓",
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Accent),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

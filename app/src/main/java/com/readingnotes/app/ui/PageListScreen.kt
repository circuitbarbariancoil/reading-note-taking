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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Capture
import com.readingnotes.app.model.Page
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.ui.PageNumberSheet
import com.readingnotes.app.ui.PageSheetAction
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

enum class PageSortMode { ByOrder, ByPageNumber }

enum class OcrJobState { Running, Failed }

/** Sealed type to identify items in the page list for selection. */
sealed class PageListItemId {
    data class ProcessedPage(val page: Int, val addedAt: String) : PageListItemId()
    data class UnprocessedCapture(val captureId: String) : PageListItemId()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageListScreen(
    book: Book,
    repository: BookRepository,
    ocrStatus: Map<String, OcrJobState>,
    onOpenPage: (Page) -> Unit,
    onCapture: () -> Unit,
    onBatchOcr: () -> Unit,
    onBack: () -> Unit,
    onOcrCapture: (Capture) -> Unit,
    processItems: List<ProcessItem> = emptyList(),
    onRetryProcessItem: (ProcessItem) -> Unit = {},
    onDismissProcessItem: (ProcessItem) -> Unit = {},
    queueCollapsed: Boolean = false,
    onExpandQueue: () -> Unit = {},
    onCollapseQueue: () -> Unit = {},
    onAssignPage: (Capture, Int) -> Unit,
    onFillPageNumber: (Capture) -> Unit,
    onDeleteCapture: (Capture) -> Unit,
    onDeletePages: (List<Page>) -> Unit = {},
    onDeleteCaptures: (List<Capture>) -> Unit = {},
    onEntries: () -> Unit = {},
) {
    var sortMode by remember { mutableStateOf(PageSortMode.ByPageNumber) }
    var assignPageDialog by remember { mutableStateOf<Capture?>(null) }
    val selectedItems = remember { mutableStateListOf<PageListItemId>() }
    val selectMode = selectedItems.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }

    fun toggleSelect(id: PageListItemId) {
        if (id in selectedItems) selectedItems.remove(id) else selectedItems.add(id)
    }

    val sortedPages = remember(book.pages, sortMode) {
        when (sortMode) {
            PageSortMode.ByOrder -> book.pages.sortedBy { it.addedAt }
            PageSortMode.ByPageNumber -> book.pages.sortedBy { it.page }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectMode) {
                    Text(
                        "‹",
                        fontSize = 26.sp,
                        color = SumiSoft,
                        modifier = Modifier.clickable { selectedItems.clear() }.padding(end = 8.dp),
                    )
                    Text(
                        "已选 ${selectedItems.size} 项",
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
                            .clickable { selectedItems.clear() }
                            .padding(8.dp),
                    )
                    Text(
                        "删除",
                        fontSize = 13.sp,
                        color = Color(0xFFB3524A),
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            book.title,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Medium,
                            fontSize = 18.sp,
                            color = Sumi,
                        )
                        Text(
                            "${book.pages.size}页 · ${book.captures.size}张未处理",
                            fontSize = 12.sp,
                            color = SumiSoft,
                        )
                    }
                    Text(
                        "条目",
                        fontSize = 12.sp,
                        color = Accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFEDE6D6))
                            .clickable(onClick = onEntries)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(
                        if (sortMode == PageSortMode.ByPageNumber) "按页序" else "按时间",
                        fontSize = 12.sp,
                        color = Accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFEDE6D6))
                            .clickable {
                                sortMode = if (sortMode == PageSortMode.ByPageNumber) PageSortMode.ByOrder else PageSortMode.ByPageNumber
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Processed pages section
                if (sortedPages.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Text("已处理", fontSize = 12.sp, color = SumiSoft, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    itemsIndexed(sortedPages, key = { index, page -> "page-$index-${page.page}-${page.addedAt}" }) { _, page ->
                        val itemId = PageListItemId.ProcessedPage(page.page, page.addedAt)
                        val selected = itemId in selectedItems
                        PageThumbnail(
                            page, book, repository,
                            selected = selected,
                            onClick = {
                                if (selectMode) toggleSelect(itemId) else onOpenPage(page)
                            },
                            onLongClick = { toggleSelect(itemId) },
                        )
                    }
                }

                // Unprocessed captures section
                if (book.captures.isNotEmpty()) {
                    val runningCount = book.captures.count { ocrStatus[it.id] == OcrJobState.Running }
                    item(span = { GridItemSpan(3) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("未处理", fontSize = 12.sp, color = SumiSoft)
                            Spacer(Modifier.weight(1f))
                            if (runningCount > 0) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = Accent,
                                )
                                Text(
                                    "OCR 中 剩 ${book.captures.size} 张",
                                    fontSize = 12.sp,
                                    color = Accent,
                                    modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                                )
                            } else {
                                Text(
                                    "全部 OCR",
                                    fontSize = 12.sp,
                                    color = Accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(onClick = onBatchOcr)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    items(book.captures, key = { "cap-${it.id}" }) { capture ->
                        val itemId = PageListItemId.UnprocessedCapture(capture.id)
                        val selected = itemId in selectedItems
                        CaptureThumbnail(
                            capture = capture,
                            state = ocrStatus[capture.id],
                            selected = selected,
                            onClick = {
                                if (selectMode) {
                                    toggleSelect(itemId)
                                } else {
                                    if (ocrStatus[capture.id] == OcrJobState.Running) return@CaptureThumbnail
                                    if (capture.ocrText != null) onFillPageNumber(capture) else assignPageDialog = capture
                                }
                            },
                            onLongClick = { toggleSelect(itemId) },
                        )
                    }
                }
            }

            // Bottom action row (hide in select mode)
            if (!selectMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "＋ 拍照",
                        fontSize = 14.sp,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Accent)
                            .clickable(onClick = onCapture)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        val hasActiveItems = processItems.any { it.step == ProcessStep.Queued || it.step == ProcessStep.Saving || it.step == ProcessStep.Ocr }
        if (processItems.isNotEmpty() && hasActiveItems && !selectMode) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (queueCollapsed) {
                    ProcessingQueueChip(
                        items = processItems,
                        onExpand = onExpandQueue,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                } else {
                    ProcessingQueueCard(
                        items = processItems,
                        onRetry = onRetryProcessItem,
                        onDismiss = onDismissProcessItem,
                        onClose = onCollapseQueue,
                    )
                }
            }
        }
    }

    assignPageDialog?.let { capture ->
        AssignPageDialog(
            failed = ocrStatus[capture.id] == OcrJobState.Failed,
            onDismiss = { assignPageDialog = null },
            onAssign = { pageNumber ->
                assignPageDialog = null
                onAssignPage(capture, pageNumber)
            },
            onOcr = {
                assignPageDialog = null
                onOcrCapture(capture)
            },
            onDelete = {
                assignPageDialog = null
                onDeleteCapture(capture)
            },
        )
    }

    if (confirmDelete) {
        val pageCount = selectedItems.count { it is PageListItemId.ProcessedPage }
        val captureCount = selectedItems.count { it is PageListItemId.UnprocessedCapture }
        val desc = buildString {
            if (pageCount > 0) append("${pageCount} 个已处理页")
            if (pageCount > 0 && captureCount > 0) append("和 ")
            if (captureCount > 0) append("${captureCount} 张未处理照片")
        }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${selectedItems.size} 项？") },
            text = { Text("将永久删除 $desc。相关条目、高亮和 Dropbox 备份也会被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val pagesToDelete = selectedItems.filterIsInstance<PageListItemId.ProcessedPage>()
                        .mapNotNull { sel -> book.pages.find { it.page == sel.page && it.addedAt == sel.addedAt } }
                    val capturesToDelete = selectedItems.filterIsInstance<PageListItemId.UnprocessedCapture>()
                        .mapNotNull { sel -> book.captures.find { it.id == sel.captureId } }
                    selectedItems.clear()
                    if (pagesToDelete.isNotEmpty()) onDeletePages(pagesToDelete)
                    if (capturesToDelete.isNotEmpty()) onDeleteCaptures(capturesToDelete)
                }) {
                    Text("删除", color = Color(0xFFB3524A))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageThumbnail(
    page: Page,
    book: Book,
    repository: BookRepository,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val imagePath = remember(page) { repository.archiveImagePath(book, page) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
            .background(if (selected) Color(0xFFEDE6D6) else Color.White)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFEDE6D6)),
            contentAlignment = Alignment.Center,
        ) {
            if (imagePath != null) {
                val bitmap = remember(imagePath) {
                    runCatching { BitmapFactory.decodeFile(imagePath)?.asImageBitmap() }.getOrNull()
                }
                bitmap?.let {
                    Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 10.sp)
                }
            } else if (page.ocrText != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("文", fontSize = 8.sp, color = Color.White)
                }
            }
        }
        Text(
            "p.${page.page}",
            fontSize = 11.sp,
            color = Sumi,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptureThumbnail(
    capture: Capture,
    state: OcrJobState?,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
            .background(if (selected) Color(0xFFEDE6D6) else Color(0xFFE8E2D8))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFDAD2C0)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = remember(capture.imagePath) {
                runCatching { BitmapFactory.decodeFile(capture.imagePath)?.asImageBitmap() }.getOrNull()
            }
            bitmap?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Text("?", fontSize = 18.sp, color = SumiSoft)

            when {
                selected -> Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 10.sp)
                }
                state == OcrJobState.Running -> Box(
                    modifier = Modifier.fillMaxSize().background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
                state == OcrJobState.Failed -> Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFB3524A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("!", fontSize = 10.sp, color = Color.White)
                }
            }
        }
        Text(
            when {
                state == OcrJobState.Failed -> "OCR 失败，点击重试"
                capture.ocrText != null -> "已识别 · 待填页码"
                else -> capture.capturedAt.take(10)
            },
            fontSize = 9.sp,
            color = when {
                state == OcrJobState.Failed -> Color(0xFFB3524A)
                capture.ocrText != null -> Accent
                else -> SumiSoft
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun AssignPageDialog(
    failed: Boolean,
    onDismiss: () -> Unit,
    onAssign: (Int) -> Unit,
    onOcr: () -> Unit,
    onDelete: () -> Unit,
) {
    PageNumberSheet(
        title = "处理照片",
        noteText = if (failed) {
            "上次 OCR 失败，可重试。\nOCR 识别文字，或只填页码先占位（回头再 OCR）："
        } else {
            "OCR 识别文字，或只填页码先占位（回头再 OCR）："
        },
        confirmLabel = "确定",
        dismissLabel = "取消",
        secondaryActions = listOf(
            PageSheetAction(label = if (failed) "重试 OCR" else "OCR", onClick = onOcr),
            PageSheetAction(label = "删除", danger = true, onClick = onDelete),
        ),
        onConfirm = onAssign,
        onDismiss = onDismiss,
    )
}

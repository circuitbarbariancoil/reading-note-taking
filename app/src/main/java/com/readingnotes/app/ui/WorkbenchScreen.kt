package com.readingnotes.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Entries
import com.readingnotes.app.model.Entry
import com.readingnotes.app.model.EntryKind
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.settings.AppSettings
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.AccentSoft
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft
import com.readingnotes.app.ui.PageNumberSheet
import com.readingnotes.app.ui.PageSheetAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private enum class MainMode { Text, Image }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    initialBook: Book,
    settings: AppSettings,
    repository: BookRepository,
    onBack: () -> Unit,
    onOpenPalette: () -> Unit,
    onCapture: () -> Unit = {},
    initialPageIndex: Int = 0,
    ocrBusy: Boolean = false,
    ocrError: String? = null,
    onDismissOcrError: () -> Unit = {},
    onOcrPage: (com.readingnotes.app.model.Page) -> Unit = {},
    onChangePageNumber: (com.readingnotes.app.model.Page, Int) -> Unit = { _, _ -> },
    processItems: List<ProcessItem> = emptyList(),
    onRetryProcessItem: (ProcessItem) -> Unit = {},
    onDismissProcessItem: (ProcessItem) -> Unit = {},
    queueCollapsed: Boolean = false,
    onExpandQueue: () -> Unit = {},
    onCollapseQueue: () -> Unit = {},
    focusRange: IntRange? = null,
) {
    var book by remember(initialBook) { mutableStateOf(initialBook) }
    var pageIndex by remember(initialBook, initialPageIndex) { mutableStateOf(initialPageIndex) }
    var mode by remember { mutableStateOf(MainMode.Text) }
    var vertical by remember { mutableStateOf(true) }
    var toolbarCollapsed by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var editingEntryId by remember { mutableStateOf<String?>(null) }
    var pageMenuOpen by remember { mutableStateOf(false) }
    var confirmReOcr by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val colors = remember(settings.palette) { settings.palette.asMap() }
    val composeColors = remember(settings.palette) {
        settings.palette.colors.associate { hc ->
            hc.name to runCatching { Color(android.graphics.Color.parseColor(hc.css)) }.getOrDefault(Accent)
        }
    }

    if (book.pages.isEmpty()) {
        EmptyState(onBack, onCapture, ocrBusy)
        return
    }

    val page = book.pages[pageIndex.coerceIn(0, book.pages.lastIndex)]
    val pageEntries = book.entries.filter { it.page == page.page }

    fun save(updated: Book) {
        book = updated
        scope.launch { repository.persist(updated, settings.dropboxCredentialJson) }
    }

    fun applyHighlight(color: String) {
        val sel = selection ?: return
        val (hl, entry) = Entries.highlightEntry(page, sel.start, sel.end, color, Instant.now().toString()) ?: return
        val newPages = book.pages.map { if (it.page == page.page) it.copy(highlights = it.highlights + hl) else it }
        save(book.copy(pages = newPages, entries = book.entries + entry))
        selection = null
    }

    fun applyExcerpt() {
        val sel = selection ?: return
        val entry = Entries.excerptEntry(page, sel.start, sel.end, Instant.now().toString()) ?: return
        save(book.copy(entries = book.entries + entry))
        selection = null
    }

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!toolbarCollapsed) {
                TopBar(
                    title = book.title,
                    author = book.author,
                    pageNo = page.page,
                    mode = mode,
                    onMode = { mode = it },
                    onCollapse = { toolbarCollapsed = true },
                    onSettings = onOpenPalette,
                    onBack = onBack,
                    onPageMenu = { pageMenuOpen = true },
                )
            }

            // OCR error banner
            ocrError?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFDE8E6))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, fontSize = 12.sp, color = Color(0xFFB3524A), modifier = Modifier.weight(1f), maxLines = 2)
                    Text("✕", fontSize = 14.sp, color = Color(0xFFB3524A), modifier = Modifier.clickable(onClick = onDismissOcrError).padding(start = 8.dp))
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    mode == MainMode.Text && page.ocrText == null -> NotOcrYet(
                        imagePath = repository.archiveImagePath(book, page),
                        onOcr = { onOcrPage(page) },
                        ocrBusy = ocrBusy,
                    )
                    mode == MainMode.Text -> PageWebView(
                        page = page,
                        colors = colors,
                        vertical = vertical,
                        interactive = true,
                        onSelectionChange = { selection = it },
                        modifier = Modifier.fillMaxSize(),
                        flash = focusRange.takeIf { pageIndex == initialPageIndex },
                    )
                    else -> PageImage(repository.archiveImagePath(book, page))
                }

                if (ocrBusy) {
                    OcrBusyIndicator(modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
                }

                if (toolbarCollapsed) {
                    IconButton(
                        onClick = { toolbarCollapsed = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) {
                        Text("⤢", fontSize = 18.sp, color = SumiSoft)
                    }
                }
            }

            if (!toolbarCollapsed) {
                PageBar(
                    index = pageIndex,
                    total = book.pages.size,
                    entryCount = pageEntries.size,
                    vertical = vertical,
                    onPrev = { if (pageIndex > 0) { pageIndex--; selection = null } },
                    onNext = { if (pageIndex < book.pages.lastIndex) { pageIndex++; selection = null } },
                    onToggleWriting = { vertical = !vertical },
                    onEntries = { drawerOpen = true },
                    onCapture = onCapture,
                )
            }
        }

        selection?.let {
            if (mode == MainMode.Text && !toolbarCollapsed) {
                SelectionBar(
                    colors = settings.palette.activeColors(),
                    onHighlight = ::applyHighlight,
                    onExcerpt = ::applyExcerpt,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
                )
            }
        }

        val hasActiveItems = processItems.any { it.step == ProcessStep.Queued || it.step == ProcessStep.Saving || it.step == ProcessStep.Ocr }
        if (processItems.isNotEmpty() && hasActiveItems) {
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

    if (drawerOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { drawerOpen = false },
            sheetState = sheetState,
            containerColor = Paper,
        ) {
            EntryList(
                entries = pageEntries,
                colorMap = composeColors,
                onEdit = { editingEntryId = it.id },
                onDelete = { target ->
                    val newPages = if (target.kind == EntryKind.highlight) {
                        book.pages.map { p ->
                            if (p.page != target.page) p
                            else p.copy(
                                highlights = p.highlights.filterNot { hl ->
                                    hl.id == target.highlightId ||
                                        (target.highlightId == null && hl.start >= target.srcStart && hl.end <= target.srcEnd)
                                },
                            )
                        }
                    } else {
                        book.pages
                    }
                    save(book.copy(pages = newPages, entries = book.entries.filterNot { it.id == target.id }))
                },
            )
        }
    }

    if (pageMenuOpen) {
        PageMenuDialog(
            page = page,
            ocrBusy = ocrBusy,
            onDismiss = { pageMenuOpen = false },
            onChangePageNumber = { newNumber ->
                pageMenuOpen = false
                onChangePageNumber(page, newNumber)
            },
            onReOcr = {
                pageMenuOpen = false
                if (page.highlights.isNotEmpty() || pageEntries.isNotEmpty()) {
                    confirmReOcr = true
                } else {
                    onOcrPage(page)
                }
            },
        )
    }

    if (confirmReOcr) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReOcr = false },
            containerColor = Paper,
            shape = RoundedCornerShape(16.dp),
            title = { Text("重新识别这一页？", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Sumi) },
            text = {
                Text(
                    "该页有 ${pageEntries.size} 条笔记。重新识别会替换原文并清除页面上的高亮标记，笔记本身保留。",
                    fontSize = 13.sp,
                    color = SumiSoft,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmReOcr = false
                    onOcrPage(page)
                }) { Text("重新识别", color = Accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmReOcr = false }) { Text("取消", color = SumiSoft) }
            },
        )
    }

    editingEntryId?.let { id ->
        book.entries.firstOrNull { it.id == id }?.let { entry ->
            EntryEditor(
                entry = entry,
                palette = settings.palette,
                knownTags = book.entries.flatMap { it.tags }.distinct().sorted(),
                onSave = { updated ->
                    save(book.copy(entries = book.entries.map { if (it.id == updated.id) updated else it }))
                    editingEntryId = null
                },
                onDismiss = { editingEntryId = null },
            )
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    author: String,
    pageNo: Int,
    mode: MainMode,
    onMode: (MainMode) -> Unit,
    onCollapse: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onPageMenu: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", fontSize = 26.sp, color = SumiSoft, modifier = Modifier.clickable(onClick = onBack).padding(end = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Sumi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(author.ifBlank { null }, "p.$pageNo").joinToString(" ・ "),
                fontSize = 12.sp, color = SumiSoft,
            )
        }
        ModeToggle(mode, onMode)
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onPageMenu) { Text("⋯", fontSize = 17.sp, color = SumiSoft) }
        IconButton(onClick = onCollapse) { Text("⤢", fontSize = 17.sp, color = SumiSoft) }
        IconButton(onClick = onSettings) { Text("⚙", fontSize = 17.sp, color = SumiSoft) }
    }
}

@Composable
private fun ModeToggle(mode: MainMode, onMode: (MainMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFFEDE6D6))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SegItem("原图", mode == MainMode.Image) { onMode(MainMode.Image) }
        SegItem("文字", mode == MainMode.Text) { onMode(MainMode.Text) }
    }
}

@Composable
private fun SegItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (selected) Color.White else SumiSoft)
    }
}

@Composable
private fun PageBar(
    index: Int,
    total: Int,
    entryCount: Int,
    vertical: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleWriting: () -> Unit,
    onEntries: () -> Unit,
    onCapture: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrev) { Text("‹ 上一页", fontSize = 13.sp, color = if (index > 0) Accent else Hairline) }
        Spacer(Modifier.weight(1f))
        Text("${index + 1} / $total", fontSize = 12.sp, color = SumiSoft)
        Spacer(Modifier.weight(1f))
        Text(if (vertical) "竖排" else "横排", fontSize = 12.sp, color = SumiSoft, modifier = Modifier.clickable(onClick = onToggleWriting).padding(8.dp))
        Text("条目 $entryCount", fontSize = 12.sp, color = Accent, modifier = Modifier.clickable(onClick = onEntries).padding(8.dp))
        Text("＋", fontSize = 16.sp, color = Accent, modifier = Modifier.clickable(onClick = onCapture).padding(8.dp))
        TextButton(onClick = onNext) { Text("下一页 ›", fontSize = 13.sp, color = if (index < total - 1) Accent else Hairline) }
    }
}

@Composable
private fun SelectionBar(
    colors: List<com.readingnotes.app.model.HighlightColor>,
    onHighlight: (String) -> Unit,
    onExcerpt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        colors.forEach { c ->
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(runCatching { Color(android.graphics.Color.parseColor(c.css)) }.getOrDefault(Accent))
                    .clickable { onHighlight(c.name) },
            )
        }
        Box(modifier = Modifier.width(1.dp).height(22.dp).background(Hairline))
        Text("摘录", fontSize = 14.sp, color = Accent, modifier = Modifier.clickable(onClick = onExcerpt))
    }
}

@Composable
private fun EntryList(
    entries: List<Entry>,
    colorMap: Map<String, Color>,
    onEdit: (Entry) -> Unit,
    onDelete: (Entry) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("本页条目 ${entries.size}", fontSize = 13.sp, color = SumiSoft, modifier = Modifier.padding(vertical = 8.dp))
        if (entries.isEmpty()) {
            Text("—", color = Hairline)
        }
        entries.forEach { entry ->
            EntryCard(entry, colorMap, onClick = { onEdit(entry) }, onDelete = { onDelete(entry) })
        }
    }
}

@Composable
private fun EntryCard(entry: Entry, colorMap: Map<String, Color>, onClick: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            EntryHtmlWebView(
                excerpt = entry.text,
                annotation = entry.annotation,
                colors = colorMap,
                modifier = Modifier.fillMaxWidth(),
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("p.${entry.page}", fontSize = 11.sp, color = SumiSoft)
            Spacer(Modifier.weight(1f))
            Text("删除", fontSize = 11.sp, color = SumiSoft, modifier = Modifier.clickable(onClick = onDelete).padding(4.dp))
        }
    }
}

/** Per-page actions: change the page number or re-run OCR. */
@Composable
private fun PageMenuDialog(
    page: com.readingnotes.app.model.Page,
    ocrBusy: Boolean,
    onDismiss: () -> Unit,
    onChangePageNumber: (Int) -> Unit,
    onReOcr: () -> Unit,
) {
    PageNumberSheet(
        title = "页面 p.${page.page}",
        initialValue = page.page.toString(),
        confirmLabel = "保存页码",
        dismissLabel = "取消",
        noteText = if (page.ocrText == null) "此页尚未 OCR。" else "重新 OCR 会覆盖已识别的文字。",
        confirmEnabled = { it != page.page },
        secondaryActions = listOf(
            PageSheetAction(
                label = if (page.ocrText == null) "OCR" else "重新 OCR",
                enabled = !ocrBusy,
                onClick = onReOcr,
            ),
        ),
        onConfirm = onChangePageNumber,
        onDismiss = onDismiss,
    )
}

/** Compact non-blocking OCR status: spinner + elapsed seconds. */
@Composable
private fun OcrBusyIndicator(modifier: Modifier = Modifier) {
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            seconds++
        }
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Accent)
        Text("识别中 ${seconds}s", fontSize = 11.sp, color = SumiSoft)
    }
}

/** Page that only has a page number: show the photo and offer OCR. */
@Composable
private fun NotOcrYet(imagePath: String?, onOcr: () -> Unit, ocrBusy: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        PageImage(imagePath)
        if (!ocrBusy) {
            Text(
                "此页尚未识别文字 · 点击 OCR",
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Accent)
                    .clickable(onClick = onOcr)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PageImage(path: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bitmap = remember(path) { path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } }
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text("无原始页图", color = SumiSoft)
        }
    }
}

@Composable
private fun EmptyState(onBack: () -> Unit, onCapture: () -> Unit, ocrBusy: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().background(Paper).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("‹ 返回", fontSize = 15.sp, color = Accent, modifier = Modifier.clickable(onClick = onBack))
        if (ocrBusy) {
            OcrBusyIndicator()
            Text("刚拍的页正在识别，完成后自动显示。", color = SumiSoft)
        } else {
            Text("还没有页面。先拍一页开始。", color = SumiSoft)
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

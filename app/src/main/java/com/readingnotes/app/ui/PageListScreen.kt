package com.readingnotes.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Capture
import com.readingnotes.app.model.Page
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

enum class PageSortMode { ByOrder, ByPageNumber }

@Composable
fun PageListScreen(
    book: Book,
    repository: BookRepository,
    onOpenPage: (Page) -> Unit,
    onCapture: () -> Unit,
    onBatchOcr: () -> Unit,
    onBack: () -> Unit,
    onProcessCapture: (Capture) -> Unit,
) {
    var sortMode by remember { mutableStateOf(PageSortMode.ByPageNumber) }
    var assignPageDialog by remember { mutableStateOf<Capture?>(null) }

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
                // Sort toggle
                Text(
                    if (sortMode == PageSortMode.ByPageNumber) "页码" else "时间",
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
                    items(sortedPages, key = { "page-${it.page}" }) { page ->
                        PageThumbnail(page, book, repository, onClick = { onOpenPage(page) })
                    }
                }

                // Unprocessed captures section
                if (book.captures.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("未处理", fontSize = 12.sp, color = SumiSoft)
                            Spacer(Modifier.weight(1f))
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
                    items(book.captures, key = { "cap-${it.id}" }) { capture ->
                        CaptureThumbnail(capture, onClick = { assignPageDialog = capture })
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

    assignPageDialog?.let { capture ->
        AssignPageDialog(
            capture = capture,
            onDismiss = { assignPageDialog = null },
            onAssign = { pageNumber ->
                assignPageDialog = null
                onProcessCapture(capture)
            },
            onOcr = {
                assignPageDialog = null
                onProcessCapture(capture)
            },
        )
    }
}

@Composable
private fun PageThumbnail(page: Page, book: Book, repository: BookRepository, onClick: () -> Unit) {
    val imagePath = remember(page) { repository.archiveImagePath(book, page) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
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
            // OCR status badge
            if (page.ocrText != null) {
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

@Composable
private fun CaptureThumbnail(capture: Capture, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE8E2D8))
            .clickable(onClick = onClick)
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
        }
        Text(
            capture.capturedAt.take(10),
            fontSize = 9.sp,
            color = SumiSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun AssignPageDialog(
    capture: Capture,
    onDismiss: () -> Unit,
    onAssign: (Int) -> Unit,
    onOcr: () -> Unit,
) {
    var pageNumText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("处理照片") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择操作：", fontSize = 13.sp, color = SumiSoft)
                OutlinedTextField(
                    value = pageNumText,
                    onValueChange = { pageNumText = it.filter { c -> c.isDigit() } },
                    label = { Text("填写页码") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOcr) { Text("OCR") }
                TextButton(
                    onClick = { pageNumText.toIntOrNull()?.let { onAssign(it) } },
                    enabled = pageNumText.toIntOrNull() != null,
                ) { Text("确定") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

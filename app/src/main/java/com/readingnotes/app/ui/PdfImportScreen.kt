package com.readingnotes.app.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.readingnotes.app.pdf.PdfSource
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

private const val THUMB_LONG_EDGE = 260
private const val PREVIEW_LONG_EDGE = 1400

/**
 * Full-screen PDF page picker: a thumbnail grid where the user selects a
 * contiguous page range (by tapping first + last page, or typing 从/到), sets a
 * starting book page number (batch offset), then imports. Rendering is lazy and
 * cached so large PDFs stay responsive.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfImportScreen(
    source: PdfSource,
    onCancel: () -> Unit,
    onImport: (fromPage: Int, toPage: Int, startPageNumber: Int) -> Unit,
) {
    val pageCount = remember(source) { source.pageCount }
    val thumbs = remember(source) { mutableStateMapOf<Int, ImageBitmap>() }

    var fromText by remember(source) { mutableStateOf("1") }
    var toText by remember(source) { mutableStateOf(pageCount.toString()) }
    var startText by remember(source) { mutableStateOf("1") }
    var startEdited by remember(source) { mutableStateOf(false) }
    var enlargedIndex by remember { mutableStateOf<Int?>(null) }

    val fromNum = fromText.toIntOrNull()?.takeIf { it in 1..pageCount }
    val toNum = toText.toIntOrNull()?.takeIf { it in 1..pageCount }
    val lo = if (fromNum != null && toNum != null) minOf(fromNum, toNum) else fromNum
    val hi = if (fromNum != null && toNum != null) maxOf(fromNum, toNum) else null
    val count = if (lo != null && hi != null) hi - lo + 1 else if (lo != null) 1 else 0
    val startNum = startText.toIntOrNull()
    val importEnabled = lo != null && hi != null && startNum != null

    fun tapPage(p: Int) {
        val f = fromText.toIntOrNull()
        val t = toText.toIntOrNull()
        if (f == null || t != null) {
            // No open range → begin a new one anchored at this page.
            fromText = p.toString()
            toText = ""
            if (!startEdited) startText = p.toString()
        } else {
            // One endpoint chosen → complete the range.
            toText = p.toString()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "✕",
                    fontSize = 20.sp,
                    color = SumiSoft,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(6.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "导入 PDF",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        color = Sumi,
                    )
                    Text("共 $pageCount 页", fontSize = 12.sp, color = SumiSoft)
                }
                Text(
                    "全部",
                    fontSize = 12.sp,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFEDE6D6))
                        .clickable {
                            fromText = "1"
                            toText = pageCount.toString()
                            if (!startEdited) startText = "1"
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
                items(pageCount, key = { it }) { index ->
                    val p = index + 1
                    val selected = lo != null && hi != null && p in lo..hi
                    val isAnchor = fromNum == p && toNum == null
                    PdfThumbCell(
                        source = source,
                        index = index,
                        cache = thumbs,
                        selected = selected || isAnchor,
                        onClick = { tapPage(p) },
                        onLongClick = { enlargedIndex = index },
                    )
                }
            }

            // Bottom control bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEDE6D6))
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NumberField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        label = "从",
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = "到",
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = startText,
                        onValueChange = { startText = it; startEdited = true },
                        label = "起始页码",
                        modifier = Modifier.weight(1.2f),
                    )
                }

                val noteText = if (importEnabled && lo != null && hi != null && startNum != null) {
                    val endNum = startNum + count - 1
                    "将导入 $count 页（PDF 第 $lo–$hi 页）→ 书内第 $startNum–$endNum 页"
                } else {
                    "点缩略图选起止页，或直接填「从 / 到」"
                }
                Text(noteText, fontSize = 12.sp, color = SumiSoft)

                Text(
                    if (importEnabled) "导入 $count 页" else "导入",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (importEnabled) Color.White else SumiSoft.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (importEnabled) Accent else Color(0xFFDDD3C2))
                        .clickable(enabled = importEnabled) {
                            if (lo != null && hi != null && startNum != null) {
                                onImport(lo, hi, startNum)
                            }
                        }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }

    enlargedIndex?.let { index ->
        Dialog(
            onDismissRequest = { enlargedIndex = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val img by produceState<ImageBitmap?>(initialValue = null, key1 = index) {
                value = withContext(Dispatchers.IO) {
                    runCatching { source.renderBitmap(index, PREVIEW_LONG_EDGE).asImageBitmap() }.getOrNull()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable { enlargedIndex = null },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = img
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                }
                Text(
                    "第 ${index + 1} 页",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfThumbCell(
    source: PdfSource,
    index: Int,
    cache: SnapshotStateMap<Int, ImageBitmap>,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val img by produceState<ImageBitmap?>(initialValue = cache[index], key1 = index) {
        if (value == null) {
            val rendered = withContext(Dispatchers.IO) {
                runCatching { source.renderBitmap(index, THUMB_LONG_EDGE).asImageBitmap() }.getOrNull()
            }
            if (rendered != null) {
                cache[index] = rendered
                value = rendered
            }
        }
    }
    val borderMod = if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(8.dp))
            .then(borderMod)
            .background(Color.White)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = img
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp, color = Hairline)
        }
        // Page-number badge
        Text(
            "${index + 1}",
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> if (new.all { it.isDigit() }) onValueChange(new) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

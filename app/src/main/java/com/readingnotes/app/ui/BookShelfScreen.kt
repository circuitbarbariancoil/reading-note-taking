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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.model.Book
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

private val NotebookBg = Color(0xFFF5F0E8)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookShelfScreen(
    books: List<Book>,
    repository: BookRepository,
    onOpenBook: (Book) -> Unit,
    onSettings: () -> Unit,
    onNewBook: (Book) -> Unit,
    onDeleteBooks: (List<String>) -> Unit = {},
    onEntries: () -> Unit = {},
    onOpenNotebook: () -> Unit = {},
) {
    var gridMode by remember { mutableStateOf(true) }
    var showNewDialog by remember { mutableStateOf(false) }
    val selectedUids = remember { mutableStateListOf<String>() }
    val selectMode = selectedUids.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }

    fun toggleSelect(uid: String) {
        if (uid in selectedUids) selectedUids.remove(uid) else selectedUids.add(uid)
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
                        "已选 ${selectedUids.size} 本",
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
                            .clickable { selectedUids.clear() }
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
                        "我的书",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 22.sp,
                        color = Sumi,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "条目",
                        fontSize = 13.sp,
                        color = Accent,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onEntries)
                            .padding(8.dp),
                    )
                    Text(
                        if (gridMode) "☷" else "☰",
                        fontSize = 20.sp,
                        color = SumiSoft,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { gridMode = !gridMode }
                            .padding(8.dp),
                    )
                    Text(
                        "⚙",
                        fontSize = 18.sp,
                        color = SumiSoft,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onSettings)
                            .padding(8.dp),
                    )
                }
            }

            // Notebook card (always visible at top, not selectable)
            if (!selectMode) {
                NotebookCard(
                    entryCount = books.find { it.uid == BookRepository.NOTEBOOK_UID }?.entries?.size ?: 0,
                    onClick = onOpenNotebook,
                )
            }

            val displayBooks = books.filter { it.uid != BookRepository.NOTEBOOK_UID }
            if (displayBooks.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("—", fontSize = 24.sp, color = Hairline)
                }
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(displayBooks, key = { it.uid }) { book ->
                        val selected = book.uid in selectedUids
                        BookCardGrid(
                            book, repository,
                            selected = selected,
                            onClick = {
                                if (selectMode) toggleSelect(book.uid) else onOpenBook(book)
                            },
                            onLongClick = { toggleSelect(book.uid) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(displayBooks, key = { it.uid }) { book ->
                        val selected = book.uid in selectedUids
                        BookCardList(
                            book, repository,
                            selected = selected,
                            onClick = {
                                if (selectMode) toggleSelect(book.uid) else onOpenBook(book)
                            },
                            onLongClick = { toggleSelect(book.uid) },
                        )
                    }
                }
            }
        }

        // FAB (hide in select mode)
        if (!selectMode) {
            FloatingActionButton(
                onClick = { showNewDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                containerColor = Accent,
                contentColor = Color.White,
            ) {
                Text("＋", fontSize = 22.sp)
            }
        }
    }

    if (showNewDialog) {
        NewBookDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { title, author ->
                showNewDialog = false
                val book = repository.createBook(title, author)
                onNewBook(book)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${selectedUids.size} 本书？") },
            text = { Text("书中所有页面、照片和条目将被永久删除，Dropbox 上的备份也会被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val toDelete = selectedUids.toList()
                    selectedUids.clear()
                    onDeleteBooks(toDelete)
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
private fun BookCardGrid(
    book: Book,
    repository: BookRepository,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val borderMod = if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(12.dp)) else Modifier
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(borderMod)
            .background(if (selected) Color(0xFFEDE6D6) else Color.White)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
    ) {
        val coverPath = remember(book) {
            book.coverPath
                ?: book.pages.firstOrNull()?.let { repository.archiveImagePath(book, it) }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFEDE6D6)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverPath != null) {
                val bitmap = remember(coverPath) {
                    runCatching { BitmapFactory.decodeFile(coverPath)?.asImageBitmap() }.getOrNull()
                }
                bitmap?.let {
                    Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } ?: Text(book.title.take(1), fontSize = 28.sp, color = Sumi, fontFamily = FontFamily.Serif)
            } else {
                Text(book.title.take(1), fontSize = 28.sp, color = Sumi, fontFamily = FontFamily.Serif)
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = Sumi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.author.isNotBlank()) {
            Text(book.author, fontSize = 11.sp, color = SumiSoft, maxLines = 1)
        }
        Text(
            "${book.pages.size}页 · ${book.entries.size}条",
            fontSize = 11.sp,
            color = SumiSoft,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCardList(
    book: Book,
    repository: BookRepository,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val borderMod = if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(12.dp)) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(borderMod)
            .background(if (selected) Color(0xFFEDE6D6) else Color.White)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val coverPath = remember(book) {
            book.coverPath
                ?: book.pages.firstOrNull()?.let { repository.archiveImagePath(book, it) }
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFEDE6D6)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverPath != null) {
                val bitmap = remember(coverPath) {
                    runCatching { BitmapFactory.decodeFile(coverPath)?.asImageBitmap() }.getOrNull()
                }
                bitmap?.let {
                    Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } ?: Text(book.title.take(1), fontSize = 20.sp, color = Sumi, fontFamily = FontFamily.Serif)
            } else {
                Text(book.title.take(1), fontSize = 20.sp, color = Sumi, fontFamily = FontFamily.Serif)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                book.title,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = Sumi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotBlank()) {
                Text(book.author, fontSize = 12.sp, color = SumiSoft)
            }
            Text("${book.pages.size}页 · ${book.entries.size}条", fontSize = 11.sp, color = SumiSoft)
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 13.sp)
            }
        } else {
            Text("›", fontSize = 20.sp, color = Hairline)
        }
    }
}

@Composable
private fun NotebookCard(entryCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NotebookBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("📓", fontSize = 22.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "笔记本",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = Sumi,
            )
            Text("$entryCount 条笔记", fontSize = 11.sp, color = SumiSoft)
        }
        Text("›", fontSize = 20.sp, color = Hairline)
    }
}

@Composable
private fun NewBookDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新书") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("书名") }, singleLine = true)
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("作者") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title, author) }, enabled = title.isNotBlank()) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

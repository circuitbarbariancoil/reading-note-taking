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

@Composable
fun BookShelfScreen(
    books: List<Book>,
    repository: BookRepository,
    onOpenBook: (Book) -> Unit,
    onSettings: () -> Unit,
    onNewBook: (Book) -> Unit,
) {
    var gridMode by remember { mutableStateOf(true) }
    var showNewDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "我的书",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 22.sp,
                    color = Sumi,
                )
                Spacer(Modifier.weight(1f))
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

            if (books.isEmpty()) {
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
                    items(books, key = { it.uid }) { book ->
                        BookCardGrid(book, repository, onClick = { onOpenBook(book) })
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(books, key = { it.uid }) { book ->
                        BookCardList(book, repository, onClick = { onOpenBook(book) })
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = { showNewDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Accent,
            contentColor = Color.White,
        ) {
            Text("＋", fontSize = 22.sp)
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
}

@Composable
private fun BookCardGrid(book: Book, repository: BookRepository, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        // Cover thumbnail
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

@Composable
private fun BookCardList(book: Book, repository: BookRepository, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
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

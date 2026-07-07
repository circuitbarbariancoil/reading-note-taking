package com.readingnotes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import com.readingnotes.app.ocr.TocItem
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.PaperPanel
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

/** An editable TOC row backed by plain strings so mid-edit text stays valid. */
private class ReviewRow(title: String, page: String, level: Int) {
    var title by mutableStateOf(title)
    var page by mutableStateOf(page)
    var level by mutableStateOf(level)
}

@Composable
fun TocReviewScreen(
    items: List<TocItem>,
    hasExisting: Boolean,
    onCancel: () -> Unit,
    onConfirm: (List<TocItem>, replace: Boolean) -> Unit,
) {
    val rows = remember(items) {
        mutableStateListOf<ReviewRow>().apply {
            items.forEach { add(ReviewRow(it.title, it.page.toString(), it.level)) }
        }
    }
    var replace by remember { mutableStateOf(true) }

    fun collect(): List<TocItem> = rows.mapNotNull { r ->
        val p = r.page.toIntOrNull() ?: return@mapNotNull null
        if (r.title.isBlank()) null else TocItem(r.title.trim(), p, r.level.coerceIn(1, 6))
    }

    Box(modifier = Modifier.fillMaxSize().background(Paper)) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
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
                        "确认目录",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        color = Sumi,
                    )
                    Text("识别到 ${rows.size} 个章节 · 可修改", fontSize = 12.sp, color = SumiSoft)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(rows) { index, row ->
                    ReviewRowEditor(
                        row = row,
                        onDelete = { rows.removeAt(index) },
                    )
                }
            }

            Text(
                "＋ 添加一行",
                fontSize = 13.sp,
                color = Accent,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        val nextPage = (rows.mapNotNull { it.page.toIntOrNull() }.maxOrNull() ?: 0) + 1
                        rows.add(ReviewRow("", nextPage.toString(), 1))
                    }
                    .padding(6.dp),
            )

            if (hasExisting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已有目录：", fontSize = 12.sp, color = SumiSoft)
                    listOf(true to "替换", false to "追加").forEach { (isReplace, label) ->
                        val on = replace == isReplace
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (on) Color.White else Accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) Accent else Color.Transparent)
                                .clickable { replace = isReplace }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "取消",
                    fontSize = 14.sp,
                    color = SumiSoft,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                val enabled = collect().isNotEmpty()
                Text(
                    "确认导入",
                    fontSize = 14.sp,
                    color = if (enabled) Color.White else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (enabled) Accent else SumiSoft.copy(alpha = 0.4f))
                        .clickable(enabled = enabled) { onConfirm(collect(), replace) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ReviewRowEditor(
    row: ReviewRow,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PaperPanel)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Level stepper 部/章/节
        Text(
            when (row.level) { 1 -> "部"; 2 -> "章"; else -> "节" },
            fontSize = 12.sp,
            color = Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                .clickable { row.level = if (row.level >= 3) 1 else row.level + 1 }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        OutlinedTextField(
            value = row.title,
            onValueChange = { row.title = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            placeholder = { Text("标题", fontSize = 13.sp) },
        )
        OutlinedTextField(
            value = row.page,
            onValueChange = { v -> if (v.all { it.isDigit() }) row.page = v },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(72.dp),
            placeholder = { Text("页", fontSize = 13.sp) },
        )
        Text(
            "✕",
            fontSize = 14.sp,
            color = SumiSoft,
            modifier = Modifier.clickable(onClick = onDelete).padding(6.dp),
        )
    }
}

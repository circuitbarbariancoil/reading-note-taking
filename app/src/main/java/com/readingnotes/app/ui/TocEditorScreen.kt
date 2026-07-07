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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.model.Section
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.PaperPanel
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

private val ErrorRed = Color(0xFFB3524A)

/** A mutable outline node; page kept as a string so mid-edit text stays valid. */
private class OutlineNode(id: String, title: String, page: String, level: Int) {
    val id = id
    var title by mutableStateOf(title)
    var page by mutableStateOf(page)
    var level by mutableStateOf(level)
}

/**
 * Unified 目录 outline editor used for manual authoring, editing an existing TOC,
 * and reviewing an AI-extracted result. Hierarchy is relative depth adjusted with
 * ← / → (the tier's name is written into the title — the book decides it). Every
 * node must carry a page number and page numbers must be non-decreasing top→bottom.
 */
@Composable
fun TocEditorScreen(
    seed: List<Section>,
    allowAppend: Boolean,
    onCancel: () -> Unit,
    onSave: (List<Section>, append: Boolean) -> Unit,
) {
    val rows = remember(seed) {
        mutableStateListOf<OutlineNode>().apply {
            seed.forEach { add(OutlineNode(it.id, it.title, it.startPage.toString(), it.level.coerceAtLeast(1))) }
        }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var append by remember { mutableStateOf(false) }

    fun newId() = java.util.UUID.randomUUID().toString().take(8)
    fun selectedIndex() = rows.indexOfFirst { it.id == selectedId }

    // Per-row validation: blank title, missing/invalid page, or page < previous page.
    val errors = remember(rows.toList(), rows.map { it.title }, rows.map { it.page }) {
        BooleanArray(rows.size).also { arr ->
            var prev: Int? = null
            rows.forEachIndexed { i, r ->
                val p = r.page.trim().toIntOrNull()
                val bad = r.title.isBlank() || p == null || p < 1 || (prev != null && p < prev!!)
                arr[i] = bad
                if (p != null) prev = p
            }
        }
    }
    val canSave = rows.isNotEmpty() && errors.none { it }

    fun addNode(level: Int) {
        val idx = selectedIndex()
        val defaultPage = when {
            idx >= 0 -> rows[idx].page
            rows.isNotEmpty() -> rows.last().page
            else -> "1"
        }
        val node = OutlineNode(newId(), "", defaultPage, level)
        if (idx >= 0) rows.add(idx + 1, node) else rows.add(node)
        selectedId = node.id
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
                        "编辑目录",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        color = Sumi,
                    )
                    Text(
                        "${rows.size} 个章节 · 选中后用 ← → 调层级、必填页码且递增",
                        fontSize = 11.sp,
                        color = SumiSoft,
                    )
                }
            }

            if (rows.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("空目录", fontSize = 14.sp, color = SumiSoft)
                    Spacer(Modifier.size(6.dp))
                    Text("点下方「＋ 章节」开始写目录", fontSize = 12.sp, color = SumiSoft)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                        OutlineRowEditor(
                            row = row,
                            depthMax = if (index > 0) rows[index - 1].level + 1 else 1,
                            selected = row.id == selectedId,
                            error = errors.getOrElse(index) { false },
                            onSelect = { selectedId = row.id },
                        )
                    }
                }
            }

            // Toolbar acting on the selected node
            val hasSel = selectedIndex() >= 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PaperPanel)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton("＋ 章节", enabled = true) { addNode(1) }
                ToolButton("＋ 同级", enabled = hasSel) { addNode(rows[selectedIndex()].level) }
                ToolButton("＋ 子级", enabled = hasSel) {
                    val idx = selectedIndex()
                    val lvl = (rows[idx].level + 1)
                    addNode(lvl)
                }
                Spacer(Modifier.weight(1f))
                ToolButton("←", enabled = hasSel) {
                    val r = rows[selectedIndex()]
                    r.level = (r.level - 1).coerceAtLeast(1)
                }
                ToolButton("→", enabled = hasSel) {
                    val idx = selectedIndex()
                    val r = rows[idx]
                    val maxLevel = if (idx > 0) rows[idx - 1].level + 1 else 1
                    r.level = (r.level + 1).coerceAtMost(maxLevel)
                }
                ToolButton("删除", enabled = hasSel, danger = true) {
                    val idx = selectedIndex()
                    rows.removeAt(idx)
                    selectedId = null
                }
            }

            if (allowAppend) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已有目录：", fontSize = 12.sp, color = SumiSoft)
                    listOf(false to "替换", true to "追加").forEach { (isAppend, label) ->
                        val on = append == isAppend
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (on) Color.White else Accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) Accent else Color.Transparent)
                                .clickable { append = isAppend }
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
                Text(
                    "保存",
                    fontSize = 14.sp,
                    color = if (canSave) Color.White else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (canSave) Accent else SumiSoft.copy(alpha = 0.4f))
                        .clickable(enabled = canSave) {
                            val sections = rows.map { r ->
                                Section(
                                    id = r.id,
                                    title = r.title.trim(),
                                    startPage = r.page.trim().toInt(),
                                    level = r.level.coerceAtLeast(1),
                                )
                            }
                            onSave(sections, append)
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun OutlineRowEditor(
    row: OutlineNode,
    depthMax: Int,
    selected: Boolean,
    error: Boolean,
    onSelect: () -> Unit,
) {
    val indent = ((row.level - 1).coerceAtLeast(0) * 16).dp
    val borderColor = when {
        error -> ErrorRed
        selected -> Accent
        else -> Hairline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x1A3F6BFF) else PaperPanel)
            .border(if (selected || error) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "L${row.level}",
            fontSize = 11.sp,
            color = SumiSoft,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Paper)
                .clickable(onClick = onSelect)
                .padding(horizontal = 7.dp, vertical = 6.dp),
        )
        OutlinedTextField(
            value = row.title,
            onValueChange = { row.title = it },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (it.isFocused) onSelect() },
            placeholder = { Text("章节标题", fontSize = 13.sp) },
        )
        OutlinedTextField(
            value = row.page,
            onValueChange = { v -> if (v.all { it.isDigit() }) row.page = v },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(70.dp)
                .onFocusChanged { if (it.isFocused) onSelect() },
            placeholder = { Text("页", fontSize = 13.sp) },
            isError = error,
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = when {
        !enabled -> SumiSoft.copy(alpha = 0.4f)
        danger -> ErrorRed
        else -> Accent
    }
    Text(
        label,
        fontSize = 13.sp,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (enabled) Hairline else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

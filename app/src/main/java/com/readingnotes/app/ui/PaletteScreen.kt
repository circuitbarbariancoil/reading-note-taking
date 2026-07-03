package com.readingnotes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.model.HighlightColor
import com.readingnotes.app.model.HighlightPalette
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

@Composable
fun PaletteScreen(
    palette: HighlightPalette,
    onSave: (HighlightPalette) -> Unit,
    onBack: () -> Unit,
) {
    val rows = remember { mutableStateListOf<HighlightColor>().apply { addAll(palette.colors) } }
    var showPresets by remember { mutableStateOf(false) }

    fun commit() = onSave(HighlightPalette(rows.filter { it.name.isNotBlank() }.toList()))

    Column(modifier = Modifier.fillMaxSize().background(Paper).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 26.sp, color = SumiSoft, modifier = Modifier.clickable { commit(); onBack() }.padding(end = 8.dp))
            Text("高亮色板", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Sumi)
        }
        Spacer(Modifier.size(16.dp))

        rows.forEachIndexed { index, color ->
            if (!color.active) return@forEachIndexed
            ColorRow(
                color = color,
                onChange = { rows[index] = it },
                onDelete = { rows[index] = color.copy(active = false) },
            )
            Spacer(Modifier.size(10.dp))
        }

        Text(
            "＋ 添加颜色", fontSize = 14.sp, color = Accent,
            modifier = Modifier.clickable { showPresets = !showPresets }.padding(vertical = 8.dp),
        )
        if (showPresets) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HighlightPalette.PRESETS.filter { preset -> rows.none { it.name == preset.name && it.active } }.forEach { preset ->
                    val tint = runCatching { Color(android.graphics.Color.parseColor(preset.css)) }.getOrDefault(Accent)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, tint, RoundedCornerShape(12.dp))
                            .clickable {
                                val existing = rows.indexOfFirst { it.name == preset.name }
                                if (existing >= 0) rows[existing] = rows[existing].copy(active = true)
                                else rows.add(preset)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(tint))
                        Spacer(Modifier.size(6.dp))
                        Text(preset.name, fontSize = 12.sp, color = Sumi)
                    }
                }
                Text(
                    "自定义…", fontSize = 12.sp, color = Accent,
                    modifier = Modifier.clickable { rows.add(HighlightColor("", "#3C5468")) }.padding(horizontal = 4.dp, vertical = 5.dp),
                )
            }
        }

        val inactive = rows.filter { !it.active && it.name.isNotBlank() }
        if (inactive.isNotEmpty()) {
            Spacer(Modifier.size(16.dp))
            Text("已停用（旧条目仍按原色渲染）", fontSize = 11.sp, color = SumiSoft)
            Spacer(Modifier.size(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                inactive.forEach { color ->
                    val tint = runCatching { Color(android.graphics.Color.parseColor(color.css)) }.getOrDefault(Accent)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFEDE6D6))
                            .clickable {
                                val idx = rows.indexOfFirst { it.name == color.name }
                                if (idx >= 0) rows[idx] = rows[idx].copy(active = true)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(tint))
                        Spacer(Modifier.size(6.dp))
                        Text("${color.name} ↺", fontSize = 12.sp, color = SumiSoft)
                    }
                }
            }
        }

        Spacer(Modifier.size(16.dp))
        Text("语法 ~={色名}文字=~ ，与 Obsidian 一致。删除颜色只是停用输入/筛选，已写入条目的语法和渲染不受影响。", fontSize = 12.sp, color = SumiSoft)
    }
}

@Composable
private fun ColorRow(
    color: HighlightColor,
    onChange: (HighlightColor) -> Unit,
    onDelete: () -> Unit,
) {
    val parsed = remember(color.css) {
        runCatching { Color(android.graphics.Color.parseColor(color.css)) }.getOrDefault(Accent)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(26.dp).clip(CircleShape).background(parsed))
        Field(color.name, "色名", Modifier.weight(1f)) { onChange(color.copy(name = it.trim())) }
        Field(color.css, "#色值", Modifier.weight(1f)) { onChange(color.copy(css = it.trim())) }
        Text("✕", fontSize = 16.sp, color = SumiSoft, modifier = Modifier.clickable(onClick = onDelete).padding(4.dp))
    }
}

@Composable
private fun Field(value: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Box(modifier = modifier) {
        if (value.isEmpty()) Text(hint, fontSize = 14.sp, color = Hairline)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = Sumi),
        )
    }
}

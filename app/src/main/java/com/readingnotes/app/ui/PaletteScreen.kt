package com.readingnotes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

    fun commit() = onSave(HighlightPalette(rows.filter { it.name.isNotBlank() }.toList()))

    Column(modifier = Modifier.fillMaxSize().background(Paper).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 26.sp, color = SumiSoft, modifier = Modifier.clickable { commit(); onBack() }.padding(end = 8.dp))
            Text("高亮色板", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Sumi)
        }
        Spacer(Modifier.size(16.dp))

        rows.forEachIndexed { index, color ->
            ColorRow(
                color = color,
                onChange = { rows[index] = it },
                onDelete = { rows.removeAt(index) },
            )
            Spacer(Modifier.size(10.dp))
        }

        Text(
            "＋ 添加颜色", fontSize = 14.sp, color = Accent,
            modifier = Modifier.clickable { rows.add(HighlightColor("", "#3C5468")) }.padding(vertical = 8.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text("语法 ~={色名}文字=~ ，与 Obsidian 一致。", fontSize = 12.sp, color = SumiSoft)
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

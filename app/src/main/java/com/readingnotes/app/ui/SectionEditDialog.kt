package com.readingnotes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.model.Section
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val EDIT_LEVELS = listOf(1 to "部", 2 to "章", 3 to "节")

@Composable
fun SectionEditDialog(
    initial: Section?,
    defaultPage: Int,
    onConfirm: (title: String, startPage: Int, level: Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var pageText by remember { mutableStateOf((initial?.startPage ?: defaultPage).toString()) }
    var level by remember { mutableStateOf(initial?.level ?: 1) }
    val page = pageText.toIntOrNull()
    val confirmAllowed = title.isNotBlank() && page != null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .imePadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(Paper)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (initial == null) "添加章节" else "编辑章节",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Sumi,
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("章节标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = pageText,
                    onValueChange = { v -> if (v.all { it.isDigit() }) pageText = v },
                    label = { Text("起始页码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("层级", fontSize = 13.sp, color = SumiSoft)
                    EDIT_LEVELS.forEach { (lv, label) ->
                        val on = level == lv
                        Text(
                            label,
                            fontSize = 13.sp,
                            color = if (on) Color.White else Accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (on) Accent else Color.Transparent)
                                .clickable { level = lv }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text("删除", color = Color(0xFFB3524A), fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = SumiSoft, fontSize = 14.sp)
                    }
                    TextButton(
                        onClick = { if (confirmAllowed) onConfirm(title.trim(), page!!, level) },
                        enabled = confirmAllowed,
                    ) {
                        Text("保存", color = if (confirmAllowed) Accent else SumiSoft.copy(alpha = 0.4f), fontSize = 14.sp)
                    }
                }

                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

package com.readingnotes.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft

enum class ProcessStep { Saving, Ocr, NeedsPage, Done, Failed }

data class ProcessItem(
    val id: String,
    val step: ProcessStep,
    val pageNumber: Int? = null,
    val message: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Composable
fun ProcessingQueueCard(
    items: List<ProcessItem>,
    onRetry: (ProcessItem) -> Unit,
    onFillPage: (ProcessItem) -> Unit = {},
    onDismiss: (ProcessItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                val clickAction = when (item.step) {
                    ProcessStep.NeedsPage -> { { onFillPage(item) } }
                    ProcessStep.Failed -> { { onRetry(item) } }
                    else -> null
                }
                val rowModifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .let { base -> if (clickAction != null) base.clickable(onClick = clickAction) else base }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                Row(
                    modifier = rowModifier,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (item.step) {
                        ProcessStep.Saving -> Text("压缩中", fontSize = 12.sp, color = SumiSoft)
                        ProcessStep.Ocr -> {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Accent)
                            Spacer(Modifier.width(8.dp))
                            Text("OCR识别中…", fontSize = 12.sp, color = Accent)
                        }
                        ProcessStep.NeedsPage -> {
                            Text("待填页码", fontSize = 12.sp, color = Accent)
                        }
                        ProcessStep.Done -> {
                            val pageLabel = item.pageNumber?.toString() ?: "?"
                            Text("✓ p.$pageLabel", fontSize = 12.sp, color = Sumi)
                        }
                        ProcessStep.Failed -> {
                            Text("⚠ 失败·将重试", fontSize = 12.sp, color = Color(0xFFB3524A))
                        }
                    }
                    item.message?.takeIf { it.isNotBlank() && item.step == ProcessStep.Failed }?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, fontSize = 11.sp, color = SumiSoft, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "✕",
                        fontSize = 12.sp,
                        color = Hairline,
                        modifier = Modifier.clickable(onClick = { onDismiss(item) }),
                    )
                }
            }
        }
    }
}

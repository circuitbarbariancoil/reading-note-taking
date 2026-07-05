package com.readingnotes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.ocr.ApiProtocol
import com.readingnotes.app.ocr.LlmProvider
import com.readingnotes.app.ocr.ProviderConfig
import com.readingnotes.app.settings.ProviderUsageStats
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft
import java.util.UUID

@Composable
fun ProviderSettingsScreen(
    config: ProviderConfig,
    usage: Map<String, ProviderUsageStats>,
    onSave: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
) {
    var providers by remember(config) { mutableStateOf(config.providers) }
    var activeIndex by remember(config) { mutableStateOf(config.activeIndex) }
    var fallback by remember(config) { mutableStateOf(config.fallbackOnError) }
    var roundRobin by remember(config) { mutableStateOf(config.roundRobin) }
    var parallelOcr by remember(config) { mutableStateOf(config.parallelOcr) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    val usableCount = providers.count { it.enabled && it.apiKey.isNotBlank() }

    fun save() {
        onSave(ProviderConfig(providers, activeIndex.coerceIn(0, providers.lastIndex.coerceAtLeast(0)), fallback, roundRobin, parallelOcr))
    }

    Column(modifier = Modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        ScreenHeader("模型设置", onBack)

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(providers, key = { _, p -> p.id }) { index, provider ->
                ProviderCard(
                    provider = provider,
                    usage = usage[provider.id] ?: ProviderUsageStats(),
                    isActive = index == activeIndex,
                    onSetActive = {
                        activeIndex = index
                        save()
                    },
                    onEdit = { editingIndex = index },
                    onDelete = {
                        providers = providers.toMutableList().also { it.removeAt(index) }
                        if (activeIndex >= providers.size) activeIndex = (providers.size - 1).coerceAtLeast(0)
                        save()
                    },
                )
            }

            item {
                AddProviderButton(onAdd = { protocol ->
                    val newProvider = when (protocol) {
                        ApiProtocol.Gemini -> LlmProvider.geminiDefault().copy(id = UUID.randomUUID().toString())
                        ApiProtocol.OpenAI -> LlmProvider.openAiDefault().copy(id = UUID.randomUUID().toString())
                        ApiProtocol.Claude -> LlmProvider.claudeDefault().copy(id = UUID.randomUUID().toString())
                    }
                    providers = providers + newProvider
                    editingIndex = providers.lastIndex
                })
            }

            item { Spacer(Modifier.height(16.dp)) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("失败时自动切换", fontSize = 14.sp, color = Sumi)
                        Text("当前模型失败后尝试下一个", fontSize = 11.sp, color = SumiSoft)
                    }
                    Switch(checked = fallback, onCheckedChange = { fallback = it; save() })
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("轮询模式", fontSize = 14.sp, color = Sumi)
                        Text("每次请求轮流使用不同模型", fontSize = 11.sp, color = SumiSoft)
                    }
                    Switch(checked = roundRobin, onCheckedChange = { roundRobin = it; save() })
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("并行 OCR", fontSize = 14.sp, color = Sumi)
                        Text(
                            if (usableCount > 1) "批量识别时 ${usableCount} 个模型同时处理"
                            else "需要 2 个以上已配置的模型",
                            fontSize = 11.sp,
                            color = if (usableCount > 1) SumiSoft else Color(0xFFB3524A),
                        )
                    }
                    Switch(
                        checked = parallelOcr,
                        onCheckedChange = { parallelOcr = it; save() },
                        enabled = usableCount > 1,
                    )
                }
            }
        }
    }

    editingIndex?.let { idx ->
        providers.getOrNull(idx)?.let { provider ->
            EditProviderDialog(
                provider = provider,
                onDismiss = { editingIndex = null },
                onSave = { updated ->
                    providers = providers.toMutableList().also { it[idx] = updated }
                    editingIndex = null
                    save()
                },
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: LlmProvider,
    usage: ProviderUsageStats,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) Color(0xFFEDE6D6) else Color.White)
            .border(1.dp, if (isActive) Accent else Hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSetActive, modifier = Modifier.size(28.dp)) {
            Icon(
                if (isActive) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = "设为活跃",
                tint = if (isActive) Accent else SumiSoft,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(provider.name, fontSize = 14.sp, color = Sumi)
            Text(
                "${provider.protocol.name} · ${provider.model}",
                fontSize = 11.sp,
                color = SumiSoft,
            )
            Text(
                "本月 ${usage.monthCalls} 次 · 累计 ${usage.totalCalls} 次",
                fontSize = 11.sp,
                color = SumiSoft,
            )
            if (provider.apiKey.isBlank()) {
                Text("⚠ 未填 API Key", fontSize = 11.sp, color = Color(0xFFB3524A))
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = SumiSoft, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddProviderButton(onAdd: (ApiProtocol) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Hairline, RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("添加模型", fontSize = 14.sp, color = Accent)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Gemini") }, onClick = { expanded = false; onAdd(ApiProtocol.Gemini) })
            DropdownMenuItem(text = { Text("OpenAI Compatible") }, onClick = { expanded = false; onAdd(ApiProtocol.OpenAI) })
            DropdownMenuItem(text = { Text("Claude") }, onClick = { expanded = false; onAdd(ApiProtocol.Claude) })
        }
    }
}

@Composable
private fun EditProviderDialog(
    provider: LlmProvider,
    onDismiss: () -> Unit,
    onSave: (LlmProvider) -> Unit,
) {
    var name by remember { mutableStateOf(provider.name) }
    var protocol by remember { mutableStateOf(provider.protocol) }
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }
    var apiKey by remember { mutableStateOf(provider.apiKey) }
    var model by remember { mutableStateOf(provider.model) }
    var protocolExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Protocol selector
                Box {
                    OutlinedTextField(
                        value = protocol.name,
                        onValueChange = {},
                        label = { Text("协议") },
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().clickable { protocolExpanded = true },
                    )
                    DropdownMenu(expanded = protocolExpanded, onDismissRequest = { protocolExpanded = false }) {
                        ApiProtocol.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    protocol = p
                                    protocolExpanded = false
                                    // Auto-fill baseUrl when switching protocol
                                    baseUrl = when (p) {
                                        ApiProtocol.Gemini -> "https://generativelanguage.googleapis.com"
                                        ApiProtocol.OpenAI -> "https://api.openai.com"
                                        ApiProtocol.Claude -> "https://api.anthropic.com"
                                    }
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(provider.copy(
                    name = name.ifBlank { provider.name },
                    protocol = protocol,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                ))
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

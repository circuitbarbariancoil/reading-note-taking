package com.readingnotes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.readingnotes.app.settings.AppSettings

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (Int, Int) -> Unit,
    onConnectDropbox: () -> Unit,
    onDisconnectDropbox: () -> Unit,
    onProviderSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var maxRetriesText by remember(settings) { mutableStateOf(settings.maxOcrRetries.toString()) }
    var monthlyBudgetText by remember(settings) { mutableStateOf(settings.monthlyApiBudget.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("设置", onBack)

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = maxRetriesText,
            onValueChange = { maxRetriesText = it.filter { c -> c.isDigit() } },
            label = { Text("OCR 自动重试次数") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = monthlyBudgetText,
            onValueChange = { monthlyBudgetText = it.filter { c -> c.isDigit() } },
            label = { Text("本月 API 上限（0=无限）") },
            singleLine = true,
        )
        Text("本月 API 调用：${settings.apiUsage.monthCalls} 次 · 累计：${settings.apiUsage.totalCalls} 次")
        Button(
            onClick = {
                onSave(
                    maxRetriesText.toIntOrNull()?.coerceIn(0, 10) ?: settings.maxOcrRetries,
                    monthlyBudgetText.toIntOrNull()?.coerceAtLeast(0) ?: settings.monthlyApiBudget,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
        }

        Button(
            onClick = onProviderSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("模型设置")
        }

        Text("Dropbox 状态：${if (settings.hasDropboxCredential) "已连接" else "未连接"}")

        Button(onClick = onConnectDropbox, modifier = Modifier.fillMaxWidth()) {
            Text("连接 Dropbox")
        }
        TextButton(
            onClick = onDisconnectDropbox,
            enabled = settings.hasDropboxCredential,
        ) {
            Text("断开 Dropbox")
        }
    }
}

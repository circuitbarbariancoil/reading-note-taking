package com.readingnotes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.readingnotes.app.settings.AppSettings

@Composable
fun HomeScreen(
    settings: AppSettings,
    onCapture: () -> Unit,
    onWorkbench: () -> Unit,
    onSettings: () -> Unit,
    onPreview: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("日语纸书采集", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前设置")
                Text("书名：${settings.bookTitle}")
                Text("OCR：${if (settings.hasAnyProvider) "已配置" else "未配置"}")
                Text("Dropbox：${if (settings.hasDropboxCredential) "已连接" else "未连接"}")
            }
        }

        Button(onClick = onCapture, modifier = Modifier.fillMaxWidth()) {
            Text("拍照采集")
        }
        Button(onClick = onWorkbench, modifier = Modifier.fillMaxWidth()) {
            Text("加工台")
        }
        Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text("设置")
        }
        Button(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
            Text("渲染预览")
        }
    }
}

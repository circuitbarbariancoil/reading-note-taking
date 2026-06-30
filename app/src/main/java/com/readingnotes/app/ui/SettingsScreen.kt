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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.readingnotes.app.settings.AppSettings

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (String, String) -> Unit,
    onConnectDropbox: () -> Unit,
    onDisconnectDropbox: () -> Unit,
    onBack: () -> Unit,
) {
    var geminiKey by remember(settings) { mutableStateOf(settings.geminiApiKey.orEmpty()) }
    var bookTitle by remember(settings) { mutableStateOf(settings.bookTitle) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("设置", onBack)

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = bookTitle,
            onValueChange = { bookTitle = it },
            label = { Text("书名") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = geminiKey,
            onValueChange = { geminiKey = it },
            label = { Text("Gemini API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = { onSave(geminiKey, bookTitle) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
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

package com.readingnotes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.readingnotes.app.dropbox.DropboxConfig
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.repository.CaptureResult
import com.readingnotes.app.settings.AppSettings
import com.readingnotes.app.settings.SettingsStore
import com.readingnotes.app.ui.CaptureScreen
import com.readingnotes.app.ui.HomeScreen
import com.readingnotes.app.ui.PagePreviewScreen
import com.readingnotes.app.ui.SettingsScreen

private enum class ShellScreen {
    Home,
    Capture,
    Settings,
    Preview,
}

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var bookRepository: BookRepository

    private var appSettings by mutableStateOf(AppSettings())
    private var currentScreen by mutableStateOf(ShellScreen.Home)
    private var captureResult by mutableStateOf<CaptureResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(applicationContext)
        bookRepository = BookRepository(applicationContext)
        appSettings = settingsStore.read()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        ShellScreen.Home -> HomeScreen(
                            settings = appSettings,
                            onCapture = { currentScreen = ShellScreen.Capture },
                            onSettings = { currentScreen = ShellScreen.Settings },
                            onPreview = { currentScreen = ShellScreen.Preview },
                        )

                        ShellScreen.Capture -> CaptureScreen(
                            settings = appSettings,
                            repository = bookRepository,
                            lastResult = captureResult,
                            onCaptureResult = { captureResult = it },
                            onOpenSettings = { currentScreen = ShellScreen.Settings },
                            onBack = { currentScreen = ShellScreen.Home },
                        )

                        ShellScreen.Settings -> SettingsScreen(
                            settings = appSettings,
                            onSave = { geminiKey, bookTitle ->
                                settingsStore.saveGeminiApiKey(geminiKey)
                                settingsStore.saveBookTitle(bookTitle)
                                appSettings = settingsStore.read()
                            },
                            onConnectDropbox = { startDropboxConnect() },
                            onDisconnectDropbox = {
                                settingsStore.clearDropboxCredential()
                                appSettings = settingsStore.read()
                            },
                            onBack = { currentScreen = ShellScreen.Home },
                        )

                        ShellScreen.Preview -> PagePreviewScreen()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Auth.getDbxCredential()?.let { credential ->
            settingsStore.saveDropboxCredentialJson(
                DbxCredential.Writer.writeToString(credential),
            )
            appSettings = settingsStore.read()
        }
    }

    private fun startDropboxConnect() {
        Auth.startOAuth2PKCE(
            this,
            DropboxConfig.APP_KEY,
            DbxRequestConfig.newBuilder(DropboxConfig.REQUEST_NAME).build(),
            DropboxConfig.SCOPES,
        )
    }
}

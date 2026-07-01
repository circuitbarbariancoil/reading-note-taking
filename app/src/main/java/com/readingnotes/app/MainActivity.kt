package com.readingnotes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.readingnotes.app.dropbox.DropboxConfig
import com.readingnotes.app.model.Book
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.repository.CaptureResult
import com.readingnotes.app.settings.AppSettings
import com.readingnotes.app.settings.SettingsStore
import com.readingnotes.app.ui.BookShelfScreen
import com.readingnotes.app.ui.CaptureScreen
import com.readingnotes.app.ui.PageListScreen
import com.readingnotes.app.ui.PagePreviewScreen
import com.readingnotes.app.ui.PaletteScreen
import com.readingnotes.app.ui.SettingsScreen
import com.readingnotes.app.ui.WorkbenchScreen
import com.readingnotes.app.ui.theme.ReadingNotesTheme

private enum class ShellScreen {
    BookShelf,
    PageList,
    Capture,
    Settings,
    Preview,
    Workbench,
    Palette,
}

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var bookRepository: BookRepository

    private var appSettings by mutableStateOf(AppSettings())
    private var currentScreen by mutableStateOf(ShellScreen.BookShelf)
    private var captureResult by mutableStateOf<CaptureResult?>(null)
    private var activeBook by mutableStateOf<Book?>(null)
    private var activePageIndex by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(applicationContext)
        bookRepository = BookRepository(applicationContext)
        appSettings = settingsStore.read()

        setContent {
            ReadingNotesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        ShellScreen.BookShelf -> BookShelfScreen(
                            books = bookRepository.listBooks(),
                            repository = bookRepository,
                            onOpenBook = { book ->
                                activeBook = book
                                currentScreen = ShellScreen.PageList
                            },
                            onSettings = { currentScreen = ShellScreen.Settings },
                            onNewBook = { book ->
                                activeBook = book
                                currentScreen = ShellScreen.PageList
                            },
                        )

                        ShellScreen.PageList -> {
                            val book = activeBook
                            if (book == null) {
                                currentScreen = ShellScreen.BookShelf
                            } else {
                                PageListScreen(
                                    book = book,
                                    repository = bookRepository,
                                    onOpenPage = { page ->
                                        activePageIndex = book.pages.indexOf(page).coerceAtLeast(0)
                                        currentScreen = ShellScreen.Workbench
                                    },
                                    onCapture = { currentScreen = ShellScreen.Capture },
                                    onBatchOcr = { /* TODO: batch OCR */ },
                                    onBack = { currentScreen = ShellScreen.BookShelf },
                                    onProcessCapture = { /* TODO: process single capture */ },
                                )
                            }
                        }

                        ShellScreen.Capture -> CaptureScreen(
                            settings = appSettings,
                            repository = bookRepository,
                            lastResult = captureResult,
                            onCaptureResult = { result ->
                                captureResult = result
                                activeBook = result.book
                            },
                            onOpenSettings = { currentScreen = ShellScreen.Settings },
                            onBack = {
                                currentScreen = if (activeBook != null) ShellScreen.PageList else ShellScreen.BookShelf
                            },
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
                            onBack = { currentScreen = ShellScreen.BookShelf },
                        )

                        ShellScreen.Preview -> PagePreviewScreen()

                        ShellScreen.Workbench -> {
                            val book = activeBook ?: bookRepository.loadCurrentBook()
                            if (book == null) {
                                currentScreen = ShellScreen.BookShelf
                            } else {
                                WorkbenchScreen(
                                    initialBook = book,
                                    settings = appSettings,
                                    repository = bookRepository,
                                    onBack = { currentScreen = ShellScreen.PageList },
                                    onOpenPalette = { currentScreen = ShellScreen.Palette },
                                    onCapture = { currentScreen = ShellScreen.Capture },
                                )
                            }
                        }

                        ShellScreen.Palette -> PaletteScreen(
                            palette = appSettings.palette,
                            onSave = {
                                settingsStore.savePalette(it)
                                appSettings = settingsStore.read()
                            },
                            onBack = { currentScreen = ShellScreen.Workbench },
                        )
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

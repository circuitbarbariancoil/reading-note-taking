package com.readingnotes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.readingnotes.app.dropbox.DropboxConfig
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Capture
import com.readingnotes.app.model.Page
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.repository.ProcessOutcome
import com.readingnotes.app.settings.AppSettings
import com.readingnotes.app.settings.SettingsStore
import com.readingnotes.app.ui.BookShelfScreen
import com.readingnotes.app.ui.CaptureScreen
import com.readingnotes.app.ui.OcrJobState
import com.readingnotes.app.ui.PageListScreen
import com.readingnotes.app.ui.PaletteScreen
import com.readingnotes.app.ui.SettingsScreen
import com.readingnotes.app.ui.WorkbenchScreen
import com.readingnotes.app.ui.theme.ReadingNotesTheme
import kotlinx.coroutines.launch

private enum class ShellScreen {
    BookShelf,
    PageList,
    Capture,
    Settings,
    Workbench,
    Palette,
}

/** A capture whose OCR finished but produced no page number: ask the user. */
private data class PendingPageNumber(val capture: Capture, val ocrText: String)

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var bookRepository: BookRepository

    private var appSettings by mutableStateOf(AppSettings())
    private var currentScreen by mutableStateOf(ShellScreen.BookShelf)
    private var activeBook by mutableStateOf<Book?>(null)
    private var activePageIndex by mutableStateOf(0)

    /** OCR job status keyed by capture id (or "page-N" for re-OCR of a page). */
    private val ocrStatus = mutableStateMapOf<String, OcrJobState>()
    private var pendingPageNumber by mutableStateOf<PendingPageNumber?>(null)

    /** Where 拍照 was launched from: workbench shots auto-OCR and jump to the new page. */
    private var captureFromWorkbench = false
    private var captureShotCount by mutableStateOf(0)
    private var captureSaving by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(applicationContext)
        bookRepository = BookRepository(applicationContext)
        appSettings = settingsStore.read()

        setContent {
            ReadingNotesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AppShell() {
        BackHandler(enabled = currentScreen != ShellScreen.BookShelf) {
            currentScreen = when (currentScreen) {
                ShellScreen.PageList, ShellScreen.Settings -> ShellScreen.BookShelf
                ShellScreen.Capture -> if (captureFromWorkbench) ShellScreen.Workbench else ShellScreen.PageList
                ShellScreen.Workbench -> ShellScreen.PageList
                ShellScreen.Palette -> ShellScreen.Workbench
                ShellScreen.BookShelf -> ShellScreen.BookShelf
            }
        }

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
                        ocrStatus = ocrStatus,
                        onOpenPage = { page ->
                            activePageIndex = book.pages.indexOf(page).coerceAtLeast(0)
                            currentScreen = ShellScreen.Workbench
                        },
                        onCapture = { openCapture(fromWorkbench = false) },
                        onBatchOcr = { batchOcr() },
                        onBack = { currentScreen = ShellScreen.BookShelf },
                        onOcrCapture = { capture -> ocrCapture(capture, jumpToPage = false) },
                        onAssignPage = { capture, pageNumber -> assignPage(capture, pageNumber) },
                        onFillPageNumber = { capture ->
                            capture.ocrText?.let { pendingPageNumber = PendingPageNumber(capture, it) }
                        },
                        onDeleteCapture = { capture -> deleteCapture(capture) },
                    )
                }
            }

            ShellScreen.Capture -> {
                val book = activeBook
                if (book == null) {
                    currentScreen = ShellScreen.BookShelf
                } else {
                    CaptureScreen(
                        title = book.title,
                        shotCount = captureShotCount,
                        saving = captureSaving,
                        onShot = { bytes -> saveShot(bytes) },
                        onClose = {
                            currentScreen = if (captureFromWorkbench) ShellScreen.Workbench else ShellScreen.PageList
                        },
                    )
                }
            }

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

            ShellScreen.Workbench -> {
                val book = activeBook
                if (book == null) {
                    currentScreen = ShellScreen.BookShelf
                } else {
                    WorkbenchScreen(
                        initialBook = book,
                        initialPageIndex = activePageIndex,
                        settings = appSettings,
                        repository = bookRepository,
                        ocrBusy = ocrStatus.values.any { it == OcrJobState.Running },
                        onBack = {
                            activeBook = bookRepository.loadBook(book.uid) ?: book
                            currentScreen = ShellScreen.PageList
                        },
                        onOpenPalette = { currentScreen = ShellScreen.Palette },
                        onCapture = { openCapture(fromWorkbench = true) },
                        onOcrPage = { page -> ocrPage(page) },
                        onChangePageNumber = { page, newNumber -> changePageNumber(page, newNumber) },
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

        pendingPageNumber?.let { pending ->
            PageNumberDialog(
                onConfirm = { pageNumber ->
                    pendingPageNumber = null
                    finishWithManualPageNumber(pending, pageNumber)
                },
                onDismiss = { pendingPageNumber = null },
            )
        }
    }

    private fun openCapture(fromWorkbench: Boolean) {
        captureFromWorkbench = fromWorkbench
        captureShotCount = 0
        currentScreen = ShellScreen.Capture
    }

    private fun saveShot(bytes: ByteArray) {
        val book = activeBook ?: return
        captureSaving = true
        lifecycleScope.launch {
            try {
                val updated = bookRepository.saveCapture(book, bytes)
                val newCapture = updated.captures.last()
                activeBook = updated
                captureShotCount++
                if (captureFromWorkbench) {
                    // Immersive flow: one shot, back to the workbench, OCR in background.
                    currentScreen = ShellScreen.Workbench
                    ocrCapture(newCapture, jumpToPage = true)
                }
            } finally {
                captureSaving = false
            }
        }
    }

    private fun ocrCapture(capture: Capture, jumpToPage: Boolean) {
        lifecycleScope.launch { runOcrCapture(capture, jumpToPage) }
    }

    private suspend fun runOcrCapture(capture: Capture, jumpToPage: Boolean) {
        val key = appSettings.geminiApiKey
        val book = activeBook ?: return
        if (key.isNullOrBlank()) {
            ocrStatus[capture.id] = OcrJobState.Failed
            return
        }
        ocrStatus[capture.id] = OcrJobState.Running
        try {
            when (val outcome = bookRepository.processCapture(book, capture, key, precomputedOcrText = capture.ocrText)) {
                is ProcessOutcome.Done -> {
                    ocrStatus.remove(capture.id)
                    activeBook = outcome.book
                    if (jumpToPage) {
                        activePageIndex = outcome.book.pages
                            .indexOfFirst { it.page == outcome.page.page && it.addedAt == outcome.page.addedAt }
                            .coerceAtLeast(0)
                    }
                    syncToDropbox(outcome.book)
                }

                is ProcessOutcome.NeedsPageNumber -> {
                    ocrStatus.remove(capture.id)
                    // Keep the recognized text so 稍后处理 doesn't lose or re-bill it.
                    activeBook = bookRepository.storeCaptureOcrText(book, capture, outcome.ocrText)
                    pendingPageNumber = PendingPageNumber(capture, outcome.ocrText)
                }
            }
        } catch (t: Throwable) {
            ocrStatus[capture.id] = OcrJobState.Failed
        }
    }

    private fun finishWithManualPageNumber(pending: PendingPageNumber, pageNumber: Int) {
        val book = activeBook ?: return
        val key = appSettings.geminiApiKey.orEmpty()
        lifecycleScope.launch {
            try {
                val outcome = bookRepository.processCapture(
                    book,
                    pending.capture,
                    key,
                    manualPageNumber = pageNumber,
                    precomputedOcrText = pending.ocrText,
                )
                if (outcome is ProcessOutcome.Done) {
                    activeBook = outcome.book
                    syncToDropbox(outcome.book)
                }
            } catch (t: Throwable) {
                ocrStatus[pending.capture.id] = OcrJobState.Failed
            }
        }
    }

    private fun batchOcr() {
        lifecycleScope.launch {
            // Sequential: one Gemini call at a time; statuses drive the UI badges.
            val attempted = mutableSetOf<String>()
            while (true) {
                val next = activeBook?.captures?.firstOrNull {
                    it.ocrText == null && it.id !in attempted && ocrStatus[it.id] != OcrJobState.Failed
                } ?: break
                attempted.add(next.id)
                runOcrCapture(next, jumpToPage = false)
                if (ocrStatus[next.id] == OcrJobState.Failed) break
            }
        }
    }

    private fun assignPage(capture: Capture, pageNumber: Int) {
        val book = activeBook ?: return
        lifecycleScope.launch {
            val ocrText = capture.ocrText
            val updated = if (ocrText != null) {
                val outcome = bookRepository.processCapture(
                    book,
                    capture,
                    appSettings.geminiApiKey.orEmpty(),
                    manualPageNumber = pageNumber,
                    precomputedOcrText = ocrText,
                )
                (outcome as ProcessOutcome.Done).book
            } else {
                bookRepository.assignPageNumber(book, capture, pageNumber)
            }
            activeBook = updated
            syncToDropbox(updated)
        }
    }

    private fun changePageNumber(page: Page, newNumber: Int) {
        val book = activeBook ?: return
        lifecycleScope.launch {
            val updated = bookRepository.changePageNumber(book, page, newNumber)
            activePageIndex = updated.pages
                .indexOfFirst { it.page == newNumber && it.addedAt == page.addedAt }
                .coerceAtLeast(0)
            activeBook = updated
            syncToDropbox(updated)
        }
    }

    private fun deleteCapture(capture: Capture) {
        val book = activeBook ?: return
        lifecycleScope.launch {
            activeBook = bookRepository.deleteCapture(book, capture)
        }
    }

    private fun ocrPage(page: Page) {
        val book = activeBook ?: return
        val key = appSettings.geminiApiKey
        val statusKey = "page-${page.page}"
        if (key.isNullOrBlank()) {
            ocrStatus[statusKey] = OcrJobState.Failed
            return
        }
        lifecycleScope.launch {
            ocrStatus[statusKey] = OcrJobState.Running
            try {
                val updated = bookRepository.ocrExistingPage(book, page, key)
                ocrStatus.remove(statusKey)
                activeBook = updated
                syncToDropbox(updated)
            } catch (t: Throwable) {
                ocrStatus[statusKey] = OcrJobState.Failed
            }
        }
    }

    private suspend fun syncToDropbox(book: Book) {
        val credential = appSettings.dropboxCredentialJson ?: return
        runCatching { bookRepository.persist(book, credential) }
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

@androidx.compose.runtime.Composable
private fun PageNumberDialog(
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var pageNumText by androidx.compose.runtime.remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OCR 未识别出页码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("这一页没有识别到页码，请手动填写：")
                OutlinedTextField(
                    value = pageNumText,
                    onValueChange = { pageNumText = it.filter { c -> c.isDigit() } },
                    label = { Text("页码") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { pageNumText.toIntOrNull()?.let(onConfirm) },
                    enabled = pageNumText.toIntOrNull() != null,
                ) { Text("确定") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后处理") }
        },
    )
}

package com.readingnotes.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.readingnotes.app.dropbox.DropboxConfig
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Capture
import com.readingnotes.app.model.Page
import com.readingnotes.app.ocr.OcrRetryWorker
import com.readingnotes.app.settings.SettingsStore
import com.readingnotes.app.ui.BookShelfScreen
import com.readingnotes.app.ui.BrowsableEntry
import com.readingnotes.app.ui.CaptureScreen
import com.readingnotes.app.ui.EntryBrowserScreen
import com.readingnotes.app.ui.EntryEditor
import com.readingnotes.app.ui.OcrJobState
import com.readingnotes.app.ui.PageNumberSheet
import com.readingnotes.app.ui.PageListScreen
import com.readingnotes.app.ui.PaletteScreen
import com.readingnotes.app.ui.ProviderSettingsScreen
import com.readingnotes.app.ui.SettingsScreen
import com.readingnotes.app.ui.WorkbenchScreen
import com.readingnotes.app.ui.theme.ReadingNotesTheme
import java.io.File

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_EDITOR = "open_editor"
    }

    private val viewModel: AppViewModel by viewModels { AppViewModel.factory(application) }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val inputStream = contentResolver.openInputStream(uri) ?: run {
            viewModel.backupStatus = "无法读取文件"
            return@registerForActivityResult
        }
        viewModel.importBackup(inputStream)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        setContent {
            ReadingNotesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Determine whether to open editor based on which alias was used
        val component = intent.component?.className ?: ""
        val openEditor = component.endsWith("ShareEditActivity") ||
            intent.getBooleanExtra(EXTRA_OPEN_EDITOR, false)
        if (openEditor) {
            viewModel.handleShareText(text, openEditor = true)
        } else {
            viewModel.handleShareText(text, openEditor = false)
            Toast.makeText(this, "已添加到笔记本", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Composable
    private fun AppShell() {
        BackHandler(enabled = viewModel.currentScreen != ShellScreen.BookShelf) {
            viewModel.currentScreen = when (viewModel.currentScreen) {
                ShellScreen.PageList, ShellScreen.Settings, ShellScreen.ProviderSettings -> {
                    viewModel.onEnterBookShelf()
                    ShellScreen.BookShelf
                }
                ShellScreen.EntryBrowser -> {
                    viewModel.entryBrowserBookUid = null
                    if (viewModel.entryBrowserOrigin == ShellScreen.BookShelf) viewModel.onEnterBookShelf()
                    viewModel.entryBrowserOrigin
                }
                ShellScreen.EntryEditor -> {
                    if (viewModel.entryEditorFromBrowser) ShellScreen.EntryBrowser
                    else ShellScreen.Workbench
                }
                ShellScreen.Capture -> if (viewModel.captureFromWorkbench) ShellScreen.Workbench else ShellScreen.PageList
                ShellScreen.Workbench -> ShellScreen.PageList
                ShellScreen.Palette -> ShellScreen.Workbench
                ShellScreen.BookShelf -> ShellScreen.BookShelf
            }
        }

        when (viewModel.currentScreen) {
            ShellScreen.BookShelf -> {
                LaunchedEffect(Unit) { viewModel.onEnterBookShelf() }
                BookShelfScreen(
                    books = viewModel.books,
                    repository = viewModel.bookRepository,
                    onOpenBook = { book ->
                        viewModel.activeBook = book
                        viewModel.currentScreen = ShellScreen.PageList
                    },
                    onSettings = { viewModel.currentScreen = ShellScreen.Settings },
                    onNewBook = { book ->
                        viewModel.activeBook = book
                        viewModel.currentScreen = ShellScreen.PageList
                    },
                    onDeleteBooks = { uids -> viewModel.deleteBooks(uids) },
                    onEntries = {
                        viewModel.entryBrowserOrigin = ShellScreen.BookShelf
                        viewModel.entryBrowserBookUid = null
                        viewModel.currentScreen = ShellScreen.EntryBrowser
                    },
                    onOpenNotebook = { viewModel.openNotebook() },
                )
            }

            ShellScreen.PageList -> {
                val book = viewModel.activeBook
                if (book == null) {
                    viewModel.onEnterBookShelf()
                    viewModel.currentScreen = ShellScreen.BookShelf
                } else {
                    PageListScreen(
                        book = book,
                        repository = viewModel.bookRepository,
                        ocrStatus = viewModel.ocrStatus,
                        processItems = viewModel.processQueue,
                        onOpenPage = { page ->
                            viewModel.workbenchFocus = null
                            viewModel.activePageIndex = book.pages.indexOf(page).coerceAtLeast(0)
                            viewModel.currentScreen = ShellScreen.Workbench
                        },
                        onCapture = { viewModel.openCapture(fromWorkbench = false) },
                        onBatchOcr = { viewModel.batchOcr() },
                        onBack = {
                            viewModel.onEnterBookShelf()
                            viewModel.currentScreen = ShellScreen.BookShelf
                        },
                        onOcrCapture = { capture -> viewModel.ocrCapture(capture, jumpToPage = false) },
                        onRetryProcessItem = { item -> viewModel.retryProcessItem(item) },
                        onDismissProcessItem = { item -> viewModel.dismissProcessItem(item) },
                        queueCollapsed = viewModel.queueCollapsed,
                        onExpandQueue = { viewModel.queueCollapsed = false },
                        onCollapseQueue = { viewModel.queueCollapsed = true },
                        onAssignPage = { capture, pageNumber -> viewModel.assignPage(capture, pageNumber) },
                        onFillPageNumber = { capture ->
                            capture.ocrText?.let { viewModel.pendingPageNumber = PendingPageNumber(capture, it) }
                        },
                        onDeleteCapture = { capture -> viewModel.deleteCapture(capture) },
                        onDeletePages = { pages -> viewModel.deletePages(pages) },
                        onDeleteCaptures = { captures -> viewModel.deleteCaptures(captures) },
                        onEntries = {
                            viewModel.entryBrowserOrigin = ShellScreen.PageList
                            viewModel.entryBrowserBookUid = book.uid
                            viewModel.currentScreen = ShellScreen.EntryBrowser
                        },
                    )
                }
            }

            ShellScreen.Capture -> {
                val book = viewModel.activeBook
                if (book == null) {
                    viewModel.currentScreen = ShellScreen.BookShelf
                } else {
                    CaptureScreen(
                        title = book.title,
                        shotCount = viewModel.captureShotCount,
                        saving = viewModel.captureSaving,
                        onShot = { bytes -> viewModel.saveShot(bytes) },
                        onClose = {
                            viewModel.currentScreen = if (viewModel.captureFromWorkbench) ShellScreen.Workbench else ShellScreen.PageList
                        },
                    )
                }
            }

            ShellScreen.Settings -> SettingsScreen(
                settings = viewModel.appSettings,
                onSave = { maxRetries, monthlyBudget -> viewModel.saveSettings(maxRetries, monthlyBudget) },
                onConnectDropbox = { startDropboxConnect() },
                onDisconnectDropbox = { viewModel.clearDropboxCredential() },
                onProviderSettings = { viewModel.currentScreen = ShellScreen.ProviderSettings },
                onRestoreFromDropbox = { viewModel.restoreFromDropbox() },
                onExportBackup = { viewModel.exportFullBackup { zipFile -> shareBackup(zipFile) } },
                onImportBackup = { importBackupLauncher.launch("application/zip") },
                onBack = { viewModel.currentScreen = ShellScreen.BookShelf },
                syncPendingCount = viewModel.syncPendingCount,
                lastSyncTime = viewModel.lastSyncTime,
                restoreStatus = viewModel.restoreStatus,
                backupStatus = viewModel.backupStatus,
            )

            ShellScreen.ProviderSettings -> ProviderSettingsScreen(
                config = viewModel.appSettings.providerConfig,
                usage = viewModel.appSettings.providerApiUsage,
                onSave = { config -> viewModel.saveProviderConfig(config) },
                onBack = { viewModel.currentScreen = ShellScreen.Settings },
            )

            ShellScreen.Workbench -> {
                val book = viewModel.activeBook
                if (book == null) {
                    viewModel.onEnterBookShelf()
                    viewModel.currentScreen = ShellScreen.BookShelf
                } else {
                    WorkbenchScreen(
                        initialBook = book,
                        initialPageIndex = viewModel.activePageIndex,
                        settings = viewModel.appSettings,
                        repository = viewModel.bookRepository,
                        ocrBusy = viewModel.ocrStatus.values.any { it == OcrJobState.Running },
                        ocrError = viewModel.ocrErrorMessage,
                        onDismissOcrError = { viewModel.ocrErrorMessage = null },
                        onBack = {
                            viewModel.workbenchFocus = null
                            viewModel.activeBook = viewModel.bookRepository.loadBook(book.uid) ?: book
                            viewModel.currentScreen = ShellScreen.PageList
                        },
                        onOpenPalette = { viewModel.currentScreen = ShellScreen.Palette },
                        onCapture = { viewModel.openCapture(fromWorkbench = true) },
                        onOcrPage = { page -> viewModel.ocrPage(page) },
                        onChangePageNumber = { page, newNumber -> viewModel.changePageNumber(page, newNumber) },
                        onDeletePage = { page -> viewModel.deletePage(page) },
                        processItems = viewModel.processQueue,
                        onRetryProcessItem = { item -> viewModel.retryProcessItem(item) },
                        onDismissProcessItem = { item -> viewModel.dismissProcessItem(item) },
                        queueCollapsed = viewModel.queueCollapsed,
                        onExpandQueue = { viewModel.queueCollapsed = false },
                        onCollapseQueue = { viewModel.queueCollapsed = true },
                        focusRange = viewModel.workbenchFocus,
                    )
                }
            }

            ShellScreen.Palette -> PaletteScreen(
                palette = viewModel.appSettings.palette,
                onSave = { palette -> viewModel.savePalette(palette) },
                onBack = { viewModel.currentScreen = ShellScreen.Workbench },
            )

            ShellScreen.EntryBrowser -> {
                LaunchedEffect(Unit) {
                    viewModel.refreshBooks()
                    viewModel.refreshNotebook()
                }
                val notebookUid = com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
                val allBooks = viewModel.books
                val notebook = viewModel.notebookBook
                val allEntries = buildList {
                    allBooks.forEach { book ->
                        book.entries.forEach { entry -> add(BrowsableEntry(entry, book.title, book.uid)) }
                    }
                    notebook?.let { nb ->
                        if (allBooks.none { it.uid == notebookUid }) {
                            nb.entries.forEach { entry -> add(BrowsableEntry(entry, nb.title, nb.uid)) }
                        }
                    }
                }
                val booksForFilter = buildList {
                    addAll(allBooks)
                    if (notebook != null && allBooks.none { it.uid == notebookUid }) add(notebook)
                }
                EntryBrowserScreen(
                    entries = allEntries,
                    books = booksForFilter,
                    filterBookUid = viewModel.entryBrowserBookUid,
                    listState = viewModel.entryBrowserListState,
                    colors = viewModel.appSettings.palette.colors,
                    onBack = {
                        viewModel.entryBrowserBookUid = null
                        viewModel.currentScreen = viewModel.entryBrowserOrigin
                    },
                    onEntryClick = { item ->
                        val book = if (item.bookUid == notebookUid) notebook
                            else allBooks.find { it.uid == item.bookUid }
                        if (book != null) {
                            viewModel.activeBook = book
                            viewModel.activePageIndex = if (item.entry.page != null) book.pages.indexOfFirst { it.page == item.entry.page }.coerceAtLeast(0) else 0
                            viewModel.entryEditorFromBrowser = true
                            viewModel.entryEditTarget = EntryEditTarget(book.uid, item.entry.id)
                            viewModel.currentScreen = ShellScreen.EntryEditor
                        }
                    },
                    notebookUid = notebookUid,
                    onNewNotebookEntry = { viewModel.openNotebookDraft() },
                    onDeleteEntries = { items -> viewModel.deleteEntries(items) },
                )
            }

            ShellScreen.EntryEditor -> {
                val draft = viewModel.notebookDraft
                val book = viewModel.activeBook
                val target = viewModel.entryEditTarget
                if (draft != null) {
                    val notebook = viewModel.notebookBook
                    EntryEditor(
                        entry = draft,
                        palette = viewModel.appSettings.palette,
                        knownTags = notebook?.entries.orEmpty().flatMap { it.tags }.distinct().sorted(),
                        onSave = { updated -> viewModel.saveNewNoteEntry(updated) },
                        onDismiss = {
                            viewModel.notebookDraft = null
                            viewModel.entryBrowserBookUid = com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
                            viewModel.currentScreen = ShellScreen.EntryBrowser
                        },
                    )
                } else if (book == null || target == null) {
                    viewModel.currentScreen = ShellScreen.BookShelf
                } else {
                    val entry = book.entries.firstOrNull { it.id == target.entryId }
                    if (entry == null) {
                        viewModel.currentScreen = ShellScreen.BookShelf
                    } else {
                        val isNotebookEntry = book.uid == com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
                        val pageIndex = if (entry.page != null) book.pages.indexOfFirst { it.page == entry.page }.coerceAtLeast(0) else 0
                        EntryEditor(
                            entry = entry,
                            palette = viewModel.appSettings.palette,
                            knownTags = book.entries.flatMap { it.tags }.distinct().sorted(),
                            onSave = { updated ->
                                if (isNotebookEntry) viewModel.saveEditedNoteEntry(updated)
                                else viewModel.saveEditedEntry(updated)
                            },
                            onDismiss = {
                                viewModel.entryEditTarget = null
                                if (isNotebookEntry) {
                                    viewModel.entryBrowserBookUid = com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
                                    viewModel.currentScreen = ShellScreen.EntryBrowser
                                } else if (viewModel.entryEditorFromBrowser) {
                                    viewModel.currentScreen = ShellScreen.EntryBrowser
                                } else {
                                    viewModel.currentScreen = ShellScreen.Workbench
                                }
                                viewModel.entryEditorFromBrowser = false
                            },
                            onViewOriginal = {
                                if (entry.page != null) {
                                    viewModel.workbenchFocus = viewModel.locateEntrySource(book.pages.getOrNull(pageIndex)?.ocrText, entry)
                                    viewModel.activePageIndex = pageIndex
                                    viewModel.currentScreen = ShellScreen.Workbench
                                }
                            },
                        )
                    }
                }
            }
        }

        viewModel.pendingPageNumber?.let { pending ->
            val hintLines = pending.ocrText
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(2)
                .toList()
                .joinToString(" / ")
            PageNumberSheet(
                title = "填写页码",
                imagePath = pending.capture.imagePath,
                hintText = hintLines.ifBlank { null },
                confirmLabel = "确定",
                dismissLabel = "稍后处理",
                onConfirm = {
                    viewModel.pendingPageNumber = null
                    viewModel.finishWithManualPageNumber(pending, it)
                },
                onDismiss = { viewModel.pendingPageNumber = null },
            )
        }
    }

    private fun shareBackup(zipFile: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "备份全部数据"))
    }

    override fun onResume() {
        super.onResume()
        Auth.getDbxCredential()?.let { credential ->
            viewModel.saveDropboxCredential(DbxCredential.Writer.writeToString(credential))
            viewModel.reloadSettings()
            viewModel.refreshSyncStatus()
        }
        viewModel.refreshSyncStatus()
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

package com.readingnotes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.readingnotes.app.dropbox.DropboxConfig
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Capture
import com.readingnotes.app.model.CodePoints
import com.readingnotes.app.model.Entry
import com.readingnotes.app.model.MarkupText
import com.readingnotes.app.model.Page
import com.readingnotes.app.dropbox.DropboxSyncWorker
import com.readingnotes.app.repository.DuplicatePageNumberException
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.repository.ProcessOutcome
import com.readingnotes.app.settings.AppSettings
import com.readingnotes.app.settings.SettingsStore
import com.readingnotes.app.ui.BookShelfScreen
import com.readingnotes.app.ui.BrowsableEntry
import com.readingnotes.app.ui.CaptureScreen
import com.readingnotes.app.ui.EntryBrowserScreen
import com.readingnotes.app.ocr.LlmProvider
import com.readingnotes.app.ocr.OcrRetryWorker
import com.readingnotes.app.ocr.ProviderConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.readingnotes.app.ui.EntryEditor
import com.readingnotes.app.ui.OcrJobState
import com.readingnotes.app.ui.PageNumberSheet
import com.readingnotes.app.ui.PageListScreen
import com.readingnotes.app.ui.PaletteScreen
import com.readingnotes.app.ui.ProviderSettingsScreen
import com.readingnotes.app.ui.SettingsScreen
import com.readingnotes.app.ui.WorkbenchScreen
import com.readingnotes.app.ui.ProcessItem
import com.readingnotes.app.ui.ProcessStep
import com.readingnotes.app.ui.theme.ReadingNotesTheme
import kotlinx.coroutines.launch

private enum class ShellScreen {
    BookShelf,
    PageList,
    Capture,
    Settings,
    ProviderSettings,
    Workbench,
    Palette,
    EntryBrowser,
    EntryEditor,
}

/** A capture whose OCR finished but produced no page number: ask the user. */
private data class PendingPageNumber(val capture: Capture, val ocrText: String)

private data class EntryEditTarget(val bookUid: String, val entryId: String)

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var bookRepository: BookRepository

    private var appSettings by mutableStateOf(AppSettings())
    private var currentScreen by mutableStateOf(ShellScreen.BookShelf)
    private var activeBook by mutableStateOf<Book?>(null)
    private var activePageIndex by mutableStateOf(0)

    /** OCR job status keyed by capture id (or "page-N" for re-OCR of a page). */
    private val ocrStatus = mutableStateMapOf<String, OcrJobState>()
    private val processQueue = mutableStateListOf<ProcessItem>()
    private var queueCollapsed by mutableStateOf(false)
    private var pendingPageNumber by mutableStateOf<PendingPageNumber?>(null)
    private var entryEditTarget by mutableStateOf<EntryEditTarget?>(null)
    private var entryEditorFromBrowser by mutableStateOf(false)
    private var workbenchFocus by mutableStateOf<IntRange?>(null)
    private var ocrErrorMessage by mutableStateOf<String?>(null)
    private var entryBrowserBookUid by mutableStateOf<String?>(null)

    /** Where 拍照 was launched from: workbench shots auto-OCR and jump to the new page. */
    private var captureFromWorkbench = false
    private var captureShotCount by mutableStateOf(0)
    private var captureSaving by mutableStateOf(false)

    // Sync status
    private var syncPendingCount by mutableStateOf(0)
    private var lastSyncTime by mutableStateOf<String?>(null)
    private var restoreStatus by mutableStateOf<String?>(null)
    private var backupStatus by mutableStateOf<String?>(null)

    private val importBackupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            backupStatus = "正在恢复…"
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: run {
                    backupStatus = "无法读取文件"
                    return@launch
                }
                val count = bookRepository.importFullBackup(inputStream, settingsStore) { msg ->
                    backupStatus = msg
                }
                appSettings = settingsStore.read()
                backupStatus = if (count > 0) "已恢复 $count 本书 + 设置" else "没有需要恢复的内容"
            } catch (e: Exception) {
                backupStatus = "恢复失败: ${e.message?.take(60)}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(applicationContext)
        bookRepository = BookRepository(applicationContext)
        DropboxSyncWorker.schedulePeriodic(applicationContext)
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
                ShellScreen.PageList, ShellScreen.Settings, ShellScreen.ProviderSettings, ShellScreen.EntryBrowser -> ShellScreen.BookShelf
                ShellScreen.EntryEditor -> if (entryEditorFromBrowser) ShellScreen.EntryBrowser else ShellScreen.Workbench
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
                onDeleteBooks = { uids -> deleteBooks(uids) },
                onEntries = {
                    entryBrowserBookUid = null
                    currentScreen = ShellScreen.EntryBrowser
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
                        processItems = processQueue,
                        onOpenPage = { page ->
                            workbenchFocus = null
                            activePageIndex = book.pages.indexOf(page).coerceAtLeast(0)
                            currentScreen = ShellScreen.Workbench
                        },
                        onCapture = { openCapture(fromWorkbench = false) },
                        onBatchOcr = { batchOcr() },
                        onBack = { currentScreen = ShellScreen.BookShelf },
                        onOcrCapture = { capture -> ocrCapture(capture, jumpToPage = false) },
                        onRetryProcessItem = { item -> retryProcessItem(item) },
                        onDismissProcessItem = { item -> dismissProcessItem(item) },
                        queueCollapsed = queueCollapsed,
                        onExpandQueue = { queueCollapsed = false },
                        onCollapseQueue = { queueCollapsed = true },
                        onAssignPage = { capture, pageNumber -> assignPage(capture, pageNumber) },
                        onFillPageNumber = { capture ->
                            capture.ocrText?.let { pendingPageNumber = PendingPageNumber(capture, it) }
                        },
                        onDeleteCapture = { capture -> deleteCapture(capture) },
                        onDeletePages = { pages -> deletePages(pages) },
                        onDeleteCaptures = { captures -> deleteCaptures(captures) },
                        onEntries = {
                            entryBrowserBookUid = book.uid
                            currentScreen = ShellScreen.EntryBrowser
                        },
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
                onSave = { maxRetries, monthlyBudget ->
                    settingsStore.saveMaxOcrRetries(maxRetries)
                    settingsStore.saveMonthlyApiBudget(monthlyBudget)
                    appSettings = settingsStore.read()
                },
                onConnectDropbox = { startDropboxConnect() },
                onDisconnectDropbox = {
                    settingsStore.clearDropboxCredential()
                    appSettings = settingsStore.read()
                },
                onProviderSettings = { currentScreen = ShellScreen.ProviderSettings },
                onRestoreFromDropbox = { restoreFromDropbox() },
                onExportBackup = { exportFullBackup() },
                onImportBackup = { importFullBackup() },
                onBack = { currentScreen = ShellScreen.BookShelf },
                syncPendingCount = syncPendingCount,
                lastSyncTime = lastSyncTime,
                restoreStatus = restoreStatus,
                backupStatus = backupStatus,
            )

            ShellScreen.ProviderSettings -> ProviderSettingsScreen(
                config = appSettings.providerConfig,
                usage = appSettings.providerApiUsage,
                onSave = { config ->
                    settingsStore.saveProviderConfig(config)
                    appSettings = settingsStore.read()
                },
                onBack = { currentScreen = ShellScreen.Settings },
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
                        ocrError = ocrErrorMessage,
                        onDismissOcrError = { ocrErrorMessage = null },
                        onBack = {
                            workbenchFocus = null
                            activeBook = bookRepository.loadBook(book.uid) ?: book
                            currentScreen = ShellScreen.PageList
                        },
                        onOpenPalette = { currentScreen = ShellScreen.Palette },
                        onCapture = { openCapture(fromWorkbench = true) },
                        onOcrPage = { page -> ocrPage(page) },
                        onChangePageNumber = { page, newNumber -> changePageNumber(page, newNumber) },
                        onDeletePage = { page -> deletePage(page) },
                        processItems = processQueue,
                        onRetryProcessItem = { item -> retryProcessItem(item) },
                        onDismissProcessItem = { item -> dismissProcessItem(item) },
                        queueCollapsed = queueCollapsed,
                        onExpandQueue = { queueCollapsed = false },
                        onCollapseQueue = { queueCollapsed = true },
                        focusRange = workbenchFocus,
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

            ShellScreen.EntryBrowser -> {
                val allBooks = bookRepository.listBooks()
                val allEntries = allBooks.flatMap { book ->
                    book.entries.map { entry -> BrowsableEntry(entry, book.title, book.uid) }
                }
                EntryBrowserScreen(
                    entries = allEntries,
                    books = allBooks,
                    filterBookUid = entryBrowserBookUid,
                    colors = appSettings.palette.colors,
                    onBack = {
                        currentScreen = if (entryBrowserBookUid != null) ShellScreen.PageList else ShellScreen.BookShelf
                    },
                    onEntryClick = { item ->
                        val book = allBooks.find { it.uid == item.bookUid }
                        if (book != null) {
                            activeBook = book
                            activePageIndex = book.pages.indexOfFirst { it.page == item.entry.page }.coerceAtLeast(0)
                            entryEditorFromBrowser = true
                            entryEditTarget = EntryEditTarget(book.uid, item.entry.id)
                            currentScreen = ShellScreen.EntryEditor
                        }
                    },
                )
            }

            ShellScreen.EntryEditor -> {
                val book = activeBook
                val target = entryEditTarget
                if (book == null || target == null) {
                    currentScreen = ShellScreen.BookShelf
                } else {
                    val entry = book.entries.firstOrNull { it.id == target.entryId }
                    if (entry == null) {
                        currentScreen = ShellScreen.BookShelf
                    } else {
                        val pageIndex = book.pages.indexOfFirst { it.page == entry.page }.coerceAtLeast(0)
                        EntryEditor(
                            entry = entry,
                            palette = appSettings.palette,
                            knownTags = book.entries.flatMap { it.tags }.distinct().sorted(),
                            onSave = { updated ->
                                lifecycleScope.launch {
                                    val updatedBook = book.copy(
                                        entries = book.entries.map { if (it.id == updated.id) updated else it },
                                    )
                                    activeBook = bookRepository.persist(updatedBook, appSettings.dropboxCredentialJson)
                                    val fromBrowser = entryEditorFromBrowser
                                    entryEditTarget = null
                                    entryEditorFromBrowser = false
                                    currentScreen = if (fromBrowser) ShellScreen.EntryBrowser else ShellScreen.Workbench
                                }
                            },
                            onDismiss = {
                                val fromBrowser = entryEditorFromBrowser
                                entryEditTarget = null
                                entryEditorFromBrowser = false
                                currentScreen = if (fromBrowser) ShellScreen.EntryBrowser else ShellScreen.Workbench
                            },
                            onViewOriginal = {
                                workbenchFocus = locateEntrySource(book.pages.getOrNull(pageIndex)?.ocrText, entry)
                                activePageIndex = pageIndex
                                currentScreen = ShellScreen.Workbench
                            },
                        )
                    }
                }
            }
        }

        pendingPageNumber?.let { pending ->
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
                    pendingPageNumber = null
                    finishWithManualPageNumber(pending, it)
                },
                onDismiss = { pendingPageNumber = null },
            )
        }
    }

    /**
     * Locates an entry's source sentence in the (possibly re-OCR'd) page text.
     * Tries the stored offsets first; falls back to text search when the frozen
     * text has changed. Returns null when the source can't be found.
     */
    private fun locateEntrySource(ocrText: String?, entry: Entry): IntRange? {
        val text = ocrText ?: return null
        val plain = MarkupText.plain(entry.text).trim()
        if (plain.isEmpty()) return null
        val n = text.codePointCount(0, text.length)
        if (entry.srcStart in 0 until entry.srcEnd && entry.srcEnd <= n) {
            val candidate = CodePoints.substring(text, entry.srcStart, entry.srcEnd)
            val probe = plain.take(8)
            if (candidate.contains(probe) || plain.contains(candidate.take(8))) {
                return entry.srcStart until entry.srcEnd
            }
        }
        val fullIdx = text.indexOf(plain)
        if (fullIdx >= 0) {
            val s = text.codePointCount(0, fullIdx)
            return s until s + plain.codePointCount(0, plain.length)
        }
        val probe = plain.take(16)
        val idx = text.indexOf(probe)
        if (idx < 0) return null
        val s = text.codePointCount(0, idx)
        return s until s + probe.codePointCount(0, probe.length)
    }

    private fun openCapture(fromWorkbench: Boolean) {
        captureFromWorkbench = fromWorkbench
        captureShotCount = 0
        currentScreen = ShellScreen.Capture
    }

    private fun saveShot(bytes: ByteArray) {
        val book = activeBook ?: return
        val tempId = "shot-${captureShotCount + 1}-${System.currentTimeMillis()}"
        startNewBatchIfIdle()
        upsertProcessItem(processQueue, tempId, ProcessStep.Saving)
        captureSaving = true
        lifecycleScope.launch {
            try {
                val updated = bookRepository.saveCapture(book, bytes)
                val newCapture = updated.captures.last()
                activeBook = updated
                captureShotCount++
                replaceProcessItem(processQueue, tempId, newCapture.id, ProcessStep.Saving)
                if (captureFromWorkbench) {
                    // Immersive flow: one shot, back to the workbench, OCR in background.
                    currentScreen = ShellScreen.Workbench
                    if (isMonthlyApiBudgetExceeded()) {
                        showMonthlyApiBudgetError()
                        removeProcessItem(processQueue, newCapture.id)
                    } else {
                        upsertProcessItem(processQueue, newCapture.id, ProcessStep.Ocr)
                        ocrCapture(newCapture, jumpToPage = true)
                    }
                } else {
                    removeProcessItem(processQueue, newCapture.id)
                }
            } catch (t: Throwable) {
                upsertProcessItem(processQueue, tempId, ProcessStep.Failed, message = t.message?.take(80) ?: "保存失败")
            } finally {
                captureSaving = false
            }
        }
    }

    private fun ocrCapture(capture: Capture, jumpToPage: Boolean) {
        if (isMonthlyApiBudgetExceeded()) {
            showMonthlyApiBudgetError()
            return
        }
        startNewBatchIfIdle()
        lifecycleScope.launch { runOcrCapture(capture, jumpToPage) }
    }

    /** A new OCR batch replaces the finished previous one on the status board. */
    private fun startNewBatchIfIdle() {
        val active = processQueue.any {
            it.step == ProcessStep.Queued || it.step == ProcessStep.Saving || it.step == ProcessStep.Ocr
        }
        if (!active) processQueue.clear()
    }

    /**
     * Run OCR for a single capture. When [assignedProvider] is set (parallel mode),
     * a single-provider config is used so the capture is processed by that specific provider.
     */
    private suspend fun runOcrCapture(
        capture: Capture,
        jumpToPage: Boolean,
        assignedProvider: LlmProvider? = null,
    ) {
        val key = appSettings.geminiApiKey.orEmpty()
        val book = activeBook ?: return
        if (!appSettings.hasAnyProvider) {
            ocrStatus[capture.id] = OcrJobState.Failed
            upsertProcessItem(processQueue, capture.id, ProcessStep.Failed, message = "未配置 OCR 服务（去设置添加）")
            return
        }
        ocrStatus[capture.id] = OcrJobState.Running
        upsertProcessItem(
            processQueue, capture.id, ProcessStep.Ocr,
            providerName = assignedProvider?.name,
        )
        val effectiveConfig = if (assignedProvider != null) {
            ProviderConfig(
                providers = listOf(assignedProvider),
                activeIndex = 0,
                fallbackOnError = false,
            )
        } else {
            appSettings.providerConfig
        }
        try {
            when (val outcome = bookRepository.processCapture(
                book,
                capture,
                key,
                precomputedOcrText = capture.ocrText,
                providerConfig = effectiveConfig,
                onApiCall = { providerId -> recordApiCall(providerId) },
            )) {
                is ProcessOutcome.Done -> {
                    ocrStatus.remove(capture.id)
                    upsertProcessItem(processQueue, capture.id, ProcessStep.Done)
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
                    upsertProcessItem(processQueue, capture.id, ProcessStep.Done)
                    activeBook = bookRepository.storeCaptureOcrText(book, capture, outcome.ocrText)
                }
            }
        } catch (t: Throwable) {
            ocrStatus[capture.id] = OcrJobState.Failed
            ocrErrorMessage = "OCR 失败: ${t.message?.take(80) ?: "未知错误"}"
            upsertProcessItem(processQueue, capture.id, ProcessStep.Failed, message = t.message?.take(80) ?: "未知错误")
            OcrRetryWorker.enqueueCaptureOcr(this@MainActivity, book.uid, capture.id)
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
                    providerConfig = appSettings.providerConfig,
                    onApiCall = { providerId -> recordApiCall(providerId) },
                )
                if (outcome is ProcessOutcome.Done) {
                    activeBook = outcome.book
                    syncToDropbox(outcome.book)
                }
            } catch (e: DuplicatePageNumberException) {
                ocrErrorMessage = e.message
            } catch (t: Throwable) {
                ocrStatus[pending.capture.id] = OcrJobState.Failed
                upsertProcessItem(processQueue, pending.capture.id, ProcessStep.Failed, message = t.message?.take(80) ?: "未知错误")
            }
        }
    }

    private fun batchOcr() {
        queueCollapsed = false
        if (isMonthlyApiBudgetExceeded()) {
            showMonthlyApiBudgetError()
            return
        }
        startNewBatchIfIdle()
        val config = appSettings.providerConfig
        val usable = config.usableProviders
        val parallel = config.parallelOcr && usable.size > 1

        lifecycleScope.launch {
            val pendingCaptures = activeBook?.captures?.filter { it.ocrText == null }.orEmpty()
            pendingCaptures.forEach { capture ->
                upsertProcessItem(processQueue, capture.id, ProcessStep.Queued)
            }

            if (parallel) {
                // Parallel mode: N workers, one per usable provider, pulling from a shared channel
                val channel = kotlinx.coroutines.channels.Channel<Capture>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                for (capture in pendingCaptures) channel.send(capture)
                channel.close()

                coroutineScope {
                    val workers = usable.map { provider ->
                        async {
                            for (capture in channel) {
                                if (isMonthlyApiBudgetExceeded()) {
                                    showMonthlyApiBudgetError()
                                    break
                                }
                                runOcrCapture(capture, jumpToPage = false, assignedProvider = provider)
                            }
                        }
                    }
                    workers.awaitAll()
                }
                // After parallel workers complete, refresh activeBook from disk
                // to ensure we have the latest state (each worker may have set
                // activeBook to its own snapshot, potentially losing other workers' changes).
                activeBook?.uid?.let { uid ->
                    activeBook = bookRepository.loadBook(uid) ?: activeBook
                }
            } else {
                // Sequential mode (original behavior)
                for (capture in pendingCaptures) {
                    if (isMonthlyApiBudgetExceeded()) {
                        showMonthlyApiBudgetError()
                        break
                    }
                    runOcrCapture(capture, jumpToPage = false)
                }
            }
        }
    }

    private fun assignPage(capture: Capture, pageNumber: Int) {
        val book = activeBook ?: return
        lifecycleScope.launch {
            try {
                val ocrText = capture.ocrText
                val updated = if (ocrText != null) {
                    val outcome = bookRepository.processCapture(
                        book,
                        capture,
                        appSettings.geminiApiKey.orEmpty(),
                        manualPageNumber = pageNumber,
                        precomputedOcrText = ocrText,
                        providerConfig = appSettings.providerConfig,
                        onApiCall = { providerId -> recordApiCall(providerId) },
                    )
                    (outcome as ProcessOutcome.Done).book
                } else {
                    bookRepository.assignPageNumber(book, capture, pageNumber)
                }
                activeBook = updated
                syncToDropbox(updated)
            } catch (e: DuplicatePageNumberException) {
                ocrErrorMessage = e.message
            }
        }
    }

    private fun changePageNumber(page: Page, newNumber: Int) {
        val book = activeBook ?: return
        lifecycleScope.launch {
            try {
                val updated = bookRepository.changePageNumber(book, page, newNumber)
                activePageIndex = updated.pages
                    .indexOfFirst { it.page == newNumber && it.addedAt == page.addedAt }
                    .coerceAtLeast(0)
                activeBook = updated
                syncToDropbox(updated)
            } catch (e: DuplicatePageNumberException) {
                ocrErrorMessage = e.message
            }
        }
    }

    private fun recordApiCall(providerId: String? = null) {
        settingsStore.recordApiCall(providerId)
        lifecycleScope.launch {
            appSettings = settingsStore.read()
        }
    }

    private fun isMonthlyApiBudgetExceeded(): Boolean {
        val budget = appSettings.monthlyApiBudget
        return budget > 0 && appSettings.apiUsage.monthCalls >= budget
    }

    private fun showMonthlyApiBudgetError() {
        val budget = appSettings.monthlyApiBudget
        ocrErrorMessage = "本月 API 调用已达上限 ($budget 次)，暂停自动 OCR"
    }

    private fun upsertProcessItem(
        list: MutableList<ProcessItem>,
        id: String,
        step: ProcessStep,
        message: String? = null,
        providerName: String? = null,
    ) {
        val item = ProcessItem(
            id = id,
            step = step,
            message = message,
            providerName = providerName,
        )
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = item else list.add(item)
    }

    private fun replaceProcessItem(
        list: MutableList<ProcessItem>,
        oldId: String,
        newId: String,
        step: ProcessStep,
        message: String? = null,
    ) {
        val oldIndex = list.indexOfFirst { it.id == oldId }
        if (oldIndex >= 0) {
            list.removeAt(oldIndex)
            val item = ProcessItem(
                id = newId,
                step = step,
                message = message,
            )
            if (oldIndex <= list.size) list.add(oldIndex, item) else list.add(item)
        } else {
            upsertProcessItem(list, newId, step, message)
        }
    }

    private fun removeProcessItem(list: MutableList<ProcessItem>, id: String) {
        list.removeAll { it.id == id }
    }

    private fun retryProcessItem(item: ProcessItem) {
        val book = activeBook ?: return
        when {
            item.id.startsWith("page-") -> {
                val pageNumber = item.id.removePrefix("page-").toIntOrNull() ?: return
                val page = book.pages.firstOrNull { it.page == pageNumber } ?: return
                ocrPage(page)
            }
            else -> {
                val capture = book.captures.firstOrNull { it.id == item.id } ?: return
                ocrCapture(capture, jumpToPage = currentScreen == ShellScreen.Workbench)
            }
        }
    }

    private fun dismissProcessItem(item: ProcessItem) {
        removeProcessItem(processQueue, item.id)
    }

    private fun deleteBooks(uids: List<String>) {
        for (uid in uids) {
            bookRepository.deleteBook(uid)
        }
        if (activeBook?.uid in uids) {
            activeBook = null
            currentScreen = ShellScreen.BookShelf
        }
    }

    private fun deletePage(page: Page) {
        val book = activeBook ?: return
        lifecycleScope.launch {
            val updated = bookRepository.deletePage(book, page)
            activeBook = updated
            activePageIndex = (activePageIndex).coerceIn(0, (updated.pages.size - 1).coerceAtLeast(0))
            if (updated.pages.isEmpty()) currentScreen = ShellScreen.PageList
            syncToDropbox(updated)
        }
    }

    private fun deleteCapture(capture: Capture) {
        val book = activeBook ?: return
        lifecycleScope.launch {
            activeBook = bookRepository.deleteCapture(book, capture)
        }
    }

    private fun deletePages(pages: List<Page>) {
        val book = activeBook ?: return
        lifecycleScope.launch {
            var current = book
            for (page in pages) {
                current = bookRepository.deletePage(current, page)
            }
            activeBook = current
            activePageIndex = 0
            syncToDropbox(current)
        }
    }

    private fun deleteCaptures(captures: List<Capture>) {
        val book = activeBook ?: return
        lifecycleScope.launch {
            var current = book
            for (capture in captures) {
                current = bookRepository.deleteCapture(current, capture)
            }
            activeBook = current
        }
    }

    private fun ocrPage(page: Page) {
        val book = activeBook ?: return
        val key = appSettings.geminiApiKey.orEmpty()
        val statusKey = "page-${page.page}"
        if (isMonthlyApiBudgetExceeded()) {
            showMonthlyApiBudgetError()
            return
        }
        if (!appSettings.hasAnyProvider) {
            ocrStatus[statusKey] = OcrJobState.Failed
            upsertProcessItem(processQueue, statusKey, ProcessStep.Failed, message = "未配置 OCR 服务（去设置添加）")
            return
        }
        startNewBatchIfIdle()
        lifecycleScope.launch {
            ocrStatus[statusKey] = OcrJobState.Running
            upsertProcessItem(processQueue, statusKey, ProcessStep.Ocr)
            try {
                val updated = bookRepository.ocrExistingPage(
                    book,
                    page,
                    key,
                    providerConfig = appSettings.providerConfig,
                    onApiCall = { providerId -> recordApiCall(providerId) },
                )
                ocrStatus.remove(statusKey)
                activeBook = updated
                upsertProcessItem(processQueue, statusKey, ProcessStep.Done)
                syncToDropbox(updated)
            } catch (t: Throwable) {
                ocrStatus[statusKey] = OcrJobState.Failed
                ocrErrorMessage = "OCR 失败: ${t.message?.take(80) ?: "未知错误"}"
                upsertProcessItem(processQueue, statusKey, ProcessStep.Failed, message = t.message?.take(80) ?: "未知错误")
                // Enqueue for background retry
                OcrRetryWorker.enqueuePageOcr(this@MainActivity, book.uid, page.page)
            }
        }
    }

    private suspend fun syncToDropbox(book: Book) {
        val credential = appSettings.dropboxCredentialJson ?: return
        runCatching { bookRepository.persist(book, credential) }
        refreshSyncStatus()
    }

    private fun refreshSyncStatus() {
        val store = com.readingnotes.app.dropbox.SyncQueueStore(applicationContext)
        syncPendingCount = store.peekAll().size
    }

    private fun exportFullBackup() {
        lifecycleScope.launch {
            backupStatus = "正在生成备份…"
            try {
                val zipFile = bookRepository.exportFullBackup(settingsStore)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.fileprovider",
                    zipFile,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                backupStatus = "备份已生成"
                startActivity(android.content.Intent.createChooser(intent, "备份全部数据"))
            } catch (e: Exception) {
                backupStatus = "备份失败: ${e.message?.take(60)}"
            }
        }
    }

    private fun importFullBackup() {
        importBackupLauncher.launch("application/zip")
    }

    private fun restoreFromDropbox() {
        val credential = appSettings.dropboxCredentialJson ?: return
        restoreStatus = "正在扫描 Dropbox…"
        lifecycleScope.launch {
            val count = bookRepository.restoreFromDropbox(credential) { msg ->
                restoreStatus = msg
            }
            restoreStatus = if (count > 0) "已恢复 $count 本书" else "没有需要恢复的书籍"
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
        refreshSyncStatus()
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

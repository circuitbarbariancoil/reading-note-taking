package com.readingnotes.app

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dropbox.core.DbxRequestConfig
import com.readingnotes.app.dropbox.DropboxConfig
import com.readingnotes.app.dropbox.DropboxSyncWorker
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.Capture
import com.readingnotes.app.model.CodePoints
import com.readingnotes.app.model.Entry
import com.readingnotes.app.model.EntryKind
import com.readingnotes.app.model.HighlightPalette
import com.readingnotes.app.model.MarkupText
import com.readingnotes.app.model.Page
import com.readingnotes.app.ocr.LlmProvider
import com.readingnotes.app.ocr.OcrRetryWorker
import com.readingnotes.app.ocr.ProviderConfig
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.repository.DuplicatePageNumberException
import com.readingnotes.app.repository.ProcessOutcome
import com.readingnotes.app.settings.AppSettings
import com.readingnotes.app.settings.SettingsStore
import com.readingnotes.app.ui.OcrJobState
import com.readingnotes.app.ui.ProcessItem
import com.readingnotes.app.ui.ProcessStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class ShellScreen {
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
data class PendingPageNumber(val capture: Capture, val ocrText: String)

data class EntryEditTarget(val bookUid: String, val entryId: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = SettingsStore(app)
    val bookRepository = BookRepository(app)

    var appSettings by mutableStateOf(AppSettings())
    var books by mutableStateOf<List<Book>>(emptyList())
    var currentScreen by mutableStateOf(ShellScreen.BookShelf)
    var activeBook by mutableStateOf<Book?>(null)
    var activePageIndex by mutableStateOf(0)
    var queueCollapsed by mutableStateOf(false)
    var pendingPageNumber by mutableStateOf<PendingPageNumber?>(null)
    var entryEditTarget by mutableStateOf<EntryEditTarget?>(null)
    var entryEditorFromBrowser by mutableStateOf(false)
    var workbenchFocus by mutableStateOf<IntRange?>(null)
    var ocrErrorMessage by mutableStateOf<String?>(null)
    var entryBrowserBookUid by mutableStateOf<String?>(null)
    var captureFromWorkbench = false
    var captureShotCount by mutableStateOf(0)
    var captureSaving by mutableStateOf(false)
    var syncPendingCount by mutableStateOf(0)
    var lastSyncTime by mutableStateOf<String?>(null)
    var restoreStatus by mutableStateOf<String?>(null)
    var backupStatus by mutableStateOf<String?>(null)
    val ocrStatus = mutableStateMapOf<String, OcrJobState>()
    val processQueue = mutableStateListOf<ProcessItem>()
    var notebookBook by mutableStateOf<Book?>(null)
    var notebookDraft by mutableStateOf<Entry?>(null)

    init {
        DropboxSyncWorker.schedulePeriodic(app)
        reloadSettings()
        refreshBooks()
        refreshSyncStatus()
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app) as T
        }
    }

    fun refreshBooks() {
        books = bookRepository.listBooks()
    }

    fun onEnterBookShelf() {
        refreshBooks()
    }

    fun reloadSettings() {
        appSettings = settingsStore.read()
    }

    fun saveSettings(maxRetries: Int, monthlyBudget: Int) {
        settingsStore.saveMaxOcrRetries(maxRetries)
        settingsStore.saveMonthlyApiBudget(monthlyBudget)
        reloadSettings()
    }

    fun saveProviderConfig(config: ProviderConfig) {
        settingsStore.saveProviderConfig(config)
        reloadSettings()
    }

    fun savePalette(palette: HighlightPalette) {
        settingsStore.savePalette(palette)
        reloadSettings()
    }

    fun clearDropboxCredential() {
        settingsStore.clearDropboxCredential()
        reloadSettings()
    }

    fun saveDropboxCredential(credentialString: String) {
        settingsStore.saveDropboxCredentialJson(credentialString)
        reloadSettings()
    }

    fun saveEditedEntry(updated: Entry) {
        val book = activeBook ?: return
        val fromBrowser = entryEditorFromBrowser
        viewModelScope.launch {
            val updatedBook = book.copy(
                entries = book.entries.map { if (it.id == updated.id) updated else it },
            )
            activeBook = bookRepository.persist(updatedBook, appSettings.dropboxCredentialJson)
            entryEditTarget = null
            entryEditorFromBrowser = false
            currentScreen = if (fromBrowser) ShellScreen.EntryBrowser else ShellScreen.Workbench
            refreshBooks()
        }
    }

    fun dismissEntryEditor() {
        val fromBrowser = entryEditorFromBrowser
        entryEditTarget = null
        entryEditorFromBrowser = false
        currentScreen = if (fromBrowser) ShellScreen.EntryBrowser else ShellScreen.Workbench
    }

    fun openCapture(fromWorkbench: Boolean) {
        captureFromWorkbench = fromWorkbench
        captureShotCount = 0
        currentScreen = ShellScreen.Capture
    }

    fun saveShot(bytes: ByteArray) {
        val book = activeBook ?: return
        val tempId = "shot-${captureShotCount + 1}-${System.currentTimeMillis()}"
        startNewBatchIfIdle()
        upsertProcessItem(processQueue, tempId, ProcessStep.Saving)
        captureSaving = true
        viewModelScope.launch {
            try {
                val updated = bookRepository.saveCapture(book, bytes)
                val newCapture = updated.captures.last()
                activeBook = updated
                captureShotCount++
                replaceProcessItem(processQueue, tempId, newCapture.id, ProcessStep.Saving)
                refreshBooks()
                if (captureFromWorkbench) {
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

    fun ocrCapture(capture: Capture, jumpToPage: Boolean) {
        if (isMonthlyApiBudgetExceeded()) {
            showMonthlyApiBudgetError()
            return
        }
        startNewBatchIfIdle()
        viewModelScope.launch { runOcrCapture(capture, jumpToPage) }
    }

    private fun startNewBatchIfIdle() {
        val active = processQueue.any {
            it.step == ProcessStep.Queued || it.step == ProcessStep.Saving || it.step == ProcessStep.Ocr
        }
        if (!active) processQueue.clear()
    }

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
        upsertProcessItem(processQueue, capture.id, ProcessStep.Ocr, providerName = assignedProvider?.name)
        val effectiveConfig = if (assignedProvider != null) {
            ProviderConfig(providers = listOf(assignedProvider), activeIndex = 0, fallbackOnError = false)
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
                    refreshBooks()
                    if (jumpToPage) {
                        activePageIndex = outcome.book.pages.indexOfFirst {
                            it.page == outcome.page.page && it.addedAt == outcome.page.addedAt
                        }.coerceAtLeast(0)
                    }
                    syncToDropbox(outcome.book)
                }

                is ProcessOutcome.NeedsPageNumber -> {
                    ocrStatus.remove(capture.id)
                    upsertProcessItem(processQueue, capture.id, ProcessStep.Done)
                    activeBook = bookRepository.storeCaptureOcrText(book, capture, outcome.ocrText)
                    refreshBooks()
                }
            }
        } catch (t: Throwable) {
            ocrStatus[capture.id] = OcrJobState.Failed
            ocrErrorMessage = "OCR 失败: ${t.message?.take(80) ?: "未知错误"}"
            upsertProcessItem(processQueue, capture.id, ProcessStep.Failed, message = t.message?.take(80) ?: "未知错误")
            OcrRetryWorker.enqueueCaptureOcr(getApplication(), book.uid, capture.id)
        }
    }

    fun finishWithManualPageNumber(pending: PendingPageNumber, pageNumber: Int) {
        val book = activeBook ?: return
        val key = appSettings.geminiApiKey.orEmpty()
        viewModelScope.launch {
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
                    refreshBooks()
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

    fun batchOcr() {
        queueCollapsed = false
        if (isMonthlyApiBudgetExceeded()) {
            showMonthlyApiBudgetError()
            return
        }
        startNewBatchIfIdle()
        val config = appSettings.providerConfig
        val usable = config.usableProviders
        val parallel = config.parallelOcr && usable.size > 1

        viewModelScope.launch {
            val pendingCaptures = activeBook?.captures?.filter { it.ocrText == null }.orEmpty()
            pendingCaptures.forEach { capture -> upsertProcessItem(processQueue, capture.id, ProcessStep.Queued) }

            if (parallel) {
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
                activeBook?.uid?.let { uid ->
                    activeBook = bookRepository.loadBook(uid) ?: activeBook
                }
            } else {
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

    fun assignPage(capture: Capture, pageNumber: Int) {
        val book = activeBook ?: return
        viewModelScope.launch {
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
                refreshBooks()
                syncToDropbox(updated)
            } catch (e: DuplicatePageNumberException) {
                ocrErrorMessage = e.message
            }
        }
    }

    fun changePageNumber(page: Page, newNumber: Int) {
        val book = activeBook ?: return
        viewModelScope.launch {
            try {
                val updated = bookRepository.changePageNumber(book, page, newNumber)
                activePageIndex = updated.pages.indexOfFirst { it.page == newNumber && it.addedAt == page.addedAt }.coerceAtLeast(0)
                activeBook = updated
                refreshBooks()
                syncToDropbox(updated)
            } catch (e: DuplicatePageNumberException) {
                ocrErrorMessage = e.message
            }
        }
    }

    private fun recordApiCall(providerId: String? = null) {
        settingsStore.recordApiCall(providerId)
        reloadSettings()
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
        val item = ProcessItem(id = id, step = step, message = message, providerName = providerName)
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
            val item = ProcessItem(id = newId, step = step, message = message)
            if (oldIndex <= list.size) list.add(oldIndex, item) else list.add(item)
        } else {
            upsertProcessItem(list, newId, step, message)
        }
    }

    private fun removeProcessItem(list: MutableList<ProcessItem>, id: String) {
        list.removeAll { it.id == id }
    }

    fun retryProcessItem(item: ProcessItem) {
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

    fun dismissProcessItem(item: ProcessItem) {
        removeProcessItem(processQueue, item.id)
    }

    fun deleteBooks(uids: List<String>) {
        val toDelete = uids.filter { it != com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID }
        for (uid in toDelete) {
            bookRepository.deleteBook(uid)
        }
        if (activeBook?.uid in toDelete) {
            activeBook = null
            currentScreen = ShellScreen.BookShelf
        }
        refreshBooks()
    }

    fun deletePage(page: Page) {
        val book = activeBook ?: return
        viewModelScope.launch {
            val updated = bookRepository.deletePage(book, page)
            activeBook = updated
            activePageIndex = activePageIndex.coerceIn(0, (updated.pages.size - 1).coerceAtLeast(0))
            if (updated.pages.isEmpty()) currentScreen = ShellScreen.PageList
            refreshBooks()
            syncToDropbox(updated)
        }
    }

    fun deleteCapture(capture: Capture) {
        val book = activeBook ?: return
        viewModelScope.launch {
            activeBook = bookRepository.deleteCapture(book, capture)
            refreshBooks()
        }
    }

    fun deletePages(pages: List<Page>) {
        val book = activeBook ?: return
        viewModelScope.launch {
            var current = book
            for (page in pages) {
                current = bookRepository.deletePage(current, page)
            }
            activeBook = current
            activePageIndex = 0
            refreshBooks()
            syncToDropbox(current)
        }
    }

    fun deleteCaptures(captures: List<Capture>) {
        val book = activeBook ?: return
        viewModelScope.launch {
            var current = book
            for (capture in captures) {
                current = bookRepository.deleteCapture(current, capture)
            }
            activeBook = current
            refreshBooks()
        }
    }

    fun ocrPage(page: Page) {
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
        viewModelScope.launch {
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
                refreshBooks()
                upsertProcessItem(processQueue, statusKey, ProcessStep.Done)
                syncToDropbox(updated)
            } catch (t: Throwable) {
                ocrStatus[statusKey] = OcrJobState.Failed
                ocrErrorMessage = "OCR 失败: ${t.message?.take(80) ?: "未知错误"}"
                upsertProcessItem(processQueue, statusKey, ProcessStep.Failed, message = t.message?.take(80) ?: "未知错误")
                OcrRetryWorker.enqueuePageOcr(getApplication(), book.uid, page.page)
            }
        }
    }

    private suspend fun syncToDropbox(book: Book) {
        val credential = appSettings.dropboxCredentialJson ?: return
        runCatching { bookRepository.persist(book, credential) }
        refreshSyncStatus()
    }

    fun refreshSyncStatus() {
        val store = com.readingnotes.app.dropbox.SyncQueueStore(getApplication())
        syncPendingCount = store.peekAll().size
    }

    fun restoreFromDropbox() {
        val credential = appSettings.dropboxCredentialJson ?: return
        restoreStatus = "正在扫描 Dropbox…"
        viewModelScope.launch {
            val count = bookRepository.restoreFromDropbox(credential) { msg ->
                restoreStatus = msg
            }
            restoreStatus = if (count > 0) "已恢复 $count 本书" else "没有需要恢复的书籍"
            refreshBooks()
        }
    }

    fun exportFullBackup(onReady: (File) -> Unit) {
        viewModelScope.launch {
            backupStatus = "正在生成备份…"
            try {
                val zipFile = bookRepository.exportFullBackup(settingsStore)
                backupStatus = "备份已生成"
                onReady(zipFile)
            } catch (e: Exception) {
                backupStatus = "备份失败: ${e.message?.take(60)}"
            }
        }
    }

    fun importBackup(inputStream: InputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            backupStatus = "正在恢复…"
            try {
                val count = bookRepository.importFullBackup(inputStream, settingsStore) { msg ->
                    backupStatus = msg
                }
                reloadSettings()
                refreshBooks()
                backupStatus = if (count > 0) "已恢复 $count 本书 + 设置" else "没有需要恢复的内容"
            } catch (e: Exception) {
                backupStatus = "恢复失败: ${e.message?.take(60)}"
            }
        }
    }

    // ── Notebook ─────────────────────────────────────────────────────────

    fun openNotebook() {
        notebookBook = bookRepository.getOrCreateNotebook()
        entryBrowserBookUid = com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
        currentScreen = ShellScreen.EntryBrowser
    }

    fun refreshNotebook() {
        notebookBook = bookRepository.loadBook(com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID)
            ?: bookRepository.getOrCreateNotebook()
    }

    fun addNoteToNotebook(text: String) {
        viewModelScope.launch {
            val updated = bookRepository.addNoteToNotebook(text)
            notebookBook = updated
            refreshBooks()
            syncToDropbox(updated)
        }
    }

    /** Handle shared text: save silently or open the editor with a draft. */
    fun handleShareText(text: String, openEditor: Boolean) {
        if (openEditor) {
            openNotebookDraft(text)
        } else {
            addNoteToNotebook(text)
        }
    }

    /** Open the entry editor with an unsaved draft; the note is created on save. */
    fun openNotebookDraft(text: String = "") {
        notebookBook = bookRepository.getOrCreateNotebook()
        val now = java.time.Instant.now().toString()
        notebookDraft = Entry(
            id = java.util.UUID.randomUUID().toString().take(8),
            page = null,
            text = text,
            kind = EntryKind.note,
            createdAt = now,
            updatedAt = now,
        )
        currentScreen = ShellScreen.EntryEditor
    }

    fun saveNewNoteEntry(entry: Entry) {
        val notebook = notebookBook ?: bookRepository.getOrCreateNotebook()
        viewModelScope.launch {
            val updated = notebook.copy(
                updatedAt = java.time.Instant.now().toString(),
                entries = notebook.entries + entry,
            )
            val persisted = bookRepository.persist(updated, appSettings.dropboxCredentialJson)
            notebookBook = persisted
            notebookDraft = null
            entryEditTarget = null
            entryBrowserBookUid = com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
            currentScreen = ShellScreen.EntryBrowser
            refreshBooks()
        }
    }

    fun saveEditedNoteEntry(updated: Entry) {
        val notebook = notebookBook ?: return
        viewModelScope.launch {
            val updatedBook = notebook.copy(
                entries = notebook.entries.map { if (it.id == updated.id) updated else it },
            )
            notebookBook = bookRepository.persist(updatedBook, appSettings.dropboxCredentialJson)
            entryEditTarget = null
            entryBrowserBookUid = com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
            currentScreen = ShellScreen.EntryBrowser
            refreshBooks()
        }
    }

    /** Delete entries from any book(s). Grouped by bookUid for efficiency. */
    fun deleteEntries(items: List<com.readingnotes.app.ui.BrowsableEntry>) {
        viewModelScope.launch {
            items.groupBy { it.bookUid }.forEach { (uid, group) ->
                val ids = group.map { it.entry.id }.toSet()
                val book = bookRepository.loadBook(uid) ?: return@forEach
                val updated = book.copy(
                    entries = book.entries.filterNot { it.id in ids },
                    updatedAt = java.time.Instant.now().toString(),
                )
                bookRepository.persist(updated, appSettings.dropboxCredentialJson)
                if (uid == com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID) {
                    notebookBook = updated
                }
                if (uid == activeBook?.uid) {
                    activeBook = updated
                }
            }
            refreshBooks()
        }
    }

    fun locateEntrySource(ocrText: String?, entry: Entry): IntRange? {
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
}

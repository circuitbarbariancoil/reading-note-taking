package com.readingnotes.app

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import com.readingnotes.app.model.Section
import com.readingnotes.app.ocr.LlmProvider
import com.readingnotes.app.ocr.OcrDispatcher
import com.readingnotes.app.ocr.OcrRetryWorker
import com.readingnotes.app.ocr.ProviderConfig
import com.readingnotes.app.ocr.TocItem
import com.readingnotes.app.image.ImageProcessing
import com.readingnotes.app.pdf.PdfSource
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
    PdfImport,
    Toc,
    TocEditor,
    TocCapture,
}

/** A capture whose OCR finished but produced no page number: ask the user. */
data class PendingPageNumber(val capture: Capture, val ocrText: String)

data class EntryEditTarget(val bookUid: String, val entryId: String)

private data class OcrFailureKey(val bookUid: String, val pageNumber: Int)

/** A unit of pending OCR work for the unified batch OCR queue. */
private sealed interface OcrWork {
    data class Pg(val page: Page) : OcrWork
    data class Cap(val capture: Capture) : OcrWork
}

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
    /** True when [workbenchFocus] targets a plain excerpt (gray-underline jump, no block). */
    var workbenchFocusIsExcerpt by mutableStateOf(false)
    var ocrErrorMessage by mutableStateOf<String?>(null)
    var entryBrowserBookUid by mutableStateOf<String?>(null)
    var captureFromWorkbench = false
    var pdfImportUri by mutableStateOf<Uri?>(null)
    var captureShotCount by mutableStateOf(0)
    var captureSaving by mutableStateOf(false)
    var syncPendingCount by mutableStateOf(0)
    var lastSyncTime by mutableStateOf<String?>(null)
    var restoreStatus by mutableStateOf<String?>(null)
    var backupStatus by mutableStateOf<String?>(null)
    val ocrStatus = mutableStateMapOf<String, OcrJobState>()
    private val ocrFailures = mutableStateMapOf<OcrFailureKey, String>()
    val processQueue = mutableStateListOf<ProcessItem>()
    var notebookBook by mutableStateOf<Book?>(null)
    var notebookDraft by mutableStateOf<Entry?>(null)
    var entryBrowserOrigin by mutableStateOf(ShellScreen.BookShelf)
    val entryBrowserListState = androidx.compose.foundation.lazy.LazyListState()

    /** Seed sections loaded into the unified TOC outline editor. */
    var tocEditorSeed by mutableStateOf<List<Section>>(emptyList())
    /** When true, the editor offers 替换/追加 (AI result on top of an existing TOC). */
    var tocEditorAllowAppend by mutableStateOf(false)
    /** True while a 目录 extraction API call is in flight. */
    var tocGenerating by mutableStateOf(false)
    var tocError by mutableStateOf<String?>(null)
    /** Transient buffer of 目录-page photos, held only until extraction, never stored. */
    val tocCaptureBuffer = mutableStateListOf<ByteArray>()
    /** True when the active PDF picker/import flow targets 目录 extraction. */
    var pdfImportForToc by mutableStateOf(false)

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
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = bookRepository.listBooks()
            val loadedByUid = loaded.associateBy { it.uid }
            val currentByUid = books.associateBy { it.uid }
            val pagesByKey = loaded.associate { book ->
                book.uid to book.pages.associateBy { page -> page.page }
            }
            val activeUid = activeBook?.uid
            val notebookUid = notebookBook?.uid
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                books = loaded.map { loadedBook ->
                    currentByUid[loadedBook.uid]?.takeIf { it == loadedBook } ?: loadedBook
                }
                activeUid?.let { uid ->
                    when (val loadedActive = loadedByUid[uid]) {
                        null -> if (activeBook != null) activeBook = null
                        else -> if (activeBook != loadedActive) {
                            activeBook = currentByUid[uid]?.takeIf { it == loadedActive } ?: loadedActive
                        }
                    }
                }
                if (notebookUid == BookRepository.NOTEBOOK_UID) {
                    when (val loadedNotebook = loadedByUid[BookRepository.NOTEBOOK_UID]) {
                        null -> if (notebookBook != null) notebookBook = null
                        else -> if (notebookBook != loadedNotebook) {
                            notebookBook = currentByUid[BookRepository.NOTEBOOK_UID]?.takeIf { it == loadedNotebook } ?: loadedNotebook
                        }
                    }
                }
                ocrFailures.entries.removeAll { (key, _) ->
                    val page = pagesByKey[key.bookUid]?.get(key.pageNumber)
                    page == null || !page.ocrText.isNullOrBlank()
                }
            }
        }
    }

    fun onEnterBookShelf() {
        refreshBooks()
    }

    fun reloadSettings() {
        appSettings = settingsStore.read()
    }

    fun ocrFailureFor(bookUid: String, pageNumber: Int): String? =
        ocrFailures[OcrFailureKey(bookUid, pageNumber)]

    fun clearOcrFailure(bookUid: String, pageNumber: Int) {
        ocrFailures.remove(OcrFailureKey(bookUid, pageNumber))
    }

    private fun setOcrFailure(bookUid: String, pageNumber: Int, message: String) {
        ocrFailures[OcrFailureKey(bookUid, pageNumber)] = message
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

    private fun patchBooks(updatedBooks: Collection<Book>) {
        if (updatedBooks.isEmpty()) return
        val updatedByUid = updatedBooks.associateBy { it.uid }
        val existing = books
        val replaced = existing.map { updatedByUid[it.uid] ?: it }
        val missing = updatedBooks.filter { updated -> existing.none { it.uid == updated.uid } }
        books = if (missing.isEmpty()) replaced else replaced + missing
    }

    fun saveEditedEntry(updated: Entry) {
        val book = activeBook ?: return
        val fromBrowser = entryEditorFromBrowser
        val updatedBook = book.copy(
            entries = book.entries.map { if (it.id == updated.id) updated else it },
        )
        activeBook = updatedBook
        entryEditTarget = null
        entryEditorFromBrowser = false
        currentScreen = if (fromBrowser) ShellScreen.EntryBrowser else ShellScreen.Workbench
        patchBooks(listOf(updatedBook))
        viewModelScope.launch {
            bookRepository.persist(updatedBook, appSettings.dropboxCredentialJson)
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

    fun openPdfImport(uri: Uri) {
        pdfImportForToc = false
        pdfImportUri = uri
        currentScreen = ShellScreen.PdfImport
    }

    fun openPdfImportForToc(uri: Uri) {
        pdfImportForToc = true
        pdfImportUri = uri
        currentScreen = ShellScreen.PdfImport
    }

    fun cancelPdfImport() {
        pdfImportUri = null
        currentScreen = ShellScreen.PageList
    }

    /**
     * Import a contiguous range of PDF pages (1-based, inclusive) as numbered
     * Pages without OCR. Each page is rendered at archive resolution, stored as
     * the page's archive image, and assigned [startPageNumber] + offset. Runs OCR
     * later via [batchOcrAll]. Reuses the processing queue for progress.
     */
    fun importPdf(uri: Uri, fromPage: Int, toPage: Int, startPageNumber: Int) {
        val book = activeBook ?: return
        val lo = minOf(fromPage, toPage)
        val hi = maxOf(fromPage, toPage)
        pdfImportUri = null
        currentScreen = ShellScreen.PageList
        queueCollapsed = false
        startNewBatchIfIdle()
        viewModelScope.launch {
            val source = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { PdfSource.open(getApplication(), uri) }
            } catch (t: Throwable) {
                ocrErrorMessage = "无法打开 PDF: ${t.message?.take(60) ?: "未知错误"}"
                return@launch
            }
            try {
                var current = activeBook ?: book
                var pageNumber = startPageNumber
                for (pdfPage in lo..hi) {
                    val itemId = "pdf-$pdfPage"
                    upsertProcessItem(processQueue, itemId, ProcessStep.Saving, message = "PDF 第 $pdfPage 页 → 第 $pageNumber 页")
                    try {
                        val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            source.renderJpeg(pdfPage - 1, ImageProcessing.ARCHIVE_LONG_EDGE)
                        }
                        current = bookRepository.importPageImage(current, bytes, pageNumber)
                        activeBook = current
                        upsertProcessItem(processQueue, itemId, ProcessStep.Done, message = "第 $pageNumber 页")
                        pageNumber++
                    } catch (e: DuplicatePageNumberException) {
                        upsertProcessItem(processQueue, itemId, ProcessStep.Failed, message = "第 $pageNumber 页已存在，跳过")
                        pageNumber++
                    } catch (t: Throwable) {
                        upsertProcessItem(processQueue, itemId, ProcessStep.Failed, message = t.message?.take(60) ?: "导入失败")
                    }
                }
                refreshBooks()
                syncToDropbox(current)
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.IO) { source.close() }
            }
        }
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
        ocrErrorMessage = null
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
                    ocrErrorMessage = null
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
                    ocrErrorMessage = null
                    refreshBooks()
                }
            }
        } catch (t: Throwable) {
            ocrStatus[capture.id] = OcrJobState.Failed
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

    /**
     * Batch-OCR everything not yet recognized: imported/numbered pages that
     * have no OCR text (processed first, since they're one step from done),
     * then captures that still lack a page number. One button, one queue.
     */
    fun batchOcrAll() {
        queueCollapsed = false
        if (isMonthlyApiBudgetExceeded()) {
            showMonthlyApiBudgetError()
            return
        }
        if (!appSettings.hasAnyProvider) {
            ocrErrorMessage = "未配置 OCR 服务（去设置添加）"
            return
        }
        startNewBatchIfIdle()
        val config = appSettings.providerConfig
        val usable = config.usableProviders
        val parallel = config.parallelOcr && usable.size > 1

        viewModelScope.launch {
            val book = activeBook
            val pendingPages = book?.pages
                ?.filter { it.ocrText.isNullOrBlank() && it.archiveImage != null }
                ?.sortedBy { it.page }
                .orEmpty()
            val pendingCaptures = book?.captures?.filter { it.ocrText == null }.orEmpty()
            pendingPages.forEach { page -> upsertProcessItem(processQueue, "page-${page.page}", ProcessStep.Queued) }
            pendingCaptures.forEach { capture -> upsertProcessItem(processQueue, capture.id, ProcessStep.Queued) }
            val work: List<OcrWork> = pendingPages.map { OcrWork.Pg(it) } + pendingCaptures.map { OcrWork.Cap(it) }

            if (parallel) {
                val channel = kotlinx.coroutines.channels.Channel<OcrWork>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                for (item in work) channel.send(item)
                channel.close()
                coroutineScope {
                    val workers = usable.map { provider ->
                        async {
                            for (item in channel) {
                                if (isMonthlyApiBudgetExceeded()) {
                                    showMonthlyApiBudgetError()
                                    break
                                }
                                when (item) {
                                    is OcrWork.Pg -> runOcrPage(item.page, provider)
                                    is OcrWork.Cap -> runOcrCapture(item.capture, jumpToPage = false, assignedProvider = provider)
                                }
                            }
                        }
                    }
                    workers.awaitAll()
                }
                activeBook?.uid?.let { uid ->
                    activeBook = bookRepository.loadBook(uid) ?: activeBook
                }
            } else {
                for (item in work) {
                    if (isMonthlyApiBudgetExceeded()) {
                        showMonthlyApiBudgetError()
                        break
                    }
                    when (item) {
                        is OcrWork.Pg -> runOcrPage(item.page, null)
                        is OcrWork.Cap -> runOcrCapture(item.capture, jumpToPage = false)
                    }
                }
            }
        }
    }

    private suspend fun runOcrPage(page: Page, assignedProvider: LlmProvider?) {
        val book = activeBook ?: return
        val key = appSettings.geminiApiKey.orEmpty()
        val statusKey = "page-${page.page}"
        val effectiveConfig = if (assignedProvider != null) {
            ProviderConfig(providers = listOf(assignedProvider), activeIndex = 0, fallbackOnError = false)
        } else {
            appSettings.providerConfig
        }
        ocrStatus[statusKey] = OcrJobState.Running
        upsertProcessItem(processQueue, statusKey, ProcessStep.Ocr, providerName = assignedProvider?.name)
        try {
            val updated = bookRepository.ocrExistingPage(
                book,
                page,
                key,
                providerConfig = effectiveConfig,
                onApiCall = { providerId -> recordApiCall(providerId) },
            )
            ocrStatus.remove(statusKey)
            activeBook = updated
            ocrErrorMessage = null
            refreshBooks()
            upsertProcessItem(processQueue, statusKey, ProcessStep.Done)
            syncToDropbox(updated)
        } catch (t: Throwable) {
            ocrStatus[statusKey] = OcrJobState.Failed
            setOcrFailure(book.uid, page.page, "OCR 失败: ${t.message?.take(80) ?: "未知错误"}")
            upsertProcessItem(processQueue, statusKey, ProcessStep.Failed, message = t.message?.take(80) ?: "未知错误")
            OcrRetryWorker.enqueuePageOcr(getApplication(), book.uid, page.page)
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

    fun updateBookMeta(book: Book, title: String, author: String) {
        viewModelScope.launch {
            val updated = bookRepository.updateBookMeta(book, title, author)
            if (activeBook?.uid == updated.uid) activeBook = updated
            refreshBooks()
            syncToDropbox(updated)
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

    fun openToc() {
        tocError = null
        currentScreen = ShellScreen.Toc
    }

    fun closeToc() {
        currentScreen = ShellScreen.PageList
    }

    /** Open the unified outline editor seeded with the book's current TOC. */
    fun openTocEditor() {
        tocEditorSeed = activeBook?.sections.orEmpty()
        tocEditorAllowAppend = false
        currentScreen = ShellScreen.TocEditor
    }

    fun cancelTocEditor() {
        tocEditorSeed = emptyList()
        tocEditorAllowAppend = false
        currentScreen = ShellScreen.Toc
    }

    /** Save the edited outline; when [append], keep the existing TOC and add to it. */
    fun saveTocFromEditor(sections: List<Section>, append: Boolean) {
        val book = activeBook ?: return
        val merged = if (append) book.sections + sections else sections
        tocEditorSeed = emptyList()
        tocEditorAllowAppend = false
        currentScreen = ShellScreen.Toc
        saveSections(merged)
    }

    /** Persist the book's table of contents, then sync. */
    fun saveSections(sections: List<Section>) {
        val book = activeBook ?: return
        viewModelScope.launch {
            val updated = bookRepository.updateSections(book, sections)
            activeBook = updated
            patchBooks(listOf(updated))
            syncToDropbox(updated)
        }
    }

    // ---- AI 目录 generation from transient 拍照 / PDF images (never stored) ----

    fun openTocCapture() {
        tocCaptureBuffer.clear()
        tocError = null
        currentScreen = ShellScreen.TocCapture
    }

    fun addTocShot(bytes: ByteArray) {
        tocCaptureBuffer.add(bytes)
    }

    /** Finish 目录 capture: extract from the buffered photos, then discard them. */
    fun finishTocCapture() {
        val images = tocCaptureBuffer.toList()
        tocCaptureBuffer.clear()
        if (images.isEmpty()) {
            currentScreen = ShellScreen.Toc
            return
        }
        currentScreen = ShellScreen.Toc
        generateTocFromImages(images)
    }

    fun cancelTocCapture() {
        tocCaptureBuffer.clear()
        currentScreen = ShellScreen.Toc
    }

    /**
     * Run AI 目录 extraction over in-memory images. On success, seeds the unified
     * editor and opens it; images are the caller's transient bytes (never stored).
     */
    fun generateTocFromImages(images: List<ByteArray>) {
        val book = activeBook ?: return
        if (!appSettings.hasAnyProvider) {
            tocError = "未配置 OCR 服务（去设置添加）"
            return
        }
        if (isMonthlyApiBudgetExceeded()) {
            showMonthlyApiBudgetError()
            tocError = ocrErrorMessage
            return
        }
        if (images.isEmpty()) {
            tocError = "没有目录页图片"
            return
        }
        tocGenerating = true
        tocError = null
        viewModelScope.launch {
            try {
                val downscaled = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    images.mapNotNull { bytes ->
                        runCatching { ImageProcessing.toOcrJpeg(ImageProcessing.decode(bytes)) }.getOrNull()
                    }
                }
                val items = OcrDispatcher(appSettings.providerConfig)
                    .extractToc(downscaled, onApiCall = { providerId -> recordApiCall(providerId) })
                tocGenerating = false
                if (items.isEmpty()) {
                    tocError = "未能从图片中识别出目录，请换清晰的目录页重试"
                } else {
                    tocEditorSeed = items.map { item ->
                        Section(
                            id = java.util.UUID.randomUUID().toString().take(8),
                            title = item.title,
                            startPage = item.page,
                            level = item.level,
                        )
                    }
                    tocEditorAllowAppend = book.sections.isNotEmpty()
                    currentScreen = ShellScreen.TocEditor
                }
            } catch (t: Throwable) {
                tocGenerating = false
                tocError = t.message?.take(120) ?: "目录识别失败"
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
        clearOcrFailure(book.uid, page.page)
        ocrErrorMessage = null
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
                ocrErrorMessage = null
                refreshBooks()
                upsertProcessItem(processQueue, statusKey, ProcessStep.Done)
                syncToDropbox(updated)
            } catch (t: Throwable) {
                ocrStatus[statusKey] = OcrJobState.Failed
                setOcrFailure(book.uid, page.page, "OCR 失败: ${t.message?.take(80) ?: "未知错误"}")
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
        entryBrowserOrigin = ShellScreen.BookShelf
        entryBrowserBookUid = com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
        currentScreen = ShellScreen.EntryBrowser
    }

    fun refreshNotebook() {
        viewModelScope.launch(Dispatchers.IO) {
            val nb = bookRepository.loadBook(com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID)
                ?: bookRepository.getOrCreateNotebook()
            kotlinx.coroutines.withContext(Dispatchers.Main) { notebookBook = nb }
        }
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
        val updated = notebook.copy(
            updatedAt = java.time.Instant.now().toString(),
            entries = notebook.entries + entry,
        )
        notebookBook = updated
        notebookDraft = null
        entryEditTarget = null
        entryBrowserBookUid = com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
        currentScreen = ShellScreen.EntryBrowser
        patchBooks(listOf(updated))
        viewModelScope.launch {
            bookRepository.persist(updated, appSettings.dropboxCredentialJson)
        }
    }

    fun saveEditedNoteEntry(updated: Entry) {
        val notebook = notebookBook ?: return
        val updatedBook = notebook.copy(
            entries = notebook.entries.map { if (it.id == updated.id) updated else it },
        )
        notebookBook = updatedBook
        entryEditTarget = null
        entryBrowserBookUid = com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID
        currentScreen = ShellScreen.EntryBrowser
        patchBooks(listOf(updatedBook))
        viewModelScope.launch {
            bookRepository.persist(updatedBook, appSettings.dropboxCredentialJson)
        }
    }

    /** Delete entries from any book(s). Grouped by bookUid for efficiency. */
    fun deleteEntries(items: List<com.readingnotes.app.ui.BrowsableEntry>) {
        val idsByBook = items.groupBy({ it.bookUid }) { it.entry.id }
        val now = java.time.Instant.now().toString()
        val idSetsByBook = idsByBook.mapValues { (_, ids) -> ids.toSet() }
        val updatedBooks = books.mapNotNull { book ->
            val idSet = idSetsByBook[book.uid] ?: return@mapNotNull null
            book.copy(
                entries = book.entries.filterNot { it.id in idSet },
                updatedAt = now,
            )
        }.toMutableList()
        idsByBook[com.readingnotes.app.repository.BookRepository.NOTEBOOK_UID]?.let { ids ->
            val idSet = ids.toSet()
            notebookBook = notebookBook?.copy(
                entries = notebookBook?.entries.orEmpty().filterNot { it.id in idSet },
                updatedAt = now,
            )?.also { updatedBook ->
                if (updatedBooks.none { it.uid == updatedBook.uid }) updatedBooks.add(updatedBook)
            }
        }
        activeBook?.uid?.let { uid ->
            idsByBook[uid]?.let { ids ->
                val idSet = ids.toSet()
                activeBook = activeBook?.copy(
                    entries = activeBook?.entries.orEmpty().filterNot { it.id in idSet },
                    updatedAt = now,
                )?.also { updatedBook ->
                    if (updatedBooks.none { it.uid == updatedBook.uid }) updatedBooks.add(updatedBook)
                }
            }
        }
        patchBooks(updatedBooks)
        // Persist in background
        viewModelScope.launch {
            idsByBook.forEach { (uid, ids) ->
                val idSet = ids.toSet()
                val book = bookRepository.loadBook(uid) ?: return@forEach
                val updated = book.copy(
                    entries = book.entries.filterNot { it.id in idSet },
                    updatedAt = java.time.Instant.now().toString(),
                )
                bookRepository.persist(updated, appSettings.dropboxCredentialJson)
            }
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

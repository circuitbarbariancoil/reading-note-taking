package com.readingnotes.app.repository

import android.content.Context
import com.readingnotes.app.dropbox.DropboxSyncWorker
import com.readingnotes.app.dropbox.DropboxClient
import com.readingnotes.app.dropbox.SyncQueueStore
import com.readingnotes.app.image.ImageProcessing
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.BookStore
import com.readingnotes.app.model.Page
import com.readingnotes.app.ocr.GeminiOcrClient
import com.readingnotes.app.ocr.OcrDispatcher
import com.readingnotes.app.ocr.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Result of processing a capture into a page. */
sealed class ProcessOutcome {
    data class Done(val book: Book, val page: Page) : ProcessOutcome()

    /** OCR ran but no page number could be extracted; the user must supply one. */
    data class NeedsPageNumber(val ocrText: String) : ProcessOutcome()
}

data class CaptureResult(
    val book: Book,
    val page: Page,
    val localBookJsonPath: String,
    val localArchivePath: String,
    val dropboxBookJsonPath: String,
    val dropboxArchivePath: String,
)

class BookRepository(
    private val context: Context,
) {
    private val mutex = Mutex()
    private val syncQueueStore = SyncQueueStore(context)
    private var cachedBook: Book? = null

    suspend fun captureAndSync(
        sourceBytes: ByteArray,
        geminiApiKey: String,
        dropboxCredentialJson: String,
        bookTitle: String,
        onApiCall: () -> Unit = {},
    ): CaptureResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sourceBitmap = ImageProcessing.decode(sourceBytes)
            val ocrJpeg = ImageProcessing.toOcrJpeg(sourceBitmap)
            val archiveWebp = ImageProcessing.toArchiveWebp(sourceBitmap)

            val book = loadOrCreateBook(bookTitle)
            val nextPageNumber = book.pages.size + 1
            val archiveRelativePath = "pages/p%04d_archive.webp".format(nextPageNumber)
            val now = utcNow()

            onApiCall()
            val ocrText = GeminiOcrClient(geminiApiKey).ocrPage(ocrJpeg)
            val extractedPageNum = extractPageNumber(ocrText)
            val pageNumber = extractedPageNum ?: nextPageNumber
            val page = Page(
                page = pageNumber,
                archiveImage = archiveRelativePath,
                ocrText = ocrText,
                ocrModel = GeminiOcrClient.DEFAULT_MODEL,
                ocrCapturedAt = now,
                addedAt = now,
            )
            val updatedBook = book.copy(
                updatedAt = now,
                pages = book.pages + page,
            )
            saveBook(updatedBook)
            saveArchiveImage(updatedBook.uid, archiveRelativePath, archiveWebp)

            val dropboxClient = DropboxClient.fromCredentialJson(dropboxCredentialJson)
            val dropboxBookJsonPath = "${updatedBook.dropboxRoot}/book.json"
            val dropboxArchivePath = "${updatedBook.dropboxRoot}/$archiveRelativePath"
            dropboxClient.uploadFile(dropboxArchivePath, archiveWebp)
            dropboxClient.uploadFile(
                dropboxBookJsonPath,
                BookStore.encode(updatedBook).toByteArray(Charsets.UTF_8),
            )

            cachedBook = updatedBook
            CaptureResult(
                book = updatedBook,
                page = page,
                localBookJsonPath = bookJsonFile(updatedBook.uid).absolutePath,
                localArchivePath = archiveFile(updatedBook.uid, archiveRelativePath).absolutePath,
                dropboxBookJsonPath = dropboxBookJsonPath,
                dropboxArchivePath = dropboxArchivePath,
            )
        }
    }

    fun loadCurrentBook(): Book? {
        cachedBook?.let { return it }
        val existing = findExistingBookJson() ?: return null
        return BookStore.decode(existing.readText())
            .also { cachedBook = it }
    }

    /** List all books on disk. */
    fun listBooks(): List<Book> {
        val booksRoot = File(context.filesDir, "books")
        val children = booksRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        return children.mapNotNull { dir ->
            val jsonFile = File(dir, "book.json")
            if (jsonFile.exists()) runCatching { BookStore.decode(jsonFile.readText()) }.getOrNull() else null
        }
    }

    /** Load a specific book by uid, recovering from backup if corrupted. */
    fun loadBook(uid: String): Book? {
        val jsonFile = bookJsonFile(uid)
        if (!jsonFile.exists()) return recoverFromBackup(uid)
        return runCatching { BookStore.decode(jsonFile.readText()) }.getOrElse {
            // Main file corrupted; attempt backup recovery
            recoverFromBackup(uid)
        }
    }

    /** Delete a book and all its data from disk. */
    fun deleteBook(uid: String) {
        val book = loadBook(uid)
        val dropboxRoot = book?.dropboxRoot ?: "/ReadingVault/books/$uid"
        syncQueueStore.enqueueDelete(dropboxRoot)
        DropboxSyncWorker.trigger(context)
        bookDir(uid).deleteRecursively()
        if (cachedBook?.uid == uid) cachedBook = null
    }

    /** Create a new empty book. */
    fun createBook(title: String, author: String = ""): Book {
        val uid = UUID.randomUUID().toString()
        val now = utcNow()
        val book = Book(
            uid = uid,
            title = title.ifBlank { "未命名" },
            author = author,
            createdAt = now,
            updatedAt = now,
            dropboxRoot = "/ReadingVault/books/$uid",
        )
        saveBook(book)
        return book
    }

    /**
     * Save a photo as a Capture (unprocessed) without running OCR.
     * Returns the updated book with the new capture appended.
     */
    suspend fun saveCapture(
        book: Book,
        sourceBytes: ByteArray,
    ): Book = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = utcNow()
            val captureId = UUID.randomUUID().toString()
            val relativePath = "captures/${captureId}.webp"
            val archiveWebp = ImageProcessing.toArchiveWebp(ImageProcessing.decode(sourceBytes))
            saveArchiveImage(book.uid, relativePath, archiveWebp)

            val capture = com.readingnotes.app.model.Capture(
                id = captureId,
                imagePath = archiveFile(book.uid, relativePath).absolutePath,
                capturedAt = now,
            )
            val updated = book.copy(
                updatedAt = now,
                captures = book.captures + capture,
            )
            saveBook(updated)
            cachedBook = updated
            updated
        }
    }

    /**
     * Process a capture: run OCR (unless [precomputedOcrText] is supplied) and
     * convert it to a Page. Page number priority: manual > OCR-extracted. When
     * neither is available the book is left untouched and
     * [ProcessOutcome.NeedsPageNumber] carries the OCR text so the caller can
     * ask the user and retry without paying for OCR again.
     */
    suspend fun processCapture(
        book: Book,
        capture: com.readingnotes.app.model.Capture,
        geminiApiKey: String,
        manualPageNumber: Int? = null,
        precomputedOcrText: String? = null,
        providerConfig: ProviderConfig? = null,
        onApiCall: () -> Unit = {},
    ): ProcessOutcome = withContext(Dispatchers.IO) {
        val ocrText = precomputedOcrText ?: run {
            val ocrJpeg = ImageProcessing.toOcrJpeg(ImageProcessing.decode(File(capture.imagePath).readBytes()))
            dispatchOcr(ocrJpeg, geminiApiKey, providerConfig, onApiCall)
        }
        mutex.withLock {
            val base = loadBook(book.uid) ?: book
            val now = utcNow()
            val pageNumber = manualPageNumber ?: extractPageNumber(ocrText)
                ?: return@withLock ProcessOutcome.NeedsPageNumber(ocrText)

            val page = buildPageFromCapture(base, capture, pageNumber, now, ocrText)
            val updated = base.copy(
                updatedAt = now,
                pages = (base.pages + page).sortedBy { it.page },
                captures = base.captures.filterNot { it.id == capture.id },
            )
            saveBook(updated)
            enqueuePageArchiveUpload(updated, page)
            cachedBook = updated
            ProcessOutcome.Done(updated, page)
        }
    }

    /**
     * Turn a capture into a page with just a page number, no OCR. The page can
     * be OCR'd later via [ocrExistingPage].
     */
    suspend fun assignPageNumber(
        book: Book,
        capture: com.readingnotes.app.model.Capture,
        pageNumber: Int,
    ): Book = withContext(Dispatchers.IO) {
        mutex.withLock {
            val base = loadBook(book.uid) ?: book
            val now = utcNow()
            val page = buildPageFromCapture(base, capture, pageNumber, now, ocrText = null)
            val updated = base.copy(
                updatedAt = now,
                pages = (base.pages + page).sortedBy { it.page },
                captures = base.captures.filterNot { it.id == capture.id },
            )
            saveBook(updated)
            enqueuePageArchiveUpload(updated, page)
            cachedBook = updated
            updated
        }
    }

    /** Run OCR on an existing page's archive image, keeping its page number. */
    suspend fun ocrExistingPage(
        book: Book,
        page: Page,
        geminiApiKey: String,
        providerConfig: ProviderConfig? = null,
        onApiCall: () -> Unit = {},
    ): Book = withContext(Dispatchers.IO) {
        val imagePath = archiveImagePath(book, page)
            ?: throw IllegalStateException("此页没有原始图片，无法 OCR")
        val ocrJpeg = ImageProcessing.toOcrJpeg(ImageProcessing.decode(File(imagePath).readBytes()))
        val ocrText = dispatchOcr(ocrJpeg, geminiApiKey, providerConfig, onApiCall)
        mutex.withLock {
            val base = loadBook(book.uid) ?: book
            val now = utcNow()
            val updated = base.copy(
                updatedAt = now,
                pages = base.pages.map {
                    if (it.page == page.page && it.addedAt == page.addedAt) {
                        it.copy(ocrText = ocrText, ocrModel = providerConfig?.activeProvider?.model ?: GeminiOcrClient.DEFAULT_MODEL, ocrCapturedAt = now)
                    } else {
                        it
                    }
                },
            )
            saveBook(updated)
            cachedBook = updated
            updated
        }
    }

    /**
     * Keep an OCR result on a capture that's still waiting for a page number,
     * so deferring the dialog doesn't lose (or re-bill) the recognition.
     */
    suspend fun storeCaptureOcrText(
        book: Book,
        capture: com.readingnotes.app.model.Capture,
        ocrText: String,
    ): Book = withContext(Dispatchers.IO) {
        mutex.withLock {
            val base = loadBook(book.uid) ?: book
            val updated = base.copy(
                updatedAt = utcNow(),
                captures = base.captures.map {
                    if (it.id == capture.id) it.copy(ocrText = ocrText) else it
                },
            )
            saveBook(updated)
            cachedBook = updated
            updated
        }
    }

    /** Change an existing page's number, moving its archive image and entries. */
    suspend fun changePageNumber(
        book: Book,
        page: Page,
        newNumber: Int,
    ): Book = withContext(Dispatchers.IO) {
        mutex.withLock {
            val base = loadBook(book.uid) ?: book
            val now = utcNow()
            val newRelPath = "pages/p%04d_archive.webp".format(newNumber)
            val oldRel = page.archiveImage
            if (oldRel != null && oldRel != newRelPath) {
                val src = archiveFile(base.uid, oldRel)
                if (src.exists()) {
                    val dest = archiveFile(base.uid, newRelPath)
                    dest.parentFile?.mkdirs()
                    src.copyTo(dest, overwrite = true)
                    src.delete()
                }
            }
            val updated = base.copy(
                updatedAt = now,
                pages = base.pages.map {
                    if (it.page == page.page && it.addedAt == page.addedAt) {
                        it.copy(page = newNumber, archiveImage = if (oldRel != null) newRelPath else null)
                    } else {
                        it
                    }
                }.sortedBy { it.page },
                entries = base.entries.map {
                    if (it.page == page.page) it.copy(page = newNumber) else it
                },
            )
            saveBook(updated)
            if (oldRel != null && oldRel != newRelPath) {
                val newLocalFile = archiveFile(updated.uid, newRelPath)
                syncQueueStore.enqueueDelete("${updated.dropboxRoot}/$oldRel")
                syncQueueStore.enqueueUpload("${updated.dropboxRoot}/$newRelPath", newLocalFile.absolutePath)
                DropboxSyncWorker.trigger(context)
            }
            cachedBook = updated
            updated
        }
    }

    /** Delete an unprocessed capture (photo) and its image file. */
    suspend fun deleteCapture(
        book: Book,
        capture: com.readingnotes.app.model.Capture,
    ): Book = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { File(capture.imagePath).delete() }
            val base = loadBook(book.uid) ?: book
            val updated = base.copy(
                updatedAt = utcNow(),
                captures = base.captures.filterNot { it.id == capture.id },
            )
            saveBook(updated)
            cachedBook = updated
            updated
        }
    }

    private fun buildPageFromCapture(
        book: Book,
        capture: com.readingnotes.app.model.Capture,
        pageNumber: Int,
        now: String,
        ocrText: String?,
    ): Page {
        val archiveRelPath = "pages/p%04d_archive.webp".format(pageNumber)
        val src = File(capture.imagePath)
        if (src.exists()) {
            val dest = archiveFile(book.uid, archiveRelPath)
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        return Page(
            page = pageNumber,
            archiveImage = archiveRelPath,
            ocrText = ocrText,
            ocrModel = ocrText?.let { GeminiOcrClient.DEFAULT_MODEL },
            ocrCapturedAt = ocrText?.let { now },
            addedAt = now,
        )
    }

    /** Absolute local path of a page's archive image, or null if not present. */
    fun archiveImagePath(book: Book, page: Page): String? {
        val name = page.archiveImage ?: return null
        val file = archiveFile(book.uid, name)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Persist an edited [book]: writes book.json locally and, when a Dropbox
     * credential is available, uploads book.json (one-way app -> Dropbox).
     */
    suspend fun persist(book: Book, dropboxCredentialJson: String?): Book =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val stamped = book.copy(updatedAt = utcNow())
                saveBook(stamped)
                cachedBook = stamped
                if (!dropboxCredentialJson.isNullOrBlank()) {
                    runCatching {
                        val client = DropboxClient.fromCredentialJson(dropboxCredentialJson)
                        client.uploadFile(
                            "${stamped.dropboxRoot}/book.json",
                            BookStore.encode(stamped).toByteArray(Charsets.UTF_8),
                        )
                    }
                }
                stamped
            }
        }

    private fun loadOrCreateBook(title: String): Book {
        cachedBook?.let { return it }
        val existingFile = findExistingBookJson()
        if (existingFile != null) {
            return BookStore.decode(existingFile.readText()).also { cachedBook = it }
        }

        val uid = UUID.randomUUID().toString()
        val now = utcNow()
        val book = Book(
            uid = uid,
            title = title.ifBlank { "未命名" },
            createdAt = now,
            updatedAt = now,
            dropboxRoot = "/ReadingVault/books/$uid",
        )
        saveBook(book)
        cachedBook = book
        return book
    }

    private fun saveBook(book: Book) {
        val file = bookJsonFile(book.uid)
        file.parentFile?.mkdirs()
        val json = BookStore.encode(book)
        // Validate JSON is well-formed before writing
        runCatching { BookStore.decode(json) }.getOrElse {
            throw IllegalStateException("Book serialization produced invalid JSON", it)
        }
        // Backup current file before overwriting
        val bak = File(file.parentFile, "book.json.bak")
        if (file.exists()) {
            file.copyTo(bak, overwrite = true)
        }
        // Write to temp then atomic rename
        val tmp = File(file.parentFile, "book.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            // Fallback: direct write if rename fails (cross-filesystem)
            file.writeText(json)
            tmp.delete()
        }
    }

    /**
     * Attempt to recover a book from its backup file if the main file is missing
     * or corrupted.
     */
    private fun recoverFromBackup(uid: String): Book? {
        val dir = bookDir(uid)
        val bak = File(dir, "book.json.bak")
        if (!bak.exists()) return null
        return runCatching {
            val book = BookStore.decode(bak.readText())
            // Restore the backup as the main file
            val main = File(dir, "book.json")
            bak.copyTo(main, overwrite = true)
            book
        }.getOrNull()
    }

    private fun saveArchiveImage(uid: String, relativePath: String, bytes: ByteArray) {
        val file = archiveFile(uid, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun enqueuePageArchiveUpload(book: Book, page: Page) {
        val relativePath = page.archiveImage ?: return
        syncQueueStore.enqueueUpload(
            "${book.dropboxRoot}/$relativePath",
            archiveFile(book.uid, relativePath).absolutePath,
        )
        DropboxSyncWorker.trigger(context)
    }

    private fun archiveFile(uid: String, relativePath: String): File =
        File(bookDir(uid), relativePath)

    private fun bookJsonFile(uid: String): File = File(bookDir(uid), "book.json")

    private fun bookDir(uid: String): File = File(File(context.filesDir, "books"), uid)

    private fun findExistingBookJson(): File? {
        val booksRoot = File(context.filesDir, "books")
        val children = booksRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        return children.firstOrNull { File(it, "book.json").exists() }?.let { File(it, "book.json") }
    }

    /**
     * Dispatch OCR: use OcrDispatcher if providerConfig has providers, else
     * fall back to legacy GeminiOcrClient with the raw API key.
     */
    private suspend fun dispatchOcr(
        ocrJpeg: ByteArray,
        legacyApiKey: String,
        providerConfig: ProviderConfig?,
        onApiCall: () -> Unit = {},
    ): String {
        val config = providerConfig?.takeIf { it.providers.isNotEmpty() }
        return if (config != null) {
            OcrDispatcher(config).ocrPage(ocrJpeg, onApiCall).text
        } else {
            onApiCall()
            GeminiOcrClient(legacyApiKey).ocrPage(ocrJpeg)
        }
    }

    private fun utcNow(): String = java.time.Instant.now().toString()

    companion object {
        // Loosened pattern: find any digits inside a [非本文...] block.
        private val PAGE_NUM_PATTERN = Regex("\\[\u975e\u672c\u6587[\uff1a:].*?(\\d+).*?\\]")

        /** Extracts page number from OCR output's non-body tag, if present. */
        fun extractPageNumber(ocrText: String): Int? =
            PAGE_NUM_PATTERN.find(ocrText)?.groupValues?.get(1)?.toIntOrNull()
    }
}

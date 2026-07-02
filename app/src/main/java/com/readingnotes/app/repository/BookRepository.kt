package com.readingnotes.app.repository

import android.content.Context
import com.readingnotes.app.dropbox.DropboxClient
import com.readingnotes.app.image.ImageProcessing
import com.readingnotes.app.model.Book
import com.readingnotes.app.model.BookStore
import com.readingnotes.app.model.Page
import com.readingnotes.app.ocr.GeminiOcrClient
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
    private var cachedBook: Book? = null

    suspend fun captureAndSync(
        sourceBytes: ByteArray,
        geminiApiKey: String,
        dropboxCredentialJson: String,
        bookTitle: String,
    ): CaptureResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sourceBitmap = ImageProcessing.decode(sourceBytes)
            val ocrJpeg = ImageProcessing.toOcrJpeg(sourceBitmap)
            val archiveWebp = ImageProcessing.toArchiveWebp(sourceBitmap)

            val book = loadOrCreateBook(bookTitle)
            val nextPageNumber = book.pages.size + 1
            val archiveRelativePath = "pages/p%04d_archive.webp".format(nextPageNumber)
            val now = utcNow()

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

    /** Load a specific book by uid. */
    fun loadBook(uid: String): Book? {
        val jsonFile = bookJsonFile(uid)
        return if (jsonFile.exists()) runCatching { BookStore.decode(jsonFile.readText()) }.getOrNull() else null
    }

    /** Delete a book and all its data from disk. */
    fun deleteBook(uid: String) {
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
    ): ProcessOutcome = withContext(Dispatchers.IO) {
        val ocrText = precomputedOcrText ?: run {
            val ocrJpeg = ImageProcessing.toOcrJpeg(ImageProcessing.decode(File(capture.imagePath).readBytes()))
            GeminiOcrClient(geminiApiKey).ocrPage(ocrJpeg)
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
            cachedBook = updated
            updated
        }
    }

    /** Run OCR on an existing page's archive image, keeping its page number. */
    suspend fun ocrExistingPage(
        book: Book,
        page: Page,
        geminiApiKey: String,
    ): Book = withContext(Dispatchers.IO) {
        val imagePath = archiveImagePath(book, page)
            ?: throw IllegalStateException("此页没有原始图片，无法 OCR")
        val ocrJpeg = ImageProcessing.toOcrJpeg(ImageProcessing.decode(File(imagePath).readBytes()))
        val ocrText = GeminiOcrClient(geminiApiKey).ocrPage(ocrJpeg)
        mutex.withLock {
            val base = loadBook(book.uid) ?: book
            val now = utcNow()
            val updated = base.copy(
                updatedAt = now,
                pages = base.pages.map {
                    if (it.page == page.page && it.addedAt == page.addedAt) {
                        it.copy(ocrText = ocrText, ocrModel = GeminiOcrClient.DEFAULT_MODEL, ocrCapturedAt = now)
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
        file.writeText(BookStore.encode(book))
    }

    private fun saveArchiveImage(uid: String, relativePath: String, bytes: ByteArray) {
        val file = archiveFile(uid, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
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

    private fun utcNow(): String = java.time.Instant.now().toString()

    companion object {
        private val PAGE_NUM_PATTERN = Regex("""\[非本文[：:]\s*[pP]?\.?(\d+)\s*]""")

        /** Extracts page number from OCR output's [非本文: p.XX] tag, if present. */
        fun extractPageNumber(ocrText: String): Int? =
            PAGE_NUM_PATTERN.find(ocrText)?.groupValues?.get(1)?.toIntOrNull()
    }
}

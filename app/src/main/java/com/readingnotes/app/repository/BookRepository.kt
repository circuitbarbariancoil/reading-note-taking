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
            val page = Page(
                page = nextPageNumber,
                archiveImage = archiveRelativePath,
                ocrText = ocrText,
                ocrModel = GeminiOcrClient.DEFAULT_MODEL,
                ocrCapturedAt = now,
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

    /** Absolute local path of a page's archive image, or null if not present. */
    fun archiveImagePath(book: Book, page: Page): String? {
        val file = archiveFile(book.uid, page.archiveImage)
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
}

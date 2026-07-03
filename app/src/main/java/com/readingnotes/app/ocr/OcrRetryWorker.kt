package com.readingnotes.app.ocr

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.settings.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * WorkManager-backed retry queue for failed OCR operations. Survives process
 * death and retries with exponential backoff (30s, 60s, 120s).
 */
class OcrRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookUid = inputData.getString(KEY_BOOK_UID) ?: return Result.failure()
        val pageNumber = inputData.getInt(KEY_PAGE_NUMBER, -1)
        val captureId = inputData.getString(KEY_CAPTURE_ID)

        val settingsStore = SettingsStore(applicationContext)
        val settings = settingsStore.read()
        val repository = BookRepository(applicationContext)

        if (settings.maxOcrRetries == 0) return Result.failure()

        val book = repository.loadBook(bookUid) ?: return Result.failure()

        return try {
            if (captureId != null) {
                // Retry OCR for an unprocessed capture
                val capture = book.captures.find { it.id == captureId } ?: return Result.success()
                repository.processCapture(
                    book = book,
                    capture = capture,
                    geminiApiKey = settings.geminiApiKey.orEmpty(),
                    providerConfig = settings.providerConfig,
                    onApiCall = { providerId -> settingsStore.recordApiCall(providerId) },
                )
            } else if (pageNumber >= 0) {
                // Retry OCR for an existing page
                val page = book.pages.find { it.page == pageNumber } ?: return Result.success()
                repository.ocrExistingPage(
                    book = book,
                    page = page,
                    geminiApiKey = settings.geminiApiKey.orEmpty(),
                    providerConfig = settings.providerConfig,
                    onApiCall = { providerId -> settingsStore.recordApiCall(providerId) },
                )
            }
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < settings.maxOcrRetries) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_BOOK_UID = "book_uid"
        private const val KEY_PAGE_NUMBER = "page_number"
        private const val KEY_CAPTURE_ID = "capture_id"
        /** Enqueue a retry for a failed page OCR. */
        fun enqueuePageOcr(context: Context, bookUid: String, pageNumber: Int) {
            val workName = "ocr_page_${bookUid}_$pageNumber"
            val request = OneTimeWorkRequestBuilder<OcrRetryWorker>()
                .setInputData(workDataOf(KEY_BOOK_UID to bookUid, KEY_PAGE_NUMBER to pageNumber))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
        }

        /** Enqueue a retry for a failed capture OCR. */
        fun enqueueCaptureOcr(context: Context, bookUid: String, captureId: String) {
            val workName = "ocr_capture_${bookUid}_$captureId"
            val request = OneTimeWorkRequestBuilder<OcrRetryWorker>()
                .setInputData(workDataOf(KEY_BOOK_UID to bookUid, KEY_CAPTURE_ID to captureId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

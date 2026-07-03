package com.readingnotes.app.dropbox

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.readingnotes.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class DropboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = SettingsStore(applicationContext).read()
        val credential = settings.dropboxCredentialJson
        if (credential.isNullOrBlank()) return@withContext Result.success()

        val queueStore = SyncQueueStore(applicationContext)
        val client = DropboxClient.fromCredentialJson(credential)
        var hadFailures = false

        for (op in queueStore.peekAll()) {
            try {
                when (op.type) {
                    TYPE_UPLOAD -> {
                        val localPath = op.localPath
                        val localFile = localPath?.let(::File)
                        if (localFile == null || !localFile.exists()) {
                            queueStore.remove(op.id)
                        } else {
                            client.uploadFile(op.dropboxPath, localFile.readBytes())
                            queueStore.remove(op.id)
                        }
                    }

                    TYPE_DELETE -> {
                        client.deleteFile(op.dropboxPath)
                        queueStore.remove(op.id)
                    }

                    else -> queueStore.remove(op.id)
                }
            } catch (_: Exception) {
                hadFailures = true
            }
        }

        if (hadFailures && queueStore.peekAll().isNotEmpty()) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "dropbox_sync"
        private const val UNIQUE_PERIODIC_WORK_NAME = "dropbox_sync_periodic"
        private const val TYPE_UPLOAD = "upload"
        private const val TYPE_DELETE = "delete"

        fun trigger(context: Context) {
            val request = OneTimeWorkRequestBuilder<DropboxSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DropboxSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

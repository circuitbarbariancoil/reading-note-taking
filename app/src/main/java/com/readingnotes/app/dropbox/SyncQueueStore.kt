package com.readingnotes.app.dropbox

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class SyncOp(
    val id: String,
    val type: String,
    val dropboxPath: String,
    val localPath: String? = null,
    val createdAt: String,
)

class SyncQueueStore(context: Context) {
    private val queueFile = File(File(context.filesDir, "sync"), "sync_queue.json")

    @Synchronized
    fun enqueueUpload(dropboxPath: String, localPath: String) {
        upsert(
            SyncOp(
                id = UUID.randomUUID().toString(),
                type = TYPE_UPLOAD,
                dropboxPath = dropboxPath,
                localPath = localPath,
                createdAt = java.time.Instant.now().toString(),
            ),
        )
    }

    @Synchronized
    fun enqueueDelete(dropboxPath: String) {
        upsert(
            SyncOp(
                id = UUID.randomUUID().toString(),
                type = TYPE_DELETE,
                dropboxPath = dropboxPath,
                createdAt = java.time.Instant.now().toString(),
            ),
        )
    }

    @Synchronized
    fun peekAll(): List<SyncOp> = readAll()

    @Synchronized
    fun remove(id: String) {
        val updated = readAll().filterNot { it.id == id }
        writeAll(updated)
    }

    private fun upsert(op: SyncOp) {
        val updated = readAll().filterNot { it.type == op.type && it.dropboxPath == op.dropboxPath } + op
        writeAll(updated)
    }

    private fun readAll(): List<SyncOp> {
        if (!queueFile.exists()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, queueFile.readText()) }
            .getOrDefault(emptyList())
    }

    private fun writeAll(ops: List<SyncOp>) {
        queueFile.parentFile?.mkdirs()
        val encoded = json.encodeToString(listSerializer, ops)
        val tmp = File(queueFile.parentFile, "${queueFile.name}.tmp")
        tmp.writeText(encoded)
        if (!tmp.renameTo(queueFile)) {
            queueFile.writeText(encoded)
            tmp.delete()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private const val TYPE_UPLOAD = "upload"
        private const val TYPE_DELETE = "delete"
        private val listSerializer = kotlinx.serialization.builtins.ListSerializer(SyncOp.serializer())
    }
}

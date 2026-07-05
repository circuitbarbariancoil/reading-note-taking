package com.readingnotes.app.dropbox

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.DbxException
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.DeleteErrorException
import com.dropbox.core.v2.files.WriteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class DropboxClient private constructor(
    private val client: DbxClientV2,
) {
    suspend fun uploadFile(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        client.files()
            .uploadBuilder(path)
            .withMode(WriteMode.OVERWRITE)
            .uploadAndFinish(ByteArrayInputStream(bytes))
    }

    suspend fun deleteFile(path: String) = withContext(Dispatchers.IO) {
        try {
            client.files().deleteV2(path)
        } catch (e: DeleteErrorException) {
            if (e.errorValue.isPathLookup() && e.errorValue.pathLookupValue.isNotFound) return@withContext
            throw e
        } catch (e: DbxException) {
            throw e
        }
    }

    /** List immediate subfolders of [path]. Returns folder names (not full paths). */
    suspend fun listFolders(path: String): List<String> = withContext(Dispatchers.IO) {
        val result = client.files().listFolder(path)
        val folders = mutableListOf<String>()
        folders.addAll(result.entries.filterIsInstance<com.dropbox.core.v2.files.FolderMetadata>().map { it.name })
        var cursor = result.cursor
        var hasMore = result.hasMore
        while (hasMore) {
            val more = client.files().listFolderContinue(cursor)
            folders.addAll(more.entries.filterIsInstance<com.dropbox.core.v2.files.FolderMetadata>().map { it.name })
            cursor = more.cursor
            hasMore = more.hasMore
        }
        folders
    }

    /** List all file entries (recursive) under [path]. Returns full paths. */
    suspend fun listFilesRecursive(path: String): List<String> = withContext(Dispatchers.IO) {
        val result = client.files().listFolderBuilder(path).withRecursive(true).start()
        val files = mutableListOf<String>()
        files.addAll(result.entries.filterIsInstance<com.dropbox.core.v2.files.FileMetadata>().map { it.pathLower ?: it.name })
        var cursor = result.cursor
        var hasMore = result.hasMore
        while (hasMore) {
            val more = client.files().listFolderContinue(cursor)
            files.addAll(more.entries.filterIsInstance<com.dropbox.core.v2.files.FileMetadata>().map { it.pathLower ?: it.name })
            cursor = more.cursor
            hasMore = more.hasMore
        }
        files
    }

    /** Download a file's content as bytes. */
    suspend fun downloadFile(path: String): ByteArray = withContext(Dispatchers.IO) {
        client.files().download(path).inputStream.use { it.readBytes() }
    }

    companion object {
        fun fromCredentialJson(credentialJson: String): DropboxClient {
            val credential = DbxCredential.Reader.readFully(credentialJson)
            val config = DbxRequestConfig.newBuilder(DropboxConfig.REQUEST_NAME).build()
            return DropboxClient(DbxClientV2(config, credential))
        }
    }
}

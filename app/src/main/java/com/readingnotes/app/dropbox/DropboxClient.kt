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

    companion object {
        fun fromCredentialJson(credentialJson: String): DropboxClient {
            val credential = DbxCredential.Reader.readFully(credentialJson)
            val config = DbxRequestConfig.newBuilder(DropboxConfig.REQUEST_NAME).build()
            return DropboxClient(DbxClientV2(config, credential))
        }
    }
}

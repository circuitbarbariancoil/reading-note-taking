package com.readingnotes.app.dropbox

object DropboxConfig {
    const val APP_KEY = "tju8txxd67454n0"
    const val REQUEST_NAME = "reading-note-taking"

    val SCOPES = listOf(
        "account_info.read",
        "files.content.write",
        "files.content.read",
    )
}

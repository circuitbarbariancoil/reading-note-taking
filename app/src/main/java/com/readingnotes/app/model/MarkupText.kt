package com.readingnotes.app.model

/** Helpers for the shared markup: `~={color}…=~` highlights and `漢字《よみ》` ruby. */
object MarkupText {

    private val HIGHLIGHT = Regex("~=\\{[^}]*\\}(.*?)=~", RegexOption.DOT_MATCHES_ALL)
    private val RUBY = Regex("《[^》]*》")

    /** Strips highlight markers and ruby readings for a compact preview. */
    fun plain(text: String): String =
        text.replace(HIGHLIGHT) { it.groupValues[1] }.replace(RUBY, "")
}

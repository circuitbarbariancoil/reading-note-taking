package com.readingnotes.app.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Helpers for the shared markup: `~={color}…=~` highlights and `漢字《よみ》` ruby. */
object MarkupText {

    private val HIGHLIGHT = Regex("~=\\{[^}]*\\}(.*?)=~", RegexOption.DOT_MATCHES_ALL)
    private val HIGHLIGHT_FULL = Regex("~=\\{([^}]*)\\}(.*?)=~", RegexOption.DOT_MATCHES_ALL)
    private val RUBY = Regex("《[^》]*》")

    /** Strips highlight markers and ruby readings for a compact preview. */
    fun plain(text: String): String =
        text.replace(HIGHLIGHT) { it.groupValues[1] }.replace(RUBY, "")

    /**
     * Returns an [AnnotatedString] with highlight colors rendered as background
     * spans. Ruby readings are stripped; highlight content is shown with its
     * color as a translucent background.
     */
    fun rich(text: String, colorMap: Map<String, Color>): AnnotatedString = buildAnnotatedString {
        // First strip ruby readings, keeping base characters.
        val noRuby = text.replace(RUBY, "")
        var cursor = 0
        HIGHLIGHT_FULL.findAll(noRuby).forEach { match ->
            // Append text before this highlight
            if (match.range.first > cursor) {
                append(noRuby.substring(cursor, match.range.first))
            }
            val colorName = match.groupValues[1]
            val content = match.groupValues[2]
            val color = colorMap[colorName]
            if (color != null) {
                pushStyle(SpanStyle(background = color.copy(alpha = 0.25f)))
                append(content)
                pop()
            } else {
                append(content)
            }
            cursor = match.range.last + 1
        }
        // Append remaining text after last highlight
        if (cursor < noRuby.length) {
            append(noRuby.substring(cursor))
        }
    }
}

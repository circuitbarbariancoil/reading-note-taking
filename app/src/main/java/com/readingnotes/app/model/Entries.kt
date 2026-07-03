package com.readingnotes.app.model

import java.util.UUID

/**
 * Builds [Entry] and [PageHighlight] objects from a text selection over a frozen
 * [Page]. All offsets are Unicode code points (see [CodePoints]).
 *
 * - A highlight expands to the whole sentence containing it and embeds the
 *   `~={color}…=~` markup (DESIGN.md). Each highlight action is its own entry.
 * - A manual excerpt keeps the exact selection, no expansion.
 */
object Entries {

    /** Japanese sentence terminators; newlines also break sentences. */
    private val TERMINATORS = setOf('。', '！', '？', '!', '?', '\n')

    /** Expand [start, end) (code points) to the sentence boundaries in [text]. */
    fun expandToSentence(text: String, start: Int, end: Int): IntRange {
        val cps = text.codePoints().toArray()
        val n = cps.size
        if (n == 0) return 0..0
        val s = start.coerceIn(0, n)
        val e = end.coerceIn(s, n)

        var sentenceStart = s
        while (sentenceStart > 0 && !TERMINATORS.contains(cps[sentenceStart - 1].toChar())) {
            sentenceStart--
        }
        var sentenceEnd = if (e > s) e else s
        while (sentenceEnd < n && !TERMINATORS.contains(cps[sentenceEnd].toChar())) {
            sentenceEnd++
        }
        if (sentenceEnd < n) sentenceEnd++ // include the terminator
        // Trim leading whitespace/newline left by the previous sentence break.
        while (sentenceStart < sentenceEnd && cps[sentenceStart].toChar() == '\n') sentenceStart++
        return sentenceStart until sentenceEnd
    }

    /** Highlight -> sentence entry with embedded `~={color}…=~` markup. */
    fun highlightEntry(
        page: Page,
        selStart: Int,
        selEnd: Int,
        color: String,
        now: String,
    ): Pair<PageHighlight, Entry>? {
        val ocrText = page.ocrText ?: return null
        val sentence = expandToSentence(ocrText, selStart, selEnd)
        val sStart = sentence.first
        val sEnd = sentence.last + 1
        val sentenceText = CodePoints.substring(ocrText, sStart, sEnd)

        val hlStartInSentence = (selStart - sStart).coerceIn(0, CodePoints.length(sentenceText))
        val hlEndInSentence = (selEnd - sStart).coerceIn(hlStartInSentence, CodePoints.length(sentenceText))
        val marked = insertMarkup(sentenceText, hlStartInSentence, hlEndInSentence, color)

        val highlight = PageHighlight(
            id = newId(),
            start = selStart,
            end = selEnd,
            color = color,
        )
        val entry = Entry(
            id = newId(),
            page = page.page,
            srcStart = sStart,
            srcEnd = sEnd,
            text = marked,
            kind = EntryKind.highlight,
            highlightId = highlight.id,
            createdAt = now,
            updatedAt = now,
        )
        return highlight to entry
    }

    /** Manual excerpt -> entry with the exact selection, no expansion, no markup. */
    fun excerptEntry(
        page: Page,
        selStart: Int,
        selEnd: Int,
        now: String,
    ): Entry? {
        val ocrText = page.ocrText ?: return null
        val text = CodePoints.substring(ocrText, selStart, selEnd)
        return Entry(
            id = newId(),
            page = page.page,
            srcStart = selStart,
            srcEnd = selEnd,
            text = text,
            kind = EntryKind.excerpt,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun insertMarkup(text: String, start: Int, end: Int, color: String): String {
        if (end <= start) return text
        val before = CodePoints.substring(text, 0, start)
        val mid = CodePoints.substring(text, start, end)
        val after = CodePoints.substring(text, end, CodePoints.length(text))
        return "$before~={$color}$mid=~$after"
    }

    private fun newId(): String = UUID.randomUUID().toString().take(8)
}

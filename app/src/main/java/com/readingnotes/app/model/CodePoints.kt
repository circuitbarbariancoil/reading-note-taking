package com.readingnotes.app.model

/**
 * Offset helpers that treat a String as a sequence of Unicode code points
 * rather than UTF-16 chars. This is the canonical offset unit shared between
 * the Android app and the Obsidian plugin (see DESIGN.md §1.2), so that rare
 * kanji / emoji (surrogate pairs) never cause off-by-one misalignment.
 */
object CodePoints {
    private val RUBY_READING = Regex("《[^》]*》")

    /** Number of Unicode code points in [s]. */
    fun length(s: String): Int = s.codePointCount(0, s.length)

    /** Substring by code-point offsets [start, end). */
    fun substring(s: String, start: Int, end: Int): String {
        val from = s.offsetByCodePoints(0, start)
        val to = s.offsetByCodePoints(from, end - start)
        return s.substring(from, to)
    }

    /** Removes ruby readings (`《...》`) while keeping base text intact. */
    fun stripRuby(s: String): String = s.replace(RUBY_READING, "")

    /** Convert a code-point offset to a UTF-16 char index into [s]. */
    fun toCharIndex(s: String, codePointOffset: Int): Int =
        s.offsetByCodePoints(0, codePointOffset)
}

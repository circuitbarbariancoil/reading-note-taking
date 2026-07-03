package com.readingnotes.app.model

import kotlinx.serialization.Serializable

/**
 * A user-configurable highlight color. [name] is the token written into the
 * `~={name}text=~` markup (shared with the Obsidian plugin); [css] is any CSS
 * color. Colors carry no fixed meaning — the user defines as many as they like.
 *
 * Deactivating ([active] = false) removes the color from input toolbars and
 * filters, but the name→css mapping is kept forever so existing entry markup
 * keeps rendering in its original color. Entry text is never rewritten.
 */
@Serializable
data class HighlightColor(
    val name: String,
    val css: String,
    val active: Boolean = true,
)

@Serializable
data class HighlightPalette(
    val colors: List<HighlightColor> = DEFAULT.colors,
) {
    fun asMap(): Map<String, String> = colors.associate { it.name to it.css }

    fun activeColors(): List<HighlightColor> = colors.filter { it.active }

    companion object {
        val DEFAULT = HighlightPalette(
            colors = listOf(
                HighlightColor("yellow", "#D9B44A"),
                HighlightColor("green", "#6E8B5A"),
                HighlightColor("blue", "#5A7A9B"),
                HighlightColor("pink", "#B96A78"),
            ),
        )

        /** Default word–standard-color pairings offered when adding a color. */
        val PRESETS = listOf(
            HighlightColor("red", "#C0504D"),
            HighlightColor("orange", "#D28445"),
            HighlightColor("yellow", "#D9B44A"),
            HighlightColor("green", "#6E8B5A"),
            HighlightColor("teal", "#4E8E8B"),
            HighlightColor("blue", "#5A7A9B"),
            HighlightColor("purple", "#7E6B9E"),
            HighlightColor("pink", "#B96A78"),
            HighlightColor("brown", "#8A6D4F"),
            HighlightColor("gray", "#8A8A82"),
        )
    }
}

package com.readingnotes.app.model

import kotlinx.serialization.Serializable

/**
 * A user-configurable highlight color. [name] is the token written into the
 * `~={name}text=~` markup (shared with the Obsidian plugin); [css] is any CSS
 * color. Colors carry no fixed meaning — the user defines as many as they like.
 */
@Serializable
data class HighlightColor(
    val name: String,
    val css: String,
)

@Serializable
data class HighlightPalette(
    val colors: List<HighlightColor> = DEFAULT.colors,
) {
    fun asMap(): Map<String, String> = colors.associate { it.name to it.css }

    companion object {
        val DEFAULT = HighlightPalette(
            colors = listOf(
                HighlightColor("yellow", "#D9B44A"),
                HighlightColor("green", "#6E8B5A"),
                HighlightColor("blue", "#5A7A9B"),
                HighlightColor("pink", "#B96A78"),
            ),
        )
    }
}

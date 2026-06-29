package com.readingnotes.app.ui

import com.readingnotes.app.model.CodePoints
import com.readingnotes.app.model.Page
import com.readingnotes.app.model.PageHighlight

/**
 * Renders a frozen [Page] (OCR text + display-layer highlights) to HTML for a
 * WebView. Vertical (tategaki) vs horizontal is a user setting toggled via CSS
 * `writing-mode` — the same markup serves both (DESIGN.md §6.3, §7).
 *
 * Highlights are applied by code-point offset over the frozen text; ruby in the
 * `漢字《よみ》` convention becomes `<ruby>` so furigana render in both modes.
 */
object PageHtml {

    /**
     * @param colors map of color name -> CSS color (from highlight-colors.json)
     * @param vertical true for tategaki (vertical-rl), false for horizontal
     */
    fun render(
        page: Page,
        colors: Map<String, String>,
        vertical: Boolean,
        fontSizePx: Int = 20,
    ): String {
        val writingMode = if (vertical) "vertical-rl" else "horizontal-tb"
        val swatches = colors.entries.joinToString("\n") { (name, css) ->
            ".hl-$name{background:${css}33;border-bottom:2px solid $css;}"
        }
        val body = buildBody(page.ocrText, page.highlights, colors)
        return """
            <!DOCTYPE html><html lang="ja"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style>
              html{height:100%;}
              html,body{margin:0;padding:16px;background:#fff;color:#1a1a1a;box-sizing:border-box;}
              body{
                writing-mode:$writingMode;
                font-family:"Noto Serif CJK JP",serif;
                font-size:${fontSizePx}px; line-height:1.9;
                ${if (vertical) "height:100%;overflow-x:auto;overflow-y:hidden;" else ""}
              }
              rt{font-size:.5em;}
              $swatches
            </style></head><body>$body</body></html>
        """.trimIndent()
    }

    private fun buildBody(
        text: String,
        highlights: List<PageHighlight>,
        colors: Map<String, String>,
    ): String {
        val n = CodePoints.length(text)
        val sorted = highlights.filter { it.start in 0..n && it.end in it.start..n }
            .sortedBy { it.start }
        val sb = StringBuilder()
        var cursor = 0
        for (h in sorted) {
            if (h.start > cursor) sb.append(rubyEscape(CodePoints.substring(text, cursor, h.start)))
            val cls = if (colors.containsKey(h.color)) "hl-${h.color}" else ""
            sb.append("<span class=\"$cls\">")
            sb.append(rubyEscape(CodePoints.substring(text, h.start, h.end)))
            sb.append("</span>")
            cursor = maxOf(cursor, h.end)
        }
        if (cursor < n) sb.append(rubyEscape(CodePoints.substring(text, cursor, n)))
        return sb.toString()
    }

    /** Escapes HTML and converts `漢字《よみ》` ruby notation into <ruby> tags. */
    private fun rubyEscape(s: String): String {
        val escaped = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
        // base《reading》 -> <ruby>base<rt>reading</rt></ruby>
        val regex = Regex("([\\p{IsHan}\\p{IsKatakana}\\p{IsHiragana}A-Za-z]+)《([^》]+)》")
        return regex.replace(escaped) { m ->
            "<ruby>${m.groupValues[1]}<rt>${m.groupValues[2]}</rt></ruby>"
        }
    }
}

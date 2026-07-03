package com.readingnotes.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

object EntryHtml {

    private const val PAPER = "#F4EFE3"
    private const val INK = "#211E1A"

    fun render(
        excerpt: String,
        annotation: String,
        colors: Map<String, String>,
    ): String {
        val swatches = colors.entries.joinToString("\n") { (name, css) ->
            ".hl-$name{background:${css}33;border-bottom:2px solid $css;}"
        }
        return """
            <!DOCTYPE html><html lang="ja"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html,body{margin:0;padding:0;background:transparent;color:$INK;overflow:hidden;}
              body{
                padding:0;
                font-family:"Noto Serif CJK JP",serif;
                font-size:15px;
                line-height:1.9;
                writing-mode:horizontal-tb;
                -webkit-user-select:none; user-select:none;
              }
              .block{background:$PAPER;border:1px solid rgba(33,30,26,.08);border-radius:12px;padding:10px 12px;}
              .excerpt{font-size:15px;}
              .annotation{margin-top:6px;color:#5C564F;font-size:12px;line-height:1.6;}
              .divider{height:1px;background:rgba(33,30,26,.12);margin:8px 0;}
              rt{font-size:.5em;}
              .tag{color:#3C5468;background:#E1E6EA;border-radius:8px;padding:0 5px;font-size:.85em;white-space:nowrap;}
              $swatches
            </style></head><body>
              <div class="block">
                <div class="excerpt">${renderText(excerpt, colors)}</div>
                ${if (annotation.isNotBlank()) "<div class=\"divider\"></div><div class=\"annotation\">${renderText(annotation, colors)}</div>" else ""}
              </div>
            </body></html>
        """.trimIndent()
    }

    private fun renderText(text: String, colors: Map<String, String>): String {
        val boldRegex = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        var cursor = 0
        boldRegex.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                sb.append(renderHighlights(text.substring(cursor, match.range.first), colors))
            }
            sb.append("<strong>").append(renderHighlights(match.groupValues[1], colors)).append("</strong>")
            cursor = match.range.last + 1
        }
        if (cursor < text.length) sb.append(renderHighlights(text.substring(cursor), colors))
        return sb.toString()
    }

    /**
     * Splits raw markup on `~={名}...=~` color spans, delegating every plain-text
     * leaf (both inside and outside spans) to [renderRuby], which escapes and
     * emits ruby. Escaping therefore happens exactly once, at the leaf.
     */
    private fun renderHighlights(text: String, colors: Map<String, String>): String {
        val regex = Regex("~=\\{([^}]*)\\}(.*?)=~", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        var cursor = 0
        regex.findAll(text).forEach { match ->
            if (match.range.first > cursor) sb.append(renderRuby(text.substring(cursor, match.range.first)))
            val name = match.groupValues[1]
            val content = renderRuby(match.groupValues[2])
            if (colors.containsKey(name)) {
                sb.append("<span class=\"hl-$name\">").append(content).append("</span>")
            } else {
                sb.append(content)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) sb.append(renderRuby(text.substring(cursor)))
        return sb.toString()
    }

    private fun renderRuby(text: String): String {
        val cps = text.codePoints().toArray()
        val sb = StringBuilder()
        var i = 0
        while (i < cps.size) {
            val ruby = tryRuby(cps, i)
            if (ruby != null) {
                val (endExclusive, base, reading) = ruby
                sb.append("<ruby>").append(esc(base)).append("<rt>").append(esc(reading)).append("</rt></ruby>")
                i = endExclusive
                continue
            }
            if (cps[i] == '#'.code) {
                var j = i + 1
                while (j < cps.size && !Character.isWhitespace(cps[j]) && cps[j] != '#'.code) j++
                if (j > i + 1) {
                    sb.append("<span class=\"tag\">").append(esc(String(cps, i, j - i))).append("</span>")
                    i = j
                    continue
                }
            }
            sb.append(esc(String(Character.toChars(cps[i]))))
            i++
        }
        return sb.toString()
    }

    private data class Ruby(val endExclusive: Int, val base: String, val reading: String)

    private fun tryRuby(cps: IntArray, i: Int): Ruby? {
        val n = cps.size
        if (!isBaseChar(cps[i])) return null
        var j = i
        while (j < n && isBaseChar(cps[j])) j++
        if (j >= n || cps[j] != '《'.code) return null
        var k = j + 1
        while (k < n && cps[k] != '》'.code) k++
        if (k >= n) return null
        return Ruby(
            endExclusive = k + 1,
            base = String(cps, i, j - i),
            reading = String(cps, j + 1, k - (j + 1)),
        )
    }

    private fun isBaseChar(cp: Int): Boolean {
        val c = cp.toChar()
        return Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            (c in '\u3040'..'\u309F') ||
            (c in '\u30A0'..'\u30FF') ||
            (c in 'A'..'Z') || (c in 'a'..'z')
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EntryHtmlWebView(
    excerpt: String,
    annotation: String,
    colors: Map<String, Color>,
    modifier: Modifier = Modifier,
) {
    var contentHeightDp by remember(excerpt, annotation) { mutableStateOf(0) }
    val setHeight = rememberUpdatedState<(Int) -> Unit> { h -> if (h > 0) contentHeightDp = h }
    val html = remember(excerpt, annotation, colors) {
        EntryHtml.render(
            excerpt = excerpt,
            annotation = annotation,
            colors = colors.mapValues { it.value.toArgb().let { argb ->
                String.format("#%06X", 0xFFFFFF and argb)
            } },
        )
    }

    AndroidView(
        modifier = modifier.height(if (contentHeightDp > 0) contentHeightDp.dp else 56.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({ setHeight.value(view.contentHeight) }, 60)
                    }
                }
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                isClickable = false
                isLongClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { web ->
            if (web.tag != html) {
                web.tag = html
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

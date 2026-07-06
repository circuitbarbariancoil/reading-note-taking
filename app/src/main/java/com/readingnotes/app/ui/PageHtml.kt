package com.readingnotes.app.ui

import com.readingnotes.app.model.CodePoints
import com.readingnotes.app.model.Page
import com.readingnotes.app.model.PageHighlight

/**
 * Renders a frozen [Page] (OCR text + display-layer highlights) to HTML for a
 * WebView. Vertical (tategaki) vs horizontal is a user setting toggled via CSS
 * `writing-mode` — the same markup serves both (DESIGN.md §6.3, §7).
 *
 * Every code point becomes a `<span data-s data-e>` carrying its code-point
 * offset over the frozen text; ruby (`漢字《よみ》`) becomes one atomic span
 * covering its whole range. This lets JS map a DOM selection back to precise
 * code-point offsets (see [PageWebView]). Highlights are applied by offset.
 */
object PageHtml {

    private val PAPER = "#F4EFE3"
    private val INK = "#211E1A"

    fun render(
        page: Page,
        colors: Map<String, String>,
        vertical: Boolean,
        fontSizePx: Int = 21,
        interactive: Boolean = false,
        flash: IntRange? = null,
    ): String {
        val writingMode = if (vertical) "vertical-rl" else "horizontal-tb"
        // Vertical (tategaki) 傍線 runs down the right edge of the column; only
        // horizontal text gets a bottom underline.
        val hlBorder = if (vertical) "border-left" else "border-bottom"
        val swatches = colors.entries.joinToString("\n") { (name, css) ->
            ".hl-$name{background:${css}33;$hlBorder:2px solid $css;}"
        }
        val body = buildBody(page.ocrText.orEmpty(), page.highlights, colors)
        val startJs = if (flash == null) SCROLL_TO_START_JS else flashJs(flash)
        val selectJs = if (interactive) SELECTION_JS else ""
        val userSelect = if (interactive) "text" else "none"
        val verticalScrollLock = if (vertical) VERTICAL_SCROLL_LOCK_JS else ""
        return """
            <!DOCTYPE html><html lang="ja"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style>
              html{height:100%;}
              html,body{margin:0;padding:20px;background:$PAPER;color:$INK;box-sizing:border-box;}
              body{
                writing-mode:$writingMode;
                font-family:"Noto Serif CJK JP",serif;
                font-size:${fontSizePx}px; line-height:2.0;
                -webkit-user-select:$userSelect; user-select:$userSelect;
                ${if (vertical) "height:100%;overflow-x:auto;overflow-y:hidden;" else ""}
              }
              rt{font-size:.5em;}
              ::selection{background:#3C546840;}
              .flash{animation:flashfade 1.8s ease-out forwards;}
              @keyframes flashfade{0%,40%{background:#3C546855;}100%{background:transparent;}}
              $swatches
            </style></head><body>$body$HIGHLIGHT_UPDATE_FN$startJs$selectJs$verticalScrollLock</body></html>
        """.trimIndent()
    }

    private fun buildBody(
        text: String,
        highlights: List<PageHighlight>,
        colors: Map<String, String>,
    ): String {
        val cps = text.codePoints().toArray()
        val n = cps.size
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            val cp = cps[i]
            if (cp == '\n'.code) {
                sb.append("<br>")
                i++
                continue
            }
            val ruby = tryRuby(cps, i)
            if (ruby != null) {
                val (endExclusive, base, reading) = ruby
                val cls = highlightClass(highlights, colors, i)
                sb.append("<span data-s=\"$i\" data-e=\"$endExclusive\"$cls>")
                sb.append("<ruby>${esc(base)}<rt>${esc(reading)}</rt></ruby>")
                sb.append("</span>")
                i = endExclusive
            } else {
                val cls = highlightClass(highlights, colors, i)
                sb.append("<span data-s=\"$i\" data-e=\"${i + 1}\"$cls>")
                sb.append(esc(String(Character.toChars(cp))))
                sb.append("</span>")
                i++
            }
        }
        return sb.toString()
    }

    private data class Ruby(val endExclusive: Int, val base: String, val reading: String)

    /** Detect `base《reading》` starting at [i] (code-point index). */
    private fun tryRuby(cps: IntArray, i: Int): Ruby? {
        val n = cps.size
        if (!isBaseChar(cps[i])) return null
        var j = i
        while (j < n && isBaseChar(cps[j])) j++
        if (j >= n || cps[j] != '《'.code) return null
        var k = j + 1
        while (k < n && cps[k] != '》'.code) k++
        if (k >= n) return null
        val base = String(cps, i, j - i)
        val reading = String(cps, j + 1, k - (j + 1))
        return Ruby(endExclusive = k + 1, base = base, reading = reading)
    }

    private fun isBaseChar(cp: Int): Boolean {
        val c = cp.toChar()
        return Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            (c in '\u3040'..'\u309F') || // hiragana
            (c in '\u30A0'..'\u30FF') || // katakana
            (c in 'A'..'Z') || (c in 'a'..'z')
    }

    private fun highlightClass(
        highlights: List<PageHighlight>,
        colors: Map<String, String>,
        offset: Int,
    ): String {
        val hit = highlights.lastOrNull { offset >= it.start && offset < it.end && colors.containsKey(it.color) }
            ?: return ""
        return " class=\"hl-${hit.color}\""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * Scrolls to the text start once loaded: the right edge for vertical-rl,
     * the top for horizontal.
     */
    private val SCROLL_TO_START_JS = """
        <script>
          window.addEventListener('load', function(){
            var first = document.querySelector('[data-s]');
            if(first) first.scrollIntoView({inline:'start', block:'start'});
          });
        </script>
    """.trimIndent()

    /** Scrolls to and briefly flashes the given code-point range (查看原文 focus). */
    private fun flashJs(range: IntRange) = """
        <script>
          window.addEventListener('load', function(){
            var spans = document.querySelectorAll('[data-s]');
            var first = null;
            for(var i=0;i<spans.length;i++){
              var s = parseInt(spans[i].getAttribute('data-s'));
              if(s >= ${range.first} && s <= ${range.last}){
                spans[i].classList.add('flash');
                if(!first) first = spans[i];
              }
            }
            if(first) setTimeout(function(){ first.scrollIntoView({inline:'center', block:'center'}); }, 50);
          });
        </script>
    """.trimIndent()

    /** Reports the current selection as code-point offsets to the Kotlin bridge. */
    private val SELECTION_JS = """
        <script>
          function reportSelection(){
            var sel = window.getSelection();
            if(!sel || sel.rangeCount === 0 || sel.isCollapsed){
              if(window.Android) Android.onSelectionCleared();
              return;
            }
            var range = sel.getRangeAt(0);
            var start = Infinity, end = -1;
            var spans = document.querySelectorAll('[data-s]');
            for(var i=0;i<spans.length;i++){
              var el = spans[i];
              if(range.intersectsNode(el)){
                var s = parseInt(el.getAttribute('data-s'));
                var e = parseInt(el.getAttribute('data-e'));
                if(s<start) start=s;
                if(e>end) end=e;
              }
            }
            if(end>start && window.Android) Android.onSelection(start, end);
            else if(window.Android) Android.onSelectionCleared();
          }
          document.addEventListener('selectionchange', function(){ setTimeout(reportSelection, 30); });
        </script>
    """.trimIndent()

    /** Prevents vertical scrolling in vertical-rl mode (WebView scrolls despite CSS overflow). */
    private val VERTICAL_SCROLL_LOCK_JS = """
        <script>
        (function(){
          window.addEventListener('scroll', function(){
            if(window.scrollY !== 0) window.scrollTo(window.scrollX, 0);
          });
        })();
        </script>
    """.trimIndent()

    /**
     * JS that applies the given highlights to already-loaded DOM spans,
     * avoiding a full WebView reload (which would reset scroll position).
     */
    fun highlightUpdateJs(highlights: List<PageHighlight>, colors: Map<String, String>): String {
        val filtered = highlights.filter { colors.containsKey(it.color) }
        val jsArray = filtered.joinToString(",", "[", "]") { "[${it.start},${it.end},'${it.color}']" }
        return "if(typeof RN_applyHL==='function')RN_applyHL($jsArray);"
    }

    private val HIGHLIGHT_UPDATE_FN = """
        <script>
        function RN_applyHL(hl){
          var spans=document.querySelectorAll('[data-s]');
          for(var i=0;i<spans.length;i++){
            var el=spans[i],s=+el.getAttribute('data-s'),cls='';
            for(var j=hl.length-1;j>=0;j--){
              if(s>=hl[j][0]&&s<hl[j][1]){cls='hl-'+hl[j][2];break;}
            }
            if(el.className!==cls)el.className=cls;
          }
        }
        </script>
    """.trimIndent()

    // Retained for existing callers/tests: substring helper alias.
    fun span(text: String, start: Int, end: Int): String = CodePoints.substring(text, start, end)
}

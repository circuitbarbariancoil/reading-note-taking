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

    /** Neutral warm-gray fill for plain excerpts (see [PageHighlight.EXCERPT_COLOR]). */
    private val EXCERPT_CSS = "#7C756B"

    fun render(
        page: Page,
        colors: Map<String, String>,
        vertical: Boolean,
        fontSizePx: Int = 21,
        interactive: Boolean = false,
        flash: IntRange? = null,
        flashExcerpt: Boolean = false,
    ): String {
        val writingMode = if (vertical) "vertical-rl" else "horizontal-tb"
        // Vertical (tategaki) 傍線 runs down the right edge of the column; only
        // horizontal text gets a bottom underline.
        val hlBorder = if (vertical) "border-left" else "border-bottom"
        val hlBorderColor = if (vertical) "border-left-color" else "border-bottom-color"
        val swatches = colors.entries.joinToString("\n") { (name, css) ->
            ".hl-$name{background:${css}33;$hlBorder:2px solid $css;}" +
            "\n.hl-$name.hl-focus{background:${css}70;$hlBorder:2.5px solid $css;}"
        }
        // Excerpt: neutral gray *fill block*, no underline; deepens on focus. On a
        // 查看原文 jump the block stays (so it remains tappable), and a gray underline
        // slowly fades in and then holds (.exflash) as the "you jumped here" cue.
        val exColor = PageHighlight.EXCERPT_COLOR
        val excerptCss = ".hl-$exColor{background:${EXCERPT_CSS}40;}" +
            "\n.hl-$exColor.hl-focus{background:${EXCERPT_CSS}73;}" +
            "\n.exflash{$hlBorder:2px solid $EXCERPT_CSS;animation:exflashfade 1.6s ease-in;}" +
            "\n@keyframes exflashfade{0%{$hlBorderColor:transparent;}60%{$hlBorderColor:transparent;}100%{$hlBorderColor:$EXCERPT_CSS;}}"
        val exRange = if (flashExcerpt) flash else null
        val body = buildBody(page.ocrText.orEmpty(), page.highlights, colors, exRange)
        val startJs = when {
            flash == null -> SCROLL_TO_START_JS
            flashExcerpt -> scrollToRangeJs(flash)
            else -> flashJs(flash)
        }
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
              .flash{animation:flashfade 1.8s ease-out;}
              @keyframes flashfade{0%,40%{box-shadow:inset 0 0 0 100px #3C546855;}100%{box-shadow:inset 0 0 0 100px transparent;}}
              .hl-focus{transition:background 0.15s ease-out;}
              $swatches
              $excerptCss
            </style></head><body>$body$HIGHLIGHT_UPDATE_FN$startJs$selectJs$verticalScrollLock</body></html>
        """.trimIndent()
    }

    private fun buildBody(
        text: String,
        highlights: List<PageHighlight>,
        colors: Map<String, String>,
        exflashRange: IntRange? = null,
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
                val cls = spanClass(highlights, colors, i, exflashRange)
                sb.append("<span data-s=\"$i\" data-e=\"$endExclusive\"$cls>")
                sb.append("<ruby>${esc(base)}<rt>${esc(reading)}</rt></ruby>")
                sb.append("</span>")
                i = endExclusive
            } else {
                val cls = spanClass(highlights, colors, i, exflashRange)
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
            (c in '\u30A0'..'\u30FF') || // katakana
            (c in 'A'..'Z') || (c in 'a'..'z')
    }

    /**
     * Combined ` class="..."` for a span: the highlight/excerpt block class plus,
     * when [offset] is inside [exflashRange] (excerpt 查看原文 jump), the `exflash`
     * underline-fade class. Keeping the block class means the excerpt stays
     * tappable during the jump (so its action bar can be summoned).
     */
    private fun spanClass(
        highlights: List<PageHighlight>,
        colors: Map<String, String>,
        offset: Int,
        exflashRange: IntRange?,
    ): String {
        val names = mutableListOf<String>()
        highlightClassName(highlights, colors, offset)?.let(names::add)
        if (exflashRange != null && offset in exflashRange) names.add("exflash")
        return if (names.isEmpty()) "" else " class=\"${names.joinToString(" ")}\""
    }

    private fun highlightClassName(
        highlights: List<PageHighlight>,
        colors: Map<String, String>,
        offset: Int,
    ): String? {
        val hit = highlights.lastOrNull {
            offset >= it.start && offset < it.end &&
                (colors.containsKey(it.color) || it.color == PageHighlight.EXCERPT_COLOR)
        } ?: return null
        return "hl-${hit.color}"
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

    /**
     * Scrolls to the given range without any box-shadow flash. Used for excerpt
     * 查看原文 jumps: the excerpt spans already carry `.exflash` (a gray underline
     * that slowly fades in and holds, on top of the still-tappable block), so JS
     * only needs to bring them into view.
     */
    private fun scrollToRangeJs(range: IntRange) = """
        <script>
          window.addEventListener('load', function(){
            var spans = document.querySelectorAll('[data-s]');
            for(var i=0;i<spans.length;i++){
              var s = parseInt(spans[i].getAttribute('data-s'));
              if(s >= ${range.first} && s <= ${range.last}){
                var el = spans[i];
                setTimeout(function(){ el.scrollIntoView({inline:'center', block:'center'}); }, 50);
                break;
              }
            }
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

          function clearFocus(){
            var focused = document.querySelectorAll('.hl-focus');
            for(var i=0;i<focused.length;i++) focused[i].classList.remove('hl-focus');
          }

          document.addEventListener('selectionchange', function(){
            var sel = window.getSelection();
            if(sel && !sel.isCollapsed) {
              clearFocus();
              if(window.Android) Android.onHighlightDismissed();
            }
          });

          document.addEventListener('click', function(ev){
            var sel = window.getSelection();
            if(sel && !sel.isCollapsed) return;
            var el = ev.target;
            while(el && el !== document.body && !el.hasAttribute('data-s')) el = el.parentElement;
            if(!el || !el.hasAttribute('data-s')){
              clearFocus();
              if(window.Android) Android.onHighlightDismissed();
              return;
            }
            var cls = el.className || '';
            var m = cls.match(/hl-(\S+)/);
            if(!m){
              clearFocus();
              if(window.Android) Android.onHighlightDismissed();
              return;
            }
            var color = m[1];
            var clickS = parseInt(el.getAttribute('data-s'));
            var spans = document.querySelectorAll('[data-s]');
            var hlStart = -1, hlEnd = -1;
            for(var i=0;i<spans.length;i++){
              var sp = spans[i];
              if(sp.className.indexOf('hl-'+color) < 0) continue;
              var s=parseInt(sp.getAttribute('data-s'));
              var e=parseInt(sp.getAttribute('data-e'));
              if(hlStart < 0){ hlStart=s; hlEnd=e; }
              else if(s <= hlEnd){ if(e>hlEnd) hlEnd=e; }
              else {
                if(clickS >= hlStart && clickS < hlEnd) break;
                hlStart=s; hlEnd=e;
              }
            }
            if(clickS < hlStart || clickS >= hlEnd){ hlStart=clickS; hlEnd=parseInt(el.getAttribute('data-e')); }
            clearFocus();
            for(var i=0;i<spans.length;i++){
              var sp = spans[i];
              var s=parseInt(sp.getAttribute('data-s'));
              if(s >= hlStart && s < hlEnd && sp.className.indexOf('hl-'+color) >= 0){
                sp.classList.add('hl-focus');
              }
            }
            if(window.Android && hlEnd > hlStart) Android.onHighlightTap(hlStart, hlEnd, color);
          });
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
        val filtered = highlights.filter { colors.containsKey(it.color) || it.color == PageHighlight.EXCERPT_COLOR }
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

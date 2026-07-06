package com.readingnotes.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.readingnotes.app.model.Page

/** Selection reported from the WebView, in code-point offsets over frozen text. */
data class Selection(val start: Int, val end: Int)

/** A tap on an existing highlight, reported from JS. */
data class HighlightTap(val start: Int, val end: Int, val color: String)

private data class WebViewState(val contentKey: String, val html: String)

/**
 * WebView that hosts the OCR page and reports text selection back to Compose as
 * code-point offsets. The native copy/paste action bar is suppressed so only the
 * app's own selection toolbar shows.
 */
private class SelectionWebView : WebView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    /** When true (vertical/tategaki mode), vertical scrolling is clamped to 0. */
    var lockVerticalScroll = false

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (lockVerticalScroll && t != 0) scrollTo(l, 0)
    }

    override fun overScrollBy(
        deltaX: Int, deltaY: Int, scrollX: Int, scrollY: Int,
        scrollRangeX: Int, scrollRangeY: Int, maxOverScrollX: Int, maxOverScrollY: Int,
        isTouchEvent: Boolean,
    ): Boolean = super.overScrollBy(
        deltaX, if (lockVerticalScroll) 0 else deltaY, scrollX, if (lockVerticalScroll) 0 else scrollY,
        scrollRangeX, scrollRangeY, maxOverScrollX, if (lockVerticalScroll) 0 else maxOverScrollY,
        isTouchEvent,
    )

    // Keep the action mode alive (so the selection isn't immediately cleared)
    // but strip every menu item, hiding the native copy/paste bar. Returning
    // null here would make Android drop the selection right away.
    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(EmptyActionModeCallback(callback))

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(EmptyActionModeCallback(callback), type)
}

private class EmptyActionModeCallback(
    private val wrapped: ActionMode.Callback?,
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        wrapped?.onCreateActionMode(mode, menu)
        menu?.clear()
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        menu?.clear()
        return true
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false

    override fun onDestroyActionMode(mode: ActionMode?) {
        wrapped?.onDestroyActionMode(mode)
    }
}

private class SelectionBridge(
    val onSelection: (Selection?) -> Unit,
    val onHighlightTap: (HighlightTap) -> Unit = {},
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @JavascriptInterface
    fun onSelection(start: Int, end: Int) {
        main.post { onSelection(Selection(start, end)) }
    }

    @JavascriptInterface
    fun onSelectionCleared() {
        main.post { onSelection(null) }
    }

    @JavascriptInterface
    fun onHighlightTap(start: Int, end: Int, color: String) {
        main.post { onHighlightTap(HighlightTap(start, end, color)) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PageWebView(
    page: Page,
    colors: Map<String, String>,
    vertical: Boolean,
    interactive: Boolean,
    onSelectionChange: (Selection?) -> Unit,
    modifier: Modifier = Modifier,
    flash: IntRange? = null,
    onHighlightTap: (HighlightTap) -> Unit = {},
) {
    val currentOnSelection = rememberUpdatedState(onSelectionChange)
    val currentOnHighlightTap = rememberUpdatedState(onHighlightTap)
    val html = PageHtml.render(page, colors, vertical, interactive = interactive, flash = flash)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SelectionWebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true

                if (interactive) {
                    val bridge = SelectionBridge(
                        onSelection = { currentOnSelection.value(it) },
                        onHighlightTap = { currentOnHighlightTap.value(it) },
                    )
                    addJavascriptInterface(bridge, "Android")
                }
            }
        },
        update = { web ->
            web.lockVerticalScroll = vertical
            web.isVerticalScrollBarEnabled = !vertical
            // Structural key: everything that requires a full HTML reload.
            // Highlights are NOT included — they update via JS to preserve scroll.
            val contentKey = "${page.page}|${page.ocrText?.hashCode()}|$vertical|$interactive|$flash"
            val prev = web.tag as? WebViewState

            if (prev == null || prev.contentKey != contentKey) {
                // Structural change (page switch, OCR, orientation): full reload.
                web.tag = WebViewState(contentKey, html)
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            } else if (prev.html != html) {
                // Only highlights changed: patch DOM classes via JS, no scroll reset.
                web.tag = WebViewState(contentKey, html)
                web.evaluateJavascript(PageHtml.highlightUpdateJs(page.highlights, colors), null)
            }
            // else: nothing changed (e.g. selection update) — skip.
        },
    )
}

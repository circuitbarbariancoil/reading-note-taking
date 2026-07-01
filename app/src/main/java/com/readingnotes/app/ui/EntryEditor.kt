package com.readingnotes.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.readingnotes.app.model.Entry
import com.readingnotes.app.model.HighlightPalette
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Hairline
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Full-screen entry editor: two CodeMirror 6 editors (摘抄 + 批注) rendered from
 * a bundled web asset, giving Obsidian-style Live Preview and `#tag` / color
 * autocomplete. Content is read back on 完成 via a JS bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EntryEditor(
    entry: Entry,
    palette: HighlightPalette,
    knownTags: List<String>,
    onSave: (Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentEntry = rememberUpdatedState(entry)
    val currentOnSave = rememberUpdatedState(onSave)
    var webView by remember { mutableStateOf<WebView?>(null) }

    val configJson = remember(entry, palette, knownTags) {
        JSONObject().apply {
            put("excerpt", entry.text)
            put("annotation", entry.annotation)
            put("tags", JSONArray(entry.tags))
            put("knownTags", JSONArray(knownTags))
            put(
                "colors",
                JSONArray().apply {
                    palette.colors.forEach { put(JSONObject().put("name", it.name).put("css", it.css)) }
                },
            )
        }.toString()
    }

    // Full-screen overlay instead of Dialog — Dialog creates a separate window
    // that breaks IME focus for WebViews (keyboard won't appear).
    Column(
        modifier = Modifier.fillMaxSize().background(Paper),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", fontSize = 26.sp, color = SumiSoft, modifier = Modifier.clickable(onClick = onDismiss).padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("条目", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Sumi)
                Text("p.${entry.page}", fontSize = 12.sp, color = SumiSoft)
            }
            Text(
                "完成", fontSize = 15.sp, color = Accent,
                modifier = Modifier
                    .clickable { webView?.evaluateJavascript("window.RN && RN.collect();", null) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth().padding(0.dp).background(Hairline))

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.domStorageEnabled = true
                    val bridge = CollectBridge { excerpt, annotation, tags ->
                        currentOnSave.value(
                            currentEntry.value.copy(
                                text = excerpt,
                                annotation = annotation,
                                tags = tags,
                                updatedAt = Instant.now().toString(),
                            ),
                        )
                    }
                    addJavascriptInterface(bridge, "Android")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript("window.RN.init(${JSONObject.quote(configJson)});", null)
                        }
                    }
                    loadUrl("file:///android_asset/editor/index.html")
                    webView = this
                }
            },
        )
    }
}

private class CollectBridge(
    val onCollect: (excerpt: String, annotation: String, tags: List<String>) -> Unit,
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @JavascriptInterface
    fun onCollect(payload: String) {
        val obj = JSONObject(payload)
        val tagsArray = obj.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArray.length()).map { tagsArray.getString(it) }
        val excerpt = obj.optString("excerpt")
        val annotation = obj.optString("annotation")
        main.post { onCollect(excerpt, annotation, tags) }
    }
}

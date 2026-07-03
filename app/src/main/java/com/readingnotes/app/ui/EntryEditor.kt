package com.readingnotes.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onViewOriginal: () -> Unit = {},
) {
    val currentEntry = rememberUpdatedState(entry)
    val currentOnSave = rememberUpdatedState(onSave)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // Back: compare live editor content with the stored entry; only prompt when dirty.
    fun requestBack() {
        val web = webView
        if (web == null) {
            currentOnDismiss.value()
            return
        }
        web.evaluateJavascript("window.RN ? RN.snapshot() : null") { result ->
            val dirty = runCatching {
                if (result == null || result == "null") return@runCatching false
                val inner = JSONObject(org.json.JSONTokener(result).nextValue() as String)
                inner.optString("excerpt") != currentEntry.value.text ||
                    inner.optString("annotation") != currentEntry.value.annotation
            }.getOrDefault(false)
            if (dirty) confirmDiscard = true else currentOnDismiss.value()
        }
    }

    androidx.activity.compose.BackHandler(onBack = ::requestBack)

    val configJson = remember(entry, palette, knownTags) {
        JSONObject().apply {
            put("excerpt", entry.text)
            put("annotation", entry.annotation)
            put("tags", JSONArray(entry.tags))
            put("knownTags", JSONArray(knownTags))
            put(
                "colors",
                JSONArray().apply {
                    palette.activeColors().forEach { put(JSONObject().put("name", it.name).put("css", it.css)) }
                },
            )
        }.toString()
    }

    // Full-screen overlay instead of Dialog — Dialog creates a separate window
    // that breaks IME focus for WebViews (keyboard won't appear).
    Column(
        modifier = Modifier.fillMaxSize().background(Paper).imePadding().navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", fontSize = 26.sp, color = SumiSoft, modifier = Modifier.clickable(onClick = ::requestBack).padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("条目", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Sumi)
                Text("p.${entry.page}", fontSize = 12.sp, color = SumiSoft)
            }
            Text(
                "查看原文",
                fontSize = 13.sp,
                color = SumiSoft,
                modifier = Modifier.clickable(onClick = onViewOriginal).padding(horizontal = 8.dp, vertical = 4.dp),
            )
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

        // Native quick-syntax toolbar: lives above the IME (imePadding on the
        // parent Column), driving the WebView editors via the RN JS bridge.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEDE6D6))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            fun js(script: String) {
                webView?.evaluateJavascript(script, null)
            }
            // Color chips scroll; the fixed syntax chips stay pinned at the right.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                palette.activeColors().forEach { hc ->
                    val tint = runCatching { Color(android.graphics.Color.parseColor(hc.css)) }.getOrDefault(Accent)
                    ToolbarChip(
                        label = hc.name,
                        background = tint.copy(alpha = 0.13f),
                        border = tint,
                    ) { js("window.RN && RN.wrap(${JSONObject.quote("~={${hc.name}}")}, ${JSONObject.quote("=~")});") }
                }
            }
            ToolbarChip("《》") { js("window.RN && RN.wrap('\\u300A', '\\u300B');") }
            ToolbarChip("B", bold = true) { js("window.RN && RN.wrap('**', '**');") }
            ToolbarChip("#") { js("window.RN && RN.insertTag();") }
        }
    }

    if (confirmDiscard) {
        DiscardDialog(
            onSave = {
                confirmDiscard = false
                webView?.evaluateJavascript("window.RN && RN.collect();", null)
            },
            onDiscard = {
                confirmDiscard = false
                currentOnDismiss.value()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun DiscardDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = RoundedCornerShape(16.dp),
        title = { Text("未保存的修改", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Sumi) },
        text = { Text("保留这次编辑吗？", fontSize = 13.sp, color = SumiSoft) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onSave) { Text("保存", color = Accent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDiscard) { Text("放弃", color = SumiSoft) }
        },
    )
}

@Composable
private fun ToolbarChip(
    label: String,
    background: Color = Paper,
    border: Color = Hairline,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = Sumi,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
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
        val tags = (0 until tagsArray.length()).map { tagsArray.getString(it) }.distinct()
        val excerpt = obj.optString("excerpt")
        val annotation = obj.optString("annotation")
        main.post { onCollect(excerpt, annotation, tags) }
    }
}

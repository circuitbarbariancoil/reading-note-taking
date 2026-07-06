package com.readingnotes.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.readingnotes.app.model.Page
import com.readingnotes.app.model.PageHighlight

private val SAMPLE_PAGE = Page(
    page = 91,
    archiveImage = "pages/p0091_archive.webp",
    ocrText = "月のような遠い乳房には、すでに柏木の手が触れ、あの時\n" +
        "華美な振袖に包まれていた膝《ひざ》には、すでに柏木の手が触れた。",
    highlights = listOf(PageHighlight(id = "h1", start = 12, end = 19, color = "red")),
)

private val SAMPLE_COLORS = mapOf(
    "red" to "#e02020",
    "orange" to "#e07b20",
    "green" to "#1f9d3a",
    "cyan" to "#127a86",
)

@Composable
fun PagePreviewScreen() {
    var vertical by remember { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OCR 文本预览", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { vertical = !vertical }) {
                Text(if (vertical) "竖排" else "横排")
            }
        }
        VerticalAwareWebView(
            html = PageHtml.render(SAMPLE_PAGE, SAMPLE_COLORS, vertical),
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VerticalAwareWebView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
            }
        },
        update = { web ->
            web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
    )
}

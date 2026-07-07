package com.readingnotes.app.ocr

import org.json.JSONArray
import org.json.JSONObject

/** One parsed table-of-contents line from a 目录页 image. */
data class TocItem(
    val title: String,
    val page: Int,
    val level: Int = 1,
)

object TocExtraction {

    /**
     * Prompt asking the model to transcribe a book's table-of-contents page(s)
     * into a strict JSON array. The printed page numbers map directly to the
     * book's page numbers, so no offset math is needed here.
     */
    val PROMPT = """
        これは書籍の「目次（もくじ）」ページの画像です。目次に印刷されている項目を
        すべて忠実に読み取り、JSON 配列だけを出力してください。各要素は次の形式です:
        {"title": 章や節のタイトル, "page": そのページ番号(整数), "level": 階層(整数)}

        規則:
        1. title は目次に印刷された通りに写す（要約・翻訳・補完をしない）。
        2. page は目次の右側などに印刷されているページ番号を整数で入れる。ページ番号が
           読み取れない項目は出力しない。
        3. level は階層。最上位（部・篇など）を 1、その下の章を 2、節を 3 … とする。
           階層が一段階だけなら全て 1 でよい。字下げ・書体の大きさで階層を判断する。
        4. 目次に載っている順（ページ昇順）で並べる。
        5. 出力は JSON 配列のみ。前置き・説明・コードフェンス（```）を付けない。

        例: [{"title":"第一章 出発","page":7,"level":1},{"title":"一 朝","page":8,"level":2}]
    """.trimIndent()

    /**
     * Parses the model's raw response into [TocItem]s. Tolerates code fences and
     * leading/trailing prose by extracting the first JSON array in the text.
     */
    fun parse(raw: String): List<TocItem> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = JSONArray(raw.substring(start, end + 1))
        val out = mutableListOf<TocItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optString("title").trim()
            val page = obj.optInt("page", Int.MIN_VALUE)
            if (title.isEmpty() || page == Int.MIN_VALUE) continue
            val level = obj.optInt("level", 1).coerceIn(1, 6)
            out.add(TocItem(title = title, page = page, level = level))
        }
        return out.sortedBy { it.page }
    }
}

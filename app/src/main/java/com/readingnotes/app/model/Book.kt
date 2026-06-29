package com.readingnotes.app.model

import kotlinx.serialization.Serializable

/**
 * On-disk model for one book, serialized as `book.json` and owned solely by the
 * app (single direction: app -> Dropbox -> Obsidian). See DESIGN.md.
 *
 * Offsets ([PageHighlight.start]/[end], [Entry.srcStart]/[srcEnd]) are measured
 * in Unicode code points over the frozen [Page.ocrText], NOT UTF-16 units or
 * bytes. Use [com.readingnotes.app.model.CodePoints] helpers everywhere.
 */
@Serializable
data class Book(
    val schema: Int = 1,
    val uid: String,
    val title: String,
    val author: String = "",
    val isbn: String? = null,
    val coverUrl: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val dropboxRoot: String,
    val pages: List<Page> = emptyList(),
    val entries: List<Entry> = emptyList(),
)

@Serializable
data class Page(
    val page: Int,
    val archiveImage: String,
    val ocrText: String,
    val ocrModel: String? = null,
    val ocrCapturedAt: String? = null,
    val proofread: Boolean = false,
    val highlights: List<PageHighlight> = emptyList(),
)

/** A color mark drawn directly on the frozen OCR text (display layer). */
@Serializable
data class PageHighlight(
    val id: String,
    val start: Int,
    val end: Int,
    val color: String,
)

enum class EntryKind { highlight, excerpt }

/**
 * An extracted card: an independent editable copy of a span of OCR text.
 * Evolves independently of the frozen source (no back-projection).
 */
@Serializable
data class Entry(
    val id: String,
    val page: Int,
    val srcStart: Int,
    val srcEnd: Int,
    val text: String,
    val kind: EntryKind = EntryKind.highlight,
    val annotation: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

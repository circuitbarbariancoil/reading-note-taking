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
    val schema: Int = 2,
    val uid: String,
    val title: String,
    val author: String = "",
    val isbn: String? = null,
    val coverPath: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val dropboxRoot: String,
    val pages: List<Page> = emptyList(),
    val captures: List<Capture> = emptyList(),
    val entries: List<Entry> = emptyList(),
)

/**
 * An unprocessed photo: just an image, not yet a page (no OCR, no page number).
 * Becomes a [Page] after OCR or manual page number assignment.
 */
@Serializable
data class Capture(
    val id: String,
    val imagePath: String,
    val capturedAt: String,
)

/**
 * A processed page: has a page number (manual or OCR-extracted) and optionally
 * OCR text. A page without ocrText can only display the archive image.
 */
@Serializable
data class Page(
    val page: Int,
    val archiveImage: String? = null,
    val ocrText: String? = null,
    val ocrModel: String? = null,
    val ocrCapturedAt: String? = null,
    val addedAt: String = "",
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

package com.readingnotes.app.ui

import com.readingnotes.app.model.Section

/**
 * Shared helpers for laying out the table of contents over the page list and
 * for resolving which chapter a page belongs to.
 */
object TocLayout {

    /**
     * The coarsest (top-most) sections used to group the page list. If the TOC
     * has multiple levels, only the smallest `level` value becomes group headers
     * (one band of headers); sub-sections stay for navigation only.
     */
    fun groupingSections(sections: List<Section>): List<Section> {
        if (sections.isEmpty()) return emptyList()
        val top = sections.minOf { it.level }
        return sections.filter { it.level == top }.sortedBy { it.startPage }
    }

    /**
     * The grouping section a given page number falls under, or null when the
     * page precedes the first section (卷首/未分章).
     */
    fun groupFor(sections: List<Section>, page: Int): Section? =
        groupingSections(sections).lastOrNull { it.startPage <= page }

    /**
     * The most specific (deepest) section covering a page — used to show the
     * current chapter name in the workbench. Null when before the first section.
     */
    fun sectionFor(sections: List<Section>, page: Int): Section? =
        sections.filter { it.startPage <= page }.maxByOrNull { it.startPage }
}

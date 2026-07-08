import type ReadingNotesPlugin from "./main";
import { renderMarkupText } from "./markup";

/**
 * Fills an entry chip element (used by both the reading-view post-processor and
 * the Live Preview widget): page badge + a snippet of the copy + 📷/📖 buttons.
 */
export function fillAnchorChip(plugin: ReadingNotesPlugin, chip: HTMLElement, entryId: string, bookUid?: string): void {
  chip.addClass("rn-anchor");
  if (!bookUid) {
    chip.addClass("rn-anchor-warn");
    chip.setText("⚠️ 笔记缺少 app_book frontmatter");
    return;
  }
  chip.setText("…");
  void plugin
    .getBook(bookUid)
    .then((book) => {
      const entry = book.entries.find((e) => e.id === entryId);
      chip.empty();
      if (!entry) {
        chip.addClass("rn-anchor-warn");
        chip.setText("⚠️ 源已在 App 删除");
        return;
      }
      chip.createSpan({ cls: "rn-anchor-page", text: entry.page != null ? `p.${entry.page}` : "条目" });
      if (entry.page != null) {
        const imgBtn = chip.createEl("button", { cls: "rn-anchor-btn", text: "📷 页图" });
        imgBtn.addEventListener("click", (ev) => {
          ev.preventDefault();
          void plugin.showPageImage(book, entry.page!);
        });
        const jumpBtn = chip.createEl("button", { cls: "rn-anchor-btn", text: "📖 原文" });
        jumpBtn.addEventListener("click", (ev) => {
          ev.preventDefault();
          void plugin.openAppView({ bookUid, page: entry.page! });
        });
      }
    })
    .catch((e: Error) => {
      chip.addClass("rn-anchor-warn");
      chip.setText(`⚠️ ${e.message}`);
    });
}

export function renderMarkupInline(plugin: ReadingNotesPlugin, el: HTMLElement, text: string): void {
  renderMarkupText(el, text, plugin.palette);
}

import { setIcon, setTooltip } from "obsidian";
import type ReadingNotesPlugin from "./main";

/**
 * Fills an entry chip element (used by both the reading-view post-processor and
 * the Live Preview widget). Deliberately quiet: a small muted page label plus
 * two ghost icon buttons (page image / jump to source) that brighten on hover,
 * so the chip reads as a caption line above the copy rather than a control bar.
 */
export function fillAnchorChip(plugin: ReadingNotesPlugin, chip: HTMLElement, entryId: string, bookUid?: string): void {
  chip.addClass("rn-anchor");
  // Swallow mouse-downs so clicking the chip never moves the editor caret
  // (which would collapse the Live Preview widget back to raw syntax).
  chip.addEventListener("mousedown", (ev) => {
    ev.preventDefault();
    ev.stopPropagation();
  });
  if (!bookUid) {
    chip.addClass("rn-anchor-warn");
    chip.setText("⚠ 笔记缺少 app_book frontmatter");
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
        chip.setText("⚠ 源已在 App 删除");
        return;
      }
      chip.createSpan({ cls: "rn-anchor-page", text: entry.page != null ? `p.${entry.page}` : "条目" });
      if (entry.page != null) {
        iconButton(chip, "image", "查看页图", () => void plugin.showPageImage(book, entry.page!));
        iconButton(chip, "book-open", "在阅读视图打开原文", () =>
          void plugin.openAppView({ bookUid, page: entry.page!, highlightId: entry.highlight_id ?? undefined }),
        );
      }
    })
    .catch((e: Error) => {
      chip.addClass("rn-anchor-warn");
      chip.setText(`⚠ ${e.message}`);
    });
}

function iconButton(parent: HTMLElement, icon: string, tooltip: string, onClick: () => void): void {
  const btn = parent.createEl("button", { cls: "rn-anchor-btn" });
  setIcon(btn, icon);
  setTooltip(btn, tooltip);
  btn.addEventListener("click", (ev) => {
    ev.preventDefault();
    ev.stopPropagation();
    onClick();
  });
}

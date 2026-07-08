import { ItemView, Notice, WorkspaceLeaf } from "obsidian";
import { renderPageText } from "./markup";
import type ReadingNotesPlugin from "./main";
import { Book } from "./types";

export const APP_VIEW_TYPE = "reading-notes-app-view";

interface NavTarget {
  bookUid: string;
  page?: number;
}

/**
 * Minimal in-tab reader (the "A view"): bookshelf → book (toc + pages) → one
 * page with its archive image (fetched live via temporary link) and the frozen
 * OCR text with highlights applied by code-point offsets.
 */
export class ReadingAppView extends ItemView {
  private book: Book | null = null;
  private currentPage: number | null = null;
  private pending: NavTarget | null = null;

  constructor(
    leaf: WorkspaceLeaf,
    private plugin: ReadingNotesPlugin,
  ) {
    super(leaf);
  }

  getViewType(): string {
    return APP_VIEW_TYPE;
  }

  getDisplayText(): string {
    return this.book ? `📖 ${this.book.title}` : "读书笔记";
  }

  getIcon(): string {
    return "book-open";
  }

  async onOpen(): Promise<void> {
    if (this.pending) {
      const t = this.pending;
      this.pending = null;
      await this.openBookPage(t.bookUid, t.page);
    } else {
      await this.renderShelf();
    }
  }

  async navigateTo(target: NavTarget): Promise<void> {
    if (!this.contentEl.isShown()) {
      this.pending = target;
      return;
    }
    await this.openBookPage(target.bookUid, target.page);
  }

  private root(): HTMLElement {
    const el = this.contentEl;
    el.empty();
    el.addClass("rn-app-view");
    return el;
  }

  private async renderShelf(): Promise<void> {
    const el = this.root();
    el.createEl("h2", { text: "书架" });
    const list = el.createDiv({ cls: "rn-shelf" });
    list.createDiv({ text: "加载中…" });
    try {
      const uids = await this.plugin.client().listBookUids();
      list.empty();
      if (uids.length === 0) list.createDiv({ text: "Dropbox 上还没有书。" });
      for (const uid of uids) {
        const row = list.createDiv({ cls: "rn-shelf-row" });
        row.setText(uid);
        row.addEventListener("click", () => void this.openBookPage(uid));
        void this.plugin
          .getBook(uid)
          .then((b) => row.setText(`${b.title}${b.author ? ` · ${b.author}` : ""}（${b.pages.length} 页 / ${b.entries.length} 条）`))
          .catch(() => {});
      }
    } catch (e) {
      list.empty();
      list.createDiv({ text: `加载失败：${(e as Error).message}` });
    }
  }

  private async openBookPage(uid: string, page?: number): Promise<void> {
    const el = this.root();
    el.createDiv({ text: "加载中…" });
    try {
      this.book = await this.plugin.getBook(uid, true);
    } catch (e) {
      new Notice(`读取 book.json 失败：${(e as Error).message}`);
      return;
    }
    const book = this.book;
    this.currentPage = page ?? book.pages[0]?.page ?? null;
    el.empty();

    const bar = el.createDiv({ cls: "rn-bar" });
    const back = bar.createEl("button", { text: "← 书架" });
    back.addEventListener("click", () => void this.renderShelf());
    bar.createSpan({ cls: "rn-bar-title", text: `${book.title}${book.author ? ` · ${book.author}` : ""}` });

    const layout = el.createDiv({ cls: "rn-layout" });
    const side = layout.createDiv({ cls: "rn-side" });
    const main = layout.createDiv({ cls: "rn-main" });

    side.createEl("h4", { text: "目录" });
    if (book.sections.length === 0) side.createDiv({ cls: "rn-dim", text: "（无目录）" });
    for (const s of [...book.sections].sort((a, b) => a.start_page - b.start_page)) {
      const item = side.createDiv({ cls: "rn-toc-item" });
      item.style.paddingLeft = `${(s.level - 1) * 14}px`;
      item.setText(`${s.title} · p.${s.start_page}`);
      item.addEventListener("click", () => void this.showPage(main, this.nearestPage(s.start_page)));
    }
    side.createEl("h4", { text: "页" });
    const pageList = side.createDiv({ cls: "rn-pages" });
    for (const p of [...book.pages].sort((a, b) => a.page - b.page)) {
      const item = pageList.createDiv({ cls: "rn-toc-item" });
      item.setText(`p.${p.page}${p.highlights.length > 0 ? ` · ${p.highlights.length} 处` : ""}`);
      item.addEventListener("click", () => void this.showPage(main, p.page));
    }

    if (this.currentPage != null) await this.showPage(main, this.currentPage);
    else main.createDiv({ cls: "rn-dim", text: "这本书还没有页。" });
  }

  /** Closest existing page ≥ [target] (sections may start on unphotographed pages). */
  private nearestPage(target: number): number {
    const pages = (this.book?.pages ?? []).map((p) => p.page).sort((a, b) => a - b);
    for (const p of pages) if (p >= target) return p;
    return pages[pages.length - 1] ?? target;
  }

  private async showPage(main: HTMLElement, pageNum: number): Promise<void> {
    const book = this.book;
    if (!book) return;
    const page = book.pages.find((p) => p.page === pageNum);
    this.currentPage = pageNum;
    main.empty();
    if (!page) {
      main.createDiv({ cls: "rn-dim", text: `没有 p.${pageNum} 的数据。` });
      return;
    }
    const nav = main.createDiv({ cls: "rn-bar" });
    const sorted = [...book.pages].sort((a, b) => a.page - b.page);
    const idx = sorted.findIndex((p) => p.page === pageNum);
    const prev = nav.createEl("button", { text: "◀" });
    prev.disabled = idx <= 0;
    prev.addEventListener("click", () => void this.showPage(main, sorted[idx - 1].page));
    nav.createSpan({ cls: "rn-bar-title", text: `p.${pageNum}` });
    const next = nav.createEl("button", { text: "▶" });
    next.disabled = idx < 0 || idx >= sorted.length - 1;
    next.addEventListener("click", () => void this.showPage(main, sorted[idx + 1].page));

    if (page.archive_image) {
      const imgWrap = main.createDiv({ cls: "rn-img-wrap" });
      imgWrap.createDiv({ cls: "rn-dim", text: "页图加载中…" });
      try {
        const link = await this.plugin.client().temporaryLink(`${book.dropbox_root}/${page.archive_image}`);
        imgWrap.empty();
        const img = imgWrap.createEl("img", { cls: "rn-page-img" });
        img.src = link;
      } catch (e) {
        imgWrap.empty();
        imgWrap.createDiv({ cls: "rn-dim", text: `页图加载失败：${(e as Error).message}` });
      }
    }

    const textEl = main.createDiv({ cls: "rn-page-text" });
    if (page.ocr_text) {
      renderPageText(textEl, page, this.plugin.palette);
    } else {
      textEl.createDiv({ cls: "rn-dim", text: "（本页没有 OCR 原文）" });
    }
  }
}

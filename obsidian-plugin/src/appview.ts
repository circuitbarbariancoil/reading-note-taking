import { ItemView, Notice, WorkspaceLeaf, setIcon } from "obsidian";
import { displayAnnotation } from "./booknote";
import { renderMarkupText, renderPageText, stripRuby } from "./markup";
import type ReadingNotesPlugin from "./main";
import { Book, Entry } from "./types";

export const APP_VIEW_TYPE = "reading-notes-app-view";

interface NavTarget {
  bookUid: string;
  page?: number;
}

type BookTab = "read" | "toc" | "entries";

/**
 * In-tab reader (the "A view"): bookshelf (cover card grid) → book workspace
 * with a header bar, 阅读/目录/条目 tabs, in-book search, page image fetched
 * live via temporary link, and the frozen OCR text with highlights applied by
 * code-point offsets (horizontal / vertical toggle).
 */
export class ReadingAppView extends ItemView {
  private book: Book | null = null;
  private currentPage: number | null = null;
  private tab: BookTab = "read";
  private vertical = false;
  private pageMode: "text" | "image" = "text";
  private searchQuery = "";
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
      await this.openBook(t.bookUid, t.page);
    } else {
      await this.renderShelf();
    }
  }

  async navigateTo(target: NavTarget): Promise<void> {
    if (!this.contentEl.isShown()) {
      this.pending = target;
      return;
    }
    await this.openBook(target.bookUid, target.page);
  }

  private root(): HTMLElement {
    const el = this.contentEl;
    el.empty();
    el.addClass("rn-app-view");
    return el;
  }

  // ── bookshelf ────────────────────────────────────────────────────────────

  private async renderShelf(): Promise<void> {
    this.book = null;
    const el = this.root();
    const header = el.createDiv({ cls: "rn-header" });
    header.createDiv({ cls: "rn-header-title", text: "书架" });
    const refresh = header.createEl("button", { cls: "rn-btn", text: "刷新" });

    const grid = el.createDiv({ cls: "rn-grid" });
    const load = async () => {
      grid.empty();
      const skeleton = grid.createDiv({ cls: "rn-dim", text: "加载中…" });
      try {
        const uids = await this.plugin.client().listBookUids();
        skeleton.remove();
        if (uids.length === 0) grid.createDiv({ cls: "rn-dim", text: "Dropbox 上还没有书。" });
        await Promise.all(uids.map((uid) => this.renderShelfCard(grid, uid)));
      } catch (e) {
        skeleton.remove();
        grid.createDiv({ cls: "rn-dim", text: `加载失败：${(e as Error).message}` });
      }
    };
    refresh.addEventListener("click", () => void load());
    await load();
  }

  private async renderShelfCard(grid: HTMLElement, uid: string): Promise<void> {
    const card = grid.createDiv({ cls: "rn-card" });
    const cover = card.createDiv({ cls: "rn-cover" });
    const meta = card.createDiv({ cls: "rn-card-meta" });
    meta.createDiv({ cls: "rn-card-title", text: uid });
    card.addEventListener("click", () => void this.openBook(uid));
    try {
      const book = await this.plugin.getBook(uid);
      cover.setText(book.title.slice(0, 2));
      if (book.cover_path) {
        void this.plugin
          .client()
          .temporaryLink(`${book.dropbox_root}/${book.cover_path}`)
          .then((link) => {
            cover.empty();
            const img = cover.createEl("img");
            img.src = link;
          })
          .catch(() => {});
      }
      meta.empty();
      meta.createDiv({ cls: "rn-card-title", text: book.title });
      if (book.author) meta.createDiv({ cls: "rn-card-author", text: book.author });
      const stats = meta.createDiv({ cls: "rn-card-stats" });
      stats.createSpan({ text: `${book.pages.length} 页` });
      stats.createSpan({ text: `${book.entries.length} 条` });
    } catch (_e) {
      meta.createDiv({ cls: "rn-dim", text: "读取失败" });
    }
  }

  // ── book workspace ───────────────────────────────────────────────────────

  private async openBook(uid: string, page?: number): Promise<void> {
    const el = this.root();
    el.createDiv({ cls: "rn-dim", text: "加载中…" });
    try {
      this.book = await this.plugin.getBook(uid, true);
    } catch (e) {
      new Notice(`读取 book.json 失败：${(e as Error).message}`);
      return;
    }
    this.currentPage = page ?? this.book.pages[0]?.page ?? null;
    this.tab = "read";
    this.searchQuery = "";
    this.renderBook();
  }

  private renderBook(): void {
    const book = this.book;
    if (!book) return;
    const el = this.root();

    const header = el.createDiv({ cls: "rn-header" });
    const back = header.createEl("button", { cls: "rn-btn rn-btn-icon" });
    setIcon(back, "arrow-left");
    back.addEventListener("click", () => void this.renderShelf());
    const titleBox = header.createDiv({ cls: "rn-header-book" });
    titleBox.createDiv({ cls: "rn-header-title", text: book.title });
    titleBox.createDiv({
      cls: "rn-header-sub",
      text: `${book.author ? book.author + " · " : ""}${book.pages.length} 页 · ${book.entries.length} 条`,
    });

    const search = header.createEl("input", { cls: "rn-search", type: "search" });
    search.placeholder = "搜索原文 / 条目…";
    search.value = this.searchQuery;
    search.addEventListener("input", () => {
      this.searchQuery = search.value;
      this.renderMain(main);
    });

    const tabs = el.createDiv({ cls: "rn-tabs" });
    const tabDefs: { id: BookTab; label: string }[] = [
      { id: "read", label: "阅读" },
      { id: "toc", label: "目录" },
      { id: "entries", label: `条目 ${book.entries.length}` },
    ];
    for (const t of tabDefs) {
      const btn = tabs.createEl("button", { cls: "rn-tab", text: t.label });
      if (t.id === this.tab) btn.addClass("rn-tab-active");
      btn.addEventListener("click", () => {
        this.tab = t.id;
        this.searchQuery = "";
        search.value = "";
        this.renderBook();
      });
    }

    const main = el.createDiv({ cls: "rn-book-main" });
    this.renderMain(main);
  }

  private renderMain(main: HTMLElement): void {
    main.empty();
    if (this.searchQuery.trim()) {
      this.renderSearch(main, this.searchQuery.trim());
      return;
    }
    if (this.tab === "read") this.renderRead(main);
    else if (this.tab === "toc") this.renderToc(main);
    else this.renderEntries(main);
  }

  // ── 阅读 tab ─────────────────────────────────────────────────────────────

  private renderRead(main: HTMLElement): void {
    const book = this.book!;
    const layout = main.createDiv({ cls: "rn-layout" });
    const side = layout.createDiv({ cls: "rn-side" });
    const content = layout.createDiv({ cls: "rn-main" });

    side.createDiv({ cls: "rn-side-heading", text: "页" });
    const pageList = side.createDiv({ cls: "rn-pages" });
    const sorted = [...book.pages].sort((a, b) => a.page - b.page);
    for (const p of sorted) {
      const item = pageList.createDiv({ cls: "rn-page-item" });
      if (p.page === this.currentPage) item.addClass("rn-page-item-active");
      item.createSpan({ text: `p.${p.page}` });
      if (p.highlights.length > 0) item.createSpan({ cls: "rn-badge", text: `${p.highlights.length}` });
      item.addEventListener("click", () => {
        this.currentPage = p.page;
        this.renderBook();
      });
    }

    if (this.currentPage == null) {
      content.createDiv({ cls: "rn-dim", text: "这本书还没有页。" });
      return;
    }
    void this.renderPage(content, this.currentPage);
  }

  private async renderPage(content: HTMLElement, pageNum: number): Promise<void> {
    const book = this.book!;
    const page = book.pages.find((p) => p.page === pageNum);
    content.empty();
    if (!page) {
      content.createDiv({ cls: "rn-dim", text: `没有 p.${pageNum} 的数据。` });
      return;
    }

    if (this.pageMode === "image" && !page.archive_image) this.pageMode = "text";
    const reader = content.createDiv({ cls: "rn-reader" });

    const nav = reader.createDiv({ cls: "rn-page-nav" });
    const sorted = [...book.pages].sort((a, b) => a.page - b.page);
    const idx = sorted.findIndex((p) => p.page === pageNum);
    const prev = nav.createEl("button", { cls: "rn-btn rn-btn-icon" });
    setIcon(prev, "chevron-left");
    prev.disabled = idx <= 0;
    prev.addEventListener("click", () => {
      this.currentPage = sorted[idx - 1].page;
      this.renderBook();
    });
    const label = nav.createDiv({ cls: "rn-page-label" });
    label.createSpan({ cls: "rn-page-num", text: `p.${pageNum}` });
    const section = this.sectionFor(pageNum);
    if (section) label.createSpan({ cls: "rn-page-section", text: section });
    const next = nav.createEl("button", { cls: "rn-btn rn-btn-icon" });
    setIcon(next, "chevron-right");
    next.disabled = idx < 0 || idx >= sorted.length - 1;
    next.addEventListener("click", () => {
      this.currentPage = sorted[idx + 1].page;
      this.renderBook();
    });
    nav.createDiv({ cls: "rn-spacer" });

    const seg = nav.createDiv({ cls: "rn-seg" });
    const segBtn = (mode: "text" | "image", text: string) => {
      const b = seg.createEl("button", { cls: "rn-seg-btn", text });
      if (this.pageMode === mode) b.addClass("rn-seg-active");
      b.disabled = mode === "image" && !page.archive_image;
      b.addEventListener("click", () => {
        this.pageMode = mode;
        void this.renderPage(content, pageNum);
      });
    };
    segBtn("text", "原文");
    segBtn("image", "页图");

    if (this.pageMode === "text") {
      const dirBtn = nav.createEl("button", { cls: "rn-btn rn-btn-quiet", text: this.vertical ? "竖排" : "横排" });
      dirBtn.addEventListener("click", () => {
        this.vertical = !this.vertical;
        void this.renderPage(content, pageNum);
      });
    }

    if (this.pageMode === "image") {
      const imgWrap = reader.createDiv({ cls: "rn-img-wrap" });
      const ph = imgWrap.createDiv({ cls: "rn-dim", text: "页图加载中…" });
      this.plugin
        .client()
        .temporaryLink(`${book.dropbox_root}/${page.archive_image}`)
        .then((link) => {
          ph.remove();
          const img = imgWrap.createEl("img", { cls: "rn-page-img" });
          img.src = link;
          img.addEventListener("click", () => void this.plugin.showPageImage(book, pageNum));
        })
        .catch((e: Error) => ph.setText(`页图加载失败：${e.message}`));
    } else {
      const textEl = reader.createDiv({ cls: "rn-page-text" });
      if (this.vertical) textEl.addClass("rn-vertical");
      if (page.ocr_text) {
        renderPageText(textEl, page, this.plugin.palette);
      } else {
        textEl.createDiv({ cls: "rn-dim", text: "（本页没有 OCR 原文）" });
      }
    }

    const pageEntries = book.entries.filter((e) => e.page === pageNum);
    if (pageEntries.length > 0) {
      const details = reader.createEl("details", { cls: "rn-page-entries" });
      details.createEl("summary", { text: `本页条目 ${pageEntries.length}` });
      const list = details.createDiv();
      for (const e of pageEntries) this.renderEntryCard(list, e, false);
    }
  }

  private sectionFor(pageNum: number): string | null {
    const sections = [...(this.book?.sections ?? [])].sort((a, b) => a.start_page - b.start_page);
    let current: string | null = null;
    for (const s of sections) {
      if (s.start_page <= pageNum) current = s.title;
      else break;
    }
    return current;
  }

  // ── 目录 tab ─────────────────────────────────────────────────────────────

  private renderToc(main: HTMLElement): void {
    const book = this.book!;
    const wrap = main.createDiv({ cls: "rn-toc" });
    if (book.sections.length === 0) {
      wrap.createDiv({ cls: "rn-dim", text: "这本书还没有目录。" });
      return;
    }
    for (const s of [...book.sections].sort((a, b) => a.start_page - b.start_page)) {
      const item = wrap.createDiv({ cls: "rn-toc-item" });
      item.style.paddingLeft = `${8 + (s.level - 1) * 22}px`;
      if (s.level === 1) item.addClass("rn-toc-l1");
      item.createSpan({ cls: "rn-toc-title", text: s.title });
      item.createSpan({ cls: "rn-toc-page", text: `p.${s.start_page}` });
      item.addEventListener("click", () => {
        this.currentPage = this.nearestPage(s.start_page);
        this.tab = "read";
        this.renderBook();
      });
    }
  }

  private nearestPage(target: number): number {
    const pages = (this.book?.pages ?? []).map((p) => p.page).sort((a, b) => a - b);
    for (const p of pages) if (p >= target) return p;
    return pages[pages.length - 1] ?? target;
  }

  // ── 条目 tab ─────────────────────────────────────────────────────────────

  private renderEntries(main: HTMLElement): void {
    const book = this.book!;
    const wrap = main.createDiv({ cls: "rn-entries" });
    if (book.entries.length === 0) {
      wrap.createDiv({ cls: "rn-dim", text: "还没有条目。" });
      return;
    }
    const sorted = [...book.entries].sort((a, b) => (a.page ?? 1e9) - (b.page ?? 1e9) || a.src_start - b.src_start);
    for (const e of sorted) this.renderEntryCard(wrap, e, true);
  }

  private renderEntryCard(wrap: HTMLElement, entry: Entry, jumpable: boolean): void {
    const card = wrap.createDiv({ cls: "rn-entry-card" });
    const head = card.createDiv({ cls: "rn-entry-head" });
    head.createSpan({ cls: "rn-entry-kind", text: entry.kind === "excerpt" ? "摘录" : entry.kind === "note" ? "笔记" : "高亮" });
    if (entry.page != null) head.createSpan({ cls: "rn-entry-page", text: `p.${entry.page}` });
    const body = card.createDiv({ cls: "rn-entry-text" });
    renderMarkupText(body, entry.text, this.plugin.palette);
    const annotation = displayAnnotation(entry);
    if (annotation) card.createDiv({ cls: "rn-entry-annot", text: `💬 ${annotation}` });
    if (entry.tags.length > 0) {
      const tagsEl = card.createDiv({ cls: "rn-entry-tags" });
      for (const t of entry.tags) tagsEl.createSpan({ cls: "rn-tag", text: t.startsWith("#") ? t : `#${t}` });
    }
    if (jumpable && entry.page != null) {
      card.addEventListener("click", () => {
        this.currentPage = entry.page!;
        this.tab = "read";
        this.renderBook();
      });
      card.addClass("rn-clickable");
    }
  }

  // ── search ───────────────────────────────────────────────────────────────

  private renderSearch(main: HTMLElement, query: string): void {
    const book = this.book!;
    const q = query.toLowerCase();
    const wrap = main.createDiv({ cls: "rn-entries" });

    const entryHits = book.entries.filter(
      (e) =>
        stripRuby(e.text).toLowerCase().includes(q) ||
        e.annotation.toLowerCase().includes(q) ||
        e.tags.some((t) => t.toLowerCase().includes(q)),
    );
    const pageHits = book.pages.filter((p) => (p.ocr_text ?? "").toLowerCase().includes(q));

    wrap.createDiv({ cls: "rn-side-heading", text: `条目 ${entryHits.length}` });
    for (const e of entryHits) this.renderEntryCard(wrap, e, true);

    wrap.createDiv({ cls: "rn-side-heading", text: `原文页 ${pageHits.length}` });
    for (const p of pageHits) {
      const card = wrap.createDiv({ cls: "rn-entry-card rn-clickable" });
      const head = card.createDiv({ cls: "rn-entry-head" });
      head.createSpan({ cls: "rn-entry-page", text: `p.${p.page}` });
      const text = p.ocr_text ?? "";
      const at = text.toLowerCase().indexOf(q);
      const from = Math.max(0, at - 30);
      card.createDiv({ cls: "rn-entry-text", text: `…${text.slice(from, at + query.length + 50)}…` });
      card.addEventListener("click", () => {
        this.currentPage = p.page;
        this.tab = "read";
        this.searchQuery = "";
        this.renderBook();
      });
    }
  }
}

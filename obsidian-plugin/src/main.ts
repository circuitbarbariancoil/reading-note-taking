import {
  App,
  Modal,
  Notice,
  Plugin,
  PluginSettingTab,
  Setting,
  SuggestModal,
  WorkspaceLeaf,
} from "obsidian";
import { APP_VIEW_TYPE, ReadingAppView } from "./appview";
import { ANCHOR_INLINE_RE, syncBookNote } from "./booknote";
import {
  authorizeUrl,
  DropboxClient,
  exchangeCode,
  makePkceVerifier,
  pkceChallenge,
} from "./dropbox";
import { renderMarkupText } from "./markup";
import { Book, DEFAULT_PALETTE, HighlightPalette } from "./types";

interface ReadingNotesSettings {
  appKey: string;
  refreshToken: string;
  notesFolder: string;
}

const DEFAULT_SETTINGS: ReadingNotesSettings = {
  // Same Dropbox app as the Android app (DropboxConfig.APP_KEY).
  appKey: "tju8txxd67454n0",
  refreshToken: "",
  notesFolder: "ReadingNotes",
};

export default class ReadingNotesPlugin extends Plugin {
  settings: ReadingNotesSettings = DEFAULT_SETTINGS;
  palette: HighlightPalette = DEFAULT_PALETTE;
  private _client: DropboxClient | null = null;
  private bookCache = new Map<string, Book>();

  async onload(): Promise<void> {
    await this.loadSettings();
    this.addSettingTab(new ReadingNotesSettingTab(this.app, this));

    this.registerView(APP_VIEW_TYPE, (leaf) => new ReadingAppView(leaf, this));

    this.addCommand({
      id: "open-app-view",
      name: "打开阅读视图（书架）",
      callback: () => void this.openAppView(),
    });

    this.addCommand({
      id: "sync-book-notes",
      name: "同步书笔记（append-only）",
      callback: () => void this.syncAllBookNotes(),
    });

    this.registerMarkdownPostProcessor((el, ctx) => {
      for (const code of Array.from(el.querySelectorAll("code"))) {
        const m = code.textContent?.match(ANCHOR_INLINE_RE);
        if (!m) continue;
        const fm = this.app.metadataCache.getCache(ctx.sourcePath)?.frontmatter;
        const bookUid = fm?.app_book as string | undefined;
        this.decorateAnchor(code, m[1], bookUid);
      }
      this.renderInlineHighlights(el);
    });

    void this.refreshPalette();
  }

  client(): DropboxClient {
    if (!this.settings.refreshToken) throw new Error("未连接 Dropbox：请在插件设置里完成授权。");
    if (!this._client) this._client = new DropboxClient(this.settings.appKey, this.settings.refreshToken);
    return this._client;
  }

  resetClient(): void {
    this._client = null;
    this.bookCache.clear();
  }

  async getBook(uid: string, fresh = false): Promise<Book> {
    if (!fresh) {
      const cached = this.bookCache.get(uid);
      if (cached) return cached;
    }
    const book = await this.client().fetchBook(uid);
    this.bookCache.set(uid, book);
    return book;
  }

  async refreshPalette(): Promise<void> {
    if (!this.settings.refreshToken) return;
    try {
      this.palette = await this.client().fetchPalette();
    } catch (_e) {
      /* keep current */
    }
  }

  async openAppView(target?: { bookUid: string; page?: number }): Promise<void> {
    let leaf: WorkspaceLeaf | null = this.app.workspace.getLeavesOfType(APP_VIEW_TYPE)[0] ?? null;
    if (!leaf) {
      leaf = this.app.workspace.getLeaf("tab");
      await leaf.setViewState({ type: APP_VIEW_TYPE, active: true });
    }
    this.app.workspace.revealLeaf(leaf);
    const view = leaf.view;
    if (view instanceof ReadingAppView && target) await view.navigateTo(target);
  }

  async syncAllBookNotes(): Promise<void> {
    try {
      await this.refreshPalette();
      const uids = await this.client().listBookUids();
      let created = 0;
      let added = 0;
      for (const uid of uids) {
        const book = await this.getBook(uid, true);
        const result = await syncBookNote(this.app, book, this.settings.notesFolder);
        if (result.created) created++;
        added += result.added;
      }
      new Notice(`书笔记同步完成：${uids.length} 本书，新建 ${created} 篇，追加 ${added} 条。`);
    } catch (e) {
      new Notice(`同步失败：${(e as Error).message}`);
    }
  }

  /** Replaces an `app:<id>` inline code with an entry chip (page + buttons). */
  private decorateAnchor(code: HTMLElement, entryId: string, bookUid?: string): void {
    const chip = createSpan({ cls: "rn-anchor" });
    code.replaceWith(chip);
    if (!bookUid) {
      chip.addClass("rn-anchor-warn");
      chip.setText("⚠️ 笔记缺少 app_book frontmatter");
      return;
    }
    chip.setText("…");
    void this.getBook(bookUid)
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
          imgBtn.addEventListener("click", () => void this.showPageImage(book, entry.page!));
          const jumpBtn = chip.createEl("button", { cls: "rn-anchor-btn", text: "📖 原文" });
          jumpBtn.addEventListener("click", () => void this.openAppView({ bookUid, page: entry.page! }));
        }
      })
      .catch((e: Error) => {
        chip.addClass("rn-anchor-warn");
        chip.setText(`⚠️ ${e.message}`);
      });
  }

  private async showPageImage(book: Book, pageNum: number): Promise<void> {
    const page = book.pages.find((p) => p.page === pageNum);
    if (!page?.archive_image) {
      new Notice(`p.${pageNum} 没有页图。`);
      return;
    }
    try {
      const link = await this.client().temporaryLink(`${book.dropbox_root}/${page.archive_image}`);
      new PageImageModal(this.app, `${book.title} · p.${pageNum}`, link).open();
    } catch (e) {
      new Notice(`页图加载失败：${(e as Error).message}`);
    }
  }

  /** Renders `~={color}…=~` markup inside reading view text (e.g. entry quotes). */
  private renderInlineHighlights(el: HTMLElement): void {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    const targets: Text[] = [];
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      const t = n as Text;
      if (t.nodeValue && t.nodeValue.includes("~={")) targets.push(t);
    }
    for (const t of targets) {
      const span = createSpan();
      renderMarkupText(span, t.nodeValue ?? "", this.palette);
      t.replaceWith(span);
    }
  }

  async loadSettings(): Promise<void> {
    this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
  }

  async saveSettings(): Promise<void> {
    await this.saveData(this.settings);
  }
}

class PageImageModal extends Modal {
  constructor(
    app: App,
    private title: string,
    private link: string,
  ) {
    super(app);
  }

  onOpen(): void {
    this.titleEl.setText(this.title);
    this.contentEl.addClass("rn-img-modal");
    const img = this.contentEl.createEl("img");
    img.src = this.link;
  }
}

class ConnectDropboxModal extends Modal {
  private verifier = makePkceVerifier();

  constructor(
    app: App,
    private plugin: ReadingNotesPlugin,
    private onDone: () => void,
  ) {
    super(app);
  }

  async onOpen(): Promise<void> {
    this.titleEl.setText("连接 Dropbox");
    const { contentEl } = this;
    const challenge = await pkceChallenge(this.verifier);
    const url = authorizeUrl(this.plugin.settings.appKey, challenge);
    contentEl.createEl("p", { text: "1. 打开下面的链接授权，Dropbox 会给你一个代码：" });
    const a = contentEl.createEl("a", { text: url, href: url });
    a.setAttr("target", "_blank");
    contentEl.createEl("p", { text: "2. 把代码粘贴到这里：" });
    const input = contentEl.createEl("input", { type: "text" });
    input.style.width = "100%";
    const btn = contentEl.createEl("button", { text: "完成授权" });
    btn.style.marginTop = "8px";
    btn.addEventListener("click", async () => {
      try {
        const token = await exchangeCode(this.plugin.settings.appKey, input.value, this.verifier);
        this.plugin.settings.refreshToken = token;
        await this.plugin.saveSettings();
        this.plugin.resetClient();
        new Notice("Dropbox 已连接。");
        this.close();
        this.onDone();
      } catch (e) {
        new Notice((e as Error).message);
      }
    });
  }
}

class BookSuggestModal extends SuggestModal<{ uid: string; label: string }> {
  constructor(
    app: App,
    private items: { uid: string; label: string }[],
    private onPick: (uid: string) => void,
  ) {
    super(app);
  }

  getSuggestions(query: string): { uid: string; label: string }[] {
    const q = query.toLowerCase();
    return this.items.filter((i) => i.label.toLowerCase().includes(q));
  }

  renderSuggestion(item: { uid: string; label: string }, el: HTMLElement): void {
    el.setText(item.label);
  }

  onChooseSuggestion(item: { uid: string; label: string }): void {
    this.onPick(item.uid);
  }
}

class ReadingNotesSettingTab extends PluginSettingTab {
  constructor(
    app: App,
    private plugin: ReadingNotesPlugin,
  ) {
    super(app, plugin);
  }

  display(): void {
    const { containerEl } = this;
    containerEl.empty();

    new Setting(containerEl)
      .setName("Dropbox")
      .setDesc(this.plugin.settings.refreshToken ? "已连接。" : "未连接。")
      .addButton((b) =>
        b.setButtonText(this.plugin.settings.refreshToken ? "重新授权" : "连接").onClick(() => {
          new ConnectDropboxModal(this.app, this.plugin, () => this.display()).open();
        }),
      )
      .addButton((b) =>
        b.setButtonText("断开").onClick(async () => {
          this.plugin.settings.refreshToken = "";
          await this.plugin.saveSettings();
          this.plugin.resetClient();
          this.display();
        }),
      );

    new Setting(containerEl)
      .setName("书笔记文件夹")
      .setDesc("新建书笔记放进 vault 的这个文件夹（已有笔记按 frontmatter app_book 定位，移动改名不受影响）。")
      .addText((t) =>
        t.setValue(this.plugin.settings.notesFolder).onChange(async (v) => {
          this.plugin.settings.notesFolder = v.trim();
          await this.plugin.saveSettings();
        }),
      );

    new Setting(containerEl)
      .setName("同步一本书")
      .setDesc("只新建/追加同步选中的一本书的笔记。")
      .addButton((b) =>
        b.setButtonText("选书同步").onClick(async () => {
          try {
            const uids = await this.plugin.client().listBookUids();
            const items = await Promise.all(
              uids.map(async (uid) => {
                try {
                  const book = await this.plugin.getBook(uid);
                  return { uid, label: `${book.title}（${book.entries.length} 条）` };
                } catch (_e) {
                  return { uid, label: uid };
                }
              }),
            );
            new BookSuggestModal(this.app, items, (uid) => {
              void (async () => {
                const book = await this.plugin.getBook(uid, true);
                const r = await syncBookNote(this.app, book, this.plugin.settings.notesFolder);
                new Notice(r.created ? `已新建《${book.title}》笔记（${r.added} 条）。` : `《${book.title}》追加 ${r.added} 条。`);
              })();
            }).open();
          } catch (e) {
            new Notice((e as Error).message);
          }
        }),
      );
  }
}

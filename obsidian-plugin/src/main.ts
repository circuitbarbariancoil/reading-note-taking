import {
  App,
  Modal,
  Notice,
  Plugin,
  PluginSettingTab,
  Setting,
  SuggestModal,
  WorkspaceLeaf,
  setIcon,
} from "obsidian";
import { fillAnchorChip } from "./anchorchip";
import { APP_VIEW_TYPE, ReadingAppView } from "./appview";
import { ANCHOR_INLINE_RE, syncBookNote } from "./booknote";
import { Lightbox, LightboxPage } from "./lightbox";
import { livePreviewExtension } from "./livepreview";
import {
  authorizeUrl,
  DropboxClient,
  exchangeCode,
  makePkceVerifier,
  pkceChallenge,
} from "./dropbox";
import { renderMarkupText } from "./markup";
import { BooksClient, MockClient } from "./mockclient";
import { Book, DEFAULT_PALETTE, HighlightPalette, isNotebook } from "./types";

export type EntrySortMode = "book" | "newest" | "oldest";

interface ReadingNotesSettings {
  appKey: string;
  refreshToken: string;
  notesFolder: string;
  renderHighlightColors: boolean;
  syncOnStartup: boolean;
  /** 0 = off. */
  syncIntervalMinutes: number;
  defaultVertical: boolean;
  entrySort: EntrySortMode;
  /** ISO timestamp of the last successful sync; "" = never. */
  lastSyncAt: string;
  lastSyncSummary: string;
}

const DEFAULT_SETTINGS: ReadingNotesSettings = {
  // Same Dropbox app as the Android app (DropboxConfig.APP_KEY).
  appKey: "tju8txxd67454n0",
  refreshToken: "",
  notesFolder: "ReadingNotes",
  renderHighlightColors: true,
  syncOnStartup: false,
  syncIntervalMinutes: 0,
  defaultVertical: false,
  entrySort: "book",
  lastSyncAt: "",
  lastSyncSummary: "",
};

export default class ReadingNotesPlugin extends Plugin {
  settings: ReadingNotesSettings = DEFAULT_SETTINGS;
  palette: HighlightPalette = DEFAULT_PALETTE;
  private _client: BooksClient | null = null;
  private bookCache = new Map<string, Book>();
  private syncTimer: number | null = null;

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
        const chip = createSpan();
        code.replaceWith(chip);
        fillAnchorChip(this, chip, m[1], bookUid);
      }
      if (this.settings.renderHighlightColors) this.renderInlineHighlights(el);
    });

    this.registerEditorExtension(livePreviewExtension(this));

    void this.refreshPalette();

    this.app.workspace.onLayoutReady(() => {
      if (this.settings.syncOnStartup && this.settings.refreshToken) void this.syncAllBookNotes(true);
      this.applySyncInterval();
    });
  }

  onunload(): void {
    if (this.syncTimer != null) window.clearInterval(this.syncTimer);
  }

  /** (Re)schedules the periodic background sync from the current settings. */
  applySyncInterval(): void {
    if (this.syncTimer != null) {
      window.clearInterval(this.syncTimer);
      this.syncTimer = null;
    }
    const minutes = this.settings.syncIntervalMinutes;
    if (minutes <= 0) return;
    this.syncTimer = window.setInterval(() => {
      if (this.settings.refreshToken) void this.syncAllBookNotes(true);
    }, minutes * 60_000);
    this.registerInterval(this.syncTimer);
  }

  cachedBookCount(): number {
    return this.bookCache.size;
  }

  clearBookCache(): void {
    this.bookCache.clear();
  }

  client(): BooksClient {
    if (!this.settings.refreshToken) throw new Error("未连接 Dropbox：请在插件设置里完成授权。");
    if (!this._client) {
      this._client = this.settings.refreshToken.startsWith("mock:")
        ? new MockClient(this.app, this.settings.refreshToken.slice(5))
        : new DropboxClient(this.settings.appKey, this.settings.refreshToken);
    }
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

  async openAppView(target?: { bookUid: string; page?: number; highlightId?: string }): Promise<void> {
    let leaf: WorkspaceLeaf | null = this.app.workspace.getLeavesOfType(APP_VIEW_TYPE)[0] ?? null;
    if (!leaf) {
      leaf = this.app.workspace.getLeaf("tab");
      await leaf.setViewState({ type: APP_VIEW_TYPE, active: true });
    }
    this.app.workspace.revealLeaf(leaf);
    const view = leaf.view;
    if (view instanceof ReadingAppView && target) await view.navigateTo(target);
  }

  async syncAllBookNotes(quiet = false): Promise<void> {
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
      const summary = `${uids.length} 本书，新建 ${created} 篇，追加 ${added} 条`;
      this.settings.lastSyncAt = new Date().toISOString();
      this.settings.lastSyncSummary = summary;
      await this.saveSettings();
      if (!quiet || added > 0 || created > 0) new Notice(`书笔记同步完成：${summary}。`);
    } catch (e) {
      this.settings.lastSyncSummary = `失败：${(e as Error).message}`;
      await this.saveSettings();
      if (!quiet) new Notice(`同步失败：${(e as Error).message}`);
    }
  }

  /**
   * Opens the zoomable page-image viewer. With [paging] the viewer can step
   * through every page that has a scan (A view); inline chips pass false.
   */
  showPageImage(book: Book, pageNum: number, paging = false): void {
    const page = book.pages.find((p) => p.page === pageNum);
    if (!page?.archive_image) {
      new Notice(`p.${pageNum} 没有页图。`);
      return;
    }
    const withImages = paging ? book.pages.filter((p) => p.archive_image) : [page];
    const pages: LightboxPage[] = [...withImages]
      .sort((a, b) => a.page - b.page)
      .map((p) => ({
        page: p.page,
        load: () => this.client().temporaryLink(`${book.dropbox_root}/${p.archive_image}`),
      }));
    new Lightbox(this.app, {
      titleBase: book.title,
      pages,
      startPage: pageNum,
      paging: paging && pages.length > 1,
    }).open();
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
    contentEl.addClass("rn-connect");
    const challenge = await pkceChallenge(this.verifier);
    const url = authorizeUrl(this.plugin.settings.appKey, challenge);

    const step1 = contentEl.createDiv({ cls: "rn-connect-step" });
    step1.createDiv({ cls: "rn-connect-step-num", text: "1" });
    const s1body = step1.createDiv({ cls: "rn-connect-step-body" });
    s1body.createDiv({ text: "打开 Dropbox 授权页，登录后会得到一串授权码。" });
    const openBtn = s1body.createEl("button", { cls: "mod-cta rn-connect-open" });
    setIcon(openBtn.createSpan({ cls: "rn-btn-ico" }), "external-link");
    openBtn.createSpan({ text: "打开授权页" });
    openBtn.addEventListener("click", () => window.open(url, "_blank"));

    const step2 = contentEl.createDiv({ cls: "rn-connect-step" });
    step2.createDiv({ cls: "rn-connect-step-num", text: "2" });
    const s2body = step2.createDiv({ cls: "rn-connect-step-body" });
    s2body.createDiv({ text: "把授权码粘贴到这里：" });
    const inputRow = s2body.createDiv({ cls: "rn-connect-input-row" });
    const input = inputRow.createEl("input", { cls: "rn-connect-input", type: "text" });
    input.placeholder = "授权码";
    const pasteBtn = inputRow.createEl("button", { cls: "rn-connect-paste" });
    setIcon(pasteBtn, "clipboard-paste");
    pasteBtn.addEventListener("click", async () => {
      try {
        input.value = (await navigator.clipboard.readText()).trim();
        input.dispatchEvent(new Event("input"));
      } catch (_e) {
        /* clipboard unavailable */
      }
    });

    const errorEl = contentEl.createDiv({ cls: "rn-connect-error" });
    errorEl.hide();

    const footer = contentEl.createDiv({ cls: "rn-connect-footer" });
    const submit = footer.createEl("button", { cls: "mod-cta", text: "完成授权" });
    submit.disabled = true;
    input.addEventListener("input", () => {
      submit.disabled = input.value.trim().length === 0;
    });
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !submit.disabled) submit.click();
    });

    submit.addEventListener("click", async () => {
      submit.disabled = true;
      submit.setText("授权中…");
      errorEl.hide();
      try {
        const token = await exchangeCode(this.plugin.settings.appKey, input.value, this.verifier);
        this.plugin.settings.refreshToken = token;
        await this.plugin.saveSettings();
        this.plugin.resetClient();
        new Notice("Dropbox 已连接。");
        this.close();
        this.onDone();
      } catch (e) {
        errorEl.setText((e as Error).message);
        errorEl.show();
        submit.setText("完成授权");
        submit.disabled = false;
      }
    });
  }
}

interface BookPickItem {
  uid: string;
  title: string;
  sub: string;
  notebook: boolean;
}

class BookSuggestModal extends SuggestModal<BookPickItem> {
  constructor(
    app: App,
    private items: BookPickItem[],
    private onPick: (uid: string) => void,
  ) {
    super(app);
    this.setPlaceholder("选一本书同步…");
  }

  getSuggestions(query: string): BookPickItem[] {
    const q = query.toLowerCase();
    return this.items.filter((i) => i.title.toLowerCase().includes(q) || i.uid.toLowerCase().includes(q));
  }

  renderSuggestion(item: BookPickItem, el: HTMLElement): void {
    el.addClass("mod-complex");
    const icon = el.createDiv({ cls: "rn-pick-icon" });
    setIcon(icon, item.notebook ? "notebook-pen" : "book");
    const content = el.createDiv({ cls: "suggestion-content" });
    content.createDiv({ cls: "suggestion-title", text: item.title });
    if (item.sub) content.createDiv({ cls: "suggestion-note", text: item.sub });
  }

  onChooseSuggestion(item: BookPickItem): void {
    this.onPick(item.uid);
  }
}

const SYNC_INTERVALS: Record<string, string> = {
  "0": "关闭",
  "15": "每 15 分钟",
  "30": "每 30 分钟",
  "60": "每小时",
};

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

    // ── 连接 ────────────────────────────────────────────────────────────
    new Setting(containerEl).setName("连接").setHeading();

    const connected = !!this.plugin.settings.refreshToken;
    new Setting(containerEl)
      .setName("Dropbox")
      .setDesc(connected ? "已连接 · 数据根目录 /ReadingVault" : "未连接。授权后插件才能读取书和条目。")
      .addButton((b) =>
        b.setButtonText(connected ? "重新授权" : "连接").onClick(() => {
          new ConnectDropboxModal(this.app, this.plugin, () => this.display()).open();
        }),
      )
      .addButton((b) => {
        b.setButtonText("断开")
          .setDisabled(!connected)
          .onClick(async () => {
            this.plugin.settings.refreshToken = "";
            await this.plugin.saveSettings();
            this.plugin.resetClient();
            this.display();
          });
      });

    // ── 同步 ────────────────────────────────────────────────────────────
    new Setting(containerEl).setName("同步").setHeading();

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
      .setName("启动时同步")
      .setDesc("打开 Obsidian 后自动做一次 append-only 同步。")
      .addToggle((t) =>
        t.setValue(this.plugin.settings.syncOnStartup).onChange(async (v) => {
          this.plugin.settings.syncOnStartup = v;
          await this.plugin.saveSettings();
        }),
      );

    new Setting(containerEl)
      .setName("定时同步")
      .setDesc("按固定间隔在后台自动同步（有新增才提示）。")
      .addDropdown((d) =>
        d
          .addOptions(SYNC_INTERVALS)
          .setValue(String(this.plugin.settings.syncIntervalMinutes))
          .onChange(async (v) => {
            this.plugin.settings.syncIntervalMinutes = Number(v);
            await this.plugin.saveSettings();
            this.plugin.applySyncInterval();
          }),
      );

    const lastAt = this.plugin.settings.lastSyncAt;
    const statusDesc = lastAt
      ? `${new Date(lastAt).toLocaleString()} · ${this.plugin.settings.lastSyncSummary}`
      : this.plugin.settings.lastSyncSummary || "还没同步过。";
    new Setting(containerEl)
      .setName("上次同步")
      .setDesc(statusDesc)
      .addButton((b) =>
        b.setButtonText("立即全部同步").onClick(async () => {
          b.setDisabled(true).setButtonText("同步中…");
          await this.plugin.syncAllBookNotes();
          this.display();
        }),
      )
      .addButton((b) =>
        b.setButtonText("选书同步").onClick(async () => {
          try {
            const uids = await this.plugin.client().listBookUids();
            const items: BookPickItem[] = await Promise.all(
              uids.map(async (uid): Promise<BookPickItem> => {
                const notebook = isNotebook(uid);
                try {
                  const book = await this.plugin.getBook(uid);
                  const bits = [
                    book.author,
                    notebook ? null : `${book.pages.length} 页`,
                    `${book.entries.length} 条`,
                  ].filter(Boolean);
                  return { uid, title: book.title, sub: bits.join(" · "), notebook };
                } catch (_e) {
                  return { uid, title: uid, sub: "读取失败", notebook };
                }
              }),
            );
            items.sort((a, b) => Number(b.notebook) - Number(a.notebook));
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

    // ── 阅读视图 ─────────────────────────────────────────────────────────
    new Setting(containerEl).setName("阅读视图").setHeading();

    new Setting(containerEl)
      .setName("原文默认竖排")
      .setDesc("阅读 tab 打开原文时的默认排版方向（可随时在页内切换）。")
      .addToggle((t) =>
        t.setValue(this.plugin.settings.defaultVertical).onChange(async (v) => {
          this.plugin.settings.defaultVertical = v;
          await this.plugin.saveSettings();
        }),
      );

    new Setting(containerEl)
      .setName("条目默认排序")
      .setDesc("条目 tab 的初始排序方式（可随时在 tab 内切换）。")
      .addDropdown((d) =>
        d
          .addOptions({ book: "书序（页码）", newest: "时间 新→旧", oldest: "时间 旧→新" })
          .setValue(this.plugin.settings.entrySort)
          .onChange(async (v) => {
            this.plugin.settings.entrySort = v as EntrySortMode;
            await this.plugin.saveSettings();
          }),
      );

    new Setting(containerEl)
      .setName("渲染高亮颜色")
      .setDesc("给 ~={色}文字=~ 上色。若你用自己的渲染插件处理这套语法，可关闭本插件的上色，避免重复渲染（锚行芯片不受影响）。")
      .addToggle((t) =>
        t.setValue(this.plugin.settings.renderHighlightColors).onChange(async (v) => {
          this.plugin.settings.renderHighlightColors = v;
          await this.plugin.saveSettings();
          this.app.workspace.updateOptions();
        }),
      );

    const paletteSetting = new Setting(containerEl)
      .setName("高亮色板")
      .setDesc("从 Dropbox 共享配置读取，与 App 一致（在 App 里编辑）。")
      .addButton((b) =>
        b.setButtonText("刷新").onClick(async () => {
          await this.plugin.refreshPalette();
          this.display();
        }),
      );
    const swatches = paletteSetting.descEl.createDiv({ cls: "rn-palette-preview" });
    for (const c of this.plugin.palette.colors) {
      const chip = swatches.createSpan({ cls: "rn-palette-chip" });
      if (!c.active) chip.addClass("rn-palette-chip-off");
      chip.createSpan({ cls: "rn-palette-dot" }).style.background = c.css;
      chip.createSpan({ text: c.name });
    }

    // ── 高级 ────────────────────────────────────────────────────────────
    new Setting(containerEl).setName("高级").setHeading();

    new Setting(containerEl)
      .setName("缓存")
      .setDesc(`内存中缓存了 ${this.plugin.cachedBookCount()} 本书的 book.json。数据看起来不对时可清空重取。`)
      .addButton((b) =>
        b.setButtonText("清空缓存").onClick(() => {
          this.plugin.clearBookCache();
          new Notice("缓存已清空。");
          this.display();
        }),
      );
  }
}

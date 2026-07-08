import { App, Modal, setIcon, setTooltip } from "obsidian";
import { ZoomPanController } from "./zoompan";

export interface LightboxPage {
  page: number;
  /** Lazily resolves the (temporary) image URL for this page. */
  load: () => Promise<string>;
}

export interface LightboxOptions {
  titleBase: string;
  pages: LightboxPage[];
  startPage: number;
  /** Allow ←/→ paging across [pages]; single-page callers pass false. */
  paging: boolean;
}

/**
 * Fullscreen zoom/pan image viewer. Zoom with Ctrl+wheel or the ± buttons,
 * drag to pan, double-click to toggle fit ↔ 2×, optional ←/→ paging,
 * Esc / backdrop to close. Zoom/pan mechanics live in {@link ZoomPanController}.
 */
export class Lightbox extends Modal {
  private stage!: HTMLElement;
  private img!: HTMLImageElement;
  private placeholder!: HTMLElement;
  private indicator!: HTMLElement;
  private zoom!: ZoomPanController;

  private index: number;
  private linkCache = new Map<number, string>();

  constructor(
    app: App,
    private opts: LightboxOptions,
  ) {
    super(app);
    const at = opts.pages.findIndex((p) => p.page === opts.startPage);
    this.index = at === -1 ? 0 : at;
  }

  onOpen(): void {
    this.modalEl.addClass("rn-lightbox-modal");
    this.contentEl.addClass("rn-lightbox");
    this.contentEl.empty();

    const bar = this.contentEl.createDiv({ cls: "rn-lb-bar" });
    this.indicator = bar.createDiv({ cls: "rn-lb-indicator" });
    const spacer = bar.createDiv({ cls: "rn-lb-spacer" });
    spacer.style.flex = "1";
    bar.createSpan({ cls: "rn-lb-hint", text: "滚轮平移 · Ctrl+滚轮缩放 · 拖拽" });
    this.toolBtn(bar, "zoom-out", "缩小", () => this.zoom.zoomBy(1 / 1.25));
    this.toolBtn(bar, "maximize", "适应窗口 (0)", () => this.zoom.fit());
    this.toolBtn(bar, "zoom-in", "放大", () => this.zoom.zoomBy(1.25));
    this.toolBtn(bar, "x", "关闭 (Esc)", () => this.close());

    this.stage = this.contentEl.createDiv({ cls: "rn-lb-stage" });
    this.placeholder = this.stage.createDiv({ cls: "rn-lb-loading", text: "加载中…" });
    this.img = this.stage.createEl("img", { cls: "rn-lb-img" });
    this.img.hide();
    this.zoom = new ZoomPanController(this.stage, this.img, {
      onBackdropClick: () => this.close(),
    });

    if (this.opts.paging && this.opts.pages.length > 1) {
      const prev = this.stage.createEl("button", { cls: "rn-lb-nav rn-lb-prev" });
      setIcon(prev, "chevron-left");
      prev.onclick = (e) => {
        e.stopPropagation();
        this.go(-1);
      };
      const next = this.stage.createEl("button", { cls: "rn-lb-nav rn-lb-next" });
      setIcon(next, "chevron-right");
      next.onclick = (e) => {
        e.stopPropagation();
        this.go(1);
      };
    }

    this.scope.register([], "ArrowLeft", () => this.opts.paging && this.go(-1));
    this.scope.register([], "ArrowRight", () => this.opts.paging && this.go(1));
    this.scope.register([], "0", () => this.zoom.fit());
    this.scope.register([], "=", () => this.zoom.zoomBy(1.25));
    this.scope.register([], "-", () => this.zoom.zoomBy(1 / 1.25));

    void this.loadCurrent();
  }

  private toolBtn(bar: HTMLElement, icon: string, tip: string, onClick: () => void): void {
    const b = bar.createEl("button", { cls: "rn-lb-btn" });
    setIcon(b, icon);
    setTooltip(b, tip);
    b.onclick = onClick;
  }

  private async loadCurrent(): Promise<void> {
    const p = this.opts.pages[this.index];
    if (!p) return;
    this.updateIndicator();
    this.img.hide();
    this.placeholder.show();
    this.placeholder.setText("加载中…");
    try {
      let link = this.linkCache.get(p.page);
      if (!link) {
        link = await p.load();
        this.linkCache.set(p.page, link);
      }
      if (this.opts.pages[this.index].page !== p.page) return; // paged away while loading
      await this.setImage(link);
    } catch (e) {
      this.placeholder.setText(`加载失败：${(e as Error).message}`);
    }
  }

  private setImage(src: string): Promise<void> {
    return new Promise((resolve) => {
      this.img.onload = () => {
        this.placeholder.hide();
        this.img.show();
        this.zoom.fit();
        resolve();
      };
      this.img.onerror = () => {
        this.placeholder.setText("图片解码失败");
        resolve();
      };
      this.img.src = src;
    });
  }

  private go(delta: number): void {
    const next = this.index + delta;
    if (next < 0 || next >= this.opts.pages.length) return;
    this.index = next;
    void this.loadCurrent();
  }

  private updateIndicator(): void {
    const p = this.opts.pages[this.index];
    const pos = this.opts.paging ? ` · ${this.index + 1}/${this.opts.pages.length}` : "";
    this.indicator.setText(`${this.opts.titleBase} · p.${p.page}${pos}`);
  }

  onClose(): void {
    this.zoom?.destroy();
    this.contentEl.empty();
  }
}

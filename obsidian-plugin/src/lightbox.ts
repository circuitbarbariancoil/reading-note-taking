import { App, Modal, setIcon, setTooltip } from "obsidian";

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

const MIN_FACTOR = 0.8;
const MAX_SCALE = 8;

/**
 * Self-contained zoom/pan image viewer (no third-party deps): wheel zoom
 * centred on the cursor, drag to pan, double-click to toggle fit ↔ 2×,
 * ± / fit buttons, optional ←/→ paging, Esc / backdrop to close.
 */
export class Lightbox extends Modal {
  private stage!: HTMLElement;
  private img!: HTMLImageElement;
  private placeholder!: HTMLElement;
  private indicator!: HTMLElement;

  private index: number;
  private linkCache = new Map<number, string>();

  private scale = 1;
  private fitScale = 1;
  private tx = 0;
  private ty = 0;
  private dragging = false;
  private dragMoved = false;
  private lastX = 0;
  private lastY = 0;

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
    this.toolBtn(bar, "zoom-out", "缩小", () => this.zoomBy(1 / 1.25));
    this.toolBtn(bar, "maximize", "适应窗口", () => this.fit());
    this.toolBtn(bar, "zoom-in", "放大", () => this.zoomBy(1.25));
    this.toolBtn(bar, "x", "关闭 (Esc)", () => this.close());

    this.stage = this.contentEl.createDiv({ cls: "rn-lb-stage" });
    this.placeholder = this.stage.createDiv({ cls: "rn-lb-loading", text: "加载中…" });
    this.img = this.stage.createEl("img", { cls: "rn-lb-img" });
    this.img.hide();

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

    this.registerEvents();
    void this.loadCurrent();
  }

  private toolBtn(bar: HTMLElement, icon: string, tip: string, onClick: () => void): void {
    const b = bar.createEl("button", { cls: "rn-lb-btn" });
    setIcon(b, icon);
    setTooltip(b, tip);
    b.onclick = onClick;
  }

  private registerEvents(): void {
    this.stage.addEventListener("wheel", (e) => this.onWheel(e), { passive: false });
    this.stage.addEventListener("pointerdown", (e) => this.onPointerDown(e));
    this.stage.addEventListener("pointermove", (e) => this.onPointerMove(e));
    this.stage.addEventListener("pointerup", (e) => this.onPointerUp(e));
    this.stage.addEventListener("pointercancel", () => (this.dragging = false));
    this.stage.addEventListener("dblclick", (e) => this.onDoubleClick(e));
    // Clicking the empty backdrop (not the image) closes.
    this.stage.addEventListener("click", (e) => {
      if (e.target === this.stage && !this.dragMoved) this.close();
    });
    this.scope.register([], "ArrowLeft", () => this.opts.paging && this.go(-1));
    this.scope.register([], "ArrowRight", () => this.opts.paging && this.go(1));
    this.scope.register([], "0", () => this.fit());
    this.scope.register([], "=", () => this.zoomBy(1.25));
    this.scope.register([], "-", () => this.zoomBy(1 / 1.25));
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
      const shown = this.opts.pages[this.index];
      if (shown.page !== p.page) return; // paged away while loading
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
        this.fit();
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

  // ── transform ──────────────────────────────────────────────────────────

  private fit(): void {
    const sw = this.stage.clientWidth;
    const sh = this.stage.clientHeight;
    const nw = this.img.naturalWidth || 1;
    const nh = this.img.naturalHeight || 1;
    this.fitScale = Math.min(sw / nw, sh / nh);
    this.scale = this.fitScale;
    this.tx = (sw - nw * this.scale) / 2;
    this.ty = (sh - nh * this.scale) / 2;
    this.apply();
  }

  private zoomAt(newScale: number, cx: number, cy: number): void {
    const min = this.fitScale * MIN_FACTOR;
    const max = Math.max(this.fitScale, 1) * MAX_SCALE;
    const clamped = Math.min(max, Math.max(min, newScale));
    const k = clamped / this.scale;
    this.tx = cx - k * (cx - this.tx);
    this.ty = cy - k * (cy - this.ty);
    this.scale = clamped;
    this.apply();
  }

  private zoomBy(factor: number): void {
    this.zoomAt(this.scale * factor, this.stage.clientWidth / 2, this.stage.clientHeight / 2);
  }

  private onWheel(e: WheelEvent): void {
    e.preventDefault();
    const rect = this.stage.getBoundingClientRect();
    const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
    this.zoomAt(this.scale * factor, e.clientX - rect.left, e.clientY - rect.top);
  }

  private onDoubleClick(e: MouseEvent): void {
    const rect = this.stage.getBoundingClientRect();
    const cx = e.clientX - rect.left;
    const cy = e.clientY - rect.top;
    const atFit = Math.abs(this.scale - this.fitScale) < 0.01;
    this.zoomAt(atFit ? Math.max(this.fitScale * 2, 1) : this.fitScale, cx, cy);
  }

  private onPointerDown(e: PointerEvent): void {
    if (e.button !== 0) return;
    // Let clicks on the overlay buttons through — capturing the pointer here
    // would steal their click and fall through to the backdrop-close handler.
    if ((e.target as HTMLElement).closest("button")) return;
    this.dragging = true;
    this.dragMoved = false;
    this.lastX = e.clientX;
    this.lastY = e.clientY;
    this.stage.setPointerCapture(e.pointerId);
    this.stage.addClass("rn-lb-grabbing");
  }

  private onPointerMove(e: PointerEvent): void {
    if (!this.dragging) return;
    const dx = e.clientX - this.lastX;
    const dy = e.clientY - this.lastY;
    if (Math.abs(dx) + Math.abs(dy) > 2) this.dragMoved = true;
    this.lastX = e.clientX;
    this.lastY = e.clientY;
    this.tx += dx;
    this.ty += dy;
    this.apply();
  }

  private onPointerUp(e: PointerEvent): void {
    this.dragging = false;
    this.stage.removeClass("rn-lb-grabbing");
    if (this.stage.hasPointerCapture(e.pointerId)) this.stage.releasePointerCapture(e.pointerId);
  }

  /** Keep the image from drifting entirely off the stage. */
  private apply(): void {
    const sw = this.stage.clientWidth;
    const sh = this.stage.clientHeight;
    const w = (this.img.naturalWidth || 1) * this.scale;
    const h = (this.img.naturalHeight || 1) * this.scale;
    this.tx = w <= sw ? (sw - w) / 2 : Math.min(0, Math.max(sw - w, this.tx));
    this.ty = h <= sh ? (sh - h) / 2 : Math.min(0, Math.max(sh - h, this.ty));
    this.img.style.transform = `translate(${this.tx}px, ${this.ty}px) scale(${this.scale})`;
    this.stage.toggleClass("rn-lb-zoomed", this.scale > this.fitScale + 0.01);
  }

  onClose(): void {
    this.contentEl.empty();
  }
}

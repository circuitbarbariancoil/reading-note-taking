export interface ZoomPanOptions {
  /** When true, wheel only zooms while Ctrl/⌘ is held (plain wheel bubbles). */
  ctrlToZoom: boolean;
  /** Fired on a click that did not turn into a drag (e.g. open fullscreen). */
  onTap?: () => void;
}

const MIN_FACTOR = 0.8;
const MAX_SCALE = 8;

/**
 * Reusable zoom/pan behaviour for an <img> inside a positioned stage element
 * (no third-party deps). Wheel zoom centred on the cursor, drag to pan when
 * zoomed, double-click to toggle fit ↔ 2×, with edge clamping. Shared by the
 * fullscreen lightbox and the A-view inline page photo.
 */
export class ZoomPanController {
  private scale = 1;
  private fitScale = 1;
  private tx = 0;
  private ty = 0;
  private dragging = false;
  private dragMoved = false;
  private lastX = 0;
  private lastY = 0;

  constructor(
    private stage: HTMLElement,
    private img: HTMLImageElement,
    private opts: ZoomPanOptions,
  ) {
    // Bound at the window capture phase so we intercept Ctrl+wheel before
    // Obsidian's own workspace zoom handler swallows it (element-level
    // listeners never fire for it inside the main workspace).
    window.addEventListener("wheel", this.onWheel, { passive: false, capture: true });
    this.stage.addEventListener("pointerdown", this.onPointerDown);
    this.stage.addEventListener("pointermove", this.onPointerMove);
    this.stage.addEventListener("pointerup", this.onPointerUp);
    this.stage.addEventListener("pointercancel", this.onPointerUp);
    this.stage.addEventListener("dblclick", this.onDoubleClick);
    this.stage.addEventListener("click", this.onClick);
  }

  destroy(): void {
    window.removeEventListener("wheel", this.onWheel, { capture: true });
    this.stage.removeEventListener("pointerdown", this.onPointerDown);
    this.stage.removeEventListener("pointermove", this.onPointerMove);
    this.stage.removeEventListener("pointerup", this.onPointerUp);
    this.stage.removeEventListener("pointercancel", this.onPointerUp);
    this.stage.removeEventListener("dblclick", this.onDoubleClick);
    this.stage.removeEventListener("click", this.onClick);
  }

  get zoomed(): boolean {
    return this.scale > this.fitScale + 0.01;
  }

  fit(): void {
    const sw = this.stage.clientWidth;
    const sh = this.stage.clientHeight;
    const nw = this.img.naturalWidth || 1;
    const nh = this.img.naturalHeight || 1;
    this.fitScale = Math.min(sw / nw, sh / nh) || 1;
    this.scale = this.fitScale;
    this.tx = (sw - nw * this.scale) / 2;
    this.ty = (sh - nh * this.scale) / 2;
    this.apply();
  }

  zoomBy(factor: number): void {
    this.zoomAt(this.scale * factor, this.stage.clientWidth / 2, this.stage.clientHeight / 2);
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

  private onWheel = (e: WheelEvent): void => {
    if (!this.stage.contains(e.target as Node)) return; // not over our image
    if (this.opts.ctrlToZoom && !e.ctrlKey && !e.metaKey) return; // let the page scroll
    // Intercept before Obsidian's global Ctrl+wheel UI-zoom handler.
    e.preventDefault();
    e.stopPropagation();
    const rect = this.stage.getBoundingClientRect();
    const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
    this.zoomAt(this.scale * factor, e.clientX - rect.left, e.clientY - rect.top);
  };

  private onDoubleClick = (e: MouseEvent): void => {
    const rect = this.stage.getBoundingClientRect();
    const atFit = Math.abs(this.scale - this.fitScale) < 0.01;
    this.zoomAt(
      atFit ? Math.max(this.fitScale * 2, 1) : this.fitScale,
      e.clientX - rect.left,
      e.clientY - rect.top,
    );
  };

  private onPointerDown = (e: PointerEvent): void => {
    if (e.button !== 0) return;
    if ((e.target as HTMLElement).closest("button")) return;
    this.dragMoved = false;
    if (!this.zoomed) return; // nothing to pan; leave the click for onTap
    this.dragging = true;
    this.lastX = e.clientX;
    this.lastY = e.clientY;
    this.stage.setPointerCapture(e.pointerId);
    this.stage.addClass("rn-lb-grabbing");
  };

  private onPointerMove = (e: PointerEvent): void => {
    if (!this.dragging) return;
    const dx = e.clientX - this.lastX;
    const dy = e.clientY - this.lastY;
    if (Math.abs(dx) + Math.abs(dy) > 2) this.dragMoved = true;
    this.lastX = e.clientX;
    this.lastY = e.clientY;
    this.tx += dx;
    this.ty += dy;
    this.apply();
  };

  private onPointerUp = (e: PointerEvent): void => {
    this.dragging = false;
    this.stage.removeClass("rn-lb-grabbing");
    if (this.stage.hasPointerCapture(e.pointerId)) this.stage.releasePointerCapture(e.pointerId);
  };

  private onClick = (e: MouseEvent): void => {
    if ((e.target as HTMLElement).closest("button")) return;
    if (!this.dragMoved && this.opts.onTap) this.opts.onTap();
  };

  /** Apply the transform, clamping so the image can't drift off the stage. */
  private apply(): void {
    const sw = this.stage.clientWidth;
    const sh = this.stage.clientHeight;
    const w = (this.img.naturalWidth || 1) * this.scale;
    const h = (this.img.naturalHeight || 1) * this.scale;
    this.tx = w <= sw ? (sw - w) / 2 : Math.min(0, Math.max(sw - w, this.tx));
    this.ty = h <= sh ? (sh - h) / 2 : Math.min(0, Math.max(sh - h, this.ty));
    this.img.style.transform = `translate(${this.tx}px, ${this.ty}px) scale(${this.scale})`;
    this.stage.toggleClass("rn-lb-zoomed", this.zoomed);
  }
}

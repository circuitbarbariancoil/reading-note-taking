import { EXCERPT_COLOR, EXCERPT_CSS, HighlightPalette, Page } from "./types";

/** Code-point helpers — the canonical offset unit shared with the app (DESIGN.md §1.2). */
export function cpSlice(s: string, start: number, end: number): string {
  const cps = Array.from(s);
  return cps.slice(start, end).join("");
}

export function cpLength(s: string): number {
  return Array.from(s).length;
}

const RUBY_RE = /《[^》]*》/g;

export function stripRuby(s: string): string {
  return s.replace(RUBY_RE, "");
}

const HIGHLIGHT_RE = /~=\{([^}]*)\}([\s\S]*?)=~/g;

export function colorCss(name: string, palette: HighlightPalette): string {
  if (name === EXCERPT_COLOR) return EXCERPT_CSS;
  return palette.colors.find((c) => c.name === name)?.css ?? "#8A8A82";
}

/** Renders text containing `~={color}…=~` markup and ruby into [el]. */
export function renderMarkupText(el: HTMLElement, text: string, palette: HighlightPalette): void {
  let cursor = 0;
  for (const m of text.matchAll(HIGHLIGHT_RE)) {
    if (m.index! > cursor) renderRuby(el, text.slice(cursor, m.index!));
    const css = colorCss(m[1], palette);
    const span = el.createSpan({ cls: "rn-hl" });
    span.style.background = css + "33";
    span.style.borderBottom = `2px solid ${css}`;
    renderRuby(span, m[2]);
    cursor = m.index! + m[0].length;
  }
  if (cursor < text.length) renderRuby(el, text.slice(cursor));
}

/** Renders `漢字《よみ》` ruby readings as small superscript spans. */
function renderRuby(el: HTMLElement, text: string): void {
  let cursor = 0;
  for (const m of text.matchAll(RUBY_RE)) {
    if (m.index! > cursor) el.appendText(text.slice(cursor, m.index!));
    el.createSpan({ cls: "rn-ruby", text: m[0] });
    cursor = m.index! + m[0].length;
  }
  if (cursor < text.length) el.appendText(text.slice(cursor));
}

/**
 * Renders a frozen page's ocr_text with display-layer highlights applied by
 * code-point offsets (horizontal layout; the JS analogue of the app's PageHtml).
 */
export function renderPageText(el: HTMLElement, page: Page, palette: HighlightPalette): void {
  const text = page.ocr_text ?? "";
  const cps = Array.from(text);
  const marks = [...page.highlights].sort((a, b) => a.start - b.start);
  let cursor = 0;
  for (const h of marks) {
    const start = Math.max(cursor, Math.min(h.start, cps.length));
    const end = Math.max(start, Math.min(h.end, cps.length));
    if (start > cursor) renderRuby(el, cps.slice(cursor, start).join(""));
    const css = colorCss(h.color, palette);
    const span = el.createSpan({ cls: "rn-hl" });
    if (h.color === EXCERPT_COLOR) {
      span.style.background = css + "40";
    } else {
      span.style.background = css + "33";
      span.style.borderBottom = `2px solid ${css}`;
    }
    renderRuby(span, cps.slice(start, end).join(""));
    cursor = end;
  }
  if (cursor < cps.length) renderRuby(el, cps.slice(cursor).join(""));
}

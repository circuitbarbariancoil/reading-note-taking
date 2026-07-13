/**
 * Mirror of the app's `book.json` schema (see ../DESIGN.md §3 and
 * app/src/main/java/com/readingnotes/app/model/Book.kt). The app serializes
 * with kotlinx JsonNamingStrategy.SnakeCase, so all keys here are snake_case.
 * All offsets (start/end, src_start/src_end) are Unicode code points over the
 * frozen ocr_text.
 */

export interface Book {
  schema: number;
  uid: string;
  title: string;
  author: string;
  isbn?: string | null;
  cover_path?: string | null;
  created_at: string;
  updated_at: string;
  dropbox_root: string;
  pages: Page[];
  captures: unknown[];
  entries: Entry[];
  sections: Section[];
}

export interface Section {
  id: string;
  title: string;
  start_page: number;
  level: number;
  note: string;
}

export interface Page {
  page: number;
  archive_image?: string | null;
  ocr_text?: string | null;
  proofread: boolean;
  highlights: PageHighlight[];
}

export interface PageHighlight {
  id: string;
  start: number;
  end: number;
  /** Palette color name, or the reserved "excerpt" for plain excerpts. */
  color: string;
}

export const EXCERPT_COLOR = "excerpt";

export type EntryKind = "highlight" | "excerpt" | "note";

export interface Entry {
  id: string;
  page?: number | null;
  src_start: number;
  src_end: number;
  /** Editable copy; may embed `~={color}…=~` markup and `漢字《よみ》` ruby. */
  text: string;
  kind: EntryKind;
  highlight_id?: string | null;
  annotation: string;
  tags: string[];
  created_at: string;
  updated_at: string;
}

export interface HighlightColor {
  name: string;
  css: string;
  active: boolean;
}

export interface HighlightPalette {
  colors: HighlightColor[];
}

/** Matches HighlightPalette.DEFAULT in the app. */
export const DEFAULT_PALETTE: HighlightPalette = {
  colors: [
    { name: "yellow", css: "#D9B44A", active: true },
    { name: "green", css: "#6E8B5A", active: true },
    { name: "blue", css: "#5A7A9B", active: true },
    { name: "pink", css: "#B96A78", active: true },
  ],
};

export const EXCERPT_CSS = "#7C756B";

/** Fixed uid of the app's singleton page-less notebook (BookRepository.NOTEBOOK_UID). */
export const NOTEBOOK_UID = "notebook-default";

export function isNotebook(bookOrUid: Book | string): boolean {
  return (typeof bookOrUid === "string" ? bookOrUid : bookOrUid.uid) === NOTEBOOK_UID;
}

export const VAULT_ROOT = "/ReadingVault";
export const BOOKS_ROOT = `${VAULT_ROOT}/books`;
export const PALETTE_PATH = `${VAULT_ROOT}/config/highlight-colors.json`;

/** Book-order sort key: page (unpaged last), then in-page offset, then time. */
export function entryOrder(a: Entry, b: Entry): number {
  const pa = a.page ?? Number.MAX_SAFE_INTEGER;
  const pb = b.page ?? Number.MAX_SAFE_INTEGER;
  if (pa !== pb) return pa - pb;
  if (a.src_start !== b.src_start) return a.src_start - b.src_start;
  return a.created_at < b.created_at ? -1 : a.created_at > b.created_at ? 1 : 0;
}

/** Newest first by creation time (notebook default / 时间排序). */
export function entryOrderNewest(a: Entry, b: Entry): number {
  return a.created_at < b.created_at ? 1 : a.created_at > b.created_at ? -1 : 0;
}

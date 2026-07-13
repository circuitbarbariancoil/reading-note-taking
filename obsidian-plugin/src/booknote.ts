import { App, TFile, normalizePath } from "obsidian";
import { Book, Entry, entryOrder } from "./types";

/**
 * One note per book. Each app entry occupies one block of plain lines
 * (no blockquote/callout markup):
 *
 *   `app:<id>`            ← renders as a caption chip carrying the page number
 *   原文副本（可含 ~={color}=~ 高亮与《》ruby）
 *   💬 App 批注（与标签重复时省略）
 *   #tag1 #tag2
 *
 * Sync is strictly append-only and position-aware: blocks already in the note
 * are NEVER touched (the user may freely rework them); a missing entry is
 * inserted immediately BEFORE the anchor line of its nearest book-order
 * successor already present, so it never interrupts prose the user wrote under
 * the preceding entry. With no successor it is appended at the end.
 */

export const ANCHOR_RE = /^`app:([A-Za-z0-9_-]+)`\s*$/;
export const ANCHOR_INLINE_RE = /^app:([A-Za-z0-9_-]+)$/;

/**
 * The annotation with any `#tag` tokens that duplicate the entry's own tags
 * removed (tags are shown on their own line/row) — "" when nothing remains.
 */
export function displayAnnotation(entry: Entry): string {
  const norm = (s: string) => s.replace(/^#/, "").trim();
  const tags = new Set(entry.tags.map(norm));
  return entry.annotation
    .trim()
    .split(/\s+/)
    .filter((w) => !(w.startsWith("#") && tags.has(norm(w))) && !tags.has(w))
    .join(" ");
}

export function entryBlock(entry: Entry): string {
  const lines: string[] = [];
  lines.push(`\`app:${entry.id}\``);
  for (const line of entry.text.split("\n")) lines.push(line);
  const tags = entry.tags.map((t) => (t.startsWith("#") ? t : `#${t}`));
  const annotation = displayAnnotation(entry);
  if (annotation) lines.push(`💬 ${annotation.split("\n").join(" ")}`);
  if (tags.length > 0) lines.push(tags.join(" "));
  return lines.join("\n");
}

export function initialNoteContent(book: Book): string {
  const sorted = [...book.entries].sort(entryOrder);
  const header = [
    "---",
    `app_book: ${book.uid}`,
    "---",
    "",
    `# ${book.title}${book.author ? ` · ${book.author}` : ""}`,
    "",
  ];
  const blocks = sorted.map((e) => entryBlock(e));
  return header.join("\n") + blocks.join("\n\n") + (blocks.length > 0 ? "\n" : "");
}

export function anchorIdsInText(text: string): Map<string, number> {
  const ids = new Map<string, number>();
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(ANCHOR_RE);
    if (m && !ids.has(m[1])) ids.set(m[1], i);
  }
  return ids;
}

export interface SyncResult {
  content: string;
  added: number;
}

/**
 * Line index of the last `---` thematic break in the body (after any leading
 * frontmatter), or null if there is none. Everything from this line down is the
 * user's hand-written zone — tail-appended entries go BEFORE it, never below.
 */
export function forbiddenZoneStart(lines: string[]): number | null {
  let bodyStart = 0;
  if (lines[0]?.trim() === "---") {
    const close = lines.findIndex((l, i) => i > 0 && l.trim() === "---");
    if (close !== -1) bodyStart = close + 1;
  }
  for (let i = lines.length - 1; i >= bodyStart; i--) {
    if (lines[i].trim() === "---") return i;
  }
  return null;
}

/** Pure append-only merge of missing entries into existing note [text]. */
export function mergeMissingEntries(text: string, book: Book): SyncResult {
  const existing = anchorIdsInText(text);
  const sorted = [...book.entries].sort(entryOrder);
  const missing = sorted.filter((e) => !existing.has(e.id));
  if (missing.length === 0) return { content: text, added: 0 };

  const lines = text.split("\n");
  // Insert from last to first so earlier line indexes stay valid.
  for (const entry of [...missing].reverse()) {
    const idx = sorted.findIndex((e) => e.id === entry.id);
    // Nearest book-order successor already present in the note.
    let successorLine: number | null = null;
    for (let j = idx + 1; j < sorted.length; j++) {
      const line = anchorIdsInText(lines.join("\n")).get(sorted[j].id);
      if (line != null) {
        successorLine = line;
        break;
      }
    }
    const block = entryBlock(entry).split("\n");
    if (successorLine != null) {
      lines.splice(successorLine, 0, ...block, "");
    } else {
      // No successor → append at the tail, but keep out of the user's zone
      // below the last `---` divider if one exists.
      const boundary = forbiddenZoneStart(lines);
      if (boundary != null) {
        lines.splice(boundary, 0, ...block, "");
      } else {
        if (lines.length > 0 && lines[lines.length - 1].trim() !== "") lines.push("");
        lines.push(...block, "");
      }
    }
  }
  return { content: lines.join("\n"), added: missing.length };
}

export function noteFileName(book: Book): string {
  const safe = book.title.replace(/[\\/:*?"<>|#^[\]]/g, "_").trim() || book.uid;
  return `${safe}.md`;
}

/** Find the note whose frontmatter `app_book` matches [uid]. */
export function findBookNote(app: App, uid: string): TFile | null {
  for (const file of app.vault.getMarkdownFiles()) {
    const fm = app.metadataCache.getFileCache(file)?.frontmatter;
    if (fm && fm.app_book === uid) return file;
  }
  return null;
}

/** Create the note if absent, otherwise append-only sync it. Returns the file. */
export async function syncBookNote(
  app: App,
  book: Book,
  folder: string,
): Promise<{ file: TFile; added: number; created: boolean }> {
  const existing = findBookNote(app, book.uid);
  if (existing) {
    const text = await app.vault.read(existing);
    const { content, added } = mergeMissingEntries(text, book);
    if (added > 0) await app.vault.modify(existing, content);
    return { file: existing, added, created: false };
  }
  const dir = normalizePath(folder);
  if (dir && !app.vault.getAbstractFileByPath(dir)) await app.vault.createFolder(dir);
  const path = normalizePath(dir ? `${dir}/${noteFileName(book)}` : noteFileName(book));
  const file = await app.vault.create(path, initialNoteContent(book));
  return { file, added: book.entries.length, created: true };
}

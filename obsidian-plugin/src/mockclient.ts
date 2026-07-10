import { App, normalizePath } from "obsidian";
import { Book, DEFAULT_PALETTE, HighlightPalette } from "./types";

/** Common surface of DropboxClient / MockClient used by the plugin. */
export interface BooksClient {
  listBookUids(): Promise<string[]>;
  fetchBook(uid: string): Promise<Book>;
  fetchPalette(): Promise<HighlightPalette>;
  temporaryLink(path: string): Promise<string>;
}

/**
 * Local data source for development: a vault folder mirroring /ReadingVault
 * (books/<uid>/book.json + pages/*.webp + config/highlight-colors.json).
 * Enabled by setting the refresh token field to `mock:<vault folder>`.
 */
export class MockClient implements BooksClient {
  constructor(
    private app: App,
    private root: string,
  ) {}

  private rel(path: string): string {
    return normalizePath(`${this.root}/${path.replace(/^\/?ReadingVault\/?|^\//, "")}`);
  }

  async listBookUids(): Promise<string[]> {
    const listing = await this.app.vault.adapter.list(normalizePath(`${this.root}/books`));
    return listing.folders.map((f) => f.split("/").pop()!);
  }

  async fetchBook(uid: string): Promise<Book> {
    const text = await this.app.vault.adapter.read(this.rel(`books/${uid}/book.json`));
    return JSON.parse(text) as Book;
  }

  async fetchPalette(): Promise<HighlightPalette> {
    try {
      const text = await this.app.vault.adapter.read(this.rel("config/highlight-colors.json"));
      const parsed = JSON.parse(text) as HighlightPalette;
      if (Array.isArray(parsed.colors) && parsed.colors.length > 0) return parsed;
    } catch (_e) {
      /* fall through */
    }
    return DEFAULT_PALETTE;
  }

  async temporaryLink(path: string): Promise<string> {
    return this.app.vault.adapter.getResourcePath(this.rel(path));
  }
}

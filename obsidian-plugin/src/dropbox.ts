import { requestUrl } from "obsidian";
import { Book, BOOKS_ROOT, DEFAULT_PALETTE, HighlightPalette, PALETTE_PATH } from "./types";

/**
 * Minimal Dropbox REST client over Obsidian's requestUrl (no CORS issues).
 * Auth: OAuth2 PKCE with offline access — the user opens the authorize URL,
 * pastes the code back, and we hold a refresh token from then on.
 */

export interface DropboxAuth {
  refreshToken: string;
}

const API = "https://api.dropboxapi.com";
const CONTENT = "https://content.dropboxapi.com";

function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function makePkceVerifier(): string {
  const bytes = new Uint8Array(48);
  crypto.getRandomValues(bytes);
  return b64url(bytes);
}

export async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return b64url(new Uint8Array(digest));
}

export function authorizeUrl(appKey: string, challenge: string): string {
  const p = new URLSearchParams({
    client_id: appKey,
    response_type: "code",
    code_challenge: challenge,
    code_challenge_method: "S256",
    token_access_type: "offline",
  });
  return `https://www.dropbox.com/oauth2/authorize?${p}`;
}

export async function exchangeCode(appKey: string, code: string, verifier: string): Promise<string> {
  const body = new URLSearchParams({
    code: code.trim(),
    grant_type: "authorization_code",
    code_verifier: verifier,
    client_id: appKey,
  });
  const res = await requestUrl({
    url: `${API}/oauth2/token`,
    method: "POST",
    contentType: "application/x-www-form-urlencoded",
    body: body.toString(),
    throw: false,
  });
  if (res.status !== 200) throw new Error(`Dropbox 授权失败 (${res.status}): ${res.text}`);
  return res.json.refresh_token as string;
}

export class DropboxClient {
  private accessToken: string | null = null;
  private expiresAt = 0;

  constructor(
    private appKey: string,
    private refreshToken: string,
  ) {}

  private async token(): Promise<string> {
    if (this.accessToken && Date.now() < this.expiresAt - 60_000) return this.accessToken;
    const body = new URLSearchParams({
      grant_type: "refresh_token",
      refresh_token: this.refreshToken,
      client_id: this.appKey,
    });
    const res = await requestUrl({
      url: `${API}/oauth2/token`,
      method: "POST",
      contentType: "application/x-www-form-urlencoded",
      body: body.toString(),
      throw: false,
    });
    if (res.status !== 200) throw new Error(`Dropbox token 刷新失败 (${res.status}): ${res.text}`);
    this.accessToken = res.json.access_token as string;
    this.expiresAt = Date.now() + (res.json.expires_in as number) * 1000;
    return this.accessToken;
  }

  private async rpc(path: string, arg: unknown): Promise<any> {
    const res = await requestUrl({
      url: `${API}${path}`,
      method: "POST",
      contentType: "application/json",
      headers: { Authorization: `Bearer ${await this.token()}` },
      body: JSON.stringify(arg),
      throw: false,
    });
    if (res.status !== 200) throw new Error(`Dropbox ${path} 失败 (${res.status}): ${res.text}`);
    return res.json;
  }

  /** List immediate subfolder names of [path]. */
  async listFolders(path: string): Promise<string[]> {
    const names: string[] = [];
    let result = await this.rpc("/2/files/list_folder", { path });
    for (;;) {
      for (const e of result.entries) if (e[".tag"] === "folder") names.push(e.name);
      if (!result.has_more) break;
      result = await this.rpc("/2/files/list_folder/continue", { cursor: result.cursor });
    }
    return names;
  }

  async downloadText(path: string): Promise<string> {
    const res = await requestUrl({
      url: `${CONTENT}/2/files/download`,
      method: "POST",
      headers: {
        Authorization: `Bearer ${await this.token()}`,
        "Dropbox-API-Arg": JSON.stringify({ path }),
      },
      throw: false,
    });
    if (res.status !== 200) throw new Error(`Dropbox 下载 ${path} 失败 (${res.status})`);
    return res.text;
  }

  /** Short-lived direct link for rendering images without downloading into the vault. */
  async temporaryLink(path: string): Promise<string> {
    const json = await this.rpc("/2/files/get_temporary_link", { path });
    return json.link as string;
  }

  async listBookUids(): Promise<string[]> {
    return this.listFolders(BOOKS_ROOT);
  }

  async fetchBook(uid: string): Promise<Book> {
    const text = await this.downloadText(`${BOOKS_ROOT}/${uid}/book.json`);
    return JSON.parse(text) as Book;
  }

  /** Shared palette written by the app; falls back to the built-in default. */
  async fetchPalette(): Promise<HighlightPalette> {
    try {
      const text = await this.downloadText(PALETTE_PATH);
      const parsed = JSON.parse(text) as HighlightPalette;
      if (Array.isArray(parsed.colors) && parsed.colors.length > 0) return parsed;
    } catch (_e) {
      /* not written yet */
    }
    return DEFAULT_PALETTE;
  }
}

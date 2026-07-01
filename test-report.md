# Test Report — PR #1 vertical/horizontal OCR renderer

**App:** Kotlin Android debug APK on emulator (android-34, Pixel-class AVD).
**Entry:** `MainActivity` → `PagePreviewScreen` renders `SAMPLE_PAGE` (《金閣寺》sample
OCR text) via `PageHtml.render` in a WebView, with a 竖排/横排 toggle button.

## Summary
Tested the vertical/horizontal OCR text renderer end-to-end on the emulator by
launching the app and exercising the orientation toggle. All three tests passed.

## Escalations / notes
- **WebView height-collapse bug found & fixed during testing.** Vertical text was
  collapsing to a single line because the WebView had no definite height. Fixed by
  giving the inner WebView `MATCH_PARENT` layout params and `html/body height:100%`,
  and constraining the Compose host with `weight(1f)` so the toggle stays visible.
  Committed in `6fceb5c`.
- **Emulator compositing lag (cosmetic, not an app bug).** With software-GPU
  emulation, the Compose header/toggle sometimes does not repaint until an input
  event. On a real device / hardware GPU this does not occur. Verified the header +
  button are always present in the view hierarchy via `uiautomator dump`.
- Out of scope for this PR (not tested): camera capture, Dropbox sync, real Gemini
  OCR call, entry/highlight editing UI, Obsidian plugin.

## Results
- **Test 1 — Default vertical render (tategaki):** PASSED. Columns flow top-to-bottom,
  ordered right-to-left (月のような… at top-right); 膝 shows ひざ furigana (ruby, not
  literal `《》`); only "すでに柏木の手" carries the red highlight; button reads 竖排.
- **Test 2 — Toggle to horizontal:** PASSED. Text reflows to horizontal LTR rows
  (visibly different from Test 1); button label flips 竖排→横排; highlight and furigana
  persist.
- **Test 3 — Toggle back to vertical:** PASSED. Layout returns to vertical-rl,
  identical to Test 1; button label flips 横排→竖排. State round-trips.

## Evidence

| Test 1 — Vertical (default) | Test 2 — Horizontal (after tap) |
|---|---|
| ![Vertical tategaki, 竖排](https://app.devin.ai/attachments/cf7ea38c-7e9c-4fdd-8ff8-f031bce5b097/ss_zoom_91026bdb.png) | ![Horizontal LTR, 横排](https://app.devin.ai/attachments/a0f60bb7-3ce9-4818-a40c-e7c5bf3f97cd/ss_zoom_ab155f8c.png) |
| RTL columns; ひざ over 膝; red span on すでに柏木の手; button 竖排 | LTR rows; ざ ruby; highlight persists across wrap; button 横排 |

| Test 3 — Back to vertical |
|---|
| ![Vertical restored, 竖排](https://app.devin.ai/attachments/a3d35a23-49b2-4a0f-a910-d5cbc9749fc4/ss_zoom_03885dbf.png) |
| Layout round-trips to vertical-rl; button 竖排 |

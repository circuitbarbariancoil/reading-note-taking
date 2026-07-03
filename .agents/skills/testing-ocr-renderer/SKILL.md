---
name: testing-ocr-renderer
description: Build, install, and runtime-test the Kotlin Android OCR text renderer (vertical/horizontal tategaki + furigana + highlights) on an emulator. Use when verifying PageHtml/WebView rendering or the 竖排/横排 toggle.
---

# Testing the OCR text renderer (vertical/horizontal)

The app renders frozen OCR text in a WebView (`PageHtml.render`) with CSS
`writing-mode: vertical-rl` / `horizontal-tb`, ruby (`漢字《よみ》`→`<ruby>`), and
`~={color}text=~` highlights at Unicode code-point offsets. `MainActivity` shows a
sample page with a 竖排/横排 toggle button.

## Build & install
```bash
export ANDROID_HOME=/home/ubuntu/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH
cd /home/ubuntu/repos/reading-note-taking
./gradlew assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.readingnotes.app
adb shell am start -n com.readingnotes.app/.MainActivity
```

## Emulator setup gotchas
- KVM perms may need: `sudo gpasswd -a ubuntu kvm && sudo chmod 666 /dev/kvm`.
- Bring the emulator window to front for screenshots: `wmctrl -i -a <window-id>`
  (find id with `wmctrl -l`).

## Known rendering pitfalls (may recur)
- **WebView height collapse:** if vertical text renders as a single horizontal line,
  the WebView likely has no definite height. Fix that worked: give the inner WebView
  `layoutParams = MATCH_PARENT/MATCH_PARENT`, set `html,body{height:100%}` in
  `PageHtml`, and constrain the Compose host with `Modifier.weight(1f)` (NOT
  `fillMaxSize`, which can hide sibling UI like the toggle row).
- **Compose header/toggle appears blank in screenshots:** with software-GPU emulation
  the Compose layer may not repaint until an input event. It is NOT an app bug —
  confirm elements exist with `adb shell uiautomator dump /sdcard/ui.xml` and
  grep for the labels/bounds. A click forces a redraw.

## Adversarial check that distinguishes working vs broken
The toggle is the discriminating test: a broken toggle looks identical to the default.
Verify the layout VISIBLY changes — vertical = top-to-bottom columns ordered
right-to-left; horizontal = left-to-right rows. Also confirm furigana renders as small
kana (not literal `《》`) and the highlight covers only the intended span.

## Devin Secrets Needed
- None for the renderer test (sample text is hardcoded). Real OCR needs a Gemini API
  key (`gemini-3-flash-preview`) supplied at runtime, never committed.

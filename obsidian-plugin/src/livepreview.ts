import { RangeSetBuilder } from "@codemirror/state";
import {
  Decoration,
  DecorationSet,
  EditorView,
  PluginValue,
  ViewPlugin,
  ViewUpdate,
  WidgetType,
} from "@codemirror/view";
import { editorInfoField, editorLivePreviewField } from "obsidian";
import { fillAnchorChip } from "./anchorchip";
import { colorCss } from "./markup";
import type ReadingNotesPlugin from "./main";
import { EXCERPT_COLOR } from "./types";

/**
 * Live Preview rendering (the reading-view post-processor does not run inside
 * the CodeMirror editor). Two kinds of inline decorations, both of which fall
 * back to raw syntax whenever the selection touches their range so the text
 * stays editable:
 *
 * - `` `app:<id>` `` inline code  → entry chip widget (page + 📷/📖 buttons)
 * - `~={color}text=~` markup      → hide the markers, color the inner text
 */

const ANCHOR_SRC_RE = /`app:([A-Za-z0-9_-]+)`/g;
const HIGHLIGHT_SRC_RE = /~=\{([^}\n]*)\}([\s\S]*?)=~/g;

class AnchorChipWidget extends WidgetType {
  constructor(
    private plugin: ReadingNotesPlugin,
    private entryId: string,
    private bookUid?: string,
  ) {
    super();
  }

  eq(other: AnchorChipWidget): boolean {
    return other.entryId === this.entryId && other.bookUid === this.bookUid;
  }

  toDOM(): HTMLElement {
    const chip = createSpan();
    fillAnchorChip(this.plugin, chip, this.entryId, this.bookUid);
    return chip;
  }

  /** Handle clicks ourselves — the editor must not move the caret into the range. */
  ignoreEvent(): boolean {
    return true;
  }
}

class MarkerHideWidget extends WidgetType {
  toDOM(): HTMLElement {
    return createSpan();
  }
}

export function livePreviewExtension(plugin: ReadingNotesPlugin) {
  return ViewPlugin.fromClass(
    class implements PluginValue {
      decorations: DecorationSet;

      constructor(view: EditorView) {
        this.decorations = this.build(view);
      }

      update(update: ViewUpdate): void {
        if (update.docChanged || update.viewportChanged || update.selectionSet) {
          this.decorations = this.build(update.view);
        }
      }

      private build(view: EditorView): DecorationSet {
        if (!view.state.field(editorLivePreviewField)) return Decoration.none;
        const bookUid = view.state.field(editorInfoField)?.file
          ? (plugin.app.metadataCache.getFileCache(view.state.field(editorInfoField).file!)?.frontmatter
              ?.app_book as string | undefined)
          : undefined;

        interface Deco {
          from: number;
          to: number;
          deco: Decoration;
        }
        const decos: Deco[] = [];
        const sel = view.state.selection.main;
        const touches = (from: number, to: number) => sel.from <= to && sel.to >= from;

        for (const { from, to } of view.visibleRanges) {
          const text = view.state.doc.sliceString(from, to);

          for (const m of text.matchAll(ANCHOR_SRC_RE)) {
            const start = from + m.index!;
            const end = start + m[0].length;
            if (touches(start, end)) continue;
            decos.push({
              from: start,
              to: end,
              deco: Decoration.replace({ widget: new AnchorChipWidget(plugin, m[1], bookUid) }),
            });
          }

          for (const m of text.matchAll(HIGHLIGHT_SRC_RE)) {
            const start = from + m.index!;
            const end = start + m[0].length;
            if (touches(start, end)) continue;
            const openLen = 4 + m[1].length; // ~={color}
            const css = colorCss(m[1], plugin.palette);
            const style =
              m[1] === EXCERPT_COLOR
                ? `background:${css}40;border-radius:2px;`
                : `background:${css}33;border-bottom:2px solid ${css};border-radius:2px;`;
            decos.push({ from: start, to: start + openLen, deco: Decoration.replace({ widget: new MarkerHideWidget() }) });
            decos.push({
              from: start + openLen,
              to: end - 2,
              deco: Decoration.mark({ attributes: { style } }),
            });
            decos.push({ from: end - 2, to: end, deco: Decoration.replace({ widget: new MarkerHideWidget() }) });
          }
        }

        decos.sort((a, b) => a.from - b.from || a.to - b.to);
        const builder = new RangeSetBuilder<Decoration>();
        for (const d of decos) {
          if (d.from < d.to) builder.add(d.from, d.to, d.deco);
        }
        return builder.finish();
      }
    },
    { decorations: (v) => v.decorations },
  );
}

import { EditorState, StateEffect, StateField, RangeSetBuilder } from "@codemirror/state";
import {
  EditorView,
  Decoration,
  ViewPlugin,
  WidgetType,
  keymap,
  drawSelection,
  placeholder,
} from "@codemirror/view";
import { history, historyKeymap, defaultKeymap } from "@codemirror/commands";
import { markdown } from "@codemirror/lang-markdown";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags as t } from "@lezer/highlight";
import {
  autocompletion,
  completionKeymap,
  closeBrackets,
  startCompletion,
} from "@codemirror/autocomplete";

let PALETTE = []; // [{name, css}]
let KNOWN_TAGS = [];

const RUBY_RE = /([\u4E00-\u9FFF\u3040-\u309F\u30A0-\u30FF\u30FCA-Za-z]+)《([^》]+)》/g;
const HL_RE = /~=\{([^}]+)\}([\s\S]*?)=~/g;
const BOLD_RE = /\*\*([^*]+)\*\*/g;
const TAG_RE = /(^|\s)#([^\s#]+)/g;

function cssFor(name) {
  const c = PALETTE.find((p) => p.name === name);
  return c ? c.css : "#3C5468";
}

class RubyWidget extends WidgetType {
  constructor(base, reading) {
    super();
    this.base = base;
    this.reading = reading;
  }
  eq(o) {
    return o.base === this.base && o.reading === this.reading;
  }
  toDOM() {
    const r = document.createElement("ruby");
    r.textContent = this.base;
    const rt = document.createElement("rt");
    rt.textContent = this.reading;
    r.appendChild(rt);
    return r;
  }
}

class HideWidget extends WidgetType {
  toDOM() {
    return document.createElement("span");
  }
}

// True when the current selection touches [from, to] — then show raw source.
function touched(sel, from, to) {
  for (const r of sel.ranges) {
    if (r.from <= to && r.to >= from) return true;
  }
  return false;
}

function livePreview(kind) {
  return ViewPlugin.fromClass(
    class {
      constructor(view) {
        this.decorations = this.build(view);
      }
      update(u) {
        if (u.docChanged || u.selectionSet || u.viewportChanged) {
          this.decorations = this.build(u.view);
        }
      }
      build(view) {
        const b = new RangeSetBuilder();
        const sel = view.state.selection;
        const text = view.state.doc.toString();
        const marks = [];

        const push = (from, to, deco) => marks.push({ from, to, deco });

        if (kind === "jp") {
          let m;
          RUBY_RE.lastIndex = 0;
          while ((m = RUBY_RE.exec(text))) {
            const from = m.index;
            const to = from + m[0].length;
            if (touched(sel, from, to)) continue;
            push(from, to, Decoration.replace({ widget: new RubyWidget(m[1], m[2]) }));
          }
          HL_RE.lastIndex = 0;
          while ((m = HL_RE.exec(text))) {
            const from = m.index;
            const to = from + m[0].length;
            const innerFrom = from + m[0].indexOf("}") + 1;
            const innerTo = to - 2;
            const color = m[1];
            if (touched(sel, from, to)) {
              push(from, to, Decoration.mark({ attributes: { style: `background:${cssFor(color)}22;` } }));
            } else {
              push(from, innerFrom, Decoration.replace({ widget: new HideWidget() }));
              push(innerFrom, innerTo, Decoration.mark({
                attributes: { style: `background:${cssFor(color)}33;border-bottom:2px solid ${cssFor(color)};` },
              }));
              push(innerTo, to, Decoration.replace({ widget: new HideWidget() }));
            }
          }
        } else {
          let m;
          BOLD_RE.lastIndex = 0;
          while ((m = BOLD_RE.exec(text))) {
            const from = m.index;
            const to = from + m[0].length;
            if (touched(sel, from, to)) {
              push(from, to, Decoration.mark({ class: "cm-strong" }));
            } else {
              push(from, from + 2, Decoration.replace({ widget: new HideWidget() }));
              push(from + 2, to - 2, Decoration.mark({ class: "cm-strong" }));
              push(to - 2, to, Decoration.replace({ widget: new HideWidget() }));
            }
          }
          TAG_RE.lastIndex = 0;
          while ((m = TAG_RE.exec(text))) {
            const from = m.index + m[1].length;
            const to = from + 1 + m[2].length;
            push(from, to, Decoration.mark({ class: "cm-tag" }));
          }
        }

        marks.sort((a, x) => a.from - x.from || a.to - x.to);
        for (const mk of marks) b.add(mk.from, mk.to, mk.deco);
        return b.finish();
      }
    },
    { decorations: (v) => v.decorations }
  );
}

function tagCompletion(context) {
  const before = context.matchBefore(/#[^\s#]*/);
  if (!before || (before.from === before.to && !context.explicit)) return null;
  const q = before.text.slice(1);
  const options = KNOWN_TAGS.filter((tag) => tag.startsWith(q)).map((tag) => ({
    label: "#" + tag,
    apply: "#" + tag,
  }));
  return { from: before.from, options, validFor: /^#[^\s#]*$/ };
}

function colorCompletion(context) {
  const before = context.matchBefore(/~=\{[^}]*/);
  if (!before) return null;
  const q = before.text.slice(3);
  const options = PALETTE.filter((p) => p.name.startsWith(q)).map((p) => ({
    label: p.name,
    apply: p.name + "}",
    type: "color",
  }));
  return { from: before.from + 3, options, validFor: /^[^}]*$/ };
}

const hlStyle = HighlightStyle.define([
  { tag: t.heading, fontWeight: "600", color: "#211E1A" },
  { tag: t.strong, fontWeight: "700" },
  { tag: t.emphasis, fontStyle: "italic" },
  { tag: t.quote, color: "#6B655C" },
  { tag: t.link, color: "#3C5468" },
  { tag: t.list, color: "#6B655C" },
]);

const baseTheme = EditorView.theme({
  "&": { backgroundColor: "transparent", color: "#211E1A", fontSize: "15px" },
  ".cm-content": { fontFamily: '"Noto Serif CJK JP", serif', lineHeight: "1.8", caretColor: "#3C5468" },
  ".cm-scroller": { fontFamily: '"Noto Serif CJK JP", serif' },
  "&.cm-focused": { outline: "none" },
  ".cm-strong": { fontWeight: "700" },
  ".cm-tag": { color: "#3C5468", backgroundColor: "#E1E6EA", borderRadius: "8px", padding: "0 4px" },
  ".cm-cursor": { borderLeftColor: "#3C5468" },
});

function makeEditor(parent, doc, kind, ph) {
  const source = kind === "jp" ? colorCompletion : tagCompletion;
  const state = EditorState.create({
    doc: doc || "",
    extensions: [
      history(),
      drawSelection(),
      closeBrackets(),
      kind === "md" ? markdown() : [],
      syntaxHighlighting(hlStyle),
      livePreview(kind),
      autocompletion({ override: [source], activateOnTyping: true }),
      keymap.of([...defaultKeymap, ...historyKeymap, ...completionKeymap]),
      baseTheme,
      EditorView.lineWrapping,
      placeholder(ph || ""),
    ],
  });
  return new EditorView({ state, parent });
}

let excerptView = null;
let annotationView = null;
let focusedView = null;

function trackFocus(view) {
  view.contentDOM.addEventListener("focus", () => {
    focusedView = view;
  });
}

function target() {
  return focusedView || excerptView;
}

// Wrap the selection (or insert an empty pair and place the caret inside).
function wrapSelection(view, before, after) {
  if (!view) return;
  const { from, to } = view.state.selection.main;
  view.dispatch({
    changes: [
      { from, insert: before },
      { from: to, insert: after },
    ],
    selection:
      from === to
        ? { anchor: from + before.length }
        : { anchor: from + before.length, head: to + before.length },
  });
  view.focus();
}

function insertText(view, text, caretOffset) {
  if (!view) return;
  const { from, to } = view.state.selection.main;
  view.dispatch({
    changes: { from, to, insert: text },
    selection: { anchor: from + (caretOffset != null ? caretOffset : text.length) },
  });
  view.focus();
}


window.RN = {
  init(configJson) {
    const cfg = typeof configJson === "string" ? JSON.parse(configJson) : configJson;
    PALETTE = cfg.colors || [];
    KNOWN_TAGS = cfg.knownTags || [];
    document.getElementById("excerpt").innerHTML = "";
    document.getElementById("annotation").innerHTML = "";
    excerptView = makeEditor(document.getElementById("excerpt"), cfg.excerpt || "", "jp", "");
    annotationView = makeEditor(document.getElementById("annotation"), cfg.annotation || "", "md", "");
    trackFocus(excerptView);
    trackFocus(annotationView);
    focusedView = excerptView;
  },
  // Quick-syntax actions, driven by the native Compose toolbar.
  wrap(before, after) {
    wrapSelection(target(), before, after);
  },
  insertTag() {
    const view = target();
    insertText(view, "#");
    startCompletion(view);
  },
  collect() {
    const excerpt = excerptView ? excerptView.state.doc.toString() : "";
    const annotation = annotationView ? annotationView.state.doc.toString() : "";
    const tags = [];
    let m;
    TAG_RE.lastIndex = 0;
    while ((m = TAG_RE.exec(annotation))) tags.push(m[2]);
    const payload = JSON.stringify({ excerpt, annotation, tags: Array.from(new Set(tags)) });
    if (window.Android && window.Android.onCollect) window.Android.onCollect(payload);
    return payload;
  },
  focusAnnotation() {
    if (annotationView) annotationView.focus();
  },
};

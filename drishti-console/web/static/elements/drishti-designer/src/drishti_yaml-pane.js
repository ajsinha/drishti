/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
/* The YAML pane: the design as text, in drishti-inputs' CodeEditor (YAML mode), kept in step with the visual side both ways. A change
   on the visual side writes the YAML again (setModel; the cursor and the scroll stay); an edit of the YAML is read after a pause
   (fromYaml: the host's reader, the server's or the one here) and handed back as the model ("model" event), or its problem is shown at
   its line and the model keeps what it had. The selected item's lines are highlighted and scrolled to; a click in the YAML selects the
   item the line belongs to. Completion and the lint come from hooks (the YAML assistant over a JSON Schema, a validator). */
import { EventBus, announce, h } from '../../drishti-core/src/drishti_index.js';
import { CodeEditor, debounce } from '../../drishti-inputs/src/drishti_index.js';
import { parse, plain } from './drishti_yaml-read.js';
import { dumpYaml, yamlRanges, pathAtLine } from './drishti_yaml-write.js';

/**
 * Reads YAML text into plain values; a line a reader cannot take (a key without its colon, a tab) is an error with its line.
 * @param {string} text
 * @returns {{value: *, problems: Array<{line: number, message: string}>}}
 */
export function readYaml(text) {
  const tree = parse(text), problems = [];
  (tree.tabs || []).forEach((n) => problems.push({ line: n + 1, message: 'line ' + (n + 1) + ' is indented with a tab: YAML allows only spaces' }));
  const visit = (n) => {
    if (!n) { return; }
    if (n.t === 'map') { n.entries.forEach((e) => { if (e.partial && (tree.tabs || []).indexOf(e.line) < 0) { problems.push({ line: e.line + 1, message: '"' + e.key + '" needs a colon: key: value' }); } visit(e.value); }); }
    if (n.t === 'seq') { n.items.forEach(visit); }
    if (n.open) { problems.push({ line: n.line + 1, message: 'a { or [ is not closed' }); }
  };
  visit(tree);
  return { value: plain(tree), problems };
}

/**
 * @typedef {Object} YamlPaneOptions
 * @property {function(*): (string|Promise<string>)} [toYaml] the model as YAML (default: dumpYaml)
 * @property {function(string): (*|Promise<*>)} [fromYaml] the YAML as the model; throws or rejects with {message, line} (default: readYaml)
 * @property {function(string, *): (Array|Promise<Array>)} [lint] the validator: problems of the text (lint problems, 1-based lines)
 * @property {function(Object): (Array|Promise<Array>)} [complete] completion (drishti-inputs' hook: {word, line, ch, lineNo, text})
 * @property {function(string): boolean} [completeAfter]
 * @property {import('./drishti_selection.js').Selection} [selection]
 * @property {function(string): ?string} [pathOf] an item id's path in the YAML ("pages[0].visuals[2]")
 * @property {function(string): ?string} [idAt] the item id a path names (the deepest path of a clicked line is tried, then its parents)
 * @property {number} [delay=500] ms after typing stops before the YAML is read
 * @property {number} [height=320]
 * @property {boolean} [readOnly]
 * @property {string} [label='YAML']
 * @property {Object} [keys] the editor's own keys
 * @property {Object} [CodeMirror]
 */

/**
 * The YAML pane. Events: model {model, text} (an edit of the YAML read), problem {problems} (it did not read), text {text} (the
 * text as written for the model), cursor {line, ch}.
 */
export class YamlPane {
  /**
   * @param {HTMLElement} el
   * @param {YamlPaneOptions} [o]
   */
  constructor(el, o) {
    this.el = el;
    this.doc = el.ownerDocument;
    this.o = Object.assign({ delay: 500, height: 320, label: 'YAML', toYaml: dumpYaml, fromYaml: (t) => { const r = readYaml(t); if (r.problems.length) { throw r.problems[0]; } return r.value; } }, o);
    this.events = new EventBus();
    this.writing = false;
    this.ranges = new Map();
    el.classList.add('dd-yaml');
    this.status = h(this.doc, 'div', { cls: 'dd-yaml-status', attrs: { role: 'status', 'aria-live': 'polite' } });
    this.host = h(this.doc, 'div', { cls: 'dd-yaml-editor' });
    el.append(this.host, this.status);
    const lint = this.o.lint ? (t) => this.o.lint(t, this.model) : null;
    this.editor = new CodeEditor(this.host, { mode: 'yaml', label: this.o.label, height: this.o.height, readOnly: !!this.o.readOnly, lint, complete: this.o.complete,
      completeAfter: this.o.completeAfter, keys: this.o.keys, CodeMirror: this.o.CodeMirror });
    this.readSoon = debounce(() => this.read(), this.o.delay);
    this.editor.on('change', (c) => {
      if (c.origin === 'setValue' || this.writing) { return; }
      this.ranges = yamlRanges(this.editor.getValue());
      this.dirty = true;
      this.readSoon();
    });
    this.editor.on('cursor', (c) => { this.events.emit('cursor', c); if (this.o.selection && this.o.idAt && this.editor.cm && this.editor.cm.hasFocus()) { this.selectAt(c.line); } });
    if (this.o.selection) { this.off = this.o.selection.on('change', (s) => { if (s.source !== 'yaml') { this.highlight(s.primary); } }); }
  }

  /**
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * The model changed on the visual side (or was loaded): the YAML is written again. An edit being typed is not overwritten by its own
   * echo (o.fromYaml: the change came from this pane).
   * @param {*} model
   * @param {{fromYaml: boolean}} [o]
   * @returns {Promise<string>} the text
   */
  setModel(model, o) {
    this.model = model;
    if ((o && o.fromYaml) || this.echo) { return Promise.resolve(this.editor.getValue()); }
    const ask = this.asked = (this.asked || 0) + 1;
    return Promise.resolve(this.o.toYaml(model)).then((text) => {
      if (ask !== this.asked) { return text; }
      this.setText(text);
      return text;
    });
  }

  /**
   * Puts text in the editor without reading it back (the server wrote it).
   * @param {string} text
   * @returns {void}
   */
  setText(text) {
    this.writing = true;
    try { this.editor.updateValue(text); } finally { this.writing = false; }
    this.dirty = false;
    this.readSoon.cancel();
    this.ranges = yamlRanges(text);
    this.showProblem(null);
    this.events.emit('text', { text });
    if (this.o.selection) { this.highlight(this.o.selection.primary(), false); }
  }

  /** @returns {string} the text */
  getText() { return this.editor.getValue(); }

  /**
   * Reads the YAML now (a pause after typing does it on its own): the "model" event, or its problem shown.
   * @returns {Promise<boolean>} whether it read
   */
  read() {
    const text = this.editor.getValue();
    this.readSoon.cancel();
    return Promise.resolve().then(() => this.o.fromYaml(text)).then((model) => {
      if (text !== this.editor.getValue()) { return false; }
      this.dirty = false;
      this.model = model;
      this.showProblem(null);
      this.echo = true;
      try { this.events.emit('model', { model, text }); } finally { this.echo = false; }
      return true;
    }, (err) => {
      const p = { line: err && err.line ? err.line : 1, message: (err && err.message) || String(err) };
      this.showProblem(p);
      this.events.emit('problem', { problems: [p] });
      return false;
    });
  }

  /** @private the reading's problem under the editor (the visual side keeps what it had) */
  showProblem(p) {
    this.status.textContent = p ? 'Line ' + p.line + ': ' + p.message + ' (the design keeps its last good version)' : '';
    this.el.toggleAttribute('data-dd-problem', !!p);
    if (p) { announce('The YAML does not read: line ' + p.line + ': ' + p.message); }
  }

  /**
   * Highlights an item's lines (and scrolls to them).
   * @param {?string} id
   * @param {boolean} [scroll=true]
   * @returns {void}
   */
  highlight(id, scroll) {
    const path = id && this.o.pathOf ? this.o.pathOf(id) : null, r = path ? this.ranges.get(path) : null;
    if (!r) { this.editor.highlight(null); return; }
    this.editor.highlight(r[0], r[1], { scroll: scroll !== false });
  }

  /** @private a line clicked: the item it belongs to is selected */
  selectAt(line) {
    let path = pathAtLine(this.ranges, line);
    while (path) {
      const id = this.o.idAt(path);
      if (id) { if (this.o.selection.primary() !== id) { this.o.selection.set(id, 'yaml'); this.highlight(id, false); } return; }
      path = path.replace(/(\.[^.[\]]+|\[\d+\])$/, '');
    }
  }

  /**
   * Lays the editor out again (it was hidden).
   * @returns {void}
   */
  refresh() { this.editor.refresh(); }

  /**
   * @returns {void}
   */
  destroy() { if (this.off) { this.off(); } this.readSoon.cancel(); this.editor.destroy(); this.events.clear(); }
}

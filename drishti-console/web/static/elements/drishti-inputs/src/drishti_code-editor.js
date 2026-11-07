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
/* The code editor: the vendored CodeMirror 5 (static/vendor/codemirror, loaded the first time an editor is made, never from a CDN)
   with Drishti's languages (Rachana, Rachana YAML, Python), completion from the function catalogue through a hook (as a person types a
   name, or Ctrl+Space: the arrows, Enter or Tab to put it in, Escape), lint markers from a lint hook (a mark under the text, a sign in
   the gutter, and the problems listed under the editor, each a link to its line), read-only, and a diff view (CodeDiff: side by side
   or unified, highlighted by the same languages, without CodeMirror). Until CodeMirror is on the page the text sits in a plain text
   area, so the value is never lost. */
import { staticUrl, loadScript, loadStyle, announce, h } from '../../drishti-core/src/drishti_index.js';
import { MODES, defineModes, tokens } from './drishti_code-modes.js';
import { wordAt, completions, insertText, lineDiff, sideBySide, diffStats } from './drishti_code-text.js';
import { InputBase, Listbox, Popover, debounce } from './drishti_inputs-dom.js';

let cmReady = null;

/**
 * CodeMirror on the page: the one given, the page's, or the vendored one loaded now (with its style sheet).
 * @param {Object} [given] a CodeMirror to use
 * @returns {Promise<Object>}
 */
export function ensureCodeMirror(given) {
  const root = typeof window !== 'undefined' ? window : globalThis;
  if (given) { defineModes(given); return Promise.resolve(given); }
  if (root.CodeMirror) { defineModes(root.CodeMirror); return Promise.resolve(root.CodeMirror); }
  if (!cmReady) {
    loadStyle(staticUrl('vendor/codemirror/codemirror.css'));
    cmReady = loadScript(staticUrl('vendor/codemirror/codemirror.js')).then(() => { defineModes(root.CodeMirror); return root.CodeMirror; }, (e) => { cmReady = null; throw e; });
  }
  return cmReady;
}

/**
 * A lint problem.
 * @typedef {Object} LintProblem
 * @property {number} line 1-based
 * @property {number} [from] the first column (0-based; default the line's start)
 * @property {number} [to] after the last column (default the line's end)
 * @property {('error'|'warning'|'info')} [severity='error']
 * @property {string} message in words
 */

/**
 * @typedef {Object} CodeEditorOptions
 * @property {string} [value]
 * @property {('rachana'|'yaml'|'python'|'text')} [mode='rachana']
 * @property {boolean} [readOnly=false]
 * @property {boolean} [lineNumbers=true]
 * @property {number} [height=200] px
 * @property {import('./drishti_code-text.js').CatalogueEntry[]} [catalogue] the functions completion offers
 * @property {function({word: string, line: string, ch: number, mode: string}): (Promise<Array>|Array)} [complete] the completion hook
 *   (the server's catalogue); answers CatalogueEntry objects
 * @property {function(string): (Promise<LintProblem[]>|LintProblem[])} [lint] the lint hook
 * @property {number} [lintDelay=400] ms after typing stops
 * @property {string} [label]
 * @property {Object} [CodeMirror] a CodeMirror to use (default: the vendored one)
 */

/**
 * The code editor. Events: change {value}, ready, lint {problems}, completed {entry}.
 */
export class CodeEditor extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {CodeEditorOptions} [options]
   */
  constructor(host, options) {
    super(host, options, 'di-code');
    const o = this.o = Object.assign({ mode: 'rachana', lineNumbers: true, height: 200, lintDelay: 400, value: '' }, this.o);
    if (!MODES[o.mode]) { throw new Error('no code mode ' + o.mode + ' (' + Object.keys(MODES).join(', ') + ')'); }
    this.value = String(o.value || '');
    this.problems = [];
    this.marks = [];
    this.area = this.el('textarea', { cls: 'di-field di-code-fallback', attrs: { 'aria-label': o.label || 'Code', spellcheck: 'false', readonly: o.readOnly || null } });
    this.area.value = this.value;
    this.area.style.height = o.height + 'px';
    this.area.addEventListener('input', () => { this.value = this.area.value; this.changed(); });
    this.box = this.el('div', { cls: 'di-code-box' }, [this.area]);
    this.list = this.el('ul', { cls: 'di-problems', attrs: { 'aria-label': 'Problems', hidden: true } });
    host.append(this.box, this.list);
    host.toggleAttribute('data-readonly', !!o.readOnly);
    this.pop = new Popover(this.doc, { cls: 'di-complete-pop', role: 'presentation', theme: host, onClose: () => { this.completing = null; } });
    this.listbox = new Listbox(this.doc, { label: 'Completions', cap: 50, onPick: (it) => this.accept(it.entry),
      render: (it, el) => { el.append(h(this.doc, 'span', { cls: 'di-option-label', text: it.label }), h(this.doc, 'span', { cls: 'di-option-detail', text: it.detail })); } });
    this.lintSoon = debounce(() => this.runLint(), o.lintDelay);
    this.ready = ensureCodeMirror(o.CodeMirror).then((CM) => this.mount(CM), () => { this.say('The code editor could not load; the text is edited as plain text'); return null; });
  }

  /** @private CodeMirror in place of the text area */
  mount(CM) {
    if (!this.host.isConnected && !this.box.isConnected) { return null; }
    const o = this.o, mode = MODES[o.mode];
    const go = () => {
      this.area.remove();
      const cm = this.cm = CM(this.box, { value: this.value, mode: mode.cm, readOnly: o.readOnly ? true : false, lineNumbers: o.lineNumbers, gutters: ['di-lint-gutter', 'CodeMirror-linenumbers'],
        indentUnit: 2, tabSize: 2, lineWrapping: false, screenReaderLabel: o.label || 'Code', extraKeys: { 'Ctrl-Space': () => this.complete(true), 'Cmd-Space': () => this.complete(true) } });
      cm.setSize(null, o.height);
      const ta = cm.getInputField();
      if (ta) { ta.setAttribute('aria-label', (o.label || 'Code') + (o.readOnly ? ' (read-only)' : '')); }
      cm.on('change', (x, ch) => { this.value = cm.getValue(); this.changed(); if (ch.origin === '+input' && /[\w.$@]/.test(ch.text.join(''))) { this.complete(false); } else if (ch.origin !== 'complete') { this.pop.close(); } });
      cm.on('keydown', (x, e) => this.completionKey(e));
      cm.on('blur', () => this.doc.defaultView.setTimeout(() => { if (!this.cm.hasFocus()) { this.pop.close(); } }, 150));
      this.lintSoon();
      this.emit('ready');
      return cm;
    };
    if (mode.vendor && !(CM.modes && CM.modes[mode.cm])) { return loadScript(staticUrl(mode.vendor)).then(go); }
    return go();
  }

  /** @private */
  changed() { this.emit('change', { value: this.value }); if (this.o.lint) { this.lintSoon(); } }

  /** @private */
  say(t) { this.host.setAttribute('data-note', t); announce(t); }

  // ---- completion ------------------------------------------------------------------------------------------------------------------------
  /**
   * Opens the completions at the cursor (forced: even for an empty word).
   * @param {boolean} [forced]
   * @returns {Promise<void>}
   */
  complete(forced) {
    if (!this.cm || this.o.readOnly || (!this.o.catalogue && !this.o.complete)) { return Promise.resolve(); }
    const cur = this.cm.getCursor(), line = this.cm.getLine(cur.line), w = wordAt(line, cur.ch);
    if (!forced && w.word.replace(/^[$@]/, '').length < 1) { this.pop.close(); return Promise.resolve(); }
    this.asked = (this.asked || 0) + 1;
    const ask = this.asked;
    const ctx = { word: w.word, line: line, ch: cur.ch, mode: this.o.mode };
    return Promise.resolve(this.o.complete ? this.o.complete(ctx) : completions(this.o.catalogue, w.word)).then((list) => {
      if (ask !== this.asked) { return; }
      list = (list || []).slice(0, 50);
      if (!list.length) { this.pop.close(); return; }
      this.completing = { line: cur.line, from: w.from, to: cur.ch, list: list };
      this.listbox.render(list.map((e) => ({ value: e.name, label: e.name, detail: e.signature || e.description || e.kind || '', entry: e })), () => false);
      this.listbox.setActive(0);
      const at = this.cm.cursorCoords(true, 'window');
      const anchor = this.anchor || (this.anchor = h(this.doc, 'span', { cls: 'di-complete-anchor' }));
      if (!anchor.isConnected) { this.doc.body.appendChild(anchor); }
      anchor.style.left = at.left + 'px';
      anchor.style.top = at.top + 'px';
      anchor.style.height = (at.bottom - at.top) + 'px';
      if (!this.pop.isOpen()) { this.pop.open(anchor, this.listbox.el, { focus: false }); } else { this.pop.place(); }
      const ta = this.cm.getInputField();
      if (ta) { ta.setAttribute('aria-controls', this.listbox.id); this.listbox.setActive(0, ta); }
      announce(list.length + (list.length === 1 ? ' completion' : ' completions') + ': ' + list[0].name);
    });
  }

  /** @private the completion's keys while it is open */
  completionKey(e) {
    if (!this.pop.isOpen()) { return; }
    const ta = this.cm.getInputField();
    if (e.key === 'Escape') { e.preventDefault(); this.pop.close(); return; }
    if (e.key === 'Tab' || e.key === 'Enter') { const it = this.listbox.items[this.listbox.active]; if (it) { e.preventDefault(); this.accept(it.entry); } return; }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'PageDown' || e.key === 'PageUp') { this.listbox.key(e, ta); e.preventDefault(); }
  }

  /**
   * Puts a completion in place of the word at the cursor.
   * @param {import('./drishti_code-text.js').CatalogueEntry} entry
   * @returns {void}
   */
  accept(entry) {
    const c = this.completing;
    if (!c || !this.cm) { return; }
    const t = insertText(entry);
    this.cm.replaceRange(t.text, { line: c.line, ch: c.from }, { line: c.line, ch: c.to }, 'complete');
    this.cm.setCursor({ line: c.line, ch: c.from + t.caret });
    this.pop.close();
    this.cm.focus();
    this.emit('completed', { entry: entry });
  }

  // ---- lint -------------------------------------------------------------------------------------------------------------------------------
  /**
   * Asks the lint hook now and draws its problems.
   * @returns {Promise<LintProblem[]>}
   */
  runLint() {
    if (!this.o.lint) { return Promise.resolve([]); }
    const text = this.value;
    return Promise.resolve(this.o.lint(text)).then((problems) => {
      if (text !== this.value) { return this.problems; }
      this.setProblems(problems || []);
      return this.problems;
    }, (err) => { this.setProblems([{ line: 1, severity: 'info', message: 'The check could not run: ' + (err && err.message ? err.message : err) }]); return this.problems; });
  }

  /**
   * Draws problems: marks under the text, signs in the gutter, the list under the editor.
   * @param {LintProblem[]} problems
   * @returns {void}
   */
  setProblems(problems) {
    this.problems = problems.slice().sort((a, b) => a.line - b.line || (a.from || 0) - (b.from || 0));
    this.marks.forEach((m) => m.clear());
    this.marks = [];
    if (this.cm) {
      this.cm.clearGutter('di-lint-gutter');
      this.problems.forEach((p) => {
        const ln = Math.max(0, Math.min(this.cm.lineCount() - 1, p.line - 1)), text = this.cm.getLine(ln), sev = p.severity || 'error';
        const from = p.from === undefined ? 0 : p.from, to = p.to === undefined ? Math.max(text.length, from + 1) : p.to;
        this.marks.push(this.cm.markText({ line: ln, ch: from }, { line: ln, ch: to }, { className: 'di-lint-' + sev, attributes: { title: p.message } }));
        this.cm.setGutterMarker(ln, 'di-lint-gutter', h(this.doc, 'span', { cls: 'di-lint-sign di-lint-' + sev, text: sev === 'error' ? '●' : sev === 'warning' ? '▲' : 'i', attrs: { title: p.message } }));
      });
    }
    this.list.textContent = '';
    this.list.hidden = !this.problems.length;
    this.problems.forEach((p) => this.list.appendChild(h(this.doc, 'li', { cls: 'di-problem di-lint-' + (p.severity || 'error') }, [h(this.doc, 'button', { cls: 'di-link', text: 'Line ' + p.line + ': ' + p.message,
      attrs: { type: 'button' }, on: { click: () => this.goTo(p.line, p.from || 0) } })])));
    this.host.classList.toggle('di-invalid', this.problems.some((p) => (p.severity || 'error') === 'error'));
    this.emit('lint', { problems: this.problems.slice() });
  }

  /**
   * Puts the cursor at a line (1-based) and column.
   * @param {number} line
   * @param {number} [ch]
   * @returns {void}
   */
  goTo(line, ch) { if (this.cm) { this.cm.setCursor({ line: line - 1, ch: ch || 0 }); this.cm.focus(); } else { this.area.focus(); } }

  // ---- value -----------------------------------------------------------------------------------------------------------------------------
  /**
   * The text.
   * @returns {string}
   */
  getValue() { return this.cm ? this.cm.getValue() : this.value; }

  /**
   * Replaces the text.
   * @param {string} text
   * @returns {void}
   */
  setValue(text) { this.value = String(text || ''); if (this.cm) { this.cm.setValue(this.value); } else { this.area.value = this.value; } }

  /**
   * Makes it read-only, or editable.
   * @param {boolean} on
   * @returns {void}
   */
  setReadOnly(on) { this.o.readOnly = !!on; this.host.toggleAttribute('data-readonly', !!on); if (this.cm) { this.cm.setOption('readOnly', !!on); } else { this.area.readOnly = !!on; } }

  /**
   * Puts the keyboard focus in the editor.
   * @returns {void}
   */
  focus() { if (this.cm) { this.cm.focus(); } else { this.area.focus(); } }

  /**
   * Takes the editor out.
   * @returns {void}
   */
  destroy() { this.lintSoon.cancel(); if (this.anchor) { this.anchor.remove(); } super.destroy(); }
}

/**
 * A diff of two texts, side by side or unified, highlighted by the language; no CodeMirror needed. Events: none (read-only).
 */
export class CodeDiff extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {{original: string, value: string, mode: string, layout: ('side'|'unified'), labels: string[]}} o labels: the two sides'
   *   names (default Before, After)
   */
  constructor(host, o) {
    super(host, o, 'di-diff-view');
    this.o = Object.assign({ mode: 'text', layout: 'side', labels: ['Before', 'After'] }, this.o);
    this.render();
  }

  /**
   * Draws the diff again (after setTexts or a layout change).
   * @returns {void}
   */
  render() {
    const o = this.o, ops = this.ops = lineDiff(o.original || '', o.value || ''), st = diffStats(ops);
    this.host.textContent = '';
    const hl = (text) => { const span = this.el('span', { cls: 'di-code-line' }); tokens(o.mode, text)[0].forEach((t) => span.appendChild(this.el('span', { cls: t.style ? 'cm-' + t.style.split(' ').join(' cm-') : null, text: t.text }))); return span; };
    this.host.appendChild(this.el('div', { cls: 'di-diff-head' }, [this.el('b', { text: o.labels[0] + ' → ' + o.labels[1] }), ' ', this.el('span', { cls: 'di-diff-add', text: '+' + st.added }), ' ',
      this.el('span', { cls: 'di-diff-del', text: '−' + st.removed }), this.el('span', { cls: 'di-sp' }),
      this.el('button', { cls: 'di-btn', text: o.layout === 'side' ? 'Unified' : 'Side by side', attrs: { type: 'button' }, data: { act: 'layout' }, on: { click: () => { o.layout = o.layout === 'side' ? 'unified' : 'side'; this.render(); } } })]));
    const table = this.el('div', { cls: 'di-diff di-diff-' + o.layout, attrs: { role: 'table', 'aria-label': 'Changes: ' + st.added + ' lines added, ' + st.removed + ' removed' } });
    const cell = (side, mark) => side ? [this.el('span', { cls: 'di-ln', text: String(side.n), attrs: { role: 'cell' } }), this.el('span', { cls: 'di-mark', text: mark, attrs: { role: 'cell', 'aria-label': mark === '+' ? 'added' : mark === '−' ? 'removed' : null } }), this.el('span', { cls: 'di-code-cell', attrs: { role: 'cell' } }, [hl(side.text)])]
      : [this.el('span', { cls: 'di-ln', attrs: { role: 'cell' } }), this.el('span', { cls: 'di-mark', attrs: { role: 'cell' } }), this.el('span', { cls: 'di-code-cell di-empty-side', attrs: { role: 'cell' } })];
    if (o.layout === 'side') {
      sideBySide(ops).forEach((r) => table.appendChild(this.el('div', { cls: 'di-diff-row', attrs: { role: 'row' } }, [this.el('span', { cls: 'di-side di-k-' + (r.left ? r.left.kind : 'none') }, cell(r.left, r.left && r.left.kind !== 'equal' ? '−' : ' ')),
        this.el('span', { cls: 'di-side di-k-' + (r.right ? r.right.kind : 'none') }, cell(r.right, r.right && r.right.kind !== 'equal' ? '+' : ' '))])));
    } else {
      ops.forEach((x) => table.appendChild(this.el('div', { cls: 'di-diff-row di-k-' + x.op, attrs: { role: 'row' } }, cell({ n: x.op === 'insert' ? x.b : x.a, text: x.text }, x.op === 'insert' ? '+' : x.op === 'delete' ? '−' : ' '))));
    }
    this.host.appendChild(table);
  }

  /**
   * Shows two other texts.
   * @param {string} original
   * @param {string} value
   * @returns {void}
   */
  setTexts(original, value) { this.o.original = original; this.o.value = value; this.render(); }

  /**
   * How many lines were added, removed and kept.
   * @returns {{added: number, removed: number, same: number}}
   */
  stats() { return diffStats(this.ops); }
}

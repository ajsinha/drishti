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
/* The cell editor as a person sees it: a field over the cell (it floats in the grid's scrolling area, so drawing the rows again never
   loses it, and it follows the cell as the grid scrolls). Per type: text, a number (shown with every digit in the grid's locale, read
   back loosely: 1.25m, (2,000), −5k), a date (drishti-inputs' business-date picker when that library is on the page, else the browser's
   date field), a formula (drishti-inputs' one-line CodeEditor in the column's codeMode, completing through its complete hook, when that
   library is on the page), a choice of the column's options (drishti-inputs' Select when that library is on the page, else a field that
   takes an option's words or value), and a checkbox (no field: Space, Enter or a double click turns it). Started by Enter,
   F2, a double click or typing (the typed character replaces the value); Enter commits and moves down (Shift+Enter up), Tab commits and
   moves right (Shift+Tab left), Escape cancels, leaving the field commits. A value that is not valid keeps the field open with the
   problem in words under it (role alert). The model's editing (drishti_grid-edit.js) does the rest. */
import { h, loaded, announce, numberEditText, isoDate } from '../../drishti-core/src/drishti_index.js';
import { editorOf, parseCellText } from './drishti_grid-edit.js';

/**
 * The floating cell editor of a drawn grid.
 */
export class CellEditor {
  /**
   * @param {Object} view the Grid (drishti_grid-view.js)
   */
  constructor(view) {
    this.view = view;
    this.doc = view.doc;
    this.at = null;
    this.el = null;
    this.busy = false;
  }

  /**
   * Whether a cell is being edited.
   * @returns {boolean}
   */
  isActive() { return !!this.at; }

  /**
   * Starts editing a cell (when it may be edited); a checkbox turns at once.
   * @param {number} r
   * @param {number} c
   * @param {{initial: string}} [o] initial: a typed character that replaces the value
   * @returns {boolean} whether editing started (or the checkbox turned)
   */
  start(r, c, o) {
    const v = this.view, m = v.model;
    if (this.at) { this.cancel(); }
    if (!m.editing.canEdit(r, c)) { v.say(m.pivotMode ? 'A pivot\'s figures are computed: edit the rows behind them' : 'This cell cannot be edited'); return false; }
    const col = m.shownColumns()[c], ed = editorOf(col), cur = m.cellValue(r, c);
    if (ed === 'checkbox') { this.save(r, c, !cur, null); return true; }
    v.focusCell(r, c);
    // the rows in view drawn now when the cell is not: the editor goes over its cell at once (an editor without its cell is hidden, and its
    // focus would scroll the grid back to where the editor last was, so the cell never came into view)
    if (!this.doc.getElementById(v.uid + '-r' + r + '-c' + c)) { v.body.render(); }
    this.at = { r: r, c: c, ed: ed, col: col, old: cur };
    const box = this.el = h(this.doc, 'div', { cls: 'dg-editor dg-editor-' + ed, attrs: { role: 'presentation' } });
    const msg = this.msg = h(this.doc, 'div', { cls: 'dg-editor-msg', attrs: { role: 'alert', id: v.uid + '-edit-msg', hidden: true } });
    const label = col.header + ', row ' + (r + 1);
    if (ed === 'select' && loaded('drishti-inputs') && loaded('drishti-inputs').Select) {   // drishti-inputs' Select: the options by their index
      const host = h(this.doc, 'div', { cls: 'dg-editor-picker dg-editor-select' });
      box.appendChild(host);
      const opts = (col.options || []).map((x, i) => ({ value: String(i), label: x !== null && typeof x === 'object' && x.label !== undefined ? x.label : String(x !== null && typeof x === 'object' ? x.value : x) }));
      let at = (col.options || []).findIndex((x) => (x !== null && typeof x === 'object' ? x.value : x) === cur);
      if (o && o.initial) { const t = opts.findIndex((x) => x.label.toLowerCase().indexOf(o.initial.toLowerCase()) === 0); if (t >= 0) { at = t; } }   // a typed letter picks the first option it starts
      const sel = this.choice = new (loaded('drishti-inputs').Select)(host, { options: opts, value: at >= 0 ? String(at) : null, placeholder: at >= 0 ? undefined : 'Pick one', label: label });
      this.input = sel.control;
      this.input.setAttribute('aria-describedby', msg.id);
      sel.on('pick', () => Promise.resolve().then(() => { if (this.at && this.choice === sel) { this.commit({ dr: 0, dc: 0 }); } }));
    } else if (ed === 'date' && loaded('drishti-inputs') && loaded('drishti-inputs').DatePicker) {
      const host = h(this.doc, 'div', { cls: 'dg-editor-picker' });
      box.appendChild(host);
      const picker = this.picker = new (loaded('drishti-inputs').DatePicker)(host, { value: cur || null, businessOnly: !!col.businessOnly, calendar: col.calendar, label: label, shortcuts: true });
      this.input = picker.input;
      this.input.setAttribute('aria-describedby', msg.id);
      picker.on('change', () => Promise.resolve().then(() => { if (this.at && this.picker === picker) { this.commit({ dr: 0, dc: 0 }); } }));
      if (o && o.initial) { this.input.value = o.initial; }
    } else if (ed === 'code' && loaded('drishti-inputs') && loaded('drishti-inputs').CodeEditor) {
      const host = h(this.doc, 'div', { cls: 'dg-editor-code' });
      box.appendChild(host);
      const code = this.code = new (loaded('drishti-inputs').CodeEditor)(host, { mode: col.codeMode || 'rachana', singleLine: true, lineNumbers: false, height: 'auto',
        tabLeaves: true, lineWrapping: false, label: label, complete: col.complete || null, value: o && o.initial ? o.initial : cur === null || cur === undefined ? '' : String(cur) });
      this.input = { focus: () => code.focus(), setAttribute: (k, val) => host.setAttribute(k, val), get value() { return code.getValue(); } };
      Promise.resolve(code.ready).then(() => {
        if (this.code !== code || !code.cm) { return; }
        code.cm.getInputField().setAttribute('aria-describedby', msg.id);
        code.focus();
        code.cm.setCursor(code.cm.lineCount(), 0);
      });
    } else {
      const inp = this.input = h(this.doc, 'input', { cls: 'dg-editor-input' + (ed === 'number' ? ' dg-num' : ''), attrs: { type: ed === 'date' ? 'date' : 'text', 'aria-label': label,
        'aria-describedby': msg.id, inputmode: ed === 'number' ? 'decimal' : null, spellcheck: 'false', autocomplete: 'off' } });
      inp.value = o && o.initial ? o.initial : ed === 'number' ? numberEditText(cur, m.editing.o.locale) : ed === 'date' ? isoDate(cur) : cur === null || cur === undefined ? '' : String(cur);
      box.appendChild(inp);
    }
    box.appendChild(msg);
    box.addEventListener('keydown', (e) => this.key(e), true);
    box.addEventListener('focusout', () => this.doc.defaultView.setTimeout(() => this.left(), 0));
    v.body.area.appendChild(box);
    this.place();
    this.input.focus();
    if (this.input.select && !(o && o.initial)) { this.input.select(); }
    if (this.picker && !(o && o.initial)) { this.picker.open(); this.input.focus(); }
    if (this.choice) {                                                                 // the list opens on the value once the focus has settled: arrows, Enter picks
      const sel = this.choice;
      this.doc.defaultView.setTimeout(() => { if (this.at && this.choice === sel) { sel.focus(); sel.open(); } }, 0);
    }
    m.live.emit('cellEditStarted', { row: r, col: c, colId: col.id, rowId: m.idAt(r), value: cur, editor: ed });
    announce('Editing ' + label + '. Enter saves, Escape cancels');
    return true;
  }

  /**
   * Puts the editor over its cell again (after the rows were drawn or scrolled); hidden while the cell is out of view.
   * @returns {void}
   */
  place() {
    if (!this.at || !this.el) { return; }
    const v = this.view, cell = this.doc.getElementById(v.uid + '-r' + this.at.r + '-c' + this.at.c);
    if (!cell) { this.el.style.visibility = 'hidden'; return; }
    const a = v.body.area.getBoundingClientRect(), r = cell.getBoundingClientRect();
    const shown = this.el.style.visibility === 'hidden';               // a cell scrolled into view after editing started (out of view at first)
    this.el.style.visibility = 'visible';
    if (shown && this.input && !this.el.contains(this.doc.activeElement)) { this.doc.defaultView.setTimeout(() => { if (this.at && this.input) { this.input.focus(); } }, 0); }
    this.el.style.left = (r.left - a.left) + 'px';
    this.el.style.top = (r.top - a.top) + 'px';
    this.el.style.width = Math.max(r.width, this.at.ed === 'date' ? 150 : this.at.ed === 'code' ? 220 : 60) + 'px';
    this.el.style.height = r.height + 'px';
  }

  /** @private the editor's keys (seen before the field's own) */
  key(e) {
    if (!this.at) { return; }
    const k = e.key;
    if (this.picker && this.picker.pop.isOpen() && (k === 'Escape' || k === 'ArrowDown' || k === 'ArrowUp')) { return; }   /* the calendar's own */
    if (this.choice && this.choice.pop.isOpen() && k !== 'Tab') { return; }                                                     /* the open list's own: Enter picks, Escape closes */
    if (this.code && this.code.pop.isOpen() && (k === 'Escape' || k === 'Enter' || k === 'Tab' || k === 'ArrowDown' || k === 'ArrowUp')) { return; }   /* the completion's own */
    if (k === 'Escape') { e.preventDefault(); e.stopPropagation(); this.cancel(); return; }
    if (k === 'Enter') { e.preventDefault(); e.stopPropagation(); this.commit({ dr: e.shiftKey ? -1 : 1, dc: 0 }); return; }
    if (k === 'Tab') { e.preventDefault(); e.stopPropagation(); this.commit({ dr: 0, dc: e.shiftKey ? -1 : 1 }); }
  }

  /** @private the focus left the editor (and its calendar): commit, or give up when the value is not valid */
  left() {
    if (!this.at || this.busy) { return; }
    const a = this.doc.activeElement;
    if (this.el && this.el.contains(a)) { return; }
    if (this.picker && this.picker.pop.el && this.picker.pop.el.contains(a)) { return; }
    if (this.choice && this.choice.pop.el && this.choice.pop.el.contains(a)) { return; }
    if (this.code && this.code.pop.el && this.code.pop.el.contains(a)) { return; }
    this.commit(null, true);
  }

  /** @private the editor's value as the column's */
  read() {
    const at = this.at;
    if (at.ed === 'select') {
      const pick = this.choice ? this.choice.getValue() : this.input.value, i = pick === null || pick === '' ? -1 : +pick, x = (at.col.options || [])[i];
      return { value: x !== null && typeof x === 'object' ? x.value : x, error: null };
    }
    if (this.picker) {
      const t = this.input.value.trim();
      if (!t) { return { value: null, error: null }; }
      if (!this.picker.validate()) { return { value: undefined, error: this.picker.msg.textContent || at.col.header + ': not a date' }; }
      return { value: this.picker.getValue(), error: null };
    }
    return parseCellText(at.col, this.input.value, { locale: this.view.model.editing.o.locale });
  }

  /**
   * Saves the editor's value (validated; a problem keeps the editor open) and moves the focus.
   * @param {?{dr: number, dc: number}} move where the focus goes after (null: it stays)
   * @param {boolean} [leaving] the focus left: a value that is not valid is given up, not kept open
   * @returns {Promise<boolean>} whether it was saved
   */
  commit(move, leaving) {
    if (!this.at || this.busy) { return Promise.resolve(false); }
    const at = this.at, got = this.read();
    if (got.error) { return Promise.resolve(this.problem(got.error, leaving)); }
    return this.save(at.r, at.c, got.value, move, leaving);
  }

  /** @private one cell's value through the model's editing: validated now, the editor closed and the focus moved at once (the value is
   *  in the row already), the write-back's answer told when it comes (a refusal puts the old value back) */
  save(r, c, value, move, leaving) {
    const v = this.view, m = v.model, col = m.shownColumns()[c];
    const plan = m.editing.plan([{ r: r, c: c, value: value }]);
    if (plan.problems.length) { return Promise.resolve(this.problem(plan.problems[0].message, leaving)); }
    const p = m.editing.apply(plan.edits, 'edit');
    this.close(true);
    if (move) { m.navigator.setFocus(r + move.dr, c + move.dc); m.selection.startRange(m.navigator.focus.row, m.navigator.focus.col, {}); v.afterFocus(true); }
    v.body.scroller.focus({ preventScroll: true });
    return p.then((tx) => {
      if (tx.status === 'proposed') { v.say('Sent for approval (' + tx.proposalId + '): the cell is marked until a second person decides'); }
      else if (tx.edits.length) { v.say('Saved ' + col.header); }
      return true;
    }, (err) => { v.say('Not saved: ' + err.message); return false; });
  }

  /** @private a value that is not valid */
  problem(text, leaving) {
    if (leaving || !this.el) { this.close(false); this.view.say('Not saved: ' + text); return false; }
    this.msg.hidden = false;
    this.msg.textContent = text;
    this.input.setAttribute('aria-invalid', 'true');
    this.el.classList.add('dg-editor-bad');
    this.input.focus();
    return false;
  }

  /**
   * Leaves the cell as it was.
   * @returns {void}
   */
  cancel() { if (this.at) { this.close(false); this.view.say('Edit cancelled'); this.view.body.scroller.focus({ preventScroll: true }); } }

  /** @private */
  close(saved) {
    const at = this.at;
    this.at = null;
    if (this.picker) { this.picker.close(); this.picker = null; }
    if (this.code) { this.code.pop.close(); this.code = null; }
    if (this.choice) { this.choice.destroy(); this.choice = null; }
    if (this.el) { this.el.remove(); this.el = null; }
    if (at) { this.view.model.live.emit('cellEditStopped', { row: at.r, col: at.c, saved: !!saved }); }
    this.view.body.schedule();
  }
}

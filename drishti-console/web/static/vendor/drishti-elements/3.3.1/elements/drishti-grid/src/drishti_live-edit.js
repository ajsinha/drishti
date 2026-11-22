/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The grid's live-mode edit, the screen (the logic is drishti_live-edit-model.js). For a live table whose declaration lets the painter edit
   (`edit`: cells or beside) and whose schema has `write`: cells mode makes the write columns editable in the grid (an editor by each rule),
   beside mode opens a record panel (a drishti-inputs Form) for the selected row with Save and Cancel. Either way the page checks the
   rules first, sends one change per row with a ULID (a retry after a network failure reuses it), never changes the row (the cell carries a
   mark: sending, queued, held with the host's message) until the table's patch arrives, and shows the host's refusals: 409 who changed the
   row and when with Edit again, 422 each message on its column, 403 and 405 the row read only, 404 the row gone. A table without `write`
   gets no edit control. The held-changes tray (the column `state`: released or rejected) is decided the same way; a rejection asks its reason.
   LiveNotices is the other half of the live panel: the paused and stale banners from the schema and "N new" when rows arrive while the page
   is scrolled down. */
import { h, announce } from '../../drishti-core/src/drishti_index.js';
import { Form } from '../../drishti-inputs/src/drishti_index.js';
import { editorFor, checkRule, checkChange, buildChange, mayEdit, outcome, changedWords, reasonMode, PendingChanges } from './drishti_live-edit-model.js';

const show = (v) => (v === null || v === undefined || v === '' ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v));

/**
 * The live edit of one grid.
 */
export class LiveEdit {
  /**
   * @param {HTMLElement} host where the status, the reason and the record panel go
   * @param {Object} o
   * @param {Object} o.spec the WriteSpec (writeSpec(schema)): required
   * @param {('cells'|'beside')} o.mode
   * @param {{write: function(Object): Promise<Object>}} o.table the live table (write answers {change, state, message}; a refusal rejects with status and error)
   * @param {function(Object): string} o.idOf the grid's row id of a row
   * @param {function(string): ?Object} o.rowOf the row held for an id
   * @param {function(string): void} o.drop takes a row away (it is gone: 404)
   * @param {function(string): string} [o.label] a column's label
   * @param {function(): Object} [o.grid] the grid (the cells mode's editors; the record panel's selection)
   */
  constructor(host, o) {
    this.host = host;
    this.doc = host.ownerDocument;
    this.o = o;
    this.spec = o.spec;
    this.pending = new PendingChanges();
    this.locked = new Map();
    this.lockAll = null;
    this.staged = [];
    this.failed = [];
    this.panel = null;
    this.draw();
  }

  /** @returns {boolean} whether the cells are edited in the grid */
  get cells() { return this.o.mode === 'cells'; }

  /** @private */
  draw() {
    const d = this.doc, mode = this.spec.reason !== 'none' || this.spec.decides;
    this.status = h(d, 'p', { cls: 'dg-le-status', attrs: { role: 'status', 'aria-live': 'polite' }, data: { liveEditStatus: '' } });
    this.alert = h(d, 'div', { cls: 'dg-le-alert', attrs: { role: 'alert', hidden: true }, data: { liveEditAlert: '' } });
    this.reasonBox = h(d, 'div', { cls: 'dg-le-reason', attrs: { hidden: !mode || !this.cells } });
    this.reasonIn = h(d, 'input', { cls: 'dg-le-reason-in', attrs: { type: 'text', 'aria-label': 'Reason for the change', placeholder: this.spec.reason === 'required' ? 'Reason (required)' : 'Reason' }, data: { liveEditReason: '' },
      on: { keydown: (e) => { if (e.key === 'Enter') { e.preventDefault(); this.sendStaged(); } } } });
    this.reasonBox.append(h(d, 'label', { text: 'Reason ' }, []), this.reasonIn);
    this.panelHost = h(d, 'div', { cls: 'dg-le-panel', attrs: { hidden: true }, data: { liveEditPanel: '' } });
    this.host.append(this.status, this.alert, this.reasonBox, this.panelHost);
  }

  /**
   * Makes a column editable by its write rule (cells mode); other columns are returned as they are.
   * @param {Object} col a grid column ({field, header, type, ...})
   * @returns {Object}
   */
  column(col) {
    const rule = this.spec.columns[col.field];
    if (!this.cells || !rule) { return col; }
    const name = col.field;
    return Object.assign({}, col, { editable: (row) => this.may(row, name), editor: editorFor(rule), required: rule.required,
      options: rule.options ? rule.options.map((x) => ({ value: x.value, label: x.label })) : undefined, validate: (v) => checkRule(name, rule, v) });
  }

  /**
   * The grid options of the edit: the propose hook and Tab to the next editable cell.
   * @returns {Object}
   */
  gridOptions() { return this.cells ? { editing: { propose: (tx) => this.propose(tx), tabEditable: true } } : {}; }

  /**
   * Whether a cell of a row may be edited now: a write column, not masked, the row not read only and no change of its own waiting.
   * @param {Object} row
   * @param {string} column
   * @returns {boolean}
   */
  may(row, column) {
    const id = this.o.idOf(row);
    return mayEdit(this.spec, row, column, this.lockAll || this.locked.get(id) || null) && !this.pending.get(id);
  }

  /** @private the editing model of the grid */
  get editing() { const g = this.o.grid && this.o.grid(); return g ? g.model.editing : null; }

  /** @private marks a row's columns */
  mark(rowId, columns, mark, note) { const e = this.editing; if (e) { columns.forEach((c) => e.setMark(rowId, c, mark, note)); } }

  /** @private the column ids of a change's columns (the grid's id of a field is its field) */
  colId(field) { const g = this.o.grid && this.o.grid(), c = g && g.model.shownColumns().find((x) => x.field === field); return c ? c.id : field; }

  // ---- the cells' edit ----------------------------------------------------------------------------------------------------------------
  /**
   * The editing model's hook: a transaction's cells as one change per row, checked, then sent (or staged until the reason is given).
   * @param {{edits: Array<{rowId: string, colId: string, field: string, value: *}>}} tx
   * @returns {Promise<void>} rejects when a value breaks a rule
   */
  propose(tx) {
    const byRow = new Map();
    tx.edits.forEach((e) => { const x = byRow.get(e.rowId) || { rowId: e.rowId, set: {}, cols: {} }; x.set[e.field] = e.value; x.cols[e.field] = e.colId; byRow.set(e.rowId, x); });
    const jobs = Array.from(byRow.values());
    for (const j of jobs) {                                   // the rules first, in the page: nothing is sent when one is broken
      const bad = checkChange(this.spec, j.set, 'x');
      const first = Object.keys(bad).filter((k) => k !== 'reason')[0];
      if (first) { return Promise.reject(new Error(bad[first])); }
    }
    this.clearAlert();
    const need = jobs.some((j) => reasonMode(this.spec, j.set) === 'required') && !this.reasonIn.value.trim();
    if (need) {
      jobs.forEach((j) => this.mark(j.rowId, Object.values(j.cols), 'staged', 'Waiting for a reason'));
      this.staged = this.staged.concat(jobs);
      this.showStaged();
      return Promise.resolve();
    }
    jobs.forEach((j) => this.mark(j.rowId, Object.values(j.cols), 'sending', 'Sending'));
    const reason = this.reasonIn.value.trim();
    this.reasonIn.value = '';
    jobs.forEach((j) => { this.send(j, reason); });
    return Promise.resolve();
  }

  /** @private the alert that asks for the reason of staged changes */
  showStaged() {
    const d = this.doc, n = this.staged.length;
    this.alert.hidden = false;
    this.alert.textContent = '';
    this.alert.setAttribute('data-kind', 'reason');
    this.alert.append('A reason is needed to send ' + n + (n === 1 ? ' change' : ' changes') + '. ',
      h(d, 'button', { cls: 'dg-le-btn', text: 'Send', attrs: { type: 'button' }, data: { act: 'send-staged' }, on: { click: () => this.sendStaged() } }), ' ',
      h(d, 'button', { cls: 'dg-le-btn', text: 'Discard', attrs: { type: 'button' }, data: { act: 'discard-staged' }, on: { click: () => this.discardStaged() } }));
    this.reasonBox.hidden = false;
    this.reasonIn.focus();
    announce('A reason is needed to send the change');
  }

  /** @private */
  sendStaged() {
    if (!this.staged.length) { return; }
    const reason = this.reasonIn.value.trim();
    if (!reason) { this.reasonIn.setAttribute('aria-invalid', 'true'); this.reasonIn.focus(); announce('A reason is required'); return; }
    this.reasonIn.removeAttribute('aria-invalid');
    const jobs = this.staged;
    this.staged = [];
    this.clearAlert();
    this.reasonIn.value = '';
    jobs.forEach((j) => { this.mark(j.rowId, Object.values(j.cols), 'sending', 'Sending'); this.send(j, reason); });
  }

  /** @private */
  discardStaged() {
    this.staged.forEach((j) => this.mark(j.rowId, Object.values(j.cols), null));
    this.staged = [];
    this.clearAlert();
  }

  /**
   * Sends one row's change and shows the answer.
   * @param {{rowId: string, set: Object, cols: Object}} j
   * @param {string} reason
   * @returns {Promise<Object>} the outcome (see outcome())
   */
  send(j, reason) {
    const row = this.o.rowOf(j.rowId);
    if (!row) { return Promise.resolve(outcome(null, { status: 404, error: { message: 'The row is gone' } })); }
    const change0 = buildChange(this.spec, row, j.set, reason, '');
    const id = this.pending.idFor(change0.key, change0.basedOn, j.set, reason), change = Object.assign({}, change0, { id: id });
    this.pending.mark(j.rowId, { id: id, state: 'sending', message: '', columns: Object.keys(j.set) });
    return Promise.resolve().then(() => this.o.table.write(change)).then((answer) => answer, (error) => { j.error = error; return null; }).then((answer) => {
      const out = outcome(answer, j.error);
      j.error = null;
      if (!out.retry) { this.pending.settled(change.key, change.basedOn, j.set, reason); }
      this.settle(j, change, out, reason);
      return out;
    });
  }

  /** @private what a host's answer does to the screen */
  settle(j, change, out, reason) {
    const cols = Object.values(j.cols), field = Object.keys(j.set), rowId = j.rowId, row = this.o.rowOf(rowId);
    const clear = () => { this.pending.arrived(rowId); this.mark(rowId, cols, null); };
    if (out.kind === 'queued' || out.kind === 'held' || out.kind === 'applied') {
      this.pending.mark(rowId, { id: change.id, state: out.kind, message: out.message, columns: field });
      this.mark(rowId, cols, out.kind, out.message || out.kind);
      this.say(this.describe(row, j.set) + ': ' + (out.kind === 'held' ? 'held' + (out.message ? ', ' + out.message : '') : out.kind === 'queued' ? 'queued' + (out.message ? ', ' + out.message : '') : 'applied, waiting for the table'));
      return;
    }
    clear();
    if (out.kind === 'refused') { this.fail(out.message || 'The change was refused', rowId); return; }
    if (out.kind === 'conflict') { this.conflict(rowId, j, out); return; }
    if (out.kind === 'invalid') {
      out.fields.forEach((f) => { const c = f.column && j.cols[f.column]; if (c) { this.mark(rowId, [c], 'invalid', f.message); } });
      this.fail(out.fields.length ? out.fields.map((f) => (f.column ? f.column + ': ' : '') + f.message).join('; ') : out.message, rowId);
      if (this.panel) { this.panel.invalid(out); }
      return;
    }
    if (out.kind === 'forbidden' || out.kind === 'unsupported') {
      if (out.kind === 'unsupported') { this.lockAll = out.message; } else { this.locked.set(rowId, out.message); }
      this.fail(out.message + ' (read only)', rowId);
      this.afterChange();
      return;
    }
    if (out.kind === 'gone') { this.say(this.describe(row, j.set) + ': the row is gone'); this.o.drop(rowId); if (this.panel) { this.closePanel(); } return; }
    this.failed.push({ j: j, reason: reason });                                  // the network: the same change id goes again
    this.fail(out.message + ' ', rowId, true);
  }

  /** @private */
  describe(row, set) { return (row ? this.o.idOf(row) : '') + ' ' + Object.keys(set).map((k) => (this.o.label ? this.o.label(k) : k) + ' ' + show(set[k])).join(', '); }

  /** @private the 409: who changed the row, the value now, and Edit again */
  conflict(rowId, j, out) {
    const d = this.doc, row = this.o.rowOf(rowId), cols = Object.keys(j.set);
    this.conflictOf = { rowId: rowId, j: j, out: out };
    const caught = row && this.spec.version && out.current && row[this.spec.version] === out.current.version;
    const now = row ? cols.map((c) => (this.o.label ? this.o.label(c) : c) + ' is now ' + (caught ? show(row[c]) : 'being updated')).join(', ') : '';
    this.alert.hidden = false;
    this.alert.textContent = '';
    this.alert.setAttribute('data-kind', 'conflict');
    this.alert.append(changedWords(out.current) + '. ' + now + '. ',
      h(d, 'button', { cls: 'dg-le-btn', text: 'Edit again', attrs: { type: 'button' }, data: { act: 'edit-again' }, on: { click: () => this.editAgain(rowId, cols[0]) } }));
    this.say('Not saved: the row was changed by someone else');
    announce('Not saved: the row was changed by someone else');
  }

  /** @private Edit again: the editor on the cell (or the panel on the row), on the version now held */
  editAgain(rowId, column) {
    this.clearAlert();
    if (!this.cells) { const row = this.o.rowOf(rowId); if (row) { this.openPanel(row); } return; }
    const g = this.o.grid(), m = g.model, c = m.shownColumns().findIndex((x) => x.field === column);
    let r = -1;
    for (let i = 0; i < m.rowCount(); i++) { if (m.idAt(i) === rowId) { r = i; break; } }
    if (r >= 0 && c >= 0) { g.startEditing(r, c); }
  }

  /** @private an error in words, with Try again for a network failure */
  fail(message, rowId, retry) {
    const d = this.doc;
    this.alert.hidden = false;
    this.alert.textContent = '';
    this.alert.setAttribute('data-kind', retry ? 'failed' : 'refused');
    this.alert.append(message);
    if (retry) { this.alert.append(h(d, 'button', { cls: 'dg-le-btn', text: 'Try again', attrs: { type: 'button' }, data: { act: 'retry' }, on: { click: () => this.retry() } })); }
    this.say('Not saved: ' + message);
    announce('Not saved: ' + message);
  }

  /** @private sends the changes that failed on the network again: the same change ids */
  retry() {
    const jobs = this.failed;
    this.failed = [];
    this.clearAlert();
    jobs.forEach((x) => { this.mark(x.j.rowId, Object.values(x.j.cols), 'sending', 'Sending'); this.send(x.j, x.reason); });
  }

  /** @private */
  clearAlert() { this.conflictOf = null; this.alert.hidden = true; this.alert.textContent = ''; this.alert.removeAttribute('data-kind'); }

  /** @private */
  say(text) { this.status.textContent = text; }

  /** @private the grid draws again (a lock changed) */
  afterChange() { const g = this.o.grid && this.o.grid(); if (g && g.refresh) { g.refresh(); } }

  // ---- the table's patches ------------------------------------------------------------------------------------------------------------
  /**
   * A patch named rows (changed or gone): their pending marks go, and the panel notices a newer version.
   * @param {string[]} rowIds
   * @returns {void}
   */
  patched(rowIds) {
    rowIds.forEach((id) => {
      const p = this.pending.get(id);
      if (p) { this.pending.arrived(id); const e = this.editing; if (e) { p.columns.forEach((c) => e.setMark(id, this.colId(c), null)); } }
      if (this.panel && this.panel.rowId === id) { this.panel.rowChanged(this.o.rowOf(id)); }
      if (this.conflictOf && this.conflictOf.rowId === id) { this.conflict(id, this.conflictOf.j, this.conflictOf.out); }   // the newer row arrived: its value is told
    });
  }

  /**
   * The rows were read again (a gap, a resync): nothing waits for a patch any more.
   * @returns {void}
   */
  reset() {
    const e = this.editing;
    this.pending.rows.forEach((p, id) => { if (e) { p.columns.forEach((c) => e.setMark(id, this.colId(c), null)); } });
    this.pending.rows.clear();
  }

  // ---- the record panel (beside) --------------------------------------------------------------------------------------------------------
  /**
   * The grid's selection changed: beside mode opens the record of the one row selected.
   * @param {string[]} ids
   * @returns {void}
   */
  select(ids) {
    if (this.cells) { return; }
    const row = ids.length === 1 ? this.o.rowOf(ids[0]) : null;
    if (row) { this.openPanel(row); } else { this.closePanel(); }
  }

  /** @private */
  openPanel(row) {
    this.closePanel();
    this.panel = new EditPanel(this.panelHost, this, row);
    this.panelHost.hidden = false;
  }

  /** @private */
  closePanel() { if (this.panel) { this.panel.destroy(); this.panel = null; } this.panelHost.hidden = true; }

  /**
   * Takes the edit down.
   * @returns {void}
   */
  destroy() { this.closePanel(); this.host.textContent = ''; }
}

/**
 * The record panel of beside mode: the write columns of one row as a form with Save and Cancel (and the reason).
 */
class EditPanel {
  /**
   * @param {HTMLElement} host
   * @param {LiveEdit} ctl
   * @param {Object} row
   */
  constructor(host, ctl, row) {
    this.host = host;
    this.ctl = ctl;
    this.doc = host.ownerDocument;
    this.rowId = ctl.o.idOf(row);
    this.row = row;
    this.opened = Object.assign({}, row);
    this.version = ctl.spec.version ? row[ctl.spec.version] : null;
    this.draw();
  }

  /** @private */
  draw() {
    const d = this.doc, c = this.ctl, spec = c.spec;
    this.host.textContent = '';
    this.host.setAttribute('data-record', this.rowId);
    const lock = c.lockAll || c.locked.get(this.rowId);
    const cols = Object.keys(spec.columns).filter((k) => !(this.opened[k] && this.opened[k].$masked === true));
    this.banner = h(d, 'p', { cls: 'dg-le-note', attrs: { role: 'status', 'aria-live': 'polite', hidden: true } });
    this.note = h(d, 'p', { cls: 'dg-le-status', attrs: { role: 'status', 'aria-live': 'polite' }, data: { panelStatus: '' } });
    this.host.append(h(d, 'h3', { cls: 'dg-le-title', text: this.rowId }), this.banner);
    if (lock || !cols.length) { this.host.append(h(d, 'p', { cls: 'dg-le-note', text: lock ? lock + ' (read only)' : 'Nothing here can be changed.', data: { why: 'readonly' } })); return; }
    const props = {}, value = {}, required = [];
    cols.forEach((k) => {
      const r = spec.columns[k], num = r.type === 'number' || r.type === 'integer' || r.type === 'money';
      const p = { title: c.o.label ? c.o.label(k) : k, type: num ? (r.type === 'integer' ? 'integer' : 'number') : 'string', hook: 'live-edit-' + k };
      if (r.options) { p.enum = r.options.map((x) => x.value); p.enumLabels = r.options.map((x) => x.label); p.widget = 'select'; p.allowEmpty = false; }
      if (r.min !== null) { p.minimum = r.min; }
      if (r.max !== null) { p.maximum = r.max; }
      if (r.pattern) { p.pattern = r.pattern; }
      props[k] = p;
      if (r.required) { required.push(k); }
      if (this.opened[k] !== undefined && this.opened[k] !== null) { value[k] = this.opened[k]; }
    });
    if (spec.reason !== 'none' || spec.decides) {
      props.reason = { type: 'string', title: 'Reason', widget: 'textarea', hook: 'live-edit-reason', description: spec.reason === 'required' ? 'Required: it goes in the audit.' : 'It goes in the audit.' };
      if (spec.reason === 'required') { required.push('reason'); }
    }
    const fh = h(d, 'div', { cls: 'dg-le-form' });
    this.host.append(fh, this.note);
    this.form = new Form(fh, { schema: { type: 'object', properties: props, required: required, order: Object.keys(props) }, value: value, submitLabel: 'Save', doneText: '', resetButton: false,
      cancelLabel: 'Cancel', onCancel: () => { c.closePanel(); }, label: 'Edit ' + this.rowId, onSubmit: (v) => this.save(v) });
  }

  /** @private Save: one change of what differs, rules checked, then sent */
  save(values) {
    const c = this.ctl, set = {};
    Object.keys(c.spec.columns).forEach((k) => {
      if (!(k in values) && this.opened[k] === undefined) { return; }
      const v = values[k] === undefined || values[k] === '' ? null : values[k], was = this.opened[k] === undefined ? null : this.opened[k];
      if (k in values && v !== was) { set[k] = v; }
    });
    if (!Object.keys(set).length) { this.say('Nothing changed'); return Promise.resolve(null); }
    const reason = String(values.reason || '').trim(), bad = checkChange(c.spec, set, reason);
    if (Object.keys(bad).length) { this.form.setErrors(bad); throw new Error(bad[Object.keys(bad)[0]]); }
    const cols = {};
    Object.keys(set).forEach((k) => { cols[k] = c.colId(k); });
    this.say('Sending…');
    return c.send({ rowId: this.rowId, set: set, cols: cols }, reason).then((out) => {
      if (out.kind === 'conflict') { this.say(''); this.banner.hidden = true; throw new Error(changedWords(out.current) + ': edit again on the new version'); }
      if (out.kind === 'invalid') { this.invalid(out); throw new Error(out.message); }
      if (['forbidden', 'unsupported', 'refused', 'failed'].indexOf(out.kind) >= 0) { throw new Error(out.message); }
      this.say(out.kind === 'held' ? 'Held' + (out.message ? ': ' + out.message : '') : out.kind === 'queued' ? 'Queued' + (out.message ? ': ' + out.message : '') : 'Applied, waiting for the table');
      return out;
    });
  }

  /** @private each message on its column */
  invalid(out) {
    if (!this.form) { return; }
    const errs = {};
    out.fields.forEach((f) => { errs[f.column || 'reason'] = f.message; });
    this.form.setErrors(errs);
  }

  /** @private */
  say(text) { if (this.note) { this.note.textContent = text; } }

  /**
   * The row's patch arrived: say when it moved since the panel opened it, with Reload.
   * @param {?Object} row
   * @returns {void}
   */
  rowChanged(row) {
    if (!row) { return; }
    this.row = row;
    const v = this.ctl.spec.version ? row[this.ctl.spec.version] : null;
    if (v === this.version) { return; }
    const d = this.doc;
    this.banner.hidden = false;
    this.banner.textContent = 'Changed since you opened it: now version ' + v + '. ';
    this.banner.append(h(d, 'button', { cls: 'dg-le-btn', text: 'Reload', attrs: { type: 'button' }, data: { act: 'reload' }, on: { click: () => { this.opened = Object.assign({}, row); this.version = v; this.draw(); } } }));
  }

  /** @returns {void} */
  destroy() { if (this.form) { this.form.destroy(); this.form = null; } this.host.textContent = ''; this.host.removeAttribute('data-record'); }
}

/**
 * The live panel's notices: the paused and stale banners from the table's schema, and "N new" when rows arrive while the page is scrolled down.
 */
export class LiveNotices {
  /**
   * @param {HTMLElement} host
   * @param {Object} o
   * @param {function(): ?Object} o.scroller the element the rows scroll in
   */
  constructor(host, o) {
    this.host = host;
    this.doc = host.ownerDocument;
    this.o = o;
    this.fresh = 0;
    const d = this.doc;
    this.banner = h(d, 'p', { cls: 'dg-le-banner', attrs: { role: 'status', hidden: true }, data: { liveBanner: '' } });
    this.news = h(d, 'button', { cls: 'dg-le-new', attrs: { type: 'button', hidden: true }, data: { liveNew: '' }, on: { click: () => this.top() } });
    host.append(this.banner, this.news);
  }

  /**
   * Shows the table's state: paused (with the reason) or stale; nothing when it is up.
   * @param {?Object} schema the table's schema document
   * @returns {void}
   */
  state(schema) {
    const st = schema && schema.live && schema.live.state, why = schema && schema.live && schema.live.reason;
    const text = st === 'paused' ? 'Live updates are paused' + (why ? ': ' + why : '') + '. The rows are as they were.' : st === 'stale' ? 'The rows may be out of date' + (why ? ': ' + why : '') + '.' : '';
    this.banner.hidden = !text;
    this.banner.textContent = text;
    if (text) { this.banner.setAttribute('data-state', st); } else { this.banner.removeAttribute('data-state'); }
  }

  /**
   * Rows arrived above the page: counted while the page is scrolled down.
   * @param {number} n
   * @param {number} [scrolled] how far the page was scrolled before the rows arrived (default: now)
   * @returns {void}
   */
  arrived(n, scrolled) {
    const s = this.o.scroller && this.o.scroller();
    if (!n || !s || (scrolled === undefined ? s.scrollTop : scrolled) <= 8) { return; }
    this.fresh += n;
    this.news.hidden = false;
    this.news.textContent = this.fresh + ' new';
    this.news.setAttribute('aria-label', this.fresh + ' new rows above: show them');
    if (!this.watching) { this.watching = true; s.addEventListener('scroll', () => { if (s.scrollTop <= 8) { this.clear(); } }); }
  }

  /** @private the page goes to the top */
  top() { const s = this.o.scroller && this.o.scroller(); if (s) { s.scrollTop = 0; } this.clear(); }

  /** @private */
  clear() { this.fresh = 0; this.news.hidden = true; this.news.textContent = ''; }

  /**
   * Takes the notices away.
   * @returns {void}
   */
  destroy() { this.banner.remove(); this.news.remove(); }
}


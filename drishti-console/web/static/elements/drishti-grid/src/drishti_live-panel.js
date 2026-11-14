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
/* The grid's live mode, the record panel (mock-up docs/lenses/mockups/trading-blotter, option B): one record beside the grid, in three tabs.
   Record: its fields, editable with a reason only when the server says this person may edit (otherwise plain text and no edit control at
   all), the four-eyes state of what was submitted, the declaration's links (Explain, VaR, limits). History: the record's changes with who,
   when, why and where each stands. Raw JSON: the full stored record of the row (GET .../rows/{entity}, not only the grid's columns),
   collapsible, with a Copy button, the version shown, a note when it changed since the panel opened it, and masked fields drawn as masked
   and never copied as a value. The panel decides nothing: the server refuses an edit made on an older version (409) and says who changed it. */
import { h, MASK } from '../../drishti-core/src/drishti_index.js';
import { Form } from '../../drishti-inputs/src/drishti_index.js';
import { ROW_KEY, ROW_VERSION, editItems, editSchema, linkFor, maskedPaths, changedFields } from './drishti_live-model.js';

const TERMINAL = ['applied', 'refused', 'conflicted', 'withdrawn', 'rejected', 'failed'];

/** The words of a change's state, as a reader would say them. */
export function stateWords(state, needed) {
  const s = String(state || '');
  if (s === 'awaiting') { return 'waiting for ' + (needed || 1) + ' approver' + ((needed || 1) === 1 ? '' : 's') + ' (four eyes)'; }
  if (s === 'queued' || s === 'applying') { return 'on its way to the source'; }
  return s || 'unknown';
}

/** Whether a change is still open (the tray lists these). */
export function isPending(change) { return TERMINAL.indexOf(String(change.state || '')) < 0; }

/** One change's items in a line: "PRICE 2.11 → 2.125". */
export function itemsText(change) {
  return (change.items || []).map((i) => String(i.path || i.field) + ' ' + show(i.from) + ' → ' + show(i.to)).join(', ');
}

function show(v) { return v === null || v === undefined || v === '' ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v); }

/**
 * The record panel.
 */
export class RecordPanel {
  /**
   * @param {HTMLElement} host
   * @param {{spec: Object, api: Object, row: Object, onSaved: function(Object): void, onClose: function(): void, copy: function(string): Promise<void>}} o
   */
  constructor(host, o) {
    this.host = host;
    this.doc = host.ownerDocument;
    this.o = o;
    this.spec = o.spec;
    this.api = o.api;
    this.row = o.row;
    this.id = o.row[ROW_KEY];
    this.opened = Object.assign({}, o.row);              // the record as the person read it: an edit is made on this version
    this.openedVersion = o.row[ROW_VERSION];
    this.tab = 'record';
    this.raw = null;
    this.form = null;
    this.draw();
  }

  /** The record's key. */
  get key() { return this.id; }

  /** Draws the panel's frame and the tab shown. */
  draw() {
    if (this.form) { this.form.destroy(); this.form = null; }
    const d = this.doc;
    this.host.textContent = '';
    this.host.setAttribute('data-record', this.id);
    const tabs = h(d, 'div', { cls: 'dg-live-tabs', attrs: { role: 'tablist' } }, [['record', 'Record'], ['history', 'History'], ['raw', 'Raw JSON']].map(([id, label]) =>
      h(d, 'button', { cls: 'dg-live-tab' + (this.tab === id ? ' on' : ''), text: label, data: { tab: id }, attrs: { type: 'button', role: 'tab', 'aria-selected': this.tab === id ? 'true' : 'false' },
        on: { click: () => { this.tab = id; this.draw(); } } })));
    this.banner = h(d, 'div', { cls: 'dg-live-changed', attrs: { role: 'status', 'aria-live': 'polite', hidden: true } });
    this.body = h(d, 'div', { cls: 'dg-live-tabbody', attrs: { role: 'tabpanel' } });
    this.host.append(
      h(d, 'header', { cls: 'dg-live-phead' }, [
        h(d, 'h3', { cls: 'dg-live-ptitle' }, [h(d, 'span', { text: this.id, cls: 'dg-live-pkey' }), ' ', h(d, 'span', { text: 'v' + this.row[ROW_VERSION], cls: 'dg-live-pver', data: { version: this.row[ROW_VERSION] } })]),
        h(d, 'button', { cls: 'dg-live-close', text: '×', attrs: { type: 'button', 'aria-label': 'Close the panel' }, on: { click: () => this.o.onClose() } })]),
      this.links(), tabs, this.banner, this.body);
    ({ record: () => this.drawRecord(), history: () => this.drawHistory(), raw: () => this.drawRaw() })[this.tab]();
    this.noteChange();
  }

  links() {
    const d = this.doc;
    return h(d, 'div', { cls: 'dg-live-links' }, (this.spec.links || []).map((l) => {
      const x = linkFor(l, this.row);
      return h(d, 'a', { cls: 'dg-live-link', text: x.label, attrs: { href: x.href }, data: { link: l.label } });
    }));
  }

  // ---- Record -----------------------------------------------------------------------------------------------------------------------------
  drawRecord() {
    const d = this.doc, spec = this.spec;
    if (!spec.editable || !(spec.fields || []).length) {
      const kv = h(d, 'dl', { cls: 'dg-live-kv', data: { readonly: 'true' } });
      (spec.columns || []).forEach((c) => { kv.append(h(d, 'dt', { text: c.label || c.field }), h(d, 'dd', { text: show(Array.isArray(this.row[c.field]) ? this.row[c.field].join(', ') : this.row[c.field]) })); });
      this.body.append(kv, h(d, 'p', { cls: 'dg-live-note', text: spec.readOnly ? 'Read-only for you: ' + spec.readOnly : 'Read-only.', data: { why: 'readonly' } }));
      return;
    }
    const host = h(d, 'div', { cls: 'dg-live-form' });
    this.status = h(d, 'p', { cls: 'dg-live-status', attrs: { role: 'status', 'aria-live': 'polite' } });
    this.body.append(host, this.status, h(d, 'p', { cls: 'dg-live-note', text: this.approvalNote() }));
    const s = editSchema(spec, this.opened);
    this.form = new Form(host, { schema: s.schema, value: s.value, submitLabel: 'Submit changes', doneText: '', resetButton: false, label: 'Edit ' + this.id,
      onSubmit: (values) => this.submit(values) });
  }

  approvalNote() {
    const a = this.spec.approval;
    return !a || a === 'none' ? 'Saved changes go to the source at once.' : 'The site asks for a second person: a change waits for an approver before it is sent.';
  }

  /** Sends the edit; the server's answer (applied, queued, waiting for approval) or its refusal (a newer version, no rights) is said in words. */
  submit(values) {
    const reason = String(values.reason || '').trim();
    const items = editItems(this.opened, values, this.spec.fields);
    if (!items.length) { this.say('Nothing changed.', 'info'); return Promise.resolve(null); }
    const body = { entity: this.id, items: items, basedOn: this.openedVersion };
    if (reason) { body.reason = reason; }
    return this.api.edit(body).then((res) => {
      const ch = (res.changes || [])[0] || {};
      this.say('Submitted: ' + stateWords(ch.state, ch.needed), 'ok');
      this.o.onSaved(res);
      return res;
    }, (e) => {
      this.say(e.message, 'bad');
      throw e;
    });
  }

  say(text, kind) { if (this.status) { this.status.textContent = text; this.status.setAttribute('data-kind', kind); } }

  // ---- History ----------------------------------------------------------------------------------------------------------------------------
  drawHistory() {
    const d = this.doc, list = h(d, 'div', { cls: 'dg-live-tl', data: { history: 'true' } });
    this.body.append(list);
    list.append(h(d, 'p', { cls: 'dg-live-note', text: 'Loading…' }));
    this.api.history(this.spec.kind, this.id).then((r) => {
      list.textContent = '';
      const rows = r.changes || [];
      if (!rows.length) { list.append(h(d, 'p', { cls: 'dg-live-note', text: 'No change made here yet. The record is at version ' + this.row[ROW_VERSION] + '.' })); return; }
      rows.forEach((c) => list.append(h(d, 'p', { cls: 'dg-live-hrow', data: { state: c.state } }, [
        h(d, 'b', { text: String(c.at || '').replace('T', ' ').slice(0, 19) }), ' ', String(c.by || ''), ' · ', itemsText(c),
        c.reason ? h(d, 'span', { cls: 'dg-live-reason', text: ' · “' + c.reason + '”' }) : null,
        h(d, 'span', { cls: 'dg-live-state', text: ' (' + stateWords(c.state, c.needed) + ')' })])));
    }, (e) => { list.textContent = ''; list.append(h(d, 'p', { cls: 'dg-live-note', text: e.message })); });
  }

  // ---- Raw JSON ---------------------------------------------------------------------------------------------------------------------------
  drawRaw() {
    const d = this.doc, tree = h(d, 'div', { cls: 'dg-live-json', data: { raw: 'true' }, attrs: { role: 'tree', 'aria-label': 'The stored record' } });
    const meta = h(d, 'span', { cls: 'dg-live-rawmeta', data: { rawVersion: '' } });
    const bar = h(d, 'div', { cls: 'dg-live-rawbar' }, [meta,
      h(d, 'button', { cls: 'dg-live-btn', text: 'Copy', data: { act: 'copy' }, attrs: { type: 'button' }, on: { click: () => this.copyRaw() } }),
      h(d, 'button', { cls: 'dg-live-btn', text: 'Expand all', attrs: { type: 'button' }, on: { click: () => this.fold(tree, true) } }),
      h(d, 'button', { cls: 'dg-live-btn', text: 'Collapse all', attrs: { type: 'button' }, on: { click: () => this.fold(tree, false) } })]);
    this.body.append(bar, tree);
    this.rawTree = tree;
    this.rawMeta = meta;
    tree.append(h(d, 'p', { cls: 'dg-live-note', text: 'Loading the stored record…' }));
    this.loadRaw();
  }

  loadRaw() {
    return this.api.row(this.id).then((r) => {
      this.raw = r;
      this.rawVersion = r.version;
      this.rawTree.textContent = '';
      this.rawTree.append(this.node(this.doc, r.doc, '', 0));
      this.rawMeta.textContent = 'version ' + r.version + ' · seq ' + r.seq + ' · ' + Object.keys(r.doc || {}).length + ' fields';
      this.rawMeta.setAttribute('data-raw-version', r.version);
      this.noteChange();
    }, (e) => { this.rawTree.textContent = ''; this.rawTree.append(h(this.doc, 'p', { cls: 'dg-live-note', text: e.message })); });
  }

  /** One JSON value as a collapsible node (objects and arrays) or a typed leaf; a masked value is drawn as masked. */
  node(d, v, name, depth) {
    const label = name === '' ? null : h(d, 'span', { cls: 'dg-live-jk', text: name + ': ' });
    if (v && typeof v === 'object') {
      const arr = Array.isArray(v), keys = arr ? v.map((x, i) => i) : Object.keys(v);
      const det = h(d, 'details', { cls: 'dg-live-jn', attrs: { open: depth < 2, role: 'treeitem' } }, [
        h(d, 'summary', {}, [label, h(d, 'span', { cls: 'dg-live-jc', text: arr ? '[' + keys.length + ']' : '{' + keys.length + '}' })])]);
      keys.forEach((k) => det.append(this.node(d, v[k], String(k), depth + 1)));
      return det;
    }
    const masked = v === MASK;
    return h(d, 'div', { cls: 'dg-live-jl', attrs: { role: 'treeitem' } }, [label, h(d, 'span', { cls: 'dg-live-jv dg-live-j' + (masked ? 'mask' : v === null ? 'null' : typeof v),
      text: v === null ? 'null' : typeof v === 'string' ? '"' + v + '"' : String(v), attrs: { title: masked ? 'Masked for you: your role may not see this field' : null }, data: masked ? { masked: 'true' } : {} })]);
  }

  fold(tree, open) { tree.querySelectorAll('details').forEach((x) => { x.open = open; }); }

  /** Copies the record as JSON; a masked field stays the mask (the value was never sent). */
  copyRaw() {
    if (!this.raw) { return Promise.resolve(); }
    const text = JSON.stringify(this.raw.doc, null, 2), masked = maskedPaths(this.raw.doc).length;
    return Promise.resolve(this.o.copy ? this.o.copy(text) : null).then(() => {
      const b = this.body.querySelector('[data-act="copy"]');
      if (b) { b.textContent = masked ? 'Copied (' + masked + ' masked)' : 'Copied'; b.setAttribute('data-copied', 'true'); }
    });
  }

  // ---- changes while it is open -----------------------------------------------------------------------------------------------------------
  /** The grid's row for the record changed (a tick or an edit applied elsewhere). */
  update(row) {
    const before = this.row;
    this.row = row;
    this.changed = changedFields(before, row).filter((k) => k !== ROW_VERSION);
    this.noteChange();
  }

  /** Says when the record moved since the panel opened it (its version is not the one the panel read), with Reload. */
  noteChange() {
    if (!this.banner) { return; }
    const moved = this.row[ROW_VERSION] !== this.openedVersion || (this.rawVersion !== undefined && this.row[ROW_VERSION] !== this.rawVersion);
    this.banner.hidden = !moved;
    this.banner.textContent = '';
    if (!moved) { return; }
    const d = this.doc;
    this.banner.append('Changed since you opened it: now version ' + this.row[ROW_VERSION] + (this.row[ROW_VERSION] === this.openedVersion ? '' : ' (you opened ' + this.openedVersion + ')') + '. ',
      h(d, 'button', { cls: 'dg-live-btn', text: 'Reload', data: { act: 'reload' }, attrs: { type: 'button' }, on: { click: () => this.reload() } }));
  }

  /** Opens the record again as it is now (an edit made on the older version would be refused). */
  reload() {
    this.opened = Object.assign({}, this.row);
    this.openedVersion = this.row[ROW_VERSION];
    this.rawVersion = undefined;
    this.draw();
  }

  destroy() { if (this.form) { this.form.destroy(); this.form = null; } this.host.textContent = ''; this.host.removeAttribute('data-record'); }
}

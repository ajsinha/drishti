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
/* The View picker in front of the pivot's compact line (BI_GUIDE.md sections P2 and 11, mode C): the ready-made layouts of the source
   (a preset's views:), then My views and Shared with me (the saved and shared views), and Save view as… (Personal or Shared). The grid
   draws it; the host's views hook lists, opens and saves (GridOptions.views): no store of the grid's own. A layout changed since its view
   was opened shows a dot, with Revert. Keys: Shift+V opens it, 1–9 pick a ready-made view, Enter opens the active one, Ctrl+S saves. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { Popover, Listbox } from '../../drishti-inputs/src/drishti_index.js';

/**
 * @typedef {Object} ViewsHook
 * @property {function(): Promise<{ready: Array<ViewEntry>, mine: Array<ViewEntry>, shared: Array<ViewEntry>}>} list
 * @property {function(ViewEntry): void} open the host lays the view out (and says so)
 * @property {function(string, ('personal'|'shared'), string): Promise<ViewEntry>} save name, scope, shared with (people or role:x)
 * @property {function(): ?{name: string, dirty: boolean}} current the view open now, and whether the layout changed since
 * @property {function(): void} [revert] back to the view as it was opened
 * @property {string} [title] the picker's words ("the layouts for this cube")
 */

/**
 * @typedef {Object} ViewEntry
 * @property {string} id
 * @property {string} name
 * @property {string} [detail] what it shows (MGMT Desk › Book · VaR, ES)
 * @property {string} [badge] personal, shared · desk head
 */

/**
 * The View picker of a grid.
 */
export class ViewPicker {
  /**
   * @param {Object} v the Grid
   */
  constructor(v) {
    this.v = v;
    this.doc = v.doc;
    this.pop = new Popover(this.doc, { cls: 'dg-views-pop', label: 'Views', theme: v.host });
    this.entries = { ready: [], mine: [], shared: [] };
    this.scope = 'personal';
  }

  /**
   * The host's views hook (GridOptions.views).
   * @returns {ViewsHook}
   */
  hook() { return this.v.o.views; }

  /**
   * The button in front of the line: VIEW and the open view's name (a dot when the layout changed since).
   * @returns {HTMLElement}
   */
  button() {
    const cur = this.hook().current ? this.hook().current() : null;
    return h(this.doc, 'button', { cls: 'dg-viewbtn' + (cur && cur.dirty ? ' dg-dirty' : ''), attrs: { type: 'button', 'aria-haspopup': 'dialog',
      'aria-label': 'View: ' + (cur ? cur.name : 'none') + (cur && cur.dirty ? ' (changed)' : '') + '. Shift+V', title: 'Views (Shift+V)' }, data: { act: 'views' } },
    [h(this.doc, 'span', { cls: 'dg-zl', text: 'View' }), h(this.doc, 'b', { text: cur ? cur.name : 'Choose…' }), cur && cur.dirty ? h(this.doc, 'span', { cls: 'dg-dot', text: '●', attrs: { 'aria-hidden': 'true' } }) : null,
      h(this.doc, 'span', { cls: 'dg-caret', text: '▾', attrs: { 'aria-hidden': 'true' } })]);
  }

  /**
   * Whether it is open.
   * @returns {boolean}
   */
  isOpen() { return this.pop.isOpen(); }

  /**
   * Opens the picker under an anchor (the VIEW button).
   * @param {Element} anchor
   * @returns {Promise<void>}
   */
  open(anchor) {
    const doc = this.doc, hk = this.hook();
    const search = this.search = h(doc, 'input', { cls: 'di-field di-search', attrs: { type: 'search', placeholder: 'Find a view', 'aria-label': 'Find a view', autocomplete: 'off' } });
    const list = this.list = new Listbox(doc, { label: 'Views', followActive: true, onPick: (it) => this.pick(it.value),
      render: (it, el) => {
        el.append(h(doc, 'span', { cls: 'dg-vp-name' }, [h(doc, 'b', { text: it.label }), it.detail ? h(doc, 'span', { cls: 'dg-vp-detail', text: it.detail }) : null]));
        if (it.badge) { el.append(h(doc, 'span', { cls: 'dg-vp-badge', text: it.badge })); }
        if (it.key) { el.append(h(doc, 'kbd', { cls: 'dg-vp-key', text: it.key })); }
      } });
    const name = this.name = h(doc, 'input', { cls: 'di-field dg-vp-save-name', attrs: { type: 'text', placeholder: 'Name of the view', 'aria-label': 'Save the view as' } });
    const who = this.who = h(doc, 'input', { cls: 'di-field dg-vp-share', attrs: { type: 'text', placeholder: 'people or role:risk', 'aria-label': 'Share with', hidden: true } });
    const seg = h(doc, 'span', { cls: 'dg-seg', attrs: { role: 'radiogroup', 'aria-label': 'Who sees it' } }, ['personal', 'shared'].map((s) =>
      h(doc, 'button', { cls: 'dg-seg-b' + (this.scope === s ? ' on' : ''), text: s === 'personal' ? 'Personal' : 'Shared', attrs: { type: 'button', role: 'radio', 'aria-checked': String(this.scope === s) }, data: { scope: s } })));
    const cur = hk.current ? hk.current() : null;
    const box = h(doc, 'div', { cls: 'dg-vp' }, [
      h(doc, 'div', { cls: 'dg-vp-head' }, [h(doc, 'b', { text: 'Views' }), h(doc, 'span', { cls: 'dg-vp-detail', text: hk.title || 'the layouts for this pivot' })]),
      search, list.el,
      cur && cur.dirty && hk.revert ? h(doc, 'button', { cls: 'di-btn dg-vp-revert', text: 'Revert to ' + cur.name, attrs: { type: 'button' }, data: { act: 'revert' } }) : null,
      h(doc, 'div', { cls: 'dg-vp-save' }, [h(doc, 'span', { text: 'Save view as…' }), name, seg, who,
        h(doc, 'button', { cls: 'di-btn dg-vp-save-btn', attrs: { type: 'button', 'aria-keyshortcuts': 'Control+S' }, data: { act: 'save' } }, ['Save ', h(doc, 'kbd', { text: 'Ctrl+S' })])]),
      h(doc, 'div', { cls: 'dg-vp-foot', text: '1–9 a ready-made view · Enter open · Esc close' })]);
    box.addEventListener('click', (e) => {
      const s = e.target.closest('[data-scope]');
      if (s) { this.scope = s.dataset.scope; box.querySelectorAll('[data-scope]').forEach((b) => { const on = b.dataset.scope === this.scope; b.classList.toggle('on', on); b.setAttribute('aria-checked', String(on)); }); who.hidden = this.scope !== 'shared'; return; }
      const a = e.target.closest('[data-act]');
      if (a && a.dataset.act === 'save') { this.save(); }
      if (a && a.dataset.act === 'revert') { this.close(); hk.revert(); }
    });
    box.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) { e.preventDefault(); this.save(); }
    });
    search.addEventListener('input', () => this.draw());
    search.addEventListener('keydown', (e) => {
      if (/^[1-9]$/.test(e.key) && !search.value) {
        const r = this.entries.ready[+e.key - 1];
        if (r) { e.preventDefault(); this.pick(r); }
        return;
      }
      list.key(e, search);
    });
    this.pop.open(anchor, box);
    search.focus();
    list.render([], () => false, { empty: 'Loading the views…' });
    return Promise.resolve(hk.list()).then((r) => { this.entries = { ready: r.ready || [], mine: r.mine || [], shared: r.shared || [] }; this.draw(); })
      .catch((err) => { list.render([], () => false, { empty: 'The views could not be listed: ' + (err && err.message ? err.message : err) }); });
  }

  /** @private draws the entries that match the search */
  draw() {
    const q = (this.search.value || '').toLowerCase();
    const hit = (e) => !q || (e.name + ' ' + (e.detail || '')).toLowerCase().indexOf(q) >= 0;
    const cur = this.hook().current ? this.hook().current() : null;
    const items = [];
    this.entries.ready.forEach((e, i) => { if (hit(e)) { items.push({ value: e, label: e.name, detail: e.detail, group: this.hook().readyTitle || 'Ready-made', key: i < 9 ? String(i + 1) : '', badge: '' }); } });
    this.entries.mine.forEach((e) => { if (hit(e)) { items.push({ value: e, label: e.name, detail: e.detail, group: 'My views', badge: e.badge || 'personal' }); } });
    this.entries.shared.forEach((e) => { if (hit(e)) { items.push({ value: e, label: e.name, detail: e.detail, group: 'Shared with me', badge: e.badge || 'shared' }); } });
    this.list.render(items, (it) => !!cur && it.value && it.value.name === cur.name, { empty: 'No view matches' });
    const at = cur ? items.findIndex((it) => it.value.name === cur.name) : -1;
    this.list.setActive(at >= 0 ? at : 0, this.search);
  }

  /** @private opens a view */
  pick(e) {
    this.close();
    this.hook().open(e);
  }

  /** @private saves the layout as a view */
  save() {
    const name = (this.name.value || '').trim();
    if (!name) { this.name.focus(); this.v.say('Name the view first'); return; }
    Promise.resolve(this.hook().save(name, this.scope, (this.who.value || '').trim())).then((saved) => {
      this.close();
      this.v.say('Saved ' + (saved && saved.name ? saved.name : name) + (this.scope === 'shared' ? ', shared' : ''));
      this.v.renderChrome();
    }).catch((err) => this.v.say('Not saved: ' + (err && err.message ? err.message : err)));
  }

  /**
   * Closes it.
   * @returns {void}
   */
  close() { this.pop.close(true); }
}

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
/* Tabs: a tablist (role tablist, tab and tabpanel, aria-selected, aria-controls) over panels the page gives. The arrows move between
   tabs (Left/Right, or Up/Down when vertical), Home and End go to the first and last, a tab opens on focus (options.manual: on Enter or
   Space); disabled tabs are skipped. A tab may carry a badge (a count), be closable (Delete or the x) and a panel may be made the first
   time its tab opens (content as a function). Only the open panel is shown; the others stay in the page, hidden, with their state. */
import { InputBase, uid } from './drishti_inputs-dom.js';

/**
 * @typedef {Object} TabDef
 * @property {string} id
 * @property {string} label
 * @property {(HTMLElement|string|function(): (HTMLElement|string))} [content] the panel: an element, text or a function called the first time it opens
 * @property {(string|number)} [badge] a small count or word beside the label
 * @property {boolean} [disabled]
 * @property {boolean} [closable]
 */

/**
 * @typedef {Object} TabsOptions
 * @property {TabDef[]} tabs
 * @property {string} [value] the open tab's id (default the first enabled one)
 * @property {('horizontal'|'vertical')} [orientation='horizontal']
 * @property {boolean} [manual=false] arrows only move the focus; Enter or Space opens
 * @property {string} [label] names the tablist
 */

/**
 * Tabs. Events: change {value, previous}, close {value} (the tab is removed unless the listener's page keeps it by calling addTab again).
 */
export class Tabs extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {TabsOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-tabs');
    const o = this.o;
    this.vertical = o.orientation === 'vertical';
    host.classList.add(this.vertical ? 'di-tabs-v' : 'di-tabs-h');
    this.uid = uid('tabs');
    this.list = this.el('div', { cls: 'di-tablist', attrs: { role: 'tablist', 'aria-orientation': this.vertical ? 'vertical' : 'horizontal', 'aria-label': o.label || null } });
    this.body = this.el('div', { cls: 'di-tabpanels' });
    host.append(this.list, this.body);
    this.tabs = [];
    (o.tabs || []).forEach((t) => this.add(t));
    const first = this.tabs.find((t) => !t.def.disabled);
    this.value = this.has(o.value) ? o.value : first ? first.def.id : null;
    this.paint();
  }

  /** @private */
  has(id) { return this.tabs.some((t) => t.def.id === id && !t.def.disabled); }

  /** @private makes a tab and its panel */
  add(def) {
    const n = this.tabs.length, tid = this.uid + '-t-' + def.id.replace(/[^\w-]/g, '_'), pid = this.uid + '-p-' + def.id.replace(/[^\w-]/g, '_');
    const btn = this.el('button', { cls: 'di-tab', data: { tab: def.id }, attrs: { type: 'button', role: 'tab', id: tid, 'aria-controls': pid, tabindex: '-1', disabled: def.disabled || null } });
    btn.appendChild(this.el('span', { cls: 'di-tab-label', text: def.label }));
    if (def.badge !== undefined && def.badge !== null && def.badge !== '') { btn.appendChild(this.el('span', { cls: 'di-tab-badge', text: String(def.badge) })); }
    if (def.closable) { btn.appendChild(this.el('span', { cls: 'di-tab-x', text: '×', attrs: { role: 'button', 'aria-label': 'Close ' + def.label, title: 'Close ' + def.label } })); }
    const panel = this.el('div', { cls: 'di-tabpanel', data: { panel: def.id }, attrs: { role: 'tabpanel', id: pid, 'aria-labelledby': tid, tabindex: '0', hidden: true } });
    btn.addEventListener('click', (e) => {
      if (e.target.closest('.di-tab-x')) { this.close(def.id); return; }
      this.setValue(def.id, true);
    });
    btn.addEventListener('keydown', (e) => this.onKey(e, def.id));
    this.list.appendChild(btn);
    this.body.appendChild(panel);
    this.tabs.push({ def: def, btn: btn, panel: panel, made: false, n: n });
  }

  /** @private the panel's content, made the first time */
  fill(t) {
    if (t.made) { return; }
    t.made = true;
    const c = typeof t.def.content === 'function' ? t.def.content() : t.def.content;
    if (typeof c === 'string') { t.panel.textContent = c; } else if (c) { t.panel.appendChild(c); }
  }

  /** @private */
  paint() {
    this.tabs.forEach((t) => {
      const on = t.def.id === this.value;
      t.btn.setAttribute('aria-selected', String(on));
      t.btn.tabIndex = on ? 0 : -1;
      t.btn.classList.toggle('di-active', on);
      t.panel.hidden = !on;
      if (on) { this.fill(t); }
    });
    if (!this.tabs.some((t) => t.def.id === this.value) && this.tabs.length) { this.tabs[0].btn.tabIndex = 0; }
  }

  /** @private */
  onKey(e, id) {
    const enabled = this.tabs.filter((t) => !t.def.disabled), at = enabled.findIndex((t) => t.def.id === id), n = enabled.length;
    const next = this.vertical ? 'ArrowDown' : 'ArrowRight', prev = this.vertical ? 'ArrowUp' : 'ArrowLeft';
    let to = -1;
    if (e.key === next) { to = (at + 1) % n; } else if (e.key === prev) { to = (at - 1 + n) % n; } else if (e.key === 'Home') { to = 0; } else if (e.key === 'End') { to = n - 1; }
    else if ((e.key === 'Enter' || e.key === ' ') && this.o.manual) { e.preventDefault(); this.setValue(id, true); return; }
    else if (e.key === 'Delete') { const t = enabled[at]; if (t && t.def.closable) { e.preventDefault(); this.close(id); } return; }
    if (to < 0) { return; }
    e.preventDefault();
    const t = enabled[to];
    t.btn.focus();
    if (!this.o.manual) { this.setValue(t.def.id, true); }
  }

  /**
   * The open tab's id.
   * @returns {(string|null)}
   */
  getValue() { return this.value; }

  /**
   * Opens a tab by id (a disabled or unknown id is ignored).
   * @param {string} id
   * @param {boolean} [tell=false] emit change
   * @returns {void}
   */
  setValue(id, tell) {
    if (!this.has(id) || id === this.value) { return; }
    const previous = this.value;
    this.value = id;
    this.paint();
    if (tell) { this.emit('change', { value: id, previous: previous }); }
  }

  /**
   * A tab's panel element.
   * @param {string} id
   * @returns {(HTMLElement|null)}
   */
  panel(id) { const t = this.tabs.find((x) => x.def.id === id); return t ? t.panel : null; }

  /**
   * Adds a tab at the end (an existing id is replaced).
   * @param {TabDef} def
   * @param {boolean} [open=false] open it
   * @returns {void}
   */
  addTab(def, open) {
    this.removeTab(def.id, true);
    this.add(def);
    if (this.value === null && !def.disabled) { this.value = def.id; }
    if (open) { this.setValue(def.id, true); } else { this.paint(); }
  }

  /**
   * Removes a tab; when it was open the next enabled one (or the one before) opens.
   * @param {string} id
   * @param {boolean} [quiet=false] no change event for the tab that opens instead
   * @returns {void}
   */
  removeTab(id, quiet) {
    const i = this.tabs.findIndex((t) => t.def.id === id);
    if (i < 0) { return; }
    const [t] = this.tabs.splice(i, 1);
    t.btn.remove(); t.panel.remove();
    if (this.value === id) {
      const near = this.tabs.slice(i).concat(this.tabs.slice(0, i).reverse()).find((x) => !x.def.disabled);
      const previous = this.value;
      this.value = near ? near.def.id : null;
      this.paint();
      if (!quiet && this.value !== null) { this.emit('change', { value: this.value, previous: previous }); }
    }
  }

  /** @private the x or Delete: tells the page, then removes */
  close(id) { this.emit('close', { value: id }); this.removeTab(id); }

  /**
   * Changes a tab's badge (a count or word; empty removes it).
   * @param {string} id
   * @param {(string|number)} badge
   * @returns {void}
   */
  setBadge(id, badge) {
    const t = this.tabs.find((x) => x.def.id === id);
    if (!t) { return; }
    t.def.badge = badge;
    const old = t.btn.querySelector('.di-tab-badge');
    if (old) { old.remove(); }
    if (badge !== undefined && badge !== null && badge !== '') { t.btn.insertBefore(this.el('span', { cls: 'di-tab-badge', text: String(badge) }), t.btn.querySelector('.di-tab-x')); }
  }
}

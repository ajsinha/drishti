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
/* Dock layout: panels in tab groups, groups in splits, saved as a workspace. The model (DockModel) is plain data with the operations
   (split, move, close, activate) and runs under Node; the view (Dock) draws it with the splitters of drishti-core's resizeHandle, tabs that
   follow the roving-tabindex pattern, and a keyboard path for every operation (the buttons of a group's header; Alt+Shift+arrows move the
   active tab to the next group or split). A panel can pop out into its own window and come back. Nothing here knows what a panel holds:
   the page gives the element for each id. */
import { h } from './drishti_dom.js';
import { rovingTabindex, announce } from './drishti_a11y.js';
import { resizeHandle } from './drishti_resize.js';
import { clamp } from './drishti_util.js';

/**
 * @typedef {Object} DockNode
 * @property {('tabs'|'split')} type
 * @property {string[]} [panels] tabs: the panel ids, in tab order
 * @property {string} [active] tabs: the shown panel
 * @property {('row'|'column')} [dir] split: row puts a left of b, column puts a above b
 * @property {number} [ratio] split: a's share, 0.1 to 0.9
 * @property {DockNode} [a]
 * @property {DockNode} [b]
 */

const share = (v) => clamp(v, 0.1, 0.9);

/** The dock's layout as data. */
export class DockModel {
  /**
   * @param {DockNode} [root] the layout (default: empty)
   */
  constructor(root) { this.root = root || { type: 'tabs', panels: [], active: null }; this.listeners = []; }

  /**
   * A model with the panels in one group.
   * @param {string[]} ids
   * @returns {DockModel}
   */
  static of(ids) { return new DockModel({ type: 'tabs', panels: ids.slice(), active: ids[0] || null }); }

  /**
   * Listens to changes.
   * @param {function(DockModel): void} fn
   * @returns {function(): void} stops listening
   */
  onChange(fn) { this.listeners.push(fn); return () => { this.listeners = this.listeners.filter((f) => f !== fn); }; }

  /** @private */
  changed() { this.listeners.forEach((f) => f(this)); }

  /**
   * Every panel id, in layout order.
   * @returns {string[]}
   */
  panels() { const out = []; (function walk(n) { if (n.type === 'tabs') { out.push(...n.panels); } else { walk(n.a); walk(n.b); } }(this.root)); return out; }

  /**
   * The tab group holding a panel.
   * @param {string} id
   * @returns {?DockNode}
   */
  groupOf(id) { let f = null; (function walk(n) { if (n.type === 'tabs') { if (n.panels.indexOf(id) >= 0) { f = n; } } else { walk(n.a); walk(n.b); } }(this.root)); return f; }

  /** @private replaces a node in the tree (the root included) */
  replace(old, node) {
    if (this.root === old) { this.root = node; return; }
    (function walk(n) { if (n.type === 'split') { if (n.a === old) { n.a = node; } else if (n.b === old) { n.b = node; } else { walk(n.a); walk(n.b); } } }(this.root));
  }

  /**
   * Adds a panel as a tab of the group of another (or of the first group).
   * @param {string} id
   * @param {string} [besideId]
   * @returns {void}
   */
  add(id, besideId) {
    if (this.panels().indexOf(id) >= 0) { throw new Error('dock: the panel ' + id + ' is in the layout already'); }
    let g = besideId ? this.groupOf(besideId) : null;
    if (!g) { g = (function first(n) { return n.type === 'tabs' ? n : first(n.a); }(this.root)); }
    g.panels.push(id);
    g.active = id;
    this.changed();
  }

  /**
   * Makes a panel the shown one of its group.
   * @param {string} id
   * @returns {void}
   */
  activate(id) { const g = this.groupOf(id); if (g && g.active !== id) { g.active = id; this.changed(); } }

  /**
   * Takes a panel out (an emptied group goes, its sibling takes its place).
   * @param {string} id
   * @returns {void}
   */
  close(id) {
    const g = this.groupOf(id);
    if (!g) { return; }
    g.panels = g.panels.filter((p) => p !== id);
    if (g.active === id) { g.active = g.panels[0] || null; }
    if (!g.panels.length && this.root !== g) {
      const parent = (function find(n) { if (n.type === 'split') { if (n.a === g || n.b === g) { return n; } return find(n.a) || find(n.b); } return null; }(this.root));
      this.replace(parent, parent.a === g ? parent.b : parent.a);
    }
    this.changed();
  }

  /**
   * Splits the group of a panel and puts the panel in the new half (after: right or below; before: left or above).
   * @param {string} id the panel to move into the new half
   * @param {('row'|'column')} dir
   * @param {string} [besideId] the panel whose group is split (default: the panel's own group)
   * @param {boolean} [before=false]
   * @returns {void}
   */
  split(id, dir, besideId, before) {
    const target = this.groupOf(besideId || id), from = this.groupOf(id);
    if (!target) { throw new Error('dock: no panel ' + (besideId || id)); }
    if (from === target && target.panels.length < 2) { return; }
    if (from) { from.panels = from.panels.filter((p) => p !== id); if (from.active === id) { from.active = from.panels[0] || null; } }
    const fresh = { type: 'tabs', panels: [id], active: id };
    const node = { type: 'split', dir: dir, ratio: 0.5, a: before ? fresh : target, b: before ? target : fresh };
    this.replace(target, node);
    if (from && !from.panels.length && from !== target) { this.prune(); }
    this.changed();
  }

  /**
   * Moves a panel into the group of another as a tab.
   * @param {string} id
   * @param {string} intoId a panel of the target group
   * @returns {void}
   */
  moveToGroupOf(id, intoId) {
    const to = this.groupOf(intoId), from = this.groupOf(id);
    if (!to || !from || to === from) { return; }
    from.panels = from.panels.filter((p) => p !== id);
    if (from.active === id) { from.active = from.panels[0] || null; }
    to.panels.push(id);
    to.active = id;
    this.prune();
    this.changed();
  }

  /** @private removes empty groups (their sibling takes the place) */
  prune() {
    const walk = (n) => {
      if (n.type === 'tabs') { return n.panels.length ? n : null; }
      const a = walk(n.a), b = walk(n.b);
      return a && b ? Object.assign(n, { a: a, b: b }) : (a || b);
    };
    this.root = walk(this.root) || { type: 'tabs', panels: [], active: null };
  }

  /**
   * Sets the share of a split (the node as found in root).
   * @param {DockNode} node
   * @param {number} ratio
   * @returns {void}
   */
  setRatio(node, ratio) { node.ratio = share(ratio); this.changed(); }

  /**
   * The workspace as data, to keep (JSON).
   * @returns {DockNode}
   */
  toJSON() { return JSON.parse(JSON.stringify(this.root)); }

  /**
   * A model from a saved workspace; panels not in `known` are dropped and `known` panels it lacks are added to the first group, so a
   * workspace saved before a panel existed still opens.
   * @param {DockNode} saved
   * @param {string[]} known
   * @returns {DockModel}
   */
  static fromJSON(saved, known) {
    const m = new DockModel(JSON.parse(JSON.stringify(saved)));
    (function walk(n) { if (n.type === 'tabs') { n.panels = n.panels.filter((p) => known.indexOf(p) >= 0); if (n.panels.indexOf(n.active) < 0) { n.active = n.panels[0] || null; } } else { walk(n.a); walk(n.b); } }(m.root));
    m.prune();
    known.filter((k) => m.panels().indexOf(k) < 0).forEach((k) => m.add(k));
    return m;
  }
}

/**
 * Keeps a workspace in the browser under a key (this person's, this browser's; a failure to store is silent).
 * @param {string} key
 * @param {Storage} [storage] default localStorage
 * @returns {{save: function(DockModel): void, load: function(string[]): ?DockModel, clear: function(): void}}
 */
export function workspaceStore(key, storage) {
  const st = () => { try { return storage || (typeof localStorage !== 'undefined' ? localStorage : null); } catch (e) { return null; } };
  return {
    save: (m) => { try { st().setItem('drishti.dock.' + key, JSON.stringify(m.toJSON())); } catch (e) { /* not kept */ } },
    load: (known) => { try { const s = st().getItem('drishti.dock.' + key); return s ? DockModel.fromJSON(JSON.parse(s), known) : null; } catch (e) { return null; } },
    clear: () => { try { st().removeItem('drishti.dock.' + key); } catch (e) { /* nothing */ } }
  };
}

/**
 * @typedef {Object} DockOptions
 * @property {DockModel} model
 * @property {function(string): HTMLElement} render the element of a panel (made once; moved between groups, never rebuilt)
 * @property {function(string): string} [title] a panel's tab text (default: its id)
 * @property {boolean} [popOut=true] the Pop out button
 */

/** The dock drawn: groups with tabs, splits with handles, a keyboard path for every move. */
export class Dock {
  /**
   * @param {HTMLElement} host
   * @param {DockOptions} o
   */
  constructor(host, o) {
    this.host = host;
    this.doc = host.ownerDocument;
    this.o = Object.assign({ title: (id) => id, popOut: true }, o);
    this.model = o.model;
    this.els = new Map();
    this.windows = new Map();
    this.root = h(this.doc, 'div', { cls: 'drishti-dock', attrs: { role: 'group', 'aria-label': 'Workspace' } });
    host.appendChild(this.root);
    this.off = this.model.onChange(() => this.draw());
    this.draw();
  }

  /** @private the element of a panel, made once */
  panelEl(id) { if (!this.els.has(id)) { this.els.set(id, this.o.render(id)); } return this.els.get(id); }

  /**
   * Draws the layout again (the model calls it on every change).
   * @returns {void}
   */
  draw() {
    while (this.root.firstChild) { this.root.removeChild(this.root.firstChild); }
    this.root.appendChild(this.node(this.model.root));
  }

  /** @private */
  node(n) {
    return n.type === 'tabs' ? this.group(n) : this.splitEl(n);
  }

  /** @private a split: two halves and the handle between them */
  splitEl(n) {
    const row = n.dir === 'row', doc = this.doc;
    const a = this.node(n.a), b = this.node(n.b), box = h(doc, 'div', { cls: 'drishti-dock-split drishti-dock-' + n.dir });
    a.classList.add('drishti-dock-a'); b.classList.add('drishti-dock-b');
    a.style.flexBasis = (n.ratio * 100) + '%'; b.style.flexBasis = ((1 - n.ratio) * 100) + '%';
    const handle = h(doc, 'div', { cls: 'drishti-dock-handle' });
    box.append(a, handle, b);
    let size = 1, start = n.ratio;
    try {
      resizeHandle(handle, { axis: row ? 'x' : 'y', label: 'Resize the panels',
        start: () => { size = (row ? box.clientWidth : box.clientHeight) || 1; start = n.ratio; return null; },
        move: (c, dx, dy) => { n.ratio = share(start + (row ? dx : dy) / size); a.style.flexBasis = (n.ratio * 100) + '%'; b.style.flexBasis = ((1 - n.ratio) * 100) + '%'; },
        end: () => this.model.changed(), reset: () => this.model.setRatio(n, 0.5),
        key: (step) => { this.model.setRatio(n, n.ratio + step * 0.02); return true; }, value: () => ({ now: Math.round(n.ratio * 100), min: 10, max: 90 }) });
    } catch (e) { /* resize not installed (a test page): the ratio is set by the model */ }
    return box;
  }

  /** @private a tab group: its tabs, its buttons, the shown panel */
  group(n) {
    const doc = this.doc, box = h(doc, 'section', { cls: 'drishti-dock-group' });
    const tabs = h(doc, 'div', { cls: 'drishti-dock-tabs', attrs: { role: 'tablist', 'aria-label': 'Panels' } });
    n.panels.forEach((id) => {
      tabs.appendChild(h(doc, 'button', { cls: 'drishti-dock-tab', text: this.o.title(id), attrs: { type: 'button', role: 'tab', 'aria-selected': String(id === n.active), id: 'dock-tab-' + id, 'aria-controls': id === n.active ? 'dock-panel-' + id : null },
        data: { panel: id }, on: { click: () => this.model.activate(id), keydown: (e) => this.tabKey(e, id) } }));
    });
    const act = (label, fn) => h(doc, 'button', { cls: 'drishti-dock-act', text: label, attrs: { type: 'button', 'aria-label': label + ': ' + this.o.title(n.active || '') }, on: { click: fn } });
    const tools = n.active ? h(doc, 'span', { cls: 'drishti-dock-tools' }, [
      act('Split right', () => { this.model.split(n.active, 'row'); announce('Split right', { document: doc }); }),
      act('Split down', () => { this.model.split(n.active, 'column'); announce('Split down', { document: doc }); }),
      this.o.popOut ? act('Pop out', () => this.popOut(n.active)) : null,
      act('Close', () => this.model.close(n.active))]) : null;
    const body = h(doc, 'div', { cls: 'drishti-dock-body', attrs: { role: 'tabpanel', id: n.active ? 'dock-panel-' + n.active : null, 'aria-labelledby': n.active ? 'dock-tab-' + n.active : null } });
    if (n.active) { body.appendChild(this.panelEl(n.active)); } else { body.appendChild(h(doc, 'p', { cls: 'drishti-dock-empty', text: 'No panels' })); }
    box.append(h(doc, 'div', { cls: 'drishti-dock-head' }, [tabs, tools]), body);
    if (tabs.children.length) { rovingTabindex(tabs, { selector: '[role=tab]', orientation: 'horizontal', onMove: (el) => this.model.activate(el.dataset.panel) }); }
    return box;
  }

  /** @private Alt+Shift+Arrow: right or down splits the tab off, left or up splits before */
  tabKey(e, id) {
    if (!(e.altKey && e.shiftKey)) { return; }
    const m = { ArrowRight: ['row', false], ArrowLeft: ['row', true], ArrowDown: ['column', false], ArrowUp: ['column', true] }[e.key];
    if (m) { e.preventDefault(); this.model.split(id, m[0], id, m[1]); }
  }

  /**
   * Moves a panel into a window of its own; closing that window brings it back as a tab.
   * @param {string} id
   * @returns {?Window}
   */
  popOut(id) {
    const win = this.doc.defaultView.open('', 'drishti-dock-' + id, 'popup,width=720,height=520');
    if (!win) { return null; }
    const el = this.panelEl(id), g = this.model.groupOf(id), title = this.o.title(id);
    win.document.title = title;
    Array.from(this.doc.styleSheets).forEach((s) => { if (s.href) { const l = win.document.createElement('link'); l.rel = 'stylesheet'; l.href = s.href; win.document.head.appendChild(l); } });
    win.document.documentElement.setAttribute('data-theme', this.doc.documentElement.getAttribute('data-theme') || '');
    win.document.body.appendChild(el);
    this.windows.set(id, win);
    const back = () => { this.windows.delete(id); if (!this.model.groupOf(id)) { this.model.add(id); } };
    win.addEventListener('pagehide', back, { once: true });
    if (g) { this.model.close(id); }
    return win;
  }

  /**
   * Stops listening and takes the dock out (pop-out windows are closed).
   * @returns {void}
   */
  destroy() { this.off(); this.windows.forEach((w) => { try { w.close(); } catch (e) { /* gone */ } }); this.root.remove(); }
}

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
/* The designer shell (EDITORS.md section 1; mock-up g7, every option of which the product owner took on 2026-11-07): one frame for
   every editor and designer, in three layouts of the same panes, each person's choice remembered through the host. "studio" (g7 A, the
   default): the left rail's tabs (the objects with a search, the palette, the outline), the canvas or the editor in the middle with the
   live YAML docked under it, the inspector and the live preview with its checks on the right (Preview shows or hides it), a status bar.
   "side-by-side" (B): the outline over the palette, the canvas and the YAML side by side, the preview always on, the inspector a card by
   the selection. "terminal" (C): dense and monospace, a DESIGN> command line (Ctrl+K), the outline as a property grid, the function
   keys F3 Validate, F4 YAML, F5 Preview, F7 History, F8 Impact, F9 Propose.
   The header says where you are, the name, the status with its versions ("Draft · approved v3"), and holds Validate, Preview,
   History, Impact, Save draft, Propose for review and + New. A Visual / YAML / Both toggle, remembered per person through a hook.
   Nothing goes live without review: Propose asks for a note and files it through the host's hook (the Approvals queue's API); a person
   may not approve their own proposal. The panes' contents are the host's (a LayoutCanvas, an Inspector, a YamlPane, a preview).
   A studio that edits several objects in turn adds the open documents above the editor (docs), a dock of panes under it (dock: problems,
   a query) and a left rail that folds away (collapsible); its header's actions may be links, toggles and choices too (drishti_shell-parts.js). */
import { EventBus, announce, h, openDialog, closeDialog } from '../../drishti-core/src/drishti_index.js';
import { actionElement, DocTabs, Dock } from './drishti_shell-parts.js';

/** The header's actions, in order, with their words. */
export const SHELL_ACTIONS = Object.freeze([
  { id: 'validate', label: 'Validate', title: 'Check the definition now (Ctrl+Enter)' },
  { id: 'preview', label: 'Preview', title: 'Read the preview again' },
  { id: 'history', label: 'History', title: 'The saved versions and reviews' },
  { id: 'impact', label: 'Impact', title: 'What uses this object' },
  { id: 'save', label: 'Save draft', title: 'Save a draft (Ctrl+S): nothing goes live until it is approved' },
  { id: 'propose', label: 'Propose for review', title: 'Ask another person to approve it (four eyes)', primary: true }
]);

/** The modes of the middle pane. */
export const SHELL_MODES = Object.freeze(['visual', 'yaml', 'both']);

/**
 * What the status pill says: a draft, proposed, approved or shipped, its versions, unsaved.
 * @param {{status: string, version: number, origin: string, dirty: boolean, text: string, versions: string}} s text (and versions): the host's
 *   own words in place of the made ones
 * @returns {{word: string, versions: string, tone: string}}
 */
export function statusWords(s) {
  if (!s) { return { word: '', versions: '', tone: '' }; }
  if (s.text) { return { word: s.text, versions: s.versions || '', tone: s.status || 'draft' }; }      // the host's own words (Saved, Read-only)
  const word = s.origin === 'file' ? 'Shipped' : s.status === 'published' || s.status === 'approved' ? 'Approved' : s.status === 'proposed' ? 'Proposed' : 'Draft';
  const versions = s.origin === 'file' ? 'from configuration' : s.origin === 'item' || s.version ? (s.version ? 'approved v' + s.version : 'never approved') + (s.status === 'draft' && s.version ? ' · a draft after it' : '') : '';
  return { word: word + (s.dirty ? ' · unsaved' : ''), versions, tone: s.status || 'draft' };
}

/**
 * @typedef {Object} ShellOptions
 * @property {string[]} [crumbs] where you are (the last one is the group)
 * @property {string} [title]
 * @property {import('./drishti_shell-parts.js').ShellAction[]} [actions] default SHELL_ACTIONS (a button each; links, toggles and
 *   choices too, with the host's data-* attributes)
 * @property {Array<{id: string, label: string}>} [newItems] + New's menu (none: no + New)
 * @property {boolean} [modes=true] the Visual / YAML / Both toggle
 * @property {string} [mode='both']
 * @property {string} [search] the objects' search placeholder (none: no objects pane)
 * @property {boolean} [preview=true] the right pane
 * @property {string} [label='Designer']
 * @property {Array<{id: string, label: string}>} [rail] the left rail's tabs besides the objects (palette, outline); shell.rail(id) is
 *   each tab's element
 * @property {boolean} [inspector=false] an inspector pane (shell.inspector)
 * @property {('studio'|'side-by-side'|'terminal')} [layout='studio'] g7's options A, B and C
 * @property {function(string): (true|string)} [canMode] whether a mode may be picked now, else why not in words
 * @property {boolean} [docs=false] the open documents above the editor (setDocs; "close" {id})
 * @property {Array<{id: string, label: string}>} [dock] the dock's tabs under the editor (dockPane(id), showDock, setDockCount; "dock" {id})
 * @property {boolean} [collapsible=false] the left rail folds away (a button in the header; setRail; "rail" {open})
 */

/** The layouts of the shell (mock-up g7: A the docked studio, B side by side, C the terminal). */
export const SHELL_LAYOUTS = Object.freeze(['studio', 'side-by-side', 'terminal']);

/** The terminal layout's function keys. */
export const SHELL_FKEYS = Object.freeze({ F3: 'validate', F4: 'yaml', F5: 'preview', F7: 'history', F8: 'impact', F9: 'propose' });

/**
 * The shell. Events: action {id} (a toggle's {id, pressed}, a choice's {id, value}), mode {mode}, new {id}, open {group, item}, search
 * {query}, tab {id}, command {text}, layout {layout, user}, close {id} (an open document), dock {id}, rail {open}.
 */
export class DesignerShell {
  /**
   * @param {HTMLElement} el
   * @param {ShellOptions} [o]
   */
  constructor(el, o) {
    this.el = el;
    this.doc = el.ownerDocument;
    this.o = Object.assign({ actions: SHELL_ACTIONS, modes: true, mode: 'both', preview: true, label: 'Designer', crumbs: [], rail: [], layout: 'studio' }, o);
    this.tabs = {};
    this.events = new EventBus();
    this.mode = this.o.mode;
    this.groups = [];
    this.build();
  }

  /**
   * Listens to one of its events; answers the function that stops listening.
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /** @private the frame */
  build() {
    const doc = this.doc, o = this.o, x = (tag, p, kids) => h(doc, tag, p, kids);
    this.el.classList.add('dd-shell');
    this.el.setAttribute('data-dd-shell', '');
    this.crumbEl = x('nav', { cls: 'dd-crumb', attrs: { 'aria-label': 'Where you are' } });
    this.titleEl = x('h1', { cls: 'dd-title', attrs: { 'data-title': '' }, text: o.title || '' });
    this.pill = x('span', { cls: 'dd-pill', attrs: { 'data-status': '', hidden: true } });
    this.versions = x('span', { cls: 'dd-versions', attrs: { 'data-versions': '' } });
    const head = x('header', { cls: 'dd-head', attrs: { 'aria-label': 'The object being edited' } }, [x('div', { cls: 'dd-head-title' }, [this.crumbEl, this.titleEl, this.pill, this.versions])]);
    if (o.modes) {
      this.modeBox = x('div', { cls: 'dd-modes', attrs: { role: 'group', 'aria-label': 'Show the visual editor, the YAML or both' } }, SHELL_MODES.map((m) =>
        x('button', { cls: 'dd-mode', text: m === 'yaml' ? 'YAML' : m[0].toUpperCase() + m.slice(1), attrs: { type: 'button', 'data-mode': m, 'aria-pressed': 'false' }, on: { click: () => this.setMode(m, true) } })));
      head.appendChild(this.modeBox);
    }
    this.buttons = {};
    const acts = x('div', { cls: 'dd-actions' });
    o.actions.forEach((a) => {
      const b = actionElement(doc, a, (ev) => this.events.emit('action', ev));
      if (a.id === 'impact') { b.appendChild(doc.createTextNode(' ')); this.impactCount = x('span', { attrs: { 'data-impact-count': '' } }); b.appendChild(this.impactCount); }
      this.buttons[a.id] = b;
      acts.appendChild(b);
    });
    if (o.newItems && o.newItems.length) {
      const nb = x('button', { cls: 'dd-btn dd-new', text: '+ New', attrs: { type: 'button', 'data-act': 'new', 'aria-haspopup': 'menu', 'aria-expanded': 'false' } });
      const menu = x('div', { cls: 'dd-newmenu', attrs: { role: 'menu', 'data-newmenu': '', hidden: true } }, o.newItems.map((n) =>
        x('button', { text: n.label, attrs: { type: 'button', role: 'menuitem', 'data-new-kind': n.id }, on: { click: () => { menu.hidden = true; nb.setAttribute('aria-expanded', 'false'); this.events.emit('new', { id: n.id }); } } })));
      nb.addEventListener('click', () => { menu.hidden = !menu.hidden; nb.setAttribute('aria-expanded', String(!menu.hidden)); if (!menu.hidden) { menu.querySelector('button').focus(); } });
      menu.addEventListener('keydown', (e) => { if (e.key === 'Escape') { menu.hidden = true; nb.setAttribute('aria-expanded', 'false'); nb.focus(); } });
      acts.append(nb, menu);
    }
    head.appendChild(acts);
    if (o.collapsible) {
      this.railToggle = x('button', { cls: 'dd-btn dd-rail-toggle', text: '◧', attrs: { type: 'button', 'data-dd-rail-toggle': '', 'aria-expanded': 'true', 'aria-label': 'Hide the left rail' },
        on: { click: () => { this.setRail(this.el.classList.contains('dd-rail-off')); this.events.emit('rail', { open: !this.el.classList.contains('dd-rail-off') }); } } });
      head.insertBefore(this.railToggle, head.firstChild);
    }
    this.msg = x('p', { cls: 'dd-msg', attrs: { 'data-msg': '', role: 'status', 'aria-live': 'polite' } });
    const grid = x('div', { cls: 'dd-shell-grid', attrs: { 'data-grid': '' } });
    const railTabs = (o.search ? [{ id: 'objects', label: 'Objects' }] : []).concat(o.rail || []);
    if (railTabs.length) {
      const list = x('div', { cls: 'dd-ptabs', attrs: { role: 'tablist', 'aria-label': 'The left rail' } });
      const rail = x('aside', { cls: 'dd-objects dd-rail', attrs: { 'aria-label': 'Objects' } }, [list]);
      railTabs.forEach((t, i) => {
        const tab = x('button', { cls: 'dd-ptab', text: t.label, attrs: { type: 'button', role: 'tab', id: 'dd-tab-' + t.id + '-' + this.uid(), 'aria-selected': String(i === 0), tabindex: i === 0 ? '0' : '-1', 'data-dd-tab': t.id } });
        const panel = x('div', { cls: 'dd-rail-panel', attrs: { role: 'tabpanel', 'aria-labelledby': tab.id, 'data-dd-panel': t.id, hidden: i !== 0 } });
        tab.addEventListener('click', () => this.showTab(t.id));
        tab.addEventListener('keydown', (e) => {
          const k = { ArrowRight: 1, ArrowLeft: -1 }[e.key];
          if (!k) { return; }
          e.preventDefault();
          const ids = railTabs.map((r) => r.id), j = (ids.indexOf(t.id) + k + ids.length) % ids.length;
          this.showTab(ids[j]);
          this.tabs[ids[j]].tab.focus();
        });
        list.appendChild(tab);
        rail.appendChild(panel);
        this.tabs[t.id] = { tab, panel };
      });
      if (railTabs.length === 1) { list.hidden = true; }
      if (o.search) {
        this.searchEl = x('input', { cls: 'dd-in dd-search', attrs: { type: 'search', 'data-ed-search': '', placeholder: o.search, 'aria-label': o.search } });
        this.searchEl.addEventListener('input', () => { this.drawObjects(); this.events.emit('search', { query: this.searchEl.value }); });
        this.objects = x('div', { attrs: { 'data-objects': '' } });
        this.tabs.objects.panel.append(this.searchEl, this.objects);
      }
      rail.appendChild(x('div', { attrs: { 'data-resize': 'panel', 'data-resize-key': 'designer.objects', 'data-resize-var': '--dd-left', 'data-resize-min': '180', 'data-resize-max': '480', 'aria-label': 'Resize the left rail' } }));
      grid.appendChild(rail);
    } else { this.el.classList.add('dd-no-objects'); }
    this.readOnlyBar = x('div', { cls: 'dd-ro', attrs: { 'data-readonly': '', hidden: true } });
    this.visual = x('div', { cls: 'dd-visual', attrs: { 'data-visual': '' } });
    this.yamlState = x('span', { cls: 'dd-ystate', attrs: { 'data-yaml-state': '' } });
    this.yamlToggle = x('button', { cls: 'dd-ytoggle', text: '▾ YAML', attrs: { type: 'button', 'data-yaml-toggle': '', 'aria-expanded': 'true' }, on: { click: () => this.toggleYaml() } });
    this.yamlBody = x('div', { cls: 'dd-ybody', attrs: { 'data-yaml-body': '' } });
    this.yaml = x('section', { cls: 'dd-yaml-panel', attrs: { 'data-yaml-panel': '', 'aria-label': 'The definition as YAML', hidden: true } }, [
      x('div', { cls: 'dd-yh' }, [this.yamlToggle, this.yamlState, x('span', { cls: 'dd-ynote', text: 'edits on either side show on the other' })]), this.yamlBody]);
    if (o.docs) { this.docs = new DocTabs(doc, (n, ev) => this.events.emit(n, ev)); this.docs.set([]); }
    if (o.dock && o.dock.length) { this.dock = new Dock(doc, o.dock, (n, ev) => this.events.emit(n, ev)); this.dock.show(null); }
    grid.appendChild(x('section', { cls: 'dd-main', attrs: { 'aria-label': 'Editor' } }, [this.docs ? this.docs.el : null, this.readOnlyBar, this.visual, this.yaml, this.dock ? this.dock.el : null]));
    if (o.inspector) {
      this.inspector = x('div', { cls: 'dd-inspector-pane', attrs: { 'data-dd-inspector': '' } });
      this.inspectorBox = x('aside', { cls: 'dd-insp-side', attrs: { 'aria-label': 'Inspector' } }, [x('h3', { cls: 'dd-sec', text: 'Inspector' }), this.inspector]);
      grid.appendChild(this.inspectorBox);
    } else { this.el.classList.add('dd-no-inspector'); }
    if (o.preview) {
      this.preview = x('div', { cls: 'dd-preview', attrs: { 'data-preview': '', 'aria-busy': 'false' } });
      grid.appendChild(x('aside', { cls: 'dd-side', attrs: { 'aria-label': 'Preview and checks', 'data-side': '' } }, [
        x('div', { attrs: { 'data-resize': 'panel', 'data-resize-key': 'designer.preview', 'data-resize-var': '--dd-right', 'data-resize-edge': 'left', 'data-resize-min': '240', 'data-resize-max': '720', 'aria-label': 'Resize the preview pane' } }), this.preview]));
    } else { this.el.classList.add('dd-no-preview'); }
    this.dlgTitle = x('h2', { attrs: { 'data-dialog-title': '', id: 'ddDlgH' + Math.random().toString(36).slice(2, 8) } });
    this.dlgBody = x('div', { attrs: { 'data-dialog-body': '' } });
    this.dlg = x('dialog', { cls: 'dd-dialog', attrs: { 'data-dialog': '', 'aria-labelledby': this.dlgTitle.id } }, [this.dlgTitle, this.dlgBody,
      x('p', { cls: 'dd-dlg-actions' }, [x('button', { cls: 'dd-btn', text: 'Close', attrs: { type: 'button', 'data-dialog-close': '' }, on: { click: () => this.closeDialog() } })])]);
    this.cmd = x('input', { cls: 'dd-cmd', attrs: { type: 'text', 'data-dd-cmd': '', placeholder: 'Ctrl+K · add bar · select v2 · align left · validate · propose', 'aria-label': 'Command', autocomplete: 'off', spellcheck: 'false' } });
    this.cmd.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && this.cmd.value.trim()) { e.preventDefault(); this.command(this.cmd.value.trim()); this.cmd.value = ''; } else if (e.key === 'Escape') { this.cmd.blur(); }
    });
    this.cmdBar = x('div', { cls: 'dd-cmdbar', attrs: { hidden: true } }, [x('span', { cls: 'dd-cmd-p', text: 'DESIGN>' }), this.cmd]);
    this.fkeys = x('div', { cls: 'dd-fkeys', attrs: { hidden: true, 'aria-hidden': 'true' } }, Object.keys(SHELL_FKEYS).map((k) => x('span', {}, [x('b', { text: k }), ' ' + SHELL_FKEYS[k]])));
    this.statusBar = x('div', { cls: 'dd-statusbar', attrs: { 'data-dd-statusbar': '', role: 'status' } });
    this.layoutSel = x('select', { cls: 'dd-layout-pick', attrs: { 'data-dd-layout-pick': '', 'aria-label': 'Layout' } }, SHELL_LAYOUTS.map((l) => x('option', { text: { studio: 'Studio', 'side-by-side': 'Side by side', terminal: 'Terminal' }[l], attrs: { value: l } })));
    this.layoutSel.addEventListener('change', () => this.setLayout(this.layoutSel.value, true));
    head.appendChild(this.layoutSel);
    this.el.append(head, this.cmdBar, this.msg, grid, this.fkeys, this.statusBar, this.dlg);
    this.doc.addEventListener('keydown', (e) => { if (this.el.contains(e.target) || e.target === this.doc.body) { this.onKey(e); } });      // the focus may have left with a hidden pane
    this.previewOn = true;
    this.setLayout(o.layout);
    this.yamlOpen = true;
    this.setCrumbs(o.crumbs);
    this.applyMode();
  }

  /** @private */
  uid() { DesignerShell.seq = (DesignerShell.seq || 0) + 1; return DesignerShell.seq; }

  /**
   * The element of a left-rail tab (objects, palette, outline).
   * @param {string} id
   * @returns {?HTMLElement}
   */
  rail(id) { return this.tabs[id] ? this.tabs[id].panel : null; }

  /**
   * Shows a left-rail tab.
   * @param {string} id
   * @returns {void}
   */
  showTab(id) {
    if (!this.tabs[id]) { return; }
    Object.keys(this.tabs).forEach((k) => { const on = k === id; this.tabs[k].tab.setAttribute('aria-selected', String(on)); this.tabs[k].tab.tabIndex = on ? 0 : -1; this.tabs[k].panel.hidden = !on && this.layout === 'studio'; });
    this.events.emit('tab', { id });
  }

  /**
   * The layout (user: the person picked it, so the "layout" event lets the host remember it).
   * @param {string} layout 'studio' (g7 A, the default), 'side-by-side' (B), 'terminal' (C)
   * @param {boolean} [user]
   * @returns {void}
   */
  setLayout(layout, user) {
    this.layout = SHELL_LAYOUTS.indexOf(layout) >= 0 ? layout : 'studio';
    SHELL_LAYOUTS.forEach((l) => this.el.classList.toggle('dd-layout-' + l, l === this.layout));
    this.el.setAttribute('data-dd-layout', this.layout);
    this.layoutSel.value = this.layout;
    const term = this.layout === 'terminal';
    this.cmdBar.hidden = !term;
    this.fkeys.hidden = !term;
    const stacked = this.layout !== 'studio';                                // B and C show every rail tab at once, one over the other
    const on = Object.keys(this.tabs).find((k) => this.tabs[k].tab.getAttribute('aria-selected') === 'true');
    Object.keys(this.tabs).forEach((k) => { this.tabs[k].panel.hidden = !stacked && k !== on; });
    if (this.layout === 'side-by-side') { this.togglePreview(true); }
    if (this.inspectorBox) { this.inspectorBox.classList.toggle('dd-card', this.layout === 'side-by-side'); }
    this.events.emit('layout', { layout: this.layout, user: !!user });
  }

  /**
   * Side by side: the inspector is a card by the selection (null: back to its place).
   * @param {?Element} anchor the selected item's element
   * @returns {void}
   */
  placeInspector(anchor) {
    const box = this.inspectorBox;
    if (!box) { return; }
    if (this.layout !== 'side-by-side' || !anchor) { box.style.removeProperty('--dd-card-l'); box.style.removeProperty('--dd-card-t'); box.toggleAttribute('data-dd-floating', false); return; }
    const r = anchor.getBoundingClientRect(), win = this.doc.defaultView, w = 300;
    const left = r.right + 8 + w > win.innerWidth ? Math.max(8, r.left - w - 8) : r.right + 8;
    box.style.setProperty('--dd-card-l', left + 'px');
    box.style.setProperty('--dd-card-t', Math.max(8, Math.min(r.top, win.innerHeight - 340)) + 'px');
    box.toggleAttribute('data-dd-floating', true);
  }

  /**
   * Shows or hides the preview pane (the Preview action's other half; side by side keeps it on).
   * @param {boolean} [on]
   * @returns {void}
   */
  togglePreview(on) {
    this.previewOn = this.layout === 'side-by-side' ? true : on === undefined ? !this.previewOn : !!on;
    this.el.classList.toggle('dd-preview-off', !this.previewOn);
  }

  /**
   * The status bar's words (what is selected, where, the problems).
   * @param {string} text
   * @returns {void}
   */
  setStatusBar(text) { this.statusBar.textContent = text || ''; }

  /**
   * A command typed on the terminal's command line: the header's actions by name ("validate", "propose", "yaml", "visual", "both",
   * "layout studio"), else the "command" event for the host (add, select, align ...).
   * @param {string} text
   * @returns {void}
   */
  command(text) {
    const t = text.trim(), w = t.toLowerCase().split(/\s+/);
    if (this.buttons[w[0]] && w.length === 1) { if (!this.buttons[w[0]].disabled) { this.buttons[w[0]].click(); } else { this.say(this.buttons[w[0]].title, true); } return; }
    if (SHELL_MODES.indexOf(w[0]) >= 0 && w.length === 1) { this.setMode(w[0], true); return; }
    if (w[0] === 'layout' && SHELL_LAYOUTS.indexOf(w[1]) >= 0) { this.setLayout(w[1], true); return; }
    this.events.emit('command', { text: t });
  }

  /** @private the terminal's keys: Ctrl+K the command line, the function keys */
  onKey(e) {
    if (this.layout !== 'terminal' || e.altKey) { return; }
    if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) { e.preventDefault(); this.cmd.focus(); return; }
    const act = !e.ctrlKey && !e.metaKey ? SHELL_FKEYS[e.key] : null;
    if (!act) { return; }
    e.preventDefault();
    if (act === 'yaml') { this.setMode(this.mode === 'yaml' ? 'both' : 'yaml', true); return; }
    const b = this.buttons[act];
    if (b && !b.disabled) { b.click(); }
  }

  /**
   * Where you are: ['Build', 'Tables & models', 'Tables'] (the last is the group, kept in data-crumb-group).
   * @param {Array<(string|{label: string, href: string})>} crumbs
   * @returns {void}
   */
  setCrumbs(crumbs) {
    const c = this.crumbEl;
    c.textContent = '';
    (crumbs || []).forEach((x, i) => {
      if (i) { c.appendChild(this.doc.createTextNode(' › ')); }
      const last = i === crumbs.length - 1, label = typeof x === 'string' ? x : x.label;
      c.appendChild(typeof x !== 'string' && x.href ? h(this.doc, 'a', { text: label, attrs: { href: x.href } }) : h(this.doc, 'span', { text: label, attrs: last ? { 'data-crumb-group': '' } : {} }));
    });
  }

  /**
   * The name in the header.
   * @param {string} t
   * @returns {void}
   */
  setTitle(t) { this.titleEl.textContent = t || ''; }

  /**
   * The status pill and the versions (null hides them).
   * @param {?{status: string, version: number, origin: string, dirty: boolean}} s
   * @returns {void}
   */
  setStatus(s) {
    const w = statusWords(s);
    this.pill.hidden = !s;
    this.pill.className = 'dd-pill dd-' + w.tone;
    this.pill.textContent = w.word;
    this.versions.textContent = w.versions;
  }

  /**
   * Turns actions on or off; an action that is off says why on hover.
   * @param {Object<string, (boolean|string)>} states id -> true (on) or why it is off
   * @returns {void}
   */
  setActions(states) {
    Object.keys(states).forEach((id) => {
      const b = this.buttons[id], v = states[id];
      if (!b || b.tagName === 'A') { return; }                                 // a link is never off
      b.disabled = v !== true;
      b.title = v === true ? b.getAttribute('data-title') : String(v || '');
    });
  }

  /**
   * An action's words (Publish or Propose, as the place requires).
   * @param {string} id
   * @param {string} label
   * @returns {void}
   */
  setLabel(id, label) { const b = this.buttons[id]; if (b && b.tagName !== 'SELECT') { b.textContent = label; } }

  /**
   * Shows or hides an action (one that does not apply here).
   * @param {string} id
   * @param {boolean} on
   * @returns {void}
   */
  showAction(id, on) { if (this.buttons[id]) { this.buttons[id].hidden = !on; } }

  /**
   * The open documents above the editor (the docs option).
   * @param {Array<{id: string, label: string, href: string, current: boolean, title: string}>} list
   * @returns {void}
   */
  setDocs(list) { if (this.docs) { this.docs.set(list); } }

  /**
   * A dock pane's element, for the host to fill (the dock option).
   * @param {string} id
   * @returns {?HTMLElement}
   */
  dockPane(id) { return this.dock && this.dock.tabs[id] ? this.dock.tabs[id].pane : null; }

  /**
   * Opens a dock pane (null folds the dock).
   * @param {?string} id
   * @returns {void}
   */
  showDock(id) { if (this.dock) { this.dock.show(id); } }

  /**
   * The count on a dock tab (bad: an error among them).
   * @param {string} id
   * @param {?number} n
   * @param {boolean} [bad]
   * @returns {void}
   */
  setDockCount(id, n, bad) { if (this.dock) { this.dock.count(id, n, bad); } }

  /**
   * Shows or folds the left rail (the collapsible option).
   * @param {boolean} open
   * @returns {void}
   */
  setRail(open) {
    this.el.classList.toggle('dd-rail-off', !open);
    if (this.railToggle) { this.railToggle.setAttribute('aria-expanded', String(!!open)); this.railToggle.setAttribute('aria-label', open ? 'Hide the left rail' : 'Show the left rail'); }
  }

  /**
   * The Visual / YAML / Both mode (user: the person picked it, so the "mode" event lets the host remember it).
   * @param {string} mode
   * @param {boolean} [user]
   * @returns {void}
   */
  setMode(mode, user) {
    if (SHELL_MODES.indexOf(mode) < 0) { return; }
    if (user && this.o.canMode && this.o.canMode(mode) !== true) { this.say(this.o.canMode(mode), true); return; }
    this.mode = mode;
    this.applyMode();
    if (user) { this.events.emit('mode', { mode }); }
  }

  /**
   * Shows or hides the YAML under the editor (Both mode).
   * @param {boolean} [open]
   * @returns {void}
   */
  toggleYaml(open) { this.yamlOpen = open === undefined ? !this.yamlOpen : !!open; this.applyMode(); this.events.emit('yaml', { open: this.yamlOpen }); }

  /**
   * Whether there is an object open (the YAML pane shows only then).
   * @param {boolean} on
   * @returns {void}
   */
  setOpen(on) { this.opened = !!on; this.applyMode(); }

  /** @private */
  applyMode() {
    const m = this.mode;
    if (this.modeBox) { this.modeBox.querySelectorAll('[data-mode]').forEach((b) => b.setAttribute('aria-pressed', String(b.getAttribute('data-mode') === m))); }
    this.visual.hidden = m === 'yaml' && !!this.opened;
    this.yaml.hidden = !this.opened || m === 'visual';
    this.yaml.classList.toggle('dd-yfull', m === 'yaml');
    const open = m === 'yaml' || this.yamlOpen;
    this.yamlBody.hidden = !open;
    this.yamlToggle.setAttribute('aria-expanded', String(open));
    this.yamlToggle.textContent = (open ? '▾' : '▸') + ' YAML';
    this.el.setAttribute('data-dd-mode', m);
  }

  /**
   * The YAML pane's state line: live and valid, with problems, not valid yet, read-only.
   * @param {string} text
   * @param {('good'|'warn'|'bad'|'')} tone
   * @returns {void}
   */
  setYamlState(text, tone) { this.yamlState.textContent = text || ''; this.yamlState.className = 'dd-ystate' + (tone ? ' dd-' + tone : ''); }

  /**
   * The objects on the left, in groups.
   * @param {Array<{id: string, label: string, items: Array<{id: string, label: string, detail: string, current: boolean, search: string, data: Object,
   *   href: string, badge: string, title: string}>}>} groups an item with an href is a link to the object's own address (a studio of
   *   pages), else a button that emits "open"; a badge is a word after its name (draft)
   * @returns {void}
   */
  setObjects(groups) { this.groups = groups || []; this.drawObjects(); }

  /** @private */
  drawObjects() {
    if (!this.objects) { return; }
    const term = (this.searchEl.value || '').toLowerCase(), doc = this.doc;
    this.objects.textContent = '';
    this.groups.forEach((g) => {
      const list = g.items.filter((x) => !term || (x.label + ' ' + (x.search || x.detail || '')).toLowerCase().indexOf(term) >= 0);
      const ul = h(doc, 'ul', { cls: 'dd-list', attrs: { role: 'list' } }, list.map((x) => h(doc, 'li', {}, [h(doc, x.href ? 'a' : 'button', { cls: 'dd-obj' + (x.current ? ' dd-on' : ''),
        attrs: { type: x.href ? null : 'button', href: x.href || null, title: x.title || null, 'aria-current': x.current ? (x.href ? 'page' : 'true') : null, 'data-object': x.id, 'data-object-item': (x.data && x.data.item) || '' },
        on: x.href ? {} : { click: () => this.events.emit('open', { group: g.id, item: x }) } }, [h(doc, 'span', { cls: 'dd-obj-n', text: x.label },
        x.badge ? [h(doc, 'span', { cls: 'dd-obj-badge', text: x.badge })] : []), h(doc, 'small', { text: x.detail || '' })])])));
      this.objects.appendChild(h(doc, 'section', { cls: 'dd-group', attrs: { 'data-group': g.id } }, [h(doc, 'h2', {}, [g.label + ' ', h(doc, 'small', { text: String(list.length) })]),
        list.length ? ul : h(doc, 'p', { cls: 'dd-muted dd-small', text: term ? 'none match' : 'none yet' })]));
    });
  }

  /**
   * A message under the header (bad: a problem).
   * @param {string} text
   * @param {boolean} [bad]
   * @returns {void}
   */
  say(text, bad) { this.msg.textContent = text || ''; this.msg.classList.toggle('dd-bad', !!bad); if (text) { announce(text); } }

  /**
   * The read-only bar (null hides it): words and an optional button.
   * @param {?{text: string, button: {label: string, run: function(): void}}} ro
   * @returns {void}
   */
  setReadOnly(ro) {
    this.readOnlyBar.textContent = '';
    this.readOnlyBar.hidden = !ro;
    if (!ro) { return; }
    this.readOnlyBar.appendChild(h(this.doc, 'span', { text: ro.text + ' ' }));
    if (ro.button) { this.readOnlyBar.appendChild(h(this.doc, 'button', { cls: 'dd-btn', text: ro.button.label, attrs: { type: 'button', 'data-copy': '' }, on: { click: ro.button.run } })); }
  }

  /**
   * The Impact button's count.
   * @param {?number} n
   * @returns {void}
   */
  setImpactCount(n) { if (this.impactCount) { this.impactCount.textContent = n === null || n === undefined ? '' : '(' + n + ')'; } }

  /**
   * A modal dialog with a title and a body (its first field focused; focus goes back on close).
   * @param {string} title
   * @param {Node} body
   * @returns {HTMLDialogElement}
   */
  dialog(title, body) {
    this.dlgTitle.textContent = title;
    this.dlgBody.textContent = '';
    this.dlgBody.appendChild(body);
    return openDialog(this.dlg, { within: this.dlgBody });
  }

  /**
   * Closes the dialog (the focus goes back where it was).
   * @returns {void}
   */
  closeDialog() { closeDialog(this.dlg); }

  /**
   * Propose for review (four eyes): a note for the reviewer, then submit(note) files it (the Approvals queue's API); its answer's words
   * are shown. The dialog closes when it was filed.
   * @param {string} name what is proposed
   * @param {function(string): Promise<*>} submit
   * @returns {void}
   */
  propose(name, submit) {
    const doc = this.doc;
    const note = h(doc, 'textarea', { cls: 'dd-in', attrs: { rows: '3', 'data-propose-note': '', 'aria-label': 'A note for the reviewer', placeholder: 'What to look at' } });
    const go = h(doc, 'button', { cls: 'dd-btn dd-primary', text: 'Propose for review', attrs: { type: 'button', 'data-propose-go': '' } });
    go.addEventListener('click', () => {
      go.disabled = true;
      Promise.resolve(submit(note.value)).then(() => { this.closeDialog(); }, (e) => { this.say(e && e.message ? e.message : String(e), true); }).then(() => { go.disabled = false; });
    });
    this.dialog('Propose ' + name + ' for review', h(doc, 'div', { cls: 'dd-form' }, [h(doc, 'p', { cls: 'dd-small', text: 'A second person approves it before it goes live; you may not approve your own proposal.' }), note, h(doc, 'p', {}, [go])]));
  }
}

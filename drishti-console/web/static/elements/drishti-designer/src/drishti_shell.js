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
/* The designer shell (EDITORS.md section 1, the product owner's choice): one frame for every editor and designer. Three panes: the
   objects with a search on the left; the editor in the middle with the live YAML under it; the preview with its checks on the right.
   The header says where you are, the name, the status with its versions ("Draft · approved v3"), and holds Validate, Preview,
   History, Impact, Save draft, Propose for review and + New. A Visual / YAML / Both toggle, remembered per person through a hook.
   Nothing goes live without review: Propose asks for a note and files it through the host's hook (the Approvals queue's API); a person
   may not approve their own proposal. The panes' contents are the host's (a LayoutCanvas, an Inspector, a YamlPane, a preview). */
import { EventBus, announce, h, openDialog, closeDialog } from '../../drishti-core/src/drishti_index.js';

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
 * @param {{status: string, version: number, origin: string, dirty: boolean}} s
 * @returns {{word: string, versions: string, tone: string}}
 */
export function statusWords(s) {
  if (!s) { return { word: '', versions: '', tone: '' }; }
  const word = s.origin === 'file' ? 'Shipped' : s.status === 'published' || s.status === 'approved' ? 'Approved' : s.status === 'proposed' ? 'Proposed' : 'Draft';
  const versions = s.origin === 'file' ? 'from configuration' : s.origin === 'item' || s.version ? (s.version ? 'approved v' + s.version : 'never approved') + (s.status === 'draft' && s.version ? ' · a draft after it' : '') : '';
  return { word: word + (s.dirty ? ' · unsaved' : ''), versions, tone: s.status || 'draft' };
}

/**
 * @typedef {Object} ShellOptions
 * @property {string[]} [crumbs] where you are (the last one is the group)
 * @property {string} [title]
 * @property {Array<{id: string, label: string, title: string, primary: boolean}>} [actions] default SHELL_ACTIONS
 * @property {Array<{id: string, label: string}>} [newItems] + New's menu (none: no + New)
 * @property {boolean} [modes=true] the Visual / YAML / Both toggle
 * @property {string} [mode='both']
 * @property {string} [search] the objects' search placeholder (none: no objects pane)
 * @property {boolean} [preview=true] the right pane
 * @property {string} [label='Designer']
 */

/**
 * The shell. Events: action {id}, mode {mode}, new {id}, open {group, item}, search {query}.
 */
export class DesignerShell {
  /**
   * @param {HTMLElement} el
   * @param {ShellOptions} [o]
   */
  constructor(el, o) {
    this.el = el;
    this.doc = el.ownerDocument;
    this.o = Object.assign({ actions: SHELL_ACTIONS, modes: true, mode: 'both', preview: true, label: 'Designer', crumbs: [] }, o);
    this.events = new EventBus();
    this.mode = this.o.mode;
    this.groups = [];
    this.build();
  }

  /**
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
      const b = x('button', { cls: 'dd-btn' + (a.primary ? ' dd-primary' : ''), text: a.label, attrs: { type: 'button', 'data-act': a.id, title: a.title || '', 'data-title': a.title || '', disabled: true },
        on: { click: () => this.events.emit('action', { id: a.id }) } });
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
    this.msg = x('p', { cls: 'dd-msg', attrs: { 'data-msg': '', role: 'status', 'aria-live': 'polite' } });
    const grid = x('div', { cls: 'dd-shell-grid', attrs: { 'data-grid': '' } });
    if (o.search) {
      this.searchEl = x('input', { cls: 'dd-in dd-search', attrs: { type: 'search', 'data-ed-search': '', placeholder: o.search, 'aria-label': o.search } });
      this.searchEl.addEventListener('input', () => { this.drawObjects(); this.events.emit('search', { query: this.searchEl.value }); });
      this.objects = x('div', { attrs: { 'data-objects': '' } });
      grid.appendChild(x('aside', { cls: 'dd-objects', attrs: { 'aria-label': 'Objects' } }, [this.searchEl, this.objects,
        x('div', { attrs: { 'data-resize': 'panel', 'data-resize-key': 'designer.objects', 'data-resize-var': '--dd-left', 'data-resize-min': '180', 'data-resize-max': '480', 'aria-label': 'Resize the objects pane' } })]));
    } else { this.el.classList.add('dd-no-objects'); }
    this.readOnlyBar = x('div', { cls: 'dd-ro', attrs: { 'data-readonly': '', hidden: true } });
    this.visual = x('div', { cls: 'dd-visual', attrs: { 'data-visual': '' } });
    this.yamlState = x('span', { cls: 'dd-ystate', attrs: { 'data-yaml-state': '' } });
    this.yamlToggle = x('button', { cls: 'dd-ytoggle', text: '▾ YAML', attrs: { type: 'button', 'data-yaml-toggle': '', 'aria-expanded': 'true' }, on: { click: () => this.toggleYaml() } });
    this.yamlBody = x('div', { cls: 'dd-ybody', attrs: { 'data-yaml-body': '' } });
    this.yaml = x('section', { cls: 'dd-yaml-panel', attrs: { 'data-yaml-panel': '', 'aria-label': 'The definition as YAML', hidden: true } }, [
      x('div', { cls: 'dd-yh' }, [this.yamlToggle, this.yamlState, x('span', { cls: 'dd-ynote', text: 'edits on either side show on the other' })]), this.yamlBody]);
    grid.appendChild(x('section', { cls: 'dd-main', attrs: { 'aria-label': 'Editor' } }, [this.readOnlyBar, this.visual, this.yaml]));
    if (o.preview) {
      this.preview = x('div', { cls: 'dd-preview', attrs: { 'data-preview': '', 'aria-busy': 'false' } });
      grid.appendChild(x('aside', { cls: 'dd-side', attrs: { 'aria-label': 'Preview and checks', 'data-side': '' } }, [
        x('div', { attrs: { 'data-resize': 'panel', 'data-resize-key': 'designer.preview', 'data-resize-var': '--dd-right', 'data-resize-edge': 'left', 'data-resize-min': '240', 'data-resize-max': '720', 'aria-label': 'Resize the preview pane' } }), this.preview]));
    } else { this.el.classList.add('dd-no-preview'); }
    this.dlgTitle = x('h2', { attrs: { 'data-dialog-title': '', id: 'ddDlgH' + Math.random().toString(36).slice(2, 8) } });
    this.dlgBody = x('div', { attrs: { 'data-dialog-body': '' } });
    this.dlg = x('dialog', { cls: 'dd-dialog', attrs: { 'data-dialog': '', 'aria-labelledby': this.dlgTitle.id } }, [this.dlgTitle, this.dlgBody,
      x('p', { cls: 'dd-dlg-actions' }, [x('button', { cls: 'dd-btn', text: 'Close', attrs: { type: 'button', 'data-dialog-close': '' }, on: { click: () => this.closeDialog() } })])]);
    this.el.append(head, this.msg, grid, this.dlg);
    this.yamlOpen = true;
    this.setCrumbs(o.crumbs);
    this.applyMode();
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
      if (!b) { return; }
      b.disabled = v !== true;
      b.title = v === true ? b.getAttribute('data-title') : String(v || '');
    });
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
   * @param {Array<{id: string, label: string, items: Array<{id: string, label: string, detail: string, current: boolean, search: string, data: Object}>}>} groups
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
      const ul = h(doc, 'ul', { cls: 'dd-list', attrs: { role: 'list' } }, list.map((x) => h(doc, 'li', {}, [h(doc, 'button', { cls: 'dd-obj' + (x.current ? ' dd-on' : ''),
        attrs: { type: 'button', 'aria-current': x.current ? 'true' : null, 'data-object': x.id, 'data-object-item': (x.data && x.data.item) || '' },
        on: { click: () => this.events.emit('open', { group: g.id, item: x }) } }, [h(doc, 'span', { cls: 'dd-obj-n', text: x.label }), h(doc, 'small', { text: x.detail || '' })])])));
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

  /** @returns {void} */
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

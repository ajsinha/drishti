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
/* The designer harness (drishti_harness.html): a small report designer built only from the kit, with a stand-in approvals queue, and
   a flow canvas. The figures and names are invented. window.__harness is what the browser test drives. */
(function () {
  'use strict';
  const E = window.Drishti.elements, core = E.core, K = E.designer;
  const q = new URLSearchParams(location.search);
  const H = window.__harness = { events: [], proposals: [], modes: [] };
  const log = document.getElementById('hx-log');
  const note = (name, detail) => { H.events.push({ name, detail }); log.textContent = H.events.length + ' events · last ' + name; };
  if (q.get('theme') === 'light') { document.documentElement.setAttribute('data-theme', 'light'); }
  document.getElementById('hx-theme').addEventListener('click', () => { const r = document.documentElement; r.setAttribute('data-theme', r.getAttribute('data-theme') === 'light' ? 'terminal' : 'light'); });

  /* ---- the report and its catalogue ------------------------------------------------------------------------------------------------ */
  const TYPES = [
    { id: 'bar', label: 'Bar chart', category: 'Charts', size: [6, 4], keywords: ['column'] }, { id: 'line', label: 'Line chart', category: 'Charts', size: [6, 4] },
    { id: 'card', label: 'Card', category: 'Numbers', size: [3, 2], keywords: ['kpi'] }, { id: 'table', label: 'Table', category: 'Tables', size: [6, 5] },
    { id: 'text', label: 'Text', category: 'Other', size: [4, 2] }, { id: 'image', label: 'Image', category: 'Other', size: [3, 3], disabled: true, why: 'Images need a file store, which this harness has not.' }];
  const SCHEMAS = {
    base: { type: 'object', title: 'Visual', properties: { title: { type: 'string', title: 'Title', maxLength: 40 }, type: { type: 'string', title: 'Type', enum: ['bar', 'line', 'card', 'table', 'text'] } }, required: ['title'] },
    card: { type: 'object', title: 'Card', properties: { title: { type: 'string', title: 'Title', maxLength: 40 }, type: { type: 'string', title: 'Type', enum: ['bar', 'line', 'card', 'table', 'text'] },
      format: { type: 'string', title: 'Number format', enum: ['amount0', 'compact', 'pct2'] }, target: { type: 'number', title: 'Target', when: { field: 'format', equals: 'pct2' } } }, required: ['title'] }
  };
  const reports = {
    sales: { title: 'Desk summary', visuals: [{ id: 'v1', type: 'card', title: 'Notional', at: { x: 0, y: 0, w: 3, h: 2 } }, { id: 'v2', type: 'bar', title: 'By desk', at: { x: 3, y: 0, w: 6, h: 4 } },
      { id: 'v3', type: 'table', title: 'Trades', at: { x: 0, y: 4, w: 12, h: 5 } }] },
    blank: { title: 'Empty report', visuals: [] }
  };
  let current = 'sales', status = { status: 'draft', version: 2, origin: 'item', dirty: false };

  /* ---- the shell ------------------------------------------------------------------------------------------------------------------------ */
  const shell = H.shell = new K.DesignerShell(document.getElementById('hx-shell'), { crumbs: ['Harness', { label: 'Reports', href: '#' }, 'Reports'], title: reports.sales.title,
    search: 'Search reports', newItems: [{ id: 'report', label: 'Report' }], rail: [{ id: 'palette', label: 'Palette' }, { id: 'outline', label: 'Outline' }], inspector: true,
    layout: q.get('layout') || 'studio', canMode: (m) => (m === 'visual' && H.yamlBroken ? 'The visual view waits until the YAML reads: fix the line marked below.' : true) });
  shell.setObjects([{ id: 'reports', label: 'Reports', items: Object.keys(reports).map((k) => ({ id: k, label: reports[k].title, detail: reports[k].visuals.length + ' visuals', current: k === current })) }]);
  shell.setOpen(true);
  shell.on('mode', (m) => { H.modes.push(m.mode); note('mode', m); });
  shell.on('new', (n) => { note('new', n); shell.say('New ' + n.id + ': the guided steps would open here.'); });
  shell.on('open', (o) => { current = o.item.id; open(); });
  shell.on('layout', (l) => { if (l.user) { H.layouts = (H.layouts || []).concat([l.layout]); note('layout', l); } });
  shell.on('command', (c) => {
    note('command', c);
    const w = c.text.split(/\s+/);
    if (w[0] === 'add' && TYPES.some((t) => t.id === w[1])) { addType(w[1], null); }
    else if (w[0] === 'select' && model.get(w[1])) { sel.set(w[1], 'command'); canvas.focus(); }
    else if (w[0] === 'align' && w[1]) { model.align(sel.list(), w[1]); }
    else { shell.say('No command "' + c.text + '": add <type>, select <id>, align <edge>, validate, propose, layout <name>.', true); }
  });

  const tools = core.h(document, 'div', { cls: 'hx-tools', attrs: { role: 'toolbar', 'aria-label': 'Arrange' } });
  const canvasEl = core.h(document, 'div', { cls: 'hx-canvas', attrs: { id: 'hx-canvas', 'aria-label': 'Report page' } });
  shell.visual.append(tools, canvasEl);
  const palEl = core.h(document, 'div', { attrs: { id: 'hx-palette' } });
  shell.rail('palette').appendChild(palEl);
  const outEl = core.h(document, 'div', { attrs: { id: 'hx-outline' } });
  shell.rail('outline').appendChild(outEl);
  const inspEl = core.h(document, 'div', { attrs: { id: 'hx-inspector' } });
  shell.inspector.appendChild(inspEl);

  /* ---- the kit ---------------------------------------------------------------------------------------------------------------------- */
  const hub = H.hub = new K.DragHub();
  const sel = H.selection = new K.Selection();
  const model = H.model = new K.LayoutModel({ cols: 12, maxH: 20 });
  const label = (it) => (it.data && it.data.title) || it.id;
  const canvas = H.canvas = new K.LayoutCanvas(canvasEl, { model, selection: sel, hub, rowPx: 34, gap: 8, handle: '.dd-item-head', label,
    createItem: (it) => core.h(document, 'div', { cls: 'dd-item-frame' }, [core.h(document, 'div', { cls: 'dd-item-head' }), core.h(document, 'div', { cls: 'dd-item-body hx-vis' })]),
    updateItem: (it, el) => { el.querySelector('.dd-item-head').textContent = label(it); el.querySelector('.dd-item-body').textContent = it.data.type + ' · ' + it.w + '×' + it.h; } });
  const palette = H.palette = new K.Palette(palEl, { entries: TYPES, hub, label: 'Visual types' });
  const outline = H.outline = new K.Outline(outEl, { selection: sel, label: 'The report' });
  const inspector = H.inspector = new K.Inspector(inspEl, {
    schemaFor: (ids) => K.sharedSchema(ids.map((id) => (model.get(id) ? SCHEMAS[model.get(id).data.type] || SCHEMAS.base : null)).filter(Boolean)),
    valuesFor: (ids) => { const c = K.commonValues(ids.map((id) => (model.get(id) || {}).data || {})); return Object.assign(c.values, { __mixed: c.mixed }); } });
  inspector.follow(sel);
  const yaml = H.yaml = new K.YamlPane(shell.yamlBody, { selection: sel, height: 260, label: 'The report as YAML',
    pathOf: (id) => { const i = model.ids().indexOf(id); return i < 0 ? null : 'visuals[' + i + ']'; },
    idAt: (path) => { const m = /^visuals\[(\d+)\]$/.exec(path); return m ? model.ids()[+m[1]] || null : null; },
    lint: (text) => { const r = K.readYaml(text); const v = r.value || {}; return r.problems.map((p) => ({ line: p.line, message: p.message })).concat(Array.isArray(v.visuals) ? [] : [{ line: 1, severity: 'warning', message: 'a report has visuals' }]); } });

  /* ---- the document: the layout model and the report's own fields --------------------------------------------------------------- */
  let title = '';
  const doc = () => ({ title, visuals: model.list().map((i) => Object.assign({ id: i.id }, i.data, { at: { x: i.x, y: i.y, w: i.w, h: i.h } })) });
  const items = (r) => r.visuals.map((v) => ({ id: v.id, x: v.at.x, y: v.at.y, w: v.at.w, h: v.at.h, label: v.title, data: { type: v.type, title: v.title, format: v.format, target: v.target } }));
  H.doc = doc;
  function sync() {
    outline.setNodes([{ id: 'page', label: title || 'Page 1', fixed: true, children: model.list().map((i) => ({ id: i.id, label: label(i), detail: i.data.type })) }]);
    yaml.setModel(doc());
    inspector.refresh();
    status.dirty = model.stack.dirty();
    shell.setStatus(status);
    shell.setActions({ validate: true, preview: true, history: true, impact: true, save: status.dirty ? true : 'Nothing to save', propose: status.dirty ? 'Save your changes first' : status.status === 'draft' ? true : 'Nothing to propose: it is ' + status.status });
    tools.querySelectorAll('[data-hx-undo]').forEach((b) => { b.disabled = !model.stack.canUndo(); });
    shell.preview.textContent = '';
    shell.preview.append(core.h(document, 'h3', { text: 'Checks' }), core.h(document, 'ul', { attrs: { 'data-checks': '' } }, [core.h(document, 'li', { text: '✓ ' + model.ids().length + ' visuals, none overlapping' })]));
  }
  model.on('change', sync);
  function open() {
    const r = reports[current];
    title = r.title;
    shell.setTitle(r.title);
    model.load(items(r));
    model.stack.clear();
    sel.clear();
    shell.setObjects([{ id: 'reports', label: 'Reports', items: Object.keys(reports).map((k) => ({ id: k, label: reports[k].title, detail: reports[k].visuals.length + ' visuals', current: k === current })) }]);
    sync();
  }
  yaml.on('model', (m) => {
    H.yamlBroken = false;
    const v = m.model || {};
    if (!Array.isArray(v.visuals)) { return; }
    title = v.title || '';
    shell.setTitle(title);
    model.commit('Edit the YAML', (list) => { list.splice(0, list.length, ...items({ visuals: v.visuals.filter((x) => x && x.id && x.at) })); return []; });
    note('yaml', { visuals: v.visuals.length });
  });
  yaml.on('problem', () => { H.yamlBroken = true; });
  inspector.on('change', (c) => {
    model.commit('Set ' + c.path, (list) => { list.filter((i) => c.ids.indexOf(i.id) >= 0).forEach((i) => { i.data[c.path] = c.value; if (c.path === 'title') { i.label = c.value; } }); return c.ids; }, { mergeKey: 'inspect:' + c.path + c.ids.join() });
    note('inspect', c);
  });
  let n = 10;
  function addType(typeId, cell) {
    const t = TYPES.find((x) => x.id === typeId);
    const id = 'v' + (++n);
    model.add({ id, x: cell ? cell.x : 0, y: cell ? cell.y : undefined, w: t.size[0], h: t.size[1], label: t.label, data: { type: typeId, title: t.label } }, { label: 'Add a ' + t.label });
    sel.set(id, 'palette');
    canvas.focus();
  }
  palette.on('add', (a) => addType(a.entry.id, null));
  canvas.on('drop', (d) => { if (d.payload.palette) { addType(d.payload.palette, d.cell); note('drop', { type: d.payload.palette, cell: d.cell }); } });
  canvas.on('open', () => { const f = inspEl.querySelector('input, select'); if (f) { f.focus(); } });
  outline.on('move', (m) => {
    if (m.parent !== 'page' && m.where === 'inside') { core.announce('A visual sits on the page.'); return; }
    const order = K.moveNode([{ id: 'page', children: model.list().map((i) => ({ id: i.id })) }], m.id, m.target, m.where === 'inside' ? 'after' : m.where);
    if (!order) { return; }
    const ids = order[0].children.map((c) => c.id);
    model.commit('Reorder', (list) => { const byId = {}; list.forEach((i) => { byId[i.id] = i; }); let y = 0; ids.forEach((id) => { byId[id].y = y; y += byId[id].h; }); list.sort((a, b) => ids.indexOf(a.id) - ids.indexOf(b.id)); return ids; });
    note('outline-move', m);
  });
  sel.on('change', (s) => {
    note('select', s);
    shell.placeInspector(s.primary ? canvas.itemEl(s.primary) : null);
    const it = s.primary && model.get(s.primary);
    shell.setStatusBar(it ? s.ids.length + ' selected · ' + label(it) + ' at column ' + (it.x + 1) + ', row ' + (it.y + 1) + ' · ' + it.w + '×' + it.h : model.ids().length + ' visuals');
  });

  /* ---- the toolbar: what the keys do, as buttons --------------------------------------------------------------------------------- */
  const btn = (text, attr, run) => { const b = core.h(document, 'button', { text, attrs: { type: 'button', [attr]: '' }, on: { click: run } }); tools.appendChild(b); return b; };
  btn('Undo', 'data-hx-undo', () => model.stack.undo());
  btn('Redo', 'data-hx-redo', () => model.stack.redo());
  ['left', 'top', 'right', 'bottom'].forEach((e) => btn('Align ' + e, 'data-hx-align-' + e, () => model.align(sel.list(), e)));
  btn('Distribute across', 'data-hx-distribute', () => model.distribute(sel.list(), 'x'));
  btn('Group', 'data-hx-group', () => model.group(sel.list()));
  btn('Front', 'data-hx-front', () => model.zOrder(sel.list(), 'front'));
  btn('Phone: first', 'data-hx-phone', () => { const id = sel.primary(); if (id) { model.arrange('phone', { order: [id].concat(model.order('phone').filter((x) => x !== id)) }); H.phone = model.order('phone'); } });

  /* ---- the header's actions ------------------------------------------------------------------------------------------------------ */
  shell.on('action', (a) => {
    note('action', a);
    if (a.id === 'save') { model.stack.markSaved(); reports[current] = doc(); shell.say('Saved as a draft.'); sync(); }
    else if (a.id === 'validate') { shell.say('Valid: no problems.'); }
    else if (a.id === 'history') { shell.dialog('History of ' + title, core.h(document, 'p', { attrs: { 'data-history': '' }, text: 'v' + status.version + ' approved by a reviewer.' })); }
    else if (a.id === 'impact') { shell.dialog('What uses ' + title, core.h(document, 'p', { attrs: { 'data-impact': '' }, text: 'Nothing uses it yet.' })); }
    else if (a.id === 'propose') {
      shell.propose(title, (text) => new Promise((resolve) => setTimeout(() => { H.proposals.push({ report: current, note: text }); status.status = 'proposed'; sync(); shell.say('Proposed for review: another person approves it.'); resolve(); }, 20)));
    }
  });

  /* ---- a flow canvas ------------------------------------------------------------------------------------------------------------- */
  const flowEl = document.getElementById('hx-flow');
  const areas = { main: flowEl.querySelector('[data-area="main"]'), right: flowEl.querySelector('[data-area="right"]') };
  const flow = H.flowModel = new K.LayoutModel({ mode: 'flow', areas: ['main', 'right'] });
  const fsel = H.flowSelection = new K.Selection();
  const flowCanvas = H.flowCanvas = new K.LayoutCanvas(flowEl, { model: flow, selection: fsel, keys: 'select', handle: '.hx-ph', label: (i) => i.label });
  function drawFlow() {
    const map = {};
    areas.main.textContent = ''; areas.right.textContent = '';
    flow.list().forEach((i) => {
      const p = core.h(document, 'section', { cls: 'hx-panel hx-s-' + (i.span || 12), attrs: { 'data-panel': i.id } }, [core.h(document, 'div', { cls: 'hx-ph', text: '⠿ ' + i.label }),
        core.h(document, 'span', { cls: 'hx-vis', text: (i.span || 12) + '/12' + (i.h ? ' · ' + i.h + ' rows' : '') })]);
      areas[i.area || 'main'].appendChild(p);
      map[i.id] = p;
    });
    flowCanvas.adopt(map, areas);
  }
  flow.on('change', drawFlow);
  flowCanvas.on('drop', (d) => { if (d.payload.item && d.target) { flow.moveTo(d.payload.item, d.target.id ? { area: d.target.area, [d.target.rel]: d.target.id } : { area: d.target.area }); note('flow-drop', d.target); } });
  flow.load([{ id: 'p1', label: 'Positions', area: 'main' }, { id: 'p2', label: 'P&L by day', area: 'main', span: 6 }, { id: 'p3', label: 'Limits', area: 'main', span: 6 }, { id: 's1', label: 'Key figures', area: 'right' }]);

  open();
  H.ready = true;
})();

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
/* The gallery's demos of drishti-designer, the designer kit: a small report designer assembled from the kit alone (the shell in each of
   its three layouts, the palette, the grid canvas, the inspector, the outline and the YAML pane kept in step both ways), and a flow
   canvas (a screen's two columns of panels). The figures and names are invented. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);

  const TYPES = [{ id: 'bar', label: 'Bar chart', category: 'Charts', size: [6, 4] }, { id: 'line', label: 'Line chart', category: 'Charts', size: [6, 4] },
    { id: 'card', label: 'Card', category: 'Numbers', size: [3, 2] }, { id: 'table', label: 'Table', category: 'Tables', size: [6, 5] }];
  const SCHEMA = { type: 'object', title: 'Visual', properties: { title: { type: 'string', title: 'Title', maxLength: 40 }, type: { type: 'string', title: 'Type', enum: ['bar', 'line', 'card', 'table'] } }, required: ['title'] };
  const START = [{ id: 'v1', x: 0, y: 0, w: 3, h: 2, data: { type: 'card', title: 'Notional' } }, { id: 'v2', x: 3, y: 0, w: 6, h: 4, data: { type: 'bar', title: 'By desk' } },
    { id: 'v3', x: 0, y: 4, w: 12, h: 4, data: { type: 'table', title: 'Trades' } }];

  /** A report designer from the kit alone, in a layout. */
  function designer(host, values, ctx) {
    const K = ctx.E.designer, doc = host.ownerDocument, h = (t, p, c) => ctx.h(doc, t, p, c);
    const box = h('div', { cls: 'gx-designer' });
    host.appendChild(box);
    const shell = new K.DesignerShell(box, { crumbs: ['Gallery', 'Reports'], title: 'Desk summary', rail: [{ id: 'palette', label: 'Palette' }, { id: 'outline', label: 'Outline' }],
      inspector: true, layout: values.layout || 'studio', actions: K.SHELL_ACTIONS });
    const sel = new K.Selection(), hub = new K.DragHub(), model = new K.LayoutModel({ cols: 12, maxH: 16 });
    const canvasEl = h('div', { attrs: { 'aria-label': 'Report page' } });
    shell.visual.appendChild(canvasEl);
    const label = (it) => it.data.title;
    const canvas = new K.LayoutCanvas(canvasEl, { model, selection: sel, hub, rowPx: 30, gap: 8, handle: '.dd-item-head', label,
      updateItem: (it, el) => { el.querySelector('.dd-item-head').textContent = label(it); el.querySelector('.dd-item-body').textContent = it.data.type + ' · ' + it.w + '×' + it.h; } });
    const palette = new K.Palette(shell.rail('palette'), { entries: TYPES, hub, label: 'Visual types' });
    const outline = new K.Outline(shell.rail('outline'), { selection: sel, label: 'The report' });
    const inspector = new K.Inspector(shell.inspector, { schemaFor: (ids) => (ids.length ? SCHEMA : null), valuesFor: (ids) => K.commonValues(ids.map((id) => model.get(id).data)).values });
    inspector.follow(sel);
    const yaml = new K.YamlPane(shell.yamlBody, { selection: sel, height: 200, label: 'The report as YAML',
      pathOf: (id) => { const i = model.ids().indexOf(id); return i < 0 ? null : 'visuals[' + i + ']'; }, idAt: (p) => { const m = /^visuals\[(\d+)\]$/.exec(p); return m ? model.ids()[+m[1]] : null; } });
    const doc0 = () => ({ title: 'Desk summary', visuals: model.list().map((i) => Object.assign({ id: i.id }, i.data, { at: { x: i.x, y: i.y, w: i.w, h: i.h } })) });
    model.on('change', () => {
      outline.setNodes(model.list().map((i) => ({ id: i.id, label: label(i), detail: i.data.type })));
      yaml.setModel(doc0());
      inspector.refresh();
      shell.setStatus({ status: 'draft', version: 3, origin: 'item', dirty: model.stack.dirty() });
      shell.setStatusBar(model.ids().length + ' visuals');
    });
    yaml.on('model', (m) => { const v = (m.model || {}).visuals; if (Array.isArray(v)) { model.commit('Edit the YAML', (list) => { list.splice(0, list.length, ...v.filter((x) => x && x.at).map((x) => ({ id: x.id, x: x.at.x, y: x.at.y, w: x.at.w, h: x.at.h, data: { type: x.type, title: x.title } }))); return []; }); } });
    inspector.on('change', (c) => model.commit('Set ' + c.path, (list) => { list.filter((i) => c.ids.indexOf(i.id) >= 0).forEach((i) => { i.data[c.path] = c.value; }); return c.ids; }));
    let n = 3;
    const add = (type, cell) => { const t = TYPES.find((x) => x.id === type), id = 'v' + (++n); model.add({ id, x: cell ? cell.x : 0, y: cell ? cell.y : undefined, w: t.size[0], h: t.size[1], data: { type, title: t.label } }); sel.set(id); ctx.log('add', { id, type }); };
    palette.on('add', (a) => add(a.entry.id));
    canvas.on('drop', (d) => { if (d.payload.palette) { add(d.payload.palette, d.cell); } });
    shell.on('action', (a) => { ctx.log('action', a); if (a.id === 'save') { model.stack.markSaved(); model.events.emit('change', {}); } shell.say(a.id + ': the host does it (a server call).'); });
    shell.on('layout', (l) => ctx.log('layout', l));
    shell.setOpen(true);
    model.load(START);
    return { target: box, element: box, ready: yaml.editor.ready || Promise.resolve(), destroy: () => { canvas.destroy(); yaml.destroy(); outline.destroy(); box.remove(); } };
  }

  demos.push({
    id: 'designer-kit', library: 'drishti-designer', kind: 'function', ag: false, keys: [], events: [], sutra: () => null,
    title: 'A designer from the kit', about: 'drishti_shell.js', types: ['ShellOptions', 'LayoutCanvasOptions', 'YamlPaneOptions'],
    summary: 'The designer shell (Visual, YAML or both; versions, Validate, Preview, History, Impact, Propose for review) in its three layouts, with the palette, the layout canvas, the inspector, the outline and the YAML pane kept in step both ways.',
    extra: [{ name: 'layout', control: { kind: 'select', values: ['studio', 'side-by-side', 'terminal'] }, desc: 'The shell\'s layout (mock-up g7: A, B or C), remembered per person by the host.' }],
    values: { layout: 'studio' },
    examples: [{ title: 'Studio (g7 A, the default)', values: { layout: 'studio' } }, { title: 'Side by side (g7 B)', values: { layout: 'side-by-side' } }, { title: 'Terminal (g7 C)', values: { layout: 'terminal' } }],
    mount: designer,
    code: (values) => ({ js: "import { DesignerShell, LayoutModel, LayoutCanvas, Selection, Palette, Inspector, Outline, YamlPane } from '/static/elements/drishti-designer/src/drishti_index.js';\n\n" +
      "const shell = new DesignerShell(el, { layout: '" + ((values && values.layout) || 'studio') + "', rail: [{ id: 'palette', label: 'Palette' }, { id: 'outline', label: 'Outline' }], inspector: true });\n" +
      "const model = new LayoutModel({ cols: 12 }), selection = new Selection();\n" +
      "new LayoutCanvas(shell.visual, { model, selection });\nnew YamlPane(shell.yamlBody, { selection });" })
  });

  demos.push({
    id: 'flow-canvas', library: 'drishti-designer', kind: 'function', ag: false, keys: [], events: [], sutra: () => null,
    title: 'A flow canvas', about: 'drishti_layout-flow.js', types: ['LayoutOptions'],
    summary: 'A screen laid out in two columns: panels in order with a span in columns; drag one by its heading to a place in either column, Alt+arrows move it, Shift+arrows size it, Ctrl+Z takes it back.',
    values: {}, examples: [{ title: 'Main and side columns', values: {} }],
    mount: function (host, values, ctx) {
      const K = ctx.E.designer, doc = host.ownerDocument, h = (t, p, c) => ctx.h(doc, t, p, c);
      const areas = { main: h('div', { cls: 'gx-flow-area', attrs: { 'data-area': 'main' } }), right: h('div', { cls: 'gx-flow-area', attrs: { 'data-area': 'right' } }) };
      const box = h('div', { cls: 'gx-flow', attrs: { 'aria-label': 'A screen' } }, [areas.main, areas.right]);
      host.appendChild(box);
      const model = new K.LayoutModel({ mode: 'flow', areas: ['main', 'right'] }), sel = new K.Selection();
      const canvas = new K.LayoutCanvas(box, { model, selection: sel, keys: 'select', handle: '.gx-flow-h', label: (i) => i.label });
      const draw = () => {
        const map = {};
        areas.main.textContent = ''; areas.right.textContent = '';
        model.list().forEach((i) => { const p = h('section', { cls: 'gx-flow-panel gx-span-' + (i.span || 12) }, [h('div', { cls: 'gx-flow-h', text: '⠿ ' + i.label }), h('span', { cls: 'gx-note', text: (i.span || 12) + '/12' })]); areas[i.area].appendChild(p); map[i.id] = p; });
        canvas.adopt(map, areas);
      };
      model.on('change', draw);
      canvas.on('drop', (d) => { if (d.payload.item && d.target) { model.moveTo(d.payload.item, d.target.id ? { area: d.target.area, [d.target.rel]: d.target.id } : { area: d.target.area }); ctx.log('moved', d.target); } });
      model.load([{ id: 'p1', label: 'Positions', area: 'main' }, { id: 'p2', label: 'By day', area: 'main', span: 6 }, { id: 'p3', label: 'Limits', area: 'main', span: 6 }, { id: 's1', label: 'Key figures', area: 'right' }]);
      return { target: box, element: box, ready: Promise.resolve(), destroy: () => { canvas.destroy(); box.remove(); } };
    },
    code: () => ({ js: "import { LayoutModel, LayoutCanvas, Selection } from '/static/elements/drishti-designer/src/drishti_index.js';\n\nconst model = new LayoutModel({ mode: 'flow', areas: ['main', 'right'] });\nconst canvas = new LayoutCanvas(screen, { model, selection: new Selection(), keys: 'select' });\ncanvas.adopt(panelsById, { main, right });" })
  });
}(typeof window !== 'undefined' ? window : globalThis));

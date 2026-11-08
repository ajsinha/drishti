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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* Rupaka phase 3 (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 9): the designer's three panes. Fields: the dataset's tables, columns (dates open into their parts), measures and
   hierarchies, with search, on drishti-tree's outline; drag one onto the canvas or press its button. Visualizations: the catalogue of visual types, then Build (the field wells of the
   selected visual) and Format (titles, labels, colours from the theme, axes, number formats, conditional formats, slicer and drill settings; with no visual
   selected, the page and the report). Filters: report, page and visual level; basic, advanced and top-N. Every action also has a button and a select, so
   the panes work without dragging. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const D = B.design;
  if (!D) { return; }
  const h = B.h;

  const ICONS = { bar: 'bar-chart-steps', column: 'bar-chart-line', line: 'graph-up', area: 'graph-up-arrow', combo: 'bar-chart', scatter: 'diagram-3', pie: 'pie-chart', donut: 'pie-chart-fill',
    waterfall: 'reception-4', treemap: 'grid-1x2', heatmap: 'grid-3x3', card: 'card-heading', 'multirow-card': 'list-columns', kpi: 'speedometer2', gauge: 'speedometer', table: 'table',
    matrix: 'grid-3x3-gap', bubble: 'record-circle', funnel: 'filter-circle', histogram: 'bar-chart-fill', box: 'distribute-vertical', map: 'globe2', slicer: 'funnel', python: 'filetype-py', 'vega-lite': 'bezier2', text: 'fonts', image: 'image', button: 'hand-index',
    tile: 'window-stack' };
  const GROUPS = [['Charts', ['bar', 'column', 'line', 'area', 'combo', 'scatter', 'bubble', 'pie', 'donut', 'funnel', 'waterfall', 'treemap', 'heatmap', 'histogram', 'box', 'map', 'vega-lite']], ['Cards', ['card', 'multirow-card', 'kpi', 'gauge']],
    ['Tables', ['table', 'matrix']], ['Python', ['python']], ['Filter and other', ['slicer', 'text', 'image', 'button']], ['Board', ['tile']]];
  const PARTS = ['year', 'quarter', 'month', 'week', 'day'];
  const OPS = { eq: 'is', ne: 'is not', lt: 'is less than', le: 'is at most', gt: 'is greater than', ge: 'is at least', between: 'is between', contains: 'contains', 'starts-with': 'starts with', 'is-null': 'is empty', 'not-null': 'is not empty' };

  D.modules.push(function () {
    B.api('GET', '/datasets').then((r) => {                           // the datasets a visual may name for itself
      const dl = document.getElementById('biDatasetNames');
      (r.datasets || []).filter((d) => d.valid).forEach((d) => dl.appendChild(h('option', { value: d.id.replace(/^file:dataset:/, ''), label: d.title })));
    }).catch(() => {});
    const q = D.q;

    // ---- Fields: drishti-tree's outline (one component for every tree; ELEMENTS.md 12.3) --------------------------------------
    // the dataset's tables and their columns (a date opens into its parts), its measures and hierarchies, in folders as the model files
    // them; each field a button (a click, or Enter, adds it; a drag puts it on the canvas or a well); badges say masked, this report only,
    // default or model, the time function; a measure of this report only on a model links to the model; one branch per added dataset
    const tree = q('[data-fields-tree]');
    const search = q('[data-fields-search]');
    const SEP = '\u0001';
    let outline = null, drawn = '';
    const modelPages = {};                                          // a bound model's name -> its item page ('' while asked, false: none)

    /** The time-intelligence function a measure uses (year to date, month to date, ... running total), or null: shown as a badge in the Fields pane. */
    function timeKind(expr) {
      const m = /\b(ytd|mtd|qtd|prior_period|same_period_last_year|running|cumulative|lag|lead|rank|dense_rank|percent_rank|moving_avg|moving_sum|moving_min|moving_max)\s*\(/.exec(expr || '');
      return m ? ({ prior_period: 'prior period', same_period_last_year: 'last year', running: 'running total', cumulative: 'running total', lag: 'previous row', lead: 'next row',
        rank: 'rank', dense_rank: 'dense rank', percent_rank: 'percent rank', moving_avg: 'moving average', moving_sum: 'moving sum', moving_min: 'moving min', moving_max: 'moving max' })[m[1]] || m[1] : null;
    }

    /** The model's field properties inherited (BI_DESIGN.md 8.1): hidden fields and measures are left out, a perspective narrows the list, folders group. */
    const persp = (ds) => { const p = D.perspective && ds.perspectives && ds.perspectives[D.perspective]; return p && p.fields && p.measures ? p : null; };
    const shownMeasure = (ds, m) => !m.hidden && (!persp(ds) || m.local || persp(ds).measures.indexOf(m.name) >= 0);
    const shownField = (ds, t, c) => !c.hidden && (!persp(ds) || persp(ds).fields.indexOf(t.name + '.' + c.name) >= 0);

    /** The item page of the model a report measure would move to (asked once; the pane is drawn again when it is known). */
    function modelPage(bound) {
      const name = bound.split('@')[0];
      if (modelPages[name] === undefined) {
        modelPages[name] = '';
        B.layer.get('datasets').then((d) => { const it = (d.models || []).find((x) => x.kind === 'model' && x.name === name); modelPages[name] = it ? '/build/tables?kind=model&item=' + B.id2path(it.id) : false; })
          .catch(() => { modelPages[name] = false; }).then(() => drawFields());
      }
      return modelPages[name] || null;
    }

    /** The rows of one dataset's tree, under `top` (null: at the top). */
    function rowsOf(rows, ds, idx, ref, top) {
      const base = (ref || 'main') + SEP;
      const mixed = (ds.measures || []).some((m) => m.implicit) && (ds.measures || []).some((m) => !m.implicit);   // both kinds in one list: say which is which
      const folders = {};
      const folder = (parent, f) => {
        const id = parent + SEP + 'folder' + SEP + f;
        if (!folders[id]) { folders[id] = true; rows.push({ id, parent, label: f, folder: f }); }
        return id;
      };
      const bound = (B.modelBinding && B.modelBinding.ref) || '';
      const mid = base + 'measures';
      rows.push({ id: mid, parent: top, label: 'Measures', table: 'measures' });
      (ds.measures || []).filter((m) => shownMeasure(ds, m)).forEach((m) => {
        const time = timeKind(m.expr);
        rows.push({ id: base + 'm' + SEP + m.name, parent: m.folder ? folder(mid, m.folder) : mid, label: m.name, words: m.name + ' ' + (m.description || '') + (time ? ' time ' + time : ''),
          field: { name: m.name, kind: 'measure', ref, masked: !!m.masked, time, local: !!m.local, origin: mixed ? (m.implicit ? 'default' : 'model') : '',
            move: m.local && bound && bound.indexOf('dataset:') !== 0 ? modelPage(bound) : null,
            title: (time ? '[' + time + '] ' : '') + m.name + ': ' + m.expr + (m.description ? ' — ' + m.description : '') + (mixed ? (m.implicit ? ' (a default measure, made from a column)' : ' (defined in the model)') : '') } });
      });
      (ds.tables || []).forEach((t) => {
        const cols = t.columns.filter((c) => shownField(ds, t, c));
        if (!cols.length) { return; }
        const tid = base + 't' + SEP + t.name;
        rows.push({ id: tid, parent: top, label: t.name, table: t.name, kind: t.kind || '' });
        cols.forEach((c) => {
          const name = t.name + '.' + c.name, id = base + 'f' + SEP + name;
          rows.push({ id, parent: c.folder ? folder(tid, c.folder) : tid, label: (c.display || c.name) + (c.key ? ' · key' : ''), words: name + ' ' + (c.display || '') + ' ' + (c.description || ''),
            field: { name, kind: 'field', ref, masked: !!c.masked, title: (c.display ? c.display + ' (' + name + ')' : name) + ' (' + c.type + ')' + (c.description ? ': ' + c.description : '') } });
          if (c.type === 'date' || c.type === 'timestamp') {
            PARTS.forEach((p) => rows.push({ id: id + SEP + p, parent: id, label: p, words: name + ' ' + p, field: { name: name + '.' + p, kind: 'field', ref, title: name + '.' + p } }));
          }
        });
      });
      const hs = Object.keys(idx.hierarchies);
      if (hs.length) {
        const hid = base + 'hierarchies';
        rows.push({ id: hid, parent: top, label: 'Hierarchies', table: 'hierarchies', closed: true });
        hs.forEach((n) => rows.push({ id: base + 'h' + SEP + n, parent: hid, label: n, field: { name: n, kind: 'hierarchy', ref, title: n + ': ' + idx.hierarchies[n].join(' > ') } }));
      }
    }

    const fieldOf = (n) => (n && n.data ? n.data.field : null);
    const addIt = (f) => D.addField(f.name, f.kind, { useSelected: true, dataset: f.ref || '' });
    function marks(n) {
      const f = fieldOf(n), out = [];
      if (!f) { return n.data.kind ? [{ text: n.data.kind }] : []; }
      if (f.masked) { out.push({ text: 'masked', badge: true, tone: 'warn' }); }
      if (f.local) { out.push({ text: 'this report only', badge: true, tone: 'accent', attrs: { 'data-origin': 'report' } }); }
      if (f.origin) { out.push({ text: f.origin, badge: true, attrs: { 'data-origin': f.origin } }); }
      if (f.time) { out.push({ text: f.time, badge: true, tone: 'link', attrs: { 'data-time': f.time } }); }
      if (f.move) { out.push({ text: 'Move to the model', href: f.move, attrs: { 'data-move-measure': f.name } }); }
      return out;
    }

    /** The Fields pane: one tree for the report's dataset and, when datasets were added, one branch for each (labelled, removable when no visual uses it). */
    function drawFields() {
      const rows = [];
      const extra = D.extraEntries ? D.extraEntries() : [];
      if (!extra.length) { rowsOf(rows, D.dataset, D.idx, '', null); } else {
        const branch = (ref, ds, idx, title, main) => {
          const id = 'dataset' + SEP + (ref || 'main');
          rows.push({ id, parent: null, label: title + (main ? ' (main)' : ''), dataset: ds.id || ref, ref, main, used: main ? false : D.datasetUsed(ref) });
          rowsOf(rows, ds, idx, ref, id);
        };
        branch('', D.dataset, D.idx, D.dataset.title || D.dataset.id || D.model.dataset, true);
        extra.forEach((x) => branch(x.ref, x.dataset, x.idx, x.title, false));
      }
      const data = { rows };
      const sig = JSON.stringify(rows);
      // drawn again only when it differs: a redraw that changes nothing keeps the buttons in place, so a field being dragged, or focused, is not taken from under the reader
      if (sig !== drawn) {
        drawn = sig;
        if (outline) { outline.setData(data); } else {
          outline = new window.Drishti.elements.tree.TreeOutline(tree, { data, title: 'Field', label: 'Fields', density: 'compact', fitRows: 5000, fill: true,
            text: (n) => n.data.words || n.label,
            marks,
            startOpen: (n) => !n.data.closed && !fieldOf(n),
            button: (n) => { const f = fieldOf(n); return f ? { label: 'Add ' + f.name + ' to the report', title: f.title, attrs: { 'data-field': f.name, 'data-kind': f.kind } } : null; },
            activate: (n) => addIt(fieldOf(n)),
            wire: (n, el) => {                                           // dragged onto the canvas or a well (drishti-designer's DragHub)
              const f = fieldOf(n);
              if (!f) { return; }
              const { name, kind, ref } = f;
              D.hub.draggable(el, { payload: () => ({ name, kind, dataset: ref || '' }), label: () => n.label });
            },
            attrs: (n) => (n.data.table ? { 'data-table': n.data.table } : n.data.folder ? { 'data-folder': n.data.folder } : n.data.dataset ? { 'data-dataset-tree': n.data.dataset } : null),
            actions: (n) => (n.data.dataset && !n.data.main ? [{ label: n.data.used ? 'Remove ' + n.label + ' (a visual reads it)' : 'Remove ' + n.label, text: '×', disabled: n.data.used,
              title: n.data.used ? 'A visual reads this dataset' : 'Remove from the Fields pane', attrs: { 'data-remove-dataset': n.data.dataset }, run: () => D.removeDataset(n.data.ref) }] : []),
            onKey: (n, e) => { const f = fieldOf(n); if (f && e.key === 'Enter') { addIt(f); return true; } return false; }
          });
          if (search.value) { outline.filter(search.value); }
        }
      }
      q('[data-model-link]').href = '/bi/m/' + B.id2path(D.dataset.id || '');
    }
    D.drawFields = drawFields;

    search.addEventListener('input', () => { if (outline) { outline.filter(search.value); } });

    // ---- Visualizations: the catalogue ---------------------------------------------------------------------------------
    const types = q('[data-types]');
    let typePalette = null;
    function drawTypes() {
      B.clear(types);
      let pickType = 'table';
      const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-add-visual': '', title: 'Add an empty visual of the type chosen beside it' }, B.icon('plus-lg'), ' Add visual');
      add.addEventListener('click', () => D.addVisual(pickType));
      const bar = h('div', { class: 'bi-addvisual' });
      types.appendChild(bar);
      const all = Object.keys(D.catalogue.visuals);
      B.formatPane.form(bar, { label: 'Add a visual', value: { type: pickType }, on: { type: (x) => { pickType = x || 'table'; } },
        fields: { type: { type: 'string', title: 'Type of the visual to add', enum: all, widget: 'select', allowEmpty: false, hook: 'data-add-visual-type' } } });
      bar.appendChild(add);
      const host = h('div', { class: 'bi-typepalette' });
      types.appendChild(host);
      const entries = [];
      GROUPS.forEach(([title, list]) => list.forEach((t) => entries.push({ id: t, label: t, category: title, icon: 'bi bi-' + ICONS[t], payload: { vtype: t }, attrs: { 'data-type': t } })));
      typePalette = new window.Drishti.elements.designer.Palette(host, { entries, hub: D.hub, label: 'Visualizations', placeholder: 'Search the visuals' });
      typePalette.on('add', (e) => chooseType(e.entry.id));
      markTypes();
    }

    /** What a well may take ({value, label}): fields as table.column, measures, and the model's field parameters (2.6.0: the reader's
     *  parameter slicer chooses which field or measure the visual draws). */
    function choicesFor(kind, ds0) {
      const ds = ds0 || D.dataset, out = [];
      if (kind !== 'measure') { (ds.tables || []).forEach((t) => t.columns.forEach((c) => out.push({ value: t.name + '.' + c.name, label: t.name + ' › ' + c.name }))); }
      if (kind !== 'field') { (ds.measures || []).forEach((m) => out.push({ value: m.name, label: m.name + ' (measure)' })); }
      (ds.parameters || []).filter((p) => p.kind === 'field' && (kind === 'any' || D.paramKind('param.' + p.name) === kind))
        .forEach((p) => out.push({ value: 'param.' + p.name, label: (p.title || p.name) + ' (' + (p.fields || []).map(B.label).join(' / ') + ')' }));
      return out;
    }

    /** What would be lost turning `v` into `type`: the wells' items no well of the new type takes (or takes beyond its room). */
    function lostIn(v, type) {
      D.curIdx = D.idxOf(v);
      const probe = { type, wells: {} };
      const items = Object.keys(v.wells).reduce((a, k) => a.concat(v.wells[k]), []);
      const lost = items.filter((x) => !D.addToVisual(probe, x));
      const kept = Object.keys(probe.wells).reduce((a, k) => a.concat(probe.wells[k]), []);
      D.curIdx = null;
      return lost.concat(items.filter((x) => lost.indexOf(x) < 0 && kept.indexOf(x) < 0));
    }

    function chooseType(type) {
      const v = D.sel();
      if (!v) { D.addVisual(type); return; }
      if (v.type === type) { return; }
      const dropped = lostIn(v, type);
      const anchor = B.q('[data-type="' + type + '"]', types) || B.q('[data-type]', types);
      const proceed = dropped.length ? B.confirm(anchor, 'Change "' + (v.title || v.id) + '" to a ' + type + '? ' + dropped.map(B.label).join(', ') + (dropped.length === 1 ? ' does' : ' do') + ' not fit its wells and will be removed from it (Undo brings it back). Choose Cancel and use Add visual to make a new ' + type + ' instead.', 'Change it') : Promise.resolve(true);
      proceed.then((yes) => { if (yes) { convert(v, type); } });
    }

    /** Format keys only one kind of visual reads; they go when the visual becomes a kind that does not read them (a slicer's `mode` on a column chart is noise). */
    const OWN_KEYS = { slicer: ['mode', 'scope', 'default', 'relative'], perspective: ['view', 'plugin'], vega: ['spec'], table: ['conditional', 'dataBars', 'subtotals', 'totals'], matrix: ['conditional', 'dataBars', 'subtotals', 'totals'] };

    function convert(v, type) {
      D.curIdx = D.idxOf(v);
      D.change('Change to a ' + type, () => {
        const old = v.wells;
        const old0 = v.type;
        const specs = D.wellSpecs(type);
        const items = Object.keys(old).reduce((a, k) => a.concat(old[k]), []);
        v.type = type;
        v.wells = {};
        items.forEach((x) => D.addToVisual(v, x));
        Object.keys(specs).forEach((w) => { if (v.wells[w] && !v.wells[w].length) { delete v.wells[w]; } });
        D.auto.delete(v.id);
        if (v.format) {
          (OWN_KEYS[old0] || []).forEach((k) => { if ((OWN_KEYS[type] || []).indexOf(k) < 0) { delete v.format[k]; } });
          if (!Object.keys(v.format).length) { delete v.format; }
        }
        if (type === 'slicer') { v.format = Object.assign({ mode: 'list', scope: 'page' }, v.format); }
        D.followType(v, type);                                   // the id says what the visual is, and the bookmarks and layouts that named the old one follow
      });
      D.curIdx = null;
      D.select(v.id);
    }

    function markTypes() {
      const v = D.sel();
      B.qa('[data-dd-entry]', types).forEach((b) => b.setAttribute('aria-selected', v && v.type === b.getAttribute('data-type') ? 'true' : 'false'));
    }

    // ---- Build: wells --------------------------------------------------------------------------------------------------
    const build = q('[data-build]');

    function drawBuild() {
      B.clear(build);
      const v = D.sel();
      if (!v) { build.appendChild(h('p', { class: 'bi-vis-empty', text: 'Select a visual on the canvas, or drag a field onto it, to fill its wells.' })); return; }
      const specs = D.wellSpecs(v.type);
      build.appendChild(h('p', { class: 'bi-build-head' }, h('strong', { text: v.type }), ' · ' + (v.title || v.id)));
      Object.keys(specs).forEach((w) => {
        const spec = specs[w], items = v.wells[w] || [];
        const ul = h('ul', { class: 'bi-chiplist' });
        items.forEach((name, i) => {
          const li = h('li', { class: 'bi-wchip' + (name.indexOf('param.') === 0 ? ' bi-wchip-param' : ''), 'data-item': name, title: name.indexOf('param.') === 0 ? 'A field parameter: the reader chooses which field or measure is drawn' : '' }, h('span', { text: name }));
          const mv = (d) => D.change('Reorder', () => { const l = v.wells[w]; const j = i + d; if (j >= 0 && j < l.length) { const t = l[i]; l[i] = l[j]; l[j] = t; } });
          if (items.length > 1) {
            const l = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Move ' + name + ' earlier', disabled: i === 0 }, B.icon('chevron-up'));
            const r = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Move ' + name + ' later', disabled: i === items.length - 1 }, B.icon('chevron-down'));
            l.addEventListener('click', () => mv(-1));
            r.addEventListener('click', () => mv(1));
            li.appendChild(l); li.appendChild(r);
          }
          const x = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove ' + name + ' from ' + w, 'data-remove': name }, B.icon('x-lg'));
          x.addEventListener('click', () => D.removeFromWell(v, w, name));
          li.appendChild(x);
          ul.appendChild(li);
        });
        // the well's "Add a field…": a drishti-inputs Form select (data-add="<well>")
        const offer = choicesFor(spec.kind, D.dsOf(v).dataset), addBox = h('div', { class: 'bi-fform bi-wadd' });
        B.formatPane.form(addBox, { label: 'Add to ' + w, fields: { add: { type: 'string', title: 'Add to ' + w, enum: offer.map((o) => o.value), enumLabels: offer.map((o) => o.label), widget: 'select',
          emptyLabel: spec.kind === 'measure' ? 'Add a measure…' : spec.kind === 'field' ? 'Add a field…' : 'Add a field or measure…', hook: { 'data-add': w } } },
        on: { add: (name) => { if (name) { D.withIdx(D.idxOf(v), () => D.change('Add ' + B.label(name), () => { D.putInWell(v, w, name); D.auto.delete(v.id); })); } } } });
        const fs = h('fieldset', { class: 'bi-well', 'data-well': w }, h('legend', {}, w, h('small', { class: 'text-muted-d', text: ' ' + (spec.kind === 'any' ? 'fields and measures' : spec.kind + 's') + (spec.required ? ', needed' : '') + (spec.max ? ', up to ' + spec.max : '') })), ul, addBox);
        D.hub.target({ el: fs, accepts: (p) => !!p && !p.vtype, over: () => fs.classList.add('bi-drop'), leave: () => fs.classList.remove('bi-drop'), drop: (x, y, p) => {      // a field dropped on the well
          fs.classList.remove('bi-drop');
          const clash = D.dsConflict(v, p.dataset || '');
          if (clash) { D.say(clash, true); return; }
          D.withIdx(D.idxForRef(p.dataset || ''), () => {
            if (!D.change('Add ' + B.label(p.name), () => { D.dsClaim(v, p.dataset || ''); if (!D.putInWell(v, w, p.name)) { D.say(p.name + ' does not go in the ' + w + ' well.', true); } D.auto.delete(v.id); })) { D.say(p.name + ' does not go in the ' + w + ' well, or is already there.', true); }
          });
        } });
        build.appendChild(fs);
      });
      const group = (D.catalogue.visuals[v.type] || {}).group;
      if (group === 'chart' || group === 'table') {
        const sel = h('select', { class: 'studio-select', 'aria-label': 'Drill down along a hierarchy', 'data-hierarchy': '' }, h('option', { value: '', text: 'none' }), Object.keys(D.idxOf(v).hierarchies).map((n) => h('option', { value: n, text: n })));
        sel.value = v.hierarchy || '';
        sel.addEventListener('change', () => D.change('Set the hierarchy', () => { if (sel.value) { v.hierarchy = sel.value; } else { delete v.hierarchy; } }));
        build.appendChild(h('label', { class: 'bi-frow' }, h('span', { text: 'Drill down along' }), sel));
      }
      if (group !== 'static' && group !== 'slicer') {
        const pages = D.model.pages.filter((p) => p.id !== D.pageId);
        const pg = h('select', { class: 'studio-select', 'aria-label': 'Drill through to page', 'data-through-page': '' }, h('option', { value: '', text: 'none' }), pages.map((p) => h('option', { value: p.id, text: p.title })));
        const allFields = Object.keys(v.wells).reduce((a, k) => a.concat(v.wells[k]), []).filter((x) => D.kindOf(x) === 'field');
        const fl = h('select', { class: 'studio-select', 'aria-label': 'Drill through carries', 'data-through-field': '' }, allFields.map((x) => h('option', { value: x, text: x })));
        pg.value = v.drillthrough ? v.drillthrough.page : '';
        if (v.drillthrough) { fl.value = v.drillthrough.field; }
        const apply = () => D.change('Set the drill-through', (m) => {
          if (!pg.value || !fl.value) { delete v.drillthrough; return; }
          v.drillthrough = { page: pg.value, field: fl.value };
          const target = m.pages.find((p) => p.id === pg.value);
          if (target) { target.drillthrough = target.drillthrough || { fields: [] }; if (target.drillthrough.fields.indexOf(fl.value) < 0) { target.drillthrough.fields.push(fl.value); } }
        });
        pg.addEventListener('change', apply);
        fl.addEventListener('change', apply);
        build.appendChild(h('div', { class: 'bi-frow' }, h('span', { text: 'Drill through to' }), pg, h('span', { text: 'carrying' }), fl));
      }
    }

    // ---- Format --------------------------------------------------------------------------------------------------------
    const fmt = q('[data-format]');
    // a format key with '' or null is removed; `false` is kept (a setting whose default is on, such as a matrix's stepped layout, is turned off by it)
    const setFmt = (v, key, val) => D.change('Format ' + key, () => { v.format = v.format || {}; if (val === '' || val == null) { delete v.format[key]; } else { v.format[key] = val; } if (!Object.keys(v.format).length) { delete v.format; } });
    const openSections = {};                                   // section name -> open, kept while the designer is open (the pane is drawn again after every change)
    const setTop = (v, key, val) => D.change('Set ' + key, () => { if (val === '' || val == null) { delete v[key]; } else { v[key] = val; } });
    const trimmed = (x) => String(x == null ? '' : x).trim();
    /** A live Form of settings (drishti-inputs, B.formatPane.form): rows of [key, field, value, on change]. */
    function settings(label, rows, cls) {
      const fields = {}, value = {}, on = {};
      rows = rows.filter(Boolean);
      if (!rows.length) { return null; }
      rows.forEach(([k, f, x, fn]) => { fields[k] = f; if (x !== undefined && x !== null && x !== '') { value[k] = x; } on[k] = fn; });
      const host = h('div', { class: 'bi-fform' + (cls ? ' ' + cls : '') });
      fmt.appendChild(host);
      B.formatPane.form(host, { fields, value, on, label });
      return host;
    }
    const text = (title, hook, more) => Object.assign({ type: 'string', title, lazy: true, hook }, more || {});

    function drawFormat() {
      B.clear(fmt);
      const v = D.sel();
      if (!v) { drawPageFormat(); return; }
      const group = (D.catalogue.visuals[v.type] || {}).group;
      const f = v.format || {};
      const pages = D.model.pages, bms = D.model.bookmarks || [], act = v.action || {};
      settings('The visual', [
        ['title', text('Title', 'data-format-title'), v.title, (x) => setTop(v, 'title', trimmed(x))],
        ['alt', text('Alt text', 'data-format-alt', { description: 'What a screen reader says about it.' }), v.alt, (x) => setTop(v, 'alt', trimmed(x))],
        group !== 'static' && group !== 'board' ? ['dataset', text('Dataset', { 'data-format-dataset': '', list: 'biDatasetNames' }, { placeholder: D.model.dataset,          // a visual may read a dataset of its own (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.10)
          description: 'Blank: the report\'s dataset (' + (D.dataset && D.dataset.title ? D.dataset.title + ', ' : '') + D.model.dataset + '). Another dataset: its fields are written in the wells in the YAML tab, and a link (Report, below the page) connects its slicers to the others.' }),
        v.dataset, (x) => setTop(v, 'dataset', trimmed(x))] : null
      ]);
      if (v.type === 'tile') { D.tileEditor(fmt, v); }
      settings('The ' + v.type, [
        v.type === 'text' ? ['text', { type: 'string', title: 'Text', widget: 'textarea', lazy: true, hook: 'data-format-text', options: { rows: 3 } }, v.text, (x) => setTop(v, 'text', x || '')] : null,
        v.type === 'image' ? ['src', text('Image', 'data-format-src', { placeholder: '/static/... or a data:image/ URI' }), v.src, (x) => setTop(v, 'src', trimmed(x))] : null,
        v.type === 'button' ? ['label', text('Label', 'data-button-label'), v.text, (x) => setTop(v, 'text', x || '')] : null,
        v.type === 'button' ? ['navigate', { type: 'string', title: 'Go to page', enum: pages.map((x) => x.id), enumLabels: pages.map((x) => x.title), widget: 'select', emptyLabel: 'none', hook: 'data-button-page' }, act.navigate,
          (x) => D.change('Set the button', () => { v.action = x ? { navigate: x } : {}; })] : null,
        v.type === 'button' ? ['bookmark', { type: 'string', title: 'or apply bookmark', enum: bms.map((b) => b.name), widget: 'select', emptyLabel: 'none', hook: 'data-button-bookmark' }, act.bookmark,
          (x) => D.change('Set the button', () => { v.action = x ? { bookmark: x } : {}; })] : null
      ]);
      // the typed format of the visual's family (report schema v2): generated from GET /reports/format-schemas
      const gen = h('div', { class: 'bi-fgen', 'data-format-generated': v.type });
      fmt.appendChild(gen);
      const inWells = Object.keys(v.wells).reduce((a, k) => a.concat(v.wells[k]), []);
      const ms = inWells.filter((x) => D.kindOf(x) === 'measure'), fs = inWells.filter((x) => D.kindOf(x) === 'field');
      B.formatPane.draw(gen, {
        type: v.type, format: f, setFmt: (k, x) => setFmt(v, k, x), palette: D.palette, measures: ms, fields: fs, series: ms, theme: D.theme, tokens: D.tokens,
        modelMeasures: (D.dsOf(v).dataset.measures || []).map((m) => m.name),
        slicerMode: (x) => { setFmt(v, 'mode', x); if (x !== 'relative-date') { setFmt(v, 'default', ''); setFmt(v, 'options', ''); } if (x !== 'list' && v.at.h > 2) { D.change('Resize a slicer', () => { v.at.h = 2; }); } },
        open: (g) => openSections[g] !== false, toggled: (g, open) => { openSections[g] = open; }
      });
      if (group !== 'static' && group !== 'board') {
        if (group !== 'slicer') { D.tooltipPicker(fmt, v); }
        D.interactionsEditor(fmt, v);
        const fields = Object.keys(v.wells).reduce((a, k) => a.concat(v.wells[k]), []), slicer = group === 'slicer';
        settings('How it behaves', [
          ['interacts', { type: 'boolean', title: 'Other visuals filter this one', hook: 'data-format-interacts' }, v.interacts !== false, (x) => setTop(v, 'interacts', x ? '' : false)],
          slicer ? null : ['limit', { type: 'integer', title: 'Most rows', minimum: 1, lazy: true, hook: 'data-format-limit' }, v.limit,
            (x) => { if (x === undefined || !isNaN(x)) { setTop(v, 'limit', x === undefined ? '' : Math.max(1, Math.round(x) || 1)); } }],
          slicer || !fields.length ? null : ['sortBy', { type: 'string', title: 'Sort by', enum: fields, widget: 'select', emptyLabel: 'default', hook: 'data-sort-by' }, v.sort ? v.sort.by : undefined,
            (x) => D.change('Sort', () => { if (x) { v.sort = { by: x, desc: v.sort ? v.sort.desc : D.kindOf(x) === 'measure' }; } else { delete v.sort; } })],
          slicer || !fields.length || !v.sort ? null : ['desc', { type: 'boolean', title: 'Descending', hook: 'data-sort-desc' }, !!v.sort.desc, (x) => D.change('Sort', () => { v.sort.desc = x; })]
        ]);
      }
    }

    function drawPageFormat() {
      const p = D.page(), m = D.model;
      fmt.appendChild(h('h4', { class: 'bi-fhead', text: 'Page' }));
      settings('The page', [['title', text('Page title', 'data-page-title'), p.title, (x) => D.change('Rename the page', () => { p.title = trimmed(x) || p.title; })]]);
      const ix = m.pages.indexOf(p);
      const acts = h('div', { class: 'bi-actions' });
      const mk = (label, fn, dis, attr) => { const b = h('button', Object.assign({ type: 'button', class: 'btn-pill btn-ghost bi-small', disabled: !!dis }, attr || {}), label);
        // a title being edited commits (and the pane redraws) when the pointer goes down on a button: act then, or the click lands on a button that is gone
        let fired = false;
        b.addEventListener('pointerdown', (e) => { if (e.button === 0 && e.pointerType !== 'touch') { const a = document.activeElement; if (a && a !== b && a.tagName === 'INPUT') { e.preventDefault(); a.blur(); } fired = true; fn(); } });
        b.addEventListener('click', (e) => { if (fired) { fired = false; e.preventDefault(); return; } fn(); });
        return b; };
      acts.appendChild(mk('Move earlier', () => D.change('Move the page', () => { m.pages.splice(ix - 1, 0, m.pages.splice(ix, 1)[0]); }), ix === 0));
      acts.appendChild(mk('Move later', () => D.change('Move the page', () => { m.pages.splice(ix + 1, 0, m.pages.splice(ix, 1)[0]); }), ix === m.pages.length - 1));
      acts.appendChild(mk('Delete the page', () => { B.confirm(B.q('[data-delete-page]'), 'Delete the page "' + p.title + '" and its visuals?', 'Delete the page').then((yes) => { if (yes) { D.change('Delete the page', () => { m.pages.splice(ix, 1); }); D.setPage(m.pages[0].id); } }); }, m.pages.length < 2, { 'data-delete-page': '' }));
      fmt.appendChild(acts);
      const dt = (p.drillthrough || {}).fields || [];
      const allFields = (D.dataset.tables || []).flatMap((t) => t.columns.map((c) => t.name + '.' + c.name));
      const setDrill = (l) => D.change('Drill-through page', () => { if (l.length) { p.drillthrough = { fields: l }; } else { delete p.drillthrough; } });
      settings('This page is a drill-through target for', [
        dt.length ? ['fields', { type: 'array', title: 'This page is a drill-through target for', items: { type: 'string', enum: dt }, widget: 'checkboxes', optionHook: 'page-drill-field',
          description: 'Untick a field to stop taking it.' }, dt, (l) => setDrill(dt.filter((x) => (l || []).indexOf(x) >= 0))] : null,
        ['add', { type: 'string', title: 'Take this field as a drill-through filter', enum: allFields.filter((x) => dt.indexOf(x) < 0), widget: 'select', emptyLabel: 'Add a drill-through field…', hook: 'data-page-drill' }, undefined,
          (x) => { if (x) { setDrill(dt.concat([x])); } }]
      ], 'bi-drill-target');
      D.pageV2(fmt, p);
      fmt.appendChild(h('h4', { class: 'bi-fhead', text: 'Report' }));
      settings('The report', [
        ['title', text('Report title', 'data-report-title'), m.title, (x) => D.change('Rename the report', () => { m.title = trimmed(x) || m.title; })],
        ['description', text('Description', 'data-report-description'), m.description, (x) => D.change('Describe the report', () => { if (trimmed(x)) { m.description = trimmed(x); } else { delete m.description; } })]
      ]);
      D.reportV2(fmt, m);
      const dates = m.dates || {};
      settings('Colours and dates', [
        ['palette', { type: 'string', title: 'Colours', enum: B.PALETTES, widget: 'select', allowEmpty: false, hook: 'data-theme-palette', description: 'default follows the console theme; colour-blind is Okabe-Ito.' },
          (m.theme || {}).palette || 'default', (x) => D.change('Theme', () => { m.theme = Object.assign({}, m.theme, { palette: x }); })],
        ['from', { type: 'date', title: 'Business date from', hook: 'data-dates-from' }, dates.from, (x) => setDates({ from: x })],
        ['to', { type: 'date', title: 'Business date to', hook: 'data-dates-to' }, dates.to, (x) => setDates({ to: x })]
      ]);
      fmt.appendChild(h('p', { class: 'bi-frow' }, h('span', { text: 'Dataset' }), h('code', { text: m.dataset })));
      const linkRows = (m.links || []).map((l) => ({ fields: l.fields.join(', ') }));
      settings('Links between datasets', [['links', { type: 'array', title: 'Links between datasets', addLabel: 'Link fields', hook: 'data-links',
        description: 'Fields of different datasets that mean the same thing (table.column, separated by commas): a slicer or a click on one filters the visuals of the other.',
        items: { type: 'object', title: 'Link', properties: { fields: { type: 'string', title: 'Fields that mean the same', placeholder: 'counterparties.sector, joined.sector', lazy: true, hook: 'data-link-fields' } } } },
      linkRows, (rows) => {
        const next = (rows || []).map((r) => String((r && r.fields) || '').split(',').map((x) => x.trim()).filter(Boolean)).filter((f) => f.length >= 2).map((f) => ({ fields: f }));
        if (JSON.stringify(next) !== JSON.stringify(m.links || [])) { D.change(next.length < (m.links || []).length ? 'Remove a link' : 'Link fields', () => { if (next.length) { m.links = next; } else { delete m.links; } }); }
      }]]);
      const bm = h('fieldset', { class: 'bi-fieldset', 'data-bookmarks': '' }, h('legend', { text: 'Bookmarks' }));
      const pageOf = (id) => D.model.pages.find((x) => x.id === id) || p;
      const snapshot = (pg) => (pg.filters || []).map((f) => B.clone(f));
      const summary = (b) => ((b.filters || []).length ? (b.filters || []).length + ' filter' + (b.filters.length === 1 ? '' : 's') : 'no filters') + (Object.keys(b.slicers || {}).length ? ', ' + Object.keys(b.slicers).length + ' slicer choice' + (Object.keys(b.slicers).length === 1 ? '' : 's') : '');
      (m.bookmarks || []).forEach((b, i) => {
        const upd = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-bookmark-update': String(i), 'aria-label': 'Update bookmark ' + b.name + ' from the filters of ' + b.page }, 'Update from the page\'s filters');
        upd.addEventListener('click', () => D.change('Update a bookmark', () => { const f = snapshot(pageOf(b.page)); if (f.length) { b.filters = f; } else { delete b.filters; } }));
        const x = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove bookmark ' + b.name }, B.icon('x-lg'));
        x.addEventListener('click', () => D.change('Remove a bookmark', () => { m.bookmarks.splice(i, 1); if (!m.bookmarks.length) { delete m.bookmarks; } }));
        const nameBox = h('div', { class: 'bi-fform' });
        bm.appendChild(h('div', { class: 'bi-cond', 'data-bookmark': b.name }, nameBox, h('span', { class: 'text-muted-d', text: ' on ' + b.page + ': ' + summary(b) + ' ' }), upd, x));
        B.formatPane.form(nameBox, { label: 'Bookmark ' + b.name, value: { name: b.name },
          fields: { name: { type: 'string', title: 'Name of bookmark ' + b.name, lazy: true, hook: { 'data-bookmark-rename': String(i) } } },
          on: { name: (v) => { const n = String(v || '').trim(); if (n && n !== b.name) { D.change('Rename a bookmark', () => { const was = b.name; b.name = n; D.model.pages.forEach((pg) => pg.visuals.forEach((vv) => { if (vv.action && vv.action.bookmark === was) { vv.action.bookmark = n; } })); }); } } } });
      });
      const slicers = p.visuals.filter((v) => v.type === 'slicer');
      const fresh = { name: { type: 'string', title: 'Bookmark name', placeholder: 'Bookmark name', hook: 'data-bookmark-name' } };
      slicers.forEach((v, k) => {
        const rel = (v.format || {}).mode === 'relative-date', label = v.title || B.label((v.wells.field || [''])[0]), offered = (v.format || {}).options || B.visuals.RELATIVE;
        fresh['s' + k] = rel ? { type: 'string', title: 'Slicer ' + label, enum: offered, enumLabels: offered.map(B.visuals.relativeLabel), widget: 'select', emptyLabel: 'not set', hook: { 'data-bookmark-slicer': v.id } }
          : { type: 'string', title: 'Slicer ' + label, placeholder: 'Values, separated by commas', hook: { 'data-bookmark-slicer': v.id } };
      });
      const newBox = h('div', { class: 'bi-fform', 'data-bookmark-slicers': '' });
      bm.appendChild(newBox);
      const newForm = B.formatPane.form(newBox, { label: 'A new bookmark', fields: fresh, value: {} });
      const addB = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', text: 'Save this page as a bookmark', 'data-add-bookmark': '' });
      addB.addEventListener('click', () => {
        const n = String(newForm.getField('name') || '').trim();
        if (!n) { D.say('Name the bookmark first.', true); return; }
        if ((m.bookmarks || []).some((b) => b.name === n)) { D.say('There is a bookmark called ' + n + ' already.', true); return; }
        D.change('Add a bookmark', () => {
          const b = { name: n, page: p.id }, f = snapshot(p), sl = {};
          slicers.forEach((v, k) => {
            const x = newForm.getField('s' + k);
            if ((v.format || {}).mode === 'relative-date') { if (x) { sl[v.id] = { relative: x }; } return; }
            const list = String(x || '').split(',').map((t) => t.trim()).filter(Boolean);
            if (list.length) { sl[v.id] = list; }
          });
          if (Object.keys(sl).length) { b.slicers = sl; }
          if (f.length) { b.filters = f; }
          (m.bookmarks = m.bookmarks || []).push(b);
        });
      });
      bm.appendChild(addB);
      bm.appendChild(h('small', { class: 'text-muted-d', text: 'A bookmark keeps this page, its filters (the Filters pane) and the slicer choices you set here. Choosing it in the viewer applies them.' }));
      fmt.appendChild(bm);
    }
    function setDates(part) {
      D.change('Business dates', (m) => {
        const d = Object.assign({}, m.dates, part);
        Object.keys(d).forEach((k) => { if (!d[k]) { delete d[k]; } });
        if (Object.keys(d).length) { m.dates = d; } else { delete m.dates; }
      });
    }

    // ---- Filters -------------------------------------------------------------------------------------------------------
    const filtersBox = q('[data-filters]');
    let editing = null;                                  // {scope, index} of the filter being edited, or {scope, index: -1} for a new one

    function scopeFilters(scope, create) {
      if (scope === 'report') { return create ? (D.model.filters = D.model.filters || []) : (D.model.filters || []); }
      if (scope === 'page') { const p = D.page(); return create ? (p.filters = p.filters || []) : (p.filters || []); }
      const v = D.sel();
      if (!v) { return []; }
      return create ? (v.filters = v.filters || []) : (v.filters || []);
    }
    function describeFilter(f) {
      if (f.op === 'top') { return B.label(f.field) + ': top ' + f.n + (f.dir === 'asc' ? ' lowest' : '') + ' by ' + f.by; }
      const vals = (f.values || []).join(', ');
      return B.label(f.field) + ' ' + (f.op === 'in' ? 'is one of' : f.op === 'not-in' ? 'is not one of' : OPS[f.op] || f.op) + (f.op === 'between' ? ' ' + (f.values || []).join(' and ') : vals ? ' ' + vals : '');
    }

    function drawFilters() {
      B.clear(filtersBox);
      [['report', 'On all pages'], ['page', 'On this page'], ['visual', 'On this visual']].forEach(([scope, title]) => {
        const v = D.sel();
        const sec = h('section', { class: 'bi-fsec', 'data-scope': scope }, h('h4', { class: 'bi-fhead', text: title + (scope === 'visual' ? (v ? ' (' + (v.title || v.id) + ')' : '') : '') }));
        if (scope === 'visual' && !v) { sec.appendChild(h('p', { class: 'bi-vis-empty', text: 'Select a visual to filter it.' })); filtersBox.appendChild(sec); return; }
        const list = scopeFilters(scope, false);
        const ul = h('ul', { class: 'bi-filterlist' });
        list.forEach((f, i) => {
          const li = h('li', { class: 'bi-fitem', 'data-filter': f.field }, h('span', { class: 'bi-filter-text', text: describeFilter(f) }));
          const ed = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Edit the filter on ' + f.field }, B.icon('pencil'));
          ed.addEventListener('click', () => { editing = { scope, index: i }; drawFilters(); });
          const rm = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove the filter on ' + f.field, 'data-remove-filter': f.field }, B.icon('x-lg'));
          rm.addEventListener('click', () => D.change('Remove a filter', () => { scopeFilters(scope, true).splice(i, 1); if (!scopeFilters(scope, true).length) { delete (scope === 'report' ? D.model : scope === 'page' ? D.page() : D.sel()).filters; } }));
          li.appendChild(ed); li.appendChild(rm);
          ul.appendChild(li);
        });
        sec.appendChild(ul);
        if (editing && editing.scope === scope) { sec.appendChild(filterEditor(scope, editing.index)); } else {
          const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-add-filter': scope, text: 'Add a filter' });
          add.addEventListener('click', () => { editing = { scope, index: -1 }; drawFilters(); });
          sec.appendChild(add);
        }
        filtersBox.appendChild(sec);
      });
    }

    /** A filter being added or changed: drishti-inputs' Form, its fields following the kind of filter (when), every control named as
     *  before (field, mode, op, values, v1, v2, n, dir, by); Pick from the data loads the field's values as a row of checkboxes. */
    function filterEditor(scope, index) {
      const cur = index >= 0 ? scopeFilters(scope, false)[index] : null;
      const host = h('div', { class: 'bi-feditor', 'data-filter-editor': '' });
      const all = (D.dataset.tables || []).flatMap((t) => t.columns.map((c) => t.name + '.' + c.name));
      const first = all[0] || '';
      const sv = D.sel(), svField = sv && Object.keys(sv.wells || {}).reduce((a, k) => a.concat(sv.wells[k]), []).find((x) => D.kindOf(x) === 'field');           // a new filter starts on the selected visual's own field
      const startField = cur ? cur.field : svField && all.indexOf(svField) >= 0 ? svField : first;
      const mode0 = !cur ? 'basic' : cur.op === 'top' ? 'top' : (cur.op === 'in' || cur.op === 'not-in') ? 'basic' : 'advanced';
      const ops = ['in', 'not-in'].concat(Object.keys(OPS)), parents = {};
      ops.forEach((o) => { parents[o] = o === 'in' || o === 'not-in' ? ['basic'] : ['advanced']; });
      const ms = (D.dataset.measures || []).map((m) => m.name);
      const nulls = ['is-null', 'not-null'];
      const vals = cur ? (cur.values || []) : [];
      let picked = null;                                   // the values read from the data, once Pick from the data was pressed
      const schema = { type: 'object', properties: {
        field: { type: 'string', title: 'Field', enum: all, widget: 'select', allowEmpty: false, name: 'field' },
        mode: { type: 'string', title: 'Filter type', enum: ['basic', 'advanced', 'top'], enumLabels: ['Basic: pick values', 'Advanced: a condition', 'Top N'], widget: 'select', allowEmpty: false, name: 'mode' },
        op: { type: 'string', title: 'Condition', enum: ops, enumLabels: ['is one of', 'is not one of'].concat(Object.keys(OPS).map((k) => OPS[k])), widget: 'select', allowEmpty: false, name: 'op',
          dependsOn: 'mode', parents, when: { field: 'mode', notEquals: 'top' } },
        values: { type: 'string', title: 'Values', placeholder: 'Values, separated by commas', name: 'values', when: { field: 'mode', equals: 'basic' } },
        pick: { type: 'string', title: 'Pick from the data', widget: 'pick', noLabel: true, when: { field: 'mode', equals: 'basic' } },
        v1: { type: 'string', title: 'Value', name: 'v1', when: { all: [{ field: 'mode', equals: 'advanced' }, { field: 'op', notIn: nulls }] } },
        v2: { type: 'string', title: 'and', name: 'v2', when: { all: [{ field: 'mode', equals: 'advanced' }, { field: 'op', equals: 'between' }] } },
        n: { type: 'integer', title: 'Keep the top', minimum: 1, name: 'n', when: { field: 'mode', equals: 'top' } },
        dir: { type: 'string', title: 'with the', enum: ['desc', 'asc'], enumLabels: ['highest', 'lowest'], widget: 'select', allowEmpty: false, name: 'dir', when: { field: 'mode', equals: 'top' } },
        by: { type: 'string', title: 'by measure', enum: ms, widget: 'select', allowEmpty: false, name: 'by', when: { field: 'mode', equals: 'top' } }
      } };
      const value = { field: startField, mode: mode0, op: mode0 === 'top' ? undefined : cur ? cur.op : 'in', values: mode0 === 'basic' && vals.length ? vals.join(', ') : undefined,
        v1: mode0 === 'advanced' && vals[0] != null ? String(vals[0]) : undefined, v2: mode0 === 'advanced' && vals[1] != null ? String(vals[1]) : undefined,
        n: cur ? cur.n || 5 : 5, dir: cur ? cur.dir || 'desc' : 'desc', by: cur && cur.by ? cur.by : ms[0] };
      const typed = () => String(form.getField('values') || '').split(',').map((x) => x.trim()).filter(Boolean);
      const widgets = { pick: (w) => {
        const box = h('div', { class: 'bi-pick', 'data-pick': '' });
        const load = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-load-values': '', text: 'Pick from the data' });
        const list = h('div', { class: 'bi-fform' });
        const draw = () => {
          B.clear(list);
          if (!picked) { return; }
          const chosen = typed();
          const sub = new window.Drishti.elements.inputs.Form(list, { embedded: true, label: 'Values', schema: { type: 'object', properties: {
            chosen: { type: 'array', title: 'Values', items: { type: 'string', enum: picked }, widget: 'checkboxes', optionHook: 'pick-value' } } }, value: { chosen: picked.filter((x) => chosen.indexOf(x) >= 0) } });
          sub.on('change', () => { const keep = typed().filter((x) => picked.indexOf(x) < 0); form.setField('values', keep.concat(sub.getField('chosen') || []).join(', ') || undefined); });
        };
        load.addEventListener('click', () => {
          B.clear(list).appendChild(h('p', { class: 'bi-vis-note', text: 'Reading values…' }));
          const ref = (B.modelBinding && B.modelBinding.ref) || B.layer.refOf(D.model.model || D.model.dataset || D.dataset.id);
          B.enqueue(() => B.mq.values(ref, form.getField('field'), 200)).then((res) => {
            picked = res.rows.map((r) => r[0]).filter((x) => x != null).map(String);
            draw();
            if (res.rowCount >= 200) { list.appendChild(h('p', { class: 'bi-vis-note', text: 'The first 200 values; type the others above.' })); }
          }, (e) => { B.clear(list).appendChild(h('p', { class: 'bi-vis-note', text: e.message })); });
        });
        box.append(load, list);
        draw();
        return { el: box };
      } };
      const form = new window.Drishti.elements.inputs.Form(host, { schema, value, widgets, submitLabel: cur ? 'Update the filter' : 'Add the filter', resetButton: false, label: 'Filter',
        onSubmit: (v) => {
          const f = { field: v.field, op: v.op };
          const type = (B.column(D.idx, v.field) || {}).type;
          const num = (x) => (['integer', 'decimal'].indexOf(type) >= 0 && x !== '' && isFinite(Number(x)) ? Number(x) : x);
          if (v.mode === 'top') { f.op = 'top'; f.n = v.n || 1; f.by = v.by; f.dir = v.dir; }
          else if (nulls.indexOf(f.op) >= 0) { f.values = []; }
          else {
            const raw = v.mode === 'basic' ? typed() : [v.v1, v.v2].filter((x) => x !== undefined);
            f.values = raw.filter((x) => x !== '' && x != null).map(num);
            if (!f.values.length) { D.say('Give the filter a value.', true); return Promise.reject(new Error('Give the filter a value.')); }
          }
          if (f.op === 'between' && f.values.length < 2) { D.say('between needs two values.', true); return Promise.reject(new Error('between needs two values.')); }
          D.change(cur ? 'Change a filter' : 'Add a filter', () => { const l = scopeFilters(scope, true); if (cur) { l[index] = f; } else { l.push(f); } });
          editing = null;
          drawFilters();
          return null;
        } });
      form.on('change', (e) => {
        if (e.path === 'field') { picked = null; form.setField('values', undefined); form.setField('pick', undefined); }
        if (e.path === 'mode') { form.setField('values', undefined); form.setField('v1', undefined); form.setField('v2', undefined); form.setField('op', e.value === 'basic' ? 'in' : e.value === 'advanced' ? 'eq' : undefined); }
      });
      const cancel = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', text: 'Cancel', 'data-filter-cancel': '' });
      cancel.addEventListener('click', () => { editing = null; drawFilters(); });
      host.appendChild(cancel);
      return host;
    }

    // ---- the tabs of the middle pane and redrawing ---------------------------------------------------------------------
    function showPane(name) {
      ['build', 'format'].forEach((t) => {
        q('[data-pane="' + t + '"]').hidden = t !== name;
        q('[data-ptab="' + t + '"]').setAttribute('aria-selected', t === name ? 'true' : 'false');
      });
    }
    ['build', 'format'].forEach((t) => q('[data-ptab="' + t + '"]').addEventListener('click', () => showPane(t)));
    D.showPane = showPane;

    const focusedInside = (el) => el.contains(document.activeElement) && /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement.tagName);
    function redraw() {
      markTypes();
      // do not rebuild a pane under a field that is being edited: the change handler already redraws after the change
      D.withIdx(D.sel() ? D.idxOf(D.sel()) : null, () => { drawBuild(); drawFormat(); });
      if (!editing || !focusedInside(filtersBox)) { drawFilters(); }
    }
    drawFields();
    drawTypes();
    D.on('model', redraw);
    D.on('page', () => { editing = null; redraw(); });
    D.on('select', () => { editing = null; redraw(); });
    redraw();
  });
})();

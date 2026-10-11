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
/* Rupaka phase 3 (RUPAKA.md section 9): the designer's three panes. Fields: the dataset's tables, columns (dates open into their parts), measures and
   hierarchies, with search; drag one onto the canvas or press its button. Visualizations: the catalogue of visual types, then Build (the field wells of the
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
    matrix: 'grid-3x3-gap', bubble: 'record-circle', funnel: 'filter-circle', histogram: 'bar-chart-fill', box: 'distribute-vertical', map: 'globe2', slicer: 'funnel', python: 'filetype-py', text: 'fonts', image: 'image', button: 'hand-index' };
  const GROUPS = [['Charts', ['bar', 'column', 'line', 'area', 'combo', 'scatter', 'bubble', 'pie', 'donut', 'funnel', 'waterfall', 'treemap', 'heatmap', 'histogram', 'box', 'map']], ['Cards', ['card', 'multirow-card', 'kpi', 'gauge']],
    ['Tables', ['table', 'matrix']], ['Python', ['python']], ['Filter and other', ['slicer', 'text', 'image', 'button']]];
  const TYPE_ICON = { string: 'fonts', integer: '123', decimal: '123', boolean: 'check2-square', date: 'calendar3', timestamp: 'clock' };
  const PARTS = ['year', 'quarter', 'month', 'week', 'day'];
  const NUMBER_FORMATS = ['', 'amount0', 'signed0', 'signed2', 'compact', 'pct'];
  const OPS = { eq: 'is', ne: 'is not', lt: 'is less than', le: 'is at most', gt: 'is greater than', ge: 'is at least', between: 'is between', contains: 'contains', 'starts-with': 'starts with', 'is-null': 'is empty', 'not-null': 'is not empty' };

  D.modules.push(function () {
    const q = D.q;

    // ---- Fields --------------------------------------------------------------------------------------------------------
    const tree = q('[data-fields-tree]');
    const search = q('[data-fields-search]');

    function fieldButton(name, kind, label, icon, title, masked) {
      const b = h('button', { type: 'button', class: 'bi-field', draggable: 'true', 'data-field': name, 'data-kind': kind, title: title || name, 'aria-label': 'Add ' + name + ' to the report' }, B.icon(icon), ' ', h('span', { class: 'bi-field-name', text: label }),
        masked ? h('small', { class: 'bi-badge', text: 'masked' }) : null);
      b.addEventListener('dragstart', (e) => {
        e.dataTransfer.setData(D.MIME, JSON.stringify({ name, kind }));
        e.dataTransfer.setData('text/plain', name);
        e.dataTransfer.effectAllowed = 'copy';
      });
      b.addEventListener('click', () => D.addField(name, kind, { useSelected: true }));
      return b;
    }

    /** The time-intelligence function a measure uses (year to date, month to date, ... running total), or null: shown as a badge in the Fields pane. */
    function timeKind(expr) {
      const m = /\b(ytd|mtd|qtd|prior_period|same_period_last_year|running)\s*\(/.exec(expr || '');
      return m ? ({ prior_period: 'prior period', same_period_last_year: 'last year', running: 'running total' })[m[1]] || m[1] : null;
    }

    function drawFields() {
      B.clear(tree);
      (D.dataset.tables || []).forEach((t) => {
        const ul = h('ul', { class: 'bi-fieldlist' });
        t.columns.forEach((c) => {
          const name = t.name + '.' + c.name;
          const li = h('li', { 'data-search': (name + ' ' + (c.description || '')).toLowerCase() }, fieldButton(name, 'field', c.name + (c.key ? ' · key' : ''), TYPE_ICON[c.type] || 'fonts', name + ' (' + c.type + ')' + (c.description ? ': ' + c.description : ''), c.masked));
          if (c.type === 'date' || c.type === 'timestamp') {
            const sub = h('ul', { class: 'bi-fieldlist bi-parts' });
            PARTS.forEach((p) => sub.appendChild(h('li', { 'data-search': (name + ' ' + p).toLowerCase() }, fieldButton(name + '.' + p, 'field', p, 'calendar3-week', name + '.' + p))));
            li.appendChild(h('details', { class: 'bi-fieldparts' }, h('summary', { text: 'date parts' }), sub));
          }
          ul.appendChild(li);
        });
        tree.appendChild(h('details', { class: 'bi-fieldtable', open: true, 'data-table': t.name }, h('summary', {}, B.icon('table'), ' ' + t.name, h('small', { class: 'text-muted-d', text: ' ' + (t.kind || '') })), ul));
      });
      const ms = h('ul', { class: 'bi-fieldlist' });
      (D.dataset.measures || []).forEach((m) => {
        const time = timeKind(m.expr);
        const btn = fieldButton(m.name, 'measure', m.name, time ? 'calendar-range' : 'calculator', (time ? '[' + time + '] ' : '') + m.name + ': ' + m.expr + (m.description ? ' — ' + m.description : ''));
        if (time) { btn.appendChild(h('small', { class: 'bi-badge', 'data-time': time, text: time })); }
        ms.appendChild(h('li', { 'data-search': (m.name + ' ' + (m.description || '') + ' ' + (time ? 'time ' + time : '')).toLowerCase() }, btn));
      });
      tree.appendChild(h('details', { class: 'bi-fieldtable', open: true, 'data-table': 'measures' }, h('summary', {}, B.icon('calculator'), ' Measures'), ms));
      const hs = h('ul', { class: 'bi-fieldlist' });
      Object.keys(D.idx.hierarchies).forEach((n) => hs.appendChild(h('li', { 'data-search': n.toLowerCase() }, fieldButton(n, 'hierarchy', n, 'diagram-2', n + ': ' + D.idx.hierarchies[n].join(' > ')))));
      if (hs.children.length) { tree.appendChild(h('details', { class: 'bi-fieldtable', 'data-table': 'hierarchies' }, h('summary', {}, B.icon('diagram-2'), ' Hierarchies'), hs)); }
      q('[data-model-link]').href = '/bi/m/' + B.id2path(D.dataset.id || '');
    }

    search.addEventListener('input', () => {
      const t = search.value.trim().toLowerCase();
      B.qa('li[data-search]', tree).forEach((li) => { li.hidden = !!t && li.getAttribute('data-search').indexOf(t) < 0; });
      B.qa('details.bi-fieldtable', tree).forEach((d) => { const any = B.qa('li[data-search]', d).some((li) => !li.hidden); d.hidden = !!t && !any; if (t && any) { d.open = true; } });
    });

    // ---- Visualizations: the catalogue ---------------------------------------------------------------------------------
    const types = q('[data-types]');
    function drawTypes() {
      B.clear(types);
      GROUPS.forEach(([title, list]) => {
        types.appendChild(h('p', { class: 'bi-type-group', text: title }));
        const row = h('div', { class: 'bi-typegrid', role: 'group', 'aria-label': title });
        list.forEach((t) => {
          const b = h('button', { type: 'button', class: 'bi-vtype', 'data-type': t, 'aria-label': t, title: t, 'aria-pressed': 'false' }, B.icon(ICONS[t]), h('span', { text: t }));
          b.addEventListener('click', () => chooseType(t));
          row.appendChild(b);
        });
        types.appendChild(row);
      });
    }

    function chooseType(type) {
      const v = D.sel();
      if (!v) { D.addVisual(type); return; }
      if (v.type === type) { return; }
      const was = v.type;
      D.change('Change to a ' + type, () => {
        const old = v.wells;
        const specs = D.wellSpecs(type);
        const items = Object.keys(old).reduce((a, k) => a.concat(old[k]), []);
        v.type = type;
        v.wells = {};
        items.forEach((x) => D.addToVisual(v, x));
        Object.keys(specs).forEach((w) => { if (v.wells[w] && !v.wells[w].length) { delete v.wells[w]; } });
        D.auto.delete(v.id);
        if (type === 'slicer') { v.format = Object.assign({ mode: 'list', scope: 'page' }, v.format); }
        if (v.id.indexOf(was + '-') === 0 && was !== 'slicer' && type !== 'slicer') { v.id = D.newVisualId(type); }          // the id says what the visual is
      });
      D.select(v.id);
    }

    function markTypes() {
      const v = D.sel();
      B.qa('.bi-vtype', types).forEach((b) => b.setAttribute('aria-pressed', v && v.type === b.getAttribute('data-type') ? 'true' : 'false'));
    }

    // ---- Build: wells --------------------------------------------------------------------------------------------------
    const build = q('[data-build]');
    function optionsFor(kind) {
      const frag = document.createDocumentFragment();
      if (kind !== 'measure') {
        (D.dataset.tables || []).forEach((t) => {
          const g = h('optgroup', { label: t.name });
          t.columns.forEach((c) => g.appendChild(h('option', { value: t.name + '.' + c.name, text: c.name })));
          frag.appendChild(g);
        });
      }
      if (kind !== 'field') {
        const g = h('optgroup', { label: 'Measures' });
        (D.dataset.measures || []).forEach((m) => g.appendChild(h('option', { value: m.name, text: m.name })));
        frag.appendChild(g);
      }
      return frag;
    }

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
          const li = h('li', { class: 'bi-wchip', 'data-item': name }, h('span', { text: name }));
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
        const add = h('select', { class: 'studio-select bi-wadd', 'aria-label': 'Add to ' + w, 'data-add': w }, h('option', { value: '', text: spec.kind === 'measure' ? 'Add a measure…' : spec.kind === 'field' ? 'Add a field…' : 'Add a field or measure…' }), optionsFor(spec.kind));
        add.addEventListener('change', () => {
          if (!add.value) { return; }
          const name = add.value;
          D.change('Add ' + B.label(name), () => { D.putInWell(v, w, name); D.auto.delete(v.id); });
        });
        const fs = h('fieldset', { class: 'bi-well', 'data-well': w }, h('legend', {}, w, h('small', { class: 'text-muted-d', text: ' ' + (spec.kind === 'any' ? 'fields and measures' : spec.kind + 's') + (spec.required ? ', needed' : '') + (spec.max ? ', up to ' + spec.max : '') })), ul, add);
        fs.addEventListener('dragover', (e) => { if ([...e.dataTransfer.types].indexOf(D.MIME) >= 0) { e.preventDefault(); fs.classList.add('bi-drop'); } });
        fs.addEventListener('dragleave', () => fs.classList.remove('bi-drop'));
        fs.addEventListener('drop', (e) => {
          fs.classList.remove('bi-drop');
          let p = null;
          try { p = JSON.parse(e.dataTransfer.getData(D.MIME)); } catch (err) { p = null; }
          if (!p) { return; }
          e.preventDefault();
          e.stopPropagation();
          if (!D.change('Add ' + B.label(p.name), () => { if (!D.putInWell(v, w, p.name)) { D.say(p.name + ' does not go in the ' + w + ' well.', true); } D.auto.delete(v.id); })) { D.say(p.name + ' does not go in the ' + w + ' well.', true); }
        });
        build.appendChild(fs);
      });
      const group = (D.catalogue.visuals[v.type] || {}).group;
      if (group === 'chart' || group === 'table') {
        const sel = h('select', { class: 'studio-select', 'aria-label': 'Drill down along a hierarchy', 'data-hierarchy': '' }, h('option', { value: '', text: 'none' }), Object.keys(D.idx.hierarchies).map((n) => h('option', { value: n, text: n })));
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
    function frow(label, input, hint) { return h('label', { class: 'bi-frow' }, h('span', { text: label }), input, hint ? h('small', { class: 'text-muted-d', text: hint }) : null); }
    function textInput(value, onChange, attrs) {
      const i = h('input', Object.assign({ type: 'text', class: 'studio-in', autocomplete: 'off', value: value || '' }, attrs || {}));
      i.addEventListener('change', () => onChange(i.value));
      return i;
    }
    function checkbox(label, value, onChange, attrs) {
      const c = h('input', Object.assign({ type: 'checkbox' }, attrs || {}));
      c.checked = !!value;
      c.addEventListener('change', () => onChange(c.checked));
      return h('label', { class: 'bi-checkrow' }, c, ' ' + label);
    }
    function selectInput(options, value, onChange, attrs) {
      const s = h('select', Object.assign({ class: 'studio-select' }, attrs || {}), options.map((o) => h('option', { value: o[0], text: o[1] })));
      s.value = value == null ? '' : value;
      s.addEventListener('change', () => onChange(s.value));
      return s;
    }
    const setFmt = (v, key, val) => D.change('Format ' + key, () => { v.format = v.format || {}; if (val === '' || val === false || val == null) { delete v.format[key]; } else { v.format[key] = val; } if (!Object.keys(v.format).length) { delete v.format; } });
    const setTop = (v, key, val) => D.change('Set ' + key, () => { if (val === '' || val == null) { delete v[key]; } else { v[key] = val; } });

    function drawFormat() {
      B.clear(fmt);
      const v = D.sel();
      if (!v) { drawPageFormat(); return; }
      const group = (D.catalogue.visuals[v.type] || {}).group;
      const f = v.format || {};
      fmt.appendChild(frow('Title', textInput(v.title, (x) => setTop(v, 'title', x.trim()), { 'data-format-title': '' })));
      fmt.appendChild(frow('Alt text', textInput(v.alt, (x) => setTop(v, 'alt', x.trim()), { 'data-format-alt': '' }), 'What a screen reader says about it.'));
      if (group === 'python') {
        fmt.appendChild(frow('Python item', textInput(f.python, (x) => setFmt(v, 'python', x.trim()), { 'data-format-python': '', placeholder: 'pnl-histogram' }), 'The Python item (catalogue id, file name or title) that draws this from the columns. Leave it empty when the values well holds a Python measure of the dataset.'));
        fmt.appendChild(frow('Run on', selectInput([['', 'auto'], ['server', 'the console (sandboxed)'], ['browser', 'the browser (Pyodide)']], f.run, (x) => setFmt(v, 'run', x), { 'data-format-run': '' }), 'auto: the console, then the browser when the console cannot run it.'));
        fmt.appendChild(frow('Parameters (JSON)', textInput(f.params ? JSON.stringify(f.params) : '', (x) => {
          if (x.trim() === '') { setFmt(v, 'params', ''); return; }
          try { const o = JSON.parse(x); if (o && typeof o === 'object' && !Array.isArray(o)) { setFmt(v, 'params', o); } } catch (e) { /* keep what was there until it parses */ }
        }, { 'data-format-params': '', placeholder: '{"bins": 24}' }), 'Handed to the function as its params; they override the item\'s defaults.'));
      }
      if (group === 'chart') {
        fmt.appendChild(checkbox('Data labels', f.labels, (x) => setFmt(v, 'labels', x), { 'data-format-labels': '' }));
        if (['bar', 'column', 'area'].indexOf(v.type) >= 0) { fmt.appendChild(checkbox('Stacked', f.stacked, (x) => setFmt(v, 'stacked', x), { 'data-format-stacked': '' })); }
        if (['line', 'area', 'combo'].indexOf(v.type) >= 0) { fmt.appendChild(checkbox('Smooth lines', f.smooth, (x) => setFmt(v, 'smooth', x))); }
        if (v.type === 'histogram') {
          fmt.appendChild(frow('Bars', textInput(f.bins, (x) => setFmt(v, 'bins', x === '' ? '' : Number(x)), { type: 'number', min: '2', max: '100', 'data-format-bins': '' }), 'Equal-width bands between the smallest and the largest value (2 to 100, default 10).'));
        }
        if (v.type === 'map') {
          fmt.appendChild(h('p', { class: 'bi-vis-note', text: (v.wells.location || []).length ? 'Shades countries by the code in the location well (ISO alpha-2 or alpha-3, or the English name).' : 'Marks points at the latitude and longitude of the rows.' }));
        }
        if (['map', 'histogram', 'box', 'funnel', 'bubble'].indexOf(v.type) < 0) fmt.appendChild(frow('Legend', selectInput([['', 'default'], ['bottom', 'bottom'], ['top', 'top'], ['right', 'right'], ['none', 'none']], f.legend, (x) => setFmt(v, 'legend', x))));
        if (['bar', 'column', 'line', 'area', 'combo'].indexOf(v.type) >= 0) {
          const at = f.axisTitles || {};
          fmt.appendChild(frow('Category axis title', textInput(at.x, (x) => setFmt(v, 'axisTitles', Object.assign({}, at, { x }))), null));
          fmt.appendChild(frow('Value axis title', textInput(at.y, (x) => setFmt(v, 'axisTitles', Object.assign({}, at, { y: x })))));
        }
        colourRow(v, f);
      }
      if (group === 'chart' || group === 'card' || group === 'table') {
        fmt.appendChild(frow('Number format', selectInput(NUMBER_FORMATS.map((n) => [n, n || 'from the dataset']), f.numberFormat, (x) => setFmt(v, 'numberFormat', x), { 'data-format-number': '' })));
      }
      if (v.type === 'gauge') {
        fmt.appendChild(frow('Minimum', textInput(f.min, (x) => setFmt(v, 'min', x === '' ? '' : Number(x)), { type: 'number' })));
        fmt.appendChild(frow('Maximum', textInput(f.max, (x) => setFmt(v, 'max', x === '' ? '' : Number(x)), { type: 'number' })));
      }
      if (v.type === 'table' || v.type === 'matrix') {
        const ms = Object.keys(v.wells).reduce((a, k) => a.concat(v.wells[k]), []).filter((x) => D.kindOf(x) === 'measure');
        if (ms.length) {
          const box = h('fieldset', { class: 'bi-fieldset' }, h('legend', { text: 'Data bars' }));
          ms.forEach((m) => box.appendChild(checkbox(m, (f.dataBars || []).indexOf(m) >= 0, (on) => {
            const cur = (f.dataBars || []).filter((x) => x !== m).concat(on ? [m] : []);
            setFmt(v, 'dataBars', cur.length ? cur : '');
          }, { 'data-databar': m })));
          fmt.appendChild(box);
        }
      }
      if (v.type === 'matrix') { fmt.appendChild(checkbox('Subtotals and totals', f.subtotals, (x) => setFmt(v, 'subtotals', x), { 'data-format-subtotals': '' })); }
      if (v.type === 'table') { fmt.appendChild(checkbox('Total row (worked out over all the rows)', f.totals, (x) => setFmt(v, 'totals', x), { 'data-format-totals': '' })); }
      if (['card', 'kpi', 'gauge', 'table', 'matrix', 'bar', 'column', 'multirow-card'].indexOf(v.type) >= 0) { conditionalEditor(v, f); }
      if (v.type === 'slicer') {
        fmt.appendChild(frow('Mode', selectInput([['list', 'list'], ['dropdown', 'dropdown'], ['range', 'range (numbers and dates)']], f.mode || 'list', (x) => { setFmt(v, 'mode', x); if (x !== 'list' && v.at.h > 2) { D.change('Resize a slicer', () => { v.at.h = 2; }); } }, { 'data-format-mode': '' })));
        fmt.appendChild(frow('Filters', selectInput([['page', 'this page'], ['report', 'every page']], f.scope || 'page', (x) => setFmt(v, 'scope', x), { 'data-format-scope': '' })));
      }
      if (v.type === 'text') {
        const ta = h('textarea', { class: 'studio-in', rows: '3', 'data-format-text': '' });
        ta.value = v.text || '';
        ta.addEventListener('change', () => setTop(v, 'text', ta.value));
        fmt.appendChild(frow('Text', ta));
        fmt.appendChild(frow('Size', selectInput([['', 'body'], ['heading', 'heading']], f.size, (x) => setFmt(v, 'size', x))));
      }
      if (v.type === 'image') { fmt.appendChild(frow('Image', textInput(v.src, (x) => setTop(v, 'src', x.trim()), { placeholder: '/static/... or a data:image/ URI' }))); }
      if (v.type === 'button') {
        fmt.appendChild(frow('Label', textInput(v.text, (x) => setTop(v, 'text', x))));
        const act = v.action || {};
        const pages = [['', 'none']].concat(D.model.pages.map((p) => [p.id, p.title]));
        fmt.appendChild(frow('Go to page', selectInput(pages, act.navigate || '', (x) => D.change('Set the button', () => { v.action = x ? { navigate: x } : {}; }), { 'data-button-page': '' })));
        const bms = [['', 'none']].concat((D.model.bookmarks || []).map((b) => [b.name, b.name]));
        fmt.appendChild(frow('or apply bookmark', selectInput(bms, act.bookmark || '', (x) => D.change('Set the button', () => { v.action = x ? { bookmark: x } : {}; }))));
      }
      if (group !== 'static') {
        fmt.appendChild(checkbox('Other visuals filter this one', v.interacts !== false, (x) => setTop(v, 'interacts', x ? '' : false), { 'data-format-interacts': '' }));
        if (group !== 'slicer') {
          fmt.appendChild(frow('Most rows', textInput(v.limit, (x) => setTop(v, 'limit', x === '' ? '' : Math.max(1, parseInt(x, 10) || 1)), { type: 'number', min: '1' })));
          const fields = Object.keys(v.wells).reduce((a, k) => a.concat(v.wells[k]), []);
          if (fields.length) {
            fmt.appendChild(frow('Sort by', selectInput([['', 'default']].concat(fields.map((x) => [x, x])), v.sort ? v.sort.by : '', (x) => D.change('Sort', () => { if (x) { v.sort = { by: x, desc: v.sort ? v.sort.desc : D.kindOf(x) === 'measure' }; } else { delete v.sort; } }), { 'data-sort-by': '' })));
            if (v.sort) { fmt.appendChild(checkbox('Descending', v.sort.desc, (x) => D.change('Sort', () => { v.sort.desc = x; }))); }
          }
        }
      }
    }

    function colourRow(v, f) {
      const row = h('div', { class: 'bi-frow' }, h('span', { text: 'Colour' }));
      const sw = h('div', { class: 'bi-swatches', role: 'group', 'aria-label': 'Theme colours' });
      D.palette.forEach((c, i) => {
        const b = h('button', { type: 'button', class: 'bi-swatch', 'aria-label': 'Theme colour ' + (i + 1), title: c, 'aria-pressed': f.colour === c ? 'true' : 'false', 'data-colour': c });
        b.style.background = c;
        b.addEventListener('click', () => setFmt(v, 'colour', hex(c)));
        sw.appendChild(b);
      });
      const none = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', text: 'Theme default' });
      none.addEventListener('click', () => setFmt(v, 'colour', ''));
      row.appendChild(sw);
      row.appendChild(none);
      fmt.appendChild(row);
    }
    function hex(c) {
      if (/^#[0-9a-f]{6}$/i.test(c)) { return c; }
      const m = /rgba?\((\d+)[ ,]+(\d+)[ ,]+(\d+)/.exec(c);
      return m ? '#' + [m[1], m[2], m[3]].map((n) => ('0' + (+n).toString(16)).slice(-2)).join('') : c;
    }

    function conditionalEditor(v, f) {
      const ms = Object.keys(v.wells).reduce((a, k) => a.concat(v.wells[k]), []).filter((x) => D.kindOf(x) === 'measure');
      if (!ms.length) { return; }
      const box = h('fieldset', { class: 'bi-fieldset', 'data-conditional': '' }, h('legend', { text: 'Conditional colours' }));
      (f.conditional || []).forEach((c, i) => {
        const row = h('div', { class: 'bi-cond' },
          selectInput(ms.map((m) => [m, m]), c.measure, (x) => D.change('Conditional format', () => { v.format.conditional[i].measure = x; }), { 'aria-label': 'Measure' }),
          selectInput(['lt', 'le', 'gt', 'ge', 'eq', 'ne'].map((o) => [o, { lt: '<', le: '≤', gt: '>', ge: '≥', eq: '=', ne: '≠' }[o]]), c.op, (x) => D.change('Conditional format', () => { v.format.conditional[i].op = x; }), { 'aria-label': 'Comparison' }),
          textInput(c.value, (x) => D.change('Conditional format', () => { v.format.conditional[i].value = Number(x); }), { type: 'number', 'aria-label': 'Value' }),
          textInput(c.colour || '#b3261e', (x) => D.change('Conditional format', () => { v.format.conditional[i].colour = x; }), { type: 'color', 'aria-label': 'Colour' }));
        const del = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove this conditional colour' }, B.icon('x-lg'));
        del.addEventListener('click', () => D.change('Conditional format', () => { v.format.conditional.splice(i, 1); if (!v.format.conditional.length) { delete v.format.conditional; } }));
        row.appendChild(del);
        box.appendChild(row);
      });
      const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-add-conditional': '', text: 'Add a rule' });
      add.addEventListener('click', () => D.change('Conditional format', () => { v.format = v.format || {}; (v.format.conditional = v.format.conditional || []).push({ measure: ms[0], op: 'lt', value: 0, colour: '#b3261e' }); }));
      box.appendChild(add);
      fmt.appendChild(box);
    }

    function drawPageFormat() {
      const p = D.page(), m = D.model;
      fmt.appendChild(h('h4', { class: 'bi-fhead', text: 'Page' }));
      fmt.appendChild(frow('Page title', textInput(p.title, (x) => D.change('Rename the page', () => { p.title = x.trim() || p.title; }), { 'data-page-title': '' })));
      const ix = m.pages.indexOf(p);
      const acts = h('div', { class: 'bi-actions' });
      const mk = (label, fn, dis, attr) => { const b = h('button', Object.assign({ type: 'button', class: 'btn-pill btn-ghost bi-small', disabled: !!dis }, attr || {}), label); b.addEventListener('click', fn); return b; };
      acts.appendChild(mk('Move earlier', () => D.change('Move the page', () => { m.pages.splice(ix - 1, 0, m.pages.splice(ix, 1)[0]); }), ix === 0));
      acts.appendChild(mk('Move later', () => D.change('Move the page', () => { m.pages.splice(ix + 1, 0, m.pages.splice(ix, 1)[0]); }), ix === m.pages.length - 1));
      acts.appendChild(mk('Delete the page', () => { if (window.confirm('Delete the page "' + p.title + '" and its visuals?')) { D.change('Delete the page', () => { m.pages.splice(ix, 1); }); D.setPage(m.pages[0].id); } }, m.pages.length < 2, { 'data-delete-page': '' }));
      fmt.appendChild(acts);
      const dt = (p.drillthrough || {}).fields || [];
      const fieldSel = h('select', { class: 'studio-select', 'aria-label': 'Take this field as a drill-through filter', 'data-page-drill': '' }, h('option', { value: '', text: 'Add a drill-through field…' }), optionsFor('field'));
      fieldSel.addEventListener('change', () => { if (fieldSel.value) { D.change('Drill-through page', () => { p.drillthrough = { fields: dt.concat([fieldSel.value]).filter((x, i, a) => a.indexOf(x) === i) }; }); } });
      const ul = h('ul', { class: 'bi-chiplist' });
      dt.forEach((x) => { const li = h('li', { class: 'bi-wchip' }, h('span', { text: x })); const b = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove ' + x }, B.icon('x-lg')); b.addEventListener('click', () => D.change('Drill-through page', () => { const l = dt.filter((y) => y !== x); if (l.length) { p.drillthrough = { fields: l }; } else { delete p.drillthrough; } })); li.appendChild(b); ul.appendChild(li); });
      fmt.appendChild(h('fieldset', { class: 'bi-fieldset' }, h('legend', { text: 'This page is a drill-through target for' }), ul, fieldSel));
      fmt.appendChild(h('h4', { class: 'bi-fhead', text: 'Report' }));
      fmt.appendChild(frow('Report title', textInput(m.title, (x) => D.change('Rename the report', () => { m.title = x.trim() || m.title; }), { 'data-report-title': '' })));
      fmt.appendChild(frow('Description', textInput(m.description, (x) => D.change('Describe the report', () => { if (x.trim()) { m.description = x.trim(); } else { delete m.description; } }))));
      fmt.appendChild(frow('Colours', selectInput(B.PALETTES.map((n) => [n, n]), (m.theme || {}).palette || 'default', (x) => D.change('Theme', () => { m.theme = Object.assign({}, m.theme, { palette: x }); }), { 'data-theme-palette': '' }), 'default follows the console theme; colour-blind is Okabe-Ito.'));
      const dates = m.dates || {};
      fmt.appendChild(frow('Business date from', textInput(dates.from, (x) => setDates({ from: x }), { type: 'date' })));
      fmt.appendChild(frow('Business date to', textInput(dates.to, (x) => setDates({ to: x }), { type: 'date' })));
      fmt.appendChild(frow('Dataset', h('code', { text: m.dataset })));
      const bm = h('fieldset', { class: 'bi-fieldset' }, h('legend', { text: 'Bookmarks' }));
      (m.bookmarks || []).forEach((b, i) => {
        const li = h('div', { class: 'bi-cond' }, h('span', { text: b.name + ' → ' + b.page }));
        const x = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove bookmark ' + b.name }, B.icon('x-lg'));
        x.addEventListener('click', () => D.change('Remove a bookmark', () => { m.bookmarks.splice(i, 1); if (!m.bookmarks.length) { delete m.bookmarks; } }));
        li.appendChild(x);
        bm.appendChild(li);
      });
      const name = h('input', { type: 'text', class: 'studio-in', placeholder: 'Bookmark name', 'aria-label': 'Bookmark name', 'data-bookmark-name': '' });
      const addB = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', text: 'Bookmark this page', 'data-add-bookmark': '' });
      addB.addEventListener('click', () => { if (name.value.trim()) { D.change('Add a bookmark', () => { (m.bookmarks = m.bookmarks || []).push({ name: name.value.trim(), page: p.id }); }); } });
      bm.appendChild(name);
      bm.appendChild(addB);
      bm.appendChild(h('small', { class: 'text-muted-d', text: 'Slicer choices in a bookmark are set in the YAML tab.' }));
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

    function filterEditor(scope, index) {
      const cur = index >= 0 ? scopeFilters(scope, false)[index] : null;
      const form = h('form', { class: 'bi-feditor', 'data-filter-editor': '', autocomplete: 'off' });
      const field = h('select', { class: 'studio-select', 'aria-label': 'Field to filter', name: 'field' }, optionsFor('any'));
      field.value = cur ? cur.field : (D.dataset.tables[0] ? D.dataset.tables[0].name + '.' + D.dataset.tables[0].columns[0].name : '');
      const mode = h('select', { class: 'studio-select', 'aria-label': 'Kind of filter', name: 'mode' }, h('option', { value: 'basic', text: 'Basic: pick values' }), h('option', { value: 'advanced', text: 'Advanced: a condition' }), h('option', { value: 'top', text: 'Top N' }));
      mode.value = !cur ? 'basic' : cur.op === 'top' ? 'top' : (cur.op === 'in' || cur.op === 'not-in') ? 'basic' : 'advanced';
      const body = h('div', { class: 'bi-fbody' });
      form.appendChild(h('label', { class: 'bi-frow' }, h('span', { text: 'Field' }), field));
      form.appendChild(h('label', { class: 'bi-frow' }, h('span', { text: 'Filter type' }), mode));
      form.appendChild(body);
      const state = { op: cur ? cur.op : 'in', values: cur ? (cur.values || []).slice() : [], n: cur ? cur.n || 5 : 5, by: cur ? cur.by : '', dir: cur ? cur.dir || 'desc' : 'desc' };

      function paint() {
        B.clear(body);
        if (mode.value === 'basic') {
          const op = selectInput([['in', 'is one of'], ['not-in', 'is not one of']], state.op === 'not-in' ? 'not-in' : 'in', (x) => { state.op = x; }, { name: 'op' });
          const txt = h('input', { type: 'text', class: 'studio-in', name: 'values', 'aria-label': 'Values, separated by commas', placeholder: 'Values, separated by commas', value: state.values.join(', ') });
          txt.addEventListener('input', () => { state.values = txt.value.split(',').map((s) => s.trim()).filter(Boolean); });
          const load = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-load-values': '', text: 'Pick from the data' });
          const pick = h('div', { class: 'bi-pick', 'data-pick': '' });
          load.addEventListener('click', () => loadValues(field.value, pick, state, txt));
          body.appendChild(h('label', { class: 'bi-frow' }, h('span', { text: 'Condition' }), op));
          body.appendChild(txt);
          body.appendChild(load);
          body.appendChild(pick);
        } else if (mode.value === 'advanced') {
          const op = selectInput(Object.keys(OPS).map((k) => [k, OPS[k]]), Object.keys(OPS).indexOf(state.op) >= 0 ? state.op : 'eq', (x) => { state.op = x; paint(); }, { name: 'op' });
          body.appendChild(h('label', { class: 'bi-frow' }, h('span', { text: 'Condition' }), op));
          if (op.value !== 'is-null' && op.value !== 'not-null') {
            const a = h('input', { type: 'text', class: 'studio-in', name: 'v1', 'aria-label': 'Value', value: state.values[0] == null ? '' : String(state.values[0]) });
            a.addEventListener('input', () => { state.values[0] = a.value; });
            body.appendChild(a);
            if (op.value === 'between') {
              const b = h('input', { type: 'text', class: 'studio-in', name: 'v2', 'aria-label': 'and', value: state.values[1] == null ? '' : String(state.values[1]) });
              b.addEventListener('input', () => { state.values[1] = b.value; });
              body.appendChild(b);
            }
          }
        } else {
          const n = h('input', { type: 'number', min: '1', class: 'studio-in', name: 'n', 'aria-label': 'How many', value: String(state.n) });
          n.addEventListener('input', () => { state.n = parseInt(n.value, 10) || 1; });
          const by = selectInput((D.dataset.measures || []).map((m) => [m.name, m.name]), state.by || (D.dataset.measures[0] || {}).name, (x) => { state.by = x; }, { name: 'by' });
          state.by = by.value;
          const dir = selectInput([['desc', 'highest'], ['asc', 'lowest']], state.dir, (x) => { state.dir = x; }, { name: 'dir' });
          body.appendChild(h('label', { class: 'bi-frow' }, h('span', { text: 'Keep the top' }), n));
          body.appendChild(h('label', { class: 'bi-frow' }, h('span', { text: 'with the' }), dir));
          body.appendChild(h('label', { class: 'bi-frow' }, h('span', { text: 'by measure' }), by));
        }
      }
      mode.addEventListener('change', () => { state.values = []; state.op = mode.value === 'top' ? 'top' : mode.value === 'basic' ? 'in' : 'eq'; paint(); });
      field.addEventListener('change', () => { state.values = []; paint(); });
      paint();
      const ok = h('button', { type: 'submit', class: 'btn-pill btn-accent bi-small', text: cur ? 'Update the filter' : 'Add the filter' });
      const cancel = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', text: 'Cancel' });
      cancel.addEventListener('click', () => { editing = null; drawFilters(); });
      form.appendChild(h('div', { class: 'bi-actions' }, ok, cancel));
      form.addEventListener('submit', (e) => {
        e.preventDefault();
        const f = { field: field.value, op: state.op };
        const type = (B.column(D.idx, field.value) || {}).type;
        const num = (x) => (['integer', 'decimal'].indexOf(type) >= 0 && x !== '' && isFinite(Number(x)) ? Number(x) : x);
        if (mode.value === 'top') { f.op = 'top'; f.n = state.n; f.by = state.by; f.dir = state.dir; }
        else if (f.op === 'is-null' || f.op === 'not-null') { f.values = []; }
        else { f.values = state.values.filter((x) => x !== '' && x != null).map(num); if (!f.values.length) { D.say('Give the filter a value.', true); return; } }
        if (f.op === 'between' && f.values.length < 2) { D.say('between needs two values.', true); return; }
        D.change(cur ? 'Change a filter' : 'Add a filter', () => { const l = scopeFilters(scope, true); if (cur) { l[index] = f; } else { l.push(f); } });
        editing = null;
        drawFilters();
      });
      return form;
    }

    /** Distinct values of the field, from the dataset itself (as the author: the author's rows and masks apply). */
    function loadValues(field, box, state, txt) {
      B.clear(box).appendChild(h('p', { class: 'bi-vis-note', text: 'Reading values…' }));
      B.enqueue(() => B.api('POST', '/datasets/' + B.id2path(D.dataset.id) + '/query', { fields: [field], limit: 200 })).then((res) => {
        B.clear(box);
        const search = h('input', { type: 'search', class: 'studio-in', placeholder: 'Search', 'aria-label': 'Search the values' });
        const list = h('div', { class: 'bi-picklist', role: 'group', 'aria-label': 'Values' });
        res.rows.forEach((r) => {
          const val = r[0];
          if (val == null) { return; }
          const c = h('input', { type: 'checkbox', value: String(val), 'data-pick-value': String(val) });
          c.checked = state.values.map(String).indexOf(String(val)) >= 0;
          c.addEventListener('change', () => {
            state.values = state.values.filter((x) => String(x) !== String(val));
            if (c.checked) { state.values.push(val); }
            txt.value = state.values.join(', ');
          });
          list.appendChild(h('label', { class: 'bi-checkrow' }, c, ' ' + String(val)));
        });
        search.addEventListener('input', () => { const t = search.value.toLowerCase(); B.qa('label', list).forEach((l) => { l.hidden = !!t && l.textContent.toLowerCase().indexOf(t) < 0; }); });
        box.appendChild(search);
        box.appendChild(list);
        if (res.rowCount >= 200) { box.appendChild(h('p', { class: 'bi-vis-note', text: 'The first 200 values; type the others above.' })); }
      }, (e) => { B.clear(box).appendChild(h('p', { class: 'bi-vis-note', text: e.message })); });
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
      drawBuild();
      drawFormat();
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

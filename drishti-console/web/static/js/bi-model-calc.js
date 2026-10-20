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
/* Wave B, the model designer's calculations (docs/architecture/BI_DESIGN.md sections 3.3.5 and 12.2, console task 3; docs/guides/EXPRESSIONS.md, the filter
   context and balance chapters), on the designer's object (window.drishtiModel, bi-model-designer.js), with no YAML typed:

     Quick calculation   (Measures tab) a measure from a measure and a calculation picked from a list: % of total, % of a field's total, ignoring a slicer,
                         through another relationship, year to date, prior period, running total, closing, opening and average balances, yesterday, a what-if
                         shock; the formula is written for you (DEL), shown, and checked by the server like any other.
     Calculations tab    calculation groups (items over SELECTED, precedence), parameters (what-if numbers, integers, text, dates, booleans, and field
                         parameters), the business calendar (holidays, fiscal year start) and FX conversion (the rates dataset and its columns).

   What is offered comes from the server's function catalogue (only what is built); everything is built with DOM calls and textContent. */
(function () {
  'use strict';
  const M = window.drishtiModel, B = window.drishtiBi;
  if (!M || !B) { return; }
  const h = M.h, q = M.q;
  const near = (el, t, bad) => B.near(el, t, bad);
  const sel = (options, value, attrs) => h('select', Object.assign({ class: 'studio-select' }, attrs || {}), options.map((o) => { const [v, t] = Array.isArray(o) ? o : [o, o]; return h('option', { value: v, text: t, selected: String(value == null ? '' : value) === String(v) }); }));
  const lit = (s) => '\'' + String(s).replace(/\\/g, '\\\\').replace(/'/g, '\\\'') + '\'';
  const mref = (n) => 'measure(' + lit(n) + ')';
  const HOLIDAYS = ['WEEKENDS', 'USNY', 'GBLO', 'USNY+GBLO', 'TARGET'];
  const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const KINDS = [['number', 'number (what-if)'], ['integer', 'whole number'], ['text', 'text'], ['date', 'date'], ['boolean', 'yes or no'], ['field', 'field (switch a breakdown)']];

  let built = null;                     // the names of the functions the server has built (its catalogue)
  (B.exprEditor && B.exprEditor.functions ? B.exprEditor.functions() : Promise.resolve([])).then((fs) => {
    built = new Set(fs.map((f) => f.name));
    if (M.tab === 'measures' || M.tab === 'calc') { M.emit('redraw'); }
  });
  const has = (fn) => !built || built.has(fn);

  /** The calculations offered, each a DEL template over the base (a measure reference, or SELECTED in a calculation group's item). `needs`: what else to pick. */
  const QUICK = [
    { id: 'share-all', fn: 'share_of', label: '% of total', suffix: '% of total', format: 'share', make: (b) => 'share_of(' + b + ', over=ALL)' },
    { id: 'share-field', fn: 'share_of', label: '% of the total of a field', needs: 'field', suffix: '% of', format: 'share', make: (b, x) => 'share_of(' + b + ', over=[' + x + '])' },
    { id: 'ignore', fn: 'calc', label: 'Ignoring a field\'s filter (a slicer)', needs: 'field', suffix: 'ignoring', make: (b, x) => 'calc(' + b + ', ignore=[' + x + '])' },
    { id: 'ignore-all', fn: 'calc', label: 'Ignoring every filter', suffix: 'all you may see', make: (b) => 'calc(' + b + ', ignore=ALL)' },
    { id: 'via', fn: 'calc', label: 'Through another relationship', needs: 'rel', suffix: 'via', make: (b, x) => 'calc(' + b + ', via=' + lit(x) + ')' },
    { id: 'ytd', fn: 'ytd', label: 'Year to date', needs: 'date', suffix: 'YTD', make: (b, x) => 'ytd(' + b + ', ' + x + ')' },
    { id: 'fytd', fn: 'fiscal_ytd', label: 'Fiscal year to date (the model\'s calendar)', needs: 'date', suffix: 'fiscal YTD', make: (b, x) => 'fiscal_ytd(' + b + ', ' + x + ')' },
    { id: 'mtd', fn: 'mtd', label: 'Month to date', needs: 'date', suffix: 'MTD', make: (b, x) => 'mtd(' + b + ', ' + x + ')' },
    { id: 'prior', fn: 'prior_period', label: 'Prior period', needs: 'date', suffix: 'prior period', make: (b, x) => 'prior_period(' + b + ', ' + x + ')' },
    { id: 'running', fn: 'running', label: 'Running total', needs: 'date', suffix: 'running', make: (b, x) => 'running(' + b + ', ' + x + ')' },
    { id: 'closing', fn: 'closing', label: 'Closing balance (last business day of the month)', needs: 'time', suffix: 'closing', make: (b, x) => 'closing(' + b + ', ' + x + ')' },
    { id: 'opening', fn: 'opening', label: 'Opening balance', needs: 'time', suffix: 'opening', make: (b, x) => 'opening(' + b + ', ' + x + ')' },
    { id: 'avg-over', fn: 'avg_over', label: 'Average balance over the dates', needs: 'time', suffix: 'average balance', make: (b, x) => 'avg_over(' + b + ', ' + x + ')' },
    { id: 'last', fn: 'last', label: 'Last value of each group', needs: 'date', suffix: 'last', make: (b, x) => 'last(' + b + ', ' + x + ')' },
    { id: 'yesterday', fn: 'as_of', label: 'As of the business day before', suffix: 'yesterday', make: (b) => 'as_of(' + b + ', shift_date(asof(), -1))' },
    { id: 'whatif', fn: null, label: 'What-if: shocked by a parameter', needs: 'param', suffix: 'shocked', make: (b, x) => b + ' * (1 + param.' + x + ')' }
  ];
  M.quickTemplates = QUICK;

  const fieldNames = (filter) => M.exprFields().filter((f) => !filter || filter(f)).map((f) => f.name);
  const isDate = (f) => /^(date|timestamp)/i.test(String(f.type || ''));
  const relOptions = () => (M.doc.relationships || []).map((r, i) => [r.id || '#' + i, r.from + ' → ' + r.to + (r.active === false ? ' (inactive)' : '')]);
  let baseAlias = null;                                                       // the dataset the picked base measure reads (its time column comes first)
  const paramOptions = () => Object.keys(M.doc.parameters || {}).filter((n) => ['number', 'integer'].indexOf((M.doc.parameters[n] || {}).kind || 'number') >= 0);
  function needOptions(need) {
    if (need === 'field') { return fieldNames((f) => !isDate(f)).map((n) => [n, n]); }
    if (need === 'date') { return fieldNames(isDate).map((n) => [n, n]); }
    if (need === 'time') {                                                  // a balance is taken along the data's own time: each dataset's business date first
      const aliases = Object.keys(M.doc.datasets || {});
      const first = baseAlias && aliases.indexOf(baseAlias) >= 0 ? [baseAlias].concat(aliases.filter((a) => a !== baseAlias)) : aliases;
      return first.map((a) => [a + '.businessDate', a + '.businessDate (the business date of ' + a + ')']).concat(fieldNames(isDate).map((n) => [n, n]));
    }
    if (need === 'rel') { return relOptions(); }
    if (need === 'param') { return paramOptions().map((n) => [n, 'param.' + n]); }
    return [];
  }
  /** A relationship picked by position (#i) is given an id, so a formula can name it; answers the id. */
  function relId(doc, v) {
    if (String(v).indexOf('#') !== 0) { return v; }
    const r = doc.relationships[Number(String(v).slice(1))];
    const taken = new Set(doc.relationships.map((x) => x.id).filter(Boolean));
    let n = doc.relationships.length, id = 'r_' + n;
    while (taken.has(id)) { id = 'r_' + (++n); }
    r.id = id;
    return id;
  }

  /** The Quick calculation section of the Measures tab: base measure, calculation, what it needs, a name; Add writes the measure. */
  M.quickMeasure = function () {
    const measures = M.exprMeasures().map((m) => m.name);
    const offered = QUICK.filter((x) => (!x.fn || has(x.fn)) && (!x.needs || needOptions(x.needs).length));
    const base = sel(measures.map((n) => [n, n]), measures[0] || '', { 'data-qm-base': '', 'aria-label': 'Measure', disabled: M.readOnly || !measures.length });
    const calc = sel(offered.map((x) => [x.id, x.label]), offered[0] ? offered[0].id : '', { 'data-qm-calc': '', 'aria-label': 'Calculation', disabled: M.readOnly });
    const what = h('select', { class: 'studio-select', 'data-qm-what': '', 'aria-label': 'Of', disabled: M.readOnly });
    const whatLabel = h('label', { 'data-qm-what-label': '' }, h('span', { 'data-qm-what-word': '', text: 'Of ' }), what);
    const name = h('input', { class: 'studio-in', 'data-qm-name': '', 'aria-label': 'Name of the new measure', disabled: M.readOnly });
    const shown = h('code', { class: 'bi-qm-expr', 'data-qm-expr': '' });
    const add = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-qm-add': '', text: 'Add measure', disabled: M.readOnly || !measures.length || !offered.length });
    let touched = false;
    const cur = () => offered.find((x) => x.id === calc.value);
    const WORD = { field: 'Field ', date: 'Date field ', rel: 'Relationship ', param: 'Parameter ' };
    function refresh(keepWhat) {
      const t = cur();
      if (!t) { return; }
      const bm = (M.doc.measures || {})[base.value];
      const bx = bm && (typeof bm === 'string' ? bm : bm.expr) || '';
      baseAlias = (Object.keys(M.doc.datasets || {}).find((a) => new RegExp('\\b' + a + '\\.').test(bx))) || null;      if (!keepWhat) {
        B.clear(what);
        needOptions(t.needs).forEach(([v, l]) => what.appendChild(h('option', { value: v, text: l })));
      }
      whatLabel.hidden = !t.needs;
      q('[data-qm-what-word]', whatLabel).textContent = WORD[t.needs] || '';
      const x = t.needs === 'rel' ? (what.value.indexOf('#') === 0 ? 'r_?' : what.value) : what.value;
      shown.textContent = t.make(mref(base.value), x);
      if (!touched) { name.value = base.value + ' ' + t.suffix + (t.needs === 'field' ? ' ' + String(what.value).split('.').pop() : ''); }
    }
    base.addEventListener('change', () => refresh(!(cur() && cur().needs === 'time')));
    calc.addEventListener('change', () => refresh(false));
    what.addEventListener('change', () => refresh(true));
    name.addEventListener('input', () => { touched = true; });
    add.addEventListener('click', () => {
      const t = cur(), n = name.value.trim();
      if (!t || !n) { near(add, 'Pick a measure and a calculation, and give the new measure a name.', true); return; }
      if ((M.doc.measures || {})[n]) { near(add, 'There is already a measure called ' + n + '.', true); return; }
      if (t.needs && !what.value) { near(add, 'Pick what the calculation needs first.', true); return; }
      name.value = '';
      M.edit((doc) => {
        const x = t.needs === 'rel' ? relId(doc, what.value) : what.value;
        doc.measures = doc.measures || {};
        doc.measures[n] = { expr: t.make(mref(base.value), x) };
        if (t.format) { doc.measures[n].format = t.format; }
      });
      M.compose(true).then(() => { const p = M.problems('measures.' + n); near(add, p.length ? p[0].code + ' ' + p[0].message : 'Added ' + n + ': ' + M.doc.measures[n].expr + '. It checks clean.', p.some((x) => (x.level || 'error') === 'error')); });
      touched = false;
    });
    refresh(false);
    return h('section', { class: 'bi-sec bi-form bi-md-quick', 'aria-label': 'Quick calculation', 'data-keep': '', 'data-quick-measure': '' }, h('h3', { text: 'Quick calculation' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'A new measure from one you have: a share of a total, a filter ignored, a balance, a period to date. The formula is written for you; edit it below afterwards if you like. Security is never ignored: a total is of the rows you may see.' }),
      h('div', { class: 'bi-md-row' }, h('label', {}, 'Measure ', base), h('label', {}, 'Calculation ', calc), whatLabel, h('label', {}, 'Name ', name), add),
      h('p', { class: 'bi-small-note' }, h('small', { class: 'text-muted-d', text: 'Formula: ' }), shown));
  };

  // ---- the Calculations tab ------------------------------------------------------------------------------------------------
  function drawGroups(pane) {
    const groups = M.doc['calc-groups'] || {};
    const sec = h('section', { class: 'bi-sec bi-form', 'aria-label': 'Calculation groups', 'data-calc-groups': '' }, h('h3', { text: 'Calculation groups' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'A group applies one of its items to whichever measure a visual shows: Current, YTD, Prior period. An item is a formula with SELECTED where the measure goes. A report picks the item (the Show as control); the measure keeps its name.' }));
    Object.keys(groups).forEach((g) => {
      const grp = groups[g] || {};
      const items = grp.items || {};
      const probs = M.problems('calc-groups.' + g);
      const prec = h('input', { class: 'studio-in bi-num', type: 'number', step: '1', value: grp.precedence == null ? '' : grp.precedence, 'aria-label': 'Precedence of ' + g, 'data-cg-precedence': g, disabled: M.readOnly });
      prec.addEventListener('change', () => M.edit((doc) => { const x = doc['calc-groups'][g]; if (prec.value === '') { delete x.precedence; } else { x.precedence = Number(prec.value); } }, true));
      const desc = h('input', { class: 'studio-in', value: grp.description || '', placeholder: 'What the group is for', 'aria-label': 'Description of ' + g, 'data-cg-description': g, disabled: M.readOnly });
      desc.addEventListener('change', () => M.edit((doc) => { const x = doc['calc-groups'][g]; if (desc.value.trim()) { x.description = desc.value.trim(); } else { delete x.description; } }, true));
      const rmG = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Delete the group ' + g, 'data-cg-delete': g, disabled: M.readOnly }, B.icon('trash'));
      rmG.addEventListener('click', () => M.edit((doc) => { delete doc['calc-groups'][g]; if (!Object.keys(doc['calc-groups']).length) { delete doc['calc-groups']; } }));
      const tb = h('tbody');
      Object.keys(items).forEach((it) => {
        const text = typeof items[it] === 'string' ? items[it] : (items[it] || {}).expr || '';
        const ex = h('input', { class: 'studio-in mono', value: text, 'aria-label': 'Formula of ' + g + ' ' + it, 'data-cg-item': g + '/' + it, disabled: M.readOnly });
        const cell = h('td', {}, ex);
        if (!M.readOnly) { M.exprEditor(ex, (v) => { if (v && v !== text) { M.edit((doc) => { const its = doc['calc-groups'][g].items; if (its[it] && typeof its[it] === 'object') { its[it].expr = v; } else { its[it] = v; } }, true); } }, { names: () => M.exprNames(true) }); }
        const ip = M.problems('calc-groups.' + g + '.items.' + it);
        if (ip.length) { cell.appendChild(h('p', { class: 'bi-near bi-bad', text: ip[0].code + ' ' + ip[0].message })); }
        const rm = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Delete the item ' + it, 'data-cg-item-delete': g + '/' + it, disabled: M.readOnly }, B.icon('trash'));
        rm.addEventListener('click', () => M.edit((doc) => { delete doc['calc-groups'][g].items[it]; }));
        tb.appendChild(h('tr', { 'data-cg-row': g + '/' + it }, h('th', { scope: 'row', text: it }), cell, h('td', {}, rm)));
      });
      // a new item: a name and a template (or write it)
      const offered = QUICK.filter((x) => x.id !== 'via' && (!x.fn || has(x.fn)) && (!x.needs || needOptions(x.needs).length));
      const iName = h('input', { class: 'studio-in', placeholder: 'YTD', 'aria-label': 'Name of the new item of ' + g, 'data-cg-new-name': g, disabled: M.readOnly });
      const iTpl = sel([['', 'Current (the measure as it is)']].concat(offered.map((x) => [x.id, x.label])), '', { 'aria-label': 'What the new item of ' + g + ' does', 'data-cg-new-template': g, disabled: M.readOnly });
      const iWhat = h('select', { class: 'studio-select', 'aria-label': 'For the new item of ' + g, 'data-cg-new-what': g, hidden: true, disabled: M.readOnly });
      iTpl.addEventListener('change', () => {
        const t = QUICK.find((x) => x.id === iTpl.value);
        B.clear(iWhat);
        iWhat.hidden = !(t && t.needs);
        if (t && t.needs) { needOptions(t.needs).forEach(([v, l]) => iWhat.appendChild(h('option', { value: v, text: l }))); }
        if (!iName.value.trim() || iName.getAttribute('data-auto')) { iName.value = t ? t.suffix.replace(/^%/, 'Share') : 'Current'; iName.setAttribute('data-auto', '1'); }
      });
      iName.addEventListener('input', () => iName.removeAttribute('data-auto'));
      const iAdd = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-cg-add-item': g, text: 'Add item', disabled: M.readOnly });
      iAdd.addEventListener('click', () => {
        const n = iName.value.trim();
        if (!n) { near(iAdd, 'Give the item a name.', true); return; }
        if (items[n] != null) { near(iAdd, 'The group has an item called ' + n + ' already.', true); return; }
        const t = QUICK.find((x) => x.id === iTpl.value);
        const what = iWhat.value;
        iName.value = ''; iName.removeAttribute('data-auto'); iTpl.value = '';               // the form is empty again for the next item
        // a template with a format of its own (a share reads as a percent) keeps it: { expr, format }
        M.edit((doc) => { const x = doc['calc-groups'][g]; x.items = x.items || {}; x.items[n] = t ? (t.format ? { expr: t.make('SELECTED', what), format: t.format } : t.make('SELECTED', what)) : 'SELECTED'; });
      });
      sec.appendChild(h('fieldset', { class: 'bi-cg', 'data-cg': g }, h('legend', {}, h('strong', { text: g }), ' ', rmG),
        h('div', { class: 'bi-md-row' }, h('label', {}, 'Precedence ', prec), h('label', { class: 'bi-md-grow' }, 'Description ', desc)),
        probs.length && probs[0].path === 'calc-groups.' + g ? h('p', { class: 'bi-near bi-bad', text: probs[0].code + ' ' + probs[0].message }) : null,
        h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl bi-tbl', 'data-plain': '' }, h('caption', { class: 'visually-hidden', text: 'Items of ' + g }),
          h('thead', {}, h('tr', {}, ['Item', 'Formula (SELECTED is the measure)', ''].map((t) => h('th', { scope: 'col', text: t })))), tb)),
        h('div', { class: 'bi-md-row', 'data-keep': '' }, h('label', {}, 'New item ', iName), h('label', {}, 'Does ', iTpl), iWhat, iAdd)));
    });
    const gName = h('input', { class: 'studio-in', placeholder: 'Time', 'aria-label': 'Name of the new calculation group', 'data-cg-new': '', disabled: M.readOnly });
    const gAdd = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-cg-add': '', text: 'Add group', disabled: M.readOnly });
    gAdd.addEventListener('click', () => {
      const n = gName.value.trim();
      if (!/^[A-Za-z][A-Za-z0-9 _-]{0,40}$/.test(n)) { near(gAdd, 'A group\'s name is a word or two: letters, digits, spaces.', true); return; }
      if (groups[n]) { near(gAdd, 'There is a group called ' + n + ' already.', true); return; }
      gName.value = '';
      M.edit((doc) => { doc['calc-groups'] = doc['calc-groups'] || {}; doc['calc-groups'][n] = { precedence: 10, items: { Current: 'SELECTED' } }; });
    });
    sec.appendChild(h('div', { class: 'bi-md-row', 'data-keep': '' }, h('label', {}, 'New group ', gName), gAdd));
    pane.appendChild(sec);
  }

  function drawParameters(pane) {
    const params = M.doc.parameters || {};
    const sec = h('section', { class: 'bi-sec bi-form', 'aria-label': 'Parameters', 'data-parameters': '' }, h('h3', { text: 'Parameters' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'A value a reader sets with a parameter slicer on a report: a what-if shock read in a formula as param.<name>, or a field parameter that switches the field a visual breaks down by (param.<name> in a well). Each has a default the queries use until someone changes it.' }));
    const fields = fieldNames();
    Object.keys(params).forEach((n) => {
      const p = params[n] || {};
      const kind = p.kind || 'number';
      const set = (key, v) => M.edit((doc) => { const x = doc.parameters[n]; if (v === '' || v == null || (Array.isArray(v) && !v.length)) { delete x[key]; } else { x[key] = v; } }, true);
      const num = (v) => (v === '' ? '' : kind === 'integer' ? Math.round(Number(v)) : Number(v));
      const kSel = sel(KINDS, kind, { 'aria-label': 'Kind of ' + n, 'data-p-kind': n, disabled: M.readOnly });
      kSel.addEventListener('change', () => M.edit((doc) => { const x = doc.parameters[n]; x.kind = kSel.value; delete x.min; delete x.max; delete x.step; delete x.values; delete x.fields; x.default = kSel.value === 'boolean' ? false : kSel.value === 'text' ? '' : kSel.value === 'date' ? new Date().toISOString().slice(0, 10) : kSel.value === 'field' ? (fields[0] || '') : 0; if (kSel.value === 'field') { x.fields = fields.slice(0, 2); } }));
      const title = h('input', { class: 'studio-in', value: p.title || '', placeholder: n, 'aria-label': 'Title of ' + n, 'data-p-title': n, disabled: M.readOnly });
      title.addEventListener('change', () => set('title', title.value.trim()));
      const row = h('div', { class: 'bi-md-row' }, h('label', {}, 'Kind ', kSel), h('label', {}, 'Title ', title));
      if (kind === 'field') {
        const chosen = new Set(p.fields || []);
        // a field parameter switches a field (a breakdown) or, since 2.6.0, a measure (what a values well shows): its choices are the model's fields and measures
        const measures = Object.keys(M.doc.measures || {});
        const choices = fields.concat(measures);
        const box = h('div', { class: 'bi-p-fields', role: 'group', 'aria-label': 'Fields or measures ' + n + ' can be', 'data-p-fields': n }, choices.map((f) => {
          const cb = h('input', { type: 'checkbox', value: f, checked: chosen.has(f), disabled: M.readOnly });
          cb.addEventListener('change', () => M.edit((doc) => { const x = doc.parameters[n]; const s = new Set(x.fields || []); if (cb.checked) { s.add(f); } else { s.delete(f); } x.fields = choices.filter((y) => s.has(y)); if (x.fields.indexOf(x.default) < 0) { x.default = x.fields[0]; } }, true));
          return h('label', { class: 'bi-check', 'data-p-choice': measures.indexOf(f) >= 0 ? 'measure' : 'field' }, cb, ' ' + f + (measures.indexOf(f) >= 0 ? ' (measure)' : ''));
        }));
        const dflt = sel((p.fields || []).map((f) => [f, f]), p.default || '', { 'aria-label': 'Default of ' + n, 'data-p-default': n, disabled: M.readOnly });
        dflt.addEventListener('change', () => set('default', dflt.value));
        row.appendChild(h('label', {}, 'Default ', dflt));
        sec.appendChild(h('fieldset', { class: 'bi-param', 'data-param': n }, h('legend', {}, h('code', { text: 'param.' + n }), ' ', rmBtn(n)), row, box));
        return;
      }
      if (kind === 'boolean') {
        const dflt = sel([['false', 'no'], ['true', 'yes']], String(!!p.default), { 'aria-label': 'Default of ' + n, 'data-p-default': n, disabled: M.readOnly });
        dflt.addEventListener('change', () => set('default', dflt.value === 'true'));
        row.appendChild(h('label', {}, 'Default ', dflt));
      } else if (kind === 'number' || kind === 'integer') {
        ['default', 'min', 'max', 'step'].forEach((k) => {
          const inp = h('input', { class: 'studio-in bi-num', type: 'number', step: 'any', value: p[k] == null ? '' : p[k], 'aria-label': k + ' of ' + n, ['data-p-' + k]: n, disabled: M.readOnly });
          inp.addEventListener('change', () => set(k, k === 'default' && inp.value === '' ? 0 : num(inp.value)));
          row.appendChild(h('label', {}, k[0].toUpperCase() + k.slice(1) + ' ', inp));
        });
      } else {
        const dflt = h('input', { class: 'studio-in', type: kind === 'date' ? 'date' : 'text', value: p.default == null ? '' : p.default, 'aria-label': 'Default of ' + n, 'data-p-default': n, disabled: M.readOnly });
        dflt.addEventListener('change', () => set('default', dflt.value));
        row.appendChild(h('label', {}, 'Default ', dflt));
        if (kind === 'text') {
          const vals = h('input', { class: 'studio-in', value: (p.values || []).join(', '), placeholder: 'LOW, MID, HIGH', 'aria-label': 'Values ' + n + ' may take', 'data-p-values': n, disabled: M.readOnly });
          vals.addEventListener('change', () => set('values', vals.value.split(',').map((s) => s.trim()).filter(Boolean)));
          row.appendChild(h('label', {}, 'Values ', vals));
        }
      }
      const pp = M.problems('parameters.' + n);
      sec.appendChild(h('fieldset', { class: 'bi-param', 'data-param': n }, h('legend', {}, h('code', { text: 'param.' + n }), ' ', rmBtn(n)), row,
        pp.length ? h('p', { class: 'bi-near bi-bad', text: pp[0].code + ' ' + pp[0].message }) : null));
    });
    function rmBtn(n) {
      const b = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Delete the parameter ' + n, 'data-p-delete': n, disabled: M.readOnly }, B.icon('trash'));
      b.addEventListener('click', () => M.edit((doc) => { delete doc.parameters[n]; if (!Object.keys(doc.parameters).length) { delete doc.parameters; } }));
      return b;
    }
    const pName = h('input', { class: 'studio-in', placeholder: 'shock', 'aria-label': 'Name of the new parameter', 'data-p-new': '', disabled: M.readOnly });
    const pKind = sel(KINDS, 'number', { 'aria-label': 'Kind of the new parameter', 'data-p-new-kind': '', disabled: M.readOnly });
    const pAdd = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-p-add': '', text: 'Add parameter', disabled: M.readOnly });
    pAdd.addEventListener('click', () => {
      const n = pName.value.trim();
      if (!/^[A-Za-z_][A-Za-z0-9_]{0,40}$/.test(n)) { near(pAdd, 'A parameter\'s name is one word (letters, digits, underscore): it is written param.' + (n || 'name') + '.', true); return; }
      if (params[n]) { near(pAdd, 'There is a parameter called ' + n + ' already.', true); return; }
      const k = pKind.value;
      pName.value = '';
      M.edit((doc) => {
        doc.parameters = doc.parameters || {};
        doc.parameters[n] = k === 'field' ? { kind: 'field', fields: fields.slice(0, 2), default: fields[0] || '' } : k === 'number' ? { kind: 'number', default: 0, min: -1, max: 1, step: 0.01 }
          : k === 'integer' ? { kind: 'integer', default: 0 } : k === 'boolean' ? { kind: 'boolean', default: false } : k === 'date' ? { kind: 'date', default: new Date().toISOString().slice(0, 10) } : { kind: 'text', default: '' };
      });
    });
    sec.appendChild(h('div', { class: 'bi-md-row', 'data-keep': '' }, h('label', {}, 'New parameter ', pName), h('label', {}, 'Kind ', pKind), pAdd));
    pane.appendChild(sec);
  }

  function drawCalendar(pane) {
    const cal = M.doc.calendars;
    const sec = h('section', { class: 'bi-sec bi-form', 'aria-label': 'Business calendar', 'data-calendar': '' }, h('h3', { text: 'Business calendar' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'The business days the balances and periods use: closing() takes each month\'s last business day, so a holiday on the 31st closes on the 28th; fiscal_ytd() starts the year in the month you pick.' }));
    if (!cal) {
      const add = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-cal-add': '', text: 'Add a calendar', disabled: M.readOnly });
      add.addEventListener('click', () => M.edit((doc) => { doc.calendars = { holidays: 'WEEKENDS', 'fiscal-year-start': 1 }; }));
      sec.append(h('p', { class: 'text-muted-d', text: 'No calendar: Saturdays and Sundays are the only days off, and the fiscal year is the calendar year.' }), add);
      pane.appendChild(sec);
      return;
    }
    const known = HOLIDAYS.indexOf(cal.holidays || 'WEEKENDS') >= 0;
    const hol = sel(HOLIDAYS.map((x) => [x, x === 'WEEKENDS' ? 'weekends only' : x]).concat([['other', 'another calendar (type its name)']]), known ? cal.holidays || 'WEEKENDS' : 'other', { 'aria-label': 'Holidays', 'data-cal-holidays': '', disabled: M.readOnly });
    const other = h('input', { class: 'studio-in', value: known ? '' : cal.holidays || '', placeholder: 'USNY+JPTO', 'aria-label': 'Holiday calendar name', 'data-cal-other': '', hidden: known, disabled: M.readOnly });
    hol.addEventListener('change', () => { other.hidden = hol.value !== 'other'; if (hol.value !== 'other') { M.edit((doc) => { doc.calendars.holidays = hol.value; }, true); } else { other.focus(); } });
    other.addEventListener('change', () => { if (other.value.trim()) { M.edit((doc) => { doc.calendars.holidays = other.value.trim().toUpperCase(); }, true); } });
    const fy = sel(MONTHS.map((m, i) => [String(i + 1), m]), String(cal['fiscal-year-start'] || 1), { 'aria-label': 'The fiscal year starts in', 'data-cal-fy': '', disabled: M.readOnly });
    fy.addEventListener('change', () => M.edit((doc) => { doc.calendars['fiscal-year-start'] = Number(fy.value); }, true));
    const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-cal-remove': '', text: 'Remove the calendar', disabled: M.readOnly });
    rm.addEventListener('click', () => M.edit((doc) => { delete doc.calendars; }));
    const cp = M.problems('calendars');
    sec.append(h('div', { class: 'bi-md-row' }, h('label', {}, 'Holidays ', hol), other, h('label', {}, 'Fiscal year starts in ', fy), rm),
      cp.length ? h('p', { class: 'bi-near bi-bad', text: cp[0].code + ' ' + cp[0].message }) : '');
    pane.appendChild(sec);
  }

  function drawFx(pane) {
    const fx = M.doc.fx;
    const aliases = Object.keys(M.doc.datasets || {});
    const sec = h('section', { class: 'bi-sec bi-form', 'aria-label': 'Currency conversion', 'data-fx': '' }, h('h3', { text: 'Currency conversion' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'The dataset of FX rates convert() and fx() read: the latest rate on or before the date, the stored pair, its inverse or through the base currency.' }));
    if (!fx) {
      const add = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-fx-add': '', text: 'Add FX rates', disabled: M.readOnly || !aliases.length });
      add.addEventListener('click', () => M.edit((doc) => { doc.fx = { rates: aliases.find((a) => /fx|rate/i.test(a)) || aliases[0], from: 'from', to: 'to', rate: 'rate', date: 'businessDate', base: 'USD' }; }));
      sec.append(h('p', { class: 'text-muted-d', text: 'No FX rates: amounts in different currencies are not added up (the currency guard says so).' }), add);
      pane.appendChild(sec);
      return;
    }
    const cols = (M.infos[fx.rates] ? M.infos[fx.rates].columns : []).map((c) => c.name);
    const rates = sel(aliases.map((a) => [a, a]), fx.rates, { 'aria-label': 'Dataset of FX rates', 'data-fx-rates': '', disabled: M.readOnly });
    rates.addEventListener('change', () => M.edit((doc) => { doc.fx.rates = rates.value; }, true));
    const row = h('div', { class: 'bi-md-row' }, h('label', {}, 'Rates ', rates));
    ['from', 'to', 'rate', 'date'].forEach((k) => {
      const s = sel((cols.indexOf(fx[k]) < 0 && fx[k] ? [fx[k]] : []).concat(cols).map((c) => [c, c]), fx[k] || '', { 'aria-label': 'Column ' + k, ['data-fx-' + k]: '', disabled: M.readOnly });
      s.addEventListener('change', () => M.edit((doc) => { doc.fx[k] = s.value; }, true));
      row.appendChild(h('label', {}, k[0].toUpperCase() + k.slice(1) + ' ', s));
    });
    const base = h('input', { class: 'studio-in bi-ccy', value: fx.base || '', maxlength: 3, placeholder: 'USD', 'aria-label': 'Base currency', 'data-fx-base': '', disabled: M.readOnly });
    base.addEventListener('change', () => M.edit((doc) => { if (base.value.trim()) { doc.fx.base = base.value.trim().toUpperCase(); } else { delete doc.fx.base; } }, true));
    const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-fx-remove': '', text: 'Remove', disabled: M.readOnly });
    rm.addEventListener('click', () => M.edit((doc) => { delete doc.fx; }));
    row.append(h('label', {}, 'Base ', base), rm);
    const fp = M.problems('fx');
    sec.append(row, fp.length ? h('p', { class: 'bi-near bi-bad', text: fp[0].code + ' ' + fp[0].message }) : '');
    pane.appendChild(sec);
  }

  function draw() {
    const pane = q('[data-calc-pane]');
    if (!pane || !M.doc) { return; }
    const run = () => { B.clear(pane); drawGroups(pane); drawParameters(pane); drawCalendar(pane); drawFx(pane); };
    if (M.keepForms) { M.keepForms(pane, run); } else { run(); }
    const n = Object.keys(M.doc['calc-groups'] || {}).length + Object.keys(M.doc.parameters || {}).length;
    const badge = q('[data-count-calc]');
    if (badge) { badge.textContent = n || ''; }
  }
  M.on('tab', (t) => { if (t === 'calc') { draw(); } });
  M.on('redraw', () => { if (M.tab === 'calc') { draw(); } });
})();

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
/* The model designer's Measures, Security and Contract tabs (docs/architecture/BI_MODEL_SPEC.md sections 2.4, 2.6, 2.8 and 9.3), on the designer's object
   (window.drishtiModel, bi-model-designer.js). Measures: pick a column and get the right default (aggregation, format, additivity, currency from the
   dataset), or write a formula with the model's fields and measures offered; the server's Check beside each row; Preview as you. Security: the datasets'
   own rules, the model's rules (built from a field and the reader's attributes, no typing), masks, and the safeguard: a role restricted on one dataset
   but free on another is named, with Add a rule and May see every row as buttons. Contract: version, lifecycle, what consumers may use. */
(function () {
  'use strict';
  const M = window.drishtiModel, B = window.drishtiBi;
  if (!M || !B) { return; }
  const h = M.h, q = M.q;
  const FORMATS = ['', 'amount0', 'amount2', 'signed0', 'signed2', 'pct', 'integer', 'decimal2'];
  const AGGS = [['', 'the column\'s default'], ['sum', 'sum'], ['avg', 'average'], ['min', 'minimum'], ['max', 'maximum'], ['count', 'count'], ['distinct', 'distinct count']];
  const FORMAT_WORDS = { amount0: 'amount, no decimals', amount2: 'amount, 2 decimals', signed0: 'signed amount, no decimals', signed2: 'signed amount, 2 decimals', pct: 'percent', integer: 'whole number', decimal2: 'number, 2 decimals' };
  const ATTRS = [['user.desks', 'the reader\'s desks'], ['user.roles', 'the reader\'s roles']];
  const sel = (options, value, attrs) => h('select', Object.assign({ class: 'studio-select' }, attrs || {}), options.map((o) => { const [v, t] = Array.isArray(o) ? o : [o, o || '(none)']; return h('option', { value: v, text: t, selected: String(value == null ? '' : value) === v }); }));
  const fieldsOf = (filter) => { const out = []; Object.keys(M.infos).forEach((a) => M.infos[a].columns.forEach((c) => { if (!filter || filter(c)) { out.push(a + '.' + c.name); } })); return out; };
  const near = (el, t, bad) => B.near(el, t, bad);
  const major = () => String(M.doc.version || '1').split('.')[0];

  /** Redraws `pane` with `draw()` and gives the half-filled forms in it ([data-keep]) their values and focus back: a Check that lands while
      someone is typing never wipes what they typed. */
  function keepForms(pane, draw) {
    const kept = [];
    B.qa('[data-keep] input, [data-keep] select, [data-keep] textarea', pane).forEach((el) => {
      const key = Array.from(el.attributes).map((a) => a.name).find((n) => n.indexOf('data-') === 0);
      if (key) { kept.push({ key, value: el.type === 'checkbox' ? el.checked : el.value, focus: document.activeElement === el }); }
    });
    draw();
    kept.forEach((k) => {
      const el = pane.querySelector('[data-keep] [' + k.key + ']');
      if (!el) { return; }
      if (el.type === 'checkbox') { el.checked = k.value; } else if (el.value !== k.value) { el.value = k.value; if (el.tagName === 'SELECT') { el.dispatchEvent(new Event('change')); } }
      if (k.focus) { el.focus(); }
    });
  }

  // ---- measures ------------------------------------------------------------------------------------------------------
  let preview = {}, formulaOpen = false;
  /** The model's fields for the ExpressionEditor's completion: alias.column with type, meaning and format. */
  M.exprFields = () => {
    const out = [];
    Object.keys(M.infos).forEach((a) => M.infos[a].columns.forEach((c) => out.push({ name: a + '.' + c.name, type: c.type, meaning: c.meaning || c.role, format: c.format, description: c.description })));
    return out;
  };
  M.exprMeasures = () => Object.keys(M.doc.measures || {}).map((n) => ({ name: n, description: (M.doc.measures[n] || {}).description || (M.doc.measures[n] || {}).expr || '' }))
    .concat(((M.check && M.check.measures) || []).filter((n) => !(M.doc.measures || {})[n]).map((n) => ({ name: n, description: 'a default measure' })));
  /** A grouping field for Try it and Preview: the first text column of a dimension the fact reaches, else the fact's first dimension column. */
  M.tryField = () => {
    const fact = Object.keys(M.doc.datasets || {}).find((a) => (M.doc.datasets[a] || {}).role !== 'dimension') || Object.keys(M.doc.datasets || {})[0];
    const rel = (M.doc.relationships || []).find((r) => r.active !== false && r.from.split('.')[0] === fact);
    const dims = rel && M.infos[rel.to.split('.')[0]] ? M.infos[rel.to.split('.')[0]].columns.filter((c) => c.type === 'string' && c.role !== 'key') : [];
    if (dims.length) { return rel.to.split('.')[0] + '.' + (dims.find((c) => c.name === 'name') || dims[0]).name; }
    const col = M.infos[fact] && M.infos[fact].columns.find((c) => c.role === 'dimension');
    return col ? fact + '.' + col.name : '';
  };
  /** The ExpressionEditor on a measure's formula: the model's fields and measures, the server's check against this draft, Try it on the saved draft. */
  M.exprEditor = (input, onCommit) => B.exprEditor(input, {
    scope: 'aggregate', label: 'Formula', fields: M.exprFields, measures: M.exprMeasures, onCommit,
    target: () => ({ model: M.doc.model + '@' + major(), yaml: M.yaml }),
    tryIt: (expr) => {
      if (M.dirty) { return Promise.reject(new Error('Try it reads the saved draft: Save first (Ctrl+S), then Try it.')); }
      const by = M.tryField();
      return B.mq.ask({ mq: 1, model: M.doc.model + '@' + major(), define: { 'Try it': expr }, select: { fields: by ? [by] : [], measures: ['Try it'] }, limit: 8 })
        .then((a) => ({ columns: a.columns, rows: a.rows, caption: 'As you' + (a.meta.asOf ? ', as of ' + a.meta.asOf : '') + ', on the saved draft' + (by ? ', by ' + by : '') }));
    }
  });
  function addMeasure(name, def) {
    let n = name, i = 2;
    while ((M.doc.measures || {})[n]) { n = name + ' ' + i++; }
    M.edit((doc) => { doc.measures = doc.measures || {}; doc.measures[n] = def; });
    return n;
  }
  function renameMeasure(from, to, anchor) {
    to = to.trim();
    if (!to || to === from) { return; }
    if ((M.doc.measures || {})[to]) { near(anchor, 'There is already a measure called ' + to + '.', true); return; }
    M.edit((doc) => {
      const next = {};
      Object.keys(doc.measures).forEach((k) => { next[k === from ? to : k] = doc.measures[k]; });
      const q1 = "measure('" + from.replace(/'/g, "\\'") + "')", q2 = 'measure("' + from + '")', to1 = "measure('" + to.replace(/'/g, "\\'") + "')";
      Object.values(next).forEach((m) => { if (m && m.expr) { m.expr = m.expr.split(q1).join(to1).split(q2).join(to1).split('[' + from + ']').join(to1); } });
      doc.measures = next;
    });
  }
  function drawMeasures() {
    const pane = B.clear(q('[data-measures-pane]'));
    const aliases = Object.keys(M.doc.datasets || {});
    q('[data-count-measures]').textContent = Object.keys(M.doc.measures || {}).length || '';
    // new measure from a column
    const fact = aliases.find((a) => (M.doc.datasets[a] || {}).role === 'fact') || aliases.find((a) => (M.doc.datasets[a] || {}).role !== 'dimension') || aliases[0];
    const dsSel = sel(aliases, fact, { 'data-nm-alias': '', 'aria-label': 'Dataset', disabled: M.readOnly });
    const colSel = h('select', { class: 'studio-select', 'data-nm-column': '', 'aria-label': 'Column', disabled: M.readOnly });
    const aggSel = sel(AGGS, '', { 'data-nm-agg': '', 'aria-label': 'Aggregation', disabled: M.readOnly });
    const fillCols = () => {
      B.clear(colSel);
      const info = M.infos[dsSel.value];
      colSel.appendChild(h('option', { value: '*', text: 'count the rows' }));
      (info ? info.columns : []).slice().sort((a, b) => (['amount', 'quantity', 'ratio'].indexOf(a.role) < 0) - (['amount', 'quantity', 'ratio'].indexOf(b.role) < 0)).forEach((c) => colSel.appendChild(h('option', { value: c.name, text: c.name + (c.role ? ' (' + c.role + (c.aggregate ? ', ' + c.aggregate : '') + ')' : '') })));
      const first = info && info.columns.find((c) => ['amount', 'quantity'].indexOf(c.role) >= 0);
      if (first) { colSel.value = first.name; }
    };
    dsSel.addEventListener('change', fillCols);
    fillCols();
    const add = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-nm-add': '', text: 'Add measure', disabled: M.readOnly || !aliases.length });
    add.addEventListener('click', () => B.busy(add, () => B.layer.post('measure', { doc: M.doc, alias: dsSel.value, column: colSel.value, aggregate: aggSel.value }).then((r) => {
      const n = addMeasure(r.name, r.measure);
      near(add, 'Added ' + n + ': ' + r.measure.expr + (r.measure.additive === false ? ' (never added up across rows)' : r.measure.additive && r.measure.additive['over-time'] ? ' (a balance: ' + r.measure.additive['over-time'] + ' over time)' : '') + (r.measure.currency ? ', in ' + r.measure.currency : '') + '.', false);
    }).catch((e) => near(add, e.message, true))));
    // a formula
    const fName = h('input', { class: 'studio-in', 'data-nf-name': '', placeholder: 'MTM per 1m notional', 'aria-label': 'Measure name', disabled: M.readOnly });
    const fExpr = h('input', { class: 'studio-in mono', 'data-nf-expr': '', placeholder: 'ratio(sum(trade.mtm), sum(trade.notional)) * 1000000', 'aria-label': 'Formula', disabled: M.readOnly });
    const fAdd = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-nf-add': '', text: 'Add formula', disabled: M.readOnly });
    fAdd.addEventListener('click', () => {
      if (!fName.value.trim() || !fExpr.value.trim()) { near(fAdd, 'Give the measure a name and a formula.', true); return; }
      const n = addMeasure(fName.value.trim(), { expr: fExpr.value.trim() });
      M.compose(true).then(() => { const p = M.problems('measures.' + n); near(fAdd, p.length ? p[0].code + ' ' + p[0].message : 'Added ' + n + '; it checks clean.', p.length > 0); });
    });
    pane.append(h('section', { class: 'bi-sec bi-form bi-md-newmeasure', 'aria-label': 'New measure', 'data-keep': '' }, h('h3', { text: 'New measure from a column' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'The aggregation, format, additivity and currency come from the dataset: amounts sum, ratios average and never add up, a balance takes its last value over time.' }),
      h('div', { class: 'bi-md-row' }, h('label', {}, 'Dataset ', dsSel), h('label', {}, 'Column ', colSel), h('label', {}, 'Aggregation ', aggSel), add)),
      h('details', { class: 'bi-sec', 'data-keep': '', 'data-nf': '', open: formulaOpen || null }, h('summary', { text: 'Write a formula' }),
        h('p', { class: 'text-muted-d bi-small-note', text: 'Python-like: sum(trade.mtm, where=trade.status == \'LIVE\'), measure(\'MTM\') / measure(\'Notional\'), a if c else b. Type to see the fields, the measures and the functions; Ctrl+Space lists them.' }),
        h('div', { class: 'bi-md-row' }, h('label', {}, 'Name ', fName), h('div', { class: 'bi-md-grow' }, fExpr), fAdd)));
    if (!M.readOnly) { M.exprEditor(fExpr, null); }
    q('[data-nf]', pane).addEventListener('toggle', (e) => { formulaOpen = e.target.open; });
    // the measures
    const list = h('div', { class: 'tbl-wrap' });
    const tb = h('tbody');
    list.appendChild(h('table', { class: 'tbl bi-tbl bi-md-measures', 'data-plain': '' }, h('caption', { class: 'visually-hidden', text: 'Measures' }),
      h('thead', {}, h('tr', {}, ['Measure', 'Formula', 'Format', 'Adds up', '', ''].map((t) => h('th', { scope: 'col', text: t })))), tb));
    Object.keys(M.doc.measures || {}).forEach((name) => {
      const m = M.doc.measures[name] || {};
      const probs = M.problems('measures.' + name);
      const nm = h('input', { class: 'studio-in', value: name, 'aria-label': 'Name of ' + name, 'data-m-name': name, disabled: M.readOnly });
      nm.addEventListener('change', () => renameMeasure(name, nm.value, nm));
      const ex = h('input', { class: 'studio-in mono', value: m.expr || (m.python ? 'python: ' + m.python : ''), 'aria-label': 'Formula of ' + name, 'data-m-expr': name, disabled: M.readOnly || !!m.python });
      const exCell = h('td', {}, ex);
      const commitExpr = (v) => { if (v && v !== (M.doc.measures[name] || {}).expr) { M.edit((doc) => { doc.measures[name].expr = v; }, true); } };
      if (!M.readOnly && !m.python) { M.exprEditor(ex, commitExpr); } else { ex.addEventListener('change', () => commitExpr(ex.value.trim())); }
      const fm = sel((FORMATS.indexOf(m.format || '') < 0 ? FORMATS.concat([m.format]) : FORMATS).map((f) => (f ? [f, FORMAT_WORDS[f] || f] : ['', 'from the column\'s meaning'])), m.format || '', { 'aria-label': 'Format of ' + name, 'data-m-format': name, disabled: M.readOnly });
      fm.addEventListener('change', () => M.edit((doc) => { if (fm.value) { doc.measures[name].format = fm.value; } else { delete doc.measures[name].format; } }, true));
      const addv = m.additive === false ? 'never' : m.additive && m.additive['over-time'] ? 'last' : 'yes';
      const ad = sel([['yes', 'yes'], ['last', 'not over time (last)'], ['never', 'never']], addv, { 'aria-label': 'Additivity of ' + name, 'data-m-additive': name, disabled: M.readOnly });
      ad.addEventListener('change', () => M.edit((doc) => { const x = doc.measures[name]; if (ad.value === 'yes') { delete x.additive; } else if (ad.value === 'never') { x.additive = false; } else { x.additive = { 'over-time': 'last' }; } }, true));
      const pv = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-m-preview': name, text: 'Preview' });
      pv.addEventListener('click', () => previewMeasure(name, pv));
      const rm = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Delete ' + name, 'data-m-delete': name, disabled: M.readOnly }, B.icon('trash'));
      rm.addEventListener('click', () => M.edit((doc) => { delete doc.measures[name]; }));
      exCell.append(probs.length ? h('p', { class: 'bi-near bi-bad', 'data-m-problem': name, text: probs[0].code + ' ' + probs[0].message }) : '',
        preview[name] ? h('div', { class: 'bi-md-preview', 'data-m-previewed': name }, preview[name]) : '');
      tb.appendChild(h('tr', { 'data-measure': name, class: probs.some((p) => p.level === 'error') ? 'bi-md-badrow' : '' }, h('td', {}, nm), exCell, h('td', {}, fm), h('td', {}, ad), h('td', {}, pv), h('td', {}, rm)));
    });
    if (!Object.keys(M.doc.measures || {}).length) { tb.appendChild(h('tr', {}, h('td', { colspan: '6', class: 'text-muted-d', text: 'No measure written yet. The default measures below exist anyway; add one above to rename or format it.' }))); }
    pane.appendChild(h('section', { class: 'bi-sec' }, h('h3', { text: 'Measures' }), list));
    const implicit = ((M.check && M.check.measures) || []).filter((n) => !(M.doc.measures || {})[n]);
    if (implicit.length) {
      pane.appendChild(h('section', { class: 'bi-sec' }, h('h3', { text: 'Default measures (there without writing them)' }),
        h('p', { class: 'text-muted-d bi-small-note', text: 'One per column with an aggregation, and a count per dataset. Reports may use them by these names.' }),
        h('ul', { class: 'bi-md-defaults', role: 'list' }, implicit.map((n) => h('li', { 'data-implicit-measure': n, text: n })))));
    }
  }
  function previewMeasure(name, btn) {
    const run = () => {
      const fact = Object.keys(M.doc.datasets || {}).find((a) => (M.doc.datasets[a] || {}).role !== 'dimension') || Object.keys(M.doc.datasets || {})[0];
      const rel = (M.doc.relationships || []).find((r) => r.active !== false && r.from.split('.')[0] === fact);
      const dims = rel && M.infos[rel.to.split('.')[0]] ? M.infos[rel.to.split('.')[0]].columns.filter((c) => c.type === 'string' && c.role !== 'key') : [];
      const by = dims.length ? rel.to.split('.')[0] + '.' + (dims.find((c) => c.name === 'name') || dims[0]).name : (M.infos[fact] ? fact + '.' + ((M.infos[fact].columns.find((c) => c.role === 'dimension') || {}).name || '') : '');
      const mq = { mq: 1, model: M.doc.model + '@' + major(), select: { fields: by && !/\.$/.test(by) ? [by] : [], measures: [name] }, limit: 12 };
      return B.mq.ask(mq).then((a) => {
        preview[name] = h('table', { class: 'tbl bi-tbl bi-md-mini', 'data-plain': '' }, h('caption', { class: 'text-muted-d', text: 'As you, ' + (a.meta.asOf ? 'as of ' + a.meta.asOf : '') + ' (the saved draft)' }),
          h('tbody', {}, a.rows.map((r) => h('tr', {}, r.map((v) => h('td', { text: v == null ? '' : typeof v === 'number' ? B.fmt(v) : String(v) }))))));
        drawMeasures();
      });
    };
    const go = () => B.busy(btn, run).catch((e) => near(btn, e.message, true));
    if (M.dirty) { return B.confirm(btn, 'Preview reads the saved draft. Save now?', 'Save and preview').then((yes) => { if (yes) { q('[data-save]').click(); window.setTimeout(() => { if (!M.dirty) { go(); } }, 1200); } }); }
    return go();
  }

  // ---- security ------------------------------------------------------------------------------------------------------
  const sec = () => { M.doc.security = M.doc.security || {}; return M.doc.security; };
  function addRule(alias, roles, where) {
    M.edit((doc) => {
      const s = (doc.security = doc.security || {});
      s.rows = s.rows || {};
      s.rows[alias] = (s.rows[alias] || []).concat([{ role: roles.length === 1 ? roles[0] : roles, where }]);
    });
  }
  function allowAll(alias, role) {
    M.edit((doc) => {
      const s = (doc.security = doc.security || {});
      s['may-see-all'] = s['may-see-all'] || {};
      s['may-see-all'][alias] = Array.from(new Set((s['may-see-all'][alias] || []).concat([role])));
    });
  }
  function drawSecurity() {
    const pane = B.clear(q('[data-security-pane]'));
    const ex = (M.check && M.check.exposures) || [];
    const badge = q('[data-count-security]');
    badge.hidden = !ex.length;
    badge.textContent = ex.length ? '⚠ ' + ex.length : '';
    pane.appendChild(h('p', { class: 'text-muted-d', text: 'Each dataset\'s own rules apply first, before any join. Rules here can only remove rows (they are ANDed with the dataset\'s); masks here can only add.' }));
    // the safeguard
    if (ex.length) {
      const box = h('section', { class: 'bi-sec bi-md-safeguard', 'data-safeguard': '', role: 'alert' }, h('h3', {}, B.icon('shield-exclamation'), ' Before this model can be published'));
      ex.forEach((x) => {
        const fix = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-safeguard-rule': x.dataset + ':' + x.role, text: 'Add a rule for ' + x.role + ' on ' + x.dataset, disabled: M.readOnly });
        const ok = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-safeguard-all': x.dataset + ':' + x.role, text: x.role + ' may see every row of ' + x.dataset, disabled: M.readOnly });
        fix.addEventListener('click', () => { draft = { alias: x.dataset, roles: x.role }; drawSecurity(); const f = q('[data-rule-form]'); if (f) { f.scrollIntoView({ block: 'nearest' }); const w = q('[data-rule-field]'); if (w) { w.focus(); } } });
        ok.addEventListener('click', () => allowAll(x.dataset, x.role));
        box.appendChild(h('p', { 'data-exposure': x.dataset + ':' + x.role }, 'The role ', h('strong', { text: x.role }), ' is restricted elsewhere in this model, but ', h('strong', { text: x.dataset }),
          ' has no rule: it would see every one of its rows. ', fix, ' ', ok));
      });
      pane.appendChild(box);
    } else if (Object.keys(M.doc.datasets || {}).length) {
      pane.appendChild(h('p', { class: 'bi-ok', 'data-safeguard-clear': '', text: 'Safeguard: no role is restricted on one dataset and free on another.' }));
    }
    // per dataset
    const s = M.doc.security || {};
    Object.keys(M.doc.datasets || {}).forEach((alias) => {
      const info = M.infos[alias];
      const own = (info && info.rows) || [];
      const mine = ((s.rows || {})[alias]) || [];
      const all = ((s['may-see-all'] || {})[alias]) || [];
      const ul = h('ul', { class: 'bi-layer-list' });
      own.forEach((r) => ul.appendChild(h('li', { class: 'text-muted-d' }, 'Dataset rule: ', h('strong', { text: [].concat(r.role).join(', ') }), ' sees ', h('code', { text: r.where }))));
      mine.forEach((r, i) => {
        const rm = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove this rule', 'data-rule-remove': alias + ':' + i, disabled: M.readOnly }, B.icon('x-lg'));
        rm.addEventListener('click', () => M.edit((doc) => { doc.security.rows[alias].splice(i, 1); if (!doc.security.rows[alias].length) { delete doc.security.rows[alias]; } }));
        ul.appendChild(h('li', { 'data-rule': alias + ':' + i }, 'Model rule: ', h('strong', { text: [].concat(r.role).join(', ') }), ' sees ', h('code', { text: r.where }), ' ', rm));
      });
      all.forEach((role) => {
        const rm = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Take back', 'data-allow-remove': alias + ':' + role, disabled: M.readOnly }, B.icon('x-lg'));
        rm.addEventListener('click', () => M.edit((doc) => { const a = doc.security['may-see-all']; a[alias] = a[alias].filter((x) => x !== role); if (!a[alias].length) { delete a[alias]; } }));
        ul.appendChild(h('li', { 'data-allowed': alias + ':' + role }, h('strong', { text: role }), ' may see every row (said on purpose; audited at publish) ', rm));
      });
      if (!ul.children.length) { ul.appendChild(h('li', { class: 'text-muted-d', text: 'No rule: every reader of the model sees every row.' })); }
      pane.appendChild(h('section', { class: 'bi-sec', 'data-sec-dataset': alias }, h('h3', {}, h('code', { text: alias }), ' ', info ? h('small', { class: 'text-muted-d', text: info.title }) : null), ul));
    });
    // add a rule (no typing beyond the role)
    const aliases = Object.keys(M.doc.datasets || {});
    if (aliases.length && !M.readOnly) {
      const d = draft || {};
      const aSel = sel(aliases, d.alias || aliases[0], { 'data-rule-alias': '', 'aria-label': 'Dataset' });
      const roles = h('input', { class: 'studio-in', 'data-rule-roles': '', value: '', placeholder: 'another role, e.g. desk-head', 'aria-label': 'Another role (comma-separated)', list: 'mdRoles' });
      const known = new Set((roleList && roleList.roles) || []);
      Object.values((M.doc.security || {}).rows || {}).forEach((rs) => rs.forEach((r) => [].concat(r.role).forEach((x) => known.add(x))));
      Object.values(M.infos).forEach((i) => (i.rows || []).forEach((r) => [].concat(r.role).forEach((x) => known.add(x))));
      const kind = sel([['attr', 'the column matches the reader'], ['value', 'the column equals a value'], ['all', 'every row']], 'attr', { 'data-rule-kind': '', 'aria-label': 'Which rows' });
      const field = h('select', { class: 'studio-select', 'data-rule-field': '', 'aria-label': 'Column' });
      const fill = () => { B.clear(field); ((M.infos[aSel.value] || {}).columns || []).forEach((c) => field.appendChild(h('option', { value: aSel.value + '.' + c.name, text: c.name }))); };
      aSel.addEventListener('change', fill);
      fill();
      const attr = sel(ATTRS, 'user.desks', { 'data-rule-attr': '', 'aria-label': 'Reader attribute' });
      const val = h('input', { class: 'studio-in', 'data-rule-value': '', placeholder: 'DESK-FX', 'aria-label': 'Value', hidden: true });
      kind.addEventListener('change', () => { field.hidden = kind.value === 'all'; attr.hidden = kind.value !== 'attr'; val.hidden = kind.value !== 'value'; });
      const go = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-rule-add': '', text: 'Add the rule' });
      const picked = new Set([].concat(d.roles || []));
      const roleBox = h('div', { class: 'bi-roles', 'data-role-picker': '', role: 'group', 'aria-label': 'Roles the rule is for' },
        Array.from(known).sort().map((r) => { const cb = h('input', { type: 'checkbox', value: r, 'data-role-pick': r, checked: picked.has(r) }); return h('label', { class: 'bi-checkrow' }, cb, ' ' + r); }));
      go.addEventListener('click', () => {
        const rs = B.qa('[data-role-pick]', roleBox).filter((c) => c.checked).map((c) => c.value).concat(roles.value.split(',').map((x) => x.trim()).filter(Boolean)).filter((x, i, a) => a.indexOf(x) === i);
        if (!rs.length) { near(go, 'Name the role (or roles) the rule is for.', true); roles.focus(); return; }
        const where = kind.value === 'all' ? 'True' : kind.value === 'attr' ? field.value + ' in ' + attr.value : field.value + " == '" + val.value.replace(/'/g, '') + "'";
        if (kind.value === 'value' && !val.value.trim()) { near(go, 'Give the value.', true); val.focus(); return; }
        draft = null;
        roles.value = '';
        B.qa('[data-role-pick]', roleBox).forEach((c) => { c.checked = false; });
        addRule(aSel.value, rs, where);
        near(go, 'Added: ' + rs.join(', ') + ' sees ' + where + '.', false);
      });
      pane.appendChild(h('section', { class: 'bi-sec bi-form', 'data-rule-form': '', 'data-keep': '' }, h('h3', { text: 'Add a rule for this model' }),
        h('datalist', { id: 'mdRoles' }, Array.from(known).map((r) => h('option', { value: r }))),
        h('p', { class: 'text-muted-d bi-small-note', 'data-roles-note': '', text: 'Roles without a rule see no rows of the dataset: a rule says which rows a role may read.' + (roleList && roleList.source === 'identity' ? ' The roles listed are the ones your account carries and the ones already used in this model; type any other role below.' : '') }),
        h('fieldset', { class: 'bi-roles-box' }, h('legend', { text: 'Roles' }), roleBox, roles),
        h('div', { class: 'bi-md-row' }, h('label', {}, 'On ', aSel), h('label', {}, 'see rows where ', kind), field, attr, val, go)));
      // masks
      const masked = (M.doc.security || {}).masked || [];
      const mf = sel(fieldsOf(), '', { 'data-mask-field': '', 'aria-label': 'Column to mask' });
      const mAdd = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-mask-add': '', text: 'Mask it' });
      mAdd.addEventListener('click', () => { if (masked.indexOf(mf.value) < 0) { M.edit((doc) => { const s2 = (doc.security = doc.security || {}); s2.masked = (s2.masked || []).concat([mf.value]); }); } });
      pane.appendChild(h('section', { class: 'bi-sec', 'data-masks': '', 'data-keep': '' }, h('h3', { text: 'Masked columns' }), h('p', { class: 'text-muted-d bi-small-note', text: 'Readers without raw access read ••• in these (the datasets\' own masks always apply too).' }),
        h('p', { class: 'bi-chips' }, masked.map((f) => { const x = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Unmask ' + f, 'data-mask-remove': f }, B.icon('x-lg')); x.addEventListener('click', () => M.edit((doc) => { doc.security.masked = doc.security.masked.filter((y) => y !== f); })); return h('span', { class: 'bi-chip' }, f, x); })),
        h('p', {}, mf, ' ', mAdd)));
    }
    void sec;
  }
  let draft = null, roleList = null;
  B.layer.get('roles').then((r) => { roleList = r; if (M.tab === 'security') { M.emit('redraw'); } }).catch(() => {});

  // ---- contract ------------------------------------------------------------------------------------------------------
  function drawContract() {
    const pane = B.clear(q('[data-contract-pane]'));
    const v = String(M.doc.version || '1.0.0');
    const ver = h('input', { class: 'studio-in mono', value: v, 'data-c-version': '', 'aria-label': 'Version', disabled: M.readOnly });
    ver.addEventListener('change', () => { if (!/^\d+\.\d+\.\d+$/.test(ver.value.trim())) { near(ver, 'A version is three numbers: 1.2.0.', true); return; } M.edit((doc) => { doc.version = ver.value.trim(); }); });
    const bump = (i) => { const p = v.split('.').map((x) => parseInt(x, 10) || 0); p[i] += 1; for (let j = i + 1; j < 3; j++) { p[j] = 0; } M.edit((doc) => { doc.version = p.join('.'); }); };
    const bumps = [['patch', 2, 'descriptions, formats'], ['minor', 1, 'adds measures or fields'], ['major', 0, 'removes or changes something consumers use']].map(([n, i, why]) => {
      const b = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-c-bump': n, title: why, text: 'Next ' + n, disabled: M.readOnly });
      b.addEventListener('click', () => bump(i));
      return b;
    });
    const title = h('input', { class: 'studio-in', value: M.doc.title || '', 'data-c-title': '', 'aria-label': 'Title', disabled: M.readOnly });
    title.addEventListener('change', () => M.edit((doc) => { doc.title = title.value.trim() || doc.model; }));
    const desc = h('textarea', { class: 'studio-in', rows: '2', 'data-c-desc': '', 'aria-label': 'Description', disabled: M.readOnly }, M.doc.description || '');
    desc.addEventListener('change', () => M.edit((doc) => { if (desc.value.trim()) { doc.description = desc.value.trim(); } else { delete doc.description; } }, true));
    const life = sel([['draft', 'draft'], ['published', 'published'], ['deprecated', 'deprecated']], M.doc.lifecycle || 'draft', { 'data-c-lifecycle': '', 'aria-label': 'Lifecycle', disabled: M.readOnly });
    life.addEventListener('change', () => M.edit((doc) => { doc.lifecycle = life.value; }, true));
    const c = M.doc.contract || {};
    const exposes = sel([['all', 'everything in the model'], ['listed', 'only what is ticked below']], c.exposes || 'all', { 'data-c-exposes': '', 'aria-label': 'What consumers may use', disabled: M.readOnly });
    exposes.addEventListener('change', () => M.edit((doc) => { doc.contract = Object.assign({}, doc.contract || {}, { exposes: exposes.value }); if (exposes.value === 'all') { delete doc.contract.fields; delete doc.contract.measures; if (Object.keys(doc.contract).length === 1) { delete doc.contract; } } }));
    const picks = h('div', { class: 'bi-layer-picks', hidden: (c.exposes || 'all') !== 'listed' });
    const tick = (key, name) => {
      const box = h('input', { type: 'checkbox', checked: (c[key] || []).indexOf(name) >= 0, 'data-c-pick': name, disabled: M.readOnly });
      box.addEventListener('change', () => M.edit((doc) => { const k = (doc.contract = doc.contract || { exposes: 'listed' }); k[key] = (k[key] || []).filter((x) => x !== name).concat(box.checked ? [name] : []); }, true));
      return h('label', { class: 'bi-checkrow' }, box, ' ' + name);
    };
    Object.keys(M.doc.measures || {}).forEach((n) => picks.appendChild(tick('measures', n)));
    fieldsOf((col) => col.role !== 'amount').forEach((f) => picks.appendChild(tick('fields', f)));
    const pub = M.item && M.item.publishedVersion ? 'Published before: a change that removes or changes what consumers use needs the next major version; the server refuses it otherwise (DRS-9126).' : 'Not published yet: the first publish sets the contract.';
    pane.append(h('section', { class: 'bi-sec bi-form' }, h('h3', { text: 'Name and version' }), h('label', { class: 'bi-frow' }, h('span', { text: 'Title' }), title),
      h('label', { class: 'bi-frow' }, h('span', { text: 'Description' }), desc), h('p', {}, h('code', { text: 'model: ' + M.doc.model })),
      h('div', { class: 'bi-md-row' }, h('label', {}, 'Version ', ver), bumps), h('p', { class: 'text-muted-d bi-small-note', text: pub + ' Reports bind to the major: ' + M.doc.model + '@' + major() + '.' }),
      h('label', { class: 'bi-frow' }, h('span', { text: 'Lifecycle' }), life)),
    h('section', { class: 'bi-sec bi-form' }, h('h3', { text: 'What consumers may use' }), exposes, picks));
  }

  function redrawTab() {
    if (!M.doc) { return; }
    q('[data-count-measures]').textContent = Object.keys(M.doc.measures || {}).length || '';
    const ex = (M.check && M.check.exposures) || [];
    const badge = q('[data-count-security]');
    badge.hidden = !ex.length;
    badge.textContent = ex.length ? '⚠ ' + ex.length : '';
    if (M.tab === 'measures') { keepForms(q('[data-measures-pane]'), drawMeasures); } else if (M.tab === 'security') { keepForms(q('[data-security-pane]'), drawSecurity); } else if (M.tab === 'contract') { drawContract(); }
  }
  M.on('redraw', redrawTab);
  M.on('tab', redrawTab);
})();

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
/* Reports bound to a model (docs/architecture/BI_MODEL_SPEC.md sections 3, 4.1 and 9.4), in the report designer and the report page. A report whose YAML
   says `model: <name>@<major>` or `model: dataset:<name>` reads that model only: the Fields pane is the model as the person sees it (/models/{id}/describe),
   every visual's data is ONE model query to the server's /mq, built from its wells, filters, the slicers and the other visuals' selections (B.mq), and a
   filter's value picker asks the model too. The header of the Fields pane says which model it is; an implicit model offers Make it a model, which writes
   it out as a draft model and rebinds the report in one step. Each visual's Query panel shows its model query as YAML.

   It sits in front of B.api for the report paths the server still answers for dataset-bound reports (the server's report endpoints bind to models in a
   later package): /reports/validate (the `dataset:` the model replaces is not a problem), /reports/format (the `model:` line is kept), /reports/dataset
   (the model's fields), /reports/query and /reports/{id}/query (the visual's model query). A report without `model:` is untouched. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const canvas = document.getElementById('biCanvas');
  if (!B || !B.layer || !B.mq || !canvas) { return; }
  const api = B.api;
  const S = (B.modelBinding = { ref: '', fields: null, idx: null, report: null, reportId: '' });

  const withModel = (yaml) => (/^model:/m.test(yaml) ? yaml : /^dataset:\s*""\s*$/m.test(yaml) ? yaml.replace(/^dataset:\s*""\s*$/m, 'model: ' + S.ref) : yaml.replace(/^(report:.*)$/m, '$1\nmodel: ' + S.ref));
  /** The report's own measures (`measures:`): read from its YAML by the console (the server's report reader does not keep them yet) and written back. */
  /** And its perspective (`perspective:`, BI_DESIGN.md 3.1): the Fields pane shows only that perspective's fields. */
  const extrasOf = (yaml) => (/^(measures|perspective):/m.test(yaml || '') ? B.layer.post('report-measures', { yaml }).then((r) => ({ measures: r.measures || {}, perspective: r.perspective || '' }))
    .catch(() => ({ measures: {}, perspective: '' })) : Promise.resolve({ measures: {}, perspective: '' }));
  const localOf = (yaml) => extrasOf(yaml).then((x) => { S.perspective = x.perspective; return x.measures; });
  const withMeasures = (yaml, ms, perspective) => {
    let out = yaml;
    if (perspective && !/^perspective:/m.test(out)) { out = out.replace(/^(model:.*)$/m, '$1\nperspective: ' + JSON.stringify(perspective)); }
    if (!ms || !Object.keys(ms).length || /^measures:/m.test(out)) { return out; }
    const obj = (m) => (typeof m === 'string' ? { expr: m } : m);
    const lines = Object.keys(ms).map((n) => '  ' + JSON.stringify(n) + ': { ' + Object.keys(obj(ms[n])).map((k) => k + ': ' + JSON.stringify(obj(ms[n])[k])).join(', ') + ' }');
    return out.replace(/^pages:/m, 'measures:\n' + lines.join('\n') + '\npages:');
  };
  const clean = (r) => {
    const problems = (r.problems || []).filter((p) => !(p.code === 'DRS-9030' && (p.path === 'dataset' || p.path === 'model')) && !(p.level === 'warning' && (p.path === 'measures' || p.path === 'perspective')));
    return Object.assign({}, r, { problems, valid: !problems.some((p) => p.level === 'error') });
  };
  /** The model's display names, for every label a visual draws (B.label): `trade.mtm` reads as the model says, `MTM`, wherever it appears. */
  B.displayNames = B.displayNames || {};
  const label = B.label;
  B.label = function (name) {
    const n = String(name), bits = n.split('.');
    if (B.displayNames[n]) { return B.displayNames[n]; }
    if (bits.length === 3 && B.displayNames[bits[0] + '.' + bits[1]]) { return B.displayNames[bits[0] + '.' + bits[1]] + ' ' + bits[2]; }
    return label(name);
  };
  function fields() {
    if (S.fields) { return Promise.resolve(S.fields); }
    return B.layer.get('fields?ref=' + encodeURIComponent(S.ref)).then((f) => {
      S.fields = f; S.idx = B.index(f);
      (f.tables || []).forEach((t) => t.columns.forEach((c) => { if (c.display) { B.displayNames[t.name + '.' + c.name] = c.display; } }));
      return f;
    });
  }
  /** The report's own measures (`measures:` in its YAML, spec 4.2) in the Fields shape, marked local, so wells, the index and the queries know them. */
  function withLocal(report) {
    if (!S.fields) { return; }
    const local = (report && report.measures) || {};
    const model = (S.fields.measures || []).filter((m) => !m.local);
    const mine = Object.keys(local).map((n) => ({ name: n, expr: typeof local[n] === 'string' ? local[n] : local[n].expr, format: (local[n] || {}).format, description: (local[n] || {}).description || '', local: true }));
    const sig = JSON.stringify(mine);
    if (sig === S.localSig) { return; }
    S.localSig = sig;
    S.fields.measures = model.concat(mine);
    S.idx = B.index(S.fields);
  }
  B.modelBinding.withLocal = withLocal;
  function find(report, pageId, visualId) {
    const pages = (report && report.pages) || [];
    const page = pages.find((p) => p.id === pageId) || pages.find((p) => (p.visuals || []).some((v) => v.id === visualId)) || pages[0];
    return { page, v: page && (page.visuals || []).find((x) => x.id === visualId) };
  }
  /** The reader's choices on the report page as filters: slicers (their field, their value) and the other visuals' selections. */
  function choices(report, state, visualId) {
    const out = [];
    const st = state || {};
    Object.keys(st.slicers || {}).forEach((sid) => {
      const val = st.slicers[sid];
      if (val == null || (Array.isArray(val) && !val.length)) { return; }
      const s = find(report, '', sid).v;
      const field = s && Object.values(s.wells || {}).flat()[0];
      if (field) { out.push(Array.isArray(val) ? { field, op: 'in', values: val } : { field, op: 'eq', value: val }); }
    });
    (st.selections || []).filter((s) => s.visual !== visualId && s.field && (s.values || []).length).forEach((s) => out.push({ field: s.field, op: 'in', values: s.values }));
    return out;
  }
  function ask(report, pageId, visualId, state) {
    return fields().then(() => {
      const { page, v } = find(report, pageId, visualId);
      if (!v) { throw Object.assign(new Error('DRS-9150 no visual ' + visualId + ' on this page'), { code: 'DRS-9150' }); }
      withLocal(report);
      const mq = B.mq(S.ref, report, page, v, S.idx, { filters: choices(report, state, visualId) });
      if (!mq) { throw Object.assign(new Error('Add a field or a measure to this visual.'), { code: 'DRS-9150' }); }
      const local = (report && report.measures) || {};
      const used = mq.select.measures.filter((n) => local[n]);
      if (used.length) { mq.define = {}; used.forEach((n) => { mq.define[n] = typeof local[n] === 'string' ? local[n] : local[n].expr; }); }       // the report's own measures travel with the query
      S.last = S.last || {};
      S.last[visualId] = mq;
      return B.mq.ask(mq).then((a) => { S.meta = S.meta || {}; S.meta[visualId] = a.meta; return Object.assign(a, { visual: visualId, query: mq }); });
    });
  }

  B.api = function (method, path, body) {
    if (!S.ref) {
      const p = api.apply(this, arguments);
      if (method === 'GET' && /^\/items\//.test(path)) {                                 // the designer opens its report item: is it bound to a model?
        return p.then((it) => { if (it && it.kind === 'report' && B.layer.modelOf(it.body)) { S.ref = B.layer.modelOf(it.body); S.reportId = it.id; } return it; });
      }
      if (method === 'GET' && /^\/reports\/[^/?]+$/.test(path) && path !== '/reports/catalogue') {                          // the report page: read its body once to know
        return p.then((r) => (r && r.report && !r.report.dataset ? api('GET', '/items/' + path.slice('/reports/'.length)).then((it) => {
          S.ref = B.layer.modelOf(it.body);
          if (!S.ref) { return r; }
          S.reportId = it.id;
          S.report = r.report;
          return localOf(it.body).then((m) => { S.report.measures = m; return fields(); }).then((f) => { withLocal(S.report); return Object.assign(clean(r), { dataset: f }); });
        }).catch(() => r) : r));
      }
      return p;
    }
    if (path === '/reports/format') { return api(method, path, body).then((r) => Object.assign({}, r, { yaml: withMeasures(withModel(r.yaml), body && body.report && body.report.measures, body && body.report && body.report.perspective) })); }
    if (path === '/reports/validate') { return api(method, path, body).then((r) => (r.report && body && /^(measures|perspective):/m.test(body.yaml || '') ? localOf(body.yaml).then((m) => { r.report.measures = m; if (S.perspective) { r.report.perspective = S.perspective; } return clean(r); }) : clean(r))); }
    if (/^\/reports\/dataset\?ref=/.test(path)) { return fields(); }
    if (path === '/reports/query') { return ask(body.report, body.page, body.visual, body.state); }
    if (/^\/reports\/[^/]+\/query$/.test(path) && S.report) { return ask(S.report, body.page, body.visual, body.state); }
    if (/^\/datasets\/.*\/query$/.test(path) && body && (body.fields || []).length === 1 && !(body.measures || []).length) {
      return B.mq.values(S.ref, body.fields[0], body.limit).then((a) => ({ columns: a.columns, rows: a.rows, rowCount: a.rowCount }));
    }
    if (method === 'GET' && /^\/reports\/[^/?]+$/.test(path) && path !== '/reports/catalogue') { return api(method, path, body).then((r) => { S.report = r.report; return fields().then((f) => Object.assign(clean(r), { dataset: f })); }); }
    return api.apply(this, arguments);
  };

  // ---- the designer: the Fields pane's header, Make it a model, the Query panel ---------------------------------------
  // this file runs before bi-design.js (so the report's first requests pass through it); the designer's module is added once that script has run
  const h = B.h;
  let D = null;
  document.addEventListener('DOMContentLoaded', () => { D = B.design; if (D && D.modules) { D.modules.push(designer); } });
  function designer() {
    if (!S.ref) { return; }
    D.boundModel = S.ref;
    const pane = canvas.querySelector('.bi-pane-fields');
    if (!pane) { return; }
    const add = pane.querySelector('[data-add-dataset]');
    if (add) { add.closest('p').hidden = true; }
    const link = pane.querySelector('[data-model-link]');
    const f = S.fields || {};
    const m = f.model || {};
    const head = h('div', { class: 'bi-md-fieldshead', 'data-model-header': m.implicit ? 'implicit' : 'model' });
    const draw = () => {
      B.clear(head);
      if (m.implicit) {
        const mk = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-make-model': '', text: 'Make it a model' });
        mk.addEventListener('click', () => promote(mk));
        head.append(h('p', {}, B.icon('boxes'), ' ', h('strong', { text: f.title || 'Implicit model' })),
          h('p', { class: 'text-muted-d bi-small-note', text: 'Made from one dataset: its pack dimensions come along. Make it a model creates a shared model that this and other reports reference (it is not part of this report); you can then move the other reports on the same dataset onto it. To add another fact, relationships, measures or security, make it a model.' }), mk);
      } else {
        head.append(h('p', {}, B.icon('boxes'), ' Model ', h('strong', { text: f.title || S.ref }), ' ', h('code', { text: S.ref })),
          h('p', { class: 'text-muted-d bi-small-note' }, 'This report references the model; it does not own it. ', h('span', { 'data-affects': '' })),
          h('p', {}, h('a', { class: 'btn-pill btn-ghost bi-small', href: '/bi/models', 'data-open-model': '', text: 'Open the model' })));
      }
    };
    draw();
    pane.insertBefore(head, pane.querySelector('[data-fields-search]'));
    if (link) {
      if (m.implicit) { link.href = '/bi/ds/' + encodeURIComponent(m.dataset || ''); link.lastChild.textContent = ' The dataset'; } else { link.hidden = true; }
    }
    // the model item's page: by id when the model list names it
    if (!m.implicit) {
      B.layer.get('datasets').then((d) => {
        const it = (d.models || []).find((x) => x.kind === 'model' && x.name === m.name);
        const a = head.querySelector('[data-open-model]');
        if (a && it) { a.href = '/bi/md/' + B.id2path(it.id); }
      }).catch(() => {});
    }
    if (!m.implicit) {
      B.layer.consumers(S.ref).then((r) => {
        const span = head.querySelector('[data-affects]');
        if (!span || !r.known) { return; }
        const n = r.consumers.filter((c) => c.kind === 'report').length;
        span.textContent = 'Changes to the model affect ' + n + (n === 1 ? ' report' : ' reports') + (r.consumers.length > n ? ' and ' + (r.consumers.length - n) + ' other item' + (r.consumers.length - n === 1 ? '' : 's') : '') + '.';
      });
    }
    perspectivePicker(head, f);
    localMeasures(pane);
    // Query: the selected visual's question as a model query (MQ) or as SQL (DSQL), one plan either way (bi-query-panel.js)
    if (B.queryPanel) {
      B.queryPanel(pane, { ref: S.ref, current: () => (D.selected && S.last && S.last[D.selected]) || null, fields: () => S.fields, on: D.on,
        lastMeta: () => (D.selected && S.meta && S.meta[D.selected]) || null });
    }
  }

  /** The perspective the report reads (BI_DESIGN.md 8.1): the Fields pane shows only its fields and measures; the report keeps it (`perspective:`). */
  function perspectivePicker(head, f) {
    const names = Object.keys(f.perspectives || {});
    D.perspective = (D.model && D.model.perspective) || S.perspective || '';
    if (!names.length) { return; }
    const pick = h('select', { class: 'studio-select', 'data-perspective-pick': '', 'aria-label': 'Perspective', disabled: D.readOnly },
      h('option', { value: '', text: 'every field' }), names.map((n) => h('option', { value: n, text: f.perspectives[n].title || n, selected: n === D.perspective })));
    pick.addEventListener('change', () => {
      D.perspective = pick.value;
      D.change('Perspective', (r) => { if (pick.value) { r.perspective = pick.value; } else { delete r.perspective; } });
      if (D.drawFields) { D.drawFields(); }
    });
    head.appendChild(h('p', {}, h('label', {}, 'Perspective ', pick)));
    if (D.drawFields) { D.drawFields(); }
  }

  /** A measure of this report only (spec 4.2): a name and a formula in the ExpressionEditor over the model's fields and measures; Try it asks the model. */
  function localMeasures(pane) {
    withLocal(D.model);
    D.idx = S.idx; D.dataset = S.fields;
    if (D.readOnly || !B.exprEditor) { return; }
    const name = h('input', { class: 'studio-in', 'data-lm-name': '', placeholder: 'MTM per trade', 'aria-label': 'Name of the report measure' });
    const expr = h('input', { class: 'studio-in mono', 'data-lm-expr': '', placeholder: "measure('MTM') / count(trade)" });
    const add = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-lm-add': '', text: 'Add to this report' });
    const box = h('details', { class: 'bi-md-localm', 'data-local-measure': '' }, h('summary', {}, B.icon('calculator'), ' New measure for this report'),
      h('p', { class: 'text-muted-d bi-small-note', text: 'Over the model\'s measures and fields, for this report only. A measure every report needs belongs in the model: use Move to the model on it later.' }),
      h('label', { class: 'bi-frow' }, h('span', { text: 'Name' }), name), h('div', { class: 'bi-frow' }, h('span', { text: 'Formula' }), expr), add);
    const search = pane.querySelector('[data-fields-search]');
    pane.insertBefore(box, search);
    const fieldsOf = () => ((S.fields && S.fields.tables) || []).flatMap((t) => t.columns.filter((c) => !c.hidden).map((c) => ({ name: t.name + '.' + c.name, type: c.type, meaning: c.meaning, format: c.format, description: c.description })));
    B.exprEditor(expr, {
      scope: 'aggregate', label: 'Formula of the report measure', fields: fieldsOf, measures: () => ((S.fields && S.fields.measures) || []).map((m) => ({ name: m.name, description: m.description || m.expr || '' })),
      target: () => ({ model: S.ref }),
      tryIt: (text) => B.mq.ask({ mq: 1, model: S.ref, define: { 'Try it': text }, select: { fields: [], measures: ['Try it'] } }).then((a) => ({ columns: a.columns, rows: a.rows, caption: 'As you, over the whole model' + (a.meta.asOf ? ', as of ' + a.meta.asOf : '') }))
    });
    add.addEventListener('click', () => {
      const n = name.value.trim(), x = expr.value.trim();
      if (!n || !x) { B.near(add, 'Give the measure a name and a formula.', true); return; }
      if ((S.fields.measures || []).some((m) => m.name === n)) { B.near(add, 'There is already a measure called ' + n + ' (a report measure cannot shadow one of the model).', true); return; }
      D.change('Add report measure ' + n, (r) => { r.measures = Object.assign({}, r.measures || {}, { [n]: { expr: x } }); });
      withLocal(D.model);
      D.idx = S.idx; D.dataset = S.fields;
      if (D.drawFields) { D.drawFields(); }
      name.value = ''; expr.value = '';
      B.near(add, 'Added ' + n + ': drag it from Measures onto a visual.', false);
    });
    D.on('model', () => { withLocal(D.model); if (D.idx !== S.idx) { D.idx = S.idx; D.dataset = S.fields; if (D.drawFields) { D.drawFields(); } } });
  }

  function promote(btn) {
    const go = () => B.busy(btn, () => B.layer.post('promote', { report: S.reportId, title: (D.model && D.model.title ? D.model.title : 'Report') + ' model', etag: D.item && D.item.etag })).then((r) => {
      D.dirty = false;
      return B.layer.consumers('dataset:' + r.dataset).then((c) => {
        const others = (c.known ? c.consumers : []).filter((x) => x.kind === 'report' && x.id !== S.reportId);
        if (!others.length) {
          B.near(btn, 'Made the shared model ' + r.ref + ' (a draft) and moved this report onto it. Opening it…', false);
          window.setTimeout(() => { window.location.href = r.href; }, 900);
          return null;
        }
        const box = h('div', { class: 'bi-warn', 'data-rebind-box': '', role: 'group', 'aria-label': 'Other reports on the same dataset' }, h('p', { text: 'Made the shared model ' + r.ref + ' (a draft); this report now references it. ' + others.length + ' other report' + (others.length === 1 ? '' : 's') + ' read the same dataset. Move them onto the model too?' }),
          h('ul', { class: 'bi-layer-list' }, others.map((x) => h('li', {}, h('label', {}, h('input', { type: 'checkbox', checked: true, 'data-rebind': x.id }), ' ' + x.title)))));
        const yes = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-rebind-go': '', text: 'Move the ticked reports' });
        const no = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-rebind-skip': '', text: 'Leave them' });
        const leave = () => { window.location.href = r.href; };
        yes.addEventListener('click', () => B.busy(yes, () => B.layer.post('rebind', { ref: r.ref, reports: B.qa('[data-rebind]', box).filter((i) => i.checked).map((i) => i.getAttribute('data-rebind')) })).then(leave).catch((e) => B.near(yes, e.message, true)));
        no.addEventListener('click', leave);
        box.append(yes, ' ', no);
        btn.after(box);
        return null;
      });
    }).catch((e) => B.near(btn, e.message, true));
    if (D.dirty) { return B.confirm(btn, 'Save the report first? Make it a model saves the report bound to the new model.', 'Save and continue').then((yes) => { if (yes) { const s = canvas.querySelector('[data-save]'); if (s) { s.click(); } window.setTimeout(() => { if (!D.dirty) { go(); } }, 1500); } }); }
    return go();
  }
})();

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
  const clean = (r) => {
    const problems = (r.problems || []).filter((p) => !(p.code === 'DRS-9030' && (p.path === 'dataset' || p.path === 'model')));
    return Object.assign({}, r, { problems, valid: !problems.some((p) => p.level === 'error') });
  };
  function fields() {
    if (S.fields) { return Promise.resolve(S.fields); }
    return B.layer.get('fields?ref=' + encodeURIComponent(S.ref)).then((f) => { S.fields = f; S.idx = B.index(f); return f; });
  }
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
      const mq = B.mq(S.ref, report, page, v, S.idx, { filters: choices(report, state, visualId) });
      if (!mq) { throw Object.assign(new Error('Add a field or a measure to this visual.'), { code: 'DRS-9150' }); }
      S.last = S.last || {};
      S.last[visualId] = mq;
      return B.mq.ask(mq).then((a) => Object.assign(a, { visual: visualId, query: mq }));
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
          return fields().then((f) => Object.assign(clean(r), { dataset: f }));
        }).catch(() => r) : r));
      }
      return p;
    }
    if (path === '/reports/format') { return api(method, path, body).then((r) => Object.assign({}, r, { yaml: withModel(r.yaml) })); }
    if (path === '/reports/validate') { return api(method, path, body).then(clean); }
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
    // Query: the selected visual's model query
    const qbox = h('details', { class: 'bi-md-query', 'data-visual-query': '' }, h('summary', { text: 'Query of the selected visual' }), h('pre', { class: 'bi-body mono', 'data-visual-query-text': '', tabindex: '0' }));
    pane.appendChild(qbox);
    const showQuery = () => {
      const pre = qbox.querySelector('pre');
      const mq = D.selected && S.last && S.last[D.selected];
      pre.textContent = mq ? B.mq.yaml(mq) : 'Choose a visual: its model query (what the server is asked, as you) shows here.';
    };
    D.on('select', showQuery);
    D.on('model', () => window.setTimeout(showQuery, 600));
    showQuery();
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

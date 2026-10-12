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
/* BI with AI (docs/guides/BI_AI.md): "Describe a report" in the designer, "Ask the data" and "Explain" on a report page. Off unless the server's AI layer is on
   (GET /bi/api/ai/status); every call is the signed-in person's, through the server's AI policy. The model never sees rows and never runs anything: a draft is checked by
   the report checker before it is shown, a question is run by the engine as the reader, a number's breakdown is worked out by the engine. Same-origin JSON only. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  if (!B) { return; }
  const h = B.h;
  const listeners = [];
  const ai = B.ai = { enabled: false, draft: false, values: 'labels-only', on(fn) { listeners.push(fn); if (ai.loaded) { fn(); } }, loaded: false };
  B.api('GET', '/ai/status').then((s) => { ai.enabled = !!s.enabled; ai.draft = !!s.draft; ai.values = s.values; }).catch(() => {}).then(() => { ai.loaded = true; listeners.forEach((f) => f()); });

  const fail = (el, e) => B.say(el, (e && e.code ? e.code + ' ' : '') + ((e && (e.detail || e.message)) || 'The AI call failed.'), true);
  const canvas = document.getElementById('biCanvas');
  const page = canvas ? canvas.getAttribute('data-bi-page') : '';

  // ---- the answer: a table, the visual that suits it, the query the model chose ----------------------------------------------------------------------------------
  function table(res) {
    const head = h('tr', {}, res.columns.map((c) => h('th', { scope: 'col', text: B.label ? B.label(c.name) : c.name })));
    const rows = res.rows.map((r) => h('tr', {}, r.map((v, i) => h('td', { class: typeof v === 'number' ? 'num' : '', text: B.fmt(v, res.columns[i].name, res.columns[i].masked) }))));
    return h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl bi-ai-table', 'data-plain': '' }, h('thead', {}, head), h('tbody', {}, rows)));
  }

  function picture(res, kind) {
    if (!window.echarts || res.rows.length < 1) { return null; }
    if (kind === 'card') { return h('p', { class: 'bi-ai-card', text: B.fmt(res.rows[0][0], res.columns[0].name, res.columns[0].masked) }); }
    if (kind !== 'bar' && kind !== 'line') { return null; }
    const box = h('div', { class: 'bi-ai-chart', role: 'img', 'aria-label': 'Chart of the answer' });
    window.setTimeout(() => {
      const c = window.echarts.init(box);
      c.setOption({ tooltip: {}, grid: { left: 60, right: 16, top: 16, bottom: 40 }, xAxis: { type: 'category', data: res.rows.map((r) => String(r[0])) }, yAxis: { type: 'value' },
        series: [{ type: kind, data: res.rows.map((r) => (typeof r[1] === 'number' ? r[1] : null)) }] });
    }, 0);
    return box;
  }

  function showAnswer(into, res) {
    B.clear(into);
    into.appendChild(h('p', { class: 'bi-ai-shown' }, h('span', { class: 'bi-badge', text: 'Shown as ' + res.visual }), ' ', h('span', { class: 'text-muted-d', text: res.rowCount + ' row' + (res.rowCount === 1 ? '' : 's') + ', answered by the engine for you' })));
    const pic = picture(res, res.visual);
    if (pic) { into.appendChild(pic); }
    into.appendChild(table(res));
    if (res.summary) { into.appendChild(h('p', { class: 'bi-ai-summary', 'data-ai-summary': '', text: res.summary })); }
    into.appendChild(h('details', { class: 'bi-ai-query' }, h('summary', { text: 'The query the model chose' }), h('pre', { class: 'mono', 'data-ai-query': '', text: JSON.stringify(res.query, null, 2) })));
  }

  // ---- the report page: ask the data, explain a number ----------------------------------------------------------------------------------------------------------
  function reportAsk() {
    const bar = document.querySelector('.bi-report-bar');
    if (!bar) { return; }
    const input = h('input', { type: 'text', class: 'studio-in', maxlength: '600', 'data-ai-q': '', 'aria-label': 'Ask the data a question', placeholder: 'Ask the data, for example: notional by desk' });
    const msg = h('p', { class: 'bi-msg', role: 'status', 'aria-live': 'polite', 'data-ai-msg': '' });
    const out = h('div', { 'data-ai-answer': '' });
    const go = h('button', { type: 'submit', class: 'btn-pill btn-accent' }, B.icon('stars'), ' Ask');
    const form = h('form', { class: 'bi-ai-form' }, h('label', { class: 'bi-tool' }, 'Ask the data', input), go);
    const sec = h('section', { class: 'bi-ai-ask', 'data-ai-ask': '', 'aria-label': 'Ask the data' }, form, msg, out);
    bar.parentNode.insertBefore(sec, bar.nextSibling);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const question = input.value.trim();
      if (!question) { return; }
      go.disabled = true;
      B.say(msg, 'Asking...');
      B.enqueue(() => B.api('POST', '/ai/ask', { report: canvas.getAttribute('data-item'), question })).then((res) => { B.say(msg, ''); showAnswer(out, res); })
        .catch((err) => { B.clear(out); fail(msg, err); }).then(() => { go.disabled = false; });
    });
  }

  let panel = null;
  function explainPanel() {
    if (panel) { return panel; }
    const body = h('div', { 'data-ai-explain-body': '' });
    const close = h('button', { type: 'button', class: 'btn-pill btn-ghost' }, 'Close');
    panel = h('section', { class: 'bi-ai-panel', 'data-ai-explain': '', role: 'dialog', 'aria-label': 'Explain this number', tabindex: '-1', hidden: '' },
      h('header', {}, h('h2', { text: 'Explain this number' }), close), body);
    close.addEventListener('click', () => { panel.hidden = true; });
    panel.addEventListener('keydown', (e) => { if (e.key === 'Escape') { panel.hidden = true; } });
    document.body.appendChild(panel);
    panel.body = body;
    return panel;
  }

  function showExplain(res) {
    const p = explainPanel();
    const b = B.clear(p.body);
    const l = res.lineage;
    b.appendChild(h('p', { class: 'bi-ai-value' }, h('strong', { text: res.measure }), ' = ', h('span', { 'data-ai-value': '', text: B.fmt(res.value, res.measure, false) })));
    const src = l.sources.map((s) => s.table + ' (' + s.kind + ', ' + s.connector + ')').join(', ');
    b.appendChild(h('ol', { class: 'bi-ai-lineage', 'data-ai-lineage': '' },
      h('li', { text: 'Dataset: ' + (l.dataset.title || l.dataset.name) }),
      h('li', { text: 'Measure ' + l.measure.name + ' = ' + (l.measure.expr || l.measure.python || '') }),
      h('li', { text: 'Reads: ' + (l.columns.map((c) => c.name).join(', ') || 'no column') }),
      h('li', { text: 'Sources: ' + src })));
    res.breakdown.forEach((d) => {
      const rows = d.members.map((m) => h('tr', {}, h('td', { text: String(m.member) }), h('td', { class: 'num', text: B.fmt(m.value, res.measure, false) }), h('td', { class: 'num', text: m.share == null ? '' : m.share + '%' })));
      b.appendChild(h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl', 'data-plain': '', 'data-ai-breakdown': d.dimension }, h('caption', { text: 'By ' + B.label(d.dimension) }),
        h('thead', {}, h('tr', {}, h('th', { scope: 'col', text: B.label(d.dimension) }), h('th', { scope: 'col', text: res.measure }), h('th', { scope: 'col', text: 'Share' }))), h('tbody', {}, rows))));
    });
    if (res.narrative) { b.appendChild(h('p', { class: 'bi-ai-summary', 'data-ai-summary': '', text: res.narrative })); }
    p.hidden = false;
    p.focus();
  }

  /** The Explain button of a visual's header: hidden until the AI is known to be on. `mk` is the report page's button maker. */
  ai.explainButton = function (spec, mk) {
    const btn = mk('bi-explain', 'lightbulb', 'Explain this number', () => {
      const v = B.reportView;
      const measure = (spec.wells && spec.wells.values && spec.wells.values[0]) || '';
      btn.disabled = true;
      B.enqueue(() => B.api('POST', '/ai/explain', { report: canvas.getAttribute('data-item'), page: v.page(), visual: spec.id, measure, state: v.state() })).then(showExplain)
        .catch((e) => { const p = explainPanel(); B.clear(p.body).appendChild(h('p', { class: 'bi-bad', role: 'alert', text: (e.code ? e.code + ' ' : '') + (e.detail || e.message) })); p.hidden = false; })
        .then(() => { btn.disabled = false; });
    }, { 'data-act': 'explain', hidden: '' });
    ai.on(() => { btn.hidden = !ai.enabled; });
    return btn;
  };

  // ---- the designer: describe a report --------------------------------------------------------------------------------------------------------------------
  function designer() {
    const D = B.design;
    const bar = document.querySelector('.bi-dactions');
    if (!D || !bar) { return; }
    const open = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ai-draft': '', hidden: '' }, B.icon('stars'), ' Describe a report');
    const text = h('textarea', { class: 'studio-in', rows: '3', maxlength: '600', 'data-ai-request': '', 'aria-label': 'Describe the report you want', placeholder: 'Describe the report: for example, notional by desk as a bar chart and the total as a card' });
    const msg = h('p', { class: 'bi-msg', role: 'status', 'aria-live': 'polite', 'data-ai-msg': '' });
    const yaml = h('textarea', { class: 'studio-in mono', rows: '14', readonly: '', 'data-ai-yaml': '', 'aria-label': 'The draft, as YAML', hidden: '' });
    const go = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-ai-go': '' }, 'Draft it');
    const use = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-ai-use': '', hidden: '' }, 'Use this draft');
    const close = h('button', { type: 'button', class: 'btn-pill btn-ghost' }, 'Close');
    const box = h('section', { class: 'bi-checkpanel bi-ai-box', 'data-ai-box': '', hidden: '', 'aria-label': 'Describe a report' }, h('h2', { text: 'Describe a report' }),
      h('p', { class: 'text-muted-d', text: 'The model sees the names, types and descriptions of this report’s dataset, never a row. Its draft is checked before you see it; it changes nothing until you use it and save.' }),
      text, h('p', {}, go, ' ', use, ' ', close), msg, yaml);
    bar.insertBefore(open, bar.firstChild);
    const anchor = document.querySelector('[data-check-box]');
    anchor.parentNode.insertBefore(box, anchor);
    ai.on(() => { open.hidden = !(ai.enabled && ai.draft); });
    open.addEventListener('click', () => { box.hidden = false; text.focus(); });
    close.addEventListener('click', () => { box.hidden = true; });
    go.addEventListener('click', () => {
      const request = text.value.trim();
      if (!request) { B.say(msg, 'Describe the report first.', true); return; }
      go.disabled = true;
      use.hidden = true;
      yaml.hidden = true;
      B.say(msg, 'Drafting...');
      B.api('POST', '/ai/draft', { dataset: D.model.dataset, request }).then((r) => {
        yaml.value = r.yaml;
        yaml.hidden = false;
        use.hidden = false;
        B.say(msg, 'The draft passed the report check. Nothing is saved.');
      }).catch((e) => fail(msg, e)).then(() => { go.disabled = false; });
    });
    use.addEventListener('click', () => { D.loadYaml(yaml.value); box.hidden = true; });
  }

  if (page === 'report') { ai.on(() => { if (ai.enabled) { reportAsk(); } }); }
  if (page === 'designer') { designer(); }
})();

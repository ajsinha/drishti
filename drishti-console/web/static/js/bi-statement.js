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
/* The statement designer of BI Studio (2.6.0, docs/guides/REPORTS.md section 5): a page with layout: statement is paper. The Paper tab of the designer
   sets the report's `statement:` (paper size, orientation, margins, the header and footer with their tokens, the period it covers, the text size) with
   plain controls, and previews the statement page by page as the server lays it out for you (POST /bi/api/reports/statement/preview: the same pages the
   PDF draws, with your own masks and row rules). Each band's page break, keep-together, repeated column titles and grouping are in Format, under
   Statement. Nothing here computes: the browser draws what the server laid out. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const D = B && B.design;
  if (!D) { return; }
  const h = B.h;
  const PAPERS = ['A4', 'A3', 'A5', 'Letter', 'Legal'];
  const PERIODS = [['none', 'none (the statement\'s date)'], ['day', 'the day'], ['month', 'the month of its date'], ['previous-month', 'the month before'],
    ['quarter', 'the quarter of its date'], ['previous-quarter', 'the quarter before'], ['year', 'the year of its date'], ['previous-year', 'the year before']];
  const SIDES = ['top', 'right', 'bottom', 'left'];
  const DEFAULTS = { header: { left: '{title}', right: '{period}' }, footer: { left: '{reader} · run {run-date}', right: 'Page {page} of {pages}' }, margins: { top: 18, right: 15, bottom: 18, left: 15 } };
  const TOKENS = '{title} {page} {pages} {run-date} {run-time} {period} {from} {to} {as-of} {reader} {param.<name>}';
  const S = { answer: null, at: 0, stale: true, shown: false };

  const isStatement = () => !!(D.model && D.page() && D.page().layout === 'statement');
  const settings = () => (D.model && D.model.statement) || {};

  /** Writes one key of `statement:` (an empty value removes it, and an empty `statement:` goes). */
  function put(key, value) {
    D.change('Statement ' + key, (m) => {
      const s = Object.assign({}, m.statement);
      if (value === undefined || value === '' || value === null) { delete s[key]; } else { s[key] = value; }
      if (Object.keys(s).length) { m.statement = s; } else { delete m.statement; }
    });
  }
  /** A part (left, center, right) of the header or the footer; the first change copies the default line so the other parts stay as they were. */
  function putLine(which, part, text) {
    const cur = Object.assign({}, settings()[which] || DEFAULTS[which]);
    if (text) { cur[part] = text; } else { delete cur[part]; }
    put(which, cur);
  }
  function putMargin(side, value) {
    const cur = Object.assign({}, typeof settings().margins === 'object' ? settings().margins : DEFAULTS.margins);
    const n = Number(value);
    if (value === '' || !(n >= 0 && n <= 60)) { delete cur[side]; } else { cur[side] = n; }
    put('margins', Object.keys(cur).length ? cur : undefined);
  }

  // ---- the settings ------------------------------------------------------------------------------------------------

  function row(label, ctl, hint) {
    return h('label', { class: 'bi-frow bi-st-row' }, h('span', { text: label }), ctl, hint ? h('small', { class: 'text-muted-d', text: hint }) : null);
  }
  function select(options, value, attrs, onChange) {
    const s = h('select', Object.assign({ class: 'studio-select' }, attrs), options.map((o) => h('option', { value: Array.isArray(o) ? o[0] : o, text: Array.isArray(o) ? o[1] : o })));
    s.value = value;
    s.addEventListener('change', () => onChange(s.value));
    return s;
  }
  function input(value, attrs, onChange) {
    const i = h('input', Object.assign({ class: 'studio-in', value: value == null ? '' : String(value) }, attrs));
    i.addEventListener('change', () => onChange(i.value.trim()));
    return i;
  }

  function form(host) {
    B.clear(host);
    const s = settings();
    host.appendChild(h('h3', { class: 'bi-st-h', text: 'Paper' }));
    host.appendChild(row('Size', select(PAPERS, s.paper || 'A4', { 'data-st-paper': '', 'aria-label': 'Paper size' }, (x) => put('paper', x === 'A4' ? undefined : x))));
    host.appendChild(row('Orientation', select([['portrait', 'portrait'], ['landscape', 'landscape']], s.orientation || 'portrait', { 'data-st-orientation': '', 'aria-label': 'Orientation' },
      (x) => put('orientation', x === 'portrait' ? undefined : x))));
    const m = typeof s.margins === 'object' ? s.margins : typeof s.margins === 'number' ? { top: s.margins, right: s.margins, bottom: s.margins, left: s.margins } : {};
    const margins = h('span', { class: 'bi-st-margins' }, SIDES.map((side) => input(m[side], { type: 'number', min: '0', max: '60', step: '1', placeholder: String(DEFAULTS.margins[side]),
      'data-st-margin': side, 'aria-label': 'Margin ' + side + ' in millimetres', title: side }, (x) => putMargin(side, x))));
    const mrow = row('Margins (mm: top, right, bottom, left)', margins);
    mrow.classList.add('bi-st-row-wide');
    host.appendChild(mrow);
    host.appendChild(row('Text size (pt)', input(s.fontSize, { type: 'number', min: '7', max: '12', placeholder: '9', 'data-st-font': '', 'aria-label': 'Text size in points' },
      (x) => put('fontSize', x === '' ? undefined : Math.max(7, Math.min(12, Number(x) || 9))))));
    ['header', 'footer'].forEach((which) => {
      host.appendChild(h('h3', { class: 'bi-st-h', text: which === 'header' ? 'Header' : 'Footer' }));
      const line = s[which] === false ? {} : (s[which] || DEFAULTS[which]);
      ['left', 'center', 'right'].forEach((part) => host.appendChild(row(part, input(line[part], { ['data-st-' + which]: part, 'aria-label': which + ' ' + part, placeholder: part === 'center' ? '' : '' },
        (x) => putLine(which, part, x)))));
    });
    host.appendChild(h('p', { class: 'text-muted-d bi-small-note', text: 'Tokens: ' + TOKENS + '. {page} and {pages} are counted once the statement is laid out.' }));
    host.appendChild(h('h3', { class: 'bi-st-h', text: 'Period' }));
    host.appendChild(row('Covers', select(PERIODS, s.period || 'none', { 'data-st-period': '', 'aria-label': 'Period the statement covers' }, (x) => put('period', x === 'none' ? undefined : x)),
      'Worked out from the statement\'s date: a subscription\'s business date, or the date below.'));
    host.appendChild(row('Its queries read', select([['end', 'the period\'s last day'], ['window', 'every day of the period']], s.dates || 'end', { 'data-st-dates': '', 'aria-label': 'Dates the queries read' },
      (x) => put('dates', x === 'end' ? undefined : x))));
    host.appendChild(h('p', { class: 'text-muted-d bi-small-note', text: 'Each band (a visual of this page) has Page break, Keep together, Repeat the column titles and Keep rows together by in Format, under Statement. A table or matrix runs over pages and has its row to itself.' }));
  }

  // ---- the preview -------------------------------------------------------------------------------------------------

  function previewArea(host) {
    B.clear(host);
    const date = h('input', { type: 'date', class: 'studio-in', 'data-st-date': '', 'aria-label': 'Statement date' });
    const go = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-st-preview': '' }, B.icon('file-earmark-richtext'), ' Preview pages');
    const prev = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-st-prev': '', 'aria-label': 'Previous page', disabled: true }, B.icon('chevron-left'));
    const next = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-st-next': '', 'aria-label': 'Next page', disabled: true }, B.icon('chevron-right'));
    const where = h('span', { class: 'bi-st-where', 'data-st-where': '', role: 'status', 'aria-live': 'polite', text: 'Not previewed yet.' });
    const files = h('span', { class: 'bi-st-files', 'data-st-files': '' });
    const sheet = h('div', { class: 'bi-st-stage', 'data-st-stage': '' });
    host.appendChild(h('div', { class: 'bi-st-bar' }, h('label', { class: 'bi-st-date' }, 'Statement date ', date), go, prev, where, next, files));
    host.appendChild(sheet);
    go.addEventListener('click', () => preview());
    prev.addEventListener('click', () => { if (S.at > 0) { S.at--; draw(); } });
    next.addEventListener('click', () => { if (S.answer && S.at < S.answer.pages.length - 1) { S.at++; draw(); } });
    host.addEventListener('keydown', (e) => {
      if (e.target.tagName === 'INPUT' || !S.answer) { return; }
      if (e.key === 'PageDown' || e.key === 'ArrowRight') { next.click(); e.preventDefault(); }
      if (e.key === 'PageUp' || e.key === 'ArrowLeft') { prev.click(); e.preventDefault(); }
    });
    // QA2-15: Page Down and Page Up page the preview while the Paper tab is shown, wherever the focus is (not only after a click on the
    // sheet), except in a field, where they keep their own meaning
    document.addEventListener('keydown', (e) => {
      if (!S.shown || !S.answer || host.contains(e.target) || e.defaultPrevented) { return; }
      if (/^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName) || e.target.isContentEditable || e.altKey || e.ctrlKey || e.metaKey) { return; }
      if (e.key === 'PageDown') { next.click(); e.preventDefault(); }
      if (e.key === 'PageUp') { prev.click(); e.preventDefault(); }
    });
    downloads(files);
  }

  /** PDF, Excel and JSON Lines of the saved draft (the server makes them as you, from what is saved): offered once the item exists. */
  function downloads(host) {
    B.clear(host);
    if (!D.itemId) { return; }
    const page = D.model && D.page() ? D.page().id : '';
    const base = '/dist/bi/' + B.id2path(D.itemId);
    host.appendChild(h('a', { class: 'btn-pill btn-ghost bi-small', href: base + '.pdf?page=' + encodeURIComponent(page), 'data-st-pdf': '', title: 'The saved draft as a PDF, made as you' }, B.icon('file-earmark-pdf'), ' PDF'));
    host.appendChild(h('a', { class: 'btn-pill btn-ghost bi-small', href: base + '.xlsx?page=' + encodeURIComponent(page), 'data-st-xlsx': '', title: 'The saved draft as Excel, set to print on this paper' }, B.icon('file-earmark-spreadsheet'), ' Excel'));
    host.appendChild(h('a', { class: 'btn-pill btn-ghost bi-small', href: base + '.jsonl?page=' + encodeURIComponent(page), 'data-st-jsonl': '', title: 'The saved draft\'s rows as JSON Lines, one a line, made as you' }, B.icon('braces'), ' JSONL'));
  }

  function preview() {
    const root = D.q('[data-st-preview-area]');
    const go = D.q('[data-st-preview]');
    const where = D.q('[data-st-where]');
    if (!isStatement()) { return Promise.resolve(); }
    go.disabled = true;
    where.textContent = 'Laying out the pages…';
    root.setAttribute('data-st-state', 'busy');
    const date = (D.q('[data-st-date]') || {}).value || '';
    return D.flushYaml().then((yaml) => B.api('POST', '/reports/statement/preview', { yaml, page: D.page().id, state: {}, date: date || undefined })).then((r) => {
      S.answer = r;
      S.at = Math.min(S.at, r.pages.length - 1);
      S.stale = false;
      draw();
      root.setAttribute('data-st-state', 'ready');
    }).catch((e) => {
      where.textContent = (e.code ? e.code + ' ' : '') + (e.detail || e.message);
      root.setAttribute('data-st-state', 'failed');
    }).finally(() => { go.disabled = false; });
  }

  /** Draws the page S.at as the server laid it out: each text, rule and fill at its place on a sheet of the paper's proportions. */
  function draw() {
    const r = S.answer;
    const stage = D.q('[data-st-stage]');
    if (!r || !stage) { return; }
    const page = r.pages[S.at];
    const room = Math.max(280, Math.min(stage.clientWidth || 640, 760) - 4);
    const scale = room / r.width;
    const sheet = h('div', { class: 'bi-st-sheet', 'data-st-sheet': '', 'data-st-page': String(page.number), role: 'img', 'aria-label': 'Page ' + page.number + ' of ' + r.pageCount });
    sheet.style.width = Math.round(r.width * scale) + 'px';
    sheet.style.height = Math.round(r.height * scale) + 'px';
    page.ops.forEach((o) => {
      let el;
      if (o.t === 'text') {
        el = h('span', { class: 'bi-st-text', 'data-role': o.role, text: o.text });
        el.style.left = (o.x * scale) + 'px';
        el.style.top = ((r.height - o.y - o.size * 0.82) * scale) + 'px';
        el.style.fontSize = (o.size * scale) + 'px';
        el.style.color = o.rgb;
        if (o.bold) { el.style.fontWeight = '700'; }
      } else if (o.t === 'rule') {
        el = h('div', { class: 'bi-st-rule' });
        el.style.left = (Math.min(o.x1, o.x2) * scale) + 'px';
        el.style.top = ((r.height - Math.max(o.y1, o.y2)) * scale) + 'px';
        el.style.width = Math.max(1, Math.abs(o.x2 - o.x1) * scale) + 'px';
        el.style.height = Math.max(1, Math.max(Math.abs(o.y2 - o.y1), o.w) * scale) + 'px';
        el.style.background = o.rgb;
      } else {
        el = h('div', { class: 'bi-st-fill' });
        el.style.left = (o.x * scale) + 'px';
        el.style.top = ((r.height - o.y - o.h) * scale) + 'px';
        el.style.width = (o.w * scale) + 'px';
        el.style.height = (o.h * scale) + 'px';
        el.style.background = o.rgb;
      }
      sheet.appendChild(el);
    });
    B.clear(stage).appendChild(sheet);
    const root = D.q('[data-st-preview-area]');
    root.setAttribute('data-st-pages', String(r.pageCount));
    root.setAttribute('data-st-at', String(page.number));
    // a date no data covers says so (QA2-12): the server's hint beside the page count, never an empty statement in silence
    D.q('[data-st-where]').textContent = 'Page ' + page.number + ' of ' + r.pageCount + ' · ' + r.paper.size + ' ' + r.paper.orientation + (r.period ? ' · ' + r.period : '') + (S.stale ? ' · changed since: preview again' : '')
      + (r.hint ? ' · ' + r.hint : '');
    if (r.hint) { root.setAttribute('data-st-hint', r.hint); } else { root.removeAttribute('data-st-hint'); }
    D.q('[data-st-prev]').disabled = S.at <= 0;
    D.q('[data-st-next]').disabled = S.at >= r.pages.length - 1;
  }

  // ---- the Paper tab -------------------------------------------------------------------------------------------------

  function syncTab() {
    const tab = D.q('[data-tab="paper"]');
    if (!tab) { return; }
    tab.hidden = !isStatement();
    if (!isStatement() && S.shown) { const c = D.q('[data-tab="canvas"]'); if (c) { c.click(); } }
  }

  D.modules.push(function () {
    const view = D.q('[data-view="paper"]');
    if (!view) { return; }
    const left = view.querySelector('[data-st-settings]');
    const right = view.querySelector('[data-st-preview-area]');
    previewArea(right);
    D.on('paper', (what) => {
      S.shown = what === 'show';
      if (!S.shown) { return; }
      form(left);
      downloads(D.q('[data-st-files]'));
      if (!S.answer || S.stale) { preview(); } else { draw(); }
    });
    D.on('model', (label) => {
      syncTab();
      if (label === 'show') { S.shown = false; return; }
      S.stale = true;
      if (S.shown && isStatement()) { form(left); if (S.answer) { draw(); } }
    });
    D.on('page', () => { S.answer = null; S.at = 0; S.stale = true; syncTab(); });
    syncTab();
  });
  B.statement = { preview, draw, isStatement };
})();

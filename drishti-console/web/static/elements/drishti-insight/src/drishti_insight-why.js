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
/* Why it moved as a page, layout A (the approved mock-up docs/lenses/mockups/var/why-it-moved.html; VAR_GUIDE.md section 3.11): the
   full Explain page opened at tab=why for a number whose explanation has steps (the server's why.answer: lines, states, steps,
   sentences). Top down: the compare line (this date against Previous day, 1 week, Month end or a picked date), four figures (the
   number on each date, the change, the tail scenario), the waterfall first and wide with, on its right, the clicked step's biggest
   contributors and its plain words, then Formula, Inputs & lineage and Checks folded below. Every figure is the server's; the browser
   only places it. A number whose explanation has no steps keeps the four-card page. */

/** True when an answer's why it moved can be drawn as layout A (it has the engine's steps). @param {Object} a @returns {boolean} */
export function whyPageFits(a) {
  const x = a && a.why;
  return !!(x && x.available && x.answer && Array.isArray(x.answer.lines) && x.answer.lines.length);
}

/** The compare date of a choice: '' (the previous business date, the server's), a week back, the month end before, or a date. */
export function compareDate(choice, to, picked) {
  if (choice === 'prev' || !to) { return ''; }
  if (choice === 'pick') { return picked || ''; }
  const d = new Date(to + 'T00:00:00Z');
  if (choice === 'week') { d.setUTCDate(d.getUTCDate() - 7); } else if (choice === 'month') { d.setUTCDate(0); }   // day 0: the month before's last
  return d.toISOString().slice(0, 10);
}

/** Which compare choice a from date is (for the chip shown on). */
function choiceOf(from, to, asked) {
  if (!asked) { return 'prev'; }
  if (asked === compareDate('week', to)) { return 'week'; }
  if (asked === compareDate('month', to)) { return 'month'; }
  return 'pick';
}

const CHOICES = [['prev', 'Previous day'], ['week', '1 week'], ['month', 'Month end'], ['pick', 'Pick a date…']];

/**
 * Draws layout A into the page's root.
 * @param {Element} root the [data-explain-page] element
 * @param {Object} a the server's answer (part=all)
 * @param {Object} k the Explain's own parts: h, part, checks, workbookHref, recompare(from) (asks again with another from date)
 * @param {string} asked the from date asked ('' for the previous business date)
 * @returns {Promise<void>}
 */
export function renderWhyPage(root, a, k, asked) {
  const h = k.h, x = a.why, ans = x.answer, doc = root.ownerDocument, win = doc.defaultView;
  const states = ans.states || [], first = states[0] || {}, last = states[states.length - 1] || {};
  const money = (v) => (v === null || v === undefined || isNaN(v) ? '—' : Math.round(v).toLocaleString('en-US'));
  const signed = (v) => (v > 0 ? '+' : v < 0 ? '−' : '') + money(Math.abs(v));
  const grid = root.querySelector('[data-dx-grid]');
  grid.textContent = '';
  grid.className = 'dx-why-page';
  root.dataset.layout = 'why-a';

  // ---- the compare line
  const cmp = h('div', 'dx-compare');
  cmp.setAttribute('data-dx-compare', '');
  cmp.appendChild(h('span', 'dx-muted', 'compare '));
  cmp.appendChild(h('b', 'mono', x.to));
  cmp.appendChild(h('span', 'dx-muted', ' with '));
  cmp.appendChild(h('b', 'mono', x.from));
  const chips = h('span', 'dx-chips');
  chips.setAttribute('role', 'group');
  chips.setAttribute('aria-label', 'Compare with');
  const on = choiceOf(x.from, x.to, asked);
  const pick = h('input', 'dx-pick');
  pick.type = 'date';
  pick.hidden = on !== 'pick';
  pick.value = on === 'pick' ? x.from : '';
  pick.setAttribute('aria-label', 'Compare with this business date');
  pick.setAttribute('data-dx-pick', '');
  CHOICES.forEach((c) => {
    const b = h('button', 'dx-chip' + (c[0] === on ? ' on' : ''), c[1]);
    b.type = 'button';
    b.dataset.dxCompare = c[0];
    b.setAttribute('aria-pressed', c[0] === on ? 'true' : 'false');
    b.addEventListener('click', () => {
      if (c[0] === 'pick') { pick.hidden = false; pick.focus(); return; }
      k.recompare(compareDate(c[0], x.to));
    });
    chips.appendChild(b);
  });
  pick.addEventListener('change', () => { if (pick.value) { k.recompare(compareDate('pick', x.to, pick.value)); } });
  chips.appendChild(pick);
  cmp.appendChild(chips);
  const I = win.Drishti && win.Drishti.elements && win.Drishti.elements.inputs;   // drishti-inputs' date picker (T-1, ME, 6 Nov 2026, the calendar)
  if (I && I.upgradeDateInput) { try { I.upgradeDateInput(pick); } catch (e) { /* the plain date field stays */ } }
  grid.appendChild(cmp);

  // ---- four figures
  const pct = ans.varFrom ? (ans.change / ans.varFrom * 100) : null;
  const kpis = h('div', 'dx-kpis');
  [[a.title.split(' ')[0] + ' ' + x.from, money(ans.varFrom), 'from'], [a.title.split(' ')[0] + ' ' + x.to, money(ans.varTo), 'to'],
   ['Change', signed(ans.change) + (pct === null ? '' : ' (' + (pct >= 0 ? '+' : '−') + Math.abs(pct).toFixed(1) + '%)'), 'change'],
   ['Tail scenario', last.varScenario ? String(last.varScenario) : '—', 'tail', first.varScenario ? 'was ' + first.varScenario : '']].forEach((f) => {
    const d = h('div', 'dx-kpi');
    d.dataset.dxKpi = f[2];
    d.appendChild(h('span', 'dx-muted', f[0]));
    const b = h('b', 'mono' + (f[2] === 'change' ? (ans.change > 0 ? ' dx-up' : ans.change < 0 ? ' dx-down' : '') : ''), f[1]);
    d.appendChild(b);
    if (f[3]) { d.appendChild(h('span', 'dx-muted dx-was', f[3])); }
    kpis.appendChild(d);
  });
  grid.appendChild(kpis);

  // ---- the waterfall (wide) and the clicked step's contributors with its words (right)
  const lines = ans.lines || [];
  const row = h('div', 'dx-why-row');
  const left = h('section', 'dx-card dx-why-wf');
  left.dataset.dxPart = 'why';
  left.appendChild(h('h2', null, 'Why it moved · steps that add up to the change'));
  const chart = h('div', 'dx-wf');
  chart.setAttribute('data-dx-waterfall', '');
  left.appendChild(chart);
  left.appendChild(h('p', 'dx-muted', 'Click a step to see its biggest contributors on the right.' + (ans.addsUp === false ? ' The steps do NOT add up: see the checks.' : '')));
  const right = h('section', 'dx-card dx-why-side');
  right.setAttribute('data-dx-side', '');
  row.appendChild(left);
  row.appendChild(right);
  grid.appendChild(row);
  const stepRows = [{ step: 'VaR ' + x.from.slice(5), value: ans.varFrom, total: 1, key: '' }]
    .concat(lines.map((l) => ({ step: l.label, value: l.effect, total: 0, key: l.key })))
    .concat([{ step: 'VaR ' + x.to.slice(5), value: ans.varTo, total: 1, key: '' }]);
  const panels = x.panels || [];
  function side(line) {
    right.textContent = '';
    right.dataset.step = line.key;
    right.appendChild(h('h2', null, 'Biggest contributors · ' + line.label));
    const keys = Array.isArray(line.steps) && line.steps.length ? line.steps : [line.key];
    const mine = panels.filter((p) => keys.some((kk) => p.id === 'drivers-' + kk || p.id === 'note-' + kk))
      .map((p) => Object.assign({}, p, { span: 12 }));
    const steps = (ans.steps || []).filter((s) => keys.indexOf(s.key) >= 0);
    const paint = h('div', 'dx-why');
    right.appendChild(paint);
    if (!mine.length) { paint.appendChild(h('p', 'dx-muted', 'No single contributor: ' + signed(line.effect) + ' for the whole scope.')); }
    const words = steps.map((s) => s.reads).filter(Boolean);
    const text = words.length ? words.join(' ') : (ans.sentences || [])[0] || '';
    if (text) {
      right.appendChild(h('h3', 'dx-h3', 'In words'));
      const p = h('p', 'dx-words', text);
      p.setAttribute('data-dx-words', '');
      right.appendChild(p);
    }
    if (!mine.length) { return Promise.resolve(); }
    return k.paint(paint, { title: line.label, from: x.from, to: x.to }, mine);
  }
  const biggest = lines.slice().sort((p, q) => Math.abs(q.effect) - Math.abs(p.effect))[0];
  const C = win.Drishti && win.Drishti.elements && win.Drishti.elements.chart;
  const drawn = new Promise((resolve) => {
    if (!(C && C.defineChartElement)) { chart.appendChild(h('p', 'dx-muted', 'The chart library is not on this page.')); resolve(); return; }
    C.defineChartElement(win.customElements, win);
    const el = doc.createElement('drishti-chart');
    el.setAttribute('density', 'terminal');
    el.style.display = 'block';
    chart.appendChild(el);
    el.addEventListener('drishti:mark-clicked', (e) => {
      const r = e.detail && e.detail.rows && e.detail.rows[0];
      const line = r && lines.find((l) => l.key === r.key);
      if (line) { side(line); }
    });
    el.addEventListener('drishti:rendered', () => resolve(), { once: true });
    el.options = { height: 260 };
    el.spec = { kind: 'waterfall', title: '', category: 'step', value: 'value', totals: { field: 'total' }, up: 'bad', down: 'good', onClick: 'select',
                data: { rows: stepRows } };
    if (el.mount) { el.mount(); }
    setTimeout(resolve, 3000);                      // drawn or not, the page goes on
  });
  const sideDone = biggest ? side(biggest) : Promise.resolve();

  // ---- Formula, Inputs & lineage and Checks, folded
  const folds = h('div', 'dx-folds');
  const jobs = [['formula', 'Formula'], ['inputs', 'Inputs & lineage (sources, versions, batches)']].map((t) => {
    const det = h('details', 'dx-fold');
    det.dataset.dxPart = t[0];
    det.id = 'dx-' + t[0];
    det.appendChild(h('summary', null, t[1]));
    const body = h('div', 'dx-fold-body');
    det.appendChild(body);
    folds.appendChild(det);
    return k.part(body, a, t[0], false);
  });
  const ck = h('details', 'dx-fold');
  ck.dataset.dxPart = 'checks';
  ck.appendChild(h('summary', null, 'Checks (open for audit)'));
  const ckBody = h('div', 'dx-fold-body');
  ck.appendChild(ckBody);
  k.checks(ckBody, a);
  if (ckBody.childNodes.length) { folds.appendChild(ck); }
  grid.appendChild(folds);
  return Promise.all(jobs.concat([drawn, sideDone])).then(() => undefined);
}

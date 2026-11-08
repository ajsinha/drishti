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
/* drishti-grid's document table without a page: the sort keys the console's tables use (drishti-core), what a tree shows with and
   without a query, a pager's words, heat buckets rounded half up, and the markup itself, element for element the same as the frozen
   renderer 1.2.0 drew (its tables.js, run here on a small stand-in document): plain tables, trees, drills, the pivot kind and the
   Data tables under charts. The behaviours on a real page are tested by the renderer's browser files. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';
import * as C from '../../web/static/elements/drishti-core/src/drishti_index.js';

// ---- a stand-in document: enough DOM for an element maker -------------------------------------------------------------------
class Node0 { constructor(t) { this.nodeType = t; this.childNodes = []; } appendChild(c) { if (c.nodeType === 11) { c.childNodes.forEach((x) => this.childNodes.push(x)); c.childNodes = []; } else { this.childNodes.push(c); } return c; } }
class El extends Node0 {
  constructor(tag) { super(1); this.tagName = tag.toUpperCase(); this.attrs = {}; }
  setAttribute(k, v) { this.attrs[k] = String(v); }
  getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; }
  set className(v) { this.attrs.class = v; }
  get className() { return this.attrs.class || ''; }
  set textContent(v) { this.childNodes = [new Text0(v)]; }
  get textContent() { return this.childNodes.map((c) => c.textContent).join(''); }
}
class Text0 extends Node0 { constructor(s) { super(3); this.data = String(s); } get textContent() { return this.data; } }
const doc = { createElement: (t) => new El(t), createTextNode: (s) => new Text0(s), createDocumentFragment: () => new Node0(11) };
globalThis.document = doc;
const ser = (n) => (n.nodeType === 3 ? JSON.stringify(n.data) : '<' + n.tagName + Object.keys(n.attrs).sort().map((k) => ' ' + k + '=' + JSON.stringify(n.attrs[k])).join('') + '>'
  + n.childNodes.map(ser).join('') + '</>');
const serAll = (x) => (Array.isArray(x) ? x.map(serAll).join('') : ser(x));
const old = await import('../../web/static/render/1.2.0/m/tables.js');
const cells = await import('../../web/static/render/1.2.0/m/cells.js');
const R = { server: '', outsideLinks: 'event' };
const hooks = { content: (c) => cells.cellText(R, c), rowAttrs: (r, i) => ({ 'data-r-row': String(i) }) };

const cell = (text, extra) => Object.assign({ text }, extra);
const PLAIN = { columns: ['Trade', 'Book', 'MTM'], numeric: [false, false, true],
  rows: [{ cells: [cell('IRS-1', { link: { kind: 'trade', id: 'IRS-1' } }), cell('RATES'), cell('−1,250', { tone: 'neg' })], highlight: true, path: '$.a' },
    { cells: [cell('IRS-2'), cell('https://example.com/x'), cell('3.2m', { tone: 'pos' })], ev: 'pick', pick: 'IRS-2' },
    { cells: [cell('OFF', { tone: 'inactive' }), cell(''), cell(null)] }],
  total: { cells: [cell('Total'), cell(''), cell('3.19m', { tone: 'pos' })] }, more: '12 more rows', search: false };
const TREE = { columns: ['Node', 'VaR'], numeric: [false, true], expand: 2,
  rows: [{ cells: [cell('BANK'), cell('10m')], children: [{ cells: [cell('RATES'), cell('6m')], children: [{ cells: [cell('IRS'), cell('4m')] }] }, { cells: [cell('FX'), cell('5m')] }] }] };
const DRILL = { id: 'pos', title: 'Positions', data: { columns: ['Desk', 'MTM'], numeric: [false, true], root: 'All desks',
  levels: [{ title: 'Desks' }, { title: 'Books', columns: ['Book', 'MTM'], numeric: [false, true] }, { title: 'Trades', columns: ['Trade', 'MTM'], numeric: [false, true] }],
  rows: [{ crumb: 'Rates', cells: [cell('Rates'), cell('5m', { tone: 'pos' })], kidsTotal: { cells: [cell('Total'), cell('5m')] },
    children: [{ crumb: 'B1', cells: [cell(''), cell('5m')], children: [{ crumb: 'T1', cells: [cell('T1', { link: { kind: 'trade', id: 'T1' } }), cell('5m')] }] }] },
  { crumb: 'FX', cells: [cell('FX'), cell('0')], children: [] }], total: { cells: [cell('Total'), cell('5m')] }, more: 'and 3 more' } };
const PIVOT = { by: 'desk', columns: ['2026-11-05', '2026-11-06'], heat: true, min: -10, max: 10, totals: [cell('1', { tone: 'pos' }), cell('2')], more: 3,
  rows: [{ label: 'Rates', cells: [cell('−10', { tone: 'neg' }), cell('2.5')], values: [-10, 2.5], total: cell('−7.5', { tone: 'neg' }) },
    { label: 'FX', group: true, path: ['FX'], cells: [cell('10'), cell('x')], values: [10, 'x'] }] };

test('the sort keys read amounts, signs, units, dates and text as the console does', () => {
  assert.deepEqual(C.textSortKey('−1,250.5'), { n: -1250.5 });
  assert.deepEqual(C.textSortKey('3.2m'), { n: 3.2e6 });
  assert.deepEqual(C.textSortKey('2 bn'), { n: 2e9 });
  assert.deepEqual(C.textSortKey('1tn'), { n: 1e12 });
  assert.deepEqual(C.textSortKey('5mm'), { n: 5e6 });
  assert.deepEqual(C.textSortKey('12.5%'), { n: 12.5 });
  assert.deepEqual(C.textSortKey('–40bp'), { n: -40 });
  assert.equal(C.textSortKey('2026-11-06').n, Date.parse('2026-11-06'));
  assert.deepEqual(C.textSortKey('Rates Desk'), { s: 'rates desk' });
  const shown = ['b', '10k', '−3', 'A', '2026-01-01', '2m', ''];
  const sorted = shown.slice().sort((a, b) => C.compareTextKeys(C.textSortKey(a), C.textSortKey(b)));
  assert.deepEqual(sorted, ['−3', '10k', '2m', '2026-01-01', '', 'a'.toUpperCase(), 'b']);
});

test('a pager says which rows it shows, or that none match', () => {
  assert.equal(G.pageInfo(60, 0, 25).text, '1–25 of 60');
  assert.deepEqual(G.pageInfo(60, 2, 25), { page: 2, pages: 3, first: 50, last: 60, text: '51–60 of 60' });
  assert.equal(G.pageInfo(60, 9, 25).page, 2);
  assert.deepEqual(G.pageInfo(0, 0, 25), { page: 0, pages: 1, first: 0, last: 0, text: 'No rows match' });
});

test('heat buckets run 0 to 9, half up, and 5 on a flat scale', () => {
  assert.equal(G.heatBucket(-10, -10, 10), 0);
  assert.equal(G.heatBucket(10, -10, 10), 9);
  assert.equal(G.heatBucket(0, -10, 10), 5);              // 4.5: half up (Python's half to even said 4)
  assert.equal(G.heatBucket(3, 3, 3), 5);
  assert.equal(G.heatBucket(1, 0, 4), 2);                 // 2.25
});

test('a plain table, a tree, a drill and a table offering Pivot are drawn as 1.2.0 drew them', () => {
  assert.equal(ser(G.docTable(doc, PLAIN, hooks)), ser(old.table(R, PLAIN)));
  assert.equal(ser(G.docTable(doc, Object.assign({}, PLAIN, { search: true }), hooks)), ser(old.table(R, Object.assign({}, PLAIN, { search: true }))));
  assert.equal(ser(G.docTable(doc, TREE, hooks)), ser(old.table(R, TREE)));
  assert.equal(ser(G.docDrill(doc, DRILL, hooks)), ser(old.drill(R, DRILL)));
  const pv = { id: 'p', title: 'Trades', data: Object.assign({ pivot: { source: 'panel/trade/T1/p' } }, PLAIN) };
  assert.equal(serAll(G.docPivotable(doc, pv, hooks)), serAll(old.pivotable(R, pv)));
  assert.equal(serAll(G.docPivotable(doc, { id: 'p', data: PLAIN }, hooks)), serAll(old.pivotable(R, { id: 'p', data: PLAIN })));
  assert.match(ser(G.docTable(doc, PLAIN, hooks)), /data-r-row="1"/);
});

test('the pivot kind is drawn as 1.2.0 drew it, heat classes by the half-up rule', () => {
  const ours = ser(G.docPivot(doc, PIVOT, hooks)), theirs = ser(old.pivot(R, PIVOT));
  assert.equal(ours.replace(/heat-\d/g, 'heat-#'), theirs.replace(/heat-\d/g, 'heat-#'));
  assert.match(ours, /heat-0/);
  const nested = Object.assign({ levels: ['desk', 'book'] }, PIVOT);
  assert.equal(ser(G.docPivot(doc, nested, hooks)).replace(/heat-\d/g, 'heat-#'), ser(old.pivot(R, nested)).replace(/heat-\d/g, 'heat-#'));
  assert.match(ser(G.docPivot(doc, nested, hooks)), /data-pv-tree=/);
});

test('the Data table under a chart: figures, a row heading drawn by the host, a cell of another class', () => {
  const t = G.docPlainTable(doc, ['Step', 'Amount (USD)'], [['Start', '1.0m'], ['End', '−2m']]);
  assert.equal(t.tagName, 'DETAILS');
  const s = ser(t);
  assert.match(s, /<SUMMARY>"Data"<\/>/);
  assert.match(s, /<TABLE class="tbl" data-plain="">/);
  assert.match(s, /<TH class="num" scope="col">"Amount \(USD\)"<\/>/);
  assert.match(s, /<TH scope="row">"Start"<\/><TD class="mono num">"1.0m"<\/>/);
  const sc = ser(G.docPlainTable(doc, ['Point', { text: 'Group', num: false }, 'x', 'y'], [[{ label: 'P1' }, { content: 'G1', cls: null }, '1', '2']],
    { rowHeader: (c) => doc.createTextNode('#' + c.label) }));
  assert.match(sc, /<TH scope="col">"Group"<\/>/);
  assert.match(sc, /<TH scope="row">"#P1"<\/><TD>"G1"<\/><TD class="mono num">"1"<\/>/);
  assert.equal(G.docPlainTable(doc, ['a', 'b'], [['x', '1']], { details: false }).attrs.class, 'tbl-wrap');
});

test('the Data tables are 1.2.0\'s chart Data tables, element for element (kinds.js dataTable and the scatter\'s)', async () => {
  const { h, str } = await import('../../web/static/render/1.2.0/m/dom.js');
  // kinds.js dataTable(headRow, rows), as 1.2.0 wrote it
  const dataTable = (headRow, rows) => h('details', { class: 'xchart-data' }, h('summary', null, 'Data'), h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl', 'data-plain': true },
    h('thead', null, h('tr', null, headRow.map((x, i) => h('th', { scope: 'col', class: i ? 'num' : null }, str(x))))),
    h('tbody', null, rows.map((r) => h('tr', null, r.map((c, i) => (i === 0 ? h('th', { scope: 'row' }, str(c)) : h('td', { class: 'mono num' }, str(c))))))))));
  const head = ['Date', 'Open', 'High'], rows = [['2026-11-05', '74.0', '75.5'], ['2026-11-06', '', '76']];
  assert.equal(ser(G.docPlainTable(doc, head, rows)), ser(dataTable(head, rows)));
  // kinds.js scatter's table: Point, an optional Group (no num), x and y; the point's heading may be an entity link
  const pts = [{ label: 'P1', link: { kind: 'company', id: 'C1' }, group: 'G', xText: '1.0', yText: '2.0' }, { xText: '3', yText: '4' }];
  for (const groups of [['G'], []]) {
    const d = { xLabel: 'Beta', yLabel: 'Return', groups };
    const theirs = h('details', { class: 'xchart-data' }, h('summary', null, 'Data'), h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl', 'data-plain': true },
      h('thead', null, h('tr', null, h('th', { scope: 'col' }, 'Point'), (d.groups && d.groups.length) ? h('th', { scope: 'col' }, 'Group') : null,
        h('th', { scope: 'col', class: 'num' }, str(d.xLabel)), h('th', { scope: 'col', class: 'num' }, str(d.yLabel)))),
      h('tbody', null, pts.map((q, i) => h('tr', null, h('th', { scope: 'row' }, cells.entity(R, q.label || (i + 1), q.link)),
        (d.groups && d.groups.length) ? h('td', null, str(q.group || '')) : null,
        h('td', { class: 'mono num' }, str(q.xText)), h('td', { class: 'mono num' }, str(q.yText))))))));
    const g = groups.length > 0;
    const ours = G.docPlainTable(doc, ['Point'].concat(g ? [{ text: 'Group', num: false }] : [], [{ text: d.xLabel, num: true }, { text: d.yLabel, num: true }]),
      pts.map((q, i) => [cells.entity(R, q.label || (i + 1), q.link)].concat(g ? [{ content: str(q.group || ''), cls: null }] : [], [q.xText, q.yText])));
    assert.equal(ser(ours), ser(theirs));
  }
});

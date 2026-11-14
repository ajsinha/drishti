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
/* drishti-inputs without a page (node --test): the holiday calendars and business dates, the range presets, typed dates, the tree's
   compact selection with lazy children and search, forms from a schema (validation in words, conditions, arrays, the four-eyes diff),
   the type-ahead's debounce, abort, cache and order, the filter bar's model and its bus, the code modes, completion and the line diff.
   The drawn components are tested in a browser against the harness (test_elements_inputs_browser.py). */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as I from '../../web/static/elements/drishti-inputs/src/drishti_index.js';
import * as C from '../../web/static/elements/drishti-core/src/drishti_index.js';

const require = createRequire(import.meta.url);
const tick = (ms) => new Promise((r) => setTimeout(r, ms || 0));

test('holiday calendars: the rules of a year (weekend rolls, nth weekdays, Easter), unions, a site\'s own dates', () => {
  const us = I.calendar('US');
  assert.deepEqual(us.holidays(2026).map((x) => x.date), ['2026-01-01', '2026-01-19', '2026-02-16', '2026-04-03', '2026-05-25', '2026-06-19', '2026-07-03', '2026-09-07', '2026-11-26', '2026-12-25']);
  assert.equal(us.holidayName('2026-07-03'), 'Independence Day');           // 4 July is a Saturday: the Friday
  assert.equal(I.easterSunday(2026), '2026-04-05');
  assert.equal(I.easterSunday(2027), '2027-03-28');
  const uk = I.calendar('UK');
  assert.deepEqual(uk.holidays(2027).filter((x) => x.date >= '2027-12-01').map((x) => x.date), ['2027-12-27', '2027-12-28']);   // both roll, one each
  assert.equal(I.calendar('CA').holidayName('2026-05-18'), 'Victoria Day');   // the Monday before 25 May
  assert.equal(I.calendar('TARGET').isBusinessDay('2026-05-01'), false);
  const both = I.calendar('US+UK');
  assert.match(both.holidayName('2026-08-31'), /UK/);
  assert.equal(both.isBusinessDay('2026-11-26'), false);
  const site = I.defineCalendar('DESK-X', { dates: [{ date: '2026-11-09', name: 'Desk offsite' }], weekend: [5, 6] });
  assert.equal(site.isBusinessDay('2026-11-06'), false);                    // Friday is a weekend day there
  assert.equal(site.isBusinessDay('2026-11-08'), true);                     // Sunday is not
  assert.equal(site.holidayName('2026-11-09'), 'Desk offsite');
  assert.throws(() => I.calendar('MARS'), /no calendar MARS/);
});

test('business dates: T-1, T-n, month-end, quarter-end, the previous month-end, business days between', () => {
  const us = I.calendar('US');
  assert.equal(us.tMinus('2026-11-27', 1), '2026-11-25');                   // Thanksgiving skipped
  assert.equal(us.tMinus('2026-11-30', 3), '2026-11-24');
  assert.equal(us.addBusinessDays('2026-11-25', 1), '2026-11-27');
  assert.equal(us.monthEnd('2026-05-10'), '2026-05-29');                    // the 31st is a Sunday
  assert.equal(us.quarterEnd('2026-02-10'), '2026-03-31');
  assert.equal(us.yearEnd('2026-06-01'), '2026-12-31');
  assert.equal(us.previousMonthEnd('2026-11-06'), '2026-10-30');
  assert.equal(us.previousMonthEnd('2026-10-30'), '2026-10-30');
  assert.equal(us.monthStart('2026-01-15'), '2026-01-02');
  assert.equal(us.businessDaysBetween('2026-11-20', '2026-11-30'), 5);
  assert.equal(us.businessDaysBetween('2026-11-30', '2026-11-20'), -5);
});

test('range presets from a business date, and what a person types', () => {
  const o = { asOf: '2026-11-06', calendar: 'US' };
  const p = (id) => { const r = I.resolvePreset(id, o); return [r.from, r.to]; };
  assert.deepEqual(p('today'), ['2026-11-06', '2026-11-06']);
  assert.deepEqual(p('t-1'), ['2026-11-05', '2026-11-05']);
  assert.deepEqual(p('wtd'), ['2026-11-02', '2026-11-06']);
  assert.deepEqual(p('mtd'), ['2026-11-02', '2026-11-06']);
  assert.deepEqual(p('qtd'), ['2026-10-01', '2026-11-06']);
  assert.deepEqual(p('ytd'), ['2026-01-02', '2026-11-06']);
  assert.deepEqual(p('last-5bd'), ['2026-11-02', '2026-11-06']);
  assert.deepEqual(p('prev-month'), ['2026-10-01', '2026-10-30']);
  assert.deepEqual(p('prev-quarter'), ['2026-07-01', '2026-09-30']);
  assert.deepEqual(p('prev-year'), ['2025-01-02', '2025-12-31']);
  assert.equal(I.DATE_PRESETS.length, 12);
  assert.throws(() => I.resolvePreset('nope', o), /no date preset/);
  const t = (s) => I.parseDateInput(s, o);
  assert.equal(t('T'), '2026-11-06');
  assert.equal(t('t-1'), '2026-11-05');
  assert.equal(t('T+2'), '2026-11-10');
  assert.equal(t('ME'), '2026-11-30');
  assert.equal(t('PME'), '2026-10-30');
  assert.equal(t('2026-2-3'), '2026-02-03');
  assert.equal(t('6 Nov 2026'), '2026-11-06');
  assert.equal(t('Nov 6, 2026'), '2026-11-06');
  assert.equal(t('2026-02-30'), null);
  assert.equal(t('soon'), null);
  const g = I.monthGrid(2026, 11, 1);
  assert.equal(g.length, 6);
  assert.equal(g[0][0].iso, '2026-10-26');                                   // the Monday of the week 1 Nov is in
  assert.equal(I.shiftDate('2026-01-31', 1, 'month'), '2026-02-28');
});

test('the tree: tri-state checks kept compact, unchecking under a checked parent, lazy children, search, single choice', async () => {
  let asked = 0;
  const t = new I.TreeModel({ nodes: [{ id: 'R', label: 'Rates', children: [{ id: 'R/S', label: 'Swaps', hasChildren: true }, { id: 'R/B', label: 'Bonds' }] }, { id: 'F', label: 'FX', hasChildren: true }],
    loadChildren: (n) => { asked++; return Promise.resolve([{ id: n.id + '/1', label: n.label + ' one' }, { id: n.id + '/2', label: n.label + ' two' }]); } });
  t.check('R/B');
  assert.equal(t.stateOf('R'), 'mixed');
  t.check('R/S');
  assert.deepEqual(t.getValue(), ['R']);                                     // both children: the parent
  await t.toggle('R/S', true);
  assert.equal(asked, 1);
  assert.equal(t.stateOf('R/S/1'), 'checked');                               // loaded under a checked node: checked
  t.check('R/S/1', false);
  assert.deepEqual(t.getValue(), ['R/B', 'R/S/2']);
  assert.equal(t.stateOf('R'), 'mixed');
  assert.deepEqual(t.getValue({ mode: 'leaves' }), ['R/B', 'R/S/2']);
  t.checkAll(true);
  assert.equal(t.summary(), 'Everything');
  t.checkAll(false);
  assert.equal(t.summary({ none: 'Every book' }), 'Every book');
  assert.equal(await t.search('two'), 1);
  assert.deepEqual(t.rows().map((r) => r.node.id), ['R', 'R/S', 'R/S/2']);
  await t.search('');
  const bad = new I.TreeModel({ nodes: [{ id: 'X', label: 'X', hasChildren: true }], loadChildren: () => Promise.reject(new Error('busy')) });
  await bad.toggle('X', true);
  assert.equal(bad.status.get('X'), 'Could not load: busy');
  const one = new I.TreeModel({ nodes: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }], multiple: false });
  one.check('a'); one.check('b');
  assert.deepEqual(one.getValue(), ['b']);
  assert.throws(() => new I.TreeModel({ nodes: [{ id: 'a', label: 'A' }, { id: 'a', label: 'B' }] }), /two nodes/);
});

const SCHEMA = { type: 'object', required: ['book', 'notional', 'tradeDate', 'maturity'], properties: {
  book: { type: 'string', pattern: '[A-Z]{2}-\\d{3}', patternMessage: 'a book code such as RT-123' },
  notional: { type: 'number', minimum: 1000 },
  product: { type: 'string', enum: ['SWAP', 'OPTION'], enumLabels: ['Swap', 'Option'] },
  strike: { type: 'number', when: { field: 'product', equals: 'OPTION' } },
  tradeDate: { type: 'date', title: 'Trade date' }, maturity: { type: 'date', after: 'tradeDate' },
  desks: { type: 'array', items: { type: 'string', enum: ['Rates', 'FX', 'Credit'] }, maxItems: 2 },
  legs: { type: 'array', minItems: 1, items: { type: 'object', required: ['pay'], properties: { pay: { type: 'string', enum: ['FIXED', 'FLOAT'] }, rate: { type: 'number', when: { field: 'pay', equals: 'FIXED' }, minimum: -5 } } } } } };

test('forms from a schema: messages in words, conditions, arrays, hidden fields left out, the four-eyes diff, reading typed numbers', () => {
  const v = { book: 'rt-1', notional: 500, product: 'SWAP', tradeDate: '2026-11-06', maturity: '2026-11-01', desks: ['Rates', 'FX', 'Credit'], legs: [{ pay: 'FIXED', rate: -9 }, { pay: 'FLOAT', rate: 99 }] };
  const r = I.validate(SCHEMA, v);
  assert.equal(r.ok, false);
  assert.equal(r.errors.book, 'Book must look like a book code such as RT-123');
  assert.equal(r.errors.notional, 'Notional must be at least 1,000');
  assert.equal(r.errors.maturity, 'Maturity must be after trade date (6 Nov 2026)');
  assert.equal(r.errors.desks, 'Pick at most 2 for desks (3 now)');
  assert.equal(r.errors['legs[0].rate'], 'Rate must be at least −5');
  assert.equal(r.errors['legs[1].rate'], undefined);                          // hidden (pay is FLOAT): not validated
  assert.equal(r.errors.strike, undefined);
  const r2 = I.validate(SCHEMA, Object.assign({}, v, { product: 'OPTION', legs: [], book: '' }));
  assert.equal(r2.errors.book, 'Book is required');
  assert.equal(r2.errors.legs, 'Pick at least 1 for legs (0 now)');
  assert.equal(I.validate(SCHEMA, { book: 'RT-123', notional: 2000, tradeDate: '2026-11-02', maturity: '2027-11-02', legs: [{ pay: 'FLOAT' }] }).ok, true);
  assert.deepEqual(I.visibleValue(SCHEMA, v).legs[1], { pay: 'FLOAT' });     // the hidden rate left out
  assert.equal('strike' in I.visibleValue(SCHEMA, Object.assign({}, v, { strike: 5 })), false);
  assert.deepEqual(I.changes({ a: 1, b: { c: 2 } }, { a: 1, b: { c: 3 }, d: 'x' }), [{ path: 'b.c', old: 2, value: 3 }, { path: 'd', old: undefined, value: 'x' }]);
  assert.deepEqual(I.defaults({ type: 'object', properties: { n: { type: 'number', default: 5 }, f: { type: 'boolean' }, l: { type: 'array', minItems: 1, items: { type: 'object', properties: { x: { type: 'string', default: 'a' } } } } } }), { n: 5, f: false, l: [{ x: 'a' }] });
  assert.deepEqual(I.setPath({ legs: [{ pay: 'A' }] }, 'legs[0].rate', 2), { legs: [{ pay: 'A', rate: 2 }] });
  assert.equal(I.getPath({ a: [{ b: 7 }] }, 'a[0].b'), 7);
  assert.equal(I.conditionHolds({ any: [{ field: 'x', gt: 5 }, { not: { field: 'y', truthy: true } }] }, { x: 1, y: 0 }, 'z'), true);
  assert.equal(I.words('tradeDate'), 'Trade date');
  assert.equal(I.words('TRADE_DATE'), 'Trade date');
  assert.deepEqual(I.parseField({ type: 'number' }, '1.25m'), { value: 1250000, error: null });
  assert.match(I.parseField({ type: 'number' }, 'lots', 'Notional').error, /Notional must be a number/);
  assert.equal(C.parseNumberText('(2,000)'), -2000);
  assert.equal(C.parseNumberText('−5k'), -5000);
  assert.equal(C.parseNumberText('12.5%'), 0.125);
  assert.equal(C.parseNumberText('1.250.000,5', 'de-DE'), 1250000.5);
  assert.equal(C.parseNumberText('1,2,3x'), null);
});

test('type-ahead: debounced, a minimum length, the request replaced aborted, a late answer dropped, the cache, an error', async () => {
  const calls = [], results = [];
  const src = (q, signal) => { calls.push(q); return new Promise((resolve, reject) => { const t = setTimeout(() => resolve([q + ' Bank', q + ' Capital']), q === 'ac' ? 40 : 5); signal.addEventListener('abort', () => { clearTimeout(t); const e = new Error('aborted'); e.name = 'AbortError'; reject(e); }); }); };
  const ta = new I.TypeAhead({ source: src, debounceMs: 10, minChars: 2, onResult: (r) => results.push(r) });
  ta.query('a');
  assert.equal(results.pop().short, true);
  ta.query('ac');
  await tick(15);
  ta.query('acm');                                                           // replaces 'ac' in flight
  await tick(60);
  assert.deepEqual(calls, ['ac', 'acm']);
  const done = results.filter((r) => !r.loading);
  assert.equal(done.length, 1);
  assert.equal(done[0].query, 'acm');
  results.length = 0;
  ta.query('acm');                                                           // from the cache, no call
  assert.equal(calls.length, 2);
  assert.equal(results[0].options[0].label, 'acm Bank');
  const bad = new I.TypeAhead({ source: () => Promise.reject(new Error('down')), debounceMs: 0, onResult: (r) => results.push(r) });
  bad.query('x');
  await tick(5);
  assert.equal(results.pop().error.message, 'down');
  const local = new I.TypeAhead({ source: ['Rates', 'FX swaps', 'Credit'], onResult: (r) => results.push(r) });
  local.query('sw');
  assert.deepEqual(results.pop().options.map((o) => o.label), ['FX swaps']);
  assert.deepEqual(I.searchOptions(I.normalizeOptions(['FX swaps', 'Swaps', 'Basis swap']), 'swa').map((o) => o.label), ['Swaps', 'FX swaps', 'Basis swap']);
});

test('the filter bar\'s model: chips in words, members, conditions read loosely, a date preset, the payload a grid follows', () => {
  const m = new I.FilterModelBar({ fields: [{ id: 'DESK', label: 'Desk' }, { id: 'MTM', label: 'MTM', type: 'number' }, { id: 'DAY', label: 'Day', type: 'date' }, { id: 'BOOK', label: 'Book', type: 'text' }] });
  m.setMembers('DESK', ['Rates', 'FX']);
  assert.equal(m.setCondition('MTM', 'gt', '1.5m'), null);
  assert.equal(m.setCondition('MTM', 'between', '5k', 'nope'), 'Write a number such as 1,000,000 or 1m');
  assert.equal(m.setDates('DAY', { preset: 'mtd', asOf: '2026-11-06', calendar: 'US' }), null);
  m.setCondition('BOOK', 'startsWith', 'Sw');
  assert.deepEqual(m.chips().map((c) => c.text), ['Desk in Rates, FX', 'MTM > 1500000', 'Day 2–6 Nov 2026 · Month to date', 'Book starts with "Sw"']);
  const p = m.payload();
  assert.deepEqual(p.model.DAY, { type: 'date', op: 'between', value: '2026-11-02', valueTo: '2026-11-06', preset: 'mtd' });
  const rows = [{ DESK: 'Rates', MTM: 2e6, DAY: '2026-11-03', BOOK: 'Swaps' }, { DESK: 'FX', MTM: 1e6, DAY: '2026-11-03', BOOK: 'Swaps' }, { DESK: 'Rates', MTM: 3e6, DAY: '2026-10-03', BOOK: 'Swaps' }];
  assert.equal(C.filterRows(rows, p.model).length, 1);                       // what a chart does with the payload
  m.setMembers('DESK', []);
  assert.equal(m.chips().length, 3);
  m.clear();
  assert.deepEqual(m.payload(), { model: {}, quickFilter: '' });
  assert.equal(C.FILTER_CHANNEL, 'filters:changed');
});

test('code: the Rachana and YAML modes, completion from a catalogue, the text put in, the line diff side by side', () => {
  const line = I.tokens('rachana', 'if(var(99, 1d) > 1.5m, "breach", [Desk]) // note')[0];
  const style = (t) => (line.find((x) => x.text.trim() === t) || {}).style;
  assert.equal(style('if'), 'builtin');
  assert.equal(style('1.5m'), 'number');
  assert.equal(style('"breach"'), 'string');
  assert.equal(style('[Desk]'), 'variable-2');
  assert.equal(style('// note'), 'comment');
  const y = I.tokens('yaml', 'title: Desk # a note\nfilter: "@desk"\n  - $positions');
  assert.equal(y[0][0].style, 'keyword');
  assert.equal(y[0][y[0].length - 1].style, 'comment');
  assert.equal(y[2].find((t) => t.text === '$positions').style, 'variable-2');
  assert.deepEqual(I.tokens('python', 'x = 1'), [[{ text: 'x = 1', style: null }]]);
  assert.deepEqual(I.wordAt('round(sum(NOT', 13), { word: 'NOT', from: 10, to: 13 });
  const cat = [{ name: 'stressed_var' }, { name: 'var' }, { name: 'variance' }, { name: 'covar' }, { name: 'NOTIONAL', kind: 'field' }];
  assert.deepEqual(I.completions(cat, 'var').map((e) => e.name), ['var', 'variance', 'stressed_var', 'covar']);
  assert.deepEqual(I.insertText({ name: 'sum' }), { text: 'sum()', caret: 4 });
  assert.deepEqual(I.insertText({ name: 'NOTIONAL', kind: 'field' }), { text: 'NOTIONAL', caret: 8 });
  assert.deepEqual(I.insertText({ name: 'if', insert: 'if(|, , )' }), { text: 'if(, , )', caret: 3 });
  const ops = I.lineDiff('a\nb\nc\nd', 'a\nB\nc\nd\ne');
  assert.deepEqual(ops.map((o) => o.op), ['equal', 'delete', 'insert', 'equal', 'equal', 'insert']);
  assert.deepEqual(I.diffStats(ops), { added: 2, removed: 1, same: 3 });
  const side = I.sideBySide(ops);
  assert.deepEqual(side[1], { left: { n: 2, text: 'b', kind: 'changed' }, right: { n: 2, text: 'B', kind: 'changed' } });
  assert.equal(side[side.length - 1].left, null);
  assert.equal(I.lineDiff('same', 'same').every((o) => o.op === 'equal'), true);
  const big = Array.from({ length: 3000 }, (_, i) => 'line ' + i).join('\n'), big2 = big.replace('line 1500', 'line 1500 changed');
  const t0 = Date.now(), d = I.lineDiff(big, big2);
  assert.equal(I.diffStats(d).added, 1);
  assert.ok(Date.now() - t0 < 500);
});

test('the bundle: drishti-inputs is a CommonJS module under Node, registered beside drishti-core, with every name', () => {
  const B = require('../../web/static/elements/drishti-inputs/drishti_inputs.js');
  assert.equal(B.library, 'drishti-inputs');
  ['DatePicker', 'DateRangePicker', 'TreePicker', 'MultiSelect', 'Combobox', 'Select', 'Form', 'FilterBar', 'CodeEditor', 'CodeDiff', 'HolidayCalendar', 'TypeAhead', 'defineInputElements', 'upgradeDateInput', 'upgradeCheckboxes', 'upgradeFields'].forEach((n) => assert.equal(typeof B[n], 'function', n));
  assert.equal(B.calendar('US').tMinus('2026-11-27', 1), '2026-11-25');
});

test('the filter bar: a list field takes contains, any of and all of, its values separated by commas', () => {
  const m = new I.FilterModelBar({ fields: [{ id: 'TAGS', label: 'Tags', type: 'list' }] });
  assert.equal(m.setCondition('TAGS', 'anyOf', 'fx, rates ,'), null);
  assert.deepEqual(m.model.TAGS, { type: 'list', op: 'anyOf', values: ['fx', 'rates'] });
  m.setCondition('TAGS', 'has', 'fx, rates');
  assert.deepEqual(m.model.TAGS, { type: 'list', op: 'has', values: ['fx'] });
  assert.equal(m.chips()[0].text, 'Tags contains fx');
  m.setCondition('TAGS', 'allOf', '');
  assert.equal(m.model.TAGS, undefined);
});

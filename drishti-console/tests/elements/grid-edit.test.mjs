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
/* drishti-grid's cell editing without a page: which cells may change, text read by type and locale, validation in words, one
   transaction per edit (cellValueChanged and editTransaction), undo and redo, a paste from Excel (quoted cells, a value filling a range,
   cells that cannot change skipped), Delete over a range, the write-back hook refusing (the old values back) and four eyes (cells marked
   until a proposal is decided), and the grid following a filter bar's model on a bus. The drawn editors are tested in a browser. */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as G from '../../web/static/elements/drishti-grid/src/drishti_index.js';
import * as C from '../../web/static/elements/drishti-core/src/drishti_index.js';

const tick = (ms) => new Promise((r) => setTimeout(r, ms || 0));
const COLS = [{ field: 'id' }, { field: 'desk', editable: true, options: ['Rates', 'FX'] }, { field: 'qty', header: 'Quantity', type: 'number', editable: true, required: true, min: 0 },
  { field: 'day', header: 'Day', type: 'date', editable: true }, { field: 'live', type: 'boolean', editable: true }, { field: 'note', editable: (r) => r.live, maxLength: 5 },
  { field: 'mtm', type: 'number' }];
const rows = () => [{ id: 'A', desk: 'Rates', qty: 10, day: '2026-11-02', live: true, note: '', mtm: 1 }, { id: 'B', desk: 'FX', qty: 20, day: '2026-11-03', live: false, note: '', mtm: 2 },
  { id: 'C', desk: 'FX', qty: 30, day: '2026-11-04', live: true, note: '', mtm: 3 }];
const grid = (o) => G.createGridModel(Object.assign({ columns: COLS, rows: rows(), getRowId: (r) => r.id }, o));

test('text read as values: numbers in a locale and loosely, dates, yes and no, a select\'s options; the editor per type', () => {
  const col = (o) => G.ColumnModel ? Object.assign({ header: 'X', type: 'text' }, o) : o;
  assert.deepEqual(G.parseCellText(col({ type: 'number' }), '1,250,000.5'), { value: 1250000.5, error: null });
  assert.deepEqual(G.parseCellText(col({ type: 'number' }), '1.250.000,5', { locale: 'de-DE' }), { value: 1250000.5, error: null });
  assert.equal(G.parseCellText(col({ type: 'number' }), '(2.5k)').value, -2500);
  assert.match(G.parseCellText(col({ type: 'number', header: 'Quantity' }), 'ten').error, /Quantity: "ten" is not a number/);
  assert.equal(G.parseCellText(col({ type: 'date' }), '6 Nov 2026').value, '2026-11-06');
  assert.equal(G.parseCellText(col({ type: 'date' }), '06/11/2026').value, '2026-11-06');
  assert.equal(G.parseCellText(col({ type: 'date' }), '11/06/2026', { locale: 'en-US' }).value, '2026-11-06');
  assert.match(G.parseCellText(col({ type: 'date' }), '2026-02-30').error, /not a date/);
  assert.equal(G.parseCellText(col({ type: 'boolean' }), 'yes').value, true);
  assert.equal(G.parseCellText(col({ options: [{ value: 'R', label: 'Rates' }] }), 'rates').value, 'R');
  assert.match(G.parseCellText(col({ options: ['Rates'] }), 'Equity').error, /not one of Rates/);
  assert.equal(G.parseCellText(col({}), '').value, null);
  assert.deepEqual(['text', 'number', 'date', 'checkbox', 'select'], [G.editorOf({ type: 'text' }), G.editorOf({ type: 'number' }), G.editorOf({ type: 'date' }), G.editorOf({ type: 'boolean' }), G.editorOf({ type: 'text', options: ['a'] })]);
  assert.equal(G.editorOf({ type: 'text', editor: 'code' }), 'code');                       // a formula: drishti-inputs' one-line CodeEditor when it is on the page
  assert.equal(G.parseCellText({ header: 'F', type: 'text', editor: 'code' }, ' sum(t.MTM) ').value, 'sum(t.MTM)');
  assert.equal(G.validateCell({ header: 'Quantity', required: true }, null, {}), 'Quantity is required');
  assert.equal(G.validateCell({ header: 'Quantity', min: 0 }, -1, {}), 'Quantity must be at least 0');
  assert.equal(G.validateCell({ header: 'Note', maxLength: 3 }, 'long', {}), 'Note may have at most 3 characters');
  assert.equal(G.validateCell({ header: 'Q', validate: (v, r) => (v > r.cap ? 'over the cap of ' + r.cap : null) }, 9, { cap: 5 }), 'over the cap of 5');
  assert.equal(G.validateCell({ header: 'Q', validate: () => false }, 9, {}), 'Q is not valid');
  assert.equal(C.numberEditText(-1234.5), '−1234.5');
  assert.equal(C.numberEditText(1234.5, 'de-DE'), '1234,5');
});

test('tab-separated text from Excel: cells, quoted cells with tabs, new lines and quotes, Windows line ends, the last line break', () => {
  assert.deepEqual(G.parseTsv('a\tb\r\nc\td\r\n'), [['a', 'b'], ['c', 'd']]);
  assert.deepEqual(G.parseTsv('"x\ty"\t"say ""hi"""\n"two\nlines"\tz'), [['x\ty', 'say "hi"'], ['two\nlines', 'z']]);
  assert.deepEqual(G.parseTsv('5'), [['5']]);
  assert.deepEqual(G.parseTsv('a\t\tc'), [['a', '', 'c']]);
  assert.deepEqual(G.parseTsv(''), []);
});

test('an edit is a transaction: which cells may change, validation refuses the whole, events, undo and redo', async () => {
  const g = grid(), ev = [];
  ['cellValueChanged', 'editTransaction', 'editUndone', 'editRedone'].forEach((n) => g.on(n, (d) => ev.push([n, d])));
  assert.equal(g.editing.canEdit(0, 0), false);                               // id: not editable
  assert.equal(g.editing.canEdit(0, 2), true);
  assert.equal(g.editing.canEdit(1, 5), false);                               // note: only while live
  assert.equal(g.editing.canEdit(0, 6), false);
  const tx = await g.editing.commit([{ r: 0, c: 2, value: 15 }, { r: 0, c: 1, value: 'FX' }]);
  assert.equal(tx.status, 'applied');
  assert.equal(tx.edits.length, 2);
  assert.equal(g.rowModel.getRowById('A').qty, 15);
  assert.equal(g.rowModel.getRowById('A').desk, 'FX');
  assert.equal(g.editing.markOf('A', 'qty'), 'edited');
  assert.deepEqual(ev.map((x) => x[0]), ['cellValueChanged', 'cellValueChanged', 'editTransaction']);
  await assert.rejects(g.editing.commit([{ r: 1, c: 2, value: -5 }, { r: 2, c: 2, value: 1 }]), (e) => e.problems[0].message === 'Quantity must be at least 0');
  assert.equal(g.rowModel.getRowById('C').qty, 30);                            // nothing applied
  await assert.rejects(g.editing.commit([{ r: 0, c: 5, value: 'too long' }]), /note may have at most 5/i);
  await g.editing.undo();
  assert.equal(g.rowModel.getRowById('A').qty, 10);
  assert.equal(g.rowModel.getRowById('A').desk, 'Rates');
  assert.equal(g.editing.markOf('A', 'qty'), null);
  assert.deepEqual(g.editing.history(), { undo: 0, redo: 1 });
  await g.editing.redo();
  assert.equal(g.rowModel.getRowById('A').qty, 15);
  assert.deepEqual(g.editing.history(), { undo: 1, redo: 0 });
  assert.equal(await g.editing.undo().then(() => g.editing.undo()), null);    // nothing more to undo
  g.setSortModel([{ colId: 'qty', sort: 'desc' }]);
  await g.editing.commit([{ r: 2, c: 2, value: 99 }]);                       // the smallest becomes the largest: re-sorted in place
  assert.equal(g.rowAt(0).id, 'A');
  assert.equal(g.setCellValue(0, 0, 'x'), false);
  assert.equal(g.setCellValue(0, 2, 7), true);
  const pivot = grid();
  pivot.wells.setState({ rows: ['desk'], columns: [], values: [{ colId: 'qty', aggFunc: 'sum' }], filters: [] });
  await pivot.setPivotMode(true);
  assert.equal(pivot.editing.canEdit(1, 1), false);                           // a pivot's figures are computed
});

test('paste from Excel: a block from the focus, a value filling a range, cells that cannot change skipped, bad values listed; Delete', async () => {
  const g = grid();
  const res = await g.editing.paste('FX\t11\t2026-11-09\nRates\t"1,200"\t9 Nov 2026\nFX\tlots\t2026-11-10\n', { r: 0, c: 1 });
  assert.equal(res.pasted, 7);                                                // C was FX already: no change
  assert.equal(res.problems.length, 1);
  assert.match(res.problems[0].message, /"lots" is not a number/);
  assert.equal(g.rowModel.getRowById('B').qty, 1200);
  assert.equal(g.rowModel.getRowById('B').day, '2026-11-09');
  assert.equal(res.transaction.source, 'paste');
  await g.editing.undo();
  assert.equal(g.rowModel.getRowById('B').qty, 20);                            // the paste undone as one
  const fill = await g.editing.paste('7', { r: 0, c: 2, r2: 2, c2: 2 });
  assert.equal(fill.pasted, 3);
  assert.deepEqual(['A', 'B', 'C'].map((id) => g.rowModel.getRowById(id).qty), [7, 7, 7]);
  const skip = await g.editing.paste('x\ty', { r: 1, c: 5 });                 // note on a row that is not live, then mtm
  assert.equal(skip.skipped, 2);
  assert.equal(skip.transaction, null);
  const cl = await g.editing.clear({ r1: 0, c1: 1, r2: 0, c2: 3 });
  assert.equal(cl.problems[0].message, 'Quantity is required');                // a required cell stays
  assert.equal(g.rowModel.getRowById('A').desk, null);
  assert.equal(g.rowModel.getRowById('A').day, null);
});

test('write-back: the server hears each transaction; a refusal puts the old values back; four eyes marks cells until decided', async () => {
  const heard = [];
  const g = grid({ editing: { writeBack: (tx) => { heard.push(tx); return tx.edits[0].value === 666 ? { status: 'refused', message: 'limit breached' } : tx.edits[0].value === 777 ? Promise.reject(new Error('offline')) : { status: 'applied' }; } } });
  await g.editing.commit([{ r: 0, c: 2, value: 12 }]);
  assert.equal(heard[0].edits[0].old, 10);
  await assert.rejects(g.editing.commit([{ r: 0, c: 2, value: 666 }]), /limit breached/);
  assert.equal(g.rowModel.getRowById('A').qty, 12);
  await assert.rejects(g.editing.commit([{ r: 0, c: 2, value: 777 }]), /offline/);
  assert.equal(g.rowModel.getRowById('A').qty, 12);
  assert.equal(g.editing.history().undo, 1);                                   // the refused ones are not undoable
  const decided = [];
  const f = grid({ editing: { fourEyes: true, writeBack: () => ({ status: 'proposed', proposalId: 'P-9' }) } });
  f.on('proposalResolved', (d) => decided.push(d.decision));
  const tx = await f.editing.commit([{ r: 1, c: 2, value: 55 }]);
  assert.equal(tx.status, 'proposed');
  assert.equal(tx.proposalId, 'P-9');
  assert.equal(f.editing.markOf('B', 'qty'), 'proposed');
  assert.equal(f.editing.canEdit(1, 2), false);                                // waiting: not editable again
  assert.equal(f.editing.resolveProposal('P-9', 'rejected'), true);
  assert.equal(f.rowModel.getRowById('B').qty, 20);
  assert.equal(f.editing.markOf('B', 'qty'), null);
  const t2 = await f.editing.commit([{ r: 1, c: 2, value: 56 }]);
  f.editing.resolveProposal(t2.proposalId, 'approved');
  assert.equal(f.editing.markOf('B', 'qty'), 'edited');
  assert.deepEqual(decided, ['rejected', 'approved']);
  assert.equal(f.editing.resolveProposal('nope', 'approved'), false);
});

test('a grid follows a filter bar\'s model on a bus: its own columns only, the quick filter too, a source picked', async () => {
  const bus = C.createBus(), g = grid();
  const off = g.followFilters(bus, { source: 'bar1' });
  bus.emit(C.FILTER_CHANNEL, { source: 'bar1', model: { desk: { type: 'set', values: ['FX'] }, REGION: { type: 'set', values: ['EMEA'] } }, quickFilter: '' });
  assert.equal(g.rowCount(), 2);
  assert.deepEqual(Object.keys(g.filterModel), ['desk']);
  bus.emit(C.FILTER_CHANNEL, { source: 'other', model: {}, quickFilter: '' });
  assert.equal(g.rowCount(), 2);                                               // another bar: not followed
  bus.emit(C.FILTER_CHANNEL, { source: 'bar1', model: {}, quickFilter: 'rates' });
  assert.equal(g.rowCount(), 1);
  off();
  bus.emit(C.FILTER_CHANNEL, { source: 'bar1', model: {}, quickFilter: '' });
  assert.equal(g.rowCount(), 1);
  await tick(1);
});

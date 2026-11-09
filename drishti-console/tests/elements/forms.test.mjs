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
/* drishti-inputs' forms without a page (node --test): typed times and dates with times, the time slots, the form types time, datetime
   and money with their checks and currencies, and dependent lists. The drawn Form (live mode, steps, names, hooks, custom widgets) is
   tested in a browser against the harness and on the console's screens. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as I from '../../web/static/elements/drishti-inputs/src/drishti_index.js';

const require = createRequire(import.meta.url);

test('a file drop takes only the endings it is given', () => {
  assert.equal(I.acceptedName('a.tar.gz', ['.jsonl', '.tar.gz']), true);
  assert.equal(I.acceptedName('A.JSONL', ['.jsonl']), true);
  assert.equal(I.acceptedName('a.exe', ['.jsonl']), false);
  assert.equal(I.acceptedName('anything', []), true);
});

test('times and dates with times: typed times, slots', () => {
  assert.equal(I.parseTime('14:05'), '14:05');
  assert.equal(I.parseTime('1405'), '14:05');
  assert.equal(I.parseTime('905'), '09:05');
  assert.equal(I.parseTime('2pm'), '14:00');
  assert.equal(I.parseTime('2:30 pm'), '14:30');
  assert.equal(I.parseTime('12am'), '00:00');
  assert.equal(I.parseTime('noon'), '12:00');
  assert.equal(I.parseTime('25:00'), null);
  assert.equal(I.parseTime('13pm'), null);
  assert.equal(I.parseDateTime('2026-12-31T17:00'), '2026-12-31T17:00');
  assert.equal(I.parseDateTime('2026-12-31 5pm'), '2026-12-31T17:00');
  assert.equal(I.parseDateTime('2026-12-31T17:00:30Z'), '2026-12-31T17:00');
  assert.equal(I.parseDateTime('2026-12-31'), '2026-12-31T00:00');
  assert.equal(I.parseDateTime('2026-13-31 10:00'), null);
  assert.deepEqual(I.timeSlots(60, '09:00', '12:00'), ['09:00', '10:00', '11:00', '12:00']);
  assert.equal(I.timeSlots(15).length, 96);
});

test('the form types time, datetime and money: their checks in words, typed values, a currency fixed or from another field', () => {
  const schema = { type: 'object', required: ['at'], properties: {
    at: { type: 'time', minimum: '08:00' }, when: { type: 'datetime', after: 'start' }, start: { type: 'date' },
    fee: { type: 'money', currency: 'ccy', minimum: 0 }, ccy: { type: 'string', enum: ['USD', 'EUR'] } } };
  assert.equal(I.validate(schema, {}).errors.at, 'At is required');
  assert.equal(I.validate(schema, { at: '07:30' }).errors.at, 'At must be at or after 08:00');
  assert.equal(I.validate(schema, { at: '09:00', start: '2026-11-06', when: '2026-11-05T10:00' }).errors.when, 'When must be after start (2026-11-06 00:00)');
  assert.equal(I.validate(schema, { at: '09:00', fee: -1 }).errors.fee, 'Fee must be at least 0');
  assert.equal(I.validate(schema, { at: '09:00', fee: 5, ccy: 'USD', start: '2026-11-06', when: '2026-11-07T09:30' }).ok, true);
  assert.deepEqual(I.parseField({ type: 'money' }, '1.5m'), { value: 1500000, error: null });
  assert.deepEqual(I.parseField({ type: 'time' }, '2pm'), { value: '14:00', error: null });
  assert.equal(I.parseField({ type: 'datetime' }, 'soon').error, 'The value must be a date and a time such as 2026-12-31 17:00');
  assert.equal(I.currencyOf(schema.properties.fee, { ccy: 'EUR' }, 'fee'), 'EUR');
  assert.equal(I.currencyOf({ type: 'money', currency: 'GBP' }, {}, 'x'), 'GBP');
  assert.equal(I.defaultWidget({ type: 'money' }), 'money');
  assert.equal(I.defaultWidget({ type: 'datetime' }), 'datetime');
});

test('dependent lists: the values offered follow what the field they depend on holds', () => {
  const items = { type: 'string', enum: ['A1', 'A2', 'B1'], dependsOn: 'kind', parents: { A1: ['A'], A2: ['A'], B1: ['B'] } };
  assert.deepEqual(I.offeredValues(items, { kind: [] }, 'sub'), ['A1', 'A2', 'B1']);
  assert.deepEqual(I.offeredValues(items, { kind: ['B'] }, 'sub'), ['B1']);
  assert.deepEqual(I.offeredValues({ enum: ['x', 'y'], dependsOn: '/top', parents: { x: ['1'] } }, { top: 1 }, 'a.b'), ['x']);
});

test('the bundle carries the pickers and the form\'s new names', () => {
  const B = require('../../web/static/elements/drishti-inputs/drishti_inputs.js');
  ['TimePicker', 'DateTimePicker', 'parseTime', 'parseDateTime', 'timeSlots', 'defineWidget', 'applyHook', 'currencyOf', 'offeredValues',
    'upgradeTimeInput', 'upgradeDateTimeInput', 'formOf', 'upgradeForms'].forEach((n) => assert.equal(typeof B[n], 'function', n));
});

test('a form the server drew: a host without a schema is left alone, the same Form answers twice, no root is no forms', () => {
  assert.equal(I.FORM_HOST_SELECTOR, '[data-di-form][data-schema]');
  assert.equal(I.formOf(null), null);
  assert.equal(I.formOf({ getAttribute: () => 'not json' }), null);
  assert.equal(I.formOf({ getAttribute: () => '{"value": {}}' }), null);       // no schema: nothing to draw
  const made = {};
  assert.equal(I.formOf({ diForm: made }), made);                              // made once; the page's script and the page's upgrade share it
  assert.deepEqual(I.upgradeForms(null), []);
  assert.deepEqual(I.upgradeForms({ querySelectorAll: () => [] }), []);
});

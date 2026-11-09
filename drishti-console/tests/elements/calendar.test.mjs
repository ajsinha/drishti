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
import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../../web/static/elements/drishti-chart/src/drishti_index.js';

const th = C.chartTheme({ theme: 'terminal' });
const env = { theme: th, fmt: C.formatterFor('integer'), xfmt: C.formatterFor() };

test('the calendar: one year per calendar, a day with no row left hollow, days sorted', () => {
  const rows = [{ day: '2026-03-02', n: 3 }, { day: '2026-01-05', n: 1 }, { day: '2025-12-30', n: 2 }, { day: 'not-a-date', n: 9 }];
  const b = C.buildCalendar({ kind: 'calendar', x: { field: 'day' }, value: { field: 'n' }, title: 'Breaches' }, rows, env);
  assert.deepEqual(b.option.calendar.map((c) => c.range), ['2025', '2026']);
  assert.equal(b.option.series.length, 2);
  assert.equal(b.option.series[1].data[0].name, '2026-01-05');
  assert.equal(b.table.rows.length, 3, 'a row without a date is not a square');
  assert.ok(b.summary.indexOf('3 days') >= 0);
});

test('the calendar is a registered kind', () => {
  assert.equal(C.kindOf('calendar').label, 'calendar heatmap');
});

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
/* drishti-renderer 2.0.0 in Node (no page): its version, the panel kinds it draws, and the rules it draws untrusted data by (the
   attributes it keeps, the links it drops). Its drawing against the console's macros is the parity suite (test_render_*). */
import test from 'node:test';
import assert from 'node:assert/strict';
import * as R from '../../web/static/elements/drishti-renderer/src/drishti_index.js';

test('the renderer is 2.0.0 and names the panel kinds it draws', () => {
  assert.equal(R.VERSION, '2.0.0');
  assert.ok(Object.isFrozen(R.KINDS));
  ['table', 'markdown', 'line', 'hbar'].forEach((k) => assert.ok(R.KINDS.includes(k), k));
});

test('untrusted attributes: handlers, styles and unsafe links are dropped, the rest kept', () => {
  const out = R.safeAttrs({ class: 'x', onclick: 'alert(1)', style: 'color:red', 'data-id': '7', 'aria-label': 'A', href: 'javascript:alert(1)', title: false });
  assert.deepEqual(out, { class: 'x', 'data-id': '7', 'aria-label': 'A' });
  assert.equal(R.safeAttrs({ href: '/v/trade/IRS-1' }).href, '/v/trade/IRS-1');
});

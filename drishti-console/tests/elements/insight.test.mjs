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
/* drishti-insight under Node: its entry (what it exports, the bundle's install doing nothing without a page), the workbook export over
   the one Explain (the links of an answer, the first one opened, a number without a workbook refused in words) and the Approvals queue
   starting only on the page that holds it. The components themselves are tested in the browser (the Explain, search and Approvals
   browser files) on the pages that use them. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import * as I from '../../web/static/elements/drishti-insight/src/drishti_index.js';

const require = createRequire(import.meta.url);

test('the entry exports the components and the workbook export; the bundle loads under Node and installs nothing without a page', () => {
  assert.deepEqual(['install', 'installExplain', 'installSearch', 'startApprovals', 'workbookLinks', 'exportWorkbook'].map((n) => typeof I[n]),
    ['function', 'function', 'function', 'function', 'function', 'function']);
  require('../../web/static/elements/drishti-core/drishti_core.js');
  const lib = require('../../web/static/elements/drishti-insight/drishti_insight.js');
  assert.equal(typeof lib.exportWorkbook, 'function');
  assert.equal(globalThis.Drishti && globalThis.Drishti.insightInstalled, undefined);       // no document: nothing installed
  I.install({});
});

test('the workbook export: the Workbook part\'s links, the first one opened, none refused in words', async () => {
  assert.deepEqual(I.workbookLinks({ workbook: { links: [{ label: 'Workbook', href: '/x.xlsx' }, { label: 'no href' }] } }), [{ label: 'Workbook', href: '/x.xlsx' }]);
  assert.deepEqual(I.workbookLinks(null), []);
  const asked = [], opened = [];
  const win = { location: { assign: (u) => opened.push(u) }, Drishti: { Explain: { ask: (req, part) => { asked.push([req, part]); return Promise.resolve({ workbook: { links: [{ label: 'VaR workbook', href: '/api/var/workbook?scope=BANK' }] } }); } } } };
  const got = await I.exportWorkbook({ kind: 'var', measure: 'var', scope: 'BANK' }, { win });
  assert.equal(got.href, '/api/var/workbook?scope=BANK');
  assert.deepEqual(opened, ['/api/var/workbook?scope=BANK']);
  assert.deepEqual(asked, [[{ kind: 'var', measure: 'var', scope: 'BANK' }, 'workbook']]);
  win.Drishti.Explain.ask = () => Promise.resolve({ workbook: { underConstruction: { id: 'explain-pivot-workbook', title: 'The pivot cell\'s workbook' } } });
  await assert.rejects(I.exportWorkbook({ kind: 'pivot' }, { win }), /not built yet: The pivot cell's workbook/);
  await assert.rejects(I.exportWorkbook({ kind: 'var' }, { win: { Drishti: {} } }), /the one Explain is not on this page/);
});

test('the Approvals queue starts only on the page that holds [data-approvals]', () => {
  const asked = [];
  const doc = { querySelector: (s) => { asked.push(s); return null; } };
  I.startApprovals({ document: doc });
  assert.deepEqual(asked, ['[data-approvals]']);
});

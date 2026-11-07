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
/* drishti-insight: the components that explain and find, on a Drishti server (they read its answers as the person: their
   entitlements, row rules and masks). The one Explain (the preview, the drawer, the full page, the keys: Drishti.Explain), the one
   search box (the command line's type-ahead, Help's search and Alt+K: Drishti.searchBox, Drishti.find), the one Approvals queue (the
   page that holds [data-approvals]) and the workbook export (a number's live-formula workbook through Explain). The bundle installs
   them in the page it loads in; Explain only for someone signed in (the page's script tag says data-explain="off" otherwise). */
import { installExplain } from './drishti_insight-explain.js';
import { installSearch } from './drishti_insight-search.js';
import { startApprovals } from './drishti_insight-approvals.js';

export { installExplain } from './drishti_insight-explain.js';
export { installSearch } from './drishti_insight-search.js';
export { startApprovals } from './drishti_insight-approvals.js';
export { workbookLinks, exportWorkbook } from './drishti_insight-workbook.js';

/**
 * Installs the components in a page (the bundle calls it when it loads): search always, Explain unless the script tag says
 * data-explain="off" (a page for someone not signed in), the Approvals queue when the page holds it. Once per page.
 * @param {Window} root the page's window (under Node: nothing to do)
 * @returns {void}
 */
export function install(root) {
  const doc = root && root.document;
  if (!doc || !doc.createElement) { return; }
  root.Drishti = root.Drishti || {};
  if (root.Drishti.insightInstalled) { return; }
  root.Drishti.insightInstalled = true;
  const tag = doc.currentScript;
  installSearch(root);
  if (!(tag && tag.getAttribute('data-explain') === 'off')) { installExplain(root); }
  if (doc.readyState === 'loading') { doc.addEventListener('DOMContentLoaded', () => startApprovals(root)); } else { startApprovals(root); }
}

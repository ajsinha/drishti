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
/* The workbook export: a number's live-formula workbook (the server's .xlsx with its formulas and a Provenance sheet, or the
   playground that exports one), found through the one Explain's Workbook part and downloaded. The browser builds no workbook: the
   server writes it, as the person asking (their entitlements, row rules and masks). */

/**
 * The workbook links of an Explain answer (its Workbook part): each {label, href, note}.
 * @param {Object} answer an answer of GET /api/explain (part workbook or all)
 * @returns {Array<{label: string, href: string, note: (string|undefined)}>}
 */
export function workbookLinks(answer) {
  const w = answer && answer.workbook;
  return (w && Array.isArray(w.links) ? w.links : []).filter((l) => l && l.href);
}

/**
 * Downloads a number's workbook: asks the one Explain for the Workbook part and opens its first link (the .xlsx answers as a file).
 * @param {Object} req the number, as data-explain names it ({kind, measure, scope, ...}, or {kind, body} for a pivot cell)
 * @param {{win: Window}} [o] win: the page (default window); its Drishti.Explain is the one Explain (installExplain)
 * @returns {Promise<{label: string, href: string}>} the link opened; rejected in words when the number has no workbook
 */
export function exportWorkbook(req, o) {
  const win = (o && o.win) || (typeof window !== 'undefined' ? window : null);
  const X = win && win.Drishti && win.Drishti.Explain;
  if (!X || typeof X.ask !== 'function') { return Promise.reject(new Error('the one Explain is not on this page (drishti-insight installs it)')); }
  return X.ask(req, 'workbook').then((a) => {
    const links = workbookLinks(a);
    if (!links.length) {
      const u = a && a.workbook && a.workbook.underConstruction;
      throw new Error(u ? 'the workbook of this number is not built yet: ' + (u.title || u.id) : 'no workbook for this number');
    }
    win.location.assign(links[0].href);
    return links[0];
  });
}

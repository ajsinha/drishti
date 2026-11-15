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
/* Explain under busy protection (docs/guides/OPERATIONS_GUIDE.md, "Busy protection"): a whole answer the server asked to wait (503,
   DRS-9501 to DRS-9505) and a Why it moved whose compared date waits (the part's "busy") are shown as the console's one busy message
   (drishti_busy.js: the countdown from the server's Retry-After, Try now, up to three tries by itself), never as a bare error. */

/**
 * The busy message in host in place of an answer; ``retry`` asks again. False when ``e`` is no busy refusal or the page has no busy script.
 * @param {Window} win the page's window
 * @param {HTMLElement} host
 * @param {Object} e an error carrying the problem body (``.body``) or a part marked busy
 * @param {function(): void} retry
 * @returns {boolean}
 */
export function showBusy(win, host, e, retry) {
  const B = win.Drishti && win.Drishti.Busy;
  const p = B && B.problem(e);
  if (!p) return false;
  host.textContent = '';
  B.render(host, p, { retry: retry });
  return true;
}

/**
 * The error of an answer that is not ok: its words, status, the problem body and the server's Retry-After (a busy refusal is counted down).
 * @param {Response} r
 * @param {Object} b the body read as JSON ({} when it was none)
 * @returns {Error}
 */
export function answerError(r, b) {
  const e = new Error(b.detail || ('HTTP ' + r.status));
  e.status = r.status;
  e.body = b;
  e.retryAfter = r.headers.get('Retry-After');
  return e;
}

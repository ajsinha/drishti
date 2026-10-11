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
/* Shared settings and small helpers of the element library (ELEMENTS.md, build step 9). One mutable `cfg` per page: the host sets it
   through DrishtiElements.configure(...) or by assigning `tokenProvider` on any element. */
export const MODULE_ORIGIN = new URL(import.meta.url).origin;
export const API = '/embed/v1';

export const cfg = { server: MODULE_ORIGIN, tokenProvider: null, hiddenGraceMs: 10000, retryMinMs: 2000, retryMaxMs: 30000,
  silenceMs: 30000, closeGraceMs: 1500 };

export const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/** The error body of a failed call as {code, status, detail} (the console answers with application/problem+json). */
export const problem = async (res) => {
  let b = {};
  try { b = await res.json(); } catch (e) { /* not JSON */ }
  return { code: b.code || ('HTTP-' + res.status), status: res.status, detail: b.detail || res.statusText };
};

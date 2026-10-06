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
/* Drishti Elements: the library entry (docs/architecture/ELEMENTS.md section 5). Importing it defines <drishti-view> and publishes
   window.DrishtiElements; importing it twice is harmless. The code is split into ES modules served under /embed/v1/m/:
   config, tokens, sanitize (allow-list and Trusted Types policy), assets (sheet, icon font, scripts), connection (one per page),
   enhancers (the console's scripts on the shadow root), frames (live patches) and element (the state machine). */
import { cfg, API, problem } from './m/config.js';
import { authed, providerChanged, invalidateToken } from './m/tokens.js';
import { connectionCount } from './m/connection.js';
import { trustedTypesActive } from './m/sanitize.js';
import { DrishtiView } from './m/element.js';

/** A typed command ("TRD 1000008", "counterparty ABC") to the entity it names: {ref, ...} or {ref: null, candidates}. */
async function resolve(text, opts = {}) {
  const server = (opts.server || cfg.server).replace(/\/$/, '');
  const res = await authed(server + API + '/resolve?text=' + encodeURIComponent(text), {}, opts.signal);
  if (!res.ok) { throw Object.assign(new Error('resolve failed'), await problem(res)); }
  return res.json();
}
window.DrishtiElements = Object.freeze({
  version: '1.0',
  configure: (o) => { Object.assign(cfg, o || {}); if (cfg.tokenProvider) { providerChanged(); } },
  resolve,
  invalidateToken,                                     // the host's user changed: the next call asks the provider again
  connections: connectionCount,
  trustedTypes: trustedTypesActive                      // true when the `drishti-elements` Trusted Types policy is in use
});
export { DrishtiView, resolve };

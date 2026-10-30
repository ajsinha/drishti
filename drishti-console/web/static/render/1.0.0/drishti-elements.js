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
/* Drishti Elements 2.0: <drishti-view>, <drishti-panel> and the alias <drishti-screen>, drawn by the client-side renderer (this folder) from
   the embed API v2. One import:

     <script type="module" src="https://drishti.bank.example/embed/v2/render/1.0.0/drishti-elements.js"></script>
     <drishti-view kind="trade" entity="IRS-48213"></drishti-view>

   The element API is Elements 1.x's (ELEMENTS.md sections 5 to 7): DrishtiElements.configure({ tokenProvider }), the attributes, the
   properties, reload() and refresh(), the drishti:* events and the loading, empty and error slots. Elements 1.x stays at /embed/v1/ for one
   release; a page uses one or the other. */
import { cfg, API, ELEMENTS_VERSION, DrishtiView, DrishtiPanel, define, invalidateToken, providerChanged, connectionCount, resolve } from './m/elements.js';
import { VERSION, CONTRACTS } from './drishti-render.js';

define();
const api = Object.freeze({
  version: ELEMENTS_VERSION,
  renderer: VERSION,
  contracts: CONTRACTS,
  api: API,
  configure: (o) => { Object.assign(cfg, o || {}); if (cfg.tokenProvider) { providerChanged(); } },
  resolve,
  invalidateToken,                                    // the host's user changed: the next call asks the provider again
  connections: connectionCount
});
if (!window.DrishtiElements || String(window.DrishtiElements.version || '').startsWith('2.')) { window.DrishtiElements = api; }
window.DrishtiElements2 = api;
export { DrishtiView, DrishtiPanel, api as DrishtiElements };

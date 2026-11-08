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
/* Drishti Elements 2.3 (carried by renderer 2.0.0; part of drishti-renderer): <drishti-view>, <drishti-panel> and the alias
   <drishti-screen>, drawn from the embed API v2. One import:

     <script type="module" src="(the console)/embed/v2/elements/2/drishti-elements.js"></script>
     <drishti-view kind="trade" entity="IRS-48213"></drishti-view>

   The element API is 2.2's, unchanged (EMBED_GUIDE.md section 19): DrishtiElements.configure({ tokenProvider }) or { auth: 'popup' },
   the attributes, the properties, reload() and refresh(), the drishti:* events and the loading, empty and error slots. */
import { VERSION, CONTRACTS } from './drishti-render.js';
import { cfg, API, ELEMENTS_VERSION, define, invalidateToken, providerChanged, connectionCount, resolve, usePopup, serverOf, elementClasses } from '../../elements/drishti-renderer/src/drishti_index.js';

const { DrishtiView, DrishtiPanel } = define(window);
const api = Object.freeze({
  version: ELEMENTS_VERSION,
  renderer: VERSION,
  contracts: CONTRACTS,
  api: API,
  configure: (o) => { Object.assign(cfg, o || {}); if (cfg.auth === 'popup') { usePopup(serverOf()); } else if (cfg.tokenProvider) { providerChanged(); } },
  signOut: (server) => { const p = cfg.tokenProvider && cfg.tokenProvider.popup; if (p && (!server || p.server === String(server).replace(/\/$/, ''))) { p.drop('host'); } },
  resolve,
  invalidateToken,                                    // the host's user changed: the next call asks the provider again
  connections: connectionCount
});
if (!window.DrishtiElements || String(window.DrishtiElements.version || '').startsWith('2.')) { window.DrishtiElements = api; }
window.DrishtiElements2 = api;
export { DrishtiView, DrishtiPanel, api as DrishtiElements, elementClasses };

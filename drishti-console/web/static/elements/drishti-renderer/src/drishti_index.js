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
/* drishti-renderer (Drishti Elements library 7, renderer 2.0.0; EMBED_GUIDE.md): Drishti screens from a static, live or streamed
   (GenUI) ViewModel, drawn with the shared libraries: drishti-grid (tables, trees, drills, pivots; the nested pivot's tree data),
   drishti-chart (every chart, the only place an engine is called), drishti-core (the element maker and its rules for untrusted data,
   formats half up, the API client), drishti-data (the live client for streams and Elements' one connection per page); the insight
   components through hooks (options.insight). Elements 2.3 (<drishti-view>, <drishti-panel>, <drishti-screen>) is part of it.
   Hosts import the public entry static/render/<version>/drishti-render.js, which says where the files are and installs the names. */
export { VERSION, CONTRACTS, render, patch, subscribe, defineScreen, installRender } from './drishti_render.js';
export { KINDS } from './drishti_kinds.js';
export { setAssetBase, statics, trustedTypesActive } from './drishti_assets.js';
export { safeAttrs, h, str, toneClass } from './drishti_dom.js';
export { tableHooks } from './drishti_kinds.js';
export { fixFor, problem, viewUrl, streamUrl, fetchView, openStream, personFetch } from './drishti_net.js';
export { nestedPivotOptions } from './drishti_nested.js';
export { ELEMENTS_VERSION, API, cfg, serverOf, usePopup, getToken, invalidateToken, providerChanged, resolve, connectionFor, connectionCount } from './drishti_embed.js';
export { elementClasses, define } from './drishti_elements.js';
import { installRender } from './drishti_render.js';

/**
 * Starts drishti-renderer in a page (the bundle calls it when it loads): window.DrishtiRender and <drishti-screen>.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) { if (root && root.document && root.customElements) { installRender(root); } }

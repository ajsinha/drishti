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
/* The client renderer's host part (Drishti's): the API of render() over Drishti Elements' painter, the embed elements 2.x, the sign-in
   window, the token provider and About this page. The public entries beside this folder import it: drishti-render.js and
   drishti-elements.js. */
export { VERSION, CONTRACTS, KINDS, render, patch, subscribe, defineScreen, installRender } from './drishti_render.js';
export { setAssetBase, statics, trustedTypesActive } from './drishti_assets.js';
export { fixFor, problem, viewUrl, streamUrl, fetchView, openStream, personFetch } from './drishti_net.js';
export { ELEMENTS_VERSION, API, cfg, serverOf, usePopup, getToken, invalidateToken, providerChanged, resolve, connectionFor, connectionCount } from './drishti_embed.js';
export { elementClasses, define } from './drishti_elements.js';

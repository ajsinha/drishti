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
/* Drishti Render 2.0.0, the public entry of drishti-renderer (docs/guides/EMBED_GUIDE.md): the same API as 1.x, drawn with the
   Drishti Elements libraries. One import, one call:

     <script type="module">
       import { render } from 'https://drishti.bank.example/static/render/2.0.0/drishti-render.js';
       render(document.getElementById('risk'), { kind: 'trade', id: 'IRS-48213' });
     </script>

   This file says where everything is, from its own address: the libraries beside the console's static root (../../elements/), the
   console's static files (../../), the sheet beside it; it works the same under /embed/v2/render/2.0.0/ for other origins. Types:
   drishti-render.d.ts beside this file. */
import { configure } from '../../elements/drishti-core/src/drishti_index.js';
import { setAssetBase, installRender } from '../../elements/drishti-renderer/src/drishti_index.js';

configure({ base: new URL('../../elements/', import.meta.url).href, staticBase: new URL('../../', import.meta.url).href });
setAssetBase({ statics: new URL('../../', import.meta.url).href, sheet: new URL('drishti-render.css', import.meta.url).href });
if (typeof window !== 'undefined') { installRender(window); }

export { VERSION, CONTRACTS, render, patch, subscribe, KINDS } from '../../elements/drishti-renderer/src/drishti_index.js';

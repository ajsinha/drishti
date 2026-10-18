<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# Drishti Elements in React, Vue and Angular

Three host pages, one per framework, each embedding `<drishti-search>`, `<drishti-view>`, `<drishti-about>` and `<drishti-report>` for a signed-in user. They are the examples of [ELEMENTS.md](../../ELEMENTS.md#react-angular-and-vue-sample-pages); `tests/test_embed_frameworks_browser.py` runs all three in Chromium.

| Folder | Framework | How it runs without a build step |
|---|---|---|
| `react/` | React 18.3.1 | the UMD builds, `createElement` (the JSX is in the comments) |
| `vue/` | Vue 3.5.43 | the runtime-only global build (no template compiler, so no `eval`), render functions |
| `angular/` | Angular 18.2.14 | the ES-module builds through an import map and the JIT compiler, a standalone zoneless component |

`host.js` (the token provider, the demo sign-in) and `sample.css` are shared.

## Run them

1. **Fetch the framework builds** (they are not in the repository; MIT; once, needs `node`/`npm` and the npm registry; offline afterwards):

   ```bash
   bash docs/guides/examples/embed/fetch-vendor.sh              # all three; or: --only react | vue | angular
   ```

   They are written to `docs/guides/examples/embed/vendor/` (git-ignored). The pages load them from there: no CDN.

2. **Start Drishti with embedding on and the demo host**, as in [tools/elements-demo/README.md](../../../../tools/elements-demo/README.md) (register the host application with the scopes `embed:view embed:about embed:report embed:search`).

3. **Open** `http://127.0.0.1:17968/samples/react/`, `/samples/vue/` or `/samples/angular/` (the demo host serves this folder at `/samples/`, under a strict content-security policy: no inline script and only the console beyond itself).

The Angular page is the exception to "strict": Angular has no UMD build, so it needs an inline import map and the JIT compiler (`'unsafe-eval'`); the demo host relaxes its policy for `/samples/angular/` alone. A real Angular application is built ahead of time (`ng build`) and needs neither: the component and its template are the same (`schemas: [CUSTOM_ELEMENTS_SCHEMA]`).

## What each page shows

* A search box (`<drishti-search>`): type `1000`, pick a result; the page's own code takes the pick (`drishti:select`) and points the view and the About body at it.
* The view and its **About this page** body (`<drishti-view>`, `<drishti-about>`), with the page's own live indicator (the `drishti:state` event) and a masked-fields badge (`drishti:masked`).
* The **report** `file:report:live-trading-desk` (`<drishti-report>`).

Events with a colon are bound with `addEventListener` in all three (Vue and Angular read the colon in a template), in `onMounted`, `ngAfterViewInit` or an effect.

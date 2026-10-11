# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""The npm package ``@drishti/elements`` as files (docs/architecture/ELEMENTS.md section 13; build step 12), made by ``tools/elements_package.py``.

``dist/`` holds the console's modules byte for byte (the entry and ``dist/m/*.js``, the layout the console serves under ``/embed/v1/elements/<version>/``),
``types/`` the TypeScript declarations generated from ``core/element_api.py``, ``integrity.json`` the SHA-384 of every file, and ``README.md`` the usage and the
reference tables. There is no bundler: the modules are plain ES modules that import each other by relative paths, so the package works from a CDN-less
``node_modules`` served by the host, or through any bundler. What the library needs at run time is fetched from the console (``server``): the element
sheets, the icon font and the chart code are not in the package, because they are versioned with the console that serves the data."""
from __future__ import annotations

import json

from core import element_api as api
from core import element_dist as dist

README = """<!--
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
# @drishti/elements

Drishti's live views and BI reports inside other web applications, as Web Components with a Shadow DOM and no iframes.
Version {version}; embed API contract {api}. Proprietary: see LICENSE in the Drishti repository.

```html
<script type="module" src="https://drishti.bank.example/embed/v1/elements/{version}/drishti-elements.js"
        integrity="{entry_sri}" crossorigin="anonymous"></script>
<drishti-view kind="trade" entity="MX-20000001" theme="light"></drishti-view>
<drishti-report report="file:report:trades-overview" theme="light"></drishti-report>
```

or from this package:

```js
import '@drishti/elements';                       // defines <drishti-view>, <drishti-panel> and <drishti-report>
window.DrishtiElements.configure({{ server: 'https://drishti.bank.example', tokenProvider: async () => (await fetch('/api/drishti-token')).json() }});
```

`server` is the console the elements talk to (default: the origin the module was loaded from, which is not what you want when the module comes from
`node_modules`). The token provider returns an embed token your backend asked the Drishti server for (RFC 8693); see `docs/guides/ELEMENTS.md`.
The element sheets, the icon font and the chart code are fetched from the console, so they always match the data it serves.

## Files

| File | What |
|---|---|
| `dist/drishti-elements.js`, `dist/m/*.js` | the library: plain ES modules, the same bytes the console serves for version {version} |
| `types/index.d.ts` | classes, event detail types, `HTMLElementTagNameMap`, `HTMLElementEventMap` |
| `types/react.d.ts` | `JSX.IntrinsicElements` entries for React |
| `integrity.json` | SHA-384 of every file (subresource integrity), also served at `/embed/v1/elements/{version}/integrity.json` |

## Reference

{tables}
"""


def package_json() -> str:
    return json.dumps({
        "name": "@drishti/elements", "version": dist.VERSION, "description": "Drishti Elements: live views and BI reports in other web applications, as Web Components without iframes",
        "license": "SEE LICENSE IN LICENSE", "private": False, "type": "module", "sideEffects": True, "main": "./dist/drishti-elements.js", "module": "./dist/drishti-elements.js",
        "types": "./types/index.d.ts", "exports": {".": {"types": "./types/index.d.ts", "default": "./dist/drishti-elements.js"}, "./react": {"types": "./types/react.d.ts"},
                                                  "./integrity.json": "./integrity.json", "./package.json": "./package.json"},
        "files": ["dist", "types", "integrity.json", "README.md"], "keywords": ["drishti", "web-components", "embedding", "bi"],
    }, indent=2) + "\n"


def build() -> dict[str, bytes]:
    """Every file of the package by its path inside the package folder."""
    record = dist.integrity()
    out = {"package.json": package_json().encode(), "integrity.json": dist.lock_text(record).encode(), "types/index.d.ts": api.typescript().encode(),
           "types/react.d.ts": api.react_types().encode()}
    for rel, path in dist.files().items():
        out["dist/" + rel] = path.read_bytes()
    tables = "\n".join(api.markdown_tables(e) for e in api.ELEMENTS)
    out["README.md"] = README.format(version=dist.VERSION, api=dist.API, entry_sri=record["files"][dist.ENTRY]["integrity"], tables=tables).encode()
    return out

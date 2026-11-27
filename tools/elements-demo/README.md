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
# Elements demo: a host application for `<drishti-view>`

The proof of concept of [Drishti Elements](../../docs/architecture/ARCHITECTURE.md#30-the-client-renderer-and-drishti-elements): a tiny "host" web
application on **its own origin** showing live Drishti views in a Shadow DOM, without an iframe: its own sign-in, a backend that asks Drishti for a
token **server to server** (the app secret never reaches the browser), a search box (`TVAL TVAL-1000001`, `TRD MX-20000001`), recent entities, an
as-of picker and two `<drishti-view>` elements sharing a connection, under a strict CSP (what it blocks: `/api/csp-reports`).

**The tokens are real.** The backend signs an assertion with its own RSA key (`demo-host-key.json`: a DEMO key, never use it anywhere real) and
exchanges it at the server (`POST /api/v1/embed/token`, RFC 8693, [API_REFERENCE](../../docs/guides/API_REFERENCE.md#24-embedding-views-in-another-web-application)):
a five-minute, read-only, always-masked embed token. Both demo users must exist in Drishti.

## Run it

Three processes on free ports, from the repository root, with the libraries of the Python you run (`python3 tools/drishti.py setup` checks them).

```bash
python3 tools/drishti.py build all        # jar and modules (Windows: py -3.13 tools\drishti.py build all)
mkdir -p /tmp/drishti-elements && cd /tmp/drishti-elements
DRISHTI_PACKS=trading,counterparty-risk,market-risk DRISHTI_PACKS_DIR=<repo>/config/packs DRISHTI_SECURITY_ENABLED=true \
DRISHTI_TOKEN_SECRET=poc-scratch-secret-0123456789abcdef0123 DRISHTI_EMBED_ENABLED=true \
DRISHTI_PUBLIC_URL=http://127.0.0.1:18969 DRISHTI_CONSOLE_URL=http://127.0.0.1:17969 \
  java -jar <repo>/lib/drishti-server-*-exec.jar --server.port=18969 --drishti.security.registered-users-only=false &
# register the demo users and the host application as an administrator: register_host() in
# drishti-console/tests/test_embed_elements_browser.py has the exact calls; POST /api/v1/admin/embed/apps shows the client secret once
cd <repo>/drishti-console
DRISHTI_TOKEN_SECRET=poc-scratch-secret-0123456789abcdef0123 DRISHTI_EMBED_ENABLED=true \
  python3 run_drishti_web.py --server.port=17969 --backend.url=http://127.0.0.1:18969 &
cd <repo> && python3 tools/elements-demo/server.py --port 17968 --console http://127.0.0.1:17969 --server http://127.0.0.1:18969 --secret <the client secret>
```

Open <http://127.0.0.1:17968/>, press **viewer** or **author**, and `TVAL-1000008` appears with its MTM and one-day P&L ticking. Both users see masked
fields: embedded views always mask; the console's own page (`/v/trade/1000008`) shows the same trade unmasked. A past date makes the view a still
snapshot (**Live** resubscribes); stopping the console shows `reconnecting`, then it repaints. `python3 -m pytest -q drishti-console/tests/test_embed_elements_browser.py` starts all three processes itself and drives this host in Chromium,
Firefox and WebKit (`python -m playwright install chromium firefox webkit`), checking the ten acceptance criteria and recording timings.

## Files

| File | What it is |
|---|---|
| `server.py` | the host's web server and its backend (sign-in, `/api/drishti-token`, CSP, CSP report collection) |
| `static/index.html`, `static/app.js`, `static/host.css` | the host page, its token provider, search and `drishti:navigate` handler, and its own styles |

The React, Vue and Angular samples of `docs/guides/examples/embed/` are served at `/samples/<framework>/` (fetch the builds with `fetch-vendor.sh`
there; register the application with the scopes `embed:view embed:about`).

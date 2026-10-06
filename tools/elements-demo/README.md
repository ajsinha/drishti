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

The proof of concept of [Drishti Elements](../../docs/architecture/ELEMENTS.md) (build step 0): a tiny "host" web
application on **its own origin** that shows live Drishti views in a Shadow DOM, without an iframe.

It has its own sign-in (a demo cookie), its own backend endpoint that asks Drishti for a token **server to server** (the
app secret never reaches the browser), its own **search box** (type `TRD MX-20000001`), a **recent entities** list, an
**as-of** date picker, a main `<drishti-view>` and a second, smaller one that shares its connection. Clicking a linked
entity inside the view (the counterparty) raises `drishti:navigate`; the host takes it over into its search box. The page
is served with the strict CSP a careful host would have (no `'unsafe-inline'`, no `frame-src`); anything it blocks is
listed at `/api/csp-reports`.

**The token path is a DEV-ONLY stand-in** (`embed.poc.enabled`, off by default) for the server's RFC 8693 exchange, which is
build step 1. Do not enable it anywhere real.

## Run it

Three processes, on ports that are free. The commands are from the repository root; the console needs its virtualenv
(`uv venv drishti-console/.venv && uv pip install -p drishti-console/.venv/bin/python -r drishti-console/requirements.txt`).

```bash
# 1. the Drishti server, security on (the console's token secret must be the same), the trading pack, a scratch folder for ./data
./mvnw -o -q package -DskipTests -pl drishti-server -am
mkdir -p /tmp/drishti-elements && cd /tmp/drishti-elements
DRISHTI_PACKS=trading DRISHTI_PACKS_DIR=<repo>/packs DRISHTI_SECURITY_ENABLED=true \
DRISHTI_TOKEN_SECRET=poc-scratch-secret-0123456789abcdef0123 \
  java -jar <repo>/drishti-server/target/drishti-server-*-exec.jar --server.port=18969 --drishti.security.registered-users-only=false &

# 2. the console, with the proof of concept on and one host application declared (exact origins, a secret)
cd <repo>/drishti-console
DRISHTI_TOKEN_SECRET=poc-scratch-secret-0123456789abcdef0123 DRISHTI_EMBED_POC_ENABLED=true \
DRISHTI_EMBED_POC_KEY=poc-signing-key-0123456789abcdef0123456 \
  .venv/bin/python run_drishti_web.py --server.port=17969 --backend.url=http://127.0.0.1:18969 \
  --embed.poc.apps.elements-demo.secret=demo-app-secret-not-for-production \
  --embed.poc.apps.elements-demo.origins=http://127.0.0.1:17968 \
  --embed.poc.apps.elements-demo.role_map.viewer=viewer --embed.poc.apps.elements-demo.role_map.author=viewer &

# 3. the host application, on its own origin
cd <repo>
python3 tools/elements-demo/server.py --port 17968 --console http://127.0.0.1:17969 --secret demo-app-secret-not-for-production
```

Open <http://127.0.0.1:17968/>, press **viewer** or **author** (the host's two demo users), and the trade
`END-1000008` appears with its MTM ticking. Both users see masked fields (`•••`): embedded views always mask. Open the
console's own page (`http://127.0.0.1:17969/v/trade/END-1000008`) beside it to see the same trade unmasked.

## What to try

- Type `TRD MX-20000001` in the search box and press Enter; pick entries from **Recent**.
- Pick a past date: the view becomes a still snapshot; **Live** resubscribes.
- Click *Harbor Point Bank* in the title: the host's search box takes `CPTY CP-HARBORPT` and the view switches.
- Stop and restart the console: the element shows `reconnecting` (the host's status line), then repaints and ticks again.

## The acceptance tests

`drishti-console/tests/test_embed_elements_browser.py` starts all three processes itself (using a server already on
`18969` if there is one) and drives this host in Chromium, Firefox and WebKit (`python -m playwright install chromium firefox
webkit`; WebKit also needs a few system libraries, which Playwright names if they are missing). It checks the ten
acceptance criteria of step 0 and records first-paint and switch timings and payload sizes:

```bash
drishti-console/.venv/bin/python -m pytest -q drishti-console/tests/test_embed_elements_browser.py
```

Results and what they changed in the plan are in the *Step 0 results* section of
[ELEMENTS.md](../../docs/architecture/ELEMENTS.md).

## Files

| File | What it is |
|---|---|
| `server.py` | the host's web server and its backend (sign-in, `/api/drishti-token`, CSP, CSP report collection) |
| `static/index.html` | the host page: search, recent, as-of, two `<drishti-view>` elements, and the one script line that loads the element |
| `static/app.js` | the host's own code: token provider, search through `DrishtiElements.resolve`, `drishti:navigate` handler |
| `static/host.css` | the host's own styles (they must not leak into the element, nor the element's into the host) |

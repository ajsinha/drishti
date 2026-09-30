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
# Drishti 1.9.0 — release notes

*2026-09-30.* **Business dates and history.** Drishti now answers "what did this look like on a given day?"

- **Live or a picked date.** The top bar has a date box. **Live** (the default) is the current business date on
  the New York calendar, and views stream. A **picked date** is a static snapshot of that day's end-of-day
  data, even when it is today: the view, its links, F8 impact and suggestions all follow the date. Weekends and
  holidays roll back to the previous business day.
- **Delta Lake.** History is read from Delta Lake by a new server connector (Delta Kernel, no Spark): one table
  per kind under `data/delta/<domain>/`, partitioned by business date, with time travel for data "as known at"
  an instant. The console never touches the lake. **Named connectors** let each domain have its own.
- **Imperfect data never breaks a screen.** A panel whose data is missing or has the wrong shape says "No data
  available".
- **Sutras are Markdown documents** (`*.sutra.md`): prose that explains the layout, around one `sutra` block.
  Studio is now a Markdown editor with snippets, an outline and a rendered Document tab.
- **Realistic sample data.** Every finance trade is fully booked (identifiers with check digits, execution,
  lifecycle, regulatory, settlement, valuation, full cashflow schedules that reprice to the MTM), built on the
  new `tools/samplegen` toolkit.
- **Risk pack groundwork:** a taxonomy of 125 products and 45 data kinds, and 170 generated Sutras.
- **Themes:** *crimson* and *crimson dark*, from Maya.

Upgrade notes: plugins keep working unchanged (the dated SPI methods have defaults). Sutra `*.yaml` files still
load. `tools/sutra_to_md.py` converts them. To see history locally, build the sample lake:
`uv run --with deltalake --with pyarrow python tools/samplegen/lake.py --samples packs/finance/samples --root data/delta --domain finance`.

---

# Drishti 1.8.0 — release notes

*2026-09-30.* **Packs per user.** Admins assign domain packs to each user. Users with several packs
choose which to see, and the server enforces it.

---

# Drishti 1.7.0 — release notes

*2026-09-30.* **F8 Impact** is built. It is the known limit listed since 1.0.0, and it now shows what
depends on an entity, what that rolls into, and the amount at stake.

---

# Drishti 1.6.0 — release notes

*2026-09-30.* This release adds **monitors and alerts**:
- live watchlists of mixed entities, over one connection per page;
- alert rules that the server checks on every tick and that fire once when they become true, with a
  bell and toasts;
- packs that suggest rules and starter monitors.

---

# Drishti 1.5.0 — release notes

*2026-09-30.* **Domain packs.** Drishti's core is now industry-neutral. Finance (the mockups) and
logistics (shipments, containers, vessels, ports) ship as packs, enabled with `DRISHTI_PACKS`. A new
industry needs configuration and content only: Sutras, vocabulary, links, roles, samples and guides.
See `docs/PACKS.md`.

---

# Drishti 1.4.0 — release notes

*2026-09-30.* This release adds **workspaces**:
- several live views on one screen, where a pane can follow another's selection (pick a trade in the
  netting set, and the next pane opens it);
- three starters;
- saved per user on the server.

---

# Drishti 1.3.0 — release notes

*2026-09-30.* This release adds:
- **Mobile.** Drishti works in Safari on iPhone and Chrome on Android, and can be added to the home
  screen. Views stack, and F-keys become a swipeable row of buttons.
- **Competitive landscape** (`/about/competitive`). It compares categories rather than vendors, and is
  candid about where Drishti is weaker.

---

# Drishti 1.2.0 — release notes

*2026-09-30.* This release adds the **in-app help centre and About page**:
- tutorials, guides and every reference document, with search;
- `F1` for help on the current screen, and a **?** on every panel;
- `/about` showing the version, what is loaded, source health and the legal notices.

The help renders the repository's own docs, so the two cannot drift apart.

---

# Drishti 1.1.0 — release notes

*2026-09-30.* This release adds **user management**:
- admins create, edit, enable or disable, reset and delete users, and read the audit log;
- everyone can change their own password;
- PBKDF2 hashing, a password policy, lockout, and a guard that always keeps an enabled admin.

A development admin, `drishti-dev-admin` / `drishti-dev-admin123`, is created on an empty store.
Change its password, or set `DRISHTI_SEED_ADMIN=false`. Nobody is forced to change a password unless
that is configured. See `docs/USER_MANAGEMENT.md`.

---

# Drishti 1.0.0 — release notes

*2026-09-30 · Ashutosh Sinha*

**Any data. Any domain. One grammar.** Drishti 1.0.0 is the first complete release: a live,
keyboard-driven terminal that renders any entity from any source through one declarative screen
grammar, **Rachana**. Each product family gets a versioned **Sutra** instead of a coded screen, and
inference fills whatever a Sutra leaves out.

## What is in 1.0.0

- **The four reference views.** The interest rate swap, FX swap, commodity future and netting-set
  mockups are reproduced end to end. Golden tests pin their values.
- **Rachana and Sutra.** A YAML grammar with twelve panel kinds and a compiled, side-effect-free
  expression language (Rachana-EL). Every problem is reported with line and column, the registry
  hot-reloads, and a broken edit keeps the last good version.
- **Inference.** Scored, explainable rules lay out any document with no Sutra (tabs, tables, curves,
  exposure areas, bars, ladders, key/value, links), and fill a Sutra's gaps.
- **The terminal.** Commands (`TRD IRS-48213 <GO>`), Bloomberg-style type-ahead, F-keys, F9 raw JSON,
  Alt+← back, breadcrumbs, and five themes including *Wall Street*.
- **Live.** SSE with leading-edge 50 ms frames and minimal patches. Slow clients get merged frames.
  Measured tick → frame p99 is about 11 ms, and 10,000 listeners share one topic.
- **Sources.** A Spring-free plugin SPI with `demo`, `file` (JSON/CSV), `rest` (HTTP/JSON) and
  `jdbc`, each with config-driven routing, deadlines and in-memory search.
- **Sutra Studio.** A highlighted editor, live previews in tens of milliseconds, problems by line,
  "start from inference", and saving for authors where enabled.
- **Security.**
  - Console sign-in (PBKDF2, signed session cookies) and HS256 tokens to the server (algorithm pinned, constant-time signature check).
  - Per-role entity kinds; denied links stay visible but disabled with the reason.
  - Raw JSON redaction; a strict CSP with no inline script or style; every asset vendored.
- **Operations.** Actuator and Prometheus metrics (view and live), a Grafana dashboard, Dockerfiles
  and compose, runbooks.

## Measured

Warm view p99 < 50 ms (a build gate); cold IRS view about 14 ms; Rachana-EL evaluation 10–50 ns;
cold inference about 13 µs; live p99 about 11 ms. See `docs/PERFORMANCE.md`.

## Known limits (stated plainly)

- **Sign-in** is config-backed (users file). OIDC/SSO is not built; the token boundary is where it
  will plug in.
- **The `aero` live-cache plugin** is not built. The `rest` and `jdbc` plugins cover generic services
  and databases.
- **F8 Impact** is reserved but not implemented.
- **Docker images** are defined (`deploy/`), but were not built in the release environment because
  it had no Docker daemon access. Build them with the commands in `docs/OPERATIONS.md`.
- **Studio saving** writes files locally. There is no review workflow yet; Sutras are expected to go
  through version control.

## Upgrading

This is the first release. Configuration keys are documented in `docs/CONFIGURATION.md`.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary and confidential.*

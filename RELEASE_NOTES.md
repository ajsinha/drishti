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

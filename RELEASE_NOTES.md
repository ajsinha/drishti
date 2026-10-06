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
# Drishti 1.18.0 — release notes

*2026-10-06.* **Data loads: tell Drishti new data has landed, and it acts on it.**

- **The "batch landed" signal** (docs/guides/DATA_LOADS.md). Your ETL — Airflow, a cron script, DishtaYantra — tells
  Drishti that a kind's data for a business date is ready: `POST /api/v1/packs/{pack}/loads` or
  `drishti.py data landed --pack P --kind K --date D --rows N`. Drishti then refreshes what it reads, **verifies** the
  date is there, **evaluates the alert rules** on that date, optionally runs a smoke check, and **sends notices** (bell,
  inbox, email) to the roles you choose, e.g. "EOD trades for 2026-10-06 loaded: 48,213 rows, 12 rejected; 3 alerts
  fired". Re-announcing is safe (idempotent per pack, kind, date and batch); a failure is recorded too.
- **Expectations and late data:** per pack, which kinds are due each business day and by when (time, zone, business-day
  calendar). A late or missing load is flagged once, Health shows `data late: …`, and the flag clears when it lands.
- **Admin → Packs → Data loads:** the history of every load with each step's result, today's expectations (on time,
  late, missing), filters and settings. **CLI:** `data landed` (exit 1 when a ready load is not verified) and
  `data loads [--expectations]`.
- **A least-privilege token scope, `loads:write`,** for ETL jobs: they may announce loads only for kinds their user's
  roles reach.
- Drishti does not ingest data itself: `data ingest`, `--watch` and `pack make` stay for staging, small setups and
  proofs of concept; production ingestion stays with your ETL.

**Upgrade notes:** none required. New: `drishti.loads.*` settings, the `loads:` section in `pack.yaml`, the `loads:write`
token scope, problem codes DRS-5011 and DRS-5012.

# Previous release: Drishti 1.17.1 — release notes

*2026-10-06.* **A way home and back, hot reload that never stops, and a drill in nine minutes.**

- **The name and logo lead home:** clicking them in the top bar opens the landing page from any page, also when you are
  signed in; the landing page then offers **← Back to …** (Alt+B) to the view or page you came from. Only same-site paths
  are accepted, so the logo cannot be turned into a redirect.
- **Sutra hot reload never stops silently:** when the operating system has no file watches left (Linux *User limit of
  inotify watches reached*, common on developer machines with several IDEs), the server polls the Sutra files instead
  and keeps picking up edits; Admin → Health shows `hotReload: POLLING`. New settings `drishti.rachana.watch: auto|poll`
  (`DRISHTI_SUTRA_WATCH`) and `poll-interval` (2 s).
- **The drill takes about 9 minutes instead of about 22, testing as much:** Java tests in parallel forks, console tests
  in parallel workers, the Java 25 check beside the console tests, and a `--docs` mode (about a minute) chosen by itself
  for documentation-only changes; `--no-push` for timing runs. Browser tests reload a page once when Chromium cancelled
  its requests because Docker changed the network (`ERR_NETWORK_CHANGED`), the cause of the rare workbench failures.
- Tests no longer depend on the order they run in, and no longer leave files in a `data` folder of the server module.

**Upgrade notes:** none required. Developers: `uv pip install -r drishti-console/requirements-test.txt` once (adds
`pytest-xdist`).

# Older release: Drishti 1.17.0 — release notes

*2026-10-05.* **From a folder of JSON Lines to a deployed pack, by command line or by the admin page.**

- **`drishti.py`, one command line for every build activity** (docs/guides/CLI_GUIDE.md, with real output for every
  command and a PyCharm section):
  - `pack make`: a folder or file of JSON Lines, a kind, a match column and a name give ONE folder to deploy: the pack
    (Sutras per group, tests, About skeleton), the data as a Delta lake or files partitioned by business date, a signed-
    ready bundle, a README.txt written for that pack (deploy, verify, lift data from Delta, add days, roll back), a
    server overlay and run scripts. Key and date fields are detected; the reasons are printed.
  - `sutra gen`, `data profile` (suggests key, date and match columns), `data ingest` (Delta or files, idempotent by
    date, `--watch` for drop folders), `pack regenerate` (a three-way merge that keeps your edits), `pack diff`
    (breaking changes flagged), `pack check`, `catalogue`, `i18n`, `bundle`, `verify`, `deploy`, `rollback`,
    `server smoke`, `doctor`, `view get|explain`, design commands over the API, and shell completion.
- **Admin → Packs → Deploy archive:** upload a bundle; the server verifies it (checksums, paths, server version,
  Sutra tests, optional signature), previews what changes with breaking changes highlighted, deploys with the previous
  version kept and an automatic rollback, and keeps a history with Roll back. A **Data source** panel per pack edits,
  tests (dates and row counts) and resets where the data comes from; changes live in an admin override file, so a
  redeploy never loses them. Archives carry the pack only, never data.
- **Operationalising by copying** (docs/guides/OPERATIONALISING.md): promote the same checksummed bundle from dev to
  test to prod; Docker and Kubernetes recipes.
- **Personal API tokens can write** when created with a scope (`design:write`, `design:approve`, `packs:admin`), always
  within the user's own roles, expiring, audited; existing tokens stay read-only.
- **The console module is `drishti-console/`** (was `console/`).
- Fixed: pack checks from the command line see the pack's own About text; server tests no longer leave a `data` folder.

**Upgrade notes:**
- Recreate the console's virtual environment once: `rm -rf drishti-console/.venv`, then QUICKSTART step 3; point IDE run
  configurations at `drishti-console/`.
- New settings: `drishti.packs.deploy.*`, `drishti.security.token-scopes`, `token-write-max-days`, console
  `packs.deploy_max_mb`. See CONFIGURATION.md.

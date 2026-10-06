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
# Drishti 1.17.1 — release notes

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

# Previous release: Drishti 1.17.0 — release notes

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

# Older release: Drishti 1.16.0 — release notes

*2026-10-05.* **Java 21 for production, About this page, and Share and Discussion.**

- **Java 21 is the production runtime.** Drishti builds Java 21 bytecode and runs on Java 21 or newer (25 is tested too).
  The build fails if any dependency needs a newer JVM (`enforceBytecodeVersion`), the drill runs every test on Java 21
  (containers included) and checks Java 25, and the Docker image defaults to Temurin 21. Blocking work never runs under
  `synchronized`, so virtual threads do not pin on 21 (a guard test keeps it so; a load test found and fixed one hot path).
- **About this page** (`?`, F1 or **About** on any view): what you are looking at (the pack's own text, filled with the
  page's values), what each number means (a glossary of exactly the fields shown, with formulas), where the data came from
  and how fresh it is, why the page looks like this (the Sutra chosen and why, empty, masked and no-access panels), and
  where to go next. Panel `?` popovers and dotted-underline hints on labels. Every quickstart pack ships its text, with
  100% coverage enforced by `sutra test`; `sutra lint` warns about fields without an explanation; the Build workbench has
  an About tab with a live preview; text can be translated (`about.<lang>.yaml`). Masked values never appear.
- **Ask about this page** (off by default, per pack): a question box answered by an AI model you configure, given only the
  page's explanation (labels, never masked values), with prompt-injection defences, limits and clean failures.
- **Share with a note:** send a view or a panel, pinned to its date, to people and roles; they open it with their own
  rights. Notices in the bell and an inbox; email (an outbox with retries, digests, per-person opt-outs) carrying values as
  each recipient may see them; Teams, Slack and signed-webhook bridges; an optional watermarked picture, drawn for the
  most restricted recipient. Links survive sign-in.
- **Discussion:** threads on a view, a panel or a field, beside About in one drawer: @mentions, quotes of values,
  "open as it was", a 15-minute edit window, retract, moderation, follow and mute. The old Notes became threads.
- **Compliance:** tamper-evident history (hash chains and seals), retention, legal holds, an eDiscovery export with
  checksums, and an Admin → Collaboration page (moderation, holds, export, verify, outbox, bridges).
- **A third adversarial QA round** on the new features: 29 findings (2 High), all fixed or addressed
  (`docs/qa/2026-10-05/`). Product decisions recorded: emails and chat bridges carry values, rendered for their audience.
- **Faster, sturdier drill:** about 18–20 minutes (from 35–45); one shared server for the browser tests; a failed console
  test is retried once and logged as flaky; timeouts report what the page was doing.

**Upgrade notes:**
- Run `./mvnw clean` (or *Rebuild Project*) once after pulling, so no Java 25 class files remain in `target/`.
- Signed tokens for deleted or disabled accounts are now refused (`drishti.security.registered-users-only`, default
  `true`); single sign-on is unaffected (it registers the account at sign-in).
- New settings: `drishti.explain.*`, `drishti.about.*`, `drishti.explain.ask.*`, `drishti.collab.*` (all collaboration
  email, bridges and snapshots are off until configured), console `ui.about_prefetch`. See CONFIGURATION.md.

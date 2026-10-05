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
# Drishti 1.16.0 — release notes

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

# Previous release: Drishti 1.15.0 — release notes

*2026-10-04.* **A KPI tile, five developer guides with generated screenshots, and one source of truth per topic.**

- **`metric`, the 21st panel kind:** a big-number KPI tile with one formatted, toned figure, an optional change beside
  it (`delta`), a unit and a caption. The workbench offers it first for a lone measure, and auto-design drafts one. The
  figure never breaks inside: it shrinks with its tile. See [PANEL_KINDS.md#metric](docs/guides/PANEL_KINDS.md#metric).
- **Five developer guides**, each with real, worked examples and screenshots made by a script, so they stay true:
  - [PANEL_DEVELOPER_GUIDE.md](docs/guides/PANEL_DEVELOPER_GUIDE.md): add a panel kind end to end, worked through the
    real `metric` code, with an ordered checklist and the tests that catch a missed step. Plus
    [PANEL_KINDS.md](docs/guides/PANEL_KINDS.md), the catalogue of all twenty-one kinds (where F1 lands).
  - [CONNECTOR_DEVELOPER_GUIDE.md](docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md): the plugin interface method by
    method, testing with the testkit contracts, and a teaching plugin that is built and contract-tested but not shipped.
  - [PACK_DEVELOPER_GUIDE.md](docs/guides/PACK_DEVELOPER_GUIDE.md): a help-desk pack built step by step with real outputs,
    from `pack.yaml` to the signed registry.
  - [SUTRA_DEVELOPER_GUIDE.md](docs/guides/SUTRA_DEVELOPER_GUIDE.md): writing, testing and shipping Sutras.
  - [BUILD_WORKBENCH_TUTORIAL.md](docs/guides/BUILD_WORKBENCH_TUTORIAL.md): four complete projects in 46 steps with
    66 pictures: from JSON files, by hand, changing a live Sutra, and keyboard only.
- **The documentation was harmonised:** guides teach and link; reference documents own each setting. The retired guides
  (PANELS, PLUGIN_GUIDE, CONNECTOR_GUIDE, RACHANA_GUIDE, SUTRA_CLI and the in-app copies) are short pointers, their help
  addresses redirect, about 70 links were repointed, and about 520 repeated lines were removed from the connector
  references.
- **Fixed in the Build workbench** (found while writing the tutorial): auto-design failed when a top-level field also
  occurs nested; dropping a field on a `kv` panel left it empty; after a typed YAML edit the editor stopped following the
  canvas and could save stale text; a rebase left the "base moved" flag; approving your own design showed "base moved"
  pointing at your own new version.

**Upgrade notes:** none required. Old help addresses (`/help/panels`, `/help/plugins`, `/help/connectors`,
`/help/build-a-pack`, `/help/sutra-guide`, `/help/rachana-guide`) redirect to the new guides.

# Older release: Drishti 1.14.1 — release notes

*2026-10-03.* **LZ4 Delta tables, clean errors for oversized requests, and masking of copied values.**

- **Delta Lake tables compressed with LZ4 or LZ4_RAW are read** by both engines (tables written by pyarrow, pandas,
  delta-rs, Spark and Hadoop). The native engine decodes them; the Hadoop engine routes LZ4 files through the same
  decoder (`lz4-via-native`, on by default). A corrupt page names its codec, table and file. See
  [DELTA_CONNECTOR §16](docs/connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop).
- **Oversized requests get a clear `413 DRS-5005`** naming the size and the limit (before, a very large upload ended in
  a broken connection). Limits per path in `drishti.http.request-limits`; the console says "This file is too large: X MB;
  the limit is Y MB".
- **Masking of copied values (opt-in):** with `drishti.security.mask-copies: true`, a masked field's value is also hidden
  where it is copied into other text of the same document ("Captured by •••"). Best effort: exact copies only; mask
  free-text fields that carry sensitive values too (dotted `redact` paths), and keep sensitive values out of free text at
  the source. The shipped configuration masks `lifecycle.timeline.description` along with the trader.
- Passing builds no longer print an alarming LZ4 stack trace from a test that refuses an unsupported codec on purpose.

**Upgrade notes:** none required. New settings: `drishti.http.request-limits.*`, `server.tomcat.max-swallow-size`
(`DRISHTI_MAX_SWALLOW`), `drishti.security.mask-copies*`, Delta `lz4-decoder` and `lz4-via-native`.

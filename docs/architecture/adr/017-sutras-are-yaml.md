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
# ADR-017: Sutras are YAML, through and through

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

Supersedes [ADR-011](011-sutras-are-markdown-documents.md). Amends ADR-003 and ADR-008 (file name).

## Context
ADR-011 made the standard Sutra a Markdown document (`*.sutra.md`) with one fenced `sutra` block holding the
YAML, so that each layout could carry the *why* beside the *what*. Plain `*.yaml` Sutras still loaded. In practice
that left two dialects of one language:

- People had to learn which one to write, and tools, tests and generators had to handle both.
- Ordinary YAML tooling did not see a Markdown Sutra as YAML: no JSON Schema completion, no YAML linter, no `yq`,
  no YAML highlighting in a code review. Studio needed its own Markdown editor to make up for it.
- The prose drifted. Much of it restated the layout ("Applies to", a "Panels" table, the strip) and went stale the
  first time someone changed the block without touching the text around it.
- Nothing in a file said which version of the language it was written in, so the grammar could not evolve safely.

## Decision
- **One dialect.** A Sutra is one YAML file, `<name>.v<N>.sutra.yaml`. Markdown Sutras are no longer read.
- **A language version.** The first key is `rachana: 1`. A file without it, or with a version this server does not
  read, is refused (`DRS-2009`). A future, incompatible grammar becomes `rachana: 2`, and a server can say exactly
  which files it does not understand instead of misreading them.
- **Prose has a place in the file.** Optional plain-text keys: `description` (one paragraph) and `notes` (longer, a
  `|` block) at the top, and `description` on any panel. They are text for people: never evaluated and not part of
  the view. What can be derived from the layout (which kind it applies to, its panels, its keys) is not written
  down at all; tools derive it, so it never drifts.
- **Strict parsing, unchanged.** Every unknown key is an error with its line and column (`DRS-2011`), so a typo in
  a key cannot pass silently. A `.sutra.md` or a plain `.yaml`/`.yml` file in a Sutra folder is reported
  (`DRS-2004`) with the fix, rather than skipped.
- **The schema is served.** `GET /api/v1/rachana/schema` returns the JSON Schema (draft 2020-12) of the language,
  generated from the grammar with this server's entity kinds and formats. Studio becomes a YAML editor that
  completes and checks against it; any editor that reads JSON Schema can do the same.
- **A one-step migration.** `python3 tools/rachana/md_to_yaml.py <file-or-folder> --delete` converts old files:
  the block becomes the file, `rachana: 1` is added, and the prose that is not derivable becomes `notes`. All 228
  shipped Sutras were converted this way and checked to build the same layouts. The pack generators write YAML.
- **Documentation follows.** A complete Sutra in a document is a ```` ```yaml ```` block whose first line (after
  comments) is `rachana: 1`; the test suite parses every one, so readers can paste them.

## Consequences
- One format to learn, teach, generate and review. Editors, linters and AI assistants treat a Sutra as the YAML it
  is; diffs in review are diffs of the layout.
- Prose is plain text, not rendered Markdown: no headings, tables or images in a Sutra. Longer design notes, and
  pictures, belong in the pack's guides, which can link to the Sutra.
- Anyone with `*.sutra.md` files (a site directory, a private pack) must convert them once; until then the server
  reports each as `DRS-2004` with the command to run, and keeps serving the rest.
- The schema and the parser come from the same grammar tables, so completion never offers what the parser refuses.
  The parser stays the authority: it also compiles every expression, which a schema cannot.
- Changing the grammar incompatibly now means a new `rachana:` version, with a converter, and an ADR.

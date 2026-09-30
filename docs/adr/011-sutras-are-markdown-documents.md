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
# ADR-011: Sutras are Markdown documents

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
A Sutra's YAML says *what* the layout is but not *why*: why the strip leads with MTM, why a panel sits on
the right, which mockup it follows. That knowledge lived in commit messages and people's heads. Markdown is
the format most people, and AI assistants, read and write most fluently, and packs with a hundred or more
Sutras need them to document themselves.

## Decision
The standard Sutra file is `<name>.v<N>.sutra.md`: a Markdown document with exactly one fenced block
marked `sutra` that holds the Rachana layout (YAML syntax, Rachana-EL in string values, as in ADR-003).
The parser reads only that block; it blanks the other lines instead of removing them, so problems keep
the Markdown file's line numbers. Plain `*.yaml` Sutras still load. Studio is a Markdown editor (toolbar,
Rachana snippets, outline, rendered Document tab). `tools/sutra_to_md.py` converts YAML Sutras.

## Consequences
Each Sutra carries its own documentation, rendered in Studio and readable on any Git host. The grammar is
unchanged, so existing tooling, the JSON schema and ADR-003 still hold for the block. Rendered prose is
authored content: the console renders it without raw HTML and with only safe links. Amends ADR-003 and
ADR-008 (file name).

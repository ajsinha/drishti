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
# Tutorial 5 · Build a domain pack

A **domain pack** teaches Drishti a new industry: its kinds of entity, their mnemonics, how they link, how their
screens look, and some sample data. It is configuration and content, not code. This tutorial builds a small pack
for a library, with two kinds (book titles and authors), and opens it in the terminal.

There are two ways to make a pack:

| Way | Best for | How |
|---|---|---|
| **Generate it** (this tutorial) | a pack with several kinds and many consistent documents | describe kinds and documents in a short Python script; the common pack builder checks them and writes everything |
| **Write it by hand** | a handful of kinds, or an existing pack you want to extend | write `pack.yaml`, Sutras and samples yourself; see [Domain packs](packs) |

Every pack after the banking family (liquidity, climate, operational risk, retail banking, genomics, politics and
society, economics) was generated this way, by `tools/packgen/common/packbuild.py`.

## What you need

- the repository checked out, and Python 3 with `pyyaml` (or `uv`, which can supply it);
- the server and console running (see *Getting started*), so you can open the result.

## 1. Create the generator script

Create `tools/packgen/library/make.py`. Start with the imports every generator uses:

```python
# tools/packgen/library/make.py: the top of the file
"""The library pack: book titles and authors.

    python3 tools/packgen/library/make.py            write packs/library
    python3 tools/packgen/library/make.py --check    fail if packs/library differs from what would be written
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
import packbuild as PB  # noqa: E402  the common pack builder
import taxonomy as T  # noqa: E402  the banking packs' link fields, so yours cannot clash with them
from risk_model import Kind, Panel as P  # noqa: E402
```

(Every file in the repository also starts with the project's copyright header; copy it from any other
`make.py`, or run `python3 tools/license_headers.py --fix` afterwards.)

## 2. Describe the kinds

A `Kind` names an entity type and describes its screen. Its arguments, in order:

| Argument | Example | Meaning |
|---|---|---|
| kind | `"book-title"` | The kind's name: lower-case, unique across all enabled packs (`book` is already a banking kind). |
| mnemonic | `"TITLE"` | What users type: `TITLE TITLE-0001`. |
| prefix | `"TITLE-"` | Every id starts with it; it becomes the id pattern, so a bare `TITLE-0001` also opens. |
| label | `"Book"` | The human name. |
| group | `"Library"` | Groups the Sutras (`sutras/library/`). |
| desc | `"A title in the catalogue…"` | One sentence, used in the Sutra and the guide. |
| id_field | `"titleId"` | The document field that holds the id. |
| strip | list of `(label, path, format, tone, emphasis)` | The header figures, at most eight. |
| panels | list of `Panel` | The body. |
| `links=` | `{"author": ("author", "Author")}` | Document field → (kind it points at, label). Makes the field a link. |
| `badge=` | `"$.onLoan + ' on loan'"` | The short text shown beside a link to this kind. |

A `Panel` is `P(kind, id, title, rows, columns, …)` with `kind` one of `kv`, `table`, `line`, `area`, `hbar`,
`ladder` or `surface`; columns are `(label, path, format, tone, total)`; and `x=`, `y=`, `label=`, `value=`,
`fmt=`, `key=`, `area=` as in a Sutra. Paths are Rachana-EL, exactly as in a Sutra.

Add this to the script:

```python
# the two kinds
G = "Library"
KINDS = [
    Kind("book-title", "TITLE", "TITLE-", "Book", G, "A title in the catalogue: author, copies and loans.", "titleId",
         [("Title", "$.title", None, None, True), ("Author", "$.authorName", None, None, False),
          ("Copies", "$.copies", "amount0", None, False), ("On loan", "$.onLoan", "amount0", None, False)],
         [P("line", "loans", "Loans per month", "$.loansByMonth", x="month", y="loans", fmt="amount0", key="F2"),
          P("table", "copies", "Copies", "$.copyList", [("Barcode", "@.barcode", None, None, False),
            ("Branch", "@.branch", None, None, False), ("Status", "@.status", None, "status", False)], key="F3")],
         links={"author": ("author", "Author")}, badge="$.onLoan + ' on loan'"),
    Kind("author", "AUTH", "AUTH-", "Author", G, "An author and their titles.", "authorId",
         [("Name", "$.name", None, None, True), ("Titles", "$.titleCount", "amount0", None, False)],
         [P("table", "titles", "Titles", "$.titles", [("Title", "@.title", None, None, False)], key="F2")]),
]
```

Every generated Sutra ends with *How this view was built* and binds **F8** (impact) and **F9** (raw JSON). A kind
with `links` also gets a *Linked entities* panel and **F7** to its first link; a kind with no links gets neither.

## 3. Write the documents

One function returns every document, by kind and then by id. Two rules:

- **Consistent:** every link must point at a document that exists (an author's `titleCount` should match the titles
  that name them).
- **Deterministic:** the same input gives the same output (seed any random generator from the id), so `--check`
  can tell whether the committed pack is current.

```python
# the sample documents
def build() -> dict[str, dict[str, dict]]:
    meta = {"source": "catalogue", "generation": 1}
    docs = {"book-title": {}, "author": {}}
    docs["author"]["AUTH-001"] = {
        "authorId": "AUTH-001", "name": "Ada Okafor", "titleCount": 1,
        "titles": [{"title": "Rivers of Light"}], "_meta": meta}
    docs["book-title"]["TITLE-0001"] = {
        "titleId": "TITLE-0001", "title": "Rivers of Light", "author": "AUTH-001", "authorName": "Ada Okafor",
        "copies": 4, "onLoan": 3,
        "loansByMonth": [{"month": "2026-07", "loans": 9}, {"month": "2026-08", "loans": 11}, {"month": "2026-09", "loans": 14}],
        "copyList": [{"barcode": "31000451", "branch": "Central", "status": "On loan"},
                     {"barcode": "31000452", "branch": "Central", "status": "Available"}],
        "_meta": {**meta, "live": True, "walk": {"onLoan": 1}}}
    return docs
```

`_meta` says where a document came from. `"live": true` with `"walk": {"onLoan": 1}` makes the demo source tick
that field up and down, so the view shows live updates.

## 4. Describe the pack and call the builder

```python
# the pack itself
def spec() -> PB.PackSpec:
    return PB.PackSpec(
        name="library", title="Library", description="Titles, authors and loans.",
        requires=[],                                    # packs this one extends; written as extends: in pack.yaml
        generator="tools/packgen/library/make.py",
        domains={"library": KINDS},                     # data domain -> kinds; one Delta Lake folder each
        examples=[("TITLE TITLE-0001", "A book · loans and copies"), ("AUTH AUTH-001", "An author · titles")],
        roles={"librarian": {"kinds": [k.kind for k in KINDS], "raw": True}})


if __name__ == "__main__":
    PB.main(spec(), build(), T.graph_fields())
```

| `PackSpec` field | Meaning |
|---|---|
| `name`, `title`, `description` | The pack's id (its folder name and what `DRISHTI_PACKS` lists), display name and summary. |
| `requires` | Packs this one builds on, e.g. `["banking-core"]` to link to counterparties. |
| `domains` | Data domain → kinds. Each domain becomes a Delta Lake connector `<domain>-store`. |
| `examples` | Commands shown on the landing page and in the pack's guide. |
| `roles` | Roles the pack adds: which kinds they may open, and whether they see raw JSON. |
| `impact=` | Optional F8 configuration (`follow`, `measures`, `formats`); see [F8 · Impact](impact). |
| `external_ids=` | Ids that live in a required pack, so links to them pass the check. |
| `overview=` | Extra prose for the generated guide. |

## 5. Run it

```bash
# write packs/library
python3 tools/packgen/library/make.py
```

You should see:

```text
library: wrote 8 files (2 documents, 2 kinds)
```

Before writing anything the builder checks that every id field matches its key, every link resolves (to this pack
or, through `external_ids`, to a pack it requires), every path a strip or panel's rows reads exists in every
document, and no link field already means another kind in the banking packs. A failure stops it and names the
document, for example:

```text
library data is inconsistent:
  book-title/TITLE-0001: author -> author/AUTH-002 does not exist
```

Other commands:

```bash
# fail if packs/library differs from what the script would write (tools/drill.sh runs this for every generator)
python3 tools/packgen/library/make.py --check

# also build ten business days of history in the Delta Lake, for the date picker
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/library/make.py --lake data/delta
```

## 6. What you get

```text
packs/library/
  pack.yaml                            kinds, mnemonics, id patterns, link fields, badges, roles, connector, routes, examples
  sutras/library/book-title.v1.sutra.md   one Markdown Sutra per kind, with prose
  sutras/library/author.v1.sutra.md
  samples/book-title/TITLE-0001.json   the documents, for the built-in demo source
  samples/author/AUTH-001.json
  samples/catalog.json                 the list the demo source and suggestions read
  guides/library.md                    an overview guide: kinds, fields, links and "Try it" commands
  config/help.yaml                     the guide's card under Help → Domain packs
```

Never edit these files by hand: change the script and run it again (each file says so at the top).

## 7. Open it

Restart the server with the pack enabled, for example on its own:

```bash
# start the server with only the library pack
DRISHTI_PACKS=library java -jar drishti-server/target/drishti-server-*-exec.jar
```

or alongside others: `DRISHTI_PACKS=finance,library`. Restart the console too, so it reads the new guide. Then:

1. In the terminal, type `TI`. You should see `TITLE` (Book) in the suggestions.
2. Type `TITLE TITLE-0001` and press Enter. You should see the pill *Library · Book* and the id *TITLE-0001*; the
   strip *Title Rivers of Light* (highlighted), *Author Ada Okafor*, *Copies 4*, *On loan 3*; the panels *Loans
   per month* (F2) and *Copies* (F3); and, on the right, *Linked entities* with *Author AUTH-001*. *On loan*
   ticks.
3. Press **F7**: the author opens.
4. Open **Help → Domain packs**: the *Library pack* guide is there.

If an admin assigns packs per user, add `library` to your user under **Admin → Users** (see
[Users and roles](user-management)).

!!! note "Live data and history"
    Live views work from the samples straight away. Real history for the date picker needs the Delta Lake built
    with `--lake`; without it, a picked date shows the samples as they are, with a note that the source is not a
    dated source.

## 8. Make it part of the build

- Commit `tools/packgen/library/make.py` and `packs/library/`.
- Add `library` to the pack list in `drishti-server/src/test/java/com/ash/drishti/server/DomainPacksTest.java`, so
  every example command is checked to open with no empty panel and no missing link.
- `tools/drill.sh` runs every generator with `--check`, so a pack that drifts from its script fails the build.

## Next steps

- Real data: add a connector for your database or files, and route the kinds to it; see
  [Sources and plugins](plugins).
- Better screens: edit a generated Sutra's layout in the script, preview ideas in [Sutra Studio](sutra-studio),
  and read [The Sutra guide](sutra-guide).
- Vocabulary: a `config/semantics.yaml` in the pack teaches labels and acronyms to every screen; see
  [Domain packs](packs).

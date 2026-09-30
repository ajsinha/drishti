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

A domain pack is configuration and content: kinds, Sutras, samples, a guide and, optionally, Delta Lake data. You
can write one by hand (see *Domain packs*), but every pack after the banking family was generated from a short
Python description by the common pack builder, `tools/packgen/common/packbuild.py`. This tutorial builds a small
pack for a library, the way the genomics, economics and other packs were built.

## 1. Describe the kinds

A `Kind` names an entity type, its mnemonic and identifier prefix, the header strip and the panels. Paths are
Rachana-EL, exactly as in a Sutra:

```python
from risk_model import Kind, Panel as P

G = "Library"
KINDS = [
    Kind("book-title", "TITLE", "TITLE-", "Book", G, "A title in the catalogue: author, copies and loans.", "titleId",
         [("Title", "$.title", None, None, True), ("Author", "$.author", None, None, False),
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

Each strip entry is `(label, path, format, tone, emphasis)`. `links` maps a field of the document to the kind it
points at; the builder declares the field for the links panel. A kind with no links gets no links panel.

## 2. Write the documents

One function returns every document, by kind and id. Keep values consistent with each other (an author's
`titleCount` equals the titles that link to them), and deterministic (seed a random generator from the id), so
`--check` can verify the pack in the build:

```python
def build() -> dict[str, dict[str, dict]]:
    docs = {"book-title": {}, "author": {}}
    docs["author"]["AUTH-001"] = {"authorId": "AUTH-001", "name": "Ada Okafor", "titleCount": 1,
                                  "titles": [{"title": "Rivers of Light"}], "_meta": {"source": "catalogue", "generation": 1}}
    docs["book-title"]["TITLE-0001"] = {"titleId": "TITLE-0001", "title": "Rivers of Light", "author": "AUTH-001",
                                        "copies": 4, "onLoan": 3, "loansByMonth": [{"month": "2026-08", "loans": 11}],
                                        "copyList": [{"barcode": "31000451", "branch": "Central", "status": "On loan"}],
                                        "_meta": {"source": "catalogue", "generation": 1}}
    return docs
```

`_meta.live: true` with `walk: {field: step}` makes a document tick in the demo source.

## 3. Describe the pack and build it

```python
def spec() -> PB.PackSpec:
    return PB.PackSpec(
        name="library", title="Library", requires=[], generator="tools/packgen/library/make.py",
        description="Titles, authors and loans.",
        domains={"library": KINDS},                      # data domain -> kinds (a Delta Lake folder each)
        examples=[("TITLE TITLE-0001", "A book · loans and copies")],
        roles={"librarian": {"kinds": [k.kind for k in KINDS], "raw": True}})

if __name__ == "__main__":
    PB.main(spec(), build(), T.graph_fields())
```

```bash
python3 tools/packgen/library/make.py            # writes packs/library
python3 tools/packgen/library/make.py --check    # fails if packs/library differs (drill runs this)
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/library/make.py --lake data/delta
```

Before writing anything the builder checks that every id field matches its key, every link resolves (to this
pack or, through `external_ids`, to a pack it requires), every path a strip reads exists, and no link field
already means another kind in the banking packs. Any failure stops it with the offending document named.

## 4. What you get

```text
packs/library/
  pack.yaml                  kinds, mnemonics, id patterns, link fields, badges, roles, connector and routes
  sutras/library/*.sutra.md  one Markdown Sutra per kind, prose included
  samples/…                  the documents, for the demo source
  guides/library.md          an overview guide with the kinds and example commands
  config/help.yaml           the guide's card in the help centre
```

The connector is a Delta Lake connector for the `library` data domain; the lake holds ten business days per
entity. Enable the pack with `DRISHTI_PACKS=library`, open `TITLE TITLE-0001`, and add the pack to the
`DomainPacksTest` list so every example is checked to open with no empty panel and no missing link.

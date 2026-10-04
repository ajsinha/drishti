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
# Runbook: a Sutra is broken, or an edit does not show

A *Sutra* is the file that lays out the view of one family of entities, for example
`packs/trading/sutras/commodity/cmd-forward.v1.sutra.yaml` for commodity forwards. A Sutra is one YAML file, `<name>.v<N>.sutra.yaml`, whose
first key is `rachana: 1` (the Rachana language version). Sutras are read from the
enabled packs' `sutras/` folders and from the site directory (`drishti.rachana.dirs`, default `./sutras`,
environment `DRISHTI_SUTRAS`). The grammar is in [RACHANA_REFERENCE.md](../../guides/RACHANA_REFERENCE.md).

**What Drishti does with a bad Sutra.** An invalid edit never takes a view down. The server reloads a changed
file within about 250 ms (`drishti.rachana.reload-debounce`). If the new text is invalid, the last good version
of that file stays live, and the problems are reported with file, line and column. A Sutra that was never
valid is simply not used; its entities are laid out by inference alone.

The examples use `http://localhost:18480` (server) and `http://localhost:17480` (console).

## Symptoms

Any of these:

- `GET /api/v1/sutras/problems` is not `{}`.
- After editing a Sutra, views still show the old layout.
- A view's *How this view was built* panel shows `inference` only, where you expected a Sutra.
- The server log has lines starting `sutra problem`.
- Saving in Studio fails with a `DRS-2xxx` message.
- After an upgrade to 1.11, `problems` lists `.sutra.md` files (`DRS-2004`): Markdown Sutras are no longer read.

## Diagnosis

### Step 1. List the problems

```bash
curl -s http://localhost:18480/api/v1/sutras/problems | python3 -m json.tool
```

When all is well you should see:

```json
{}
```

Otherwise each file with problems is listed with its problems, for example:

```json
{
    "packs/trading/sutras/commodity/cmd-forward.v1.sutra.yaml": [
        {
            "code": "DRS-2021",
            "message": "unknown panel kind 'tabel'",
            "location": {"file": "packs/trading/sutras/commodity/cmd-forward.v1.sutra.yaml", "line": 41, "column": 11}
        }
    ]
}
```

The same appears in the server log as a warning, one line per problem, in the form
`sutra problem <file>:<line>:<column> <code> <message>`:

```text
sutra problem packs/trading/sutras/commodity/cmd-forward.v1.sutra.yaml:41:11 DRS-2021 unknown panel kind 'tabel'
```

Common codes:

| Code | Meaning | Typical fix |
|---|---|---|
| `DRS-2001` | The file cannot be read or parsed (YAML syntax). | Fix indentation, quotes or colons at the reported line. |
| `DRS-2004` | A file in a Sutra folder is not a Sutra file: a `.sutra.md` (Markdown Sutras are no longer read) or a plain `.yaml`/`.yml`. | Convert a `.sutra.md` (below); rename a YAML Sutra to `<name>.v<N>.sutra.yaml`; move any other YAML file out of the folder. |
| `DRS-2009` | `rachana:` is missing, or names a language version this server does not read. | Put `rachana: 1` as the first key. |
| `DRS-2010` | A required key is missing (`missing '<key>'`). | Add the key. |
| `DRS-2011` | Unknown key (often a typo). | Correct the key's spelling. |
| `DRS-2012` | A value has the wrong type (text, list or mapping expected; `description` and `notes` must be text). | Quote the value, or give the shape the message names. |
| `DRS-2020` | Bad name or version, or no site directory to save into. | Lower-case kebab names, `version` a positive integer; set `drishti.rachana.dirs`. |
| `DRS-2021` | Unknown panel kind. | Use one of the kinds in RACHANA_REFERENCE.md. |
| `DRS-2022` | A panel lacks a required option. | Add the option named in the message. |
| `DRS-2023` | An option the panel kind does not take (`body` outside `tabs`, `search` outside `table`/`ladder`, …). | Remove it, or use a kind that takes it. |
| `DRS-2024` | Duplicate panel id. | Rename one panel. |
| `DRS-2025` | A key is not F1–F12, or one function key is used twice. | Use a free function key. |
| `DRS-2026` | Too many figures in the strip. | Remove some; the message says the maximum (8). |
| `DRS-2027` | `area` is not `main` or `right`. | Correct it. |
| `DRS-2028` | The same name and version is defined in two files. | Give one file a new version, or delete the duplicate. |
| `DRS-2032` | The file could not be read at all (an unforeseen failure, such as one too deeply nested to read); the log has the stack trace. The other Sutras load and hot reload continues. | Simplify the file; if it looks valid, report the log entry. |
| `DRS-2101` | An expression does not compile, or is past the size limits (nested deeper than `drishti.rachana.max-expression-depth`, 200, or longer than `max-expression-length`, 10,000 characters). | Fix the Rachana-EL at the reported place; write long sums with `sum(...)`. |

### Step 1a. A `.sutra.md` or plain `.yaml` file (`DRS-2004`, `DRS-2009`)

Since 1.11 Sutras are YAML only (ADR-017). A Markdown Sutra left in a site folder is reported, not loaded:

```json
{
    "sutras/rates/my-swap.v1.sutra.md": [
        {
            "code": "DRS-2004",
            "message": "Markdown Sutras are no longer read (Sutras are YAML since 1.11): convert it with python3 tools/rachana/md_to_yaml.py sutras/rates/my-swap.v1.sutra.md --delete",
            "location": {"file": "sutras/rates/my-swap.v1.sutra.md", "line": 1, "column": 1}
        }
    ]
}
```

Run the command the message gives, from the repository root. It accepts files or folders, so one run converts a
whole site directory:

```bash
python3 tools/rachana/md_to_yaml.py sutras --delete
```

You should see one line per file, such as
`sutras/rates/my-swap.v1.sutra.md -> sutras/rates/my-swap.v1.sutra.yaml`. The converter keeps the `sutra` block
unchanged (comments included), adds `rachana: 1` at the top, and turns the prose around it into `notes:` (dropping
the tables and lines that only restated the layout). Leave out `--delete` to keep the `.md` files while you compare;
the YAML loads at once, and each `.md` stays reported (`DRS-2004`) until you delete it. Within about 250 ms, `GET /api/v1/sutras/problems` no longer lists
the file and the converted Sutra is live.

For a plain `.yaml` file the message is `a Sutra file is named <name>.v<N>.sutra.yaml; rename <file>`. If it is a
Sutra, rename it (and add `rachana: 1` at the top if it lacks it); if it is some other YAML file, move it out of the
Sutra folder.

`DRS-2009` means the file has no `rachana: 1`, or a version this server does not read, for example
`'rachana: 2' is not a language version this server reads (it reads 1)`. Add or correct the first key:

```yaml
rachana: 1
sutra: my-swap
version: 1
match: { kind: trade, where: "$.productType == 'IRS_FIXFLOAT'" }
```

### Step 2. Check which version a view uses

```bash
curl -s http://localhost:18480/api/v1/views/trade/END-1000008 | python3 -c 'import json,sys; print(json.load(sys.stdin)["provenance"]["layout"])'
```

```text
Sutra cmd-forward v1 + inference
```

And the versions the server has loaded:

```bash
curl -s http://localhost:18480/api/v1/sutras | python3 -c 'import json,sys; [print(s["name"], s["latest"], s["versions"], s["where"]) for s in json.load(sys.stdin) if s["name"]=="cmd-forward"]'
```

```text
cmd-forward 1 [1] $.productType == 'CMD_FORWARD'
```

| What you see | Meaning |
|---|---|
| The old version, and `problems` lists the file | Your edit is invalid; the last good version is still live. Step 3. |
| The old version, no problems | The server never saw the edit: wrong directory, a pack that is not enabled, or a Studio save waiting for review. Step 4. |
| `inference` only | No Sutra matches this entity: the `match.where` condition is false for it, or the Sutra was never valid. |
| Another Sutra's name | A Sutra with higher `priority` (or a more specific `where`) matches first. |

### Step 3. Find the error in the Build workbench

Open the Sutra in the Build workbench, at the entity you are fixing (the old `/studio` address redirects there):

```text
http://localhost:17480/studio?sutra=cmd-forward@1&kind=trade&id=END-1000008
```

Press **Ctrl+Enter** to preview the text in the editor against the entity. Problems are listed by line; click one
to jump to it. The preview changes nothing on the server.

### Step 4. Find out why an edit was not picked up

1. Is the file in a directory the server reads? Pack Sutras are read only for enabled packs:

   ```bash
   curl -s http://localhost:18480/api/v1/packs | python3 -c 'import json,sys; print([p["name"] for p in json.load(sys.stdin)])'
   ```

2. Is hot reload on? `drishti.rachana.hot-reload` is `true` by default. With it off, changes apply at the next restart.
3. Was it saved from Studio with review on? Then it is a proposal, not yet live:

   ```bash
   curl -s http://localhost:18480/api/v1/sutras/proposals
   ```

   ```json
   {"enabled":true,"proposals":[]}
   ```

   A pending proposal is listed here and at **Build → Govern → Reviews** (`/build/reviews`) in the console. It becomes live when an approver
   approves it.

## Fixes

**Fix the file in place.** Correct it in your editor or repository and save. Hot reload applies it within about
250 ms; nothing needs restarting. Then check step 1 again.

**But not in a generated pack.** Most shipped packs are generated: their Sutras start with a comment such as
`Generated by tools/packgen/banking/make_sutras.py from the taxonomy. Edit the taxonomy, not this file.` A fix made in such a file
works at once, but the drill (`tools/drill.sh`) then fails with `… out of date`, and the next run of the
generator undoes it. Use the in-place fix only to restore service; then make the same change in the generator (or the taxonomy
it reads, `tools/packgen/banking/taxonomy.py`), run it, and commit both. The banking packs' Sutras come from `tools/packgen/banking/make_sutras.py`; the
others from `tools/packgen/<area>/make.py` ([PACK_DEVELOPER_GUIDE.md](../../guides/PACK_DEVELOPER_GUIDE.md#how-the-shipped-packs-are-generated)).
To change a generated layout for your site only, save a higher version in the site Sutra folder (Studio does
this) instead of editing the pack.

**Fix it in the Build workbench.** Its **Save** is available when `drishti.rachana.studio-save` is on (environment
`DRISHTI_STUDIO_SAVE=true`) and you have the author role. `GET /api/v1/studio/settings` tells you what you may do:

```json
{"approve":true,"review":true,"save":true}
```

Things to know:

- Studio checks the Sutra before saving, so an invalid Sutra is never written.
- It saves into the first site Sutra directory (`./sutras/<domain>/<name>.v<version>.sutra.yaml`). A pack's own
  Sutra cannot be overwritten under the same name and version (`DRS-2028`). Raise `version` (for example to 2); the
  highest version wins, and the pack file stays as it was.
- With review on (`drishti.governance.enabled`, default `true`), the save becomes a proposal. Someone with the
  approver role, or an admin, approves it at **Build → Govern → Reviews**. With four-eyes on (the default when security is
  on), nobody approves their own proposal.

**Go back to the last good version.** Revert the file in version control and save; hot reload applies it. For
Studio saves, the history of a Sutra is at `GET /api/v1/sutras/{name}/history`.

## Verification

1. `curl -s http://localhost:18480/api/v1/sutras/problems` returns `{}`.
2. The view's provenance names the new version, for example `Sutra cmd-forward v2 + inference`, and the
   *How this view was built* panel says the same.
3. No new `sutra problem` lines in the server log after the save; the log's latest
   `sutras loaded: N names, 0 problem file(s), 1 changed` line confirms the reload.

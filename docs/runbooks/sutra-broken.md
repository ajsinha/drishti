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
`packs/trading/sutras/commodity/cmd-forward.v1.sutra.md` for commodity forwards. Sutras are read from the
enabled packs' `sutras/` folders and from the site directory (`drishti.rachana.dirs`, default `./sutras`,
environment `DRISHTI_SUTRAS`). The grammar is in [RACHANA_REFERENCE.md](../RACHANA_REFERENCE.md).

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
    "packs/trading/sutras/commodity/cmd-forward.v1.sutra.md": [
        {
            "code": "DRS-2021",
            "message": "unknown panel kind 'tabel'",
            "location": {"file": "packs/trading/sutras/commodity/cmd-forward.v1.sutra.md", "line": 41, "column": 11}
        }
    ]
}
```

The same appears in the server log as a warning, one line per problem, in the form
`sutra problem <file>:<line>:<column> <code> <message>`:

```text
sutra problem packs/trading/sutras/commodity/cmd-forward.v1.sutra.md:41:11 DRS-2021 unknown panel kind 'tabel'
```

Common codes:

| Code | Meaning | Typical fix |
|---|---|---|
| `DRS-2001` | The file cannot be read or parsed (YAML syntax). | Fix indentation, quotes or colons at the reported line. |
| `DRS-2004` | A Markdown Sutra needs exactly one closed ` ```sutra ` block. | Close the block, or remove the second one. |
| `DRS-2010` | A required key is missing (`missing '<key>'`). | Add the key. |
| `DRS-2011` | Unknown key (often a typo). | Correct the key's spelling. |
| `DRS-2012` | A key must be text. | Quote the value. |
| `DRS-2020` | Bad domain or name, or no site directory to save into. | Use plain names; set `drishti.rachana.dirs`. |
| `DRS-2021` | Unknown panel kind. | Use one of the kinds in RACHANA_REFERENCE.md. |
| `DRS-2022` | A panel lacks a required option. | Add the option named in the message. |
| `DRS-2023` | `body` on a panel that is not `tabs`. | Move the content into a `tabs` panel. |
| `DRS-2024` | Duplicate panel id. | Rename one panel. |
| `DRS-2025` | An action key is not F1–F12. | Use a function key. |
| `DRS-2026` | Too many figures in the strip. | Remove some; the message says the maximum. |
| `DRS-2027` | `area` is not `main` or `right`. | Correct it. |
| `DRS-2028` | The same name and version is defined in two files. | Give one file a new version, or delete the duplicate. |
| `DRS-2101` | An expression does not compile. | Fix the Rachana-EL at the reported place. |

### Step 2. Check which version a view uses

```bash
curl -s http://localhost:18480/api/v1/views/trade/T-10452 | python3 -c 'import json,sys; print(json.load(sys.stdin)["provenance"]["layout"])'
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

### Step 3. Find the error in Studio

Open the Sutra in Studio, at the entity you are fixing:

```text
http://localhost:17480/studio?sutra=cmd-forward@1&kind=trade&id=T-10452
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

   A pending proposal is listed here and at `/studio/reviews` in the console. It becomes live when an approver
   approves it.

## Fixes

**Fix the file in place.** Correct it in your editor or repository and save. Hot reload applies it within about
250 ms; nothing needs restarting. Then check step 1 again.

**Fix it in Studio.** Studio's **Save** is available when `drishti.rachana.studio-save` is on (environment
`DRISHTI_STUDIO_SAVE=true`) and you have the author role. `GET /api/v1/studio/settings` tells you what you may do:

```json
{"approve":true,"review":true,"save":true}
```

Things to know:

- Studio checks the Sutra before saving, so an invalid Sutra is never written.
- It saves into the first site Sutra directory (`./sutras/<domain>/<name>.v<version>.sutra.md`). A pack's own
  Sutra cannot be overwritten under the same name and version (`DRS-2028`). Raise `version` (for example to 2); the
  highest version wins, and the pack file stays as it was.
- With review on (`drishti.governance.enabled`, default `true`), the save becomes a proposal. Someone with the
  approver role, or an admin, approves it at `/studio/reviews`. With four-eyes on (the default when security is
  on), nobody approves their own proposal.

**Go back to the last good version.** Revert the file in version control and save; hot reload applies it. For
Studio saves, the history of a Sutra is at `GET /api/v1/sutras/{name}/history`.

## Verification

1. `curl -s http://localhost:18480/api/v1/sutras/problems` returns `{}`.
2. The view's provenance names the new version, for example `Sutra cmd-forward v2 + inference`, and the
   *How this view was built* panel says the same.
3. No new `sutra problem` lines in the server log after the save; the log's latest
   `sutras loaded: N names, 0 problem file(s), 1 changed` line confirms the reload.

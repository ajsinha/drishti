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

# Production standard

The short list of rules a production change is judged by. One home per fact: this document holds the rules; the procedures stay in the
[operations guide](../guides/OPERATIONS_GUIDE.md) and every setting in [CONFIGURATION.md](CONFIGURATION.md).

## Upgrades

| Rule | How it is met | Enforced by |
|---|---|---|
| An upgrade swaps one release; the site's files and data are outside it | releases side by side (`/opt/drishti/releases/<version>`), one `current` link, settings in `/etc/drishti`, data in the data folder | [OPERATIONS_GUIDE, section 11](../guides/OPERATIONS_GUIDE.md#11-upgrade-and-rollback) |
| The Python libraries of a running release are never changed | each release has its own Python prefix (`<release>/python`); when the pins did not change two releases may share one prefix | `drishti.py upgrade check <new-release-dir>` fails the run that would install into the running one |
| Drishti never creates an environment and never installs a library | the operator makes the Python and runs pip; `setup` and `upgrade check` only check and print the command | `tools/test_no_venv.py`, `tools/test_upgradecheck.py` |
| A rollback needs nothing that the upgrade removed | the previous release folder and its Python stay until the new release has run a business day; the site's files, the identity database and the content store are copied before the switch | the rollback of section 11.5, tried on a scratch machine |
| No migrations | a retired settings name refuses the start and names its replacement; stored data is only read by the release that wrote it or restored from the backup | section 11.4 |
| The check is read-only | it writes, installs, starts and stops nothing and reads no secret value | `tools/test_upgradecheck.py` |

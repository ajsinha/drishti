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
# lib: Drishti's own jars

Every jar sits under the install folder, every piece of data under the data folder.

- `<home>/lib` holds Drishti's own jar(s): shipped with a release and replaced on an upgrade (made by `python tools/drishti.py build jar`,
  which builds with Maven and copies the server's exec jar here). Every tool and script finds the server jar in this one folder;
  `--jar FILE` names another for a one-off run. The tools refuse when this folder holds more than one Drishti server exec jar, or one whose version is not this install's (`build jar` replaces the old one).
- `<home>/lib/ext` holds YOUR jars (JDBC drivers, connector plugins, a company's own libraries): kept on an upgrade, read by the server
  at start (`drishti.lib.path`, `DRISHTI_LIB_PATH`; see [ext/README.md](ext/README.md)). A Drishti server jar put there is skipped.

An upgrade replaces the Drishti jar in `<home>/lib` only and keeps `lib/ext`. Jars found in `<data>/lib` (the previous default) are moved
once into `lib/ext` at the next start; Admin → Health says so.

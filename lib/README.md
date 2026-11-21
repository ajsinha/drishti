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
- `<home>/lib/drishti-server-exec.jar` is the same jar under a name that does not change with the version (a hard link, or a copy where
  the file system has none), written by the same build: IntelliJ's server launchers (JAR Applications) run it. `lib/server.args` in a service install or an image
holds the server's JVM options (`deploy/jvm/*.args`, generated from the function `drishti.py start server` runs). The tools never read it
  (their pattern `drishti-server-*-exec.jar` does not match it).
- `<home>/lib/ext` holds YOUR jars (JDBC drivers, connector plugins, a company's own libraries): kept on an upgrade, read by the server
  at start (`drishti.lib.path`, `DRISHTI_LIB_PATH`; see [ext/README.md](ext/README.md)). A Drishti server jar put there is skipped.

An upgrade replaces the Drishti jar in `<home>/lib` only and keeps `lib/ext`. Jars found in `<data>/lib` (the previous default) are moved
once into `lib/ext` at the next start; Admin → Health says so.

- `<home>/lib/wheels` holds the `drishti_lake-<version>-py3-none-any.whl` wheel (Drishti Lake's Python path: the load rules, the Python
  loader, the archive guards, the launcher of its Java tools). `pip install -r drishti-console/requirements.txt` installs it from here (a
  pure-Python wheel: the same on Linux, macOS and Windows, no executable). Where it is not installed the tools put this wheel on their path
  (`tools/lake/lakewheel.py`); for development `LAKE_PYTHON` names the product's `python` folder instead. Replaced on an upgrade.

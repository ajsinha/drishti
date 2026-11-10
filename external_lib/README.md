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
# external_lib: the console's own Python libraries

Put Python libraries in `python/` and the console picks them up at start, with no rebuild: modules (`mylib.py`), packages (folders
with `__init__.py`), or pure-Python `.whl` / `.zip` / `.egg` files. Python items in the sandbox may import one only when it is on the
allow-list `bi.python.packages`. Restart the console after a change.

**Jars do not go here.** JDBC drivers, connector plugins and any library a connector or a module needs go in the server's **lib
folder** (`drishti.lib.path`, `DRISHTI_LIB_PATH`, default `<data folder>/lib`), the one place for them. A server that finds jars in the
old `external_lib/java` folder moves them into lib once, at start, lists them in `lib/moved-<date-time>.bak` and says so in Admin → Health.

The folder is configurable: `DRISHTI_EXTERNAL_LIB=/opt/drishti/external_lib`, or the console setting `external_lib`. Default:
`external_lib` in the repository root. Only administrators should be able to write here: what is in it runs inside the console.
Details: docs/admin/CONFIGURATION.md, "Your own libraries".

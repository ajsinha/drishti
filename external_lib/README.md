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
# external_lib: your own libraries

Put libraries here and Drishti picks them up at start, with no rebuild:

| Folder | What goes in it | Who loads it |
|---|---|---|
| `java/` | jars: JDBC drivers, connector plugins, any library a plugin needs | the **server**: every jar is on its class path from the start (restart after adding or removing one) |
| `python/` | modules (`mylib.py`), packages (folders with `__init__.py`), or pure-Python `.whl` / `.zip` / `.egg` files | the **console**: importable from the start (restart after a change); Python items in the sandbox may import one only when it is on the allow-list `bi.python.packages` |

The folder is configurable: `DRISHTI_EXTERNAL_LIB=/opt/drishti/lib` (both processes), or the console setting
`external_lib`. Default: `external_lib` in the working directory (server) and in the repository root (console).
Admin → Health lists what each process found. Only administrators should be able to write here: what is in it runs
inside the server and the console. Details: docs/admin/CONFIGURATION.md, "External libraries".

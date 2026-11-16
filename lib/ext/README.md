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
# lib/ext: your jars

`<home>/lib/ext` is the site's shared lib folder (`drishti.lib.path`, `DRISHTI_LIB_PATH`; this folder is the default): JDBC drivers,
connector plugins and a company's own libraries. Every jar here is read once, at start, into one lib class loader under the kernel's;
connectors and modules see it, and it never replaces a kernel class. Restart the server after a change. Admin → Health lists each jar
with its SHA-256.

An upgrade keeps this folder; it replaces only Drishti's own jar in `<home>/lib`. Drishti's server jar never goes here (one found here
is skipped with a warning). Jars found in `<data>/lib` (the previous default) or `external_lib/java` are moved here once, listed in
`moved-<date-time>.bak`. Only administrators should be able to write here: what is in it runs inside the server. The repository keeps
the folder empty apart from this file (`.gitignore`). Details: docs/guides/ADMIN_GUIDE.md section 22.1.

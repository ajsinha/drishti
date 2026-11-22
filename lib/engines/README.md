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
# lib/engines: engine JARs

`<home>/lib/engines` is the engine lib folder (`drishti.engine-lib.path`; this folder is the default). Each engine JAR is built outside
Drishti against drishti-engine's `engine-api`, and Drishti reads it once, at start, in a class loader of its own. The JAR sees the
platform packages the server provides (engine API, drishti-tools, Drishti Lake, Rachana) and its own classes, and nothing else. Each engine
is bound from `config/engines/<id>.yaml`. Restart the server after a change. Admin → Engines lists each JAR with its SHA-384, its engines
and versions, and the state of each engine.

An upgrade keeps this folder. An engine JAR put in `lib/ext` is not loaded from there. Only administrators should be able to write here:
what is in it runs inside the server (trust `folder`). Under `drishti.engine-lib.trust: pinned`, an engine also needs its version and
SHA-384 in its engine file, and the kit's certificate beside the JAR. The repository keeps the folder empty apart from this file
(`.gitignore`). Details: docs/architecture/ENGINES_IN_DRISHTI.md.

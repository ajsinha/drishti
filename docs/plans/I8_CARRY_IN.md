<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti. Proprietary: see LICENSE. -->
# Carried into combined i8 (named, by owner decision)

Items decided in i7b to move to combined i8, each with where it was found. Combined i8 starts only on the owner's OK.

| Item | From | What | Why moved |
|---|---|---|---|
| Cluster-spanning live tables read as the first opener | G4 gate F4 (I7B_GATE_4_REVIEW.md) | Ask other members with a node identity carrying the table's audience; audit every reader by name where they read (option A) | Owner 2026-11-21: fix if within ~2 h, else park; it needs a trusted node identity and a cluster test |
| Zip writing through drishti-tools (T11) | I7B_DUPLICATES T11, G4 gate F9 | `tools-codec` gains `Zips`; Drishti's four JDK zip writers move to it (pinned shrink-only until then) | drishti-tools 1.1.0 has no zip helper |
| drishti-tools 1.2.0 helpers | G4 tools adoption | `Zips`, an HTTP client factory in tools-net, a JVM tmpdir/native-copy helper in tools-files, a configurable header mark in the guards kit | Found while adopting tools in Drishti |

## Owner requirement for engines (2026-11-22)

An engine is built and unit-tested without the Drishti application: it needs only the engine API and, if it wants them, drishti-tools,
Drishti Lake and Rachana. Its own test kit runs with no server. To use it, its JAR is copied into the Drishti server's lib folder: the
server finds it at start and binds it by configuration. No engine is compiled into Drishti, and adding or upgrading one needs no Drishti
build. This is the acceptance test of the engine design (ENGINE_INTEGRATION_DESIGN.md, refreshed at the start of combined i8), and Drishti
Risk is the first engine delivered this way.

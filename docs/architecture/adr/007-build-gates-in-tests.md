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
# ADR-007: Build gates in tests

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
Header and file-size rules must not depend on developer discipline.

## Decision
`drishti-it` holds `LicenseHeaderTest`, `SourceFileSizeTest` (1500 lines, UX exempt) and `ArchitectureRulesTest`. `tools/license_headers.py --fix` inserts headers. The parent POM is `spring-boot-starter-parent`, which aligns versions, so there is no separate BOM module. Formatting follows `.editorconfig`; Spotless and Error Prone are deferred.

## Consequences
A plain `./mvnw verify` enforces the rules. The formatter gate may be added later if drift appears.

## Amendment (1.10.3): Error Prone and Spotless are on
- **Error Prone** runs in every compile (`maven-compiler-plugin`, `.mvn/jvm.config` opens the compiler on the JDK). Its
  default error-level checks fail the build; `NonAtomicVolatileUpdate`, `LockNotBeforeTry` and
  `JavaTimeDefaultTimeZone` are raised to errors too. Style-level findings stay warnings. An intentional exception
  carries `@SuppressWarnings("<Check>")` with the reason beside it.
- **Spotless** checks at `verify`, enforcing what `.editorconfig` promises: no unused imports, no trailing
  whitespace, a final newline, spaces not tabs. `./mvnw spotless:apply` fixes a failure. A full reformatter
  (google-java-format) is deliberately not used: it would rewrite the code base's wider-line style.


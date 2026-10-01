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
# ADR-001: Backend is a spring boot application

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
The Drishti backend could be shipped as an embeddable engine plus a server (the Pravaha model) or only as a server.

## Decision
Drishti ships **only** as the Spring Boot application `drishti-server`. It is never embedded. Maven modules are internal building blocks and each contributes its beans through its own `@Configuration`. Domain classes stay constructor-injected, so they unit-test without a Spring context. `drishti-api` (the plugin SPI) stays Spring-free.

## Consequences
There is one deployable, and Spring configuration, metrics and lifecycle are available everywhere. We give up embeddability, which no user needs. ArchUnit enforces one-way module dependencies and a Spring-free SPI.

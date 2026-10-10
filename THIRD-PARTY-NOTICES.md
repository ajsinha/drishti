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
# Third-party notices

Drishti is proprietary. It bundles or builds with these third-party components under their own licences:

| Component | Licence |
|---|---|
| Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper`) | Apache-2.0 |
| Spring Boot, Spring Framework | Apache-2.0 |
| Jackson | Apache-2.0 |
| Jakarta Mail API and Eclipse Angus Mail (SMTP client, via `spring-boot-starter-mail`) | EPL-2.0 / GPL-2.0 with Classpath Exception / EDL-1.0 |
| GreenMail (tests only: an in-process SMTP server) | Apache-2.0 |
| Caffeine | Apache-2.0 |
| JCTools | Apache-2.0 |
| HdrHistogram | BSD-2-Clause / CC0 |
| Bootstrap, Bootstrap Icons (console, vendored) | MIT |
| Apache ECharts (console, vendored) | Apache-2.0 |
| FastAPI, Starlette, Jinja2, Uvicorn, httpx, PyYAML (console) | MIT / BSD |
| CodeMirror 5 and its Python mode (console, vendored) | MIT |
| FINOS Perspective (console, vendored; Rūpaka phase 0 proof of concept) | Apache-2.0 |
| DuckDB-Wasm (console, installed by `tools/fetch-duckdb-wasm.sh`, not in the repository; Rūpaka phase 0) | MIT |
| Apache Arrow JS (console, vendored bundle) and `arrow-format` with flatbuffers-java (server, Arrow IPC messages) | Apache-2.0 |
| Pyodide (console, Calc; installed by `tools/fetch-pyodide.sh`, not in the repository) | MPL-2.0 |
| CPython, numpy, pandas, scipy, statsmodels, matplotlib and their dependencies (inside Pyodide) | PSF-2.0, BSD-3-Clause, BSD-3-Clause, BSD-3-Clause, BSD-3-Clause, PSF-based (matplotlib); see each package |
| Delta Kernel, Apache Parquet (parquet-java), snappy-java, AWS SDK for Java 2.x and Apache HttpClient 5 (the Delta connector and its native engine, `drishti-deltalake`) | Apache-2.0 |
| zstd-jni (Parquet pages compressed with ZSTD) | BSD-2-Clause |
| Apache Avro (decodes Schema Registry messages in the Kafka connector, `drishti-plugin-kafka`), with Apache Commons Compress | Apache-2.0 |

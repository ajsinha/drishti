/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.nativ.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** DuckDB and SQLite are in the registry, required and without a fallback (DRISHTI_NATIVE.md sections 2.1 and 2.2). */
class RequiredLibrariesTest {

    @Test
    void duckdbAndSqliteLoadAndSayTheirVersions() {
        Map<String, Map<String, Object>> m = RequiredLibraries.toMap();
        assertThat(m).containsOnlyKeys("duckdb", "sqlite");
        for (Map<String, Object> e : m.values()) {
            assertThat(e).containsEntry("status", "OK").containsEntry("required", true).containsEntry("fallback", "none (required)");
            assertThat((String) e.get("version")).isNotBlank().doesNotStartWith("v");
        }
        assertThat((String) m.get("duckdb").get("line")).startsWith("DuckDB ").endsWith("(required, no fallback)");
        assertThat((String) m.get("sqlite").get("line")).startsWith("SQLite 3.");
    }

    @Test
    void aLibraryThatCannotLoadIsDownWithItsReason() {
        RequiredLibraries.Status s = RequiredLibraries.probe("duckdb", "jdbc:nosuchdb:", "SELECT 1");
        assertThat(s.loaded()).isFalse();
        assertThat(s.toMap()).containsEntry("status", "DOWN");
        assertThat(s.reason()).contains("no JDBC driver for jdbc:nosuchdb:");
        assertThat(s.line()).startsWith("DuckDB cannot load (required, no fallback)");
    }
}

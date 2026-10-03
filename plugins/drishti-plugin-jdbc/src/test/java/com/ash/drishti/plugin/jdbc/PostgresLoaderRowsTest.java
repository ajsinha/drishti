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
package com.ash.drishti.plugin.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A row that cannot be loaded fails the load early and precisely (the line and the id, nothing published), and an id
 * that appears twice in a day is loaded once, the last line winning, as in the JSON-lines connector. Skipped where
 * Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresLoaderRowsTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    private static String row(String domain, String id, int v, String doc) {
        return "{\"domain\":\"" + domain + "\",\"kind\":\"trade\",\"id\":\"" + id + "\",\"date\":\"2026-09-29\",\"doc\":\"" + doc
                + "\",\"columns\":{\"mtm\":" + v + "}}";
    }

    private static void load(List<String> lines, String... options) throws Exception {
        Path f = Files.createTempFile("rows", ".jsonl");
        try {
            Files.write(f, lines, StandardCharsets.UTF_8);
            List<String> args = new ArrayList<>(List.of(f.toString(), POSTGRES.getJdbcUrl(), "--user", POSTGRES.getUsername(), "--password",
                    POSTGRES.getPassword(), "--writers", "2", "--batch", "2"));
            args.addAll(List.of(options));
            PostgresLoader.main(args.toArray(new String[0]));
        } finally {
            Files.delete(f);
        }
    }

    private static String query(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void aNulNamesTheLineAndTheIdAndPublishesNothing() throws Exception {
        load(List.of(row("nul1", "T-1", 1, "{}")));
        assertThatThrownBy(() -> load(List.of(row("nul1", "T-2", 2, "{}"), row("nul1", "T-3", 3, "{}"),
                row("nul1", "T-BAD", 4, "a\\u0000b"), row("nul1", "T-5", 5, "{}"))))
                .hasMessageContaining("line 3").hasMessageContaining("T-BAD").hasMessageContaining("u0000");
        assertThat(query("SELECT string_agg(id, ',' ORDER BY id) FROM nul1.entities")).isEqualTo("T-1");   // the day as it was
    }

    @Test
    void aBadLineIsNamedToo() {
        assertThatThrownBy(() -> load(List.of(row("bad1", "T-1", 1, "{}"),
                "{\"domain\":\"bad1\",\"kind\":\"trade\",\"id\":\"T-2\",\"date\":\"nope\",\"doc\":\"{}\"}")))
                .hasMessageContaining("line 2");
    }

    @Test
    void aDuplicateIdKeepsTheLastLineInBothLoadModes() throws Exception {
        for (String[] options : new String[][] {{}, {"--recreate"}}) {
            String domain = options.length == 0 ? "dup1" : "dup2";
            load(List.of(row(domain, "T-1", 1, "{}"), row(domain, "T-2", 2, "{}"), row(domain, "T-1", 10, "{}"), row(domain, "T-3", 3, "{}"),
                    row(domain, "T-1", 100, "{}")), options);
            assertThat(query("SELECT count(*) FROM " + domain + ".entities")).isEqualTo("3");
            assertThat(query("SELECT mtm FROM " + domain + ".entities WHERE id = 'T-1'")).isEqualTo("100");
            assertThat(query("SELECT rows FROM " + domain + ".entity_dates")).isEqualTo("3");
            assertThat(query("SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + domain + "' AND table_name LIKE '%stage%'"))
                    .isEqualTo("0");
        }
    }
}

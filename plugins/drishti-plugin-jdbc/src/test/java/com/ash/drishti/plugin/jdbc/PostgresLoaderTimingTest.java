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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * How long {@link PostgresLoader} takes for a book (run by hand, not in the build): {@code ./mvnw -o test -pl
 * plugins/drishti-plugin-jdbc -Dtest=PostgresLoaderTimingTest -Ddrishti.timing.jsonl=FILE}, FILE written by
 * {@code bulk_trades.py --trades 10000 --days 3 --jsonl FILE}. Prints the first load (an empty table) and three reloads
 * of the same days over it.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "drishti.timing.jsonl", matches = ".+")
class PostgresLoaderTimingTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    private static long load(Path file, String... options) throws Exception {
        String[] args = new String[6 + options.length];
        args[0] = file.toString();
        args[1] = POSTGRES.getJdbcUrl();
        args[2] = "--user";
        args[3] = POSTGRES.getUsername();
        args[4] = "--password";
        args[5] = POSTGRES.getPassword();
        System.arraycopy(options, 0, args, 6, options.length);
        long t0 = System.nanoTime();
        PostgresLoader.main(args);
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Test
    void timesAFirstLoadAndReloads() throws Exception {
        Path file = Path.of(System.getProperty("drishti.timing.jsonl"));
        long lines;
        try (var s = Files.lines(file)) {
            lines = s.filter(l -> !l.isBlank()).count();
        }
        long first = load(file, "--recreate");
        long[] again = {load(file), load(file), load(file)};
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM trading.entities")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(lines);
        }
        System.out.printf("TIMING %,d rows: first load %,d ms; reloads %,d / %,d / %,d ms%n", lines, first, again[0], again[1], again[2]);
    }
}

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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A database connection is borrowed for one unit of work and returned at once, so it can never leak and is never held long
 * (docs/connectors/CONNECTOR_FILES.md, Connection pools). In main code:
 *
 * <ul>
 *   <li>{@code getConnection(} (a {@code DataSource} or {@code DriverManager}) and {@code .borrow()} (a pool) appear only in the
 *       resource list of a try-with-resources, or (for {@code .borrow()}) in a {@code return} of a helper that is itself used that way;
 *   <li>{@code createStatement(}, {@code prepareStatement(} and {@code prepareCall(} likewise, so a statement is closed with its block;
 *   <li>no field holds a {@code Connection}, {@code Statement} or {@code ResultSet} (a field outlives the unit of work, and a
 *       stream or a live subscription could hold it); the pool classes and the embedded databases (SQLite, DuckDB, in-process files
 *       that are not pooled) are listed below, each with its reason.
 * </ul>
 * The rule is a source scan like {@link NoBlockingUnderSynchronizedTest}; a new exception needs a line in a list below.
 */
class ConnectionDisciplineTest {

    /** Files that open a connection outside a try-with-resources, and why: the pool's factory hands it to the pool. */
    private static final Map<String, String> OPENS_FOR_THE_POOL = Map.of(
            "plugins/drishti-plugin-jdbc/src/main/java/com/ash/drishti/plugin/jdbc/JdbcPool.java", "the pool's connection factory: the pool owns and closes it",
            "plugins/drishti-plugin-duckdb/src/main/java/com/ash/drishti/plugin/duckdb/DuckDbFile.java", "embedded DuckDB: the in-process database handle, closed on detach or replacement",
            "drishti-server/src/main/java/com/ash/drishti/server/bi/poc/PocQueryService.java", "embedded DuckDB: the in-process database handle, closed on shutdown",
            "drishti-server/src/main/java/com/ash/drishti/server/bi/engine/DuckDbEngine.java", "embedded DuckDB: the in-process database handle, closed on shutdown");

    /** Files that keep a connection in a field, and why. */
    private static final Map<String, String> MAY_HOLD_A_CONNECTION = Map.of(
            "plugins/drishti-plugin-duckdb/src/main/java/com/ash/drishti/plugin/duckdb/DuckDbFile.java", "embedded DuckDB: the handle and its duplicates, bounded by pool-size permits",
            "drishti-server/src/main/java/com/ash/drishti/server/bi/poc/PocQueryService.java", "embedded DuckDB: the in-process database handle",
            "drishti-server/src/main/java/com/ash/drishti/server/bi/engine/DuckDbEngine.java", "embedded DuckDB: the in-process database handle");

    private static final Pattern OPEN = Pattern.compile("\\bgetConnection\\(|\\.borrow\\(\\)|\\bcreateStatement\\(|\\bprepareStatement\\(|\\bprepareCall\\(");
    private static final Pattern TRY_RESOURCES = Pattern.compile("\\btry\\s*\\(");
    private static final Pattern FIELD = Pattern.compile(
            "^(?: {4}| {8}(?=final )|\\s*(?=(?:private|protected|public|static|volatile)\\b))"
                    + "(?:(?:private|protected|public|static|final|volatile)\\s+)*(?:java\\.sql\\.|org\\.duckdb\\.)?"
                    + "(?:Connection|Statement|PreparedStatement|CallableStatement|ResultSet|DuckDBConnection|MongoCursor<[^>]*>)\\s+\\w+\\s*(?:;|=)", Pattern.MULTILINE);

    @Test
    void connectionsAreOnlyOpenedInTryWithResources() throws IOException {
        Path root = RepositoryFiles.root();
        List<String> found = new ArrayList<>();
        for (Path p : mainSources()) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (OPENS_FOR_THE_POOL.containsKey(rel) || rel.endsWith("/api/pool/ResourcePool.java")) {
                continue;
            }
            String code = code(p);
            List<int[]> resources = resourceLists(code);
            Matcher m = OPEN.matcher(code);
            while (m.find()) {
                int at = m.start();
                if (resources.stream().anyMatch(r -> at > r[0] && at < r[1])) {
                    continue;
                }
                if (m.group().equals(".borrow()") && returned(code, at)) {
                    continue;                                       // a helper that returns the lease to a caller's try-with-resources
                }
                found.add(rel + ":" + line(code, at) + " " + m.group() + " outside a try-with-resources");
            }
        }
        assertThat(found).as("borrow a connection and open a statement in the resource list of a try-with-resources, so it is returned at once").isEmpty();
    }

    @Test
    void noFieldHoldsAConnectionOrAStatementOrAResultSet() throws IOException {
        Path root = RepositoryFiles.root();
        List<String> found = new ArrayList<>();
        for (Path p : mainSources()) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (MAY_HOLD_A_CONNECTION.containsKey(rel)) {
                continue;
            }
            String code = code(p);
            if (!Files.readString(p).matches("(?s).*\\b(?:java\\.sql|org\\.duckdb|com\\.mongodb)\\..*")) {
                continue;                                           // a message broker's Connection (JMS, AMQP) is not a database connection
            }
            Matcher m = FIELD.matcher(code);
            while (m.find()) {
                found.add(rel + ":" + line(code, m.start()) + " field " + m.group().trim());
            }
        }
        assertThat(found).as("a connection, statement or result set belongs to one unit of work, never to a field").isEmpty();
    }

    @Test
    void theScanRecognisesWhatItRefuses() {
        String bad = "void f() { Connection c = ds.getConnection(); }";
        String good = "void f() { try (Connection c = ds.getConnection(); Statement s = c.createStatement()) { } }";
        Matcher m = OPEN.matcher(bad);
        assertThat(m.find()).isTrue();
        assertThat(resourceLists(bad).stream().anyMatch(r -> m.start() > r[0] && m.start() < r[1])).isFalse();
        int opens = 0;
        Matcher g = OPEN.matcher(good);
        while (g.find()) {
            int at = g.start();
            assertThat(resourceLists(good).stream().anyMatch(r -> at > r[0] && at < r[1])).isTrue();
            opens++;
        }
        assertThat(opens).isEqualTo(2);
        assertThat(FIELD.matcher("    private Connection kept;\n").find()).isTrue();
        assertThat(FIELD.matcher("    final ResultSet rows = null;\n").find()).isTrue();
        assertThat(FIELD.matcher("        Connection c = ds.getConnection();\n").find()).isFalse();
        assertThat(FIELD.matcher("    void f(Connection c) {}\n").find()).isFalse();
    }

    private static List<Path> mainSources() {
        Path root = RepositoryFiles.root();
        return RepositoryFiles.withSuffixes(Set.of(".java")).stream()
                .filter(p -> root.relativize(p).toString().replace('\\', '/').contains("/src/main/")).toList();
    }

    /** The source with comments and string literals blanked, positions kept. */
    private static String code(Path p) throws IOException {
        char[] c = Files.readString(p).toCharArray();
        int i = 0;
        while (i < c.length) {
            if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '/') {
                while (i < c.length && c[i] != '\n') {
                    c[i++] = ' ';
                }
            } else if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '*') {
                while (i < c.length && !(c[i] == '*' && i + 1 < c.length && c[i + 1] == '/')) {
                    if (c[i] != '\n') {
                        c[i] = ' ';
                    }
                    i++;
                }
                if (i < c.length) {
                    c[i++] = ' ';
                    c[i++] = ' ';
                }
            } else if (c[i] == '"') {
                boolean block = i + 2 < c.length && c[i + 1] == '"' && c[i + 2] == '"';
                int end = i + (block ? 3 : 1);
                while (end < c.length && !(block ? c[end] == '"' && end + 2 < c.length && c[end + 1] == '"' && c[end + 2] == '"' : c[end] == '"' && c[end - 1] != '\\')) {
                    if (c[end] != '\n') {
                        c[end] = ' ';
                    }
                    end++;
                }
                i = Math.min(c.length, end + (block ? 3 : 1));
            } else {
                i++;
            }
        }
        return new String(c);
    }

    /** The [open, close] positions of every try-with-resources resource list. */
    private static List<int[]> resourceLists(String code) {
        List<int[]> out = new ArrayList<>();
        Matcher m = TRY_RESOURCES.matcher(code);
        while (m.find()) {
            int depth = 1;
            int i = m.end();
            for (; i < code.length() && depth > 0; i++) {
                char ch = code.charAt(i);
                depth += ch == '(' ? 1 : ch == ')' ? -1 : 0;
            }
            out.add(new int[] {m.end(), i});
        }
        return out;
    }

    private static boolean returned(String code, int at) {
        int start = Math.max(code.lastIndexOf(';', at), Math.max(code.lastIndexOf('{', at), code.lastIndexOf('}', at))) + 1;
        return code.substring(start, at).trim().equals("return pool") || code.substring(start, at).trim().startsWith("return");
    }

    private static int line(String code, int at) {
        return 1 + (int) code.substring(0, at).chars().filter(c -> c == '\n').count();
    }
}

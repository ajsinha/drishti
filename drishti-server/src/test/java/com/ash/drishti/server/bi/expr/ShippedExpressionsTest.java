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
package com.ash.drishti.server.bi.expr;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.Dialect;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The converter's guarantee over everything shipped (docs/architecture/BI_DESIGN.md section 3.3.7): every expression of every dataset shipped with 2.1 (the BI
 * folder and the tutorials' data, kept as written then under {@code src/test/resources/bi/shipped-2.1}) converted to DEL v2 compiles to <em>identical SQL
 * with identical parameters</em>, measure by measure, with the row rules of every role applied; and nothing shipped now has an expression in the old syntax.
 */
class ShippedExpressionsTest {

    @org.junit.jupiter.api.BeforeEach
    void readTheOriginals() {
        Expressions.configure(Expressions.Legacy.WARN, 10_000);                 // the 2.1 originals are read in the old syntax, to compare
    }

    @org.junit.jupiter.api.AfterEach
    void reset() {
        Expressions.configure(Expressions.Legacy.REFUSE, 10_000);
    }

    static final List<Path> ROOTS = List.of(Path.of("..", "config", "bi"), Path.of("..", "docs", "tutorials", "data"));
    static final List<Path> ORIGINALS = List.of(Path.of("src", "test", "resources", "bi", "shipped-2.1"));

    static List<Path> shipped() throws Exception {
        return items(ROOTS);
    }

    static List<Path> items(List<Path> roots) throws Exception {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.getFileName().toString().matches(".*\\.(dataset|model|report|board|notebook)\\.yaml")).sorted().forEach(out::add);
            }
        }
        return out;
    }

    /** The SQL and parameters of one measure alone and by the dataset's first field, as a reader holding every role the rules name; or the refusal. */
    static List<String> sql(Dataset ds, String measure) {
        Set<String> roles = new LinkedHashSet<>();
        ds.security().rows().forEach(r -> roles.add(r.role()));
        ds.security().tables().values().forEach(t -> t.layers().forEach(l -> l.forEach(r -> roles.add(r.role()))));
        SqlCompiler.Reader reader = new SqlCompiler.Reader(true, roles, Map.of("desks", List.of("DESK-A"), "name", List.of("rita")), SqlCompiler.Masks.NONE);
        Map<String, SqlCompiler.Source> sources = new LinkedHashMap<>();
        ds.tables().keySet().forEach(t -> sources.put(t, new SqlCompiler.Source("t_" + t, List.of())));
        List<String> out = new ArrayList<>();
        List<List<String>> shapes = new ArrayList<>();
        shapes.add(List.of());
        ds.hierarchies().values().stream().findFirst().ifPresent(levels -> shapes.add(List.of(levels.get(0))));
        for (List<String> fields : shapes) {
            try {
                SqlCompiler c = new SqlCompiler(ds, Dialect.DUCKDB);
                QueryRequest q = new QueryRequest(fields, List.of(measure), List.of(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9), List.of(), 100, null);
                SqlCompiler.Compiled compiled = c.compile(c.plan(q, reader), reader, sources, 101, null);
                out.add(compiled.sql() + " " + compiled.params());
            } catch (RuntimeException e) {
                out.add("refused: " + e.getMessage());
            }
        }
        return out;
    }

    @Test
    void everyShippedExpressionConvertsToIdenticalSql() throws Exception {
        int files = 0;
        int compared = 0;
        int changed = 0;
        for (Path f : items(ORIGINALS)) {
            String yaml = Files.readString(f);
            ExpressionMigrator.Result m = ExpressionMigrator.migrate(yaml);
            assertThat(m.problems()).as(f + ": expressions the converter cannot convert").isEmpty();
            changed += m.changes().size();
            for (ExpressionMigrator.Change c : m.changes()) {
                assertThat(Expressions.isLegacy(c.to())).as(c.to()).isFalse();
            }
            DatasetParser.Result before = DatasetParser.parse(yaml);
            if (before.dataset() == null || before.problems().stream().anyMatch(DatasetParser.Problem::error) || !yaml.contains("dataset:")) {
                continue;
            }
            DatasetParser.Result after = DatasetParser.parse(m.yaml());
            assertThat(after.problems().stream().filter(DatasetParser.Problem::error)).as(f + " after conversion").isEmpty();
            assertThat(after.problems()).as(f + ": no old syntax left").noneMatch(p -> Expressions.OLD_SYNTAX.equals(p.code()));
            files++;
            for (Map.Entry<String, Dataset.Measure> e : before.dataset().measures().entrySet()) {
                if (e.getValue().expr() == null) {
                    continue;
                }
                assertThat(sql(after.dataset(), e.getKey())).as(f.getFileName() + " measure " + e.getKey()).isEqualTo(sql(before.dataset(), e.getKey()));
                compared++;
            }
        }
        assertThat(files).isGreaterThanOrEqualTo(8);
        assertThat(compared).isGreaterThan(40);
        assertThat(changed).isGreaterThan(5);
    }

    @Test
    void nothingShippedIsInTheOldSyntax() throws Exception {
        for (Path f : shipped()) {
            ExpressionMigrator.Result m = ExpressionMigrator.migrate(Files.readString(f));
            assertThat(m.changes()).as(f + " still has old-syntax expressions: drishti.py bi migrate --expressions " + f + " --write").isEmpty();
            assertThat(m.problems()).as(f.toString()).isEmpty();
        }
    }
}

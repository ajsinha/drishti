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

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.check.ExpressionChecker.Scope;
import com.ash.drishti.tables.migrate.DelTranslator;
import com.ash.drishti.tables.migrate.MigrationAudit;
import com.ash.drishti.tables.migrate.TablesMigrator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The migration with DEL's real translator (docs/architecture/ARCHITECTURE.md §28 sections 2.4 and 4): DEL read by its parser, mapped by part A's
 * {@link DelToRachana}, printed by Rachana; then the layer's rewrites. Every dataset and model shipped before the switch (a copy of {@code bi/before-tables})
 * converts, the reports bound to a version-1 dataset bind its model, the outputs pass the layer's checks, and a second run changes nothing.
 */
class DelTablesTranslatorTest {

    @TempDir Path dir;

    final DelTranslator t = new DelTablesTranslator();

    String ok(String del) {
        DelTranslator.Result r = t.translate(del, Scope.AGGREGATE);
        assertThat(r.refusal()).as(del).isNull();
        return r.rachana();
    }

    @Test
    void delIntoRachanasOneSpelling() {
        assertThat(ok("True")).isEqualTo("true");
        assertThat(ok("x if c else None")).isEqualTo("c ? x : null");
        assertThat(ok("not x == 1")).isEqualTo("!(x == 1)");
        assertThat(ok("x ** 2 + y // 3")).isEqualTo("power(x, 2) + floor(y / 3)");
        assertThat(ok("0 < x <= 10")).isEqualTo("0 < x && x <= 10");
        assertThat(ok("x is None")).isEqualTo("x == null");
        assertThat(ok("startswith(NAME, \"A\")")).isEqualTo("starts_with(NAME, 'A')");
        assertThat(ok("sum(trades.MTM, where=trades.STATUS != 'MATURED')")).isEqualTo("sum(trades.MTM, where = trades.STATUS != 'MATURED')");
        assertThat(t.translate("sum(", Scope.AGGREGATE).refusal()).contains("DEL does not read it");
    }

    /**
     * The shipped datasets, models, reports and notebooks as they were before the switch (docs/architecture/ARCHITECTURE.md §28 step 5): the migration's
     * regression input while the tool lives (Q24).
     */
    static final Path BEFORE = Path.of("src", "test", "resources", "bi", "before-tables");

    @Test
    void everyShippedItemConvertsAndASecondRunChangesNothing() throws Exception {
        Path shipped = dir.resolve("bi");                                    // a copy: the consumers are rewritten in place
        for (String sub : List.of("datasets", "models", "reports", "notebooks")) {
            Files.createDirectories(shipped.resolve(sub));
            try (var files = Files.list(BEFORE.resolve(sub))) {
                for (Path f : files.filter(x -> x.toString().endsWith(".yaml")).toList()) {
                    Files.copy(f, shipped.resolve(sub).resolve(f.getFileName()));
                }
            }
        }
        Path tables = dir.resolve("tables");
        TablesMigrator m = new TablesMigrator(t);
        TablesMigrator.Plan p = m.plan(shipped, tables);
        assertThat(p.items()).as(TablesMigrator.report(p)).allMatch(i -> i.state().equals("converted"));
        assertThat(p.items()).hasSize(34);
        assertThat(p.problems()).as("the outputs pass the layer's checks").filteredOn(Problem::isError).isEmpty();
        assertThat(p.changes()).anyMatch(c -> "sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED' && trades.TRADE_STATUS != null)".equals(c.after()));
        assertThat(p.changes()).filteredOn(c -> c.key().equals("measures.Cashflow amount.expr")).singleElement()
                .satisfies(c -> assertThat(c.after()).contains("payments.CURRENCY == 'JPY' ? 1 / 148.2 :"));
        assertThat(p.consumers()).containsOnlyKeys("reports/live-trading-desk.report.yaml", "reports/live-exposure.report.yaml");
        assertThat(p.consumers().get("reports/live-trading-desk.report.yaml")).contains("model: live-trading@1");
        TablesMigrator.write(p, tables, shipped, null, MigrationAudit.NONE, null);
        assertThat(m.plan(shipped, tables).anyWrite()).as("idempotent on the shipped set").isFalse();
        assertThat(Files.list(tables.resolve("bi")).count()).isEqualTo(p.writes().size());
    }
}

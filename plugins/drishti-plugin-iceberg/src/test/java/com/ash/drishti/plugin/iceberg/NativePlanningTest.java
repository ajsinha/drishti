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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.read.TaskReader;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Planning prunes without losing a row: for each transform (and an evolved spec), a filter on the source column plans
 * fewer files than the table has, and the matching rows of the planned files are exactly the matching rows of all of
 * them.
 */
class NativePlanningTest {

    static Path root;
    static ExecutorService pool;

    @BeforeAll
    static void copy() {
        root = Fixtures.copy();
        pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterAll
    static void close() {
        pool.close();
    }

    static long micros(int y, int mo, int d, int h) {
        return LocalDateTime.of(y, mo, d, h, 0).toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
    }

    static int day(int y, int m, int d) {
        return (int) LocalDate.of(y, m, d).toEpochDay();
    }

    // columns of the transform tables: 1 id, 2 code, 3 n, 4 ts, 5 day, 6 amt
    static Stream<Arguments> filters() {
        return Stream.of(Arguments.of("t_identity", Expr.eq(2, "alpha1")), Arguments.of("t_bucket", Expr.eq(1, 7L)),
                Arguments.of("t_bucket", Expr.in(1, List.of(3L, 9L))), Arguments.of("t_bucket_str", Expr.eq(2, "beta2")),
                Arguments.of("t_truncate_str", Expr.eq(2, "albert0")), Arguments.of("t_truncate_str", Expr.cmp(2, Expr.Op.LT, "b")),
                Arguments.of("t_truncate_int", Expr.eq(3, 6)), Arguments.of("t_truncate_int", Expr.cmp(3, Expr.Op.GE, 60)),
                Arguments.of("t_truncate_dec", Expr.eq(6, new BigDecimal("3.70"))), Arguments.of("t_year", Expr.cmp(4, Expr.Op.LT, micros(2025, 1, 1, 0))),
                Arguments.of("t_month", Expr.cmp(4, Expr.Op.GE, micros(2026, 6, 1, 0))),
                Arguments.of("t_day", Expr.and(Expr.cmp(4, Expr.Op.GE, micros(2025, 3, 1, 0)), Expr.cmp(4, Expr.Op.LT, micros(2025, 9, 1, 0)))),
                Arguments.of("t_hour", Expr.cmp(4, Expr.Op.LE, micros(2024, 6, 7, 6))), Arguments.of("t_day_of_date", Expr.eq(5, day(2025, 3, 3))),
                Arguments.of("t_month_of_date", Expr.cmp(5, Expr.Op.GT, day(2025, 10, 15))),
                Arguments.of("partition_evolution", Expr.cmp(4, Expr.Op.LT, micros(2025, 1, 1, 0))),
                Arguments.of("partition_evolution", Expr.eq(2, "charlie0")), Arguments.of("t_identity", Expr.eq(1, 13L)),
                Arguments.of("spark_trades", Expr.cmp(3, Expr.Op.GE, micros(2026, 9, 30, 0))));
    }

    static boolean matches(Schema s, Object[] row, List<Integer> ids, Expr filter) {
        for (Expr.Pred p : filter.preds()) {
            Object v = row[ids.indexOf(p.fieldId())];
            Type.Primitive t = (Type.Primitive) s.field(p.fieldId()).type();
            if (v == null) {
                return false;
            }
            boolean ok = switch (p.op()) {
                case EQ -> Values.compare(t, v, p.value()) == 0;
                case IN -> p.values().stream().anyMatch(x -> Values.compare(t, v, x) == 0);
                case LT -> Values.compare(t, v, p.value()) < 0;
                case LE -> Values.compare(t, v, p.value()) <= 0;
                case GT -> Values.compare(t, v, p.value()) > 0;
                case GE -> Values.compare(t, v, p.value()) >= 0;
                default -> true;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    static List<String> matching(Table t, Snapshot s, List<FileTask> tasks, Expr filter) {
        Schema schema = t.metadata().schemaFor(s);
        List<Integer> ids = schema.columns().stream().map(NestedField::id).toList();
        List<String> out = new ArrayList<>();
        for (FileTask task : tasks) {
            try (TaskReader r = t.rows(task, schema, ids, filter, false)) {
                while (r.hasNext()) {
                    Object[] row = r.next();
                    if (matches(schema, row, ids, filter)) {
                        out.add(java.util.Arrays.deepToString(row));
                    }
                }
            }
        }
        out.sort(null);
        return out;
    }

    @ParameterizedTest
    @MethodSource("filters")
    void aFilterPrunesFilesButNoMatchingRow(String table, Expr filter) throws Exception {
        try (Catalog c = Catalogs.open(Fixtures.settings(root))) {
            Table t = c.load(table).orElseThrow();
            Snapshot s = t.currentSnapshot().orElseThrow();
            List<FileTask> all = t.plan(s, Expr.TRUE, pool);
            List<FileTask> pruned = t.plan(s, filter, pool);
            assertThat(pruned.size()).as(table + " " + filter + ": files planned of " + all.size()).isLessThan(all.size());
            List<String> want = matching(t, s, all, filter);
            assertThat(want).as("the filter matches something").isNotEmpty();
            assertThat(matching(t, s, pruned, filter)).isEqualTo(want);
        }
    }
}

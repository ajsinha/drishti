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
package com.ash.drishti.tables.read;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The scan estimate of a model whose tables come from different connectors (VIRTUAL_TABLES.md section 10, V10): the engine joins them, so
 * each side of a relationship that crosses connectors is read whole; a side the source says is larger than {@code large} rows and that its
 * table does not filter is warned about before the query runs (never refused: the reader decides).
 */
public final class ScanEstimate {

    /** Rows above which an unfiltered side of a join across connectors is warned about, unless the holder says otherwise. */
    public static final long DEFAULT_LARGE = 1_000_000L;

    /** One side of the model: its alias, table, connector, the rows its source holds (-1: unknown) and whether its table filters. */
    public record Side(String alias, String table, String connector, long rows, boolean filtered) {}

    private ScanEstimate() {}

    /** Each alias of {@code m} with its root source's connector and row estimate on {@code date}. */
    public static Map<String, Side> sides(ModelDef m, Catalogue catalogue, SourceRows source, LocalDate date) {
        Map<String, Side> out = new LinkedHashMap<>();
        for (ModelDef.Use u : m.tables().values()) {
            TableDef t = catalogue.effective(u.table()).orElse(null);
            if (t == null) {
                continue;
            }
            boolean filtered = false;
            TableDef at = t;
            for (int depth = 0; at != null && depth < 10; depth++) {
                filtered |= at.filter() != null;
                if (at.from() == null || at.from().form() != TableDef.Source.Form.TABLE) {
                    break;
                }
                at = catalogue.table(at.from().table()).orElse(null);
            }
            TableDef.Source root = at == null ? null : at.from();
            String connector = root == null ? null : source.connector(root);
            long rows = root == null ? -1 : source.estimate(root, date);
            out.put(u.alias(), new Side(u.alias(), t.name(), connector, rows, filtered));
        }
        return out;
    }

    /** The warnings: each relationship across connectors with a large unfiltered side. */
    public static List<Problem> warnings(ModelDef m, Catalogue catalogue, SourceRows source, LocalDate date, long large) {
        Map<String, Side> sides = sides(m, catalogue, source, date);
        List<Problem> out = new ArrayList<>();
        for (ModelDef.Relationship r : m.relationships()) {
            Side a = sides.get(alias(r.from()));
            Side b = sides.get(alias(r.to()));
            if (a == null || b == null || Objects.equals(a.connector(), b.connector())) {
                continue;
            }
            for (Side s : List.of(a, b)) {
                if (!s.filtered() && s.rows() > (large <= 0 ? DEFAULT_LARGE : large)) {
                    out.add(Problem.warning(TableCodes.READ, "relationships." + (r.id() == null ? r.from() + "->" + r.to() : r.id()),
                            s.alias() + " (" + s.table() + ", " + s.rows() + " rows on " + date + " from " + s.connector() + ") is joined across "
                                    + "connectors to " + (s == a ? b : a).alias() + " (" + (s == a ? b : a).connector() + ") with no filter: it is "
                                    + "read whole; filter the table or ask for fewer dates"));
                }
            }
        }
        return out;
    }

    private static String alias(String end) {
        return end == null || end.indexOf('.') < 0 ? end : end.substring(0, end.indexOf('.'));
    }
}

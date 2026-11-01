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
package com.ash.drishti.server.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.engine.source.SourceRouter;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bank's desk P&amp;L (the kind {@code desk-pnl}, VAR_GUIDE.md section 15.16): each desk's actual and hypothetical P&amp;L of a business
 * date, read from its connector as the server and kept a few minutes per date (a restated day is read again after that). Never an input of
 * the VaR engine: the backtest compares the official VaR of the day before with it.
 */
final class VarDeskPnl {

    /** One desk's P&amp;L of a date (USD); {@code legalEntity} may be null. */
    record Row(String desk, String legalEntity, double actual, double hypothetical) {}

    /** A date's rows; {@code read} false when no connector serves the kind. */
    record Day(LocalDate date, List<Row> rows, boolean read) {

        /** The sum over the rows a node takes (its desks, and its legal entity when it has one); null when no row is the node's. */
        double[] sum(java.util.Set<String> desks, String legalEntity, boolean all) {
            double a = 0;
            double h = 0;
            int n = 0;
            for (Row r : rows) {
                if ((all || desks.contains(r.desk())) && (legalEntity == null || r.legalEntity() == null || legalEntity.equals(r.legalEntity()))) {
                    a += r.actual();
                    h += r.hypothetical();
                    n++;
                }
            }
            return n == 0 ? null : new double[] {a, h};
        }

        /** Each desk's actual P&amp;L of the day among the given (all: every desk). */
        Map<String, Double> byDesk(java.util.Set<String> desks, String legalEntity, boolean all) {
            Map<String, Double> out = new LinkedHashMap<>();
            for (Row r : rows) {
                if ((all || desks.contains(r.desk())) && (legalEntity == null || r.legalEntity() == null || legalEntity.equals(r.legalEntity()))) {
                    out.merge(r.desk(), r.actual(), Double::sum);
                }
            }
            return out;
        }
    }

    private final SourceRouter router;
    private final String kind;
    private final Map<LocalDate, Day> cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(5_000)
            .expireAfterWrite(Duration.ofMinutes(5)).<LocalDate, Day>build().asMap();

    VarDeskPnl(SourceRouter router, String kind) {
        this.router = router;
        this.kind = kind;
    }

    String kind() {
        return kind;
    }

    /** A date's desk P&amp;L (empty when the source has none of that date; {@code read} false when no connector serves the kind). */
    Day day(LocalDate date) {
        Day hit = cache.get(date);
        if (hit != null) {
            return hit;
        }
        java.util.List<Row> rows = new java.util.ArrayList<>();
        boolean read;
        try {
            read = router == null ? false : router.documents(kind, AsOf.of(date), (EntityDocument d) -> {
                Row r = row(d.data(), date);
                if (r != null) {
                    rows.add(r);
                }
            });
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(VarDeskPnl.class).debug("{} not read on {}: {}", kind, date, e.getMessage());
            read = false;
        }
        Day d = new Day(date, List.copyOf(rows), read);
        cache.put(date, d);
        return d;
    }

    /** A row of the date (a row of another date, from a source that ignores the date asked, is left out). */
    static Row row(DataNode n, LocalDate date) {
        String day = VarLoader.text(n, "BUSINESS_DATE");
        String desk = VarLoader.text(n, "DESK_ID");
        double a = VarLoader.number(VarLoader.text(n, "ACTUAL_PNL"));
        double h = VarLoader.number(VarLoader.text(n, "HYPOTHETICAL_PNL"));
        if (desk == null || day != null && !day.startsWith(date.toString()) || !Double.isFinite(a) && !Double.isFinite(h)) {
            return null;
        }
        return new Row(desk, VarLoader.text(n, "LEGAL_ENTITY"), a, h);
    }

    /** Forgets what was read (a data-load of the kind). */
    void clear() {
        cache.clear();
    }
}

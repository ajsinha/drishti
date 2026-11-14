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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.pivot.PivotProperties;
import com.ash.drishti.engine.search.SearchProperties;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * What a search said, in the one shape every reader of a search gets (the search API's answer, and a live result set's {@code search}
 * when a FORM or the search page runs it through the live result sets): the query, kind and mnemonic, the condition and order, the
 * columns and their labels, scanned and matched, partial and the sources that failed, the time it took, the business date with no data
 * (and the newest that has), and what the kind offers to pivot. The rows themselves are the caller's.
 */
@Component
public class SearchAnswers {

    private final Mnemonics mnemonics;
    private final SearchProperties searchProps;
    private final PivotProperties pivots;
    private final SourceRouter router;
    private final BusinessDates dates;

    public SearchAnswers(Mnemonics mnemonics, SearchProperties searchProps, PivotProperties pivots, SourceRouter router, BusinessDates dates) {
        this.mnemonics = mnemonics;
        this.searchProps = searchProps;
        this.pivots = pivots;
        this.router = router;
        this.dates = dates;
    }

    /** The summary of a search's result (everything but its rows). */
    public Map<String, Object> summary(String q, SearchQuery query, StructuredSearch.Result r, AsOf asOf) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("kind", r.kind());
        out.put("mnemonic", mnemonics.codeFor(r.kind()));
        out.put("condition", r.condition());
        out.put("orderBy", r.orderBy());
        out.put("descending", query.descending());
        out.put("limit", query.limit());
        out.put("columns", r.columns());
        out.put("labels", r.columns().stream().collect(Collectors.toMap(c -> c, c -> HistoryController.label(c.substring(2)), (a, b) -> a,
                LinkedHashMap::new)));
        out.put("scanned", r.scanned());
        out.put("matched", r.matched());
        out.put("partial", r.partial());
        // the sources that failed or did not answer in time, and why: a partial answer says what it is missing
        out.put("failed", r.failed().entrySet().stream().map(e -> Map.of("source", e.getKey(), "reason", e.getValue())).toList());
        out.put("elapsedMs", r.elapsedMs());
        out.put("pivot", offered(r.kind()));               // Pivot (the pivot engine on the kind), when the kind's pack opts in (null otherwise)
        noData(r, asOf).ifPresent(n -> out.put("noData", n));
        return out;
    }

    /**
     * When nothing was read because the sources hold no data of the kind on the date (a date not loaded yet, or before the first):
     * the date and the newest date that has data, so the console can say so and offer that date instead of an empty result.
     */
    private Optional<Map<String, Object>> noData(StructuredSearch.Result r, AsOf asOf) {
        if (r.scanned() > 0) {
            return Optional.empty();
        }
        java.time.LocalDate day = dates.resolve(asOf).businessDate();
        return router.latestDate(r.kind()).filter(latest -> day != null && !latest.equals(day))
                .map(latest -> Map.of("businessDate", day.toString(), "latestDate", latest.toString()));
    }

    /**
     * What this kind's results offer to pivot (PIVOT_GUIDE.md section 1: Pivot opens the pivot screen on the kind, with its pack's
     * arrangement as the view): {@code {source, fields, view}}; null when its pack does not opt in (or says what cannot be read).
     */
    private Map<String, Object> offered(String kind) {
        if (!pivots.enabled()) {
            return null;
        }
        try {
            return searchProps.pivotOf(kind).map(spec -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", kind);
                m.put("fields", spec.fields().stream().map(com.ash.drishti.rachana.model.PivotSpec.Field::name).toList());
                m.put("view", com.ash.drishti.server.pivot.SpecViews.view(kind, null, spec.arrangementMap()));
                return m;
            }).orElse(null);
        } catch (IllegalArgumentException e) {
            org.slf4j.LoggerFactory.getLogger(SearchAnswers.class).warn("no Pivot for {}: {}", kind, e.getMessage());
            return null;
        }
    }
}

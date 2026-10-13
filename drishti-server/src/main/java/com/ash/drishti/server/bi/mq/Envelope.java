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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.report.Report;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A published consumer's envelope (docs/architecture/BI_DESIGN.md section 5.3, decision 2): the fields, measures and filterable fields its visuals, slicers,
 * drills, filters, bookmarks and links can produce. A reader who may view the report but has no read on its model ("Build") may ask only queries inside the
 * envelope; anything else is {@code DRS-9151} "this report cannot ask that". So a report can be shared widely without opening the model to free querying. A
 * date part of a field ({@code trades.tradeDate.month}) counts as the field (a drill changes the part). The envelope is also the index impact analysis reads:
 * which consumers use a field or a measure. Immutable.
 *
 * @param consumer the consumer's catalogue id
 */
public record Envelope(String consumer, Set<String> fields, Set<String> measures, Set<String> filters) {

    public Envelope {
        fields = Set.copyOf(fields);
        measures = Set.copyOf(measures);
        filters = Set.copyOf(filters);
    }

    /** The envelope of a report on a model (hierarchies are expanded to their levels, so a drill stays inside). */
    public static Envelope of(String consumer, Report r, ModelView v) {
        Set<String> fields = new LinkedHashSet<>();
        Set<String> measures = new LinkedHashSet<>();
        Set<String> filters = new LinkedHashSet<>();
        Map<String, List<String>> hierarchies = v == null ? Map.of() : v.def().hierarchies();
        r.filters().forEach(f -> filters.add(base(f.field())));
        r.links().forEach(l -> l.fields().forEach(x -> filters.add(base(x))));
        r.bookmarks().forEach(b -> b.filters().forEach(f -> filters.add(base(f.field()))));
        for (Report.Page p : r.pages()) {
            p.filters().forEach(f -> filters.add(base(f.field())));
            if (p.drillThrough() != null) {
                p.drillThrough().fields().forEach(x -> filters.add(base(x)));
            }
            for (Report.Visual vis : p.visuals()) {
                vis.wells().values().forEach(items -> items.forEach(x -> (isField(x, v) ? fields : measures).add(isField(x, v) ? base(x) : x)));
                vis.filters().forEach(f -> {
                    filters.add(base(f.field()));
                    if (f.by() != null) {
                        measures.add(f.by());
                    }
                });
                if (vis.sort() != null && vis.sort().by() != null) {
                    (isField(vis.sort().by(), v) ? fields : measures).add(isField(vis.sort().by(), v) ? base(vis.sort().by()) : vis.sort().by());
                }
                if (vis.hierarchy() != null) {
                    hierarchies.getOrDefault(vis.hierarchy(), List.of()).forEach(x -> fields.add(base(x)));
                }
                if (vis.drillThrough() != null && vis.drillThrough().field() != null) {
                    filters.add(base(vis.drillThrough().field()));
                }
            }
        }
        for (List<String> levels : hierarchies.values()) {                       // a hierarchy any visual shows: every level is reachable by drilling
            if (levels.stream().anyMatch(x -> fields.contains(base(x)))) {
                levels.forEach(x -> fields.add(base(x)));
            }
        }
        filters.addAll(fields);                                                  // selecting a mark (cross-filter, drill) filters on the visual's fields
        return new Envelope(consumer, fields, measures, filters);
    }

    private static boolean isField(String x, ModelView v) {
        if (!x.contains(".")) {
            return false;
        }
        String alias = x.substring(0, x.indexOf('.'));
        return v == null || v.flat().tables().containsKey(alias) || v.def().datasets().containsKey(alias);
    }

    /** {@code alias.column} of a field (a date part dropped). */
    static String base(String field) {
        if (field == null) {
            return "";
        }
        String[] p = field.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : field;
    }

    /** True when the envelope uses the field ({@code alias.column}) or the measure. */
    public boolean uses(String fieldOrMeasure) {
        return fields.contains(base(fieldOrMeasure)) || filters.contains(base(fieldOrMeasure)) || measures.contains(fieldOrMeasure);
    }

    /** Refuses a query outside the envelope ({@code DRS-9151}, each problem saying what the report cannot ask). */
    public void check(ModelQuery q, ModelView v) {
        Map<String, String> ids = v.ids();
        List<MqException.Problem> out = new ArrayList<>();
        for (int i = 0; i < q.fields().size(); i++) {
            String f = ids.getOrDefault(q.fields().get(i), q.fields().get(i));
            if (!fields.contains(base(f))) {
                out.add(MqException.Problem.of("DRS-9151", "select.fields[" + i + "]", "this report cannot ask that: it shows no '" + f + "'"));
            }
        }
        for (int i = 0; i < q.measures().size(); i++) {
            String m = ids.getOrDefault(q.measures().get(i), q.measures().get(i));
            if (!measures.contains(m)) {
                out.add(MqException.Problem.of("DRS-9151", "select.measures[" + i + "]", "this report cannot ask that: it shows no measure '" + m + "'"));
            }
        }
        for (int i = 0; i < q.where().size(); i++) {
            ModelQuery.Where w = q.where().get(i);
            String f = ids.getOrDefault(w.field(), w.field());
            if (!filters.contains(base(f))) {
                out.add(MqException.Problem.of("DRS-9151", "where[" + i + "].field", "this report cannot ask that: it does not filter on '" + f + "'"));
            }
            if (w.by() != null && !measures.contains(ids.getOrDefault(w.by(), w.by()))) {
                out.add(MqException.Problem.of("DRS-9151", "where[" + i + "].by", "this report cannot ask that: it shows no measure '" + w.by() + "'"));
            }
        }
        for (int i = 0; i < q.having().size(); i++) {
            String m = ids.getOrDefault(q.having().get(i).measure(), q.having().get(i).measure());
            if (!measures.contains(m)) {
                out.add(MqException.Problem.of("DRS-9151", "having[" + i + "].measure", "this report cannot ask that: it shows no measure '" + m + "'"));
            }
        }
        if (!q.define().isEmpty()) {
            out.add(MqException.Problem.of("DRS-9151", "define", "this report cannot ask that: a local measure needs read on the model"));
        }
        if (!out.isEmpty()) {
            throw new MqException(out);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("consumer", consumer);
        m.put("fields", fields.stream().sorted().toList());
        m.put("measures", measures.stream().sorted().toList());
        m.put("filters", filters.stream().sorted().toList());
        return m;
    }
}

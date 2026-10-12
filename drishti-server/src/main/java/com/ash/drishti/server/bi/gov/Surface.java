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
package com.ash.drishti.server.bi.gov;

import com.ash.drishti.server.bi.dataset.Composition;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.report.Report;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a dataset offers to reports (its fields {@code table.column}, measures and hierarchies) and what a report uses of it, so a change to the
 * dataset can be set against the reports that read it (docs/guides/BI_GOVERNANCE.md, "Impact"). The comparison is by name: a renamed field is a
 * removed field and an added one, because a report names fields, not meanings.
 */
public final class Surface {

    /** What a dataset offers. */
    public record Offer(Set<String> fields, Set<String> measures, Set<String> hierarchies, Set<String> tables) {

        public static Offer of(Dataset d) {
            Set<String> fields = new TreeSet<>();
            for (Dataset.Table t : d.tables().values()) {
                fields.add(t.name() + "." + Dataset.ID);
                fields.add(t.name() + "." + Dataset.DATE);
                if (t.each() != null) {
                    fields.add(t.name() + "." + Dataset.INDEX);
                }
                t.columns().keySet().forEach(c -> fields.add(t.name() + "." + c));
            }
            return new Offer(fields, new TreeSet<>(d.measures().keySet()), new TreeSet<>(d.hierarchies().keySet()), new TreeSet<>(d.tables().keySet()));
        }

        public static Offer empty() {
            return new Offer(Set.of(), Set.of(), Set.of(), Set.of());
        }
    }

    /** What changed between two offers: the names that were there and are not, and the names that are new. */
    public record Change(List<String> removedFields, List<String> removedMeasures, List<String> removedHierarchies, List<String> addedFields, List<String> addedMeasures) {

        /** True when something a report could name has gone. */
        public boolean breaking() {
            return !removedFields.isEmpty() || !removedMeasures.isEmpty() || !removedHierarchies.isEmpty();
        }

        public List<String> removed() {
            List<String> all = new ArrayList<>(removedFields);
            all.addAll(removedMeasures);
            all.addAll(removedHierarchies);
            return all;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("breaking", breaking());
            m.put("removedFields", removedFields);
            m.put("removedMeasures", removedMeasures);
            m.put("removedHierarchies", removedHierarchies);
            m.put("addedFields", addedFields);
            m.put("addedMeasures", addedMeasures);
            return m;
        }
    }

    /** One place a report names a dataset field or measure. */
    public record Use(String name, String where) {}

    /** Every name a report uses, with where. */
    public record Refs(List<Use> fields, List<Use> measures, List<Use> hierarchies) {}

    private Surface() {}

    public static Change change(Offer before, Offer after) {
        return new Change(minus(before.fields(), after.fields()), minus(before.measures(), after.measures()), minus(before.hierarchies(), after.hierarchies()),
                minus(after.fields(), before.fields()), minus(after.measures(), before.measures()));
    }

    private static List<String> minus(Set<String> a, Set<String> b) {
        return a.stream().filter(x -> !b.contains(x)).sorted().toList();
    }

    /**
     * The names the report uses. {@code tables} says which first segments are tables of the dataset (a name {@code table.column[.part]} is a field,
     * anything else in a well or filter is a measure).
     */
    public static Refs refs(Report r, Set<String> tables) {
        return refs(r, tables, ref -> true);
    }

    /**
     * As {@link #refs(Report, Set)} for a report that reads several datasets: only what is read from the dataset {@code mine} accepts (a visual's own dataset, else
     * the report's; the report's and the pages' filters and the bookmarks belong to the report's dataset).
     */
    public static Refs refs(Report r, Set<String> tables, java.util.function.Predicate<String> mine) {
        List<Use> fields = new ArrayList<>();
        List<Use> measures = new ArrayList<>();
        List<Use> hierarchies = new ArrayList<>();
        Collector c = new Collector(tables, fields, measures, hierarchies);
        boolean own = mine.test(r.dataset());
        if (own) {
            c.filters(r.filters(), "report filter");
        }
        for (Report.Page p : r.pages()) {
            if (own) {
                c.filters(p.filters(), "page " + p.id());
                if (p.drillThrough() != null) {
                    p.drillThrough().fields().forEach(f -> c.name(f, "page " + p.id() + " drill-through"));
                }
            }
            for (Report.Visual v : p.visuals()) {
                if (!mine.test(r.datasetOf(v))) {
                    continue;
                }
                String at = "page " + p.id() + ", visual " + v.id();
                v.wells().values().forEach(l -> l.forEach(n -> c.name(n, at)));
                c.filters(v.filters(), at);
                if (v.sort() != null) {
                    c.name(v.sort().by(), at + " sort");
                }
                if (v.hierarchy() != null) {
                    hierarchies.add(new Use(v.hierarchy(), at));
                }
                if (v.drillThrough() != null) {
                    c.name(v.drillThrough().field(), at + " drill-through");
                }
            }
        }
        if (own) {
            r.bookmarks().forEach(b -> c.filters(b.filters(), "bookmark " + b.name()));
        }
        return new Refs(fields, measures, hierarchies);
    }

    private record Collector(Set<String> tables, List<Use> fields, List<Use> measures, List<Use> hierarchies) {

        void filters(List<Report.Filter> list, String at) {
            for (Report.Filter f : list) {
                name(f.field(), at + " filter");
                if (f.by() != null) {
                    name(f.by(), at + " filter");
                }
            }
        }

        void name(String n, String at) {
            if (n == null || n.isBlank()) {
                return;
            }
            String[] parts = n.split("\\.");
            if (parts.length >= 2 && tables.contains(parts[0])) {
                fields.add(new Use(parts[0] + "." + parts[1], at));
            } else {
                measures.add(new Use(n, at));
            }
        }
    }

    /**
     * What a composed or derived dataset takes from the dataset {@code mine} accepts: the columns it groups by or reads (as {@code table.column}, a date part
     * dropped), the measures it asks for, and the fields its filters name.
     */
    public static Refs refs(Dataset d, java.util.function.Predicate<String> mine, Set<String> tables) {
        List<Use> fields = new ArrayList<>();
        List<Use> measures = new ArrayList<>();
        Collector c = new Collector(tables, fields, measures, new ArrayList<>());
        for (Dataset.Input in : Composition.sources(d)) {
            if (!mine.test(in.dataset())) {
                continue;
            }
            String at = "source '" + in.alias() + "' of " + d.name();
            in.by().forEach(n -> c.name(n.source(), at));
            in.fields().forEach(n -> c.name(n.source(), at));
            in.measures().forEach(n -> measures.add(new Use(n.source(), at)));
            in.filters().forEach(f -> c.name(f.field(), at + " filter"));
        }
        return new Refs(fields, measures, List.of());
    }

    /** The uses of removed names: a report that names none of them is not in the answer. */
    public static List<String> broken(Refs refs, Change change) {
        Set<String> out = new LinkedHashSet<>();
        for (Use u : refs.fields()) {
            if (change.removedFields().contains(u.name())) {
                out.add(u.name() + " (" + u.where() + ")");
            }
        }
        for (Use u : refs.measures()) {
            if (change.removedMeasures().contains(u.name())) {
                out.add("measure " + u.name() + " (" + u.where() + ")");
            }
        }
        for (Use u : refs.hierarchies()) {
            if (change.removedHierarchies().contains(u.name())) {
                out.add("hierarchy " + u.name() + " (" + u.where() + ")");
            }
        }
        return new ArrayList<>(out);
    }

    /** The distinct names a report uses of its dataset, for lineage. */
    public static Map<String, List<String>> used(Refs refs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("fields", refs.fields().stream().map(Use::name).distinct().sorted().toList());
        m.put("measures", refs.measures().stream().map(Use::name).distinct().sorted().toList());
        m.put("hierarchies", refs.hierarchies().stream().map(Use::name).distinct().sorted().toList());
        return m;
    }
}

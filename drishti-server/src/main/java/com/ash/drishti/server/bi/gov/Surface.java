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

import com.ash.drishti.server.bi.dataset.LegacySources;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.report.Report;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a dataset offers to reports (its fields {@code table.column}, measures and hierarchies) and what a report uses of it, so a change to the
 * dataset can be set against the reports that read it (docs/guides/BI_GOVERNANCE.md, "Impact"). The comparison is by name: a renamed field is a
 * removed field and an added one, because a report names fields, not meanings.
 */
public final class Surface {

    /**
     * What a dataset offers.
     *
     * @param fieldMeaning for a field read through a {@code path} or computed by an {@code expr}: what it is read from, so a renamed field is recognised
     * @param fieldType the declared type of a field, so a changed type is seen
     * @param measureMeaning the expression of a measure, so a renamed or redefined measure is seen
     */
    public record Offer(Set<String> fields, Set<String> measures, Set<String> hierarchies, Set<String> tables, Map<String, String> fieldMeaning, Map<String, String> fieldType,
            Map<String, String> measureMeaning) {

        public Offer(Set<String> fields, Set<String> measures, Set<String> hierarchies, Set<String> tables) {
            this(fields, measures, hierarchies, tables, Map.of(), Map.of(), Map.of());
        }

        public static Offer of(Dataset d) {
            Set<String> fields = new TreeSet<>();
            Map<String, String> meaning = new LinkedHashMap<>();
            Map<String, String> type = new LinkedHashMap<>();
            for (Dataset.Table t : d.tables().values()) {
                fields.add(t.name() + "." + Dataset.ID);
                fields.add(t.name() + "." + Dataset.DATE);
                if (t.each() != null) {
                    fields.add(t.name() + "." + Dataset.INDEX);
                }
                for (Dataset.Column c : t.columns().values()) {
                    String name = t.name() + "." + c.name();
                    fields.add(name);
                    if (c.expr() != null && !c.expr().isBlank()) {
                        meaning.put(name, "expr " + c.expr().strip().replaceAll("\\s+", " "));
                    } else if (c.path() != null && !c.path().isBlank() && !c.path().equals(c.name())) {
                        meaning.put(name, "path " + c.path().strip());
                    }
                    if (c.type() != null && !c.type().isBlank()) {
                        type.put(name, c.type());
                    }
                }
            }
            Map<String, String> measureMeaning = new LinkedHashMap<>();
            d.measures().values().forEach(m -> measureMeaning.put(m.name(), m.expr() != null ? "expr " + m.expr().strip().replaceAll("\\s+", " ") : "python " + m.python()));
            return new Offer(fields, new TreeSet<>(d.measures().keySet()), new TreeSet<>(d.hierarchies().keySet()), new TreeSet<>(d.tables().keySet()), meaning, type, measureMeaning);
        }

        public static Offer empty() {
            return new Offer(Set.of(), Set.of(), Set.of(), Set.of());
        }
    }

    /** A name that went and another that took its place with the same meaning (the same path or expression): offered as a rename, not a removal and an addition. */
    public record Rename(String kind, String from, String to) {}

    /** A name that stayed but now means something else: another declared type, or another path or expression. */
    public record Alter(String kind, String name, String what, String from, String to) {}

    /**
     * What changed between two offers: the names that were there and are not, and the names that are new. {@code renames} are removed names whose
     * meaning reappears under an added name (they are also in the removed list: a report using the old name still breaks until it is updated);
     * {@code alters} are names that stayed with a changed type or meaning.
     */
    public record Change(List<String> removedFields, List<String> removedMeasures, List<String> removedHierarchies, List<String> addedFields, List<String> addedMeasures,
            List<Rename> renames, List<Alter> alters) {

        public Change(List<String> removedFields, List<String> removedMeasures, List<String> removedHierarchies, List<String> addedFields, List<String> addedMeasures) {
            this(removedFields, removedMeasures, removedHierarchies, addedFields, addedMeasures, List.of(), List.of());
        }

        /** True when something a report could name has gone. */
        public boolean breaking() {
            return !removedFields.isEmpty() || !removedMeasures.isEmpty() || !removedHierarchies.isEmpty();
        }

        /** True when a name a report could use stayed but changed type or meaning. */
        public boolean altering() {
            return !alters.isEmpty();
        }

        public List<String> removed() {
            List<String> all = new ArrayList<>(removedFields);
            all.addAll(removedMeasures);
            all.addAll(removedHierarchies);
            return all;
        }

        /** The new name for a removed one, or null. */
        public String renamedTo(String from) {
            return renames.stream().filter(r -> r.from().equals(from)).map(Rename::to).findFirst().orElse(null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("breaking", breaking());
            m.put("removedFields", removedFields);
            m.put("removedMeasures", removedMeasures);
            m.put("removedHierarchies", removedHierarchies);
            m.put("addedFields", addedFields);
            m.put("addedMeasures", addedMeasures);
            m.put("renames", renames.stream().map(r -> Map.of("kind", r.kind(), "from", r.from(), "to", r.to())).toList());
            m.put("alters", alters.stream().map(a -> Map.of("kind", a.kind(), "name", a.name(), "what", a.what(), "from", a.from(), "to", a.to())).toList());
            return m;
        }
    }

    /** One place a report names a dataset field or measure. */
    public record Use(String name, String where) {}

    /** Every name a report uses, with where. */
    public record Refs(List<Use> fields, List<Use> measures, List<Use> hierarchies) {}

    private Surface() {}

    public static Change change(Offer before, Offer after) {
        List<String> removedFields = minus(before.fields(), after.fields());
        List<String> removedMeasures = minus(before.measures(), after.measures());
        List<String> addedFields = minus(after.fields(), before.fields());
        List<String> addedMeasures = minus(after.measures(), before.measures());
        List<Rename> renames = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        for (String r : removedFields) {
            String was = before.fieldMeaning().get(r);
            if (was == null) {
                continue;
            }
            for (String a : addedFields) {
                if (!taken.contains(a) && tableOf(a).equals(tableOf(r)) && was.equals(after.fieldMeaning().get(a)) && Objects.equals(before.fieldType().get(r), after.fieldType().get(a))) {
                    renames.add(new Rename("field", r, a));
                    taken.add(a);
                    break;
                }
            }
        }
        for (String r : removedMeasures) {
            String was = before.measureMeaning().get(r);
            if (was == null) {
                continue;
            }
            for (String a : addedMeasures) {
                if (!taken.contains(a) && was.equals(after.measureMeaning().get(a))) {
                    renames.add(new Rename("measure", r, a));
                    taken.add(a);
                    break;
                }
            }
        }
        List<Alter> alters = new ArrayList<>();
        for (String f : before.fields()) {
            if (!after.fields().contains(f)) {
                continue;
            }
            String t0 = before.fieldType().get(f);
            String t1 = after.fieldType().get(f);
            if (t0 != null && t1 != null && !t0.equals(t1)) {
                alters.add(new Alter("field", f, "type", t0, t1));
            }
            String m0 = before.fieldMeaning().get(f);
            String m1 = after.fieldMeaning().get(f);
            if (!Objects.equals(m0, m1) && (m0 != null || m1 != null)) {
                alters.add(new Alter("field", f, "meaning", m0 == null ? "the column itself" : m0, m1 == null ? "the column itself" : m1));
            }
        }
        for (String m : before.measures()) {
            if (!after.measures().contains(m)) {
                continue;
            }
            String m0 = before.measureMeaning().get(m);
            String m1 = after.measureMeaning().get(m);
            if (m0 != null && m1 != null && !m0.equals(m1)) {
                alters.add(new Alter("measure", m, "meaning", m0, m1));
            }
        }
        return new Change(removedFields, removedMeasures, minus(before.hierarchies(), after.hierarchies()), addedFields, addedMeasures, renames, alters);
    }

    private static String tableOf(String field) {
        int dot = field.indexOf('.');
        return dot < 0 ? field : field.substring(0, dot);
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
        for (Dataset.Input in : LegacySources.sources(d)) {
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

    /** The uses of removed names: a report that names none of them is not in the answer. A name that was renamed says to what. */
    public static List<String> broken(Refs refs, Change change) {
        Set<String> out = new LinkedHashSet<>();
        for (Use u : refs.fields()) {
            if (change.removedFields().contains(u.name())) {
                out.add(u.name() + renamed(change, u.name()) + " (" + u.where() + ")");
            }
        }
        for (Use u : refs.measures()) {
            if (change.removedMeasures().contains(u.name())) {
                out.add("measure " + u.name() + renamed(change, u.name()) + " (" + u.where() + ")");
            }
        }
        for (Use u : refs.hierarchies()) {
            if (change.removedHierarchies().contains(u.name())) {
                out.add("hierarchy " + u.name() + " (" + u.where() + ")");
            }
        }
        return new ArrayList<>(out);
    }

    private static String renamed(Change change, String name) {
        String to = change.renamedTo(name);
        return to == null ? "" : " [renamed to " + to + "]";
    }

    /** The uses of names that stayed but changed type or meaning: the report still draws, but means something else (or fails at run time). */
    public static List<String> altered(Refs refs, Change change) {
        Set<String> out = new LinkedHashSet<>();
        for (Alter a : change.alters()) {
            List<Use> uses = "measure".equals(a.kind()) ? refs.measures() : refs.fields();
            for (Use u : uses) {
                if (u.name().equals(a.name())) {
                    out.add(("measure".equals(a.kind()) ? "measure " : "") + a.name() + ": " + a.what() + " " + a.from() + " -> " + a.to() + " (" + u.where() + ")");
                }
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

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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Resolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The datasets a report reads (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.10): the report's own and any a visual names with {@code dataset:}, each as the catalogue item and the parsed
 * model, by the text the report wrote. Also the report's {@code links}: a choice made on a field of one dataset (a slicer, a click on a slice) reaches a visual of
 * another dataset only through a link that names the field and one that the other dataset has.
 */
public final class DatasetSet {

    /** One dataset of the report. */
    public record Entry(BiStore.Item item, Dataset dataset) {

        public String id() {
            return item.id();
        }
    }

    private final Map<String, Entry> byRef = new LinkedHashMap<>();

    /** A report that reads one dataset. */
    public static DatasetSet single(String ref, BiStore.Item item, Dataset dataset) {
        DatasetSet s = new DatasetSet();
        s.put(ref, new Entry(item, dataset));
        return s;
    }

    public void put(String ref, Entry e) {
        byRef.put(ref, e);
    }

    /** The dataset a ref names, or null when it was not found. */
    public Entry of(String ref) {
        return byRef.get(ref);
    }

    public Map<String, Entry> all() {
        return byRef;
    }

    /** True when both refs name the same dataset (a catalogue id, a file name and the dataset's name can all name one). */
    public boolean same(String a, String b) {
        Entry x = byRef.get(a);
        Entry y = byRef.get(b);
        return a.equals(b) || x != null && y != null && x.id().equals(y.id());
    }

    private static boolean resolves(Dataset ds, String field) {
        try {
            return new Resolver(ds).resolve(List.of(field.split("\\.")), null).column() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The field a choice made on {@code field} (of the dataset {@code from}) is on in the dataset {@code to}: the field itself when both are one dataset, else the
     * first member of a link that names {@code field} and that {@code to} has; null when there is none (the choice does not reach that visual).
     */
    public String translate(Report report, String field, String from, String to) {
        if (same(from, to)) {
            return field;
        }
        Entry target = byRef.get(to);
        if (target == null) {
            return null;
        }
        for (Report.Link link : report.links()) {
            if (link.fields().contains(field)) {
                for (String candidate : link.fields()) {
                    if (resolves(target.dataset(), candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }
}

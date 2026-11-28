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
package com.ash.drishti.server.enginehost;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.VectorSink;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.enginehost.KindData;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The engines' read API over the server's router (EN-40): a kind or lake table read the normal way (its connectors, the read order, the
 * lake connectors over Drishti Lake's {@code TableSnapshot.columns} and {@code vectors}), so an engine never sees a connector, a credential
 * or a path. Row rules and masks are the host's ({@code LakeInputs}), applied to what this returns.
 */
final class RouterKindData implements KindData {

    private final SourceRouter router;
    private final Duration budget;

    RouterKindData(SourceRouter router, Duration budget) {
        this.router = router;
        this.budget = budget;
    }

    private static AsOf asOf(LocalDate date) {
        return date == null ? AsOf.LATEST : AsOf.of(date);
    }

    @Override
    public boolean serves(String kind) {
        return !router.readers(kind).isEmpty();
    }

    @Override
    public Optional<java.util.NavigableSet<LocalDate>> dates(String kind) {
        return router.heldDates(kind);
    }

    @Override
    public Optional<LocalDate> latest(String kind) {
        return router.latestDate(kind);
    }

    @Override
    public Optional<String> stamp(String kind, LocalDate date) {
        return router.dataVersion(kind, asOf(date));
    }

    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, LocalDate date) {
        Optional<ColumnSet> c = router.columns(kind, paths, asOf(date), budget);
        if (c.isPresent()) {
            return c;
        }
        Optional<ColumnSet> d = fromDocuments(kind, paths, date);
        if (d.isPresent()) {
            return d;
        }
        // a large kind with some asked fields not kept as columns: the kept ones (the others read as absent; the engine names them)
        java.util.Set<String> held = router.columnar(kind);
        java.util.List<String> kept = paths.stream().filter(held::contains).toList();
        return kept.isEmpty() || kept.size() == paths.size() ? Optional.empty() : router.columns(kind, kept, asOf(date), budget);
    }

    /** Most documents a kind without columns is read by (a small reference kind: scenario sets, limits); more answers absent. */
    static final int DOCUMENT_ROWS = 200_000;

    /**
     * A kind whose source keeps no columns for these paths (a document kind: a field holding a list, such as a scenario set's dates) read
     * as documents into columns: a number field as numbers, a list as its items joined by commas, anything else as text. Empty when the
     * kind has no documents on the date or more than {@link #DOCUMENT_ROWS}.
     */
    private Optional<ColumnSet> fromDocuments(String kind, Collection<String> paths, LocalDate date) {
        java.util.List<com.ash.drishti.api.EntityDocument> docs = new java.util.ArrayList<>();
        boolean[] over = {false};
        try {
            router.documents(kind, asOf(date), d -> {
                if (docs.size() >= DOCUMENT_ROWS) {
                    over[0] = true;
                } else if (!d.deleted()) {
                    docs.add(d);
                }
            });
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (docs.isEmpty() || over[0]) {
            return Optional.empty();
        }
        return toColumns(rows(docs, null), paths, date);
    }

    /**
     * The engine file's read of a reference kind (ENGINE SPEC {@code inputs.<name>.as-of}, {@code .field}): with {@code onOrBefore} each
     * record's latest version on or before the date, as the in-tree loaders read documents as of a date (the kind's own documents of the
     * date, else every record listed across dates and fetched as of it); with {@code field} the items of each record's list-of-records
     * field are the rows, each with its record's fields beside its own (a credit limit's {@code BUCKETS} under its {@code LIMIT_ID}).
     */
    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, LocalDate date, boolean onOrBefore, String field) {
        if (!onOrBefore && field == null) {
            return columns(kind, paths, date);
        }
        java.util.Map<String, com.ash.drishti.api.EntityDocument> docs = new java.util.LinkedHashMap<>();
        try {
            if (onOrBefore) {
                boolean whole = router.documents(kind, asOf(date), d -> docs.put(d.ref().id(), d));
                if (!whole) {                                       // as the in-tree loaders: the listing only when the kind cannot be read whole
                    docs.clear();
                    var listing = router.listAcross(kind, DOCUMENT_ROWS, budget, asOf(date), new com.ash.drishti.engine.source.SourceFailures());
                    var refs = listing.hits().stream().map(h -> h.ref()).toList();
                    router.fetchAll(refs, budget, asOf(date)).forEach((ref, d) -> docs.put(ref.id(), d));
                }
            } else {
                router.documents(kind, asOf(date), d -> docs.put(d.ref().id(), d));
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        java.util.List<com.ash.drishti.api.EntityDocument> live = docs.values().stream().filter(d -> !d.deleted()).toList();
        if (live.isEmpty() || live.size() > DOCUMENT_ROWS) {
            return Optional.empty();
        }
        return toColumns(rows(live, field), paths, date);
    }

    /** A row read from documents: its id and its fields (an item's own, then its record's). */
    private record DocRow(String id, java.util.function.Function<String, com.ash.drishti.api.DataNode> get) {}

    private static java.util.List<DocRow> rows(java.util.List<com.ash.drishti.api.EntityDocument> docs, String field) {
        java.util.List<com.ash.drishti.api.EntityDocument> sorted = new java.util.ArrayList<>(docs);
        sorted.sort(java.util.Comparator.comparing(d -> d.ref().id()));
        java.util.List<DocRow> out = new java.util.ArrayList<>();
        for (com.ash.drishti.api.EntityDocument d : sorted) {
            com.ash.drishti.api.DataNode data = d.data();
            if (field == null) {
                out.add(new DocRow(d.ref().id(), data::get));
                continue;
            }
            com.ash.drishti.api.DataNode items = data.get(field);
            for (int j = 0; items instanceof com.ash.drishti.api.DataNode.Arr && j < items.size(); j++) {
                com.ash.drishti.api.DataNode item = items.get(j);
                out.add(new DocRow(d.ref().id() + "#" + j, p -> {
                    com.ash.drishti.api.DataNode v = item.get(p);
                    return v.isMissing() ? data.get(p) : v;
                }));
            }
        }
        return out;
    }

    /** Rows into columns: a number field as numbers, a list as its items joined by commas, anything else as text. */
    private static Optional<ColumnSet> toColumns(java.util.List<DocRow> docs, Collection<String> paths, LocalDate date) {
        int n = docs.size();
        if (n == 0) {
            return Optional.empty();
        }
        String[] ids = new String[n];
        java.util.Map<String, double[]> numbers = new java.util.LinkedHashMap<>();
        java.util.Map<String, String[]> texts = new java.util.LinkedHashMap<>();
        for (String p : paths) {
            boolean numeric = true;
            boolean any = false;
            for (DocRow d : docs) {
                com.ash.drishti.api.DataNode v = d.get().apply(p);
                if (!v.isMissing() && !v.isNull()) {
                    any = true;
                    numeric &= v.unwrap() instanceof Number;
                }
            }
            if (!any) {
                continue;                                              // the documents do not hold it: absent, the engine names it
            }
            double[] num = numeric ? new double[n] : null;
            String[] txt = numeric ? null : new String[n];
            for (int i = 0; i < n; i++) {
                com.ash.drishti.api.DataNode v = docs.get(i).get().apply(p);
                if (numeric) {
                    num[i] = v.isMissing() || v.isNull() ? Double.NaN : v.asDouble();
                } else if (v.isMissing() || v.isNull()) {
                    txt[i] = null;
                } else if (v.size() > 0 && !(v.unwrap() instanceof String)) {
                    java.util.List<String> items = new java.util.ArrayList<>();
                    for (int j = 0; j < v.size(); j++) {
                        items.add(v.get(j).asText());
                    }
                    txt[i] = String.join(",", items);
                } else {
                    txt[i] = v.asText();
                }
            }
            if (numeric) {
                numbers.put(p, num);
            } else {
                texts.put(p, txt);
            }
        }
        for (int i = 0; i < n; i++) {
            ids[i] = docs.get(i).id();
        }
        return Optional.of(new ColumnSet(ids, numbers, texts, date));
    }

    @Override
    public java.util.Set<String> columnar(String kind) {
        return router.columnar(kind);
    }

    @Override
    public boolean vectors(String kind, String column, List<String> texts, LocalDate date, VectorSink sink) throws Exception {
        if (router.vectors(kind, column, texts, asOf(date), sink)) {
            return true;
        }
        // a kind that keeps the vector as a list field of its documents (a small one: the FX scenarios of a date), in id order
        java.util.List<com.ash.drishti.api.EntityDocument> docs = new java.util.ArrayList<>();
        router.documents(kind, asOf(date), d -> {
            if (!d.deleted() && docs.size() <= DOCUMENT_ROWS) {
                docs.add(d);
            }
        });
        if (docs.isEmpty() || docs.size() > DOCUMENT_ROWS) {
            return false;
        }
        docs.sort(java.util.Comparator.comparing(d -> d.ref().id()));
        boolean any = false;
        for (com.ash.drishti.api.EntityDocument d : docs) {
            com.ash.drishti.api.DataNode v = d.data().get(column);
            if (!(v instanceof com.ash.drishti.api.DataNode.Arr) || v.size() == 0) {
                continue;                                              // only a list of numbers is a vector (a marker object is not)
            }
            double[] values = new double[v.size()];
            for (int j = 0; j < values.length; j++) {
                values[j] = v.get(j).asDouble();
            }
            String[] t = new String[texts.size()];
            for (int k = 0; k < t.length; k++) {
                com.ash.drishti.api.DataNode x = d.data().get(texts.get(k));
                t[k] = x.isMissing() || x.isNull() ? null : x.asText();
            }
            sink.row(d.ref().id(), values, values.length, t);
            any = true;
        }
        return any;
    }
}

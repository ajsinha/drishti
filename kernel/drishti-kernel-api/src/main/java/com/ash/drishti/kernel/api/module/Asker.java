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

package com.ash.drishti.kernel.api.module;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.LivePage;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.kernel.api.services.RawRecord;
import com.ash.drishti.kernel.api.services.SearchAnswer;
import com.ash.drishti.kernel.api.services.Series;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One person's typed calls of a landing: the kernel's typed services ({@link Kernel}) asked as one {@link Person}, answered as the
 * JSON-shaped maps {@link Answers} reads. A module implements the three accessors (a record of {@link #kernel} and
 * {@link #person}); every call is the typed call of the person, never a call to this server over HTTP.
 */
public interface Asker {

    /** The kernel's typed services. */
    Kernel kernel();

    /** The person asked as. */
    Person person();

    /** The same questions as another person (a person of {@link Person#onDate}). */
    Asker as(Person p);

    default String user() {
        return person().user();
    }

    default String display() {
        return person().display();
    }

    default boolean admin() {
        return person().admin();
    }

    default String lens() {
        return person().lens();
    }

    /** What tells one person's answers from another's ({@link Person#key()}). */
    default String key() {
        return person().key();
    }

    /** The same person with their date box on another business date. */
    default Asker onDate(LocalDate day) {
        return as(person().onDate(day));
    }

    /** A search: {@code kind, matched, scanned, partial, noData, rows: [{ref: {kind, id}, title, values}]} (values by {@code $.FIELD}). */
    default Map<String, Object> search(String query) {
        SearchAnswer a = kernel().records().search(person(), query);
        List<Object> rows = new ArrayList<>();
        for (SearchAnswer.Row r : a.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ref", ref(r.ref()));
            row.put("title", r.title());
            row.put("values", r.values());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", a.kind());
        out.put("matched", a.matched());
        out.put("scanned", a.scanned());
        out.put("partial", a.partial());
        out.put("noData", a.noData());
        out.put("rows", rows);
        return out;
    }

    /**
     * One record whole: {@code ref, provenance: {businessDate, source}, data}.
     *
     * @throws Kernel.Refused 404 when no source holds it
     */
    default Map<String, Object> raw(String kind, String id) {
        EntityRef ref = EntityRef.of(kind, id);
        RawRecord r = kernel().records().raw(person(), ref).orElseThrow(
                () -> new Kernel.Refused(ErrorCode.ENTITY_NOT_FOUND.httpStatus(), ErrorCode.ENTITY_NOT_FOUND.code(), "no source holds " + ref));
        Map<String, Object> prov = new LinkedHashMap<>();
        if (r.provenance() != null) {
            prov.put("source", r.provenance().source());
            prov.put("businessDate", r.provenance().businessDate() == null ? null : r.provenance().businessDate().toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref(r.ref()));
        out.put("provenance", prov);
        out.put("data", r.data().unwrap());
        return out;
    }

    /** One field over the last {@code days} business days: {@code path, label, dated, points: [{date, value, dataDate, source, generation}]}. */
    default Map<String, Object> series(String kind, String id, String path, int days) {
        Series s = kernel().history().series(person(), EntityRef.of(kind, id), path, days, null);
        List<Object> points = new ArrayList<>();
        for (Series.Point p : s.points()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", p.date() == null ? null : p.date().toString());
            m.put("value", p.value());
            m.put("dataDate", p.dataDate() == null ? null : p.dataDate().toString());
            m.put("source", p.source());
            m.put("generation", p.generation());
            points.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", s.path());
        out.put("label", s.label());
        out.put("dated", s.dated());
        out.put("points", points);
        return out;
    }

    /** The business dates: {@code current, selected, live, holidays}. */
    default Map<String, Object> dates() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", kernel().dates().current().toString());
        out.put("selected", kernel().dates().selected(person()).toString());
        out.put("live", kernel().dates().live(person()));
        out.put("holidays", kernel().dates().holidays().stream().map(LocalDate::toString).toList());
        return out;
    }

    /** The person's settings. */
    default Map<String, Object> settings() {
        return kernel().settings().of(person());
    }

    /** The first {@code limit} rows of a search's live set: {@code set, live, total, seq, offset, rows, search} (notices only when there are some). */
    default Map<String, Object> live(String query, int limit) {
        LivePage p = kernel().live().query(person(), query, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set", p.set());
        out.put("live", p.live());
        out.put("total", p.total());
        out.put("seq", p.seq());
        out.put("offset", p.offset());
        out.put("rows", p.rows());
        out.put("search", p.search());
        if (!p.asOfNotices().isEmpty()) {
            out.put("asOfNotices", p.asOfNotices());
        }
        return out;
    }

    /** The pivot engine's figures of rows the module holds ({@code Pivots.figures}). */
    default InlineFigures.Figures figures(List<InlineFigures.Field> fields, List<List<Object>> rows, List<Map<String, Object>> values, String by,
            String across, Map<String, Object> top) {
        return kernel().pivots().figures(person(), fields, rows, values, by, across, top);
    }

    private static Map<String, Object> ref(EntityRef r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", r.kind());
        m.put("id", r.id());
        return m;
    }
}

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
package com.ash.drishti.server.live;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Drishti record as a live table's row: its document's fields as stored, with the entity id under the key column when the document
 * does not hold it (a default declaration is keyed by {@code id}), and the business date it was read for when the document has none.
 */
public final class KindRows {

    /** The column naming the business date a record was read for. */
    public static final String BUSINESS_DATE = "business_date";

    private KindRows() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> row(EntityDocument d, List<String> key) {
        return row(d.ref().id(), d.data(), key, d.provenance() == null || d.provenance().businessDate() == null ? null
                : d.provenance().businessDate().toString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> row(String id, DataNode doc, List<String> key, String businessDate) {
        Object u = doc == null ? null : doc.unwrap();
        Map<String, Object> r = u instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        if (key.size() == 1 && r.get(key.get(0)) == null) {
            r.put(key.get(0), id);
        }
        if (businessDate != null && !r.containsKey(BUSINESS_DATE)) {
            r.put(BUSINESS_DATE, businessDate);
        }
        return r;
    }

    /** The key values of a record known only by its id (a deletion). */
    public static List<Object> key(String id) {
        return List.of(id);
    }

    /** Two row versions compared: numbers by value, anything else as text. */
    public static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}

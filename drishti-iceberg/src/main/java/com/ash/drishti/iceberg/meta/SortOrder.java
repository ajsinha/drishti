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
package com.ash.drishti.iceberg.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** A sort order: what a table's files are sorted by (Drishti's layout: {@code id} ascending). Order 0 is unsorted. */
public record SortOrder(int orderId, List<Field> fields) {

    /** One sort key: a transform of a column, its direction and where nulls go. */
    public record Field(int sourceId, Transform transform, boolean ascending, boolean nullsFirst) {}

    public SortOrder {
        fields = List.copyOf(fields);
    }

    public static final SortOrder UNSORTED = new SortOrder(0, List.of());

    public static SortOrder read(JsonNode n) {
        List<Field> fields = new ArrayList<>();
        for (JsonNode f : n.path("fields")) {
            int source = f.has("source-id") ? f.get("source-id").asInt() : f.path("source-ids").path(0).asInt();
            fields.add(new Field(source, Transform.parse(f.path("transform").asText("identity")), !"desc".equalsIgnoreCase(f.path("direction").asText()),
                    "nulls-first".equalsIgnoreCase(f.path("null-order").asText())));
        }
        return new SortOrder(n.path("order-id").asInt(0), fields);
    }

    public ObjectNode write() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("order-id", orderId);
        var arr = o.putArray("fields");
        for (Field f : fields) {
            arr.addObject().put("transform", f.transform().toString()).put("source-id", f.sourceId()).put("direction", f.ascending() ? "asc" : "desc")
                    .put("null-order", f.nullsFirst() ? "nulls-first" : "nulls-last");
        }
        return o;
    }

    /** Whether the files are sorted by this column alone, ascending (so per-file id bounds do not overlap). */
    public boolean sortedBy(int fieldId) {
        return !fields.isEmpty() && fields.get(0).sourceId() == fieldId && fields.get(0).ascending()
                && fields.get(0).transform() instanceof Transform.Identity;
    }
}

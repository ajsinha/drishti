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
package com.ash.drishti.rachana.ui;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The suppliers as the author sees them (BC-05, BC-08, BC-W5): each one's fields with their types, whether the author may
 * read it, and which fields are masked for the author. The server answers it from the packs and the BI models as the caller
 * (P3); the command line reads it from a file ({@code --suppliers}); without one the supplier rules are skipped.
 */
public interface SupplierCatalog {

    /** The supplier as the author sees it; empty when it does not exist or the author may not read it (BC-08). */
    Optional<Info> find(BoardDoc.Supply supply);

    /**
     * One supplier.
     *
     * @param fields its attributes and their types (a model: its columns and measures)
     * @param masked the attributes masked for the author
     */
    record Info(Map<String, TypeRef> fields, Set<String> masked) {

        public Info {
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            masked = Set.copyOf(masked);
        }
    }

    /**
     * Reads {@code suppliers: { "connector:trading/trade-search": { fields: { TRADE_ID: { type: id, of: trading/trade },
     * TRADE_DATE: date }, masked: [MTM_USD], readable: true } }}. A supplier with {@code readable: false} is not found.
     *
     * @throws UiDocumentException when a field type is unknown
     */
    static SupplierCatalog fromDocument(JsonNode root) {
        Map<String, Info> all = new LinkedHashMap<>();
        JsonNode s = root.path("suppliers");
        java.util.List<UiProblem> problems = new java.util.ArrayList<>();
        s.fields().forEachRemaining(e -> {
            if (!e.getValue().path("readable").asBoolean(true)) {
                return;
            }
            Map<String, TypeRef> fields = new LinkedHashMap<>();
            e.getValue().path("fields").fields().forEachRemaining(f -> {
                TypeRef t = UiDocuments.typeOf(f.getValue(), "suppliers." + e.getKey() + ".fields." + f.getKey(), problems);
                if (t != null) {
                    fields.put(f.getKey(), t);
                }
            });
            Set<String> masked = new LinkedHashSet<>();
            e.getValue().path("masked").forEach(m -> masked.add(m.asText()));
            all.put(e.getKey(), new Info(fields, masked));
        });
        if (!problems.isEmpty()) {
            throw new UiDocumentException("the supplier file is not valid", problems);
        }
        return supply -> Optional.ofNullable(all.get(supply.key()));
    }
}

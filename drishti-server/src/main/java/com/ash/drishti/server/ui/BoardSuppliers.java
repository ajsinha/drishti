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
package com.ash.drishti.server.ui;

import com.ash.drishti.rachana.ui.BoardDoc;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Reads a chip's supplier AS THE CALLER (docs/architecture/UI_BOARD.md section 7.2): connectors through the entity and search paths with the caller's
 * redactor and kinds, models through BI runs with the caller's roles, row rules and masks. Nothing is read as the system.
 */
public interface BoardSuppliers {

    /**
     * Reads one supply.
     *
     * @param who the caller
     * @param ask what to read
     * @return the rows as the caller may see them
     * @throws com.ash.drishti.common.DrishtiException with the supplier's own code (a kind the caller may not open: DRS-2074)
     */
    Rows read(Principal who, Ask ask);

    /**
     * Follows a live supply: {@code frame} receives each new version of the record, as the caller may see it. Null when the supply does not tick.
     */
    default AutoCloseable follow(Principal who, Ask ask, Consumer<Rows> frame) {
        return null;
    }

    /**
     * One read.
     *
     * @param supply  the instance's supply
     * @param query   its query, board parameters already substituted (may be null)
     * @param id      the record id a trace or control gave (an entity supply opened by id), else null
     * @param filters attribute to value, from {@code filter-on} traces
     * @param maxRows the most rows painted
     */
    record Ask(BoardDoc.Supply supply, JsonNode query, String id, Map<String, Object> filters, int maxRows) {

        public Ask {
            filters = filters == null ? Map.of() : Map.copyOf(filters);
        }
    }

    /**
     * Rows as the caller may see them.
     *
     * @param columns   the attribute names
     * @param rows      each row, attribute to value (a masked value is already {@code •••} or null)
     * @param refs      per row, the record's id as its entity reference names it (never masked by rule), or null
     * @param masked    attributes masked for the caller
     * @param total     rows before truncation (-1 unknown)
     * @param runId     the BI run that answered, else null
     * @param reused    true when the answer was shared from another viewer with the same access profile
     * @param pending   a BI run still working: the rows follow on completion; null when answered
     */
    record Rows(List<String> columns, List<Map<String, Object>> rows, List<String> refs, Set<String> masked, long total, String runId, boolean reused,
            CompletableFuture<Rows> pending) {

        public Rows {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : rows;
            masked = masked == null ? Set.of() : Set.copyOf(masked);
        }

        public static Rows of(List<String> columns, List<Map<String, Object>> rows, Set<String> masked) {
            return new Rows(columns, rows, null, masked, rows.size(), null, false, null);
        }

        public Rows asReused() {
            return new Rows(columns, rows, refs, masked, total, runId, true, pending);
        }

        public Rows withRun(String id) {
            return new Rows(columns, rows, refs, masked, total, id, reused, pending);
        }
    }
}

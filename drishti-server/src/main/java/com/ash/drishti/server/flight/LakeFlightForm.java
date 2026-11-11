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
package com.ash.drishti.server.flight;

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.arrow.ArrowIpc;
import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.flight.FlightRefusal;
import com.ash.drishti.flight.FlightRequest;
import com.ash.drishti.lakeconfig.Lake;
import com.ash.drishti.lakeconfig.LakeTable;
import com.ash.drishti.lakeconfig.Lakes;
import com.ash.drishti.memlake.MemoryLake;
import com.ash.drishti.server.data.DataViewController;
import com.ash.drishti.kernel.api.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * {@code form: lake}: a table of a lake named by the lake configuration (the lake files, {@link Lakes}; HOW_IT_FITS.md, never a connector's settings), read as its kind with every
 * check of {@code form: kind}. Request: {@code {"lake": "feeds", "kind": "news.headlines", "paths": [...], "where": [...], "cursor":
 * "SEQ"}}. A table of the in-memory lake ({@code mem://<store>}) is answered by the node that owns its store (the endpoint names it). With
 * {@code stream: true} and a {@code cursor} (a whole-number column that grows with each append: a sequence, an epoch time), new rows are
 * sent as they are appended; the sequence of a batch is its largest cursor value, and {@code resume} sends the rows after it, so a client
 * that reconnects, to this node or another holding the table, misses nothing and gets nothing twice.
 */
final class LakeFlightForm implements FlightForm {

    private final java.util.function.Supplier<Lakes> lakes;

    /** A table and the lake it is in. */
    record Found(Lake lake, LakeTable table) {}
    private final KindFlightForm kinds;
    private final DataViewController data;
    private final MemoryLake memory;

    LakeFlightForm(java.util.function.Supplier<Lakes> lakes, KindFlightForm kinds, DataViewController data, MemoryLake memory) {
        this.lakes = lakes;
        this.kinds = kinds;
        this.data = data;
        this.memory = memory;
    }

    @Override
    public FlightRequest.Form form() {
        return FlightRequest.Form.LAKE;
    }

    @Override
    public String domain(Principal p, FlightRequest r) {
        return table(r).lake().name();
    }

    Found table(FlightRequest r) {
        JsonNode q = r.request();
        if (!q.isObject() || !q.path("lake").isTextual() || !q.path("kind").isTextual()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "form lake: request is {\"lake\": ..., \"kind\": ..., \"paths\", \"where\", \"cursor\"}");
        }
        String lake = q.get("lake").asText();
        String kind = q.get("kind").asText();
        Lakes all = lakes.get();
        if (all.all().isEmpty()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "this server has no lake files (config/lakes/, DATA_LAKE_GUIDE.md): read the kind "
                    + "with form kind");
        }
        Lake l = all.find(lake).orElseThrow(() -> new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "no lake '" + lake + "': the lakes are "
                + String.join(", ", all.all().keySet())));
        int dot = kind.indexOf('.');
        LakeTable t;
        try {
            t = (dot > 0 ? l.table(kind.substring(0, dot), kind.substring(dot + 1)) : l.table(kind)).orElseThrow(() -> new FlightRefusal(
                    ErrorCode.FLIGHT_BAD_TICKET, "the lake '" + lake + "' has no table for " + kind + " (its tables: " + l.tables().keySet() + ")"));
        } catch (IllegalArgumentException e) {                       // several tables of one kind: the lake says which to name
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, e.getMessage());
        }
        return new Found(l, t);
    }

    /** The same request as {@code form: kind} (the kind's part after its domain, as the Data view names it). */
    private FlightRequest asKind(FlightRequest r, Found f, List<String> extraWhere) {
        LakeTable t = f.table();
        ObjectNode o = r.request().deepCopy();
        String kind = t.kind();
        o.put("kind", kind);
        o.remove("lake");
        o.remove("cursor");
        if (!extraWhere.isEmpty()) {
            com.fasterxml.jackson.databind.node.ArrayNode w = JsonNodeFactory.instance.arrayNode();
            JsonNode had = o.get("where");
            if (had != null && had.isTextual()) {
                w.add(had.asText());
            } else if (had != null && had.isArray()) {
                had.forEach(x -> w.add(x.asText()));
            }
            extraWhere.forEach(w::add);
            o.set("where", w);
        }
        return new FlightRequest(FlightRequest.Form.KIND, o, r.part(), r.stream(), r.resume());
    }

    @Override
    public FlightBackend.Plan plan(Principal p, FlightRequest r, FlightNodes nodes) {
        Found t = table(r);
        FlightBackend.Plan k = kinds.plan(p, asKind(r, t, List.of()), nodes);
        List<FlightBackend.Node> at = new ArrayList<>();
        if (t.lake().inMemory()) {
            if (memory != null && memory.owns(t.lake().store())) {
                at.add(nodes.self());
            }
            at.addAll(nodes.peers());                                    // the owner when it is not this node; a peer holding a copy
        } else {
            at.addAll(nodes.anyMember());
        }
        return new FlightBackend.Plan(k.schema(), List.of(new FlightBackend.Part(t.lake().name() + "/" + t.table().name(), at, -1)), -1, -1);
    }

    @Override
    public FlightBackend.BatchSource open(Principal p, FlightRequest r, int part, int batchRows) {
        Found t = table(r);
        String cursor = r.request().path("cursor").asText("");
        return kinds.open(p, asKind(r, t, r.resume() >= 0 && !cursor.isEmpty() ? List.of(cursor + " > " + r.resume()) : List.of()), part, batchRows);
    }

    @Override
    public FlightBackend.Subscription subscribe(Principal p, FlightRequest r, int part, int batchRows) {
        Found t = table(r);
        String cursor = r.request().path("cursor").asText("");
        if (cursor.isEmpty()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "a live lake stream needs cursor: a whole-number column that grows with each append");
        }
        FlightRequest base = asKind(r, t, List.of());
        DataViewController.FlightSelection sel = kinds.select(p, base);
        if (!sel.columns().contains(cursor)) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "the cursor " + cursor + " must be one of the paths read");
        }
        Schema schema = kinds.batches(p, sel, 1, true).schema();
        long[] held = {r.resume()};
        return new FlightBackend.Subscription() {
            @Override
            public Schema schema() {
                return schema;
            }

            @Override
            public long next(VectorSchemaRoot root, Duration wait) {
                long until = System.nanoTime() + wait.toNanos();
                do {
                    List<String> after = held[0] < 0 ? List.of() : List.of(cursor + " > " + held[0]);
                    DataViewController.FlightSelection s = kinds.select(p, asKind(r, t, after));
                    List<Map<String, Object>> rows = new ArrayList<>();
                    try {
                        data.flightRead(p, s, rows::add);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                    if (!rows.isEmpty()) {
                        long max = held[0];
                        for (Map<String, Object> row : rows) {
                            if (row.get(cursor) instanceof Number n) {
                                max = Math.max(max, n.longValue());
                            }
                        }
                        List<ArrowTable.Type> types = new ArrayList<>();
                        s.columns().forEach(c -> types.add(KindFlightForm.type(s.dataset(), c)));
                        ArrowTable tbl = PushedBatches.table(s.columns(), types, rows);
                        ArrowIpc.fill(root, tbl, 0, tbl.rows());
                        held[0] = max;
                        return max;
                    }
                    try {
                        Thread.sleep(Math.min(250, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return END;
                    }
                } while (System.nanoTime() < until);
                return NONE;
            }
        };
    }

    @Override
    public List<FlightRequest> catalogue(Principal p) {
        List<FlightRequest> out = new ArrayList<>();
        for (Lake l : lakes.get().all().values()) {
            String lake = l.name();
            for (LakeTable t : l.tables().values()) {
                out.add(new FlightRequest(FlightRequest.Form.LAKE,
                        JsonNodeFactory.instance.objectNode().put("lake", lake).put("kind", t.domain() + "." + t.kind()), -1, false, -1));
            }
        }
        return out;
    }
}

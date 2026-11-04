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
import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.flight.FlightRefusal;
import com.ash.drishti.flight.FlightRequest;
import com.ash.drishti.server.data.DataViewController;
import com.ash.drishti.server.data.DataViewService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code form: kind}: a kind's rows on a day, its paths (columns) and filter lines, read by the Data view's reader with every check of a
 * download (the kind open to the person, internal only, masked columns left out, masks applied, busy protection, memory-only retention,
 * the audit line {@code data.flight}). Request: {@code {"kind": "trade", "date": "2026-10-30", "paths": ["TRADE_ID", "DESK"], "where":
 * ["DESK = RATES"]}} ({@code columns} is a synonym of {@code paths}; no date: the latest). One part, answered by any member (each reads
 * through the cluster). The schema follows the kind's data contract: integer as int64, amounts, rates and numbers as float64, boolean as
 * bool, the rest as text.
 */
final class KindFlightForm implements FlightForm {

    private static final Set<String> FLOATS = Set.of("amount", "rate", "number", "decimal");

    private final DataViewController data;

    KindFlightForm(DataViewController data) {
        this.data = data;
    }

    @Override
    public FlightRequest.Form form() {
        return FlightRequest.Form.KIND;
    }

    @Override
    public String domain(Principal p, FlightRequest r) {
        return select(p, r).dataset().pack();
    }

    @Override
    public FlightBackend.Plan plan(Principal p, FlightRequest r, FlightNodes nodes) {
        DataViewController.FlightSelection s = select(p, r);
        PushedBatches empty = batches(p, s, 1, true);
        try {
            return new FlightBackend.Plan(empty.schema(), List.of(new FlightBackend.Part(s.dataset().kind(), nodes.anyMember(), -1)), -1, -1);
        } finally {
            empty.close();
        }
    }

    @Override
    public FlightBackend.BatchSource open(Principal p, FlightRequest r, int part, int batchRows) {
        return batches(p, select(p, r), batchRows, false);
    }

    @Override
    public FlightBackend.Subscription subscribe(Principal p, FlightRequest r, int part, int batchRows) {
        throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "a kind is read as a snapshot; for live rows read a kind held in the in-memory lake "
                + "with form lake and stream: true");
    }

    @Override
    public List<FlightRequest> catalogue(Principal p) {
        List<FlightRequest> out = new ArrayList<>();
        for (DataViewService.Dataset d : data.flightKinds(p)) {
            ObjectNode o = JsonNodeFactory.instance.objectNode().put("kind", d.kind());
            out.add(new FlightRequest(FlightRequest.Form.KIND, o, -1, false, -1));
        }
        return out;
    }

    DataViewController.FlightSelection select(Principal p, FlightRequest r) {
        JsonNode q = r.request();
        if (!q.isObject() || !q.path("kind").isTextual()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "form kind: request is {\"kind\": ..., \"date\", \"paths\", \"where\"}");
        }
        JsonNode paths = q.has("paths") ? q.get("paths") : q.get("columns");
        try {
            return data.flightSelection(p, q.get("kind").asText(), q.path("date").isTextual() ? q.get("date").asText() : null, texts(paths),
                    texts(q.get("where")));
        } catch (com.ash.drishti.common.DrishtiException e) {
            throw FlightBackends.refusal(e);
        }
    }

    PushedBatches batches(Principal p, DataViewController.FlightSelection s, int batchRows, boolean planOnly) {
        List<ArrowTable.Type> types = new ArrayList<>();
        for (String c : s.columns()) {
            types.add(type(s.dataset(), c));
        }
        if (planOnly) {
            return new PushedBatches(s.columns(), types, batchRows, sink -> { });
        }
        return new PushedBatches(s.columns(), types, batchRows, sink -> data.flightRead(p, s, row -> sink.row(row)));
    }

    static ArrowTable.Type type(DataViewService.Dataset d, String column) {
        for (DataViewService.Column c : d.columns()) {
            if (c.name().equals(column)) {
                String t = c.type() == null ? "" : c.type();
                if ("integer".equals(t)) {
                    return ArrowTable.Type.INT64;
                }
                if (FLOATS.contains(t)) {
                    return ArrowTable.Type.FLOAT64;
                }
                if ("boolean".equals(t)) {
                    return ArrowTable.Type.BOOL;
                }
                return ArrowTable.Type.UTF8;
            }
        }
        return ArrowTable.Type.UTF8;
    }

    static List<String> texts(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (n.isTextual()) {
            out.add(n.asText());
        } else if (n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        }
        return out;
    }
}

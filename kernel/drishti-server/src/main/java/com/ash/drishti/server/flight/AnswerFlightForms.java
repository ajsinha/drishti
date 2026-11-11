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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.arrow.ArrowIpc;
import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.flight.FlightRefusal;
import com.ash.drishti.flight.FlightRequest;
import com.ash.drishti.flight.TableBatches;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.mq.ModelCatalog;
import com.ash.drishti.server.bi.mq.ModelQuery;
import com.ash.drishti.server.bi.mq.MqParser;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.pivot.PivotController;
import com.ash.drishti.kernel.api.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The forms whose answer the query layer computes whole (a model query's result, a pivot view's cells): computed once per read, sent in
 * batches. The plan computes it too (its schema depends on the answer), so a client that calls GetFlightInfo then DoGet reads it twice:
 * the second read is served from the layer's own result cache (MQ's, the pivot engine's partial states).
 */
final class AnswerFlightForms {

    private AnswerFlightForms() {}

    /** A computed answer: the table and the schema's metadata. */
    record Answer(ArrowTable table, Map<String, String> meta, String domain) {}

    abstract static class Computed implements FlightForm {

        abstract Answer answer(Principal p, FlightRequest r);

        @Override
        public String domain(Principal p, FlightRequest r) {
            return answer(p, r).domain();
        }

        @Override
        public FlightBackend.Plan plan(Principal p, FlightRequest r, FlightNodes nodes) {
            Answer a = answer(p, r);
            return new FlightBackend.Plan(ArrowIpc.schema(a.table(), a.meta()),
                    List.of(new FlightBackend.Part(form().name().toLowerCase(java.util.Locale.ROOT), nodes.anyMember(), a.table().rows())),
                    a.table().rows(), -1);
        }

        @Override
        public FlightBackend.BatchSource open(Principal p, FlightRequest r, int part, int batchRows) {
            Answer a = answer(p, r);
            return new TableBatches(a.table(), a.meta(), batchRows);
        }

        @Override
        public FlightBackend.Subscription subscribe(Principal p, FlightRequest r, int part, int batchRows) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "form " + form().name().toLowerCase(java.util.Locale.ROOT)
                    + " is read as a snapshot; a standing query's updates come over Server-Sent Events (BI_GUIDE.md)");
        }
    }

    /**
     * {@code form: model}: a BI model query (MQ, BI_GUIDE.md), {@code {"model": "risk-summary", "query": {...}}} or the MQ JSON with
     * {@code model} in it; answered by MQ with the person's row rules, masks and the BI power checked.
     */
    static final class Model extends Computed {
        private final ModelCatalog catalog;
        private final MqService mq;
        private final BiService bi;

        private final com.ash.drishti.server.bi.sql.SqlService sql;

        Model(ModelCatalog catalog, MqService mq, BiService bi, com.ash.drishti.server.bi.sql.SqlService sql) {
            this.catalog = catalog;
            this.mq = mq;
            this.bi = bi;
            this.sql = sql;
        }

        /**
         * A DSQL statement over the model ({@code {"model": …, "sql": …}}, what Flight SQL sends): the server's own SQL path
         * ({@link com.ash.drishti.server.bi.sql.SqlService#answer}: parse, bind as the reader, one model query or a composite over model
         * queries), never a second SQL engine.
         */
        private Answer statement(Principal p, String model, String text) {
            if (sql == null || !sql.properties().enabled()) {
                throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, com.ash.drishti.common.Branding.sqlName()
                        + " is switched off on this server (drishti.bi.sql.enabled)");
            }
            com.ash.drishti.server.bi.engine.QueryService.Result res = sql.answer(catalog.bind(p, model), model, text, null);
            return new Answer(FlightTables.of(res.columns(), res.rows()), FlightTables.meta("drishti.model", model, "drishti.masked",
                    res.meta() == null ? "" : String.join(",", res.meta().masked())), model);
        }

        @Override
        public FlightRequest.Form form() {
            return FlightRequest.Form.MODEL;
        }

        @Override
        public String domain(Principal p, FlightRequest r) {
            return "bi";
        }

        @Override
        Answer answer(Principal p, FlightRequest r) {
            JsonNode q = r.request();
            try {
                bi.requirePower(p, BiAccess.BI);
                if (q.isObject() && q.path("sql").isTextual()) {
                    if (!q.path("model").isTextual()) {
                        throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "a statement names its model: {\"model\": …, \"sql\": …}");
                    }
                    return statement(p, q.get("model").asText(), q.get("sql").asText());
                }
                ModelQuery mqy;
                if (q.isTextual()) {
                    mqy = MqParser.parse(q.asText());
                } else if (q.has("query")) {
                    JsonNode body = q.get("query").deepCopy();
                    if (body.isObject() && !body.has("mq")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("mq", 1);
                    }
                    mqy = MqParser.parse(body);
                    if (q.path("model").isTextual()) {
                        mqy = mqy.withModel(q.get("model").asText());
                    }
                } else {
                    mqy = MqParser.parse(q);
                }
                MqService.Answer a = mq.ask(catalog.bind(p, mqy.model()), mqy, false);
                return new Answer(FlightTables.of(a.result().columns(), a.result().rows()),
                        FlightTables.meta("drishti.model", mqy.model(), "drishti.masked",
                                a.result().meta() == null ? "" : String.join(",", a.result().meta().masked())),
                        mqy.model());
            } catch (DrishtiException e) {
                throw FlightBackends.refusal(e);
            } catch (RuntimeException e) {
                if (e instanceof FlightRefusal f) {
                    throw f;
                }
                throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "the model query was refused: " + e.getMessage(), e);
            }
        }

        @Override
        public List<FlightRequest> catalogue(Principal p) {
            List<FlightRequest> out = new ArrayList<>();
            if (!bi.hasPower(p, BiAccess.BI)) {
                return out;
            }
            for (Map<String, Object> m : catalog.list(p)) {
                out.add(new FlightRequest(FlightRequest.Form.MODEL, JsonNodeFactory.instance.objectNode().put("model", String.valueOf(m.get("id"))),
                        -1, false, -1));
            }
            return out;
        }
    }

    /**
     * {@code form: pivot}: a pivot view, the same JSON as {@code POST /api/v1/pivot/query} (BI_GUIDE.md), answered by the pivot
     * engine through its controller (the viewer, the edition, the audit line {@code pivot.query}); one record per cell and measure, as the
     * pivot's Arrow export writes it.
     */
    static final class Pivot extends Computed {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final PivotController pivot;

        Pivot(PivotController pivot) {
            this.pivot = pivot;
        }

        @Override
        public FlightRequest.Form form() {
            return FlightRequest.Form.PIVOT;
        }

        @Override
        @SuppressWarnings("unchecked")
        Answer answer(Principal p, FlightRequest r) {
            if (!r.request().isObject()) {
                throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "form pivot: request is the pivot view as JSON (POST /api/v1/pivot/query)");
            }
            try {
                Map<String, Object> body = JSON.convertValue(r.request(), Map.class);
                Map<String, Object> a = pivot.query(p, body);
                PivotController.Cells c = PivotController.cells(a);
                return new Answer(FlightTables.of(c.columns(), c.rows()),
                        FlightTables.meta("drishti.provenance", String.valueOf(a.get("provenance")), "drishti.source", String.valueOf(a.get("source"))),
                        "pivot:" + a.get("source"));
            } catch (DrishtiException e) {
                throw FlightBackends.refusal(e);
            }
        }

        @Override
        public String domain(Principal p, FlightRequest r) {
            JsonNode s = r.request().path("source");
            return s.isTextual() ? s.asText() : "pivot";
        }

        @Override
        public List<FlightRequest> catalogue(Principal p) {
            return List.of();
        }
    }
}

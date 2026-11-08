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
package com.ash.drishti.flight;

import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * One Flight request: a Rachana request (docs/architecture/ARCHITECTURE.md) and how it is read. It is the JSON of a Flight descriptor's
 * command and of a ticket (docs/guides/FLIGHT_REFERENCE.md):
 *
 * <pre>
 * {"form": "kind",    "request": {"kind": "trade", "paths": ["TRADE_ID", "DESK", "MTM"], "filter": "DESK = 'RATES'"}}
 * {"form": "model",   "request": {"model": "risk-summary", "query": {...the MQ query...}}}
 * {"form": "pivot",   "request": {...the same JSON as POST /api/v1/pivot/query...}}
 * {"form": "measure", "request": "measure(var.scenarios, scope: desk/RATES, date: 2026-10-30)"}
 * {"form": "lake",    "request": {"table": "news.headlines", "columns": [...], "since": 1200}}
 * </pre>
 *
 * A ticket adds {@code part} (which endpoint of the plan: a shard, the in-memory lake's owner) and, for a live stream, {@code stream: true}
 * and {@code resume} (the last sequence the client holds; the stream sends from the next). {@code request} is a JSON object or Rachana text;
 * the query layer reads it exactly as the REST API does.
 */
public record FlightRequest(Form form, JsonNode request, int part, boolean stream, long resume) {

    /** What the request reads. */
    public enum Form { KIND, MODEL, PIVOT, MEASURE, LAKE }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 1 << 20;

    public FlightRequest {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(request, "request");
        if (part < -1) {
            throw new IllegalArgumentException("part must be -1 (all) or a part number");
        }
        resume = Math.max(-1, resume);
    }

    /** Parses a descriptor command or a ticket; refuses with {@code DRS-5603} and the reason. */
    public static FlightRequest parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "the ticket is empty: send a request as JSON");
        }
        if (bytes.length > MAX_BYTES) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "the ticket is over 1 MB");
        }
        JsonNode n;
        try {
            n = JSON.readTree(bytes);
        } catch (java.io.IOException e) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "the ticket is not JSON: "
                    + (e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage()));
        }
        if (n == null || !n.isObject()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "the ticket is a JSON object with form and request");
        }
        String f = n.path("form").asText("");
        Form form;
        try {
            form = Form.valueOf(f.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "form is kind, model, pivot, measure or lake, not '" + f + "'");
        }
        JsonNode req = n.get("request");
        if (req == null || req.isNull() || !(req.isObject() || req.isTextual())) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "request is a JSON object or Rachana text");
        }
        if (req.isTextual() && req.asText().isBlank()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "request is empty");
        }
        int part = n.path("part").asInt(-1);
        if (part < -1) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "part is a part number of the plan, from 0");
        }
        return new FlightRequest(form, req, part, n.path("stream").asBoolean(false), n.path("resume").asLong(-1));
    }

    /** The JSON of this request (the bytes of a ticket). */
    public byte[] toBytes() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("form", form.name().toLowerCase(Locale.ROOT));
        o.set("request", request);
        if (part >= 0) {
            o.put("part", part);
        }
        if (stream) {
            o.put("stream", true);
        }
        if (resume >= 0) {
            o.put("resume", resume);
        }
        try {
            return JSON.writeValueAsBytes(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same request for one part of its plan (an endpoint's ticket). */
    public FlightRequest forPart(int p) {
        return new FlightRequest(form, request, p, stream, resume);
    }

    /** The same request resuming after a sequence number. */
    public FlightRequest resumingAfter(long sequence) {
        return new FlightRequest(form, request, part, true, sequence);
    }

    /** The request as text for audit and Admin → Data out (the Rachana text, or the JSON). */
    public String text() {
        return request.isTextual() ? request.asText() : request.toString();
    }

    @Override
    public String toString() {
        return new String(toBytes(), StandardCharsets.UTF_8);
    }
}

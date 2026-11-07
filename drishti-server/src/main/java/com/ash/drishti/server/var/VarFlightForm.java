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
package com.ash.drishti.server.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.flight.FlightRefusal;
import com.ash.drishti.flight.FlightRequest;
import com.ash.drishti.server.flight.FlightBackends;
import com.ash.drishti.server.flight.FlightForm;
import com.ash.drishti.server.flight.FlightNodes;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * {@code form: measure} for VaR (docs/architecture/DATA_ARCHITECTURE.md section 12 decided 4 and Q6; VAR_GUIDE.md section 2: the vectors are computed when
 * asked, never stored): the engine's own figures through {@link VarService#scoped} as the person (their view, the cache), exactly.
 * <ul>
 *   <li>{@code var.scenarios}: the scope's P&amp;L vector, one row per scenario: {@code SCENARIO} (int64, 0-based), {@code SCENARIO_DATE},
 *       {@code PNL} (float64, as the engine sums); the schema's metadata carries the scope, date, currency, VaR and ES;</li>
 *   <li>{@code var.parts}: one row per part of the scope ({@code by}: child, node, position, trade, desk, book): {@code KEY}, {@code SCOPE},
 *       {@code LABEL}, {@code KIND} and {@code PNL}, a fixed-size list of one value per scenario: float64 for nodes, float32 for positions
 *       and trades as the hot file stores them (metadata {@code drishti.pnl.precision}).</li>
 * </ul>
 * With {@code stream: true}, {@code var.scenarios} sends the vector again each time an intraday batch is applied to the date; the sequence
 * is the date's intraday stamp (resume: the stamp held; a newer one is sent at once).
 */
final class VarFlightForm implements FlightForm {

    private static final Pattern CALL = Pattern.compile("^\\s*measure\\s*\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*(?:,(.*))?\\)\\s*$", Pattern.DOTALL);
    private static final Pattern OPTION = Pattern.compile("([A-Za-z]+)\\s*:\\s*'?([^,']+)'?");
    private static final FieldType F64 = FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE));
    private static final FieldType F32 = FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE));
    private static final FieldType TEXT = FieldType.nullable(ArrowType.Utf8.INSTANCE);

    private final VarService svc;
    private final VarViews views;

    VarFlightForm(VarService svc, VarViews views) {
        this.svc = svc;
        this.views = views;
    }

    /** A measure call read from the request: {@code {"measure", "scope", "date", "ccy", "by"}} or {@code measure('var.scenarios', 'DESK:RATES', date: 2026-10-30)}. */
    record Call(String measure, String scope, LocalDate date, String ccy, String by) {}

    static Call call(FlightRequest r) {
        JsonNode q = r.request();
        Map<String, String> o = new LinkedHashMap<>();
        String measure;
        String scope;
        if (q.isTextual()) {
            Matcher m = CALL.matcher(q.asText());
            if (!m.matches()) {
                throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "form measure: write measure('var.scenarios', 'DESK:RATES', date: 2026-10-30)");
            }
            measure = m.group(1);
            scope = m.group(2);
            if (m.group(3) != null) {
                Matcher om = OPTION.matcher(m.group(3));
                while (om.find()) {
                    o.put(om.group(1), om.group(2).trim());
                }
            }
        } else {
            measure = q.path("measure").asText("");
            scope = q.path("scope").asText("");
            for (String k : List.of("date", "ccy", "by")) {
                if (q.path(k).isTextual()) {
                    o.put(k, q.get(k).asText());
                }
            }
        }
        measure = measure.trim().toLowerCase(java.util.Locale.ROOT);
        if (!measure.equals("var.scenarios") && !measure.equals("var.parts")) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "measures read through Flight: var.scenarios, var.parts (not '" + measure + "')");
        }
        if (scope.isBlank()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "give a scope: BANK, DESK:RATES, POS:<id>, PORT:<id> ...");
        }
        LocalDate date = null;
        if (o.containsKey("date")) {
            try {
                date = LocalDate.parse(o.get("date"));
            } catch (DateTimeParseException e) {
                throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "not a date: '" + o.get("date") + "' (write 2026-10-30)");
            }
        }
        return new Call(measure, scope.trim(), date, o.get("ccy"), o.getOrDefault("by", "child"));
    }

    @Override
    public FlightRequest.Form form() {
        return FlightRequest.Form.MEASURE;
    }

    @Override
    public String domain(Principal p, FlightRequest r) {
        return "var";
    }

    @Override
    public FlightBackend.Plan plan(Principal p, FlightRequest r, FlightNodes nodes) {
        Call c = call(r);
        VarService.Scoped s = scoped(p, c);
        Built b = build(p, c, s);
        long rows = b.rows();
        return new FlightBackend.Plan(b.schema(), List.of(new FlightBackend.Part(s.res().canonical(), nodes.anyMember(), rows)), rows, -1);
    }

    @Override
    public FlightBackend.BatchSource open(Principal p, FlightRequest r, int part, int batchRows) {
        Call c = call(r);
        Built b = build(p, c, scoped(p, c));
        return new FlightBackend.BatchSource() {
            boolean sent;

            @Override
            public Schema schema() {
                return b.schema();
            }

            @Override
            public boolean next(VectorSchemaRoot root) {
                if (sent) {
                    return false;
                }
                sent = true;
                b.fill(root);
                return true;
            }
        };
    }

    @Override
    public FlightBackend.Subscription subscribe(Principal p, FlightRequest r, int part, int batchRows) {
        Call c = call(r);
        if (!c.measure().equals("var.scenarios")) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "a live stream reads var.scenarios (the vector after each intraday batch)");
        }
        Schema schema = build(p, c, scoped(p, c)).schema();
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
                    VarService.Scoped s = scoped(p, c);
                    long stamp = s.date().intradayStamp();
                    if (stamp > held[0]) {
                        build(p, c, s).fill(root);
                        held[0] = stamp;
                        return stamp;
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
        return List.of(new FlightRequest(FlightRequest.Form.MEASURE,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode("measure('var.scenarios', 'BANK')"), -1, false, -1));
    }

    private VarService.Scoped scoped(Principal p, Call c) {
        try {
            return svc.scoped(p, c.scope(), c.date(), c.ccy(), null);
        } catch (DrishtiException e) {
            throw FlightBackends.refusal(e);
        }
    }

    /** What a read sends: its schema, its row count and how it fills a root. */
    interface Built {
        Schema schema();

        int rows();

        void fill(VectorSchemaRoot root);
    }

    private Built build(Principal p, Call c, VarService.Scoped s) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("drishti.measure", c.measure());
        meta.put("drishti.scope", s.res().canonical());
        meta.put("drishti.date", s.date().date().toString());
        meta.put("drishti.currency", s.date().currency());
        meta.put("drishti.var", Double.toString(s.measures().var()));
        meta.put("drishti.es", Double.toString(s.measures().es()));
        meta.put("drishti.intraday", Long.toString(s.date().intradayStamp()));
        List<LocalDate> dates = s.dates();
        if (c.measure().equals("var.scenarios")) {
            double[] v = s.vector();
            Schema schema = new Schema(List.of(new Field("SCENARIO", FieldType.nullable(new ArrowType.Int(64, true)), null),
                    new Field("SCENARIO_DATE", TEXT, null), new Field("PNL", F64, null)), meta);
            return new Built() {
                public Schema schema() {
                    return schema;
                }

                public int rows() {
                    return v.length;
                }

                public void fill(VectorSchemaRoot root) {
                    BigIntVector sc = (BigIntVector) root.getVector(0);
                    VarCharVector d = (VarCharVector) root.getVector(1);
                    Float8Vector pnl = (Float8Vector) root.getVector(2);
                    for (FieldVector f : root.getFieldVectors()) {
                        f.reset();
                    }
                    sc.allocateNew(v.length);
                    d.allocateNew(v.length);
                    pnl.allocateNew(v.length);
                    for (int i = 0; i < v.length; i++) {
                        sc.set(i, i);
                        if (i < dates.size()) {
                            d.setSafe(i, dates.get(i).toString().getBytes(StandardCharsets.UTF_8));
                        } else {
                            d.setNull(i);
                        }
                        pnl.set(i, v[i]);
                    }
                    for (FieldVector f : root.getFieldVectors()) {
                        f.setValueCount(v.length);
                    }
                    root.setRowCount(v.length);
                }
            };
        }
        List<VarViews.Part> parts = views.parts(s, c.by(), p);
        int width = s.vector().length;
        boolean single = !parts.isEmpty() && parts.stream().allMatch(x -> x.kind().equals("position") || x.kind().equals("trade"));
        meta.put("drishti.pnl.precision", single ? "float32 (as the hot file stores position rows)" : "float64 (as the engine sums)");
        meta.put("drishti.scenario.dates", String.join(",", dates.stream().map(LocalDate::toString).toList()));
        Field item = new Field("item", single ? F32 : F64, null);
        Schema schema = new Schema(List.of(new Field("KEY", TEXT, null), new Field("SCOPE", TEXT, null), new Field("LABEL", TEXT, null),
                new Field("KIND", TEXT, null), new Field("PNL", FieldType.nullable(new ArrowType.FixedSizeList(width)), List.of(item))), meta);
        return new Built() {
            public Schema schema() {
                return schema;
            }

            public int rows() {
                return parts.size();
            }

            public void fill(VectorSchemaRoot root) {
                int n = parts.size();
                for (FieldVector f : root.getFieldVectors()) {
                    f.reset();
                }
                VarCharVector[] text = new VarCharVector[4];
                for (int k = 0; k < 4; k++) {
                    text[k] = (VarCharVector) root.getVector(k);
                    text[k].allocateNew(n);
                }
                FixedSizeListVector list = (FixedSizeListVector) root.getVector(4);
                list.setInitialCapacity(Math.max(1, n));
                list.allocateNew();
                FieldVector items = list.getDataVector();
                for (int i = 0; i < n; i++) {
                    VarViews.Part pt = parts.get(i);
                    String[] t = {pt.key(), pt.scope(), pt.label(), pt.kind()};
                    for (int k = 0; k < 4; k++) {
                        if (t[k] == null) {
                            text[k].setNull(i);
                        } else {
                            text[k].setSafe(i, t[k].getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    list.setNotNull(i);
                    double[] v = pt.vector();
                    for (int j = 0; j < width; j++) {
                        double x = j < v.length ? v[j] : 0.0;
                        if (single) {
                            ((Float4Vector) items).setSafe(i * width + j, (float) x);
                        } else {
                            ((Float8Vector) items).setSafe(i * width + j, x);
                        }
                    }
                }
                items.setValueCount(n * width);
                for (FieldVector f : root.getFieldVectors()) {
                    f.setValueCount(n);
                }
                root.setRowCount(n);
            }
        };
    }
}

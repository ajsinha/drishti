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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The inputs of a Python Transform (docs/architecture/BI_MODEL_SPEC.md section 1.2 C; BI_PARITY_PLAN.md P5). An input is a <b>model query</b>, as since 2.2,
 * or (2.4.0) a <b>pack kind read straight through a connector</b>, so raw rows can be cleaned before any dataset or model exists:
 *
 * <pre>
 * inputs:
 *   trades: { kind: trade, fields: [TRADE_ID, DESK, CURRENCY, NOTIONAL], where: [{ field: DESK, op: in, values: [DESK-FX] }] }
 *   prices: { kind: price, connector: market-store }          # a connector by name; empty: whichever serves the kind
 * </pre>
 *
 * A kind input is the rows of the kind's pack-generated dataset (one row per entity per business date, or per element of {@code each}), asked through its
 * implicit model <em>as the reader</em>: the kind's entitlements, the reader's row rules and masks apply exactly as they do to a report on it, and a masked
 * column arrives masked. Naming a connector (or {@code each}) reads the same kind through that connector instead of the one the pack routes it to. Both forms
 * answer through {@link #ask}, which the Python dataset (DatasetSources) and the Transform editor's input preview ({@link TransformInputController}) share,
 * so the editor previews exactly the rows a run would hand the Transform.
 */
public final class TransformInputs {

    /** The keys a kind input may have. */
    public static final Set<String> KIND_KEYS = Set.of("kind", "pack", "connector", "each", "fields", "where", "limit");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-]{0,127}");

    /**
     * An input bound for one reader: the model it is asked of, the query, and the alias whose prefix is dropped from the answer's column names.
     *
     * @param kind true for a pack kind input
     * @param ref the model reference asked ({@code dataset:pack.trading.trade} for a kind input)
     */
    public record Asked(MqService.Bound bound, ModelQuery query, String alias, boolean kind, String ref) {}

    private TransformInputs() {}

    /** Whether an input names a pack kind rather than a model query. */
    public static boolean isKind(JsonNode in) {
        return in != null && in.isObject() && in.has("kind") && !in.has("model") && !in.has("select");
    }

    /** The problems of a kind input's shape, as {path suffix, message}; empty when it is well formed. Names are checked when it runs. */
    public static List<String[]> check(JsonNode in) {
        List<String[]> out = new ArrayList<>();
        in.fieldNames().forEachRemaining(k -> {
            if (!KIND_KEYS.contains(k)) {
                out.add(new String[] {k, "a kind input has " + new java.util.TreeSet<>(KIND_KEYS) + ", not '" + k + "'"});
            }
        });
        for (String k : List.of("kind", "pack", "connector", "each")) {
            JsonNode v = in.path(k);
            if (!v.isMissingNode() && !(v.isTextual() && NAME.matcher(v.asText()).matches())) {
                out.add(new String[] {k, "'" + k + "' is a name (letters, digits, '.', '-', '_')"});
            }
        }
        JsonNode f = in.path("fields");
        if (!f.isMissingNode() && !(f.isArray() && allNames(f))) {
            out.add(new String[] {"fields", "fields is a list of the kind's columns, such as [TRADE_ID, DESK, NOTIONAL]"});
        }
        JsonNode w = in.path("where");
        if (!w.isMissingNode() && !w.isArray()) {
            out.add(new String[] {"where", "where is a list of filters: [{ field: DESK, op: in, values: [DESK-FX] }]"});
        }
        JsonNode l = in.path("limit");
        if (!l.isMissingNode() && !(l.canConvertToInt() && l.asInt() > 0)) {
            out.add(new String[] {"limit", "limit is a positive number of rows"});
        }
        if (out.isEmpty()) {
            try {
                ObjectNode probe = query("dataset:check", "t", in, 10);
                ((ArrayNode) probe.path("select").path("fields")).add("t.probe");
                MqParser.parse(probe);                                          // the filters' shape, with the query's own codes
            } catch (MqException e) {
                e.problems().forEach(p -> out.add(new String[] {p.path().isEmpty() ? "where" : p.path(), p.code() + " " + p.detail()}));
            }
        }
        return out;
    }

    private static boolean allNames(JsonNode list) {
        for (JsonNode n : list) {
            if (!n.isTextual() || !NAME.matcher(n.asText()).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Binds an input for {@code reader} and builds the query it is asked with: a kind input's rows (its {@code fields}, else every stored column), a model
     * query as it is stated. A query that names no time is asked at {@code time} (the outer query's date; {@link ModelQuery.Time#DEFAULT}: the latest).
     *
     * @param maxRows the most rows the input may hand over (a kind input's own {@code limit} may be lower)
     */
    public static Asked ask(ModelCatalog catalog, Principal reader, JsonNode in, ModelQuery.Time time, int maxRows) {
        if (!isKind(in)) {
            String model = in == null ? "" : in.path("model").asText("");
            MqService.Bound b = catalog.bind(reader, model);
            ObjectNode o = in != null && in.isObject() ? ((ObjectNode) in).deepCopy() : JSON.createObjectNode();
            if (!o.has("mq")) {
                o.put("mq", 1);
            }
            return new Asked(b, at(MqParser.parse(o), time), null, false, model);
        }
        List<String[]> problems = check(in);
        if (!problems.isEmpty()) {
            throw new MqException(MqException.Problem.of("DRS-9108", "input." + problems.get(0)[0], problems.get(0)[1]));
        }
        DatasetDef def = kindDataset(catalog, in);
        String ref = com.ash.drishti.server.bi.layer.ImplicitModels.QUERY_PREFIX + def.name();
        boolean other = !java.util.Objects.equals(def.source().connector(), generated(catalog, in).source().connector()) || def.source().each() != null;
        MqService.Bound b = other ? catalog.bindDataset(reader, def) : catalog.bind(reader, ref);
        String alias = b.view().def().datasets().keySet().iterator().next();
        var table = b.view().flat().tables().get(alias);
        List<String> names = new ArrayList<>();
        if (in.path("fields").isArray() && !in.path("fields").isEmpty()) {
            List<String> unknown = new ArrayList<>();
            for (JsonNode f : in.path("fields")) {
                String n = bare(f.asText(), alias);
                if (!table.columns().containsKey(n)) {
                    unknown.add(n);
                }
                names.add(n);
            }
            if (!unknown.isEmpty()) {
                throw new MqException(new MqException.Problem("DRS-9108", "input.fields", "the kind '" + def.source().kind() + "' has no column " + unknown + " (it has "
                        + table.columns().keySet() + ")", java.util.Map.of("columns", new ArrayList<>(table.columns().keySet()))));
            }
        } else {
            table.physical().forEach(c -> names.add(c.name()));
        }
        int limit = Math.min(maxRows, in.path("limit").canConvertToInt() ? in.path("limit").asInt() : maxRows);
        ObjectNode o = query(ref, alias, in, limit);
        ArrayNode fields = (ArrayNode) o.path("select").path("fields");
        names.forEach(n -> fields.add(alias + "." + n));
        return new Asked(b, at(MqParser.parse(o), time), alias, true, ref);
    }

    /** The JSON of a kind input's query: shape rows, no measures, its filters with their fields qualified by the alias. */
    private static ObjectNode query(String ref, String alias, JsonNode in, int limit) {
        ObjectNode o = JSON.createObjectNode();
        o.put("mq", 1);
        o.put("model", ref);
        ObjectNode select = o.putObject("select");
        select.putArray("fields");
        select.putArray("measures");
        o.put("shape", ModelQuery.ROWS);
        o.put("limit", limit);
        if (in.path("where").isArray() && !in.path("where").isEmpty()) {
            ArrayNode where = o.putArray("where");
            for (JsonNode w : in.path("where")) {
                ObjectNode c = w.isObject() ? ((ObjectNode) w).deepCopy() : JSON.createObjectNode();
                if (c.path("field").isTextual() && !c.path("field").asText().contains(".")) {
                    c.put("field", alias + "." + c.path("field").asText());
                }
                where.add(c);
            }
        }
        return o;
    }

    private static String bare(String field, String alias) {
        return field.startsWith(alias + ".") ? field.substring(alias.length() + 1) : field;
    }

    /** The generated dataset of the kind (of the named pack, else the first that declares it). */
    private static DatasetDef generated(ModelCatalog catalog, JsonNode in) {
        String kind = in.path("kind").asText("");
        String pack = in.path("pack").asText("");
        Optional<DatasetDef> d = pack.isBlank() ? catalog.packs().ofKind(kind) : catalog.packs().named(DatasetDef.PACK_PREFIX + pack + "." + kind);
        return d.orElseThrow(() -> new MqException(MqException.Problem.of("DRS-9102", "input.kind", "no active pack declares the kind '" + kind + "'"
                + (pack.isBlank() ? "" : " in the pack '" + pack + "'"))));
    }

    /** The dataset a kind input reads: the generated one, or a copy of it that reads the named connector (or one element of {@code each}) instead. */
    static DatasetDef kindDataset(ModelCatalog catalog, JsonNode in) {
        DatasetDef g = generated(catalog, in);
        String connector = in.path("connector").asText("");
        String each = in.has("each") ? in.path("each").asText("") : null;
        if ((connector.isBlank() || connector.equals(g.source().connector())) && each == null) {
            return g;
        }
        String via = connector.isBlank() ? (g.source().connector() == null ? "" : g.source().connector()) : connector;
        DatasetDef.Source s = DatasetDef.Source.ofKind(g.source().pack(), g.source().kind(), via, each);
        String id = g.id() + "_via_" + via.replaceAll("[^A-Za-z0-9_]", "_") + (each == null ? "" : "_each_" + each.replaceAll("[^A-Za-z0-9_]", "_"));
        return new DatasetDef(g.name(), g.version(), id, g.title() + " (through " + via + ")", g.description(), g.owner(), g.sample(), g.endorsement(), g.generated(), s,
                g.grain(), g.columns(), g.time(), g.refresh(), g.security(), g.extendsRef(), g.where(), g.ai(), g.tests(), g.rollups(), g.derivation());
    }

    /** {@code q} at {@code time} when it names no time of its own. */
    static ModelQuery at(ModelQuery q, ModelQuery.Time time) {
        if (time == null || time.equals(ModelQuery.Time.DEFAULT) || !q.time().equals(ModelQuery.Time.DEFAULT)) {
            return q;
        }
        return new ModelQuery(q.mq(), q.model(), q.define(), q.fields(), q.measures(), q.where(), q.having(), time, q.sort(), q.limit(), q.totals(), q.drill(), q.shape(),
                q.params(), q.options(), q.ext());
    }

    /** An answer with the alias dropped from its column names ({@code trade.DESK} is {@code DESK}): the frame the Transform reads. */
    public static QueryService.Result bare(QueryService.Result r, String alias) {
        if (alias == null) {
            return r;
        }
        List<ArrowIpcWriter.Column> cols = r.columns().stream().map(c -> new ArrowIpcWriter.Column(bare(c.name(), alias), c.kind(), c.values())).toList();
        return new QueryService.Result(cols, r.rows(), r.meta());
    }
}

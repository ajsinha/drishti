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
package com.ash.drishti.server.bi.layer;

import com.ash.drishti.packs.Pack;
import com.ash.drishti.server.bi.model.PackModels;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The datasets every pack publishes for its kinds (decision D3, docs/architecture/BI_MODEL_SPEC.md section 7): {@code pack.<pack>.<kind>}, derived when it is
 * first asked for and again whenever the packs change (the registry's version moves), never written to {@code config/bi}. Columns are the connector's promoted
 * columns (else the paths of the pack's samples) under camel-case names; a column whose name the link graph knows ({@code desk}, {@code counterpartyId}) is a
 * dimension that {@code references} its kind; numbers are amounts, dates dates; a column named in the pack's vocabulary ({@code config/about.yaml}) carries
 * that meaning, and a {@code trade currency} unit becomes {@code unit: { currency: currency }}. The grain is one row per entity id per business date; the time
 * mode is {@code live} where the kind's connector pushes (and {@code drishti.bi.mq.pack-live} allows it), else {@code snapshot}; the version is the pack's;
 * the owner is {@code pack:<pack>} and the endorsement {@code certified}. A pack may refine it with an optional {@code bi:} block in {@code pack.yaml}
 * ({@code datasets.<kind>.grain.keys}, {@code exclude}, {@code time}, {@code skip}). Read-only: an edit is {@code DRS-9107}; {@code extends} narrows one.
 * Thread-safe: one generation at a time per registry version, a lock (never {@code synchronized}) around the rebuild.
 */
public final class PackDatasets {

    /** Describes a kind of a pack as a table (the connector's columns or the samples'): {@link PackModels#describe}. */
    public interface Describer {
        PackModels.TableInfo describe(Pack pack, String kind);
    }

    private final Supplier<List<Pack>> packs;
    private final LongSupplier version;
    private final Describer describer;
    private final Function<String, Optional<String>> kindOfField;
    private final Predicate<String> pushes;
    private final boolean liveDefault;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, DatasetDef> byName = new ConcurrentHashMap<>();
    private volatile long builtFor = Long.MIN_VALUE;

    /**
     * @param kindOfField the kind a field names ({@code desk} is a desk), from the link graph
     * @param pushes whether the connector that serves a kind pushes live updates
     * @param liveDefault false keeps every generated dataset a snapshot
     */
    public PackDatasets(Supplier<List<Pack>> packs, LongSupplier version, Describer describer, Function<String, Optional<String>> kindOfField, Predicate<String> pushes,
            boolean liveDefault) {
        this.packs = packs;
        this.version = version;
        this.describer = describer;
        this.kindOfField = kindOfField;
        this.pushes = pushes;
        this.liveDefault = liveDefault;
    }

    /**
     * Whether the pack's connector holds one slice of its kinds ({@code settings.union: true}, as the trading pack's trade-endur, trade-calypso
     * and trade-murex): a route names one of them for reads, but a BI table of the kind must read them all.
     */
    static boolean unionMember(Pack p, String connector) {
        if (connector == null || connector.isBlank()) {
            return false;
        }
        Map<String, Object> spec = map(map(p.manifest().get("connectors")).get(connector));
        return Boolean.parseBoolean(String.valueOf(map(spec.get("settings")).get("union")));
    }

    /** Every generated dataset, by name. */
    public Map<String, DatasetDef> all() {
        refresh();
        return Map.copyOf(byName);
    }

    public Optional<DatasetDef> named(String name) {
        refresh();
        return Optional.ofNullable(byName.get(name));
    }

    /** The generated dataset of a kind (the first pack that declares it), for an implicit model's dimensions. */
    public Optional<DatasetDef> ofKind(String kind) {
        refresh();
        return byName.values().stream().filter(d -> d.source().kind().equals(kind) && d.source().each() == null).findFirst();
    }

    private void refresh() {
        long v = version.getAsLong();
        if (v == builtFor) {
            return;
        }
        lock.lock();
        try {
            if (v == builtFor) {
                return;
            }
            Map<String, DatasetDef> next = new LinkedHashMap<>();
            for (Pack p : packs.get()) {
                Map<String, Object> bi = map(p.manifest().get("bi"));
                List<?> skip = bi.get("skip") instanceof List<?> l ? l : List.of();
                Map<String, Object> vocabulary = vocabulary(p);
                for (String kind : p.kinds()) {
                    if (skip.contains(kind)) {
                        continue;
                    }
                    try {
                        DatasetDef d = generate(p, kind, map(map(bi.get("datasets")).get(kind)), vocabulary);
                        d = withDescription(d, kindDescription(p, kind, d));
                        next.put(d.name(), d);
                    } catch (RuntimeException e) {
                        // a kind whose columns cannot be read now gets no dataset until the packs change again
                    }
                }
            }
            byName.clear();
            byName.putAll(next);
            builtFor = v;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static DatasetDef withDescription(DatasetDef d, String description) {
        return new DatasetDef(d.name(), d.version(), d.id(), d.title(), description, d.owner(), d.sample(), d.endorsement(), d.generated(), d.source(), d.grain(),
                d.columns(), d.time(), d.refresh(), d.security(), d.extendsRef(), d.where(), d.ai(), d.tests(), d.rollups());
    }

    /**
     * What a dataset card says about one kind (not about the whole pack): the kind's {@code description:} in the pack's {@code config/about.yaml}
     * ({@code kinds.<kind>.description}), else what its glossary says the kind is, else its grain in words with the pack's title.
     */
    static String kindDescription(Pack p, String kind, DatasetDef d) {
        Path about = p.resolve("config/about.yaml");
        if (Files.isRegularFile(about)) {
            try {
                JsonNode k = LayerYaml.YAML.readTree(Files.readString(about)).path("kinds").path(kind);
                if (k.path("description").isTextual() && !k.path("description").asText().isBlank()) {
                    return k.path("description").asText().strip();
                }
                JsonNode self = k.path("glossary").path(kind).path("means");
                if (self.isTextual() && !self.asText().isBlank() && !self.asText().startsWith("The " + kind + " the line")) {
                    return self.asText().strip();
                }
            } catch (java.io.IOException | RuntimeException e) {
                // an unreadable about file: the words below
            }
        }
        return d.title() + ": one row per " + kind.replace('-', ' ') + " per business date, from the " + (p.title() == null || p.title().isBlank() ? p.name() : p.title())
                + " pack.";
    }

    /** The {@code vocabulary:} of the pack's {@code config/about.yaml}, or empty. */
    private static Map<String, Object> vocabulary(Pack p) {
        Path about = p.resolve("config/about.yaml");
        if (!Files.isRegularFile(about)) {
            return Map.of();
        }
        try {
            JsonNode n = LayerYaml.YAML.readTree(Files.readString(about)).path("vocabulary");
            Map<String, Object> out = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> out.put(e.getKey(), LayerYaml.YAML.convertValue(e.getValue(), Object.class)));
            return out;
        } catch (java.io.IOException | RuntimeException e) {
            return Map.of();
        }
    }

    DatasetDef generate(Pack p, String kind, Map<String, Object> refine, Map<String, Object> vocabulary) {
        PackModels.TableInfo info = describer.describe(p, kind);
        List<?> exclude = refine.get("exclude") instanceof List<?> l ? l : List.of();
        Map<String, Object> overrides = map(refine.get("columns"));
        Map<String, DatasetDef.Col> cols = new LinkedHashMap<>();
        boolean hasCurrency = info.columns().stream().anyMatch(c -> "currency".equals(c.name()));
        for (PackModels.ColumnInfo c : info.columns()) {
            if (exclude.contains(c.name()) || DatasetDef.BUILT_IN.contains(c.name())) {
                continue;
            }
            String leaf = c.path().substring(c.path().lastIndexOf('.') + 1);
            Optional<String> ref = kindOfField.apply(c.name()).or(() -> kindOfField.apply(c.path())).or(() -> c.path().contains(".") && "id".equals(leaf)
                    ? kindOfField.apply(c.path().substring(0, c.path().indexOf('.'))) : Optional.empty());
            String role = ref.isPresent() ? "dimension" : DatasetDefParser.defaultRole(c.type(), null);
            Map<String, Object> vocab = map(vocabulary.containsKey(c.name()) ? vocabulary.get(c.name()) : vocabulary.get(leaf));
            String meaning = vocab.isEmpty() ? null : vocabulary.containsKey(c.name()) ? c.name() : leaf;
            String unit = vocab.get("unit") == null ? null : String.valueOf(vocab.get("unit"));
            String currency = unit != null && unit.contains("currency") && hasCurrency ? "currency" : null;
            boolean amount = "amount".equals(role);
            Map<String, Object> o = map(overrides.get(c.name()));
            Map<String, Object> ou = map(o.get("unit"));
            if (ou.get("currency") != null) {
                currency = String.valueOf(ou.get("currency"));
            }
            String additive = o.get("additive") != null ? String.valueOf(o.get("additive")) : amount ? "true" : "false";
            cols.put(c.name(), new DatasetDef.Col(c.name(), c.type(), c.path().equals(c.name()) ? null : c.path(), null, role, meaning, ref.orElse(null), null, currency,
                    currency == null ? unit : null, additive, amount ? "sum" : null, null, "c_" + c.name(), vocab.get("means") == null ? "" : String.valueOf(vocab.get("means"))));
        }
        Map<String, Object> grain = map(refine.get("grain"));
        List<String> keys = grain.get("keys") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of("id");
        String connector = p.routes().getOrDefault(kind, info.connector());
        if (unionMember(p, connector)) {
            connector = null;                      // one source system of several (union: true): the table reads every one of them together
        }
        String forced = refine.get("time") == null ? null : String.valueOf(refine.get("time"));
        String mode = forced != null ? forced : liveDefault && pushes.test(kind) ? "live" : "snapshot";
        String name = DatasetDef.PACK_PREFIX + p.name() + "." + kind;
        String title = title(p, kind);
        return new DatasetDef(name, p.version(), "ds_pack_" + p.name().replace('-', '_') + "_" + kind.replace('-', '_'), title, p.description(), "pack:" + p.name(),
                p.sample(), "certified", new DatasetDef.Generated("pack", p.name(), p.version()), DatasetDef.Source.ofKind(p.name(), kind, connector, null),
                new DatasetDef.Grain("One row per " + kind + " per business date", keys, "businessDate"), cols,
                new DatasetDef.Time(mode, "business-date", null, null, null, null, null), new DatasetDef.Refresh("", true), DatasetDef.Security.NONE, null, null, null,
                List.of(), new ArrayList<>());
    }

    /** The kind's label from the pack's mnemonics, else the kind in words. */
    private static String title(Pack p, String kind) {
        for (Object m : map(p.manifest().get("mnemonics")).values()) {
            Map<String, Object> mm = map(m);
            if (kind.equals(String.valueOf(mm.get("kind"))) && mm.get("label") != null) {
                return String.valueOf(mm.get("label"));
            }
        }
        String words = kind.replace('-', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** The YAML a person sees on a generated dataset's YAML tab (read-only). */
    public static String yaml(DatasetDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rachana", 2);
        m.put("dataset", d.name());
        m.put("version", d.version());
        m.put("id", d.id());
        m.put("title", d.title());
        m.put("description", d.description());
        m.put("owner", d.owner());
        m.put("endorsement", d.endorsement());
        m.put("generated", Map.of("by", "pack", "pack", d.generated().pack(), "packVersion", d.generated().packVersion()));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("pack", d.source().pack());
        source.put("kind", d.source().kind());
        if (d.source().connector() != null && !d.source().connector().isBlank()) {
            source.put("connector", d.source().connector());
        }
        m.put("source", source);
        m.put("grain", Map.of("says", d.grain().says(), "keys", d.grain().keys(), "date", d.grain().date()));
        m.put("time", Map.of("mode", d.time().mode(), "as-of", d.time().asOf()));
        m.put("refresh", Map.of("on-load", true));
        Map<String, Object> cols = columns(d);
        m.put("columns", cols);
        m.put("tests", List.of(Map.of("name", "One row per " + d.source().kind(), "unique", d.grain().keys())));
        return write(m);
    }

    /** A dataset that extends another, as it reads once the chain is folded in (the base's source, grain, time and columns, every layer's row rules). */
    public static String resolvedYaml(ResolvedDataset r) {
        DatasetDef d = r.def();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rachana", 2);
        m.put("dataset", d.name());
        m.put("version", d.version());
        m.put("id", d.id());
        m.put("title", d.title());
        m.put("description", d.description());
        if (d.extendsRef() != null) {
            m.put("extends", d.extendsRef());
        }
        if (d.source() != null && DatasetDef.Source.PACK.equals(d.source().type())) {
            m.put("source", Map.of("pack", d.source().pack(), "kind", d.source().kind()));
        }
        if (d.grain() != null) {
            m.put("grain", Map.of("says", d.grain().says() == null ? "" : d.grain().says(), "keys", d.grain().keys(), "date", d.grain().date() == null ? "" : d.grain().date()));
        }
        if (d.time() != null) {
            m.put("time", Map.of("mode", d.time().mode(), "as-of", d.time().asOf() == null ? "business-date" : d.time().asOf()));
        }
        m.put("columns", columns(d));
        List<Map<String, Object>> rows = new ArrayList<>();
        r.layers().forEach(l -> l.forEach(rule -> rows.add(Map.of("roles", rule.roles(), "where", rule.where()))));
        Map<String, Object> sec = new LinkedHashMap<>();
        sec.put("rows", rows);
        sec.put("masked", new ArrayList<>(r.masked()));
        m.put("security", sec);
        if (!r.filters().isEmpty()) {
            m.put("where", String.join(" and ", r.filters()));
        }
        m.put("chain", r.chain());
        return write(m);
    }

    private static Map<String, Object> columns(DatasetDef d) {
        Map<String, Object> cols = new LinkedHashMap<>();
        for (DatasetDef.Col c : d.columns().values()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("type", c.type());
            if (c.path() != null) {
                cm.put("path", c.path());
            }
            cm.put("role", c.role());
            if (c.references() != null) {
                cm.put("references", c.references());
            }
            if (c.meaning() != null) {
                cm.put("meaning", c.meaning());
            }
            if (c.unitCurrency() != null) {
                cm.put("unit", Map.of("currency", c.unitCurrency()));
            }
            if (c.aggregate() != null) {
                cm.put("additive", "true".equals(c.additive()));
                cm.put("aggregate", c.aggregate());
            }
            cm.put("id", c.id());
            cols.put(c.name(), cm);
        }
        return cols;
    }

    private static String write(Map<String, Object> m) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()
                    .disable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.MINIMIZE_QUOTES)).writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

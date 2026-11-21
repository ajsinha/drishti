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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.search.SearchProperties;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.engine.search.form.Criterion;
import com.ash.drishti.engine.search.form.FieldType;
import com.ash.drishti.engine.search.form.FormCompiler;
import com.ash.drishti.engine.search.form.FormField;
import com.ash.drishti.engine.search.form.FormInference;
import com.ash.drishti.engine.search.form.FormSpec;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.rachana.about.GlossaryEntry;
import com.ash.rachana.model.StripItem;
import com.ash.rachana.model.Sutra;
import com.ash.drishti.server.design.FormDefinitions;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Query by form ({@code FORM trading <GO>}): the form of a kind, declared in its pack's {@code form:} section or inferred, and
 * its criteria compiled to the search grammar. The compiled command is run by the ordinary search, so masks, kinds per role, packs
 * per user and sample-pack visibility all apply; this controller only describes the form and writes the command.
 */
@RestController
@RequestMapping("/api/v1/forms")
public class FormController {

    private final PackRegistry registry;
    private final PackAccess access;
    private final Entitlements entitlements;
    private final Mnemonics mnemonics;
    private final SourceRouter router;
    private final StructuredSearch search;
    private final SutraRegistry sutras;
    private final AboutCatalog about;
    private final BusinessDates dates;
    private final FormCompiler compiler;
    private final FormInference inference;
    private final SearchProperties searchProps;
    private final FormDefinitions definitions;
    private final int sampleRows;
    /** Rows read to find the values of a dropdown: the first {@code sampleRows} of a kind can all be one product group (rates before FX), so the dropdown looked narrower than the data. */
    private final int enumScanRows;

    public FormController(PackRegistry registry, PackAccess access, Entitlements entitlements, Mnemonics mnemonics, SourceRouter router,
            StructuredSearch search, SearchProperties searchProps, FormDefinitions definitions, SutraRegistry sutras, AboutCatalog about, BusinessDates dates,
            @Value("${drishti.search.form.max-values:500}") int maxValues, @Value("${drishti.search.form.max-inferred-fields:14}") int maxInferred,
            @Value("${drishti.search.form.enum-max:12}") int enumMax, @Value("${drishti.search.form.sample-rows:200}") int sampleRows,
            @Value("${drishti.search.form.enum-scan-rows:1000}") int enumScanRows) {
        this.registry = registry;
        this.access = access;
        this.entitlements = entitlements;
        this.mnemonics = mnemonics;
        this.router = router;
        this.search = search;
        this.searchProps = searchProps;
        this.definitions = definitions;
        this.sutras = sutras;
        this.about = about;
        this.dates = dates;
        this.compiler = new FormCompiler(maxValues);
        this.inference = new FormInference(maxInferred, enumMax);
        this.sampleRows = Math.max(20, Math.min(1000, sampleRows));
        this.enumScanRows = Math.max(this.sampleRows, Math.min(SearchQuery.MAX_LIMIT, enumScanRows));
    }

    /**
     * The form of a pack's kind ({@code ?kind=} picks one; the first the caller may open otherwise), with every kind the caller may
     * open so the console can offer a selector. Fields are marked {@code indexed} when the kind's source keeps them as columns.
     * {@code ?kindsOnly=true}: the pack and the kinds only, no form (the forms home lists every pack's kinds and reads no data: building
     * a form reads the kind's rows for its dropdowns, seconds per pack on a cold server).
     */
    @GetMapping("/{pack}")
    public Map<String, Object> form(@PathVariable String pack, @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "false") boolean kindsOnly, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        Pack p = pack(principal, pack);
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (String k : p.kinds()) {
            if (entitlements.mayOpen(principal, k)) {
                String code = mnemonics.codeFor(k);
                kinds.add(Map.of("kind", k, "mnemonic", code == null ? k : code,
                        "label", code == null ? k : mnemonics.of(code).map(m -> m.label()).orElse(k), "declared", definitions.declared(p, k).isPresent()));
            }
        }
        if (kinds.isEmpty()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "no access to any kind of pack '" + p.name() + "'");
        }
        String chosen = kind == null || kind.isBlank() ? String.valueOf(kinds.get(0).get("kind")) : kind;
        if (kinds.stream().noneMatch(k -> k.get("kind").equals(chosen))) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "pack '" + p.name() + "' has no kind '" + chosen + "' you may open");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", p.name());
        out.put("code", p.code());
        out.put("title", p.title());
        out.put("kinds", kinds);
        if (kindsOnly) {
            return out;
        }
        FormSpec spec = specOf(p, chosen, asOf, principal);
        out.put("kind", chosen);
        out.put("mnemonic", mnemonics.codeFor(chosen) == null ? chosen : mnemonics.codeFor(chosen));
        Map<String, Object> form = spec.toMap();
        java.util.Set<String> columnar = router.columnar(chosen);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) form.get("fields");
        fields.forEach(f -> f.put("indexed", columnar.contains(String.valueOf(f.get("path")))));
        out.put("form", form);
        return out;
    }

    /**
     * Compiles criteria to the search command: {@code {criteria: [{path, op, value?, value2?, values?, case?}], orderBy?, descending?, limit?}}
     * answers {@code {command}}; a criterion the form cannot take, an unreadable value or a refused regular expression is a 400 saying which.
     */
    @PostMapping("/{pack}/{kind}/compile")
    public Map<String, Object> compile(@PathVariable String pack, @PathVariable String kind, @RequestBody Map<String, Object> body, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        Pack p = pack(principal, pack);
        if (!p.kinds().contains(kind)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "pack '" + p.name() + "' has no kind '" + kind + "'");
        }
        entitlements.requireOpen(principal, kind);
        FormSpec spec = specOf(p, kind, asOf, principal);
        List<Criterion> criteria = new ArrayList<>();
        if (body.get("criteria") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    criteria.add(Criterion.of(m));
                }
            }
        }
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 0;
        String code = mnemonics.codeFor(kind);
        String command = compiler.compile(code == null ? kind : code, spec, criteria, body.get("orderBy") == null ? null : String.valueOf(body.get("orderBy")),
                Boolean.TRUE.equals(body.get("descending")), limit, dates.resolve(asOf).businessDate(), dates.calendar());
        SearchQuery.pick(command);                                     // it must read back as a search
        return Map.of("command", command);
    }

    private Pack pack(Principal principal, String text) {
        return access.byCodeOrName(principal.user(), text).flatMap(n -> registry.packs().stream().filter(p -> p.name().equals(n)).findFirst())
                .filter(p -> access.sampleVisible(principal.user(), p.name()))
                .orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "no pack '" + text + "' is switched on for you"));
    }

    /**
     * The pack's declared form, else one inferred; enum fields without values get the values found in the data. Kept per data version
     * (a load makes a new one) and per whether the caller sees masks: building it reads a thousand rows, 0.45 s on a million trades a
     * day, and the console asks twice a search (the form, then its criteria compiled), so 50 users searching waited 5 s.
     */
    private FormSpec specOf(Pack p, String kind, AsOf asOf, Principal principal) {
        String version = router.dataVersion(kind, asOf).orElse(null);
        if (version == null) {
            return buildSpec(p, kind, asOf, principal);
        }
        String key = p.name() + "|" + kind + "|" + (asOf.live() ? "live" : asOf.businessDate() + "@" + asOf.knownAt()) + "|" + version + "|"
                + entitlements.masks(principal);
        return specs.get(key, k -> buildSpec(p, kind, asOf, principal));
    }

    /** Forms by pack, kind, date, data version and masking: see {@link #specOf}. */
    private final com.github.benmanes.caffeine.cache.Cache<String, FormSpec> specs = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(500).expireAfterAccess(java.time.Duration.ofHours(12)).build();

    private FormSpec buildSpec(Pack p, String kind, AsOf asOf, Principal principal) {
        Optional<FormSpec> declared = definitions.declared(p, kind);
        // the first rows, read only when needed (an inferred form, a dropdown no column and no glossary fills): a declared form whose
        // dropdowns come from the columns reads no documents (reading a thousand trades one by one made FORM trading 15 s cold)
        List<Map<String, List<Object>>> read = new ArrayList<>(1);
        java.util.function.Supplier<Map<String, List<Object>>> samples = () -> {
            if (read.isEmpty()) {
                read.add(sample(kind, asOf, principal));
            }
            return read.get(0);
        };
        FormSpec spec = declared.orElseGet(() -> infer(kind, samples.get()));
        // the first documents whole, read once for every dropdown that neither a column, a glossary nor the samples fill: at most
        // enum-scan-rows documents, never the whole kind (a search "where FIELD != ''" per dropdown read every trade, one by one)
        List<List<Map<String, Object>>> docs = new ArrayList<>(1);
        java.util.function.Supplier<List<Map<String, Object>>> first = () -> {
            if (docs.isEmpty()) {
                docs.add(firstDocuments(kind, asOf, principal));
            }
            return docs.get(0);
        };
        List<FormField> fields = new ArrayList<>();
        for (FormField f : spec.fields()) {
            fields.add(f.type() == FieldType.ENUM && f.values().isEmpty() ? withValues(f, kind, samples, first, asOf, principal) : f);
        }
        return new FormSpec(kind, spec.calendar(), fields, spec.inferred());
    }

    private FormField withValues(FormField f, String kind, java.util.function.Supplier<Map<String, List<Object>>> samples,
            java.util.function.Supplier<List<Map<String, Object>>> first, AsOf asOf, Principal principal) {
        TreeSet<String> found = new TreeSet<>();
        about.forKind(kind).map(a -> a.glossary().get(f.path())).map(GlossaryEntry::values).ifPresent(v -> found.addAll(v.keySet()));
        if (found.isEmpty() && !entitlements.masked(principal, f.path())) {   // the whole column, every connector of a union
            found.addAll(distinctOf(kind, f.path(), asOf));
        }
        if (found.isEmpty()) {                                        // a source that is not columnar: the sampled rows
            samples.get().getOrDefault(f.path(), List.of()).stream().filter(v -> v != null && !String.valueOf(v).isBlank()).forEach(v -> found.add(String.valueOf(v)));
        }
        if (found.isEmpty()) {                                        // not one of the key columns: read it from the first documents
            first.get().forEach(d -> Optional.ofNullable(at(d, f.path())).filter(v -> !String.valueOf(v).isBlank()).ifPresent(v -> found.add(String.valueOf(v))));
        }
        return f.withValues(new ArrayList<>(found), f.depends().isEmpty() ? Map.of() : parents(f, kind, first, asOf, principal));
    }

    /**
     * For a dependent enum (a product sub type that {@code depends} on the product type): each of its values with the values of the field it
     * depends on that it occurs with, read from the data (every connector serving the kind), so the form offers only the sub types of the
     * product types chosen. Empty when the data cannot be read: then every value is offered.
     */
    private Map<String, List<String>> parents(FormField f, String kind, java.util.function.Supplier<List<Map<String, Object>>> first, AsOf asOf,
            Principal principal) {
        boolean open = !entitlements.masked(principal, f.path()) && !entitlements.masked(principal, f.depends());
        String version = open ? router.dataVersion(kind, asOf).orElse(null) : null;   // cached per data version for fields the caller sees
        String key = kind + "|" + f.path() + "<" + f.depends() + "|" + (asOf.live() ? "live" : asOf.businessDate() + "@" + asOf.knownAt()) + "|" + version;
        if (version != null) {
            Map<String, List<String>> cached = parentValues.getIfPresent(key);
            if (cached != null) {
                return cached;
            }
        }
        Map<String, List<String>> computed = computeParents(f, kind, first, asOf, principal);
        if (version != null && !computed.isEmpty()) {
            parentValues.put(key, computed);
        }
        return computed;
    }

    private final com.github.benmanes.caffeine.cache.Cache<String, Map<String, List<String>>> parentValues = com.github.benmanes.caffeine.cache.Caffeine
            .newBuilder().maximumSize(2_000).expireAfterAccess(java.time.Duration.ofHours(12)).build();

    private Map<String, List<String>> computeParents(FormField f, String kind, java.util.function.Supplier<List<Map<String, Object>>> first, AsOf asOf,
            Principal principal) {
        Map<String, java.util.TreeSet<String>> by = new java.util.TreeMap<>();
        boolean[] whole = {false};                                    // every source answered as columns: no documents to read
        if (!entitlements.masked(principal, f.path()) && !entitlements.masked(principal, f.depends())) {
            columnValues(kind, List.of(f.path(), f.depends()), asOf).ifPresent(c -> {
                whole[0] = true;
                for (int i = 0; i < c.size(); i++) {
                    Object child = c.value(f.path(), i);
                    Object parent = c.value(f.depends(), i);
                    if (child != null && parent != null && !String.valueOf(child).isBlank() && !String.valueOf(parent).isBlank()) {
                        by.computeIfAbsent(String.valueOf(child), x -> new java.util.TreeSet<>()).add(String.valueOf(parent));
                    }
                }
            });
        }
        if (!whole[0]) {                                              // a source that is not columnar: the first documents
            for (Map<String, Object> d : first.get()) {
                Object child = at(d, f.path());
                Object parent = at(d, f.depends());
                if (child != null && parent != null && !String.valueOf(child).isBlank() && !String.valueOf(parent).isBlank()) {
                    by.computeIfAbsent(String.valueOf(child), x -> new java.util.TreeSet<>()).add(String.valueOf(parent));
                }
            }
        }
        if (by.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        by.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    /** Columns of the kind as the sources hold them (all the rows, every connector of a union); empty when a source is not columnar. */
    /**
     * The distinct values of a column of the kind on the date, every connector of a union: read once per data version (a load makes
     * a new one), not on every form request (a 1.16 million row column took 1.2 s each time). Only for fields the caller may see.
     */
    private List<String> distinctOf(String kind, String path, AsOf asOf) {
        String version = router.dataVersion(kind, asOf).orElse(null);
        String key = kind + "|" + path + "|" + (asOf.live() ? "live" : asOf.businessDate() + "@" + asOf.knownAt()) + "|" + version;
        List<String> cached = version == null ? null : distinctValues.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        TreeSet<String> found = new TreeSet<>();
        columnValues(kind, List.of(path), asOf).ifPresent(c -> {
            for (int i = 0; i < c.size(); i++) {
                Object v = c.value(path, i);
                if (v != null && !String.valueOf(v).isBlank()) {
                    found.add(String.valueOf(v));
                }
            }
        });
        List<String> out = List.copyOf(found);
        if (version != null && !out.isEmpty()) {
            distinctValues.put(key, out);
        }
        return out;
    }

    /** Distinct column values by kind, path, date and data version: see {@link #distinctOf}. */
    private final com.github.benmanes.caffeine.cache.Cache<String, List<String>> distinctValues = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumWeight(2_000_000).weigher((String k, List<String> v) -> 1 + v.size()).expireAfterAccess(java.time.Duration.ofHours(12)).build();

    private Optional<com.ash.drishti.api.ColumnSet> columnValues(String kind, List<String> paths, AsOf asOf) {
        try {
            return router.columns(kind, paths, asOf, java.time.Duration.ofSeconds(10));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The first {@code enumScanRows} documents of the kind as the caller sees them (masks applied), each as a map; empty when none can be read. */
    private List<Map<String, Object>> firstDocuments(String kind, AsOf asOf, Principal principal) {
        try {
            var hits = router.list(kind, "", enumScanRows, java.time.Duration.ofSeconds(10), asOf, new com.ash.drishti.engine.source.SourceFailures()).hits();
            List<com.ash.drishti.api.EntityRef> refs = hits.stream().map(h -> h.ref()).distinct().limit(enumScanRows).toList();
            List<Map<String, Object>> out = new ArrayList<>();
            router.fetchAll(refs, java.time.Duration.ofSeconds(10), asOf).values().forEach(d -> {
                if (d != null && !d.deleted() && entitlements.redact(principal, d.data()).unwrap() instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> doc = (Map<String, Object>) m;
                    out.add(doc);
                }
            });
            return out;
        } catch (RuntimeException e) {
            return List.of();                                         // no values: the person types one
        }
    }

    /** The value at a dotted path of a document, or null. */
    private static Object at(Map<String, Object> doc, String path) {
        Object cur = doc;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(part);
        }
        return cur;
    }

    private FormSpec infer(String kind, Map<String, List<Object>> samples) {
        List<String> where = new ArrayList<>();
        List<String> strip = new ArrayList<>();
        for (Sutra s : sutras.forKind(kind)) {
            if (s.match() != null && s.match().where() != null) {
                where.add(s.match().where());
            }
            for (StripItem item : s.strip()) {
                strip.add(item.bind());
            }
        }
        var glossary = about.forKind(kind).map(a -> a.glossary()).orElse(Map.of());
        return inference.infer(kind, searchColumns(kind), where, strip, path -> {
            GlossaryEntry e = glossary.get(path);
            return e == null ? null : e.term();
        }, samples);
    }

    private List<String> searchColumns(String kind) {
        return searchProps.columnsOf(kind);
    }

    /** The first rows' values of the kind's search columns, by path, to read types from. */
    private Map<String, List<Object>> sample(String kind, AsOf asOf, Principal principal) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        try {
            String code = mnemonics.codeFor(kind);
            StructuredSearch.Result r = search.run(SearchQuery.parse((code == null ? kind : code) + " limit " + enumScanRows), asOf,
                    data -> entitlements.redact(principal, data));
            for (StructuredSearch.Row row : r.rows()) {
                row.values().forEach((k, v) -> out.computeIfAbsent(k.startsWith("$.") ? k.substring(2) : k, x -> new ArrayList<>()).add(v));
            }
        } catch (RuntimeException e) {
            // no sample: types come from names
        }
        return out;
    }
}

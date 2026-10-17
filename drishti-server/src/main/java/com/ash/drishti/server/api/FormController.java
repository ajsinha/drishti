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
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
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
import com.ash.drishti.rachana.model.StripItem;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.design.FormDefinitions;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
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
     */
    @GetMapping("/{pack}")
    public Map<String, Object> form(@PathVariable String pack, @RequestParam(required = false) String kind, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
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
        FormSpec spec = specOf(p, chosen, asOf, principal);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", p.name());
        out.put("code", p.code());
        out.put("title", p.title());
        out.put("kinds", kinds);
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

    /** The pack's declared form, else one inferred; enum fields without values get the values found in the data. */
    private FormSpec specOf(Pack p, String kind, AsOf asOf, Principal principal) {
        Optional<FormSpec> declared = definitions.declared(p, kind);
        Map<String, List<Object>> samples = sample(kind, asOf, principal);
        FormSpec spec = declared.orElseGet(() -> infer(kind, samples));
        List<FormField> fields = new ArrayList<>();
        for (FormField f : spec.fields()) {
            fields.add(f.type() == FieldType.ENUM && f.values().isEmpty() ? withValues(f, kind, samples, asOf, principal) : f);
        }
        return new FormSpec(kind, spec.calendar(), fields, spec.inferred());
    }

    private FormField withValues(FormField f, String kind, Map<String, List<Object>> samples, AsOf asOf, Principal principal) {
        TreeSet<String> found = new TreeSet<>();
        about.forKind(kind).map(a -> a.glossary().get(f.path())).map(GlossaryEntry::values).ifPresent(v -> found.addAll(v.keySet()));
        if (found.isEmpty()) {
            samples.getOrDefault(f.path(), List.of()).stream().filter(v -> v != null && !String.valueOf(v).isBlank()).forEach(v -> found.add(String.valueOf(v)));
        }
        if (found.isEmpty()) {                                        // not one of the key columns: read it from the data
            try {
                String code = mnemonics.codeFor(kind);
                StructuredSearch.Result r = search.run(SearchQuery.parse((code == null ? kind : code) + " where " + f.path() + " != '' limit " + enumScanRows), asOf,
                        data -> entitlements.redact(principal, data));
                r.rows().forEach(row -> Optional.ofNullable(row.values().get("$." + f.path())).ifPresent(v -> found.add(String.valueOf(v))));
            } catch (RuntimeException e) {
                // no values: the person types one
            }
        }
        return f.withValues(new ArrayList<>(found), f.depends().isEmpty() ? Map.of() : parents(f, kind, asOf, principal));
    }

    /**
     * For a dependent enum (a product sub type that {@code depends} on the product type): each of its values with the values of the field it
     * depends on that it occurs with, read from the data (every connector serving the kind), so the form offers only the sub types of the
     * product types chosen. Empty when the data cannot be read: then every value is offered.
     */
    private Map<String, List<String>> parents(FormField f, String kind, AsOf asOf, Principal principal) {
        Map<String, java.util.TreeSet<String>> by = new java.util.TreeMap<>();
        try {
            String code = mnemonics.codeFor(kind);
            StructuredSearch.Result r = search.run(SearchQuery.parse((code == null ? kind : code) + " where " + f.path() + " != '' and " + f.depends()
                    + " != '' limit " + enumScanRows), asOf, data -> entitlements.redact(principal, data));
            for (var row : r.rows()) {
                Object child = row.values().get("$." + f.path());
                Object parent = row.values().get("$." + f.depends());
                if (child != null && parent != null) {
                    by.computeIfAbsent(String.valueOf(child), x -> new java.util.TreeSet<>()).add(String.valueOf(parent));
                }
            }
        } catch (RuntimeException e) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        by.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
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

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
package com.ash.drishti.server.liveset;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.edit.EditRuntime;
import com.ash.drishti.server.edit.EditableFields;
import com.ash.drishti.server.modules.DomainFolders;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the live result sets (modules/trading/docs/guide.md section 7): the snapshot from the kind's bound stores in one pass (else the
 * search's matches, read one by one), the live side from the kind's {@code live:} binding, the condition from the search grammar, the
 * declarations from the modules' {@code blotters/} folders and {@code drishti.live-sets.dir} ({@code config/blotters}), and the edits
 * into the write-back when it is on.
 */
@Configuration
public class LiveSetConfiguration {

    @Bean(destroyMethod = "close")
    public LiveSets liveSets(SourceRouter router, StructuredSearch search, ObjectProvider<com.ash.drishti.server.api.SearchController> searches,
            com.ash.drishti.engine.search.SearchProperties searchProps, com.ash.drishti.engine.command.Mnemonics mnemonics,
            EditableFields fields, ObjectProvider<EditRuntime> edits,
            com.ash.drishti.server.security.Entitlements entitlements, ObjectProvider<com.ash.drishti.identity.AuditLog> auditLog,
            com.ash.drishti.rachana.format.Formats formats, com.ash.drishti.packs.PackRegistry packs, Environment env) {
        java.time.Duration storeBudget = java.time.Duration.ofSeconds(env.getProperty("drishti.live-sets.store-timeout-seconds", Long.class, 120L));
        LiveSets.Sources sources = new LiveSets.Sources() {
            @Override
            public java.util.Optional<StorePages.Store> store(String kind) {
                // a kind a lake keeps as columns (Delta, Iceberg, files) is paged from it; others are read whole (capped)
                return router.columnar(kind).isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(routerStore(router, kind, storeBudget));
            }

            @Override
            public java.util.Optional<java.util.List<String>> fields(String query) {
                try {
                    return java.util.Optional.of(SearchQuery.parse(query).documentPaths().stream().map(p -> p.startsWith("$.") ? p.substring(2) : p)
                            .distinct().toList());
                } catch (RuntimeException e) {
                    return java.util.Optional.empty();
                }
            }

            @Override
            public boolean snapshot(String kind, String query, Consumer<EntityDocument> sink) throws Exception {
                if (router.documents(kind, AsOf.LATEST, sink)) {
                    return true;
                }
                StructuredSearch.Result r = search.run(SearchQuery.parse(query != null ? query : kind + " limit " + SearchQuery.MAX_LIMIT), AsOf.LATEST, d -> d);
                for (StructuredSearch.Row row : r.rows()) {
                    EntityDocument d = router.fetch(row.ref(), AsOf.LATEST).get(30, TimeUnit.SECONDS);
                    if (d != null) {
                        sink.accept(d);
                    }
                }
                return true;
            }

            @Override
            public Subscription subscribe(String kind, Consumer<EntityDocument> listener) {
                return router.subscribeKind(kind, listener);
            }

            @Override
            public Predicate<DataNode> condition(String query) {
                return search.condition(SearchQuery.parse(query));
            }

            @Override
            public java.util.Optional<LiveSetSpec> defaultFor(String kind) {
                return defaultSpec(kind, searchProps.columnsOf(kind), router.columnar(kind), mnemonics.codeFor(kind));
            }

            @Override
            public String kindOf(String command) {
                return search.kindOf(SearchQuery.pick(command));
            }

            @Override
            @SuppressWarnings("unchecked")
            public java.util.Map<String, Object> search(String command, AsOf asOf, com.ash.drishti.kernel.api.security.Principal who,
                    java.util.function.UnaryOperator<DataNode> mask, Consumer<EntityDocument> sink) {
                // the search as its API answers it (masks, kinds, the edition, Drishti*net's nodes merged): its rows fill the set
                java.util.Map<String, Object> said = new java.util.LinkedHashMap<>(searches.getObject().search(command, asOf, who));
                Object rows = said.remove("rows");
                said.remove(com.ash.drishti.server.net.SearchScatter.MATCHED_IDS);
                if (rows instanceof java.util.List<?> l) {
                    for (Object o : l) {
                        if (o instanceof StructuredSearch.Row row) {
                            sink.accept(new EntityDocument(row.ref(), document(row), new com.ash.drishti.api.Provenance("search", 0L,
                                    java.time.Instant.now(), false, null)));
                        }
                    }
                }
                return said;
            }
        };
        LiveSets sets = new LiveSets(sources, Clock.systemUTC(), env.getProperty("drishti.live-sets.flush-ms", Long.class, 50L),
                env.getProperty("drishti.live-sets.window-capacity", Integer.class, 5_000));
        sets.defaultMaxRows(env.getProperty("drishti.live-sets.default-max-rows", Integer.class, 250_000));
        // who sees what: one reader per request, its audience everything that decides it (roles and embedding app, which also decide the
        // Drishti*net nodes a search reaches; the masks; administrators' view of the nodes); kind access is checked on every request
        sets.visibility((p, kind) -> {
            boolean admin = entitlements.isAdmin(p);
            boolean masks = entitlements.masks(p);
            return new LiveReader(p.securitySignature() + "|masks=" + masks + "|admin=" + admin, entitlements.redactor(p), LiveReader.Scope.ALL, admin);
        });
        sets.validator(spec -> problems(spec, formats::has, kind -> contractFields(packs, kind)));
        Predicate<String> on = DomainFolders.on(env);
        Path modules = Path.of(env.getProperty("drishti.live-sets.modules-dir", "modules"));
        Path site = Path.of(env.getProperty("drishti.live-sets.dir", "config/blotters"));
        sets.load(LiveSets.folders(modules, site, on), LiveSets::moduleOf);
        fields.addSource(sets::rules);
        EditRuntime rt = edits.getIfAvailable();
        if (rt != null) {
            sets.submit((p, body) -> rt.service().submit(p, body));
        }
        com.ash.drishti.identity.AuditLog a = auditLog.getIfAvailable();
        if (a != null) {
            sets.audit(a::record);
        }
        sets.access((p, spec) -> {
            // the site's configuration: the write-back on, this declaration's switch, a usable command for a source target
            if (rt == null || !rt.settings().enabled()) {
                return "editing is off on this server (edit.enabled in config/edit/edit.yaml)";
            }
            if (java.util.Arrays.stream(env.getProperty("drishti.live-sets.edits-off", "").split(",")).map(String::trim).anyMatch(spec.id()::equals)) {
                return "the site switched this blotter's edits off (drishti.live-sets.edits-off)";
            }
            com.ash.drishti.rachana.model.Form.Save save = spec.form().save();
            if ("source".equals(save.target()) && save.connector() != null) {
                java.util.Optional<String> why = rt.writes().unusable(save.connector(), save.command(), save.kind() != null ? save.kind() : spec.kind());
                if (why.isPresent()) {
                    return why.get();
                }
            }
            // the person's rights: the edit power, the kind, and at least one field their roles may change
            if (!entitlements.mayEdit(p)) {
                return p.user() + " does not hold the edit power";
            }
            if (!entitlements.mayOpen(p, spec.kind())) {
                return p.user() + " may not open " + spec.kind() + " records";
            }
            java.util.List<String> held = entitlements.formRoles(p);
            boolean any = spec.form().fields().stream().anyMatch(f -> spec.form().editableBy(f, held));
            return any ? null : "no field of this blotter is editable for " + p.user() + "'s roles";
        });
        return sets;
    }

    /** A search's row as a document: its columns' values at their paths ({@code $.A.B} is {@code {A: {B: v}}}), and its title. */
    @SuppressWarnings("unchecked")
    static DataNode document(StructuredSearch.Row row) {
        java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
        row.values().forEach((path, v) -> {
            String[] parts = (path.startsWith("$.") ? path.substring(2) : path).split("\\.");
            java.util.Map<String, Object> at = doc;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = at.computeIfAbsent(parts[i], k -> new java.util.LinkedHashMap<String, Object>());
                if (!(next instanceof java.util.Map<?, ?>)) {
                    return;                                   // a value and a deeper path under one name: the value stays
                }
                at = (java.util.Map<String, Object>) next;
            }
            at.putIfAbsent(parts[parts.length - 1], v);
        });
        if (row.title() != null && !doc.containsKey(TITLE)) {
            doc.put(TITLE, row.title());
        }
        return DataNode.of(doc);
    }

    /**
     * A kind's default declaration (a bare mnemonic): its search columns (the pack's key fields), else its promoted columns, up to twelve;
     * keyed by the entity id; read-only; titled by its mnemonic. Empty when there is no column to show.
     */
    static java.util.Optional<LiveSetSpec> defaultSpec(String kind, java.util.List<String> keyColumns, java.util.Set<String> promoted, String code) {
        java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<>();
        (keyColumns == null ? java.util.List.<String>of() : keyColumns).forEach(c -> fields.add(c.startsWith("$.") ? c.substring(2) : c));
        if (fields.isEmpty() && promoted != null) {
            promoted.stream().filter(c -> !c.equals("id") && !c.equals("doc") && !c.equals("business_date")).sorted().forEach(fields::add);
        }
        java.util.List<java.util.Map<String, Object>> columns = fields.stream().limit(12)
                .map(f -> java.util.Map.<String, Object>of("field", f, "label", com.ash.drishti.server.api.HistoryController.label(f))).toList();
        if (columns.isEmpty()) {
            return java.util.Optional.empty();
        }
        String id = ("kind-" + kind.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-"));
        id = id.length() > 63 ? id.substring(0, 63) : id;
        return java.util.Optional.of(new LiveSetSpec(id, code == null ? kind : code, kind, null, null, null, null, null, columns, java.util.List.of(),
                java.util.List.of(), null, false, java.util.List.of(), null, null, false, null));
    }

    /**
     * A declaration's problems beyond its shape (modules/trading/docs/guide.md section 6): every named format is in formats.yaml (the
     * bundled one and the packs'), every field it names is in the kind's data contract (when the kind has one), and every editable field
     * has a write path (it binds a record field the write-back may set: not the key, nor the version, who or when, which it stamps).
     */
    static java.util.List<String> problems(LiveSetSpec spec, Predicate<String> formatKnown,
            java.util.function.Function<String, java.util.Optional<java.util.Set<String>>> contract) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String f : spec.formats()) {
            if (!formatKnown.test(f)) {
                out.add("format " + f + " is not a named format of formats.yaml");
            }
        }
        java.util.Optional<java.util.Set<String>> known = contract.apply(spec.kind());
        if (known.isPresent()) {
            for (String f : spec.namedFields()) {
                String top = f.contains(".") ? f.substring(0, f.indexOf('.')) : f;
                if (!known.get().contains(top)) {
                    out.add("field " + f + " is not in the " + spec.kind() + " data contract");
                }
            }
        }
        if (spec.form() != null) {
            java.util.Set<String> stamped = new java.util.HashSet<>();
            for (String s : new String[] {spec.key(), spec.version(), spec.changedBy(), spec.changedAt()}) {
                if (s != null) {
                    stamped.add(s);
                }
            }
            for (com.ash.drishti.rachana.model.Form.Field f : spec.form().fields()) {
                String bind = f.recordField();
                if (bind == null || bind.isBlank()) {
                    out.add("edit field " + f.id() + " binds no record field: it has no write path");
                } else if (!f.readonly() && stamped.contains(bind)) {
                    out.add("edit field " + f.id() + " binds " + bind + ", which names or stamps the record (key, version, who, when): it has no write"
                            + " path; mark it readonly: true");
                }
            }
        }
        return out;
    }

    /**
     * The top-level fields of a kind's data contract ({@code contracts/<kind>.contract.yaml} of the pack that owns it: {@code fields} and
     * {@code nested}); empty when no loaded pack has a contract of the kind (nothing to check against).
     */
    static java.util.Optional<java.util.Set<String>> contractFields(com.ash.drishti.packs.PackRegistry packs, String kind) {
        for (com.ash.drishti.packs.Pack p : packs.packs()) {
            if (!p.kinds().contains(kind) || p.dir() == null) {
                continue;
            }
            Path f = p.dir().resolve("contracts").resolve(kind + ".contract.yaml");
            if (java.nio.file.Files.isRegularFile(f)) {
                return contractFields(f);
            }
        }
        return java.util.Optional.empty();
    }

    /** The top-level fields of one contract file ({@code fields} and {@code nested}); empty when it cannot be read or names none. */
    static java.util.Optional<java.util.Set<String>> contractFields(Path f) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                    .readTree(f.toFile());
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            n.path("fields").forEach(x -> out.add(x.path("name").asText()));
            n.path("nested").fieldNames().forEachRemaining(name -> out.add(name.replace("[]", "")));
            return out.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(out);
        } catch (java.io.IOException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * The kind's durable store as a set pages it, through the bindings: its records' fields as the lake's columns when it keeps them all
     * as columns, else its documents read once; pages fetched by id; the version the router gives (a load moves it).
     */
    static StorePages.Store routerStore(SourceRouter router, String kind, java.time.Duration budget) {
        return new StorePages.Store() {
            @Override
            public String version() {
                return router.dataVersion(kind, AsOf.LATEST).orElse("");
            }

            @Override
            public boolean project(java.util.List<String> fields, StorePages.Projection sink) throws Exception {
                if (router.columnar(kind).containsAll(fields)) {
                    java.util.Optional<com.ash.drishti.api.ColumnSet> cs = router.columns(kind, fields, AsOf.LATEST, budget);
                    if (cs.isPresent()) {
                        com.ash.drishti.api.ColumnSet c = cs.get();
                        for (int i = 0; i < c.size(); i++) {
                            Object[] v = new Object[fields.size()];
                            for (int j = 0; j < v.length; j++) {
                                v[j] = c.value(fields.get(j), i);
                            }
                            sink.row(c.ids()[i], v);
                        }
                        return true;
                    }
                }
                return router.documents(kind, AsOf.LATEST, d -> {
                    if (!d.deleted()) {
                        Object[] v = new Object[fields.size()];
                        for (int j = 0; j < v.length; j++) {
                            v[j] = d.data().get(fields.get(j)).unwrap();
                        }
                        sink.row(d.ref().id(), v);
                    }
                });
            }

            @Override
            public java.util.Map<String, DataNode> fetch(java.util.Collection<String> ids) {
                java.util.List<com.ash.drishti.api.EntityRef> refs = ids.stream().map(id -> com.ash.drishti.api.EntityRef.of(kind, id)).toList();
                java.util.Map<String, DataNode> out = new java.util.HashMap<>();
                router.fetchAll(refs, budget, AsOf.LATEST).forEach((ref, d) -> {
                    if (d != null && !d.deleted()) {
                        out.put(ref.id(), d.data());
                    }
                });
                return out;
            }
        };
    }

    /** Where a search's row keeps its title in the set's document. */
    public static final String TITLE = "_title";
}

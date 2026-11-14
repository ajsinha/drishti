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
            com.ash.drishti.server.security.Entitlements entitlements, ObjectProvider<com.ash.drishti.identity.AuditLog> auditLog, Environment env) {
        LiveSets.Sources sources = new LiveSets.Sources() {
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

    /** Where a search's row keeps its title in the set's document. */
    public static final String TITLE = "_title";
}

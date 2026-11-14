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
    public LiveSets liveSets(SourceRouter router, StructuredSearch search, EditableFields fields, ObjectProvider<EditRuntime> edits,
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
        };
        LiveSets sets = new LiveSets(sources, Clock.systemUTC(), env.getProperty("drishti.live-sets.flush-ms", Long.class, 50L),
                env.getProperty("drishti.live-sets.window-capacity", Integer.class, 5_000));
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
}

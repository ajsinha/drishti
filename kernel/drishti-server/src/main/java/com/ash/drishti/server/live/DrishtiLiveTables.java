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
package com.ash.drishti.server.live;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.edit.EditRuntime;
import com.ash.drishti.server.edit.EditableFields;
import com.ash.drishti.server.security.Entitlements;
import com.ash.rachana.SutraException;
import com.ash.rachana.live.LiveTables;
import com.ash.rachana.live.spi.LiveStream;
import com.ash.rachana.live.spi.SnapshotSource;
import com.ash.rachana.model.Form;
import com.ash.rachana.table.LiveTableSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Drishti's live tables (LIVE_TABLES 11, the switch-over): Rachana's engine ({@code rachana-live}) with Drishti's adapter: every kind's
 * snapshot through the bindings ({@link KindSnapshot}) and its {@code live:} binding as the stream ({@link KindStream}); the
 * {@link DrishtiAuthoriser} over the entitlements (row rules, masks, the edit power, the site's switches); the audit log as the
 * {@code AuditSink}; the write-back as the {@code ChangeSink} ({@link DrishtiChangeSink}, Rachana's outbox not installed). Declarations are
 * each module's {@code blotters/*.live.yaml} and the site's {@code config/blotters}, read with Drishti's profile keys (kind, query, links,
 * edit roles, save, editable-by) and checked against the formats and the kinds' data contracts; a kind without a declaration has a default
 * one from its search or promoted columns ({@code kind-<kind>}), declared in code on first use. Mounted on the Data Protocol by
 * {@link LiveTablesController}. A FORM's result and the search page's are queries on tables declared in code ({@link FormResults},
 * their sources in {@link LiveSources}). The one engine of live tables, blotters and FORM results.
 */
@Configuration(proxyBeanMethods = false)
public class DrishtiLiveTables {

    private static final Logger LOG = LoggerFactory.getLogger(DrishtiLiveTables.class);

    /** Drishti's profile keys of a declaration. */
    public static final Set<String> PROFILE_KEYS = LiveTableSpec.PROFILE_KEYS;

    /** The name of a kind's default declaration. */
    public static String defaultName(String kind) {
        String id = "kind-" + kind.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        return id.length() > 63 ? id.substring(0, 63) : id;
    }

    @Bean(destroyMethod = "close")
    public LiveTables liveTables(SourceRouter router, com.ash.drishti.engine.search.StructuredSearch search,
            com.ash.drishti.engine.search.SearchProperties searchProps, com.ash.drishti.engine.command.Mnemonics mnemonics, EditableFields fields,
            ObjectProvider<EditRuntime> edits, Entitlements entitlements, ObjectProvider<com.ash.drishti.identity.AuditLog> auditLog,
            com.ash.rachana.format.Formats formats, com.ash.drishti.packs.PackRegistry packs,
            ObjectProvider<com.ash.drishti.server.switches.CommandCatalog> catalog, LiveSources sources, KindBindings bindings, Environment env) {
        LiveTables.Options o = LiveTables.Options.DEFAULTS.withFlushMs(env.getProperty("drishti.live-sets.flush-ms", Long.class, 50L))
                .withWindowCapacity(env.getProperty("drishti.live-sets.window-capacity", Integer.class, 5_000))
                .withMaxSnapshotRows(env.getProperty("drishti.live-sets.default-max-rows", Integer.class, 250_000));
        Map<String, LiveTableSpec> specs = new java.util.concurrent.ConcurrentHashMap<>();
        LiveTables lt = new LiveTables(new LiveTables.Resolver() {
            @Override
            public SnapshotSource snapshot(String name) {
                // a table declared in code (a FORM's result) reads its kind as its source says: the day, whole or by columns, the members
                return sources.get(name).<SnapshotSource>map(x -> new KindSnapshot(x.kind(), bindings, List.of(com.ash.drishti.engine.search.SearchFilter.KEY),
                        x.at(), x.whole(), x.peers(), null)).orElseGet(() -> new KindSnapshot(name, bindings, keyOf(specs, name)));
            }

            @Override
            public LiveStream stream(String name) {
                return sources.get(name).<LiveStream>map(x -> new KindStream(x.kind(), bindings, List.of(com.ash.drishti.engine.search.SearchFilter.KEY),
                        x.peerStream())).orElseGet(() -> new KindStream(name, bindings, keyOf(specs, name)));
            }
        }, o, Clock.systemUTC());
        EditRuntime rt = edits.getIfAvailable();
        Set<String> off = new java.util.HashSet<>();
        for (String s : env.getProperty("drishti.live-sets.edits-off", "").split(",")) {
            if (!s.isBlank()) {
                off.add(s.strip());
            }
        }
        DrishtiAuthoriser authoriser = new DrishtiAuthoriser(specs::get, rights(entitlements, rt, off));
        lt.authoriser(authoriser);
        AUTHORISERS.put(lt, authoriser);
        com.ash.drishti.identity.AuditLog a = auditLog.getIfAvailable();
        if (a != null) {
            lt.audit(e -> a.record(String.valueOf(e.get("caller") instanceof com.ash.drishti.kernel.api.security.Principal p ? p.user() : e.get("caller")),
                    "live-" + e.get("event"), e.get("table") + ":" + e.get("key"), e.get("reason") == null ? "" : String.valueOf(e.get("reason"))));
        }
        if (rt != null) {
            lt.sink(new DrishtiChangeSink(specs::get, (p, body) -> rt.service().submit(p, body)));
        }
        // the declarations: each module's blotters folder, then the site's (a site file of the same name replaces the module's)
        Predicate<String> on = com.ash.drishti.server.modules.DomainFolders.on(env);
        Path site = Path.of(env.getProperty("drishti.live-sets.dir", Path.of(env.getRequiredProperty("drishti.config.dir"), "blotters").toString()));
        Map<Path, String> folders = folders(com.ash.drishti.packs.distribution.Distributions.homes(env), site, on);
        for (Path dir : folders.keySet()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(LiveTableSpec.SUFFIX)).sorted().toList()) {
                    declare(lt, specs, f, formats::has, kind -> contractFields(packs, kind));
                }
            } catch (IOException e) {
                LOG.warn("live tables: {} could not be listed: {}", dir, e.getMessage());
            }
        }
        fields.addSource(() -> rules(specs.values()));
        DefaultTables defaults = new DefaultTables(lt, specs, kind -> {
            List<String> narrow = catalog.getIfAvailable() == null ? List.of()
                    : catalog.getObject().listRule(kind).map(r -> r.requires()).orElse(List.of());
            Optional<LiveTableSpec> own = defaultSpec(kind, searchProps.columnsOf(kind), router.columnar(kind), mnemonics.codeFor(kind), "id", narrow);
            return own.isPresent() ? own : defaultSpec(kind, contractColumns(packs, kind), Set.of(), mnemonics.codeFor(kind), "id", narrow);
        });
        DEFAULTS.put(lt, defaults);
        SPECS.put(lt, specs);
        return lt;
    }

    /** The sources of the tables declared in code (a FORM's result), by name. */
    @Bean
    public LiveSources liveSources() {
        return new LiveSources();
    }

    /** The bindings of every kind through the source router, for the tables declared in code. */
    @Bean
    public KindBindings kindBindings(SourceRouter router, com.ash.drishti.engine.search.StructuredSearch search, Environment env) {
        return routerBindings(router, search, Duration.ofSeconds(env.getProperty("drishti.live-sets.store-timeout-seconds", Long.class, 120L)));
    }

    /** The declarations one engine's authoriser and write-back know, by name. */
    static final Map<LiveTables, Map<String, LiveTableSpec>> SPECS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Declares a table made in code (a FORM's result): known to the authoriser and the write-back as a file's declaration is. */
    public static void declare(LiveTables lt, LiveTableSpec spec) {
        Map<String, LiveTableSpec> specs = SPECS.get(lt);
        if (specs != null) {
            specs.put(spec.name(), spec);
        }
        lt.declare(spec);
    }

    /** The authoriser of one engine. */
    static final Map<LiveTables, DrishtiAuthoriser> AUTHORISERS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Why a person may not edit a table (the site's switches, their rights), or null when they may or it cannot be told. */
    public static String readOnly(LiveTables lt, String name, com.ash.drishti.kernel.api.security.Principal p) {
        DrishtiAuthoriser a = AUTHORISERS.get(lt);
        if (a == null) {
            return null;
        }
        try {
            return a.view(p, name).readOnlyReason();
        } catch (com.ash.rachana.live.spi.Authoriser.Refusal e) {
            return e.getMessage();
        }
    }

    /** The kinds' default declarations of one engine (made on first use). */
    static final Map<LiveTables, DefaultTables> DEFAULTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** A kind's default declaration, declared on first use; empty when the kind has no column to show. */
    public static Optional<String> defaultTable(LiveTables lt, String kind) {
        DefaultTables d = DEFAULTS.get(lt);
        return d == null ? Optional.empty() : d.of(kind);
    }

    /** The declaration of a table, or null. */
    public static LiveTableSpec spec(LiveTables lt, String name) {
        return lt.specs().stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    record DefaultTables(LiveTables lt, Map<String, LiveTableSpec> specs, Function<String, Optional<LiveTableSpec>> make) {
        Optional<String> of(String kind) {
            String name = defaultName(kind);
            if (specs.containsKey(name)) {
                return Optional.of(name);
            }
            synchronized (this) {
                if (!specs.containsKey(name)) {
                    Optional<LiveTableSpec> s = make.apply(kind);
                    if (s.isEmpty()) {
                        return Optional.empty();
                    }
                    specs.put(name, s.get());
                    lt.declare(s.get());
                }
            }
            return Optional.of(name);
        }
    }

    static void declare(LiveTables lt, Map<String, LiveTableSpec> specs, Path f, Predicate<String> formats,
            Function<String, Optional<Set<String>>> contract) {
        try {
            LiveTableSpec s = LiveTableSpec.read(Files.readString(f), f.getFileName().toString(), PROFILE_KEYS, formats);
            List<String> more = problems(s, formats, contract);
            if (!more.isEmpty()) {
                LOG.warn("live tables: {} is set aside: {}", f, String.join("; ", more));
                ASIDE.put(f.getFileName().toString(), more);
                return;
            }
            specs.put(s.name(), s);
            lt.declare(s);
        } catch (SutraException e) {
            LOG.warn("live tables: {} is set aside: {}", f, e.problems());
            ASIDE.put(f.getFileName().toString(), e.problems().stream().map(String::valueOf).toList());
        } catch (IOException e) {
            LOG.warn("live tables: {} could not be read: {}", f, e.getMessage());
        }
    }

    /** The declarations set aside at load, by file (Admin lists them). */
    public static final Map<String, List<String>> ASIDE = new java.util.concurrent.ConcurrentHashMap<>();

    private static List<String> keyOf(Map<String, LiveTableSpec> specs, String kind) {
        for (LiveTableSpec s : specs.values()) {
            if (s.snapshot().equals(kind)) {
                return s.key();
            }
        }
        return List.of("id");
    }

    /** Each module's blotters folder (in the folder its boot gave it, when the module is on) and the site's folder last. */
    static Map<Path, String> folders(com.ash.drishti.api.resolution.ModuleHomes homes, Path site, Predicate<String> moduleOn) {
        Map<Path, String> out = new LinkedHashMap<>();
        for (com.ash.drishti.api.resolution.ModuleHomes.Home h : homes.homes().values()) {
            if (moduleOn.test(h.id()) && Files.isDirectory(h.dir().resolve("blotters"))) {
                out.put(h.dir().resolve("blotters"), h.id());
            }
        }
        if (site != null) {
            out.put(site, null);
        }
        return out;
    }

    /** What decides what a person sees: roles and embedding app, masks, administrator. */
    static String signature(Entitlements entitlements, com.ash.drishti.kernel.api.security.Principal p) {
        return p.securitySignature() + "|masks=" + entitlements.masks(p) + "|admin=" + entitlements.isAdmin(p);
    }

    /** Drishti's rights over a person: the entitlements, the write-back's settings and the site's switches. */
    static DrishtiAuthoriser.Rights rights(Entitlements entitlements, EditRuntime rt, Set<String> editsOff) {
        return new DrishtiAuthoriser.Rights() {
            @Override
            public boolean mayOpen(com.ash.drishti.kernel.api.security.Principal p, String kind) {
                return entitlements.mayOpen(p, kind);
            }

            @Override
            public String signature(com.ash.drishti.kernel.api.security.Principal p) {
                return DrishtiLiveTables.signature(entitlements, p);
            }

            @Override
            public DrishtiAuthoriser.RowRule rows(com.ash.drishti.kernel.api.security.Principal p, String kind) {
                return entitlements.rowRule(p, kind).map(r -> new DrishtiAuthoriser.RowRule(r.text(), row -> r.test(DataNode.of(row)))).orElse(null);
            }

            @Override
            @SuppressWarnings("unchecked")
            public UnaryOperator<Map<String, Object>> redact(com.ash.drishti.kernel.api.security.Principal p) {
                if (!entitlements.masks(p)) {
                    return null;
                }
                UnaryOperator<DataNode> r = entitlements.redactor(p);
                return row -> (Map<String, Object>) r.apply(DataNode.of(row)).unwrap();
            }

            @Override
            public boolean masked(com.ash.drishti.kernel.api.security.Principal p, String field) {
                return entitlements.masked(p, field);
            }

            @Override
            public String readOnly(com.ash.drishti.kernel.api.security.Principal p, LiveTableSpec spec) {
                if (rt == null || !rt.settings().enabled()) {
                    return "editing is off on this server (edit.enabled in config/edit/edit.yaml)";
                }
                if (editsOff.contains(spec.name())) {
                    return "the site switched this table's edits off (drishti.live-sets.edits-off)";
                }
                Form form = form(spec);
                if (form != null && "source".equals(form.save().target()) && form.save().connector() != null) {
                    Optional<String> why = rt.writes().unusable(form.save().connector(), form.save().command(),
                            form.save().kind() != null ? form.save().kind() : DrishtiAuthoriser.kind(spec));
                    if (why.isPresent()) {
                        return why.get();
                    }
                }
                if (!entitlements.mayEdit(p)) {
                    return p.user() + " does not hold the edit power";
                }
                if (!entitlements.mayOpen(p, DrishtiAuthoriser.kind(spec))) {
                    return p.user() + " may not open " + DrishtiAuthoriser.kind(spec) + " records";
                }
                return null;
            }

            @Override
            public List<String> formRoles(com.ash.drishti.kernel.api.security.Principal p) {
                return entitlements.formRoles(p);
            }
        };
    }

    /** The bindings of every kind through the source router. */
    static KindBindings routerBindings(SourceRouter router, com.ash.drishti.engine.search.StructuredSearch search, Duration budget) {
        return new KindBindings() {
            @Override
            public Optional<Store> store(String kind) {
                return router.columnar(kind).isEmpty() ? Optional.empty() : Optional.of(routerStore(router, kind, budget));
            }

            @Override
            public boolean documents(String kind, Consumer<com.ash.drishti.api.EntityDocument> sink) throws Exception {
                if (router.documents(kind, AsOf.LATEST, sink)) {
                    return true;
                }
                // a kind no reader lists whole: its records as its search finds them
                var r = search.run(com.ash.drishti.engine.search.SearchQuery.pick(kind + " limit " + com.ash.drishti.engine.search.SearchQuery.MAX_LIMIT),
                        AsOf.LATEST, d -> d);
                for (var row : r.rows()) {
                    var d = router.fetch(row.ref(), AsOf.LATEST).get(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (d != null) {
                        sink.accept(d);
                    }
                }
                return true;
            }

            @Override
            public Map<String, DataNode> fetch(String kind, Collection<String> ids) {
                return fetch(kind, ids, AsOf.LATEST);
            }

            @Override
            public Optional<Store> store(String kind, AsOf at) {
                return router.columnar(kind).isEmpty() ? Optional.empty() : Optional.of(routerStore(router, kind, budget, at));
            }

            @Override
            public boolean documents(String kind, AsOf at, Consumer<com.ash.drishti.api.EntityDocument> sink) throws Exception {
                return at == null || at.isLatest() ? documents(kind, sink) : router.documents(kind, at, sink);
            }

            @Override
            public Map<String, DataNode> fetch(String kind, Collection<String> ids, AsOf at) {
                Map<String, DataNode> out = new HashMap<>();
                router.fetchAll(ids.stream().map(id -> EntityRef.of(kind, id)).toList(), budget, at).forEach((ref, d) -> {
                    if (d != null && !d.deleted()) {
                        out.put(ref.id(), d.data());
                    }
                });
                return out;
            }

            @Override
            public AutoCloseable subscribe(String kind, Consumer<com.ash.drishti.api.EntityDocument> listener) {
                com.ash.drishti.api.Subscription s = router.subscribeKind(kind, listener);
                return s::close;
            }

            @Override
            public List<String> paused(String kind) {
                return router.livePaused(kind);
            }
        };
    }

    /** A kind's durable store as a table pages it: its lake columns projected, records fetched by id, the router's data version. */
    static KindBindings.Store routerStore(SourceRouter router, String kind, Duration budget) {
        return routerStore(router, kind, budget, AsOf.LATEST);
    }

    /** The same at a business date: the lake read as of that day (its time travel). */
    static KindBindings.Store routerStore(SourceRouter router, String kind, Duration budget, AsOf at) {
        return new KindBindings.Store() {
            @Override
            public String version() {
                return router.dataVersion(kind, at).orElse("");
            }

            @Override
            public boolean project(List<String> fields, KindBindings.Projection sink) throws Exception {
                Optional<com.ash.drishti.api.ColumnSet> cs = router.columns(kind, fields, at, budget);
                if (cs.isEmpty()) {
                    return false;
                }
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

            @Override
            public boolean column(String field) {
                return router.columnar(kind).contains(field);
            }

            @Override
            public Map<String, DataNode> fetch(Collection<String> ids) {
                Map<String, DataNode> out = new HashMap<>();
                router.fetchAll(ids.stream().map(id -> EntityRef.of(kind, id)).toList(), budget, at).forEach((ref, d) -> {
                    if (d != null && !d.deleted()) {
                        out.put(ref.id(), d.data());
                    }
                });
                return out;
            }
        };
    }

    // ---- declarations ------------------------------------------------------------------------------------------------

    /** The field Rachana adds to an edit form that names no key: the row is the table's key, not a form field. */
    static final String ROW_KEY = "row_key";

    /** The write-back form of a declaration's {@code edit:}, or null for a read-only table. */
    public static Form form(LiveTableSpec spec) {
        if (spec.edit() == null) {
            return null;
        }
        Map<String, Object> checked = new LinkedHashMap<>(spec.edit());   // as Rachana checks it: the row's key is the table's, not a field
        if (!checked.containsKey("key") && checked.get("fields") instanceof List<?> fl) {
            List<Object> withKey = new ArrayList<>(fl);
            withKey.add(Map.of("id", ROW_KEY, "type", "text", "readonly", true));
            checked.put("fields", withKey);
            checked.put("key", ROW_KEY);
        }
        return Form.fromOption(checked).orElse(null);
    }

    /** The write-back's rules for the editable fields of the declarations (the form {@code live:<table>}, panel {@code edit}). */
    public static List<EditableFields.Rule> rules(Collection<LiveTableSpec> specs) {
        List<EditableFields.Rule> out = new ArrayList<>();
        for (LiveTableSpec s : specs) {
            Form f = form(s);
            if (f == null) {
                continue;
            }
            String kind = DrishtiAuthoriser.kind(s);
            String saveKind = f.save() != null && f.save().kind() != null ? f.save().kind() : kind;
            for (Form.Field fd : f.fields()) {
                if (ROW_KEY.equals(fd.id()) || fd.recordField() == null || fd.recordField().isBlank()) {
                    continue;                                 // the row's key: the table names the record
                }
                out.add(new EditableFields.Rule(saveKind, kind, DrishtiChangeSink.formName(s.name()), DrishtiChangeSink.PANEL, null, f, fd));
            }
        }
        return out;
    }

    /**
     * A declaration's problems beyond what Rachana checks (docs/guides/LIVE_RESULTS_GUIDE.md section 6): every named format is known, every
     * column is in the kind's data contract (when the kind has one), a link is a path of this console, an editable table's form names where
     * its changes go ({@code save:}) and every editable field binds a record field the write-back may set.
     */
    @SuppressWarnings("unchecked")
    public static List<String> problems(LiveTableSpec spec, Predicate<String> formatKnown, Function<String, Optional<Set<String>>> contract) {
        List<String> out = new ArrayList<>();
        for (LiveTableSpec.Column c : spec.columns()) {
            if (c.fmt() != null && !formatKnown.test(c.fmt())) {
                out.add("format " + c.fmt() + " is not a named format of formats.yaml");
            }
        }
        Optional<Set<String>> known = contract.apply(DrishtiAuthoriser.kind(spec));
        if (known.isPresent()) {
            for (LiveTableSpec.Column c : spec.columns()) {
                String top = c.name().contains(".") ? c.name().substring(0, c.name().indexOf('.')) : c.name();
                if (!known.get().contains(top) && !"id".equals(top) && !KindRows.BUSINESS_DATE.equals(top)) {
                    out.add("field " + c.name() + " is not in the " + DrishtiAuthoriser.kind(spec) + " data contract");
                }
            }
        }
        if (spec.profile().get("links") instanceof List<?> links) {
            for (Object l : links) {
                if (l instanceof Map<?, ?> lm && lm.get("href") != null && !String.valueOf(lm.get("href")).startsWith("/")) {
                    out.add("link " + lm.get("label") + ": href is a path of this console (/...), not '" + lm.get("href") + "'");
                } else if (!(l instanceof Map<?, ?> lm2) || lm2.get("label") == null || lm2.get("href") == null) {
                    out.add("a link is { label, href }");
                }
            }
        }
        if (spec.edit() != null) {
            Form f = form(spec);
            if (f == null) {
                out.add("edit: is not a form the write-back reads");
            } else if (f.save() == null) {
                out.add("edit: needs save: (target, command, approval) for the changes to go anywhere");
            }
        }
        return out;
    }

    /**
     * A kind's default declaration (a bare mnemonic): its search columns, else its promoted columns, up to twelve; keyed by the record's
     * id ({@code keyColumn}); read-only; titled by its mnemonic; narrowed when its contract says it is never listed whole. Empty when there
     * is no column to show.
     */
    public static Optional<LiveTableSpec> defaultSpec(String kind, List<String> keyColumns, Set<String> promoted, String code, String keyColumn,
            List<String> narrow) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        (keyColumns == null ? List.<String>of() : keyColumns).forEach(c -> fields.add(c.startsWith("$.") ? c.substring(2) : c));
        if (fields.isEmpty() && promoted != null) {
            promoted.stream().filter(c -> !c.equals("id") && !c.equals("doc") && !c.equals(KindRows.BUSINESS_DATE)).sorted().forEach(fields::add);
        }
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        columns.add(Map.of("name", keyColumn, "label", "Id"));
        fields.stream().filter(f -> !f.equals(keyColumn)).limit(12).forEach(f -> columns.add(Map.of("name", f, "label",
                com.ash.drishti.server.api.HistoryController.label(f))));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("rachana", 1);
        doc.put("live-table", defaultName(kind));
        doc.put("title", code == null ? kind : code);
        doc.put("kind", kind);
        doc.put("snapshot", kind);
        doc.put("stream", kind);
        doc.put("key", keyColumn);
        doc.put("columns", columns);
        if (narrow != null && !narrow.isEmpty()) {
            doc.put("narrow", Map.of("any-of", narrow.stream().map(n -> n.startsWith("$.") ? n.substring(2) : n).toList()));
        }
        return Optional.of(LiveTableSpec.of(doc, PROFILE_KEYS, f -> true));
    }

    /** A default declaration with the default 5-argument form (no narrowing). */
    public static Optional<LiveTableSpec> defaultSpec(String kind, List<String> keyColumns, Set<String> promoted, String code, String keyColumn) {
        return defaultSpec(kind, keyColumns, promoted, code, keyColumn, List.of());
    }

    /** The top-level fields of a kind's data contract; empty when no loaded pack has one. */
    static Optional<Set<String>> contractFields(com.ash.drishti.packs.PackRegistry packs, String kind) {
        return contract(packs, kind).flatMap(DrishtiLiveTables::contractFields);
    }

    /** The top-level fields of one contract file ({@code fields} and {@code nested}); empty when it cannot be read or names none. */
    static Optional<Set<String>> contractFields(Path file) {
        try {
            return contractFields(new ObjectMapper(new YAMLFactory()).readTree(file.toFile()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Set<String>> contractFields(JsonNode n) {
        Set<String> out = new LinkedHashSet<>();
        n.path("fields").forEach(x -> out.add(x.path("name").asText()));
        n.path("nested").fieldNames().forEachRemaining(name -> out.add(name.replace("[]", "")));
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /** A kind's columns from its data contract: its key fields, its date column when the data has one, then its fields in order. */
    static List<String> contractColumns(com.ash.drishti.packs.PackRegistry packs, String kind) {
        return contract(packs, kind).map(DrishtiLiveTables::columnsOf).orElse(List.of());
    }

    static List<String> columnsOf(JsonNode n) {
        Set<String> out = new LinkedHashSet<>();
        n.path("key").forEach(x -> out.add(x.asText()));
        Set<String> named = new LinkedHashSet<>();
        n.path("fields").forEach(x -> named.add(x.path("name").asText()));
        if (n.hasNonNull("dated-by") && (KindRows.BUSINESS_DATE.equals(n.path("dated-by").asText()) || named.contains(n.path("dated-by").asText()))) {
            out.add(n.path("dated-by").asText());
        }
        out.addAll(named);
        out.removeIf(String::isBlank);
        return new ArrayList<>(out);
    }

    private static Optional<JsonNode> contract(com.ash.drishti.packs.PackRegistry packs, String kind) {
        for (com.ash.drishti.packs.Pack p : packs.packs()) {
            if (!p.kinds().contains(kind) || p.dir() == null) {
                continue;
            }
            Path f = p.dir().resolve("contracts").resolve(kind + ".contract.yaml");
            if (Files.isRegularFile(f)) {
                try {
                    return Optional.of(new ObjectMapper(new YAMLFactory()).readTree(f.toFile()));
                } catch (IOException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}

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
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.rachana.model.Form;
import com.ash.drishti.server.edit.EditableFields;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live result sets (modules/trading/docs/guide.md section 7): one engine behind every blotter and every FORM search that ticks. It reads
 * the declarations ({@code blotters/*.blotter.yaml} of the modules that are on, and the site's {@code config/blotters/}), opens one shared
 * {@link LiveSet} per declaration and condition while anyone views it, flushes the viewers' windows every {@code flush-ms}, trims the live
 * side by each declaration's retention, and sends a blotter's edits down the one write-back path (versions, approvals, outbox, audit):
 * the declaration's {@code edit:} form joins the Sutras' forms in {@link EditableFields} under the name {@code live:<id>}.
 */
public final class LiveSets implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LiveSets.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How a set is filled and kept: the snapshot and the live side, by kind. */
    public interface Sources {

        /** Every record of the kind now (the durable store through the bindings); false when it cannot read so. */
        boolean snapshot(String kind, String query, java.util.function.Consumer<EntityDocument> sink) throws Exception;

        /** The kind's live changes. */
        Subscription subscribe(String kind, java.util.function.Consumer<EntityDocument> listener);

        /** The query's condition as a test of one document. */
        Predicate<DataNode> condition(String query);

        /** The kind a search command reads ({@code TRD where ...} is {@code trade}). */
        default String kindOf(String command) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this server runs no searches into live sets");
        }

        /**
         * A search's matches as a set's snapshot (a FORM's result, the search page's): each match to the sink as its document masked by
         * {@code mask}, in the search's order, run as {@code who} (across Drishti*net's nodes as the search is); answers what the search said (kind, mnemonic, columns, labels, matched, scanned, partial,
         * failed, elapsedMs, noData, pivot), the shape {@code GET /api/v1/live/query} gives as {@code search}.
         */
        /**
         * The default declaration of a kind (a bare mnemonic, {@code POS <GO>}), made from its contract: the entity id as the key, the
         * kind's search columns (its key fields, else its promoted columns), read-only. Empty when the kind has none to show.
         */
        default Optional<LiveSetSpec> defaultFor(String kind) {
            return Optional.empty();
        }

        default Map<String, Object> search(String command, AsOf asOf, Principal who, UnaryOperator<DataNode> mask,
                java.util.function.Consumer<EntityDocument> sink) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this server runs no searches into live sets");
        }
    }

    /**
     * Whether a person may edit through a declaration now: null when both the site's configuration and the person's rights allow it,
     * else the reason in words (modules/trading/docs/guide.md section 8).
     */
    public interface Access {
        String readOnly(Principal p, LiveSetSpec spec);
    }

    /** The audit log: actor, action, subject, detail. */
    public interface Audit {
        void record(String actor, String action, String subject, String detail);
    }

    /** What a blotter's edit is handed to: the write-back's submit, as the person. */
    public interface Submit extends BiFunction<Principal, JsonNode, List<Map<String, Object>>> {
    }

    private final Map<String, LiveSetSpec> specs = new ConcurrentHashMap<>();
    /** The declarations a search made (a FORM's result, the search page's), while their set is open: never listed, never editable. */
    private final Map<String, LiveSetSpec> implicit = new ConcurrentHashMap<>();
    /** The default declarations made for kinds no module declares a set of, by id. */
    private final Map<String, LiveSetSpec> defaults = new ConcurrentHashMap<>();
    private volatile int defaultMaxRows = 250_000;
    private final Map<String, String> problems = new ConcurrentHashMap<>();
    private final Map<String, LiveSet> open = new ConcurrentHashMap<>();
    private final Map<String, Instant> idleSince = new ConcurrentHashMap<>();
    private final ReentrantLock opening = new ReentrantLock();
    private final Sources sources;
    private final Clock clock;
    private final ScheduledExecutorService ticker;
    private final int windowCapacity;
    private volatile Submit submit;
    private volatile Access access = (p, s) -> s.editable() ? null : "its declaration names no editable fields";
    private volatile Audit audit = (a, b, c, d) -> { };

    /**
     * @param sources the snapshot and live side
     * @param flushMs how often viewers' windows are flushed (50)
     * @param windowCapacity records waiting per viewer before it is asked to resync (5,000)
     */
    public LiveSets(Sources sources, Clock clock, long flushMs, int windowCapacity) {
        this.sources = sources;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.windowCapacity = windowCapacity < 16 ? 5_000 : windowCapacity;
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drishti-live-sets");
            t.setDaemon(true);
            return t;
        });
        long every = Math.max(10, flushMs);
        ticker.scheduleWithFixedDelay(this::flushAll, every, every, TimeUnit.MILLISECONDS);
        ticker.scheduleWithFixedDelay(this::upkeep, 30, 30, TimeUnit.SECONDS);
    }

    /** Who may edit, by the site's configuration and the person's rights. */
    public LiveSets access(Access a) {
        this.access = a;
        return this;
    }

    /** Where refusals are recorded. */
    public LiveSets audit(Audit a) {
        this.audit = a;
        return this;
    }

    /** Why this person may not edit through the declaration, or null when they may. */
    public String readOnly(Principal p, LiveSetSpec spec) {
        if (!spec.editable()) {
            return "its declaration names no editable fields";
        }
        if (submit == null) {
            return "editing is off on this server (edit.enabled in config/edit/edit.yaml)";
        }
        return access.readOnly(p, spec);
    }

    /** Where a blotter's edits go (the write-back's submit). */
    public LiveSets submit(Submit s) {
        this.submit = s;
        return this;
    }

    // ------------------------------------------------------------------------------------------------------------ declarations

    /**
     * Reads every {@code *.blotter.yaml} in {@code dirs}; a declaration with problems is set aside and listed ({@link #problems()}).
     * {@code module} names the module a folder belongs to (null for the site's own).
     */
    public void load(List<Path> dirs, Function<Path, String> module) {
        Map<String, LiveSetSpec> found = new LinkedHashMap<>();
        Map<String, String> bad = new LinkedHashMap<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".blotter.yaml")).sorted().toList()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = YAML.readValue(f.toFile(), Map.class);
                        LiveSetSpec s = LiveSetSpec.parse(m, f.getFileName().toString(), module.apply(dir));
                        if (found.containsKey(s.id())) {
                            bad.put(f.toString(), "blotter " + s.id() + " is declared twice");
                        } else {
                            found.put(s.id(), s);
                        }
                    } catch (IOException | IllegalArgumentException e) {
                        bad.put(f.toString(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                bad.put(dir.toString(), e.getMessage());
            }
        }
        specs.clear();
        specs.putAll(found);
        problems.clear();
        problems.putAll(bad);
        bad.forEach((f, why) -> LOG.warn("live sets: {} is set aside: {}", f, why));
    }

    /** Adds one declaration (tests, and a FORM search's implicit one). */
    public void add(LiveSetSpec s) {
        specs.put(s.id(), s);
    }

    public Optional<LiveSetSpec> spec(String id) {
        LiveSetSpec s = specs.get(id);
        if (s == null) {
            s = implicit.get(id);
        }
        return Optional.ofNullable(s != null ? s : defaults.get(id));
    }

    /** The most rows a default declaration's snapshot reads ({@code drishti.live-sets.default-max-rows}); beyond it the set says capped. */
    public LiveSets defaultMaxRows(int n) {
        this.defaultMaxRows = Math.max(1, n);
        return this;
    }

    /**
     * A kind's own set (a bare mnemonic, {@code POS <GO>}): a module's declaration of the kind (the one named as the kind, else the first by
     * id) overrides the default made from the kind's contract. No per-mnemonic YAML is needed.
     */
    public LiveSetSpec forKind(String kind) {
        LiveSetSpec own = specs.get(kind);
        if (own != null && own.kind().equals(kind)) {
            return own;
        }
        Optional<LiveSetSpec> declared = specs.values().stream().filter(x -> x.kind().equals(kind)).min(java.util.Comparator.comparing(LiveSetSpec::id));
        if (declared.isPresent()) {
            return declared.get();
        }
        LiveSetSpec made = defaults.values().stream().filter(x -> x.kind().equals(kind)).findFirst().orElse(null);
        if (made != null) {
            return made;
        }
        LiveSetSpec d = sources.defaultFor(kind)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the kind " + kind + " has no columns to show as a set"));
        defaults.put(d.id(), d);
        return d;
    }

    /** True for a default declaration ({@link #forKind}). */
    public boolean defaulted(String id) {
        return defaults.containsKey(id) && !specs.containsKey(id);
    }

    /** True for the declaration a search made ({@link #query}). */
    public boolean implicit(String id) {
        return implicit.containsKey(id) && !specs.containsKey(id);
    }

    /** True when a search's set was filled for people whose masked fields are masked (its id says so). */
    public static boolean maskedSet(String id) {
        return id != null && id.startsWith(MASKED);
    }

    private static final String MASKED = "qm-";
    private static final String PLAIN = "qu-";

    public List<LiveSetSpec> specs() {
        return List.copyOf(specs.values());
    }

    public Map<String, String> problems() {
        return Map.copyOf(problems);
    }

    /** The editable fields of every editable declaration, for {@link EditableFields#addSource}. */
    public List<EditableFields.Rule> rules() {
        List<EditableFields.Rule> out = new ArrayList<>();
        for (LiveSetSpec s : specs.values()) {
            if (!s.editable()) {
                continue;
            }
            Form f = s.form();
            String kind = f.save().kind() != null ? f.save().kind() : s.kind();
            for (Form.Field fd : f.fields()) {
                out.add(new EditableFields.Rule(kind, s.kind(), s.formName(), LiveSetSpec.PANEL, null, f, fd));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------ sets

    /** The shared set of a declaration and condition, opened (snapshot read, live side subscribed) on first use. */
    public LiveSet open(LiveSetSpec spec, String query) {
        String q = query == null || query.isBlank() ? spec.query() : query.strip();
        String key = spec.id() + "\u001f" + (q == null ? "" : q);
        LiveSet s = open.get(key);
        if (s != null) {
            return s;
        }
        if (implicit.containsKey(spec.id()) || spec.id().startsWith(MASKED) || spec.id().startsWith(PLAIN)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the result " + spec.id() + " has closed: run the search again");
        }
        opening.lock();                                   // one opening at a time: a second viewer waits for the first's snapshot
        try {
            s = open.get(key);
            if (s != null) {
                return s;
            }
            Predicate<DataNode> holds = q == null ? d -> true : sources.condition(q);
            LiveSet made = new LiveSet(spec, q, holds, clock);
            String qq = q;
            boolean capping = defaulted(spec.id());
            int cap = defaultMaxRows;
            made.start(l -> sources.subscribe(spec.kind(), l), sink -> {
                java.util.concurrent.atomic.AtomicInteger read = new java.util.concurrent.atomic.AtomicInteger();
                java.util.function.Consumer<EntityDocument> counted = !capping ? sink : d -> {
                    if (read.incrementAndGet() > cap) {
                        throw new Capped();
                    }
                    sink.accept(d);
                };
                try {
                    if (!sources.snapshot(spec.kind(), qq, counted)) {
                        LOG.info("live sets: {} has no snapshot reader for {}; it starts empty and fills from the live side", spec.id(), spec.kind());
                    }
                } catch (Capped c) {
                    made.capped(true);                    // a kind of a million records: the first rows, said so
                    LOG.info("live sets: {} read the first {} records of {} (drishti.live-sets.default-max-rows)", spec.id(), cap, spec.kind());
                }
            });
            open.put(key, made);
            return made;
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the live set " + spec.id() + " could not be read: " + e.getMessage());
        } finally {
            opening.unlock();
        }
    }

    /**
     * The set of a search command (a FORM's result, the search page's): the search's matches, in its order, with the kind's live changes
     * merged in by key while the business date is the live one (static otherwise, and when the kind has no {@code live:} binding). One set
     * per command, business date and masking, shared by everyone who runs it; {@code again} (a search pressed again) reads a static set
     * afresh, while a live one is kept current by its live side. Masked people get their own set, filled and kept masked, so a masked
     * value never decides which rows are in it.
     */
    public LiveSet query(String command, AsOf asOf, Principal who, boolean masks, UnaryOperator<DataNode> mask, boolean again) {
        AsOf at = asOf == null ? AsOf.LATEST : asOf;
        String c = command == null ? "" : command.strip();
        if (c.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a live query names a search: q=TRD where ...");
        }
        String kind = sources.kindOf(c);
        String id = queryId(c, at, masks);
        String key = id + "\u001f" + c;
        LiveSet s = open.get(key);
        if (s != null && !(again && !s.live())) {
            return s;
        }
        opening.lock();
        try {
            s = open.get(key);
            if (s != null && !(again && !s.live())) {
                return s;
            }
            if (s != null) {
                open.remove(key, s);
                s.close();
            }
            UnaryOperator<DataNode> m = mask == null ? UnaryOperator.identity() : mask;
            LiveSetSpec bare = new LiveSetSpec(id, c, kind, c, null, null, null, null, List.of(), List.of(), List.of(), null, false, List.of(), null,
                    null, false, null);
            Predicate<DataNode> holds = sources.condition(c);
            LiveSet made = new LiveSet(bare, c, holds, clock, true);
            made.start(l -> at.live() ? sources.subscribe(kind, d -> l.accept(masked(d, m))) : null, sink -> {
                Map<String, Object> said = sources.search(c, at, who, m, sink);
                made.origin(said);
                made.spec(withColumns(bare, said));
            });
            implicit.put(id, made.spec());
            open.put(key, made);
            idleSince.remove(key);
            return made;
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the search " + c + " could not be read: " + e.getMessage());
        } finally {
            opening.unlock();
        }
    }

    /** The kind a search command reads. */
    public String kindOf(String command) {
        return sources.kindOf(command == null ? "" : command.strip());
    }

    /** Stops a default declaration's snapshot at its cap. */
    private static final class Capped extends RuntimeException {
        Capped() {
            super(null, null, false, false);
        }
    }

    /** A search's set's id: the command, the business date (or live) and the masking, as a short hash. */
    static String queryId(String command, AsOf at, boolean masks) {
        String basis = command + "\u001f" + (at.live() ? "live" : at.businessDate() + "@" + at.knownAt()) + "\u001f" + masks;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(basis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return (masks ? MASKED : PLAIN) + java.util.HexFormat.of().formatHex(h, 0, 10);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EntityDocument masked(EntityDocument d, UnaryOperator<DataNode> m) {
        return d.deleted() ? d : new EntityDocument(d.ref(), m.apply(d.data()), d.provenance(), false);
    }

    /** The search's columns ({@code $.CURRENCY}) as the declaration's ({@code {field: CURRENCY, label: Currency}}). */
    @SuppressWarnings("unchecked")
    static LiveSetSpec withColumns(LiveSetSpec s, Map<String, Object> said) {
        List<Map<String, Object>> cols = new ArrayList<>();
        Map<String, Object> labels = said.get("labels") instanceof Map<?, ?> l ? (Map<String, Object>) l : Map.of();
        if (said.get("columns") instanceof List<?> l) {
            for (Object o : l) {
                String path = String.valueOf(o);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("field", path.startsWith("$.") ? path.substring(2) : path);
                c.put("path", path);
                c.put("label", labels.getOrDefault(path, path));
                cols.add(c);
            }
        }
        String title = said.get("mnemonic") != null ? said.get("mnemonic") + " search" : s.title();
        return new LiveSetSpec(s.id(), title, s.kind(), s.query(), s.key(), s.version(), s.changedBy(), s.changedAt(), cols, List.of(), List.of(), null,
                false, List.of(), null, null, false, null);
    }

    /** Every open set (Health, Admin). */
    public List<Map<String, Object>> describe() {
        return open.values().stream().map(LiveSet::describe).toList();
    }

    private void flushAll() {
        try {
            for (LiveSet s : open.values()) {
                for (LiveSet.Window w : s.openWindows()) {
                    try {
                        w.flush();
                    } catch (RuntimeException e) {
                        s.closeWindow(w);                 // a viewer that cannot take its event has gone
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("live sets: flush: {}", e.getMessage());
        }
    }

    /** The retention of each open set, and the sets nobody has viewed for ten minutes closed. */
    void upkeep() {
        Instant now = Instant.now(clock);
        for (Map.Entry<String, LiveSet> e : open.entrySet()) {
            LiveSet s = e.getValue();
            try {
                s.trim();
            } catch (RuntimeException x) {
                LOG.debug("live sets: retention of {}: {}", s.spec().id(), x.getMessage());
            }
            if (s.windows() > 0) {
                idleSince.remove(e.getKey());
            } else if (idleSince.putIfAbsent(e.getKey(), now) != null && idleSince.get(e.getKey()).plusSeconds(600).isBefore(now)) {
                open.remove(e.getKey(), s);
                idleSince.remove(e.getKey());
                if (implicit.containsKey(s.spec().id()) && !specs.containsKey(s.spec().id())) {
                    implicit.remove(s.spec().id());
                }
                s.close();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ edits

    /**
     * A blotter's edit: {@code {entity, items: [{field, from, to}], basedOn, reason}}. Refused at once when the record moved since the
     * person saw it (its version is not {@code basedOn}): the answer names the version, who made it and when. Otherwise handed to the
     * write-back as the person, under the declaration's form: approvals, the outbox and the audit are the write-back's.
     */
    public List<Map<String, Object>> edit(String id, Principal p, JsonNode body) {
        LiveSetSpec spec = specs.get(id);
        if (spec == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no blotter " + id);
        }
        String entity = body.path("entity").asText("");
        String no = readOnly(p, spec);
        if (no != null) {
            audit.record(p.user(), "edit-refused", spec.kind() + ":" + entity, "blotter " + id + " is read-only for " + p.user() + ": " + no);
            throw new DrishtiException(ErrorCode.EDIT_REFUSED, "blotter " + id + " is read-only for you: " + no);
        }
        Submit s = submit;
        if (entity.isBlank() || !body.hasNonNull("basedOn")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "an edit names the record (entity) and the version it was made on (basedOn)");
        }
        LiveSet set = open(spec, null);
        LiveSet.Row now = set.current(entity);
        long basedOn = body.path("basedOn").asLong();
        if (now != null && now.version() >= 0 && now.version() != basedOn) {
            String by = spec.changedBy() == null ? null : String.valueOf(now.doc().get(spec.changedBy()).unwrap());
            String at = spec.changedAt() == null ? null : String.valueOf(now.doc().get(spec.changedAt()).unwrap());
            String why = spec.kind() + " " + entity + " is at version " + now.version()
                    + (by == null || "null".equals(by) ? "" : ", changed by " + by) + (at == null || "null".equals(at) ? "" : " at " + at)
                    + "; you edited version " + basedOn + ": open it again and edit from what is there";
            audit.record(p.user(), "edit-conflicted", spec.kind() + ":" + entity, "blotter " + id + ": " + why);
            throw new DrishtiException(ErrorCode.EDIT_CONFLICT, why);
        }
        ObjectNode change = JSON.createObjectNode();
        change.put("kind", spec.form().save().kind() != null ? spec.form().save().kind() : spec.kind());
        change.put("entity", entity);
        change.put("basedOn", basedOn);
        change.put("sutra", spec.formName());
        change.put("panel", LiveSetSpec.PANEL);
        change.put("via", "blotter");
        if (body.hasNonNull("reason")) {
            change.put("reason", body.path("reason").asText());
        }
        ArrayNode items = change.putArray("items");
        for (JsonNode it : body.path("items")) {
            ObjectNode i = items.addObject();
            i.put("field", it.path("field").asText(""));
            i.set("from", it.get("from"));
            i.set("to", it.get("to"));
        }
        return s.apply(p, change);
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        open.values().forEach(LiveSet::close);
        open.clear();
    }

    /** The folders the declarations are read from: each module's {@code blotters/} (module on), then the site's. */
    public static List<Path> folders(Path modulesRoot, Path siteDir, Predicate<String> moduleOn) {
        List<Path> out = new ArrayList<>();
        if (modulesRoot != null && Files.isDirectory(modulesRoot)) {
            try (Stream<Path> ms = Files.list(modulesRoot)) {
                ms.sorted().forEach(m -> {
                    if (moduleOn.test(m.getFileName().toString())) {
                        for (Path b : List.of(m.resolve("blotters"), m.resolve("src/dmod/blotters"))) {
                            if (Files.isDirectory(b)) {
                                out.add(b);
                            }
                        }
                    }
                });
            } catch (IOException e) {
                LOG.warn("live sets: {} cannot be listed: {}", modulesRoot, e.getMessage());
            }
        }
        if (siteDir != null) {
            out.add(siteDir);
        }
        return out;
    }

    /** The module a declarations folder belongs to: {@code modules/<id>/blotters} → {@code id}. */
    public static String moduleOf(Path dir) {
        Path p = dir.getFileName() != null && dir.getFileName().toString().equals("blotters") ? dir.getParent() : null;
        if (p != null && p.getFileName() != null && p.getFileName().toString().equals("dmod")) {
            p = p.getParent() == null ? null : p.getParent().getParent();
        }
        return p == null || p.getParent() == null || !"modules".equals(String.valueOf(p.getParent().getFileName())) ? null
                : p.getFileName().toString();
    }

    /** Unused by the engine; keeps the masking contract in one place for callers. */
    static UnaryOperator<DataNode> none() {
        return d -> d;
    }

    /** For tests: a supplier view of the open sets. */
    Supplier<Integer> openCount() {
        return open::size;
    }

    /** The record as it would be fetched (an edit's panel opened on a record outside any set). */
    static EntityRef ref(LiveSetSpec s, String id) {
        return EntityRef.of(s.kind(), id);
    }

    static AsOf live() {
        return AsOf.LATEST;
    }
}

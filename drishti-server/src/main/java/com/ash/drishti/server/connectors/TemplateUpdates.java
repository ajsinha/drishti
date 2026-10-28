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
package com.ash.drishti.server.connectors;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.PackLoader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connector files that have drifted from their pack's current template (CONNECTOR_FILES.md, "Template updates"). A file generated
 * once from an older template can lack kinds the pack serves now; this finds, for each connector with text (a file or a shared
 * edit):
 * <ul>
 *   <li><b>additive</b>: kinds the template serves that the connector lacks, settings keys the template has and the connector
 *       does not (layout entries for those kinds among them), and columns a layout list of the template has and the connector's same
 *       list lacks ({@code layout.<kind>.columns} and {@code .vectors}: a pack that gains columns, as the VaR inputs did in 2.9.4; the
 *       connector's own columns and their order kept, the template's new ones appended);</li>
 *   <li><b>conflicting</b>: settings both have with different values (an administrator's edit, never changed automatically);</li>
 *   <li><b>obsolete</b>: kinds in the connector that no loaded pack serves.</li>
 * </ul>
 * With {@code template-updates: additive} (the default) the additive part is merged automatically, keeping every edit, through
 * {@link ConnectorManager#save} (so a shared edit, on every node, with the previous text in the history); {@code notify} only lists;
 * {@code off} does nothing automatically. Conflicting and obsolete parts are only ever changed by an administrator's button.
 */
public final class TemplateUpdates implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateUpdates.class);
    static final String ACTOR = "system";

    /** What happens automatically. */
    public enum Mode {
        ADDITIVE, NOTIFY, OFF;

        public static Mode of(String s) {
            try {
                return s == null || s.isBlank() ? ADDITIVE : valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                LOG.warn("connectors template-updates '{}' is not additive, notify or off; using additive", s);
                return ADDITIVE;
            }
        }
    }

    /** One drifted connector: its three classes, the current text and the template's text (for the side-by-side view). */
    public record Drift(String name, String pack, List<String> missingKinds, Map<String, String> newSettings,
            Map<String, List<String>> conflicting, List<String> obsoleteKinds, String etag, String text, String templateText,
            Map<String, String> extendedLists, Map<String, Object> newParams) {

        public Drift(String name, String pack, List<String> missingKinds, Map<String, String> newSettings,
                Map<String, List<String>> conflicting, List<String> obsoleteKinds, String etag, String text, String templateText,
                Map<String, String> extendedLists) {
            this(name, pack, missingKinds, newSettings, conflicting, obsoleteKinds, etag, text, templateText, extendedLists, Map.of());
        }

        public Drift(String name, String pack, List<String> missingKinds, Map<String, String> newSettings,
                Map<String, List<String>> conflicting, List<String> obsoleteKinds, String etag, String text, String templateText) {
            this(name, pack, missingKinds, newSettings, conflicting, obsoleteKinds, etag, text, templateText, Map.of(), Map.of());
        }

        public boolean additive() {
            return !missingKinds.isEmpty() || !newSettings.isEmpty() || !extendedLists.isEmpty() || !newParams.isEmpty();
        }

        public boolean any() {
            return additive() || !conflicting.isEmpty() || !obsoleteKinds.isEmpty();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("pack", pack);
            m.put("missingKinds", missingKinds);
            m.put("newSettings", newSettings.keySet().stream().sorted().toList());
            m.put("extendedLists", extendedLists.keySet().stream().sorted().toList());
            m.put("newParams", newParams.keySet().stream().sorted().toList());
            List<Map<String, Object>> c = new ArrayList<>();
            conflicting.forEach((k, v) -> c.add(Map.of("key", k, "connector", v.get(0), "template", v.get(1))));
            m.put("conflicting", c);
            m.put("obsoleteKinds", obsoleteKinds);
            m.put("additive", additive());
            m.put("etag", etag);
            m.put("text", text);
            m.put("templateText", templateText == null ? "" : templateText);
            return m;
        }
    }

    /** One automatic change, for the page's "recent automatic updates" and the admin notice. */
    public record Notice(Instant at, String name, String what) {}

    private final ConnectorManager manager;
    private final AuditLog audit;
    private volatile Mode mode;
    private final Deque<Notice> notices = new ConcurrentLinkedDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private Thread thread;

    public TemplateUpdates(ConnectorManager manager, AuditLog audit, Mode mode) {
        this.manager = manager;
        this.audit = audit;
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    public List<Notice> notices() {
        return List.copyOf(notices);
    }

    // -- finding ---------------------------------------------------------------------------------------------------

    /** Every connector with text that has drifted, by name. */
    public List<Drift> drifts() {
        List<Drift> out = new ArrayList<>();
        for (String name : manager.files().names()) {
            drift(name).filter(Drift::any).ifPresent(out::add);
        }
        return out;
    }

    /** The drift of one connector (empty when it has no text or cannot be read). */
    @SuppressWarnings("unchecked")
    public Optional<Drift> drift(String name) {
        ConnectorFiles.Definition d;
        try {
            d = manager.files().read(name);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (d == null) {
            return Optional.empty();
        }
        Set<String> served = new LinkedHashSet<>(manager.knownKinds());
        List<String> obsolete = d.kinds().stream().filter(k -> !served.contains(k)).toList();
        Optional<ConnectorManager.Template> t = manager.template(name);
        List<String> missing = new ArrayList<>();
        Map<String, String> fresh = new LinkedHashMap<>();
        Map<String, List<String>> conflicting = new LinkedHashMap<>();
        Map<String, String> extended = new LinkedHashMap<>();
        String templateText = null;
        String pack = null;
        if (t.isPresent() && String.valueOf(t.get().definition().get("plugin")).equals(d.plugin())) {
            pack = t.get().pack();
            templateText = manager.templateText(t.get());
            Map<String, Object> def = t.get().definition();
            if (!d.kinds().isEmpty() && def.get("kinds") instanceof List<?> l) {   // no kinds in the file: it serves what routes send
                l.forEach(k -> {
                    if (!d.kinds().contains(String.valueOf(k))) {
                        missing.add(String.valueOf(k));
                    }
                });
            }
            Map<String, Object> flat = new LinkedHashMap<>();
            PackLoader.flatten("", def.get("settings") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of(), flat);
            flat.forEach((k, v) -> {
                String tv = v == null ? "" : String.valueOf(v);
                String fv = d.settings().get(k);
                if (fv == null) {
                    fresh.put(k, tv);
                } else if (!fv.equals(tv)) {
                    String grown = layoutList(k) ? grown(fv, tv) : null;
                    if (grown != null) {
                        extended.put(k, grown);                         // columns the template gained: appended, the rest kept
                    } else {
                        conflicting.put(k, List.of(fv, tv));
                    }
                }
            });
        } else if (t.isPresent()) {
            pack = t.get().pack();
            templateText = manager.templateText(t.get());
        }
        Map<String, Object> params = t.isPresent() && String.valueOf(t.get().definition().get("plugin")).equals(d.plugin())
                ? newParams(t.get().definition(), d.compute()) : Map.of();
        return Optional.of(new Drift(name, pack, missing, fresh, conflicting, obsolete, d.etag(), d.text(), templateText, extended, params));
    }

    /**
     * A reactive connector's parameters its pack's template has gained since the file was written ({@code kinds.<kind>.params.<name>}, e.g.
     * {@code ccy} on var-whatif in 2.9.4), keyed {@code <kind>.<param>}: an older file lacks them and a call naming one is refused (DRS-5205).
     * Only parameters of kinds the file already computes; nothing it has is changed.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> newParams(Map<String, Object> template, Map<String, Object> compute) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(template.get("kinds") instanceof Map<?, ?> tk) || compute == null || !(compute.get("kinds") instanceof Map<?, ?> fk)) {
            return out;
        }
        tk.forEach((kind, tdef) -> {
            if (fk.get(kind) instanceof Map<?, ?> fdef && tdef instanceof Map<?, ?> td && td.get("params") instanceof Map<?, ?> tp) {
                Map<?, ?> fp = fdef.get("params") instanceof Map<?, ?> m ? m : Map.of();
                tp.forEach((p, spec) -> {
                    if (!fp.containsKey(p)) {
                        out.put(kind + "." + p, spec);
                    }
                });
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> withParams(Map<String, Object> compute, Map<String, Object> newParams) {
        if (compute == null || newParams.isEmpty()) {
            return compute;
        }
        Map<String, Object> c = new LinkedHashMap<>(compute);
        Map<String, Object> kinds = new LinkedHashMap<>((Map<String, Object>) c.get("kinds"));
        newParams.forEach((key, spec) -> {
            int dot = key.indexOf('.');
            String kind = key.substring(0, dot);
            Map<String, Object> def = new LinkedHashMap<>((Map<String, Object>) kinds.get(kind));
            Map<String, Object> params = def.get("params") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            params.putIfAbsent(key.substring(dot + 1), spec);
            def.put("params", params);
            kinds.put(kind, def);
        });
        c.put("kinds", kinds);
        return c;
    }

    /** A pack layout's list of columns ({@code layout.<kind>.columns} or {@code .vectors}). */
    static boolean layoutList(String key) {
        return key.startsWith("layout.") && (key.endsWith(".columns") || key.endsWith(".vectors"));
    }

    /**
     * The connector's list with the template's columns it lacks appended (its own columns and order kept), or null when it lacks none
     * (then the lists differ by the connector's own edit only: a conflict, never changed automatically).
     */
    static String grown(String connector, String template) {
        List<String> have = new ArrayList<>();
        for (String c : connector.split(",")) {
            if (!c.isBlank()) {
                have.add(c.strip());
            }
        }
        List<String> add = new ArrayList<>();
        for (String c : template.split(",")) {
            if (!c.isBlank() && !have.contains(c.strip()) && !add.contains(c.strip())) {
                add.add(c.strip());
            }
        }
        if (add.isEmpty()) {
            return null;
        }
        have.addAll(add);
        return String.join(",", have);
    }

    // -- changing --------------------------------------------------------------------------------------------------

    /** Text with the drift's additive part merged in (kinds appended, new keys added), every other line's value kept. */
    static String merged(ConnectorFiles files, ConnectorFiles.Definition d, Drift drift, List<String> dropKinds) {
        List<String> kinds = new ArrayList<>(d.kinds());
        kinds.removeAll(dropKinds);
        drift.missingKinds().forEach(k -> {
            if (!kinds.contains(k)) {
                kinds.add(k);
            }
        });
        Map<String, Object> settings = new LinkedHashMap<>(d.settings());
        drift.newSettings().forEach(settings::putIfAbsent);
        settings.putAll(drift.extendedLists());
        StringBuilder header = new StringBuilder();
        for (String line : d.text().split("\n", -1)) {
            if (!line.startsWith("#")) {
                break;
            }
            header.append(header.isEmpty() ? "" : "\n").append(line);
        }
        Object enabled = d.enabled() == null ? null : "true".equals(d.enabled().trim()) ? Boolean.TRUE
                : "false".equals(d.enabled().trim()) ? Boolean.FALSE : d.enabled();
        return files.render(header.toString(), d.plugin(), enabled, kinds, d.description(), settings, d.requires(), withParams(d.compute(), drift.newParams()),
                d.writes());
    }

    private ConnectorManager.Change write(String name, String ifMatch, boolean additive, boolean dropObsolete, String user, String what) {
        Drift drift = drift(name).orElseThrow(() -> new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "connector '" + name + "' has no file or shared edit"));
        if (ifMatch != null && !ifMatch.equals(drift.etag())) {
            throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, "connector '" + name + "' changed since you read it; reload it");
        }
        ConnectorFiles.Definition d = manager.files().read(name);
        Drift use = additive ? drift : new Drift(name, drift.pack(), List.of(), Map.of(), Map.of(), drift.obsoleteKinds(), drift.etag(), drift.text(),
                drift.templateText());
        if (additive && (!drift.extendedLists().isEmpty() || !drift.newParams().isEmpty())) {
            backup(name);                                              // the file as it was, beside it (besides the history)
        }
        String text = merged(manager.files(), d, use, dropObsolete ? drift.obsoleteKinds() : List.of());
        ConnectorManager.Change c = manager.save(name, text, drift.etag(), true);
        audit.record(user, "connector-template-update", name, what);
        return c;
    }

    /**
     * Keeps the connector's file as it was before columns were added to its layout: {@code <name>.yaml.before-<yyyyMMdd-HHmmss>.bak} in
     * the connectors folder (not a {@code .yaml}, so never read as a connector); a shared edit's previous text stays in the history.
     */
    private void backup(String name) {
        try {
            java.nio.file.Path f = manager.files().file(name);
            if (f != null && java.nio.file.Files.isRegularFile(f)) {
                String at = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                java.nio.file.Path b = f.resolveSibling(f.getFileName() + ".before-" + at + ".bak");
                java.nio.file.Files.copy(f, b);
                LOG.info("connector {}: the file before its layout gained the template's columns is kept as {}", name, b.getFileName());
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("connector {}: no backup of the file before the update ({})", name, e.getMessage());
        }
    }

    /** Add missing kinds: merges the additive part, keeping every edit. */
    public ConnectorManager.Change addMissing(String name, String ifMatch, String user) {
        Drift d = drift(name).filter(Drift::additive).orElseThrow(() -> new DrishtiException(ErrorCode.CONNECTOR_CONFLICT,
                "connector '" + name + "' lacks nothing its pack's template has"));
        return write(name, ifMatch, true, false, user, "added from the template of pack " + d.pack() + ": kinds " + d.missingKinds()
                + ", settings " + d.newSettings().keySet());
    }

    /** Remove obsolete kinds: kinds no loaded pack serves. */
    public ConnectorManager.Change removeObsolete(String name, String ifMatch, String user) {
        Drift d = drift(name).filter(x -> !x.obsoleteKinds().isEmpty()).orElseThrow(() -> new DrishtiException(ErrorCode.CONNECTOR_CONFLICT,
                "connector '" + name + "' has no kind that no pack serves"));
        return write(name, ifMatch, false, true, user, "removed kinds no pack serves: " + d.obsoleteKinds());
    }

    /** Regenerate from template: the template's text replaces the connector's (the old text goes to the history). */
    public ConnectorManager.Change regenerate(String name, String ifMatch, String user) {
        ConnectorManager.Template t = manager.template(name).orElseThrow(() -> new DrishtiException(ErrorCode.CONNECTOR_CONFLICT,
                "connector '" + name + "' has no pack template to regenerate from"));
        String now = manager.files().text(name);
        String etag = now == null ? null : ConnectorFiles.etag(now);
        if (ifMatch != null && etag != null && !ifMatch.equals(etag)) {
            throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, "connector '" + name + "' changed since you read it; reload it");
        }
        ConnectorManager.Change c = manager.save(name, manager.templateText(t), etag, true);
        audit.record(user, "connector-template-update", name, "regenerated from the template of pack " + t.pack() + " (the old text is in the history)");
        return c;
    }

    /** Fix all additive: merges the additive part of every drifted connector; returns the names changed. */
    public List<String> fixAllAdditive(String user) {
        List<String> done = new ArrayList<>();
        for (Drift d : drifts()) {
            if (d.additive()) {
                try {
                    addMissing(d.name(), d.etag(), user);
                    done.add(d.name());
                } catch (DrishtiException e) {
                    LOG.info("connector {}: template update skipped ({})", d.name(), e.getMessage());
                }
            }
        }
        return done;
    }

    /**
     * One automatic pass (at start, then every interval): with {@code additive}, merges each drifted connector's additive part;
     * another node merging the same connector at once makes this one's write a conflict, which is fine (the next pass sees it
     * merged). Returns the names merged.
     */
    public List<String> check() {
        if (mode != Mode.ADDITIVE) {
            return List.of();
        }
        lock.lock();
        try {
            List<String> done = new ArrayList<>();
            for (Drift d : drifts()) {
                if (!d.additive()) {
                    continue;
                }
                try {
                    write(d.name(), d.etag(), true, false, ACTOR, "added automatically from the template of pack " + d.pack() + ": kinds "
                            + d.missingKinds() + ", settings " + d.newSettings().keySet() + ", columns in " + d.extendedLists().keySet()
                            + ", parameters " + d.newParams().keySet() + " (template-updates: additive)");
                    String what = "added from pack " + d.pack() + "'s template: kinds " + d.missingKinds() + ", settings " + d.newSettings().keySet()
                            + (d.extendedLists().isEmpty() ? "" : ", the template's new columns in " + d.extendedLists().keySet())
                            + (d.newParams().isEmpty() ? "" : ", the template's new parameters " + d.newParams().keySet());
                    LOG.info("connector {}: {}; every edit kept, the previous text is in the history", d.name(), what);
                    notices.addFirst(new Notice(Instant.now(), d.name(), what));
                    while (notices.size() > 50) {
                        notices.removeLast();
                    }
                    done.add(d.name());
                } catch (DrishtiException e) {
                    LOG.debug("connector {}: automatic template update skipped ({})", d.name(), e.getMessage());
                }
            }
            return done;
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@link #check()} now and then every {@code intervalMs} on a virtual thread. */
    public synchronized TemplateUpdates start(long intervalMs) {
        if (thread == null && mode == Mode.ADDITIVE) {
            try {
                check();                        // once before the server answers: a request never meets a connector the upgrade has not reached
            } catch (RuntimeException e) {
                LOG.warn("connector template updates at start: {}", e.getMessage());
            }
            thread = Thread.ofVirtual().name("drishti-connector-template-updates").start(() -> loop(intervalMs));
        }
        return this;
    }

    /** The background loop: runs on its own virtual thread, never under this object's monitor. */
    private void loop(long intervalMs) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(Math.max(1000, intervalMs));      // the first check ran at start
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                check();
            } catch (RuntimeException e) {
                LOG.warn("connector template updates: {}", e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}

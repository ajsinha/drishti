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
package com.ash.drishti.registry;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The registry in process (ACCESS_MODEL.md section 5): decides over an {@link AccessStore} and keeps the decisions as one immutable
 * snapshot, recomputed when the store's model changes ({@link #refresh()}, or the store's watch). Thread-safe: readers see one snapshot.
 * Per-person questions keep nothing per person (session isolation).
 */
public final class EmbeddedAccessRegistry implements AccessRegistry, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(EmbeddedAccessRegistry.class.getName());

    private final AccessStore store;
    private final Function<String, Boolean> overrides;
    private final Clock clock;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot;
    private volatile AutoCloseable watch;
    private volatile FunctionSwitches functionSwitches = FunctionSwitches.NONE;

    /**
     * @param store     where the model is kept
     * @param overrides a module's switch set above the store (the setting {@code drishti.modules.<id>.enabled}: the command line, the
     *                  environment), or null when not set
     * @param clock     today, for the licence's dates
     */
    public EmbeddedAccessRegistry(AccessStore store, Function<String, Boolean> overrides, Clock clock) {
        this.store = store;
        this.overrides = overrides == null ? id -> null : overrides;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.snapshot = decide();
    }

    /** Watches the store every {@code every} and recomputes on a change. */
    public EmbeddedAccessRegistry watch(Duration every) {
        this.watch = store.watch(every, this::refresh);
        return this;
    }

    /** Reads the model again and recomputes the decisions; listeners are told when anything changed. */
    public void refresh() {
        Snapshot next = decide();
        Snapshot before = snapshot;
        snapshot = next;
        if (!next.sameAs(before)) {
            for (Runnable l : listeners) {
                try {
                    l.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "an access listener failed: " + e);
                }
            }
        }
    }

    @Override
    public List<ModuleState> modules() {
        return snapshot.states;
    }

    @Override
    public Optional<ModuleState> module(String id) {
        return Optional.ofNullable(snapshot.byId.get(ModuleManifest.norm(id)));
    }

    @Override
    public boolean moduleOn(String id) {
        ModuleState s = snapshot.byId.get(ModuleManifest.norm(id));
        return s != null && s.on();
    }

    @Override
    public Optional<ModuleManifest> manifest(String id) {
        String n = ModuleManifest.norm(id);
        if (ModuleManifest.CORE.equals(n)) {
            return Optional.of(ModuleManifest.CORE_MODULE);
        }
        return snapshot.model.modules().stream().filter(m -> m.id().equals(n)).findFirst();
    }

    @Override
    public Optional<String> moduleOfClass(String className) {
        return snapshot.model.modules().stream().filter(m -> m.ownsClass(className)).map(ModuleManifest::id).findFirst();
    }

    @Override
    public Optional<String> moduleOfEngine(String engineId) {
        return snapshot.model.modules().stream().filter(m -> m.engines().contains(engineId)).map(ModuleManifest::id).findFirst();
    }

    @Override
    public Optional<String> moduleOfApi(String path) {
        return snapshot.model.modules().stream().filter(m -> m.ownsApi(path)).map(ModuleManifest::id).findFirst();
    }

    @Override
    public Optional<String> moduleOfConnector(String name) {
        return snapshot.model.modules().stream().filter(m -> m.connectors().contains(name)).map(ModuleManifest::id).findFirst();
    }

    @Override
    public boolean packOn(String packId, String domain) {
        Snapshot s = snapshot;
        for (ModuleManifest m : s.model.modules()) {
            if (m.packs().contains(packId)) {
                return moduleOn(m.id());
            }
        }
        String d = ModuleManifest.norm(domain);
        if (d.isEmpty() || ModuleManifest.CORE.equals(d)) {
            return true;
        }
        boolean known = false;
        for (ModuleManifest m : s.model.modules()) {
            if (m.domain().equals(d)) {
                known = true;
                if (moduleOn(m.id())) {
                    return true;
                }
            }
        }
        return !known;
    }

    @Override
    public Set<String> words() {
        return snapshot.words;
    }

    @Override
    public Optional<String> moduleOfFunction(String word) {
        String w = word == null ? "" : word.strip().toUpperCase(java.util.Locale.ROOT);
        return snapshot.model.modules().stream().filter(m -> m.words().contains(w)).map(ModuleManifest::id).findFirst();
    }

    @Override
    public Optional<String> functionOfApi(String path) {
        // the most specific function wins: /api/v1/risk/exposure/predeal is PREDEAL's, the rest of /api/v1/risk/exposure EXP's
        String best = null;
        int longest = -1;
        for (ModuleManifest m : snapshot.model.modules()) {
            for (FunctionSpec f : m.functions()) {
                for (String prefix : f.api()) {
                    if (prefix.length() > longest && ModuleManifest.under(path, List.of(prefix))) {
                        best = f.word();
                        longest = prefix.length();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public boolean functionOn(String word) {
        return functionOffReason(word).isEmpty();
    }

    @Override
    public Optional<String> functionOffReason(String word) {
        if (word == null || word.isBlank()) {
            return Optional.empty();
        }
        String w = word.strip().toUpperCase(java.util.Locale.ROOT);
        Optional<String> module = moduleOfFunction(w);
        if (module.isPresent() && !moduleOn(module.get())) {
            return Optional.of("its module '" + module.get() + "' is off: " + module(module.get()).map(ModuleState::reason).orElse(""));
        }
        Boolean on = functionSwitches.on(w);
        return Boolean.FALSE.equals(on) ? Optional.of("an administrator switched it off (Admin -> Commands)") : Optional.empty();
    }

    @Override
    public boolean functionAllowed(Subject subject, String word) {
        return subject != null && functionOn(word);              // per-person grants and denials: 2.9.11.i
    }

    @Override
    public void functionSwitches(FunctionSwitches switches) {
        this.functionSwitches = switches == null ? FunctionSwitches.NONE : switches;
    }

    @Override
    public List<Lens> lenses() {
        return snapshot.lenses;
    }

    @Override
    public boolean lensDeclared(String id) {
        String n = ModuleManifest.norm(id);
        return snapshot.model.lenses().stream().anyMatch(l -> l.id().equals(n));
    }

    @Override
    public boolean lensOffered(String id) {
        String n = ModuleManifest.norm(id);
        return snapshot.lenses.stream().anyMatch(l -> l.id().equals(n));
    }

    @Override
    public List<Lens> lensesFor(Subject subject) {
        Snapshot s = snapshot;
        if (subject == null) {
            return List.of();
        }
        if (subject.admin() || s.model.roles().isEmpty()) {
            return s.lenses;
        }
        Set<String> roles = rolesOf(s, subject);
        List<Lens> out = new ArrayList<>();
        for (Lens l : s.lenses) {
            if (s.model.roles().stream().anyMatch(r -> roles.contains(r.id()) && r.opens(l.id()))) {
                out.add(l);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public boolean allowed(Subject subject, String lens, String action) {
        Snapshot s = snapshot;
        if (subject == null || !lensOffered(lens)) {
            return false;
        }
        if (subject.admin() || s.model.roles().isEmpty()) {
            return true;
        }
        Set<String> roles = rolesOf(s, subject);
        return s.model.roles().stream().anyMatch(r -> roles.contains(r.id()) && r.allows(lens, action));
    }

    @Override
    public Licence licence() {
        return snapshot.model.licence();
    }

    @Override
    public List<String> warnings() {
        return snapshot.warnings;
    }

    @Override
    public void setEnabled(String module, boolean on, String by) {
        store.setEnabled(module, on);
        LOG.log(System.Logger.Level.INFO, "the module ''{0}'' switched {1} by {2} (takes effect at the next start)", module, on ? "on" : "off", by);
        refresh();
    }

    @Override
    public long version() {
        return snapshot.version;
    }

    @Override
    public AutoCloseable onChange(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public String describe() {
        return store.describe();
    }

    @Override
    public void close() {
        AutoCloseable w = watch;
        if (w != null) {
            try {
                w.close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "closing the access watch: " + e);
            }
        }
    }

    private static Set<String> rolesOf(Snapshot s, Subject subject) {
        Set<String> roles = new HashSet<>(subject.roles());
        for (Assignment a : s.model.assignments()) {
            if (a.matches(subject)) {
                roles.addAll(a.roles());
            }
        }
        return roles;
    }

    private Snapshot decide() {
        AccessModel model = store.read();
        LocalDate today = LocalDate.now(clock);
        Map<String, ModuleManifest> byId = new LinkedHashMap<>();
        for (ModuleManifest m : model.modules()) {
            byId.put(m.id(), m);
        }
        Map<String, ModuleState> states = new LinkedHashMap<>();
        states.put(ModuleManifest.CORE, new ModuleState(ModuleManifest.CORE, ModuleManifest.CORE_MODULE.title(), ModuleManifest.CORE, true, true, true, true,
                "", "always on"));
        for (ModuleManifest m : model.modules()) {
            state(m, byId, model, today, states, new LinkedHashSet<>());
        }
        Set<String> words = new LinkedHashSet<>();
        for (ModuleManifest m : model.modules()) {
            if (states.get(m.id()).on()) {
                words.addAll(m.words());
            }
        }
        List<Lens> lenses = new ArrayList<>();
        List<String> warnings = new ArrayList<>(model.warnings());
        for (Lens l : model.lenses()) {
            boolean on = true;
            for (String need : l.modules()) {
                ModuleState st = states.get(need);
                if (st == null) {
                    if (!byId.containsKey(need)) {
                        warnings.add("the lens '" + l.id() + "' (" + l.source() + ") needs the module '" + need + "', which is not known here");
                    }
                    on = false;
                } else if (!st.on()) {
                    on = false;
                }
            }
            if (on) {
                lenses.add(l);
            }
        }
        long version = 17;
        for (ModuleState st : states.values()) {
            version = 31 * version + st.hashCode();
        }
        version = 31 * version + lenses.hashCode();
        version = 31 * version + model.roles().hashCode();
        version = 31 * version + model.assignments().hashCode();
        version = 31 * version + model.licence().hashCode();
        return new Snapshot(model, List.copyOf(states.values()), Map.copyOf(states), Set.copyOf(words), List.copyOf(lenses), List.copyOf(warnings), version);
    }

    /** A module's state, its requirements decided first (a cycle is refused: both off). */
    private ModuleState state(ModuleManifest m, Map<String, ModuleManifest> byId, AccessModel model, LocalDate today, Map<String, ModuleState> states,
            Set<String> visiting) {
        ModuleState done = states.get(m.id());
        if (done != null) {
            return done;
        }
        Boolean override = overrides.apply(m.id());
        Boolean site = model.switches().get(m.id());
        boolean enabled = override != null ? override : site != null ? site : m.shipped();
        String source = override != null ? "setting drishti.modules." + m.id() + ".enabled" : site != null ? "config/modules/" + m.id() + ".yaml" : "shipped";
        String refusal = model.licence().refusal(m.feature(), today);
        String reason = "";
        if (!m.installed()) {
            reason = "not installed (its jar is not on the class path)";
        } else if (refusal != null) {
            reason = "not licensed: " + refusal;
        } else if (!enabled) {
            reason = "switched off (" + source + ")";
        } else if (!visiting.add(m.id())) {
            reason = "its requirements form a cycle";
        } else {
            for (String need : m.requires()) {
                if (ModuleManifest.CORE.equals(need)) {
                    continue;
                }
                ModuleManifest dep = byId.get(need);
                ModuleState ds = dep == null ? null : state(dep, byId, model, today, states, visiting);
                if (ds == null || !ds.on()) {
                    reason = "needs the module '" + need + "', which is " + (ds == null ? "not known here" : "off");
                    break;
                }
            }
            visiting.remove(m.id());
        }
        ModuleState s = new ModuleState(m.id(), m.title(), m.domain(), m.installed(), refusal == null, enabled, reason.isEmpty(), reason, source);
        states.put(m.id(), s);
        return s;
    }

    private record Snapshot(AccessModel model, List<ModuleState> states, Map<String, ModuleState> byId, Set<String> words, List<Lens> lenses,
            List<String> warnings, long version) {

        boolean sameAs(Snapshot other) {
            return other != null && version == other.version && warnings.equals(other.warnings);
        }
    }
}

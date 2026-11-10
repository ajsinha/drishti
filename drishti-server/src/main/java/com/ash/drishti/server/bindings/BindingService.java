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
package com.ash.drishti.server.bindings;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.packs.bindings.BindingResolver;
import com.ash.drishti.packs.bindings.Bindings;
import com.ash.drishti.packs.bindings.BindingsFile;
import com.ash.drishti.packs.bindings.DataNeeds;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The kernel's data bindings (docs/architecture/KERNEL_AND_MODULES.md section 5; ADMIN_GUIDE.md, "Data bindings"): reads
 * {@code config/bindings.yaml}, applies it to the connectors and the router without a restart, resolves every kind (site binding, distribution
 * default, convention, demo, unbound), switches off the functions whose required data nothing serves, and keeps the four-eyes proposals of
 * Admin → Data bindings. Every step is audited. A binding names connectors and lakes only: no credential ever reaches a module.
 */
public final class BindingService {

    private static final Logger LOG = LoggerFactory.getLogger(BindingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One proposal. {@code state}: proposed, approved, rejected or withdrawn; {@code binding} null removes the kind's binding. */
    public record Proposal(String id, String kind, Map<String, Object> binding, Map<String, Object> was, String reason, String proposedBy,
            String proposedAt, String state, String decidedBy, String decidedAt, String comment) {

        Proposal decided(String s, String by, String at, String why) {
            return new Proposal(id, kind, binding, was, reason, proposedBy, proposedAt, s, by, at, why);
        }
    }

    private final BindingsFile file;
    private final ConnectorManager connectors;
    private final SourceRouter router;
    private final PackRegistry packs;
    private final DataNeeds allNeeds;
    private final Predicate<String> moduleOn;
    private final java.util.function.BooleanSupplier demoEnabled;
    private final AuditLog audit;
    private final Path proposalsFile;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Map<String, Object>> contracts = new ConcurrentHashMap<>();

    private volatile Bindings applied = Bindings.NONE;
    private volatile String appliedEtag = null;
    private volatile String fileProblem = null;
    private volatile boolean started;
    private volatile BindingResolver.Result last = new BindingResolver.Result(Map.of(), Map.of(), List.of());

    public BindingService(BindingsFile file, ConnectorManager connectors, SourceRouter router, PackRegistry packs, DataNeeds needs,
            Predicate<String> moduleOn, java.util.function.BooleanSupplier demoEnabled, AuditLog audit, Path proposalsFile, Clock clock) {
        this.file = file;
        this.connectors = connectors;
        this.router = router;
        this.packs = packs;
        this.allNeeds = needs;
        this.moduleOn = moduleOn;
        this.demoEnabled = demoEnabled;
        this.audit = audit;
        this.proposalsFile = proposalsFile;
        this.clock = clock;
    }

    // -- applying ----------------------------------------------------------------------------------------------------

    /** Reads the file again when it changed (an edit, an approval, another node) and applies it: connectors, routes, then the gate. */
    public void refresh() {
        lock.lock();
        try {
            String etag = file.etag();
            if (!etag.equals(appliedEtag)) {
                try {
                    Bindings next = file.read();
                    apply(next);
                    fileProblem = null;
                } catch (RuntimeException e) {
                    if (!String.valueOf(e.getMessage()).equals(fileProblem)) {
                        LOG.warn("data bindings: {} is not usable; the bindings applied before stay: {}", file.path(), e.getMessage());
                    }
                    fileProblem = e.getMessage();
                }
                started = true;
                appliedEtag = etag;
            }
            last = resolve(false);
        } finally {
            lock.unlock();
        }
    }

    private void apply(Bindings next) {
        Bindings was = applied;
        boolean first = !started;
        started = true;
        applied = next;
        List<String> redone = List.of();
        if (first) {
            connectors.adopt(next);                                 // the environment gave the connectors these kinds at start
        } else {
            redone = connectors.bindings(next);
        }
        router.bind(next.readOrder());
        if (!was.equals(next)) {
            LOG.info("data bindings applied from {}: {} kinds bound{}", file.path(), next.byKind().size(),
                    redone.isEmpty() ? "" : "; connectors applied again: " + redone);
        }
    }

    /** The functions switched off by an unserved need, with the reason (the registry asks this; lock-free). */
    public Optional<String> offReason(String function) {
        return Optional.ofNullable(last.off().get(function));
    }

    /** The bindings as applied. */
    public Bindings applied() {
        return applied;
    }

    // -- resolution --------------------------------------------------------------------------------------------------

    /** Every kind's resolution; {@code fresh} also reads each needed kind's newest date (for STALE): the page, not the gate. */
    public BindingResolver.Result resolve(boolean fresh) {
        DataNeeds needs = allNeeds.of(moduleOn);
        Set<String> needed = new LinkedHashSet<>();
        needs.needs().forEach(n -> needed.add(n.kind()));
        LocalDate today = LocalDate.now(clock);
        BindingResolver.Facts facts = new BindingResolver.Facts() {
            @Override
            public Set<String> kinds() {
                Set<String> out = new LinkedHashSet<>();
                packs.packs().forEach(p -> out.addAll(p.kinds()));
                return out;
            }

            @Override
            public List<BindingResolver.Source> sources(String kind) {
                return router.serving(kind).stream().map(s -> new BindingResolver.Source(s.name(), s.plugin(), s.lake(), s.demo(), s.computed())).toList();
            }

            @Override
            public Set<String> connectors() {
                return connectors.names();
            }

            @Override
            public Optional<String> defaultRoute(String kind) {
                return packs.packs().stream().map(p -> p.routes().get(kind)).filter(r -> r != null && !r.isBlank()).findFirst();
            }

            @Override
            public Optional<LocalDate> latest(String kind) {
                if (!fresh || !needed.contains(kind)) {
                    return Optional.empty();
                }
                try {
                    return router.latestDate(kind);
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }

            @Override
            public Optional<String> cadence(String kind) {
                return Optional.ofNullable(contract(kind).get("cadence") instanceof Map<?, ?> c ? c.get("frequency") : null).map(String::valueOf);
            }

            @Override
            public Optional<String> hint(String kind) {
                return Optional.ofNullable(contract(kind).get("binding-hint") instanceof Map<?, ?> h ? h.get("lake-table") : null).map(String::valueOf);
            }

            @Override
            public boolean demoEnabled() {
                return demoEnabled.getAsBoolean();
            }

            @Override
            public LocalDate today() {
                return today;
            }
        };
        return BindingResolver.resolve(applied, needs, facts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contract(String kind) {
        return contracts.computeIfAbsent(kind, k -> {
            for (Pack p : packs.packs()) {
                Path f = p.resolve("contracts/" + k + ".contract.yaml");
                if (Files.isRegularFile(f)) {
                    try {
                        return YAML.readValue(f.toFile(), LinkedHashMap.class);
                    } catch (IOException e) {
                        return Map.of();
                    }
                }
            }
            return Map.of();
        });
    }

    /** The answer of {@code GET /api/v1/admin/bindings}: every kind, the counts, what is off, the problems, the proposals waiting. */
    public Map<String, Object> status() {
        refresh();
        BindingResolver.Result r = resolve(true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", file.path().toString());
        out.put("etag", file.etag());
        out.put("fileProblem", fileProblem);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (com.ash.drishti.kernel.api.data.BindingState s : com.ash.drishti.kernel.api.data.BindingState.values()) {
            counts.put(s.name(), r.count(s));
        }
        out.put("counts", counts);
        out.put("unboundRequired", r.kinds().values().stream().filter(k -> k.required() && !k.state().usable()).map(BindingResolver.Resolution::kind).toList());
        out.put("off", r.off());
        out.put("kinds", r.kinds().values().stream().map(BindingResolver.Resolution::toMap).toList());
        out.put("problems", r.problems());
        out.put("deprecated", deprecated());
        out.put("connectors", connectors.names());
        out.put("proposals", proposals().stream().filter(p -> "proposed".equals(p.state())).toList());
        return out;
    }

    /** The deprecation lines (section 5.4): connector files that still list {@code kinds:}, read this release only. */
    public List<String> deprecated() {
        List<String> out = new ArrayList<>();
        connectors.deprecatedKinds().forEach((n, k) -> out.add(n + ".yaml lists kinds: " + k + ": read this release only; "
                + (file.exists() ? "the binding in " + file.path().getFileName() + " is the one home, remove the list" : "the next start writes " + file.path().getFileName() + " from them")));
        return out;
    }

    /** The figures Health shows under {@code bindings}. */
    public Map<String, Object> health() {
        BindingResolver.Result r = last;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", file.exists() ? file.path().toString() : null);
        m.put("bound", r.count(com.ash.drishti.kernel.api.data.BindingState.BOUND));
        m.put("auto", r.count(com.ash.drishti.kernel.api.data.BindingState.AUTO));
        m.put("demo", r.count(com.ash.drishti.kernel.api.data.BindingState.DEMO));
        m.put("computed", r.count(com.ash.drishti.kernel.api.data.BindingState.COMPUTED));
        m.put("unboundRequired", r.kinds().values().stream().filter(k -> k.required() && !k.state().usable()).map(k -> k.kind() + ": " + k.reason()).toList());
        m.put("functionsOff", r.off());
        m.put("problems", fileProblem == null ? r.problems() : java.util.stream.Stream.concat(java.util.stream.Stream.of(fileProblem), r.problems().stream()).toList());
        m.put("deprecated", deprecated());
        return m;
    }

    // -- four eyes ---------------------------------------------------------------------------------------------------

    /**
     * Proposes {@code kind}'s binding ({@code value} null removes it). Refused (DRS-1029) for a computed kind, a connector no file defines,
     * anything like a host or a credential, demo and real together; nothing changes until a second administrator approves.
     */
    public Proposal propose(String kind, Map<String, Object> value, String reason, String by) {
        if (reason == null || reason.isBlank()) {
            throw new DrishtiException(ErrorCode.BINDING_REFUSED, "say why the binding changes (the reason is kept in the audit log)");
        }
        String k = kind == null ? "" : kind.trim();
        if (allNeeds.computed().containsKey(k) || router.serving(k).stream().anyMatch(SourceRouter.Serving::computed)) {
            throw new DrishtiException(ErrorCode.BINDING_REFUSED, k + " is computed by a module: it is not bound to a connector");
        }
        Bindings.Binding b = null;
        if (value != null) {
            try {
                b = Bindings.binding(k, value);
            } catch (Bindings.Invalid e) {
                throw new DrishtiException(ErrorCode.BINDING_REFUSED, e.getMessage());
            }
            Set<String> defined = connectors.names();
            for (String c : b.connectors()) {
                if (!defined.contains(c)) {
                    throw new DrishtiException(ErrorCode.BINDING_REFUSED, k + ": no connector file defines '" + c + "' (Admin → Connectors)");
                }
            }
        }
        lock.lock();
        try {
            List<Proposal> all = new ArrayList<>(proposals());
            if (all.stream().anyMatch(p -> "proposed".equals(p.state()) && p.kind().equals(k))) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "a change of " + k + "'s binding already waits for a decision");
            }
            Map<String, Object> was = applied.of(k).map(Bindings.Binding::toMap).orElse(null);
            Proposal p = new Proposal(HexFormat.of().formatHex(RANDOM.generateSeed(6)), k, b == null ? null : b.toMap(), was, reason.strip(), by,
                    clock.instant().toString(), "proposed", null, null, null);
            all.add(p);
            save(all);
            audit.record(by, "binding-proposed", "binding/" + k, (b == null ? "remove" : String.valueOf(b.toMap())) + " | " + reason.strip());
            return p;
        } finally {
            lock.unlock();
        }
    }

    /** A second administrator decides; an approval writes {@code bindings.yaml} (the old one kept as {@code .bak}) and applies it at once. */
    public Proposal decide(String id, boolean approve, String comment, String by) {
        lock.lock();
        try {
            List<Proposal> all = new ArrayList<>(proposals());
            int i = index(all, id);
            Proposal p = all.get(i);
            if (!"proposed".equals(p.state())) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "the proposal is already " + p.state());
            }
            if (p.proposedBy().equals(by)) {
                throw new DrishtiException(ErrorCode.FOUR_EYES, "a second administrator decides a binding change (four eyes); you may withdraw it");
            }
            Proposal d = p.decided(approve ? "approved" : "rejected", by, clock.instant().toString(), comment == null ? "" : comment.strip());
            if (approve) {
                Bindings current = file.read();
                Bindings next = current.with(p.kind(), p.binding() == null ? null : Bindings.binding(p.kind(), p.binding()));
                file.write(next);
                appliedEtag = null;                                    // read and applied again below
            }
            all.set(i, d);
            save(all);
            audit.record(by, approve ? "binding-approved" : "binding-rejected", "binding/" + p.kind(),
                    (p.binding() == null ? "remove" : String.valueOf(p.binding())) + " | proposed by " + p.proposedBy() + " | " + d.comment());
        } finally {
            lock.unlock();
        }
        refresh();
        return proposals().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    /** The proposer takes it back. */
    public Proposal withdraw(String id, String by) {
        lock.lock();
        try {
            List<Proposal> all = new ArrayList<>(proposals());
            int i = index(all, id);
            Proposal p = all.get(i);
            if (!"proposed".equals(p.state()) || !p.proposedBy().equals(by)) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "only its proposer withdraws a proposal that still waits");
            }
            Proposal d = p.decided("withdrawn", by, clock.instant().toString(), "");
            all.set(i, d);
            save(all);
            audit.record(by, "binding-withdrawn", "binding/" + p.kind(), "");
            return d;
        } finally {
            lock.unlock();
        }
    }

    private static int index(List<Proposal> all, String id) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no binding proposal " + id);
    }

    /** Every proposal, oldest first. */
    public List<Proposal> proposals() {
        try {
            if (!Files.isRegularFile(proposalsFile)) {
                return List.of();
            }
            return JSON.readValue(proposalsFile.toFile(), new TypeReference<List<Proposal>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void save(List<Proposal> all) {
        try {
            Files.createDirectories(proposalsFile.toAbsolutePath().getParent());
            List<Proposal> keep = all.size() > 500 ? all.subList(all.size() - 500, all.size()) : all;
            Path tmp = proposalsFile.resolveSibling(proposalsFile.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(keep), StandardCharsets.UTF_8);
            Files.move(tmp, proposalsFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

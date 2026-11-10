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
package com.ash.drishti.server.modules.deploy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.ModuleDiscovery;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.module.runtime.RouteTable;
import com.ash.drishti.module.runtime.placement.ModuleParts;
import com.ash.drishti.module.runtime.placement.NodeView;
import com.ash.drishti.module.runtime.placement.PlacementResolver;
import com.ash.drishti.module.tool.ModuleConformance;
import com.ash.drishti.server.placement.PlacementService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Module deployment, cluster-first (KERNEL_AND_MODULES.md sections 4.11.4, 4.11.8, 7.6 and 12): one module store per cluster (a {@code .dmod}
 * uploaded once, checked with the fifteen conformance checks and kept by its id and version, never rebuilt), plans that say per node what
 * happens (following the placement: a node where no part is placed receives nothing), four eyes, then the apply this node coordinates:
 * <ul>
 *   <li>install and enable: phase 1 stages the package on every placed node (each checks the SHA-256 and runs V1–V9 itself), phase 2
 *       activates them together; any refusal or failure rolls back every node to what it ran before;</li>
 *   <li>upgrade (rolling): staged everywhere first, then activated node by node, the old version serving on the others until each is
 *       switched; a failure puts the switched nodes back to the old version;</li>
 *   <li>remove and disable: every placed node unloads it (remove: 410 tombstones, the files kept for a reinstall); every node records it,
 *       so saved work that used it is marked <i>needs module X</i>.</li>
 * </ul>
 * Every node finally records the cluster's word in its registry. Each step is audited.
 */
public final class ModuleDeployService {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleDeployService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The actions. */
    public static final Set<String> ACTIONS = Set.of("install", "upgrade", "remove", "disable", "enable");

    /** One node's line of a deployment: what happens there and what happened. */
    public record NodeStep(String node, boolean placed, String now, String after, String action, String status, String detail, String at) {

        NodeStep with(String s, String d, String when) {
            return new NodeStep(node, placed, now, after, action, s, d, when);
        }
    }

    /**
     * A deployment: the plan (proposed), then its outcome. {@code state}: proposed, applied, failed (rolled back), rejected or withdrawn.
     */
    public record Deployment(String id, String action, String module, String version, String previous, String sha, String fingerprint,
            String reason, String proposedBy, String proposedAt, String state, List<NodeStep> nodes, List<String> checks, List<String> warnings,
            String decidedBy, String decidedAt, String outcome) {

        Deployment with(String s, List<NodeStep> n, String by, String at, String out) {
            return new Deployment(id, action, module, version, previous, sha, fingerprint, reason, proposedBy, proposedAt, s, n, checks, warnings,
                    by, at, out);
        }
    }

    /** What the store holds of one version. */
    public record Stored(String id, String version, String title, String about, String publisher, String sha, long bytes, List<String> checks,
            List<String> parts, String storedBy, String storedAt) {
    }

    private final Path store;
    private final Path deploymentsFile;
    private final ModuleNode node;
    private final PlacementService placement;
    private final NodeCalls calls;
    private final AuditLog audit;
    private final Clock clock;
    private final int cycles;
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantLock applying = new ReentrantLock();

    public ModuleDeployService(Path root, ModuleNode node, PlacementService placement, NodeCalls calls, AuditLog audit, Clock clock, int cycles) {
        this.store = root.resolve("store");
        this.deploymentsFile = root.resolve("deployments.json");
        this.node = node;
        this.placement = placement;
        this.calls = calls;
        this.audit = audit;
        this.clock = clock;
        this.cycles = cycles;
    }

    // ---- the store -----------------------------------------------------------------------------------------------------------------

    /**
     * Stores a package once: unpacked, its hash list checked and the fifteen conformance checks run (C15 with {@code cycles} load/unload
     * cycles); refused with every failed check in words. The same version again is accepted only with the same bytes.
     */
    /**
     * The distribution's own modules (KERNEL_AND_MODULES.md section 3.3): each verified first-party package is put in the store as the
     * product's build left it (its conformance checks ran in that build; a rebuild of the same version replaces the stored copy, which an
     * upload never may), then installed on this node unless an administrator removed or switched it off. Returns a line per module.
     */
    public List<String> installShipped(List<Path> shipped, String by) {
        com.ash.drishti.module.runtime.FirstParty list = com.ash.drishti.module.runtime.KernelFacts.ofKernel().firstParty();
        List<Path> verified = new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Path dmod : shipped) {
            Path work = store.resolve(".shipped-" + UUID.randomUUID().toString().substring(0, 8));
            try {
                Path dir = work.resolve("pkg");
                Dmod.unpack(dmod, dir);
                DmodManifest m;
                try (InputStream in = Files.newInputStream(dir.resolve(DmodManifest.FILE))) {
                    m = DmodManifest.read(in, dmod.getFileName().toString());
                }
                Dmod.verify(dir, dmod, m.label());
                com.ash.drishti.module.runtime.FirstParty.Origin origin = list.origin(m, dir);
                if (!origin.firstParty()) {
                    out.add(m.label() + ": not installed by default: " + origin.why());
                    continue;
                }
                String sha = sha(dmod);
                Path at = store.resolve(m.id()).resolve(m.version());
                Path meta = at.resolve("stored.json");
                boolean same = Files.isRegularFile(meta) && JSON.readValue(meta.toFile(), Stored.class).sha().equalsIgnoreCase(sha);
                if (!same) {
                    keep(dmod, dir, m, sha, Files.size(dmod), List.of("the fifteen conformance checks ran in the product's build (first-party, "
                            + origin.why() + ")"), by, "the distribution's");
                }
                verified.add(dmod);
            } catch (IOException | RuntimeException e) {
                out.add(dmod.getFileName() + ": not installed by default: " + e.getMessage());
            } finally {
                ModuleNode.delete(work);
            }
        }
        out.addAll(node.installShipped(verified));
        return out;
    }

    /** Puts a verified package and its unpacked folder in the store ({@code <id>/<version>/}), with its record; audited. */
    private Stored keep(Path dmod, Path dir, DmodManifest m, String sha, long bytes, List<String> lines, String by, String note) throws IOException {
        Path at = store.resolve(m.id()).resolve(m.version());
        Files.createDirectories(at);
        Files.copy(dmod, at.resolve(Dmod.fileName(m.id(), m.version())), StandardCopyOption.REPLACE_EXISTING);
        ModuleNode.delete(at.resolve("pkg"));
        Files.move(dir, at.resolve("pkg"));
        Stored s = new Stored(m.id(), m.version(), m.title(), m.about(), m.publisher().getOrDefault("name", ""), sha, bytes, lines,
                ModuleParts.of(m).parts().keySet().stream().toList(), by, clock.instant().toString());
        JSON.writerWithDefaultPrettyPrinter().writeValue(at.resolve("stored.json").toFile(), s);
        audit.record(by, "module-stored", "module/" + m.id(), m.version() + " | sha256 " + sha + " | " + note);
        return s;
    }

    public Stored upload(byte[] bytes, String by) {
        if (bytes == null || bytes.length == 0) {
            throw ModuleNode.refused("no package: send the .dmod's bytes");
        }
        Path work = store.resolve(".incoming-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(work);
            Path dmod = work.resolve("upload.dmod");
            Files.write(dmod, bytes);
            Path dir = work.resolve("pkg");
            Dmod.unpack(dmod, dir);
            DmodManifest m;
            try (InputStream in = Files.newInputStream(dir.resolve(DmodManifest.FILE))) {
                m = DmodManifest.read(in, "the upload");
            }
            Dmod.verify(dir, dmod, m.label());   // V2: refused when a file is missing, changed or not listed; the rest are notes
            String sha = sha(dmod);
            Path at = store.resolve(m.id()).resolve(m.version());
            Path meta = at.resolve("stored.json");
            if (Files.isRegularFile(meta)) {
                Stored s = JSON.readValue(meta.toFile(), Stored.class);
                if (!s.sha().equalsIgnoreCase(sha)) {
                    throw ModuleNode.refused(m.label() + " is already in the store with other bytes: a version is built once; give the new build "
                            + "a new version");
                }
                return s;
            }
            List<ModuleConformance.Check> checks = ModuleConformance.run(dmod, null, work.resolve("checks"), cycles);
            List<String> lines = checks.stream().map(ModuleConformance.Check::line).toList();
            if (!ModuleConformance.passed(checks)) {
                audit.record(by, "module-store-refused", "module/" + m.id(), m.version() + " | " + failed(checks));
                throw ModuleNode.refused(m.label() + " fails the conformance checks: " + failed(checks));
            }
            return keep(dmod, dir, m, sha, bytes.length, lines, by, bytes.length + " bytes");
        } catch (IOException e) {
            throw ModuleNode.refused("the package does not read: " + e.getMessage());
        } catch (com.ash.drishti.module.runtime.ModuleRefusedException e) {
            throw ModuleNode.refused(e.check() + " " + e.getMessage());
        } finally {
            ModuleNode.delete(work);
        }
    }

    private static String sha(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            return Dmod.sha256(in);
        }
    }

    private static String failed(List<ModuleConformance.Check> checks) {
        return String.join("; ", checks.stream().filter(c -> c.status() != ModuleConformance.Status.PASS).map(ModuleConformance.Check::line).toList());
    }

    /** Every stored version, newest first per module. */
    public List<Stored> stored() {
        List<Stored> out = new ArrayList<>();
        if (!Files.isDirectory(store)) {
            return out;
        }
        try (var ids = Files.list(store)) {
            for (Path id : ids.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).sorted().toList()) {
                try (var vs = Files.list(id)) {
                    for (Path v : vs.filter(p -> Files.isRegularFile(p.resolve("stored.json"))).toList()) {
                        out.add(JSON.readValue(v.resolve("stored.json").toFile(), Stored.class));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort((a, b) -> a.id().equals(b.id()) ? com.ash.drishti.module.runtime.VersionRange.compare(b.version(), a.version())
                : a.id().compareTo(b.id()));
        return out;
    }

    private Path dmod(String id, String version) {
        Path p = store.resolve(ModuleNode.safe(id)).resolve(ModuleNode.safe(version)).resolve(Dmod.fileName(id, version));
        if (!Files.isRegularFile(p)) {
            throw ModuleNode.refused(id + " " + version + " is not in the module store: upload its .dmod first");
        }
        return p;
    }

    private Stored storedOf(String id, String version) {
        try {
            dmod(id, version);
            return JSON.readValue(store.resolve(id).resolve(version).resolve("stored.json").toFile(), Stored.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private DmodManifest manifestOf(String id, String version) {
        try (InputStream in = Files.newInputStream(store.resolve(id).resolve(version).resolve("pkg").resolve(DmodManifest.FILE))) {
            return DmodManifest.read(in, id + " " + version);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- plans ---------------------------------------------------------------------------------------------------------------------

    /** The nodes where a part of the module is placed (by the current placement, with the module's own parts). */
    List<String> placedNodes(DmodManifest m) {
        List<ModuleParts> mods = new ArrayList<>(placement.modules().stream().filter(x -> !x.module().equals(m.id())).toList());
        mods.add(ModuleParts.of(m));
        PlacementResolver.Matrix mx = PlacementResolver.resolve(placement.withDefaults(placement.current(), mods), mods, placement.matrix().nodes());
        Set<String> out = new LinkedHashSet<>();
        mx.parts().stream().filter(p -> p.module().equals(m.id())).forEach(p -> out.addAll(p.placed()));
        return List.copyOf(out);
    }

    /**
     * Plans an action on a module: which nodes receive it and what each does, the store's checks, the warnings; changes nothing. The plan
     * waits for a second administrator, who applies exactly it ({@code DRS-1031} when the cluster changed since).
     */
    public Deployment plan(String action, String id, String version, String reason, String by) {
        String act = action == null ? "" : action.toLowerCase(Locale.ROOT).strip();
        if (!ACTIONS.contains(act)) {
            throw ModuleNode.refused("the action is one of " + new java.util.TreeSet<>(ACTIONS) + ", not " + action);
        }
        if (reason == null || reason.isBlank()) {
            throw ModuleNode.refused("say why (the reason is kept in the audit log)");
        }
        ModuleNode.safe(id);
        ModuleNode.Entry now = node.entry(id).orElse(null);
        String v = version == null || version.isBlank() ? null : version.strip();
        List<String> warnings = new ArrayList<>();
        List<String> checks = List.of();
        DmodManifest m;
        String sha = null;
        String previous = now == null ? null : now.version();
        switch (act) {
            case "install", "upgrade", "enable" -> {
                if (act.equals("enable")) {
                    if (now == null || now.state() != ModuleNode.State.OFF) {
                        throw ModuleNode.refused(id + " is not switched off (" + (now == null ? "not deployed" : now.state().name().toLowerCase(Locale.ROOT)) + ")");
                    }
                    v = now.version();
                    previous = null;
                }
                if (v == null) {
                    v = stored().stream().filter(s -> s.id().equals(id)).map(Stored::version).findFirst()
                            .orElseThrow(() -> ModuleNode.refused(id + " is not in the module store: upload its .dmod first"));
                }
                Stored s = storedOf(id, v);
                sha = s.sha();
                checks = s.checks();
                m = manifestOf(id, v);
                if (now != null && now.state() == ModuleNode.State.ACTIVE) {
                    if (now.version().equals(v)) {
                        throw ModuleNode.refused(id + " " + v + " is already active");
                    }
                    act = "upgrade";
                } else if (!act.equals("enable")) {
                    act = "install";
                    previous = null;
                }
            }
            default -> {
                if (now == null || now.state() == ModuleNode.State.REMOVED || (act.equals("disable") && now.state() != ModuleNode.State.ACTIVE)) {
                    throw ModuleNode.refused(id + " is " + (now == null ? "not deployed" : now.state().name().toLowerCase(Locale.ROOT)) + ": nothing to " + act);
                }
                v = now.version();
                m = manifestOf(id, v);
                sha = storedOf(id, v).sha();
            }
        }
        List<String> placed = placedNodes(m);
        if (placed.isEmpty() && !act.equals("remove") && !act.equals("disable")) {
            throw ModuleNode.refused(id + " is placed on no node: place its parts first (Admin → Cluster → Modules, config/placement.yaml)");
        }
        List<NodeStep> steps = new ArrayList<>();
        for (NodeView n : placement.matrix().nodes()) {
            boolean here = placed.contains(n.id());
            String before = now == null || now.state() != ModuleNode.State.ACTIVE || !here ? "-" : now.version();
            String words = switch (act) {
                case "install", "enable" -> here ? "stage " + v + ", activate with the others" : "nothing (not placed): forwards its requests";
                case "upgrade" -> here ? "stage " + v + ", then switch " + previous + " → " + v + " in turn" : "nothing (not placed)";
                case "remove" -> here ? "unload, 410 for its paths, files kept for a reinstall" : "nothing loaded: stops forwarding, answers 410";
                default -> here ? "unload, 404 for its paths" : "nothing loaded: stops forwarding";
            };
            String after = switch (act) {
                case "remove" -> "removed";
                case "disable" -> "off";
                default -> here ? v : "-";
            };
            if (!n.up()) {
                warnings.add(n.id() + " is down" + (here ? ": the apply fails on it unless it is back" : ""));
            }
            steps.add(new NodeStep(n.id(), here, before, after, words, "planned", null, null));
        }
        if (act.equals("upgrade")) {
            warnings.add("rolling: " + String.join(", ", placed) + " switch one at a time; the others serve " + previous + " meanwhile");
        }
        Deployment d = new Deployment("dpl-" + UUID.randomUUID().toString().substring(0, 8), act, id, v, previous, sha, fingerprint(), reason.strip(),
                by, clock.instant().toString(), "proposed", steps, checks, warnings, null, null, null);
        lock.lock();
        try {
            List<Deployment> all = new ArrayList<>(deployments());
            all.add(d);
            save(all);
        } finally {
            lock.unlock();
        }
        audit.record(by, "module-deploy-planned", "module/" + id, d.id() + " | " + act + " " + v + " | " + reason.strip());
        return d;
    }

    /** The cluster a plan is computed for: placement's fingerprint and the module registry. */
    public String fingerprint() {
        StringBuilder s = new StringBuilder(placement.fingerprint());
        node.entries().forEach(e -> s.append('|').append(e.id()).append('@').append(e.version()).append(':').append(e.state()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- apply ---------------------------------------------------------------------------------------------------------------------

    /** Applies a plan: a second administrator (four eyes), the cluster unchanged since the plan; then runs it (see the class). */
    public Deployment apply(String id, String comment, String by) {
        if (!applying.tryLock()) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "another deployment is being applied: wait for it");
        }
        try {
            Deployment d = find(id);
            if (!"proposed".equals(d.state())) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "the deployment is already " + d.state());
            }
            if (d.proposedBy().equals(by)) {
                throw new DrishtiException(ErrorCode.FOUR_EYES, "a second administrator applies a deployment (four eyes): " + by + " planned it");
            }
            if (!d.fingerprint().equals(fingerprint())) {
                throw new DrishtiException(ErrorCode.PLACEMENT_STALE, "the cluster changed since the plan (a member, its labels, the placement or a "
                        + "module): plan again");
            }
            audit.record(by, "module-deploy-applying", "module/" + d.module(), d.id() + " | " + d.action() + " " + d.version() + " | planned by "
                    + d.proposedBy() + (comment == null || comment.isBlank() ? "" : " | " + comment.strip()));
            Deployment done;
            try {
                done = switch (d.action()) {
                    case "install", "enable" -> together(d, by);
                    case "upgrade" -> rolling(d, by);
                    default -> leave(d, by);
                };
            } catch (RuntimeException e) {
                LOG.warn("deployment {} failed: {}", d.id(), e.toString());
                done = d.with("failed", d.nodes(), by, now(), "failed: " + e.getMessage());
            }
            update(done);
            audit.record(by, "module-deploy-" + done.state(), "module/" + d.module(), d.id() + " | " + done.outcome());
            return done;
        } finally {
            applying.unlock();
        }
    }

    /** Install and enable: stage on every placed node, then activate them together; any failure rolls every node back. */
    private Deployment together(Deployment d, String by) {
        List<NodeStep> steps = new ArrayList<>(d.nodes());
        Path pkg = dmod(d.module(), d.version());
        String failure = stageAll(d, steps, pkg);
        if (failure != null) {
            return d.with("failed", steps, by, now(), "rolled back: " + failure + "; nothing changed on any node");
        }
        List<Integer> activated = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            NodeStep s = steps.get(i);
            if (!s.placed()) {
                continue;
            }
            NodeCalls.Answer a = calls.activate(s.node(), d.id(), d.module(), d.version());
            if (!a.ok()) {
                steps.set(i, s.with("failed", a.words(), now()));
                failure = s.node() + ": " + a.words();
                break;
            }
            steps.set(i, s.with("activated", "loaded " + d.version(), now()));
            activated.add(i);
        }
        if (failure != null) {
            rollBack(d, steps, activated, null);
            abortAll(d, steps);
            return d.with("failed", steps, by, now(), "rolled back on every node: " + failure);
        }
        recordAll(d, ModuleNode.State.ACTIVE, by, steps);
        return d.with("applied", steps, by, now(), d.action() + "ed " + d.version() + " on " + placedList(steps));
    }

    /** Upgrade: staged everywhere first, then switched node by node; the others serve the old version until their turn. */
    private Deployment rolling(Deployment d, String by) {
        List<NodeStep> steps = new ArrayList<>(d.nodes());
        Path pkg = dmod(d.module(), d.version());
        String failure = stageAll(d, steps, pkg);
        if (failure != null) {
            return d.with("failed", steps, by, now(), "rolled back: " + failure + "; every node still runs " + d.previous());
        }
        List<Integer> switched = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            NodeStep s = steps.get(i);
            if (!s.placed()) {
                continue;
            }
            NodeCalls.Answer a = calls.activate(s.node(), d.id(), d.module(), d.version());
            if (!a.ok()) {
                steps.set(i, s.with("failed", a.words(), now()));
                failure = s.node() + ": " + a.words();
                break;
            }
            steps.set(i, s.with("activated", d.previous() + " → " + d.version(), now()));
            switched.add(i);
        }
        if (failure != null) {
            rollBack(d, steps, switched, d.previous());
            abortAll(d, steps);
            return d.with("failed", steps, by, now(), "rolled back to " + d.previous() + " on every node: " + failure);
        }
        recordAll(d, ModuleNode.State.ACTIVE, by, steps);
        return d.with("applied", steps, by, now(), "upgraded " + d.previous() + " → " + d.version() + " on " + placedList(steps) + ", one at a time");
    }

    /** Remove and disable: every node takes it off (placed nodes unload it), then every node records it. */
    private Deployment leave(Deployment d, String by) {
        List<NodeStep> steps = new ArrayList<>(d.nodes());
        boolean remove = d.action().equals("remove");
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            NodeStep s = steps.get(i);
            NodeCalls.Answer a = calls.unload(s.node(), d.module(), remove ? ModuleRuntime.Leave.REMOVE : ModuleRuntime.Leave.DISABLE);
            if (!a.ok()) {
                failures.add(s.node() + ": " + a.words());
                steps.set(i, s.with("failed", a.words(), now()));
            } else {
                steps.set(i, s.with(remove ? "removed" : "off", a.words(), now()));
            }
        }
        recordAll(d, remove ? ModuleNode.State.REMOVED : ModuleNode.State.OFF, by, steps);
        String out = (remove ? "removed " : "switched off ") + d.module() + " " + d.version() + " on every node"
                + (failures.isEmpty() ? "" : "; to retry: " + String.join("; ", failures));
        return d.with(failures.isEmpty() ? "applied" : "failed", steps, by, now(), out);
    }

    /** Phase 1: the package to every placed node and staged there; null when all are ready, else the first refusal (all aborted). */
    private String stageAll(Deployment d, List<NodeStep> steps, Path pkg) {
        for (int i = 0; i < steps.size(); i++) {
            NodeStep s = steps.get(i);
            if (!s.placed()) {
                continue;
            }
            NodeCalls.Answer a = calls.stage(s.node(), d.id(), d.module(), d.version(), d.sha(), pkg);
            if (!a.ok()) {
                steps.set(i, s.with("refused", a.words(), now()));
                abortAll(d, steps);
                return s.node() + " refused at stage: " + a.words();
            }
            steps.set(i, s.with("staged", a.words(), now()));
        }
        return null;
    }

    private void abortAll(Deployment d, List<NodeStep> steps) {
        steps.stream().filter(NodeStep::placed).forEach(s -> calls.abort(s.node(), d.id()));
    }

    private void rollBack(Deployment d, List<NodeStep> steps, List<Integer> done, String previous) {
        for (int i : done) {
            NodeStep s = steps.get(i);
            NodeCalls.Answer a = calls.rollback(s.node(), d.module(), d.version(), previous);
            steps.set(i, s.with("rolled back", a.words(), now()));
        }
    }

    /** Every node records the cluster's word on the module (placed or not). */
    private void recordAll(Deployment d, ModuleNode.State state, String by, List<NodeStep> steps) {
        ModuleNode.Entry e = ModuleNode.entryOf(store.resolve(d.module()).resolve(d.version()).resolve("pkg"), state, by, d.id());
        for (int i = 0; i < steps.size(); i++) {
            NodeCalls.Answer a = calls.record(steps.get(i).node(), e);
            if (!a.ok()) {
                NodeStep s = steps.get(i);
                steps.set(i, s.with(s.status(), (s.detail() == null ? "" : s.detail() + "; ") + "registry not updated: " + a.words()
                        + " (it catches up at its next deployment)", now()));
            }
        }
    }

    private static String placedList(List<NodeStep> steps) {
        return String.join(", ", steps.stream().filter(NodeStep::placed).map(NodeStep::node).toList());
    }

    // ---- decisions and the list ----------------------------------------------------------------------------------------------------

    /** Rejects (another administrator) or withdraws (its proposer) a waiting plan. */
    public Deployment close(String id, boolean withdraw, String comment, String by) {
        Deployment d = find(id);
        if (!"proposed".equals(d.state())) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "the deployment is already " + d.state());
        }
        if (withdraw != d.proposedBy().equals(by)) {
            throw new DrishtiException(ErrorCode.FOUR_EYES, withdraw ? "only its proposer withdraws a plan" : "its proposer withdraws, a second administrator rejects");
        }
        Deployment out = d.with(withdraw ? "withdrawn" : "rejected", d.nodes(), by, now(), comment == null ? "" : comment.strip());
        update(out);
        audit.record(by, withdraw ? "module-deploy-withdrawn" : "module-deploy-rejected", "module/" + d.module(), d.id());
        return out;
    }

    public Deployment find(String id) {
        return deployments().stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no deployment " + id));
    }

    /** Every deployment, oldest first (the audit list). */
    public List<Deployment> deployments() {
        try {
            return Files.isRegularFile(deploymentsFile) ? JSON.readValue(deploymentsFile.toFile(), new TypeReference<List<Deployment>>() { }) : List.of();
        } catch (IOException e) {
            LOG.warn("modules: {} does not read: {}", deploymentsFile, e.getMessage());
            return List.of();
        }
    }

    private void update(Deployment d) {
        lock.lock();
        try {
            List<Deployment> all = new ArrayList<>(deployments());
            all.replaceAll(x -> x.id().equals(d.id()) ? d : x);
            save(all);
        } finally {
            lock.unlock();
        }
    }

    private void save(List<Deployment> all) {
        try {
            Files.createDirectories(deploymentsFile.toAbsolutePath().getParent());
            List<Deployment> keep = all.size() > 500 ? all.subList(all.size() - 500, all.size()) : all;
            Path tmp = deploymentsFile.resolveSibling("deployments.json.tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), keep);
            Files.move(tmp, deploymentsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The Admin → Modules list: every module of the registry (with the store's versions, the nodes it is placed on and this node's
     * Health row), the store, and the deployments waiting for a second administrator.
     */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> here = node.status();
        List<Map<String, Object>> mods = new ArrayList<>();
        List<Stored> st = stored();
        Set<String> ids = new java.util.TreeSet<>();
        node.entries().forEach(e -> ids.add(e.id()));
        st.forEach(s -> ids.add(s.id()));
        for (String id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            ModuleNode.Entry e = node.entry(id).orElse(null);
            m.put("id", id);
            m.put("version", e == null ? null : e.version());
            m.put("state", e == null ? "stored" : e.state().name().toLowerCase(Locale.ROOT));
            m.put("title", e != null ? e.title() : st.stream().filter(s -> s.id().equals(id)).map(Stored::title).findFirst().orElse(id));
            m.put("prefix", e == null ? null : e.prefix());
            m.put("origin", node.origin(id).map(com.ash.drishti.module.runtime.FirstParty.Origin::word).orElse(null));
            m.put("claims", e == null ? List.of() : e.claims());
            m.put("by", e == null ? null : e.by());
            m.put("at", e == null ? null : e.at());
            m.put("deployment", e == null ? null : e.deployment());
            m.put("versions", st.stream().filter(s -> s.id().equals(id)).toList());
            try {
                String v = e != null ? e.version() : st.stream().filter(s -> s.id().equals(id)).map(Stored::version).findFirst().orElse(null);
                m.put("placed", v == null ? List.of() : placedNodes(manifestOf(id, v)));
            } catch (RuntimeException ex) {
                m.put("placed", List.of());
            }
            mods.add(m);
        }
        out.put("node", here.get("node"));
        out.put("modules", mods);
        out.put("here", here.get("modules"));
        out.put("nodes", placement.matrix().nodes().stream().map(n -> Map.of("id", n.id(), "tags", n.tags(), "up", n.up())).toList());
        out.put("waiting", deployments().stream().filter(d -> "proposed".equals(d.state())).toList());
        out.put("fingerprint", fingerprint());
        return out;
    }

    private String now() {
        return clock.instant().toString();
    }

    /** The node's registry entries of modules that are no longer active (the console's "needs module X"). */
    public List<ModuleNode.Entry> gone() {
        return node.entries().stream().filter(e -> e.state() != ModuleNode.State.ACTIVE).toList();
    }

    /** For a test: the route table of this node (the console's notice page asks it). */
    public RouteTable.Snapshot routes() {
        return node.runtime().routes().snapshot();
    }

    /** True when the store has this module at all (Admin uses it to offer a reinstall). */
    public boolean inStore(String id) {
        return stored().stream().anyMatch(s -> s.id().equals(id));
    }

    /** The active versions on this node ({@code active.yaml}). */
    public Map<String, String> activeHere() {
        return ModuleDiscovery.readActive(node.root().resolve("installed").resolve("active.yaml"));
    }
}

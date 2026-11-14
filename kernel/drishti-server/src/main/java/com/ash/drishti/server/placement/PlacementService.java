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
package com.ash.drishti.server.placement;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.module.runtime.placement.FirstLiveElection;
import com.ash.drishti.module.runtime.placement.ModuleParts;
import com.ash.drishti.module.runtime.placement.NodeView;
import com.ash.drishti.module.runtime.placement.Placement;
import com.ash.drishti.module.runtime.placement.PlacementResolver;
import com.ash.drishti.module.runtime.placement.SingletonElection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where each module part runs (KERNEL_AND_MODULES.md section 4.11, CLUSTER_GUIDE.md "Module placement"): {@code config/placement.yaml}
 * resolved against the modules the runtime loaded (told through {@link PlacementHost}, the runtime's {@code ModuleHost}) and the members of
 * Drishti*net. No all-nodes default in a cluster: a part not placed runs nowhere. On a single node (no cluster) a module the file does not
 * name is placed here by default (Health notes "single node: placed here"). A one-runner part ({@code feed}, {@code singleton}) is owned through the
 * one {@link SingletonElection} (the first live placed node by name), so every node agrees on the owner and failover never leaves the
 * placement. Today's in-process modules (banking)
 * count as placed on every node, unguarded, so their behaviour is unchanged.
 *
 * <p>A change is a plan, then an apply by a second administrator (four eyes), refused when the cluster changed since the plan; it writes the
 * file with the next version, applies at once (no restart) and is sent to every member; a member adopts only a higher version. Every step is
 * audited.
 */
public final class PlacementService {

    private static final Logger LOG = LoggerFactory.getLogger(PlacementService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One plan. {@code state}: proposed, applied, rejected or withdrawn.
     *
     * @param placement   the proposed file's text
     * @param fingerprint the cluster it was computed for (placement version, members and their labels, modules): an apply on another is refused
     * @param before      the matrix now
     * @param after       the matrix with the plan applied
     */
    public record Plan(String id, String placement, String reason, String fingerprint, String proposedBy, String proposedAt, String state,
            List<String> warnings, Map<String, Object> before, Map<String, Object> after, String decidedBy, String decidedAt, String comment) {

        Plan decided(String s, String by, String at, String why) {
            return new Plan(id, placement, reason, fingerprint, proposedBy, proposedAt, s, warnings, before, after, by, at, why);
        }
    }

    /** Where one request for a module part goes: run here, or the node to forward it to, or nowhere (the words say why). */
    public record Route(boolean here, String node, String reason) {
    }

    private final Path file;
    private final Path plansFile;
    private final Supplier<List<NodeView>> nodes;
    private final Supplier<String> self;
    private final Supplier<Collection<ModuleParts>> inProcess;
    private final AuditLog audit;
    private final Clock clock;
    private final Map<String, ModuleParts> declared = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger spread = new AtomicInteger();
    private final SingletonElection election;
    private volatile Consumer<Placement> publisher = p -> { };
    private volatile Placement current = Placement.EMPTY;
    private volatile String fileProblem;
    private volatile long seen = -1;
    private volatile java.util.function.Predicate<String> firstParty = id -> false;
    private volatile java.util.function.BooleanSupplier singleNode = () -> false;

    /**
     * Which modules are verified first-party (the module host tells): the shipped default placement puts every part of one on every node,
     * unless the site's {@code config/placement.yaml} names the module (the site's word wins).
     */
    public void firstParty(java.util.function.Predicate<String> verified) {
        this.firstParty = verified == null ? id -> false : verified;
    }

    /**
     * Whether this server is a single node (no cluster): then a module the site's file does not name is placed here by default (there is
     * nowhere else for it to run). In a cluster there is no all-nodes default.
     */
    public void singleNode(java.util.function.BooleanSupplier single) {
        this.singleNode = single == null ? () -> false : single;
    }

    /** The modules placed here only by the single-node default (the file does not name them), each noted on Health. */
    public List<String> notes() {
        if (!singleNode.getAsBoolean()) {
            return List.of();
        }
        return declared.values().stream().map(ModuleParts::module).filter(id -> !current.modules().containsKey(id) && !firstParty.test(id))
                .sorted().map(id -> id + ": single node: placed here").toList();
    }

    /** The placement with the shipped default for first-party modules the site's file does not name (every module, on a single node). */
    Placement withDefaults(Placement p) {
        return withDefaults(p, declared.values());
    }

    /** As above, for these modules (a plan's: one not loaded yet included). */
    public Placement withDefaults(Placement p, java.util.Collection<ModuleParts> modules) {
        Map<String, Map<String, Placement.Target>> all = new LinkedHashMap<>(p.modules());
        for (ModuleParts m : modules) {
            if (!all.containsKey(m.module()) && (firstParty.test(m.module()) || singleNode.getAsBoolean())) {
                Map<String, Placement.Target> parts = new LinkedHashMap<>();
                m.parts().keySet().forEach(part -> parts.put(part, new Placement.Target(List.of(), List.of("all"), 0, true)));
                all.put(m.module(), parts);
            }
        }
        return all.size() == p.modules().size() ? p : new Placement(p.format(), p.version(), all);
    }

    public PlacementService(Path file, Path plansFile, Supplier<List<NodeView>> nodes, Supplier<String> self,
            Supplier<Collection<ModuleParts>> inProcess, AuditLog audit, Clock clock) {
        this.file = file;
        this.plansFile = plansFile;
        this.nodes = nodes;
        this.self = self;
        this.inProcess = inProcess;
        this.audit = audit;
        this.clock = clock;
        this.election = new FirstLiveElection(() -> nodes.get().stream().filter(NodeView::up).map(NodeView::id).toList());
        this.election.onChange(part -> LOG.info("placement: {} is now owned by {}", part,
                election.owner(part).orElse("no node (unavailable)")));
    }

    /** The one election of one-runner parts (owner of {@code module.part}, told of every change). */
    public SingletonElection election() {
        return election;
    }

    /** Who hears of an applied placement (the members of Drishti*net). */
    public void publisher(Consumer<Placement> p) {
        this.publisher = p == null ? x -> { } : p;
    }

    /** Reads the file again when it changed (a hand edit, an apply) and adopts it; a file that does not read keeps the last good one. */
    public void refresh() {
        try {
            long stamp = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() ^ Files.size(file) : 0;
            if (stamp == seen) {
                return;
            }
            String text = stamp == 0 ? "" : Files.readString(file);
            Placement p = Placement.read(text);
            current = p;
            fileProblem = null;
            seen = stamp;
        } catch (IllegalArgumentException | IOException e) {
            fileProblem = file.getFileName() + " is not applied (the last good placement stays): " + e.getMessage();
            LOG.warn("placement: {}", fileProblem);
        }
    }

    public Placement current() {
        return current;
    }

    public Path file() {
        return file;
    }

    /** A module the runtime loaded (its parts from the manifest). */
    public void declare(ModuleParts parts) {
        declared.put(parts.module(), parts);
    }

    /** A module the runtime removed. */
    public void forget(String module) {
        declared.remove(module);
    }

    /** Every module placement knows: today's in-process ones, then those the runtime loaded. */
    public List<ModuleParts> modules() {
        Map<String, ModuleParts> all = new LinkedHashMap<>();
        inProcess.get().forEach(m -> all.put(m.module(), m));
        declared.values().stream().sorted(java.util.Comparator.comparing(ModuleParts::module)).forEach(m -> all.putIfAbsent(m.module(), m));
        return List.copyOf(all.values());
    }

    public String self() {
        return self.get();
    }

    public PlacementResolver.Matrix matrix() {
        return PlacementResolver.resolve(withDefaults(current), modules(), nodes.get(), election);
    }

    /** True when at least one part of the module is placed on this node (an in-process module always is). */
    public boolean placedHere(String module) {
        String me = self();
        return matrix().parts().stream().anyMatch(p -> p.module().equals(module) && p.placed().contains(me));
    }

    /**
     * True when the module's {@code compute} part is placed on this node, or the module declares none (its Python then goes wherever the
     * module loads): the module's Python pool is only made where its compute is.
     */
    public boolean computePartHere(String module) {
        String me = self();
        List<PlacementResolver.PartView> rows = matrix().parts().stream().filter(p -> p.module().equals(module) && p.part().equals("compute")).toList();
        return rows.isEmpty() || rows.stream().anyMatch(p -> p.placed().contains(me));
    }

    /** The module whose REST namespace is {@code prefix}, if placement knows it. */
    public Optional<ModuleParts> byPrefix(String prefix) {
        return modules().stream().filter(m -> m.prefix().equals(prefix)).findFirst();
    }

    /**
     * Where a request for {@code module.part} runs: here when the part is placed on this node (a one-runner part: when this node owns it);
     * else the owner, or one of the placed nodes that are up, in turn; else nowhere: the part is unavailable (never run on an unplaced node).
     */
    public Route route(String module, String part) {
        PlacementResolver.PartView row = matrix().part(module, part).orElse(null);
        if (row == null) {
            return new Route(false, null, module + " has no part " + part);
        }
        String me = self();
        if (row.status().equals("in-process")) {
            return new Route(true, me, "built into the server: on every node");
        }
        if (row.placed().contains(me) && row.up().contains(me) && (row.owner() == null || row.owner().equals(me))) {
            return new Route(true, me, "placed here");
        }
        if (row.owner() != null) {
            return new Route(false, row.owner(), "owned by " + row.owner());
        }
        List<String> others = row.up().stream().filter(n -> !n.equals(me)).toList();
        if (others.isEmpty()) {
            return new Route(false, null, row.status().equals("not-placed") ? module + "'s " + part + " is not placed on any node"
                    : module + "'s " + part + " is unavailable right now (none of its placed nodes is up" + (row.placed().isEmpty() ? "" : ": " + String.join(", ", row.placed())) + "); try again later");
        }
        String to = others.get(Math.floorMod(spread.getAndIncrement(), others.size()));
        return new Route(false, to, "placed on " + String.join(", ", row.placed()));
    }

    /** The answer of {@code GET /api/v1/admin/cluster/modules}: the nodes, the node × part matrix, the problems, the plans waiting. */
    public Map<String, Object> status() {
        PlacementResolver.Matrix m = matrix();
        String me = self();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("self", me);
        out.put("file", file.toString());
        out.put("version", current.version());
        out.put("fileProblem", fileProblem);
        out.put("nodes", m.nodes().stream().map(n -> Map.of("id", n.id(), "tags", n.tags(), "up", n.up(), "self", n.id().equals(me))).toList());
        out.put("parts", rows(m));
        out.put("problems", m.problems());
        out.put("notes", notes());
        out.put("placement", current.toYaml());
        out.put("plans", plans().stream().filter(p -> "proposed".equals(p.state())).toList());
        return out;
    }

    private static List<Map<String, Object>> rows(PlacementResolver.Matrix m) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlacementResolver.PartView p : m.parts()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("module", p.module());
            r.put("version", p.version());
            r.put("part", p.part());
            r.put("class", p.cls().word());
            r.put("memoryMb", p.memoryMb());
            r.put("maxNodes", p.maxNodes());
            r.put("placed", p.placed());
            r.put("up", p.up());
            r.put("owner", p.owner());
            r.put("status", p.status());
            Map<String, String> cells = new LinkedHashMap<>();
            p.cells().forEach(c -> cells.put(c.node(), c.state().name()));
            r.put("cells", cells);
            out.add(r);
        }
        return out;
    }

    /** The cluster a plan is computed for: the placement's version, every member with its labels, every module with its version. */
    public String fingerprint() {
        StringBuilder s = new StringBuilder("v").append(current.version());
        nodes.get().stream().sorted(java.util.Comparator.comparing(NodeView::id))
                .forEach(n -> s.append('|').append(n.id()).append(n.tags().stream().sorted().toList()));
        modules().forEach(m -> s.append('|').append(m.module()).append('@').append(m.version()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Plans a placement: reads it, refuses it with every reason ({@code DRS-1030}) when a heavy part goes over its max-nodes or onto every
     * node without allow-all, a part does not exist or an in-process module is named; else stores the plan (the matrix before and after)
     * for a second administrator. Nothing changes yet.
     */
    public Plan plan(String text, String reason, String by) {
        if (reason == null || reason.isBlank()) {
            throw new DrishtiException(ErrorCode.PLACEMENT_REFUSED, "say why the placement changes (the reason is kept in the audit log)");
        }
        Placement p;
        try {
            p = Placement.read(text);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.PLACEMENT_REFUSED, e.getMessage());
        }
        List<NodeView> ns = nodes.get();
        List<String> refused = PlacementResolver.refusals(p, modules(), ns);
        if (!refused.isEmpty()) {
            audit.record(by, "placement-refused", "placement", String.join("; ", refused));
            throw new DrishtiException(ErrorCode.PLACEMENT_REFUSED, "the placement is refused: " + String.join("; ", refused));
        }
        List<String> warnings = new ArrayList<>();
        java.util.Set<String> known = modules().stream().map(ModuleParts::module).collect(java.util.stream.Collectors.toSet());
        p.modules().keySet().stream().filter(id -> !known.contains(id)).forEach(id -> warnings.add(id + " is not installed: its placement waits for it"));
        Map<String, Object> before = Map.of("parts", rows(matrix()));
        PlacementResolver.Matrix afterM = PlacementResolver.resolve(withDefaults(p), modules(), ns);
        warnings.addAll(afterM.problems());
        lock.lock();
        try {
            Plan plan = new Plan("pl-" + UUID.randomUUID().toString().substring(0, 8), p.toYaml(), reason.strip(), fingerprint(), by,
                    clock.instant().toString(), "proposed", warnings, before, Map.of("parts", rows(afterM)), null, null, null);
            List<Plan> all = new ArrayList<>(plans());
            all.add(plan);
            save(all);
            audit.record(by, "placement-planned", "placement/" + plan.id(), reason.strip());
            return plan;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a plan (a second administrator): refused for its proposer ({@code DRS-2007}) and when the cluster changed since it was planned
     * ({@code DRS-1031}); writes the file with the next version (the old one kept as {@code .bak}), applies it now and sends it to every member.
     */
    public Plan apply(String id, String comment, String by) {
        Placement applied;
        Plan done;
        lock.lock();
        try {
            List<Plan> all = new ArrayList<>(plans());
            int i = find(all, id);
            Plan p = all.get(i);
            if (!"proposed".equals(p.state())) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "the plan is already " + p.state());
            }
            if (p.proposedBy().equals(by)) {
                throw new DrishtiException(ErrorCode.FOUR_EYES, "a second administrator applies a placement (four eyes): " + by + " planned it");
            }
            if (!p.fingerprint().equals(fingerprint())) {
                throw new DrishtiException(ErrorCode.PLACEMENT_STALE, "the cluster changed since the plan (a member, its labels, a module or"
                        + " the placement): plan again");
            }
            applied = Placement.read(p.placement()).withVersion(current.version() + 1);
            write(applied);
            done = p.decided("applied", by, clock.instant().toString(), comment == null ? "" : comment.strip());
            all.set(i, done);
            save(all);
            audit.record(by, "placement-applied", "placement/" + id, "version " + applied.version() + " | planned by " + p.proposedBy() + " | "
                    + p.reason());
        } finally {
            lock.unlock();
        }
        publisher.accept(applied);
        return done;
    }

    /** Rejects (another administrator) or withdraws (its proposer) a waiting plan. */
    public Plan close(String id, boolean withdraw, String comment, String by) {
        lock.lock();
        try {
            List<Plan> all = new ArrayList<>(plans());
            int i = find(all, id);
            Plan p = all.get(i);
            if (!"proposed".equals(p.state())) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "the plan is already " + p.state());
            }
            if (withdraw != p.proposedBy().equals(by)) {
                throw new DrishtiException(ErrorCode.FOUR_EYES, withdraw ? "only its proposer withdraws a plan" : "its proposer withdraws, a second administrator rejects");
            }
            Plan d = p.decided(withdraw ? "withdrawn" : "rejected", by, clock.instant().toString(), comment == null ? "" : comment.strip());
            all.set(i, d);
            save(all);
            audit.record(by, withdraw ? "placement-withdrawn" : "placement-rejected", "placement/" + id, d.comment());
            return d;
        } finally {
            lock.unlock();
        }
    }

    /** A member's placement: adopted when its version is higher than this node's (written here, applied now). True when adopted. */
    public boolean adopt(String text, String from) {
        Placement p = Placement.read(text);
        lock.lock();
        try {
            if (p.version() <= current.version()) {
                return false;
            }
            write(p);
        } finally {
            lock.unlock();
        }
        audit.record("system", "placement-adopted", "placement", "version " + p.version() + " from " + from);
        LOG.info("placement: version {} adopted from {}", p.version(), from);
        return true;
    }

    private void write(Placement p) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, p.toYaml());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            current = p;
            fileProblem = null;
            seen = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PLACEMENT_REFUSED, "placement.yaml could not be written: " + e.getMessage(), e);
        }
    }

    private static int find(List<Plan> all, String id) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no placement plan " + id);
    }

    /** Every plan, oldest first. */
    public List<Plan> plans() {
        try {
            return Files.isRegularFile(plansFile) ? JSON.readValue(plansFile.toFile(), new TypeReference<List<Plan>>() { }) : List.of();
        } catch (IOException e) {
            LOG.warn("placement: {} does not read: {}", plansFile, e.getMessage());
            return List.of();
        }
    }

    private void save(List<Plan> all) {
        try {
            Files.createDirectories(plansFile.toAbsolutePath().getParent());
            List<Plan> keep = all.size() > 200 ? all.subList(all.size() - 200, all.size()) : all;
            JSON.writerWithDefaultPrettyPrinter().writeValue(plansFile.toFile(), keep);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PLACEMENT_REFUSED, "the plan could not be kept: " + e.getMessage(), e);
        }
    }

    /** The Health figure: the version, the parts by status, the problems. */
    public Map<String, Object> health() {
        PlacementResolver.Matrix m = matrix();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", current.version());
        Map<String, Long> by = new java.util.TreeMap<>();
        m.parts().forEach(p -> by.merge(p.status(), 1L, Long::sum));
        out.put("parts", by);
        out.put("problems", m.problems());
        List<String> notes = notes();
        if (!notes.isEmpty()) {
            out.put("notes", notes);
        }
        if (fileProblem != null) {
            out.put("fileProblem", fileProblem);
        }
        return out;
    }
}

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
package com.ash.drishti.server.engines;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin, Engines over REST (docs/architecture/COMPUTE_ENGINES.md section 11), for administrators only (a personal API token never: the
 * security settings' token-never list): every engine with its state, memory, threads, needs, used by and where its switch comes from; what
 * turning one off hides; a batch of changes with one reason, checked as a whole; the History. A change for another node goes to that node,
 * signed with the cluster key ({@code POST /api/v1/net/engines}), which writes its own file; both nodes audit it.
 */
@RestController
public class EngineAdminController {

    static final int MAX_REASON = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One change: the engine and on or off. */
    public record ChangeIn(String engine, Boolean on) {}

    /**
     * A batch.
     *
     * @param reason why (required)
     * @param node the node to change (blank: this one)
     * @param changes the changes
     * @param withNeeds also turn on what the engines turned on need
     * @param withDependents also turn off the engines that need the ones turned off
     * @param expected the files' checksums the page read ({id: sha}); a file changed since is refused (DRS-1023)
     */
    public record Batch(String reason, String node, List<ChangeIn> changes, Boolean withNeeds, Boolean withDependents, Map<String, String> expected) {}

    private final EngineRegistry registry;
    private final EngineCluster cluster;
    private final Entitlements entitlements;
    private final AuditLog audit;

    public EngineAdminController(EngineRegistry registry, EngineCluster cluster, Entitlements entitlements, AuditLog audit) {
        this.registry = registry;
        this.cluster = cluster;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    /** The BLAS binding (Admin -> Engines shows its banner when auto fell back to Java); absent in a test that makes the controller itself. */
    private com.ash.drishti.server.nativelib.NativeLibraries nativeLibraries;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void nativeLibraries(com.ash.drishti.server.nativelib.NativeLibraries n) {
        this.nativeLibraries = n;
    }

    @GetMapping("/api/v1/admin/engines")
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return view();
    }

    @GetMapping("/api/v1/admin/engines/{id}/impact")
    public Map<String, Object> impact(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        EngineCatalogue.Entry e = registry.entry(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine", id);
        m.put("title", e.title());
        m.put("hides", e.hides());
        m.put("neededBy", registry.catalogue().neededBy(id));
        m.put("usedBy", registry.catalogue().usedBy(id));
        m.put("elsewhere", cluster.peers().entrySet().stream().filter(x -> "running".equals(x.getValue().get(id))).map(Map.Entry::getKey).toList());
        m.put("live", e.liveOn());
        return m;
    }

    @GetMapping("/api/v1/admin/engines/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "100") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return Map.of("node", registry.node(), "changes", registry.history().newestFirst(Math.min(limit, 1000)));
    }

    @PostMapping("/api/v1/admin/engines")
    public Map<String, Object> change(@RequestBody Batch batch, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        entitlements.requireAdmin(p);
        String node = batch == null || batch.node() == null ? "" : batch.node().strip();
        if (!node.isEmpty() && !node.equals(cluster.self()) && !"this server".equals(node)) {
            return forward(node, batch, p);
        }
        return apply(batch, p.user());
    }

    /** A change sent by another node's Admin, Engines, signed with the cluster key, for an administrator there. */
    @PostMapping("/api/v1/net/engines")
    public Map<String, Object> fromPeer(@RequestBody Batch batch, @RequestAttribute(Principal.ATTRIBUTE) Principal p, HttpServletRequest request) {
        if (!cluster.fromPeer(request)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "only another node of this cluster may send a change here, signed with the cluster key");
        }
        entitlements.requireAdmin(p);
        return apply(batch, p.user());
    }

    private Map<String, Object> apply(Batch batch, String actor) {
        List<ChangeIn> changes = batch == null || batch.changes() == null ? List.of() : batch.changes();
        String reason = batch == null || batch.reason() == null ? "" : batch.reason().strip();
        if (changes.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no changes: send {reason, changes: [{engine, on}]}");
        }
        if (reason.length() > MAX_REASON) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a reason of at most " + MAX_REASON + " characters");
        }
        List<EngineRegistry.Change> asked = new ArrayList<>();
        for (ChangeIn c : changes) {
            if (c == null || c.engine() == null || c.on() == null) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "each change is {engine, on}");
            }
            asked.add(new EngineRegistry.Change(c.engine().strip(), c.on()));
        }
        List<EngineRegistry.Outcome> out = registry.apply(asked, reason, actor, Boolean.TRUE.equals(batch.withNeeds()),
                Boolean.TRUE.equals(batch.withDependents()), batch.expected());
        Map<String, Object> m = view();
        m.put("outcomes", out);
        return m;
    }

    private Map<String, Object> forward(String node, Batch batch, Principal p) throws IOException {
        if (!cluster.peers().containsKey(node)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no node '" + node + "' is alive in this cluster; the nodes: " + cluster.nodes());
        }
        Batch local = new Batch(batch.reason(), "", batch.changes(), batch.withNeeds(), batch.withDependents(), batch.expected());
        PeerHttp.Answer a = cluster.call(node, "POST", "/api/v1/net/engines", JSON.writeValueAsBytes(local), p);
        String what = batch.changes() == null ? "" : batch.changes().stream().map(c -> c.engine() + (Boolean.TRUE.equals(c.on()) ? " on" : " off")).toList().toString();
        if (audit != null) {
            audit.record(p.user(), "engine-change-sent", "node/" + node, what + " | " + batch.reason() + " | answered " + a.status());
        }
        JsonNode body = a.body() == null || a.body().length == 0 ? JSON.createObjectNode() : JSON.readTree(a.body());
        if (a.status() >= 400) {
            ErrorCode code = a.status() == 409 ? ErrorCode.ENGINE_DEPENDENCY : a.status() == 403 ? ErrorCode.FORBIDDEN : ErrorCode.BAD_REQUEST;
            if ("DRS-1023".equals(body.path("code").asText())) {
                code = ErrorCode.ENGINE_FILE_CONFLICT;
            }
            String detail = body.path("detail").asText(new String(a.body() == null ? new byte[0] : a.body(), StandardCharsets.UTF_8));
            throw new DrishtiException(code, "node " + node + ": " + detail.replaceFirst("^DRS-\\d+ ", ""));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.convertValue(body, Map.class);
        m.put("forwardedTo", node);
        return m;
    }

    /** Every engine of this node, and the cluster's matrix. */
    Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", registry.node());
        m.put("dir", registry.files().dir().toAbsolutePath().normalize().toString());
        if (nativeLibraries != null) {
            m.put("native", Map.of("blas", nativeLibraries.describe()));   // the BLAS path of this node and of each member
        }
        long total = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EngineCatalogue.Entry e : registry.entries()) {
            Map<String, Object> r = row(e);
            total += ((Number) r.get("memoryBytes")).longValue();
            rows.add(r);
        }
        m.put("engines", rows);
        m.put("running", registry.running().size());
        m.put("installed", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("installed"))).count());
        m.put("memoryBytes", total);
        m.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("on", cluster.on());
        c.put("self", cluster.self());
        c.put("peers", cluster.peers());
        Map<String, Boolean> avail = new LinkedHashMap<>();
        registry.entries().forEach(e -> avail.put(e.id(), cluster.available(e.id())));
        c.put("available", avail);
        m.put("cluster", c);
        return m;
    }

    private Map<String, Object> row(EngineCatalogue.Entry e) {
        Map<String, Object> r = new LinkedHashMap<>();
        String id = e.id();
        ComputeEngine eng = registry.engine(id);
        EngineSwitches.Switch sw = registry.switchOf(id);
        r.put("id", id);
        r.put("title", e.title());
        r.put("about", e.about());
        r.put("installed", eng != null);
        r.put("state", registry.state(id).word());
        r.put("reason", registry.reason(id));
        r.put("on", registry.desired(id));
        r.put("liveOn", e.liveOn());
        r.put("restartNeeded", registry.restartNeeded(id));
        boolean running = registry.running(id);
        r.put("memoryBytes", running && eng != null ? eng.memoryBytes() : 0L);
        r.put("threads", eng == null ? 0 : ComputeEngine.threadsNamed(eng.threadPrefixes()));
        r.put("datesOpen", running && eng != null ? eng.datesOpen() : 0);
        r.put("inFlight", registry.inFlight(id));
        r.put("needs", e.needs());
        r.put("uses", e.uses());
        r.put("neededBy", registry.catalogue().neededBy(id));
        r.put("usedBy", registry.catalogue().usedBy(id));
        r.put("source", sw.source());
        r.put("alias", sw.alias() == null ? "" : sw.alias());
        r.put("overridden", sw.overridden());
        r.put("file", registry.files().file(e).toString());
        r.put("fileExists", Files.isRegularFile(registry.files().file(e)));
        r.put("checksum", registry.files().checksum(e));
        r.put("hides", e.hides());
        return r;
    }
}

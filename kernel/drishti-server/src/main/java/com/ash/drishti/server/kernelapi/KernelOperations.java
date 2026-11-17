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
package com.ash.drishti.server.kernelapi;

import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Operation;
import com.ash.drishti.kernel.api.services.Operations;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.server.engines.EngineCluster;
import com.ash.drishti.server.engines.EngineGateFilter;
import com.ash.drishti.server.engines.EngineOffException;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.server.modules.ModuleGateFilter;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.placement.PlacementGuardFilter;
import com.ash.drishti.server.placement.PlacementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.stereotype.Component;

/**
 * {@link Operations}: one module asks another, in process, through the doors its REST path passes (KERNEL_AND_MODULES.md section 2.3),
 * in the REST path's order and with its answers: the token's scope ({@link KernelDoors#scope}), the module and function gate
 * ({@link ModuleGateFilter#verdict}), placement ({@link PlacementGuardFilter#placed}: a part placed on another node is asked there, over
 * {@code POST /api/v1/net/modules/operation/{module}/{name}} for the same person, and a node that does not answer is the REST path's
 * {@code 503 DRS-1032}), the engine gate ({@link EngineGateFilter}'s rule: an engine that is off refuses, one running on another node is
 * asked there; the call is counted while it runs, so switching the engine off drains it), then the provider's method on a thread of its
 * own within the call's time ({@link KernelDoors#call}), its refusal mapped as the REST path maps it. Each call that runs or is refused
 * here is written to the access log. The request the caller holds already counts as a request in flight; a call is not counted again.
 */
@Component
public class KernelOperations implements Operations {

    /** The path an operation is asked on another node by (a node's call only: {@code NetCallFilter}). */
    public static final String PATH = "/api/v1/net/modules/operation/";

    private final OperationRegistry registry;
    private final KernelDoors doors;
    private final ObjectMapper json;
    private final ObjectProvider<FilterRegistrationBean<ModuleGateFilter>> moduleGate;
    private final ObjectProvider<EngineRegistry> engines;
    private final ObjectProvider<EngineCluster> engineCluster;
    private final ObjectProvider<PlacementService> placement;
    private final ObjectProvider<PeerCalls> peers;
    private final ObjectProvider<AccessLog> accessLog;

    public KernelOperations(OperationRegistry registry, KernelDoors doors, ObjectMapper json,
            ObjectProvider<FilterRegistrationBean<ModuleGateFilter>> moduleGate, ObjectProvider<EngineRegistry> engines,
            ObjectProvider<EngineCluster> engineCluster, ObjectProvider<PlacementService> placement, ObjectProvider<PeerCalls> peers,
            ObjectProvider<AccessLog> accessLog) {
        this.registry = registry;
        this.doors = doors;
        this.json = json;
        this.moduleGate = moduleGate;
        this.engines = engines;
        this.engineCluster = engineCluster;
        this.placement = placement;
        this.peers = peers;
        this.accessLog = accessLog;
    }

    @Override
    public boolean available(String module) {
        ModuleGateFilter gate = gate();
        return registry.serves(module) && (gate == null || gate.verdictOfModule(module).isEmpty());
    }

    @Override
    public <T> T of(String module, Class<T> consumerInterface) {
        if (!consumerInterface.isInterface()) {
            throw new IllegalArgumentException(consumerInterface.getName() + " is not an interface");
        }
        for (Method m : consumerInterface.getMethods()) {
            if (m.isDefault() || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getAnnotation(Operation.class) == null || m.getParameterCount() != 2 || !Person.class.equals(m.getParameterTypes()[0])) {
                throw new IllegalArgumentException(consumerInterface.getName() + "." + m.getName()
                        + " is not an operation: @Operation(\"name\") on (Person, <request record>)");
            }
        }
        Object proxy = Proxy.newProxyInstance(consumerInterface.getClassLoader(), new Class<?>[] {consumerInterface}, (self, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> "Operations[" + module + "]";
                };
            }
            Object answer = run(RequestPerson.of((Person) args[0]), module, m.getAnnotation(Operation.class).value(), args[1], false);
            return answer(answer, m.getGenericReturnType());
        });
        return consumerInterface.cast(proxy);
    }

    /**
     * An operation another node forwarded: run here only (one hop); its answer is written as JSON.
     *
     * @throws Kernel.Refused as {@link #of}'s calls refuse
     */
    Object served(RequestPerson p, String module, String name, JsonNode request) {
        return run(p, module, name, request == null || request.isNull() || request.isMissingNode() ? null : request, true);
    }

    private Object answer(Object answer, Type type) {
        if (answer == null) {
            return null;
        }
        Class<?> raw = json.constructType(type).getRawClass();
        if (raw.isInstance(answer) && !(answer instanceof JsonNode && raw != JsonNode.class)) {
            return answer;
        }
        return json.convertValue(answer, json.constructType(type));
    }

    private ModuleGateFilter gate() {
        FilterRegistrationBean<ModuleGateFilter> r = moduleGate.getIfAvailable();
        return r == null ? null : r.getFilter();
    }

    private Object run(RequestPerson p, String module, String name, Object request, boolean fromPeer) {
        Optional<OperationRegistry.Entry> found = registry.find(module, name);
        String path = found.map(OperationRegistry.Entry::restPath).orElse(null);
        String method = path == null ? "POST" : found.get().restMethod();
        String scopePath = path == null ? PATH + module + "/" + name : path;
        String outcome = "refused";
        try {
            doors.scope(p, method, scopePath);
            ModuleGateFilter gate = gate();
            if (gate != null) {
                Optional<ModuleGateFilter.Refusal> r = path == null ? gate.verdictOfModule(module) : gate.verdict(path, p.principal());
                if (r.isPresent()) {
                    throw new Kernel.Refused(r.get().status(), r.get().code(), r.get().code() + " " + r.get().detail());
                }
            }
            if (found.isEmpty()) {
                throw new Kernel.Refused(404, "DRS-1020", "the module " + module + " serves no operation " + name
                        + " here (absent, off, or not serving operations)");
            }
            PlacementService ps = placement.getIfAvailable();
            Optional<PlacementGuardFilter.Placed> placed = ps == null ? Optional.empty() : PlacementGuardFilter.placed(ps, module, path);
            if (placed.isPresent()) {
                PlacementService.Route r = ps.route(placed.get().module(), placed.get().part());
                if (!r.here()) {
                    PeerCalls pc = peers.getIfAvailable();
                    if (r.node() == null || fromPeer || pc == null || !pc.clustered()) {
                        throw new Kernel.Refused(503, "DRS-1032", PlacementGuardFilter.notHere(placed.get().module(), placed.get().part(), r, ps.self()));
                    }
                    outcome = "forwarded to " + r.node();
                    return forward(pc, r.node(), p, module, name, request,
                            () -> new Kernel.Refused(503, "DRS-1032", PlacementGuardFilter.unanswered(placed.get().module(), placed.get().part(), r.node())));
                }
            }
            EngineRegistry er = engines.getIfAvailable();
            Optional<String> engine = er == null || path == null ? Optional.empty() : er.catalogue().forPath(path).map(e -> e.id());
            AutoCloseable counted = () -> { };
            if (engine.isPresent()) {
                String id = engine.get();
                if (er.running(id)) {
                    try {
                        counted = er.enter(id);
                    } catch (EngineOffException e) {
                        throw new Kernel.Refused(404, "DRS-1020", EngineGateFilter.off(er, id));
                    }
                } else {
                    PeerCalls pc = peers.getIfAvailable();
                    EngineCluster ec = engineCluster.getIfAvailable();
                    Optional<String> node = fromPeer || pc == null || !pc.clustered() || ec == null ? Optional.empty() : ec.runs(id);
                    if (node.isEmpty()) {
                        throw new Kernel.Refused(404, "DRS-1020", EngineGateFilter.off(er, id));
                    }
                    outcome = "forwarded to " + node.get();
                    return forward(pc, node.get(), p, module, name, request,
                            () -> new Kernel.Refused(502, "DRS-5052", EngineGateFilter.unreachable(er, node.get(), id)));
                }
            }
            try {
                OperationRegistry.Entry e = found.get();
                Object answer = doors.call(p, method, scopePath, () -> invoke(e, p, request));
                outcome = "ok";
                return answer;
            } finally {
                try {
                    counted.close();
                } catch (Exception ignored) {
                    // a counter's decrement does not fail
                }
            }
        } catch (Kernel.Refused r) {
            outcome = "refused " + r.status() + " " + r.code();
            throw r;
        } finally {
            log(p, module, name, outcome);
        }
    }

    private Object invoke(OperationRegistry.Entry e, RequestPerson p, Object request) {
        Object arg = request == null ? null : json.convertValue(request, json.constructType(e.request()));
        try {
            return e.method().invoke(e.bean(), p, arg);
        } catch (InvocationTargetException x) {
            if (x.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (x.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(x.getCause());
        } catch (IllegalAccessException x) {
            throw new IllegalStateException(e.where() + " cannot be called", x);
        }
    }

    /** Asks the node that runs it, for the same person; its answer as JSON, its refusal as it gave it. */
    private JsonNode forward(PeerCalls pc, String node, RequestPerson p, String module, String name, Object request,
            Supplier<Kernel.Refused> unanswered) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("lens", p.lens());
        person.put("asOf", p.dateBox());
        person.put("knownAt", p.asOf().knownAt() == null ? null : p.asOf().knownAt().toString());
        person.put("scopes", p.scopes());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("person", person);
        body.put("request", request);
        byte[] bytes;
        try {
            bytes = json.writeValueAsBytes(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the request of " + module + ":" + name + " does not write as JSON: " + e.getOriginalMessage(), e);
        }
        String at = PATH + URLEncoder.encode(module, StandardCharsets.UTF_8) + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        PeerCalls.Answer a = pc.call(node, "POST", at, bytes, p.principal(), KernelDoors.TIMEOUT);
        if (a.status() == 0) {
            throw unanswered.get();
        }
        JsonNode tree;
        try {
            tree = a.body() == null || a.body().isBlank() ? NullNode.getInstance() : json.readTree(a.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            tree = NullNode.getInstance();
        }
        if (a.status() >= 300) {
            throw new Kernel.Refused(a.status(), tree.path("code").asText(""), tree.path("detail").asText(a.body()));
        }
        return tree;
    }

    private void log(RequestPerson p, String module, String name, String outcome) {
        AccessLog sink = accessLog.getIfAvailable();
        if (sink != null) {
            sink.record(new AccessLog.Event(Instant.now(), p.user(), "operation", module, name, outcome, p.dateBox()));
        }
    }
}

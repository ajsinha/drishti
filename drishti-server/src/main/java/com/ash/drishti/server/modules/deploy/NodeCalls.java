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
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.server.net.PeerCalls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The coordinator's calls to one node of a deployment: this node directly, any other over Drishti*net ({@code /api/v1/net/modules/…}, signed
 * with the cluster key, the package sent in chunks under the 16 MB call limit). One shape for both, so a deployment runs the same on one
 * node and on many.
 */
public final class NodeCalls {

    /** Who deployments act as between nodes. */
    static final Principal SYSTEM = new Principal("drishti-deploy", List.of());
    static final int CHUNK = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A node's answer: ok, and its words (the reason when not). */
    public record Answer(boolean ok, String words) {
    }

    private final ModuleNode self;
    private final Supplier<String> selfId;
    private final PeerCalls peers;
    private final Duration timeout;

    public NodeCalls(ModuleNode self, Supplier<String> selfId, PeerCalls peers, Duration timeout) {
        this.self = self;
        this.selfId = selfId;
        this.peers = peers;
        this.timeout = timeout;
    }

    private boolean local(String node) {
        return node.equals(selfId.get()) || peers == null || !peers.clustered();
    }

    public Answer stage(String node, String deployment, String id, String version, String sha, Path dmod) {
        if (local(node)) {
            return run(() -> {
                self.chunkFrom(deployment, dmod);
                List<String> notes = self.stage(deployment, id, version, sha);
                return "ready" + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
            });
        }
        try (InputStream in = Files.newInputStream(dmod)) {
            byte[] buf;
            int i = 0;
            do {
                buf = in.readNBytes(CHUNK);
                if (buf.length == 0 && i > 0) {
                    break;
                }
                PeerCalls.Answer a = peers.call(node, "POST", "/api/v1/net/modules/stage/" + deployment + "/chunk/" + i, buf, SYSTEM, timeout);
                if (a.status() != 200) {
                    return new Answer(false, words(a));
                }
                i++;
            } while (buf.length == CHUNK);
        } catch (IOException e) {
            return new Answer(false, "the package does not read: " + e.getMessage());
        }
        return remote(node, "/api/v1/net/modules/stage/" + deployment, Map.of("id", id, "version", version, "sha", sha));
    }

    public Answer activate(String node, String deployment, String id, String version) {
        if (local(node)) {
            return run(() -> json(self.activate(deployment, id, version)));
        }
        return remote(node, "/api/v1/net/modules/activate/" + deployment, Map.of("id", id, "version", version));
    }

    public Answer abort(String node, String deployment) {
        if (local(node)) {
            return run(() -> {
                self.abort(deployment);
                return "aborted";
            });
        }
        return remote(node, "/api/v1/net/modules/abort/" + deployment, Map.of());
    }

    public Answer rollback(String node, String id, String version, String previous) {
        if (local(node)) {
            return run(() -> json(self.rollback(id, version, previous)));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", id);
        b.put("version", version);
        b.put("previous", previous);
        return remote(node, "/api/v1/net/modules/rollback", b);
    }

    public Answer unload(String node, String id, ModuleRuntime.Leave leave) {
        if (local(node)) {
            return run(() -> json(self.unload(id, leave)));
        }
        return remote(node, "/api/v1/net/modules/unload", Map.of("id", id, "leave", leave.name()));
    }

    public Answer record(String node, ModuleNode.Entry e) {
        if (local(node)) {
            return run(() -> {
                self.record(e);
                return "recorded";
            });
        }
        return remote(node, "/api/v1/net/modules/registry", e);
    }

    private Answer remote(String node, String path, Object body) {
        try {
            PeerCalls.Answer a = peers.call(node, "POST", path, JSON.writeValueAsBytes(body), SYSTEM, timeout);
            return a.status() == 200 ? new Answer(true, a.body()) : new Answer(false, words(a));
        } catch (IOException e) {
            return new Answer(false, e.getMessage());
        }
    }

    private static String words(PeerCalls.Answer a) {
        if (a.status() == 0) {
            return "no answer (" + a.body() + ")";
        }
        try {
            JsonNode j = JSON.readTree(a.body().getBytes(StandardCharsets.UTF_8));
            String d = j.path("detail").asText("");
            return d.isEmpty() ? a.status() + " " + a.body() : d;
        } catch (IOException e) {
            return a.status() + " " + a.body();
        }
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            return String.valueOf(o);
        }
    }

    private static Answer run(Supplier<String> s) {
        try {
            return new Answer(true, s.get());
        } catch (DrishtiException e) {
            return new Answer(false, e.getMessage());
        } catch (RuntimeException e) {
            return new Answer(false, e.toString());
        }
    }
}

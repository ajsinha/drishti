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
package com.ash.drishti.compute;

import com.ash.drishti.compute.arrow.ArrowTable;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One unit of work for one worker (docs/architecture/DATA_ARCHITECTURE.md › Reactive connectors, section 6.1): its correlation id (the answer goes to it and
 * nothing else), the pool and engine, the kind and parameters (never code), the inputs as Arrow tables already read and masked as the
 * viewer, the viewer (for audit, quotas and fairness; never credentials), its class and budget, and the seed any randomness must use.
 *
 * @param id the correlation id, {@code req-...}; also the name of the request's scratch folder
 * @param pool the pool that runs it
 * @param engine the engine entry point ({@code module:Class}); null for the pool's first
 * @param kind what to compute, as the engine names it
 * @param params the parameters (JSON values)
 * @param inputs named tables, written to the scratch folder as Arrow files
 * @param viewer who asked
 * @param cls interactive, event or batch
 * @param timeout the wall-time budget; null for the pool's default
 * @param seed the seed of any random numbers (determinism)
 * @param chunk which chunk of a fanned-out request this is ({@code null} for a whole request)
 */
public record ComputeRequest(String id, String pool, String engine, String kind, Map<String, Object> params, Map<String, ArrowTable> inputs,
        Viewer viewer, RequestClass cls, Duration timeout, long seed, Chunk chunk) {

    /** Who asked: the user, their roles and their view signature (which masks applied); never a credential. */
    public record Viewer(String user, List<String> roles, String viewSignature) {
        public Viewer {
            Objects.requireNonNull(user, "user");
            roles = roles == null ? List.of() : List.copyOf(roles);
            viewSignature = viewSignature == null ? "" : viewSignature;
        }

        public static Viewer of(String user) {
            return new Viewer(user, List.of(), "");
        }
    }

    /** The place of a chunk in its fan-out: the parent request id, its index and how many chunks there are. */
    public record Chunk(String parent, int index, int of) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    public ComputeRequest {
        id = id == null || id.isBlank() ? newId() : id;
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("a request id is letters, digits, dots, hyphens and underscores, not '" + id + "'");
        }
        Objects.requireNonNull(pool, "pool");
        kind = kind == null ? "" : kind;
        params = params == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
        inputs = inputs == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        for (String n : inputs.keySet()) {
            if (!n.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("an input name is letters, digits, dots, hyphens and underscores, not '" + n + "'");
            }
        }
        Objects.requireNonNull(viewer, "viewer");
        cls = cls == null ? RequestClass.INTERACTIVE : cls;
    }

    /** A new correlation id: time-ordered, unique, safe as a folder name. */
    public static String newId() {
        byte[] r = new byte[6];
        RANDOM.nextBytes(r);
        return "req-" + Long.toString(System.currentTimeMillis(), 36) + "-" + HexFormat.of().formatHex(r);
    }

    /** A request of {@code user} for {@code kind} on {@code pool}; the rest by the builder's methods. */
    public static Builder on(String pool, String kind, String user) {
        return new Builder(pool, kind, Viewer.of(user));
    }

    /** The same request with other inputs, as chunk {@code index} of {@code of}, under a new id. */
    public ComputeRequest chunk(Map<String, ArrowTable> in, int index, int of) {
        return new ComputeRequest(id + "-c" + index, pool, engine, kind, params, in, viewer, cls, timeout, seed, new Chunk(id, index, of));
    }

    /** The same request under another id (a retry or a forward). */
    public ComputeRequest withId(String newId) {
        return new ComputeRequest(newId, pool, engine, kind, params, inputs, viewer, cls, timeout, seed, chunk);
    }

    /** Builds a request. */
    public static final class Builder {
        private final String pool;
        private final String kind;
        private Viewer viewer;
        private String id;
        private String engine;
        private final Map<String, Object> params = new LinkedHashMap<>();
        private final Map<String, ArrowTable> inputs = new LinkedHashMap<>();
        private RequestClass cls = RequestClass.INTERACTIVE;
        private Duration timeout;
        private long seed;

        private Builder(String pool, String kind, Viewer viewer) {
            this.pool = pool;
            this.kind = kind;
            this.viewer = viewer;
        }

        public Builder id(String v) {
            id = v;
            return this;
        }

        public Builder engine(String v) {
            engine = v;
            return this;
        }

        public Builder viewer(Viewer v) {
            viewer = v;
            return this;
        }

        public Builder param(String k, Object v) {
            params.put(k, v);
            return this;
        }

        public Builder params(Map<String, Object> m) {
            params.putAll(m);
            return this;
        }

        public Builder input(String name, ArrowTable t) {
            inputs.put(name, t);
            return this;
        }

        public Builder cls(RequestClass c) {
            cls = c;
            return this;
        }

        public Builder timeout(Duration d) {
            timeout = d;
            return this;
        }

        public Builder seed(long s) {
            seed = s;
            return this;
        }

        public ComputeRequest build() {
            return new ComputeRequest(id, pool, engine, kind, params, inputs, viewer, cls, timeout, seed, null);
        }
    }
}

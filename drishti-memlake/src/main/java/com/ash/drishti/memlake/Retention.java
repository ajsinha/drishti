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
package com.ash.drishti.memlake;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRegistry;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Never on disk: the guard (docs/architecture/IN_MEMORY_LAKE.md section 8). A connector whose lake is in memory ({@code mem://}) is
 * memory-only, and so is everything computed from it. One check, {@link #mayPersist}, sits in front of every place Drishti writes, and
 * refuses memory-only data unless the place is allowed: a user's download (streamed, audited) and another node of the cluster answering a
 * request over Drishti*net. Refusals say why, in words, with the contract.
 *
 * <p>One instance per server, reached by the sinks through {@link #current()} (a server without memory stores has the permissive one).
 */
public final class Retention {

    /** Where Drishti would write. */
    public enum Sink {
        LAKE("written to a lake on disk or in S3"),
        DISK_CACHE("cached on disk"),
        BI_RESULT("saved with a BI run"),
        DUCKDB_SPILL("spilled by DuckDB to a temporary file"),
        REPORT("delivered by a scheduled report"),
        EMAIL("attached to an e-mail"),
        INBOX("sent to an inbox or a share"),
        AI_PROVIDER("sent to an outside AI provider"),
        DOWNLOAD("downloaded by a user"),
        PEER_NODE("sent to another node of the cluster");

        final String words;

        Sink(String words) {
            this.words = words;
        }
    }

    private static volatile Retention current = new Retention(null, null, s -> "");

    private final SourceRegistry registry;
    private final Function<String, String> codeFor;
    private final Function<String, String> contractOfStore;
    private volatile long computedAt;
    private volatile Map<String, String> memoryKinds = Map.of();       // kind or mnemonic (lower case) → the contract, "" when none
    private final AtomicLong refusals = new AtomicLong();
    private final AtomicLong downloads = new AtomicLong();

    /**
     * @param registry the connectors (null: nothing is memory-only)
     * @param codeFor a kind's mnemonic ({@code TRD}), or null
     * @param contractOfStore a store's contract
     */
    public Retention(SourceRegistry registry, Function<String, String> codeFor, Function<String, String> contractOfStore) {
        this(registry, codeFor, contractOfStore, null);
    }

    /** A guard over given memory-only kinds (tests): kind or mnemonic, lower case, to its contract; {@code connector:<name>} for a connector. */
    static Retention of(Map<String, String> kinds) {
        return new Retention(null, null, s -> "", Map.copyOf(kinds));
    }

    private Retention(SourceRegistry registry, Function<String, String> codeFor, Function<String, String> contractOfStore, Map<String, String> fixed) {
        this.registry = registry;
        this.codeFor = codeFor;
        this.contractOfStore = contractOfStore;
        this.fixed = fixed;
    }

    private final Map<String, String> fixed;

    /** The server's guard. */
    public static Retention current() {
        return current;
    }

    /** Installs the server's guard (at start). */
    public static void install(Retention r) {
        current = r == null ? new Retention(null, null, s -> "") : r;
    }

    /** The memory-only kinds and their mnemonics, lower case, with their contracts (recomputed at most every 5 s). */
    Map<String, String> memoryKinds() {
        if (fixed != null) {
            return fixed;
        }
        if (registry == null) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        if (now - computedAt < 5_000) {
            return memoryKinds;
        }
        Map<String, String> m = new HashMap<>();
        for (String name : registry.connectorStatuses().keySet()) {
            SourcePlugin p = registry.connector(name).orElse(null);
            if (p == null || !p.memoryOnly()) {
                continue;
            }
            String root = registry.applied(name).map(c -> c.settings().getOrDefault("root", "")).orElse("");
            String contract = root.startsWith("mem://") ? contractOfStore.apply(com.ash.drishti.memlake.store.MemoryStores.storeName(root)) : "";
            for (String kind : registry.applied(name).map(c -> c.kinds()).orElse(List.of())) {
                m.put(kind.toLowerCase(Locale.ROOT), contract == null ? "" : contract);
                String code = codeFor == null ? null : codeFor.apply(kind);
                if (code != null && !code.isBlank()) {
                    m.put(code.toLowerCase(Locale.ROOT), contract == null ? "" : contract);
                }
            }
            m.put("connector:" + name.toLowerCase(Locale.ROOT), contract == null ? "" : contract);
        }
        memoryKinds = Map.copyOf(m);
        computedAt = now;
        return memoryKinds;
    }

    /** Whether a kind (or its mnemonic) is held in memory only. */
    public boolean memoryOnly(String kindOrMnemonic) {
        return kindOrMnemonic != null && memoryKinds().containsKey(kindOrMnemonic.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether a connector is memory-only. */
    public boolean memoryOnlyConnector(String connector) {
        return connector != null && memoryKinds().containsKey("connector:" + connector.trim().toLowerCase(Locale.ROOT));
    }

    /** The memory-only ones among {@code kinds} (kinds or mnemonics). */
    public Set<String> memoryOnly(Collection<String> kinds) {
        Set<String> out = new HashSet<>();
        if (kinds != null) {
            kinds.stream().filter(this::memoryOnly).forEach(out::add);
        }
        return out;
    }

    /**
     * Whether {@code text} (a BI question, its data versions, a prompt) names a memory-only kind, mnemonic or connector as a whole word. Errs
     * on the side of the contract: a question that merely mentions the word is treated as reading it (its result is then not stored).
     */
    public boolean mentions(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Map<String, String> kinds = memoryKinds();
        if (kinds.isEmpty()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : kinds.keySet()) {
            String word = k.startsWith("connector:") ? k.substring(10) : k;
            if (java.util.regex.Pattern.compile("(?<![a-z0-9_-])" + java.util.regex.Pattern.quote(word) + "(?![a-z0-9_-])").matcher(t).find()) {
                return true;
            }
        }
        return false;
    }

    /** Whether data of {@code kinds} may be written to {@code sink}: always, unless one is memory-only and the place is not allowed. */
    public boolean mayPersist(Sink sink, Collection<String> kinds) {
        return sink == Sink.DOWNLOAD || sink == Sink.PEER_NODE || memoryOnly(kinds).isEmpty();
    }

    /** As {@link #mayPersist}, refusing with words: "This view uses news data held in memory only (contract VX-1); it cannot be …". */
    public void require(Sink sink, Collection<String> kinds, String what) {
        Set<String> mem = memoryOnly(kinds);
        if (sink == Sink.DOWNLOAD) {
            if (!mem.isEmpty()) {
                downloads.incrementAndGet();
            }
            return;
        }
        if (sink == Sink.PEER_NODE || mem.isEmpty()) {
            return;
        }
        refusals.incrementAndGet();
        throw new DrishtiException(ErrorCode.MEMORY_ONLY, words(sink, mem, what));
    }

    /** The refusal in words. */
    String words(Sink sink, Set<String> mem, String what) {
        String data = String.join(", ", mem.stream().sorted().toList());
        Set<String> contracts = new HashSet<>();
        mem.forEach(k -> {
            String c = memoryKinds().getOrDefault(k.toLowerCase(Locale.ROOT), "");
            if (!c.isBlank()) {
                contracts.add(c);
            }
        });
        return (what == null || what.isBlank() ? "This" : what) + " uses " + data + " data held in memory only"
                + (contracts.isEmpty() ? "" : " (contract " + String.join(", ", contracts.stream().sorted().toList()) + ")")
                + "; it cannot be " + sink.words + ". Only a download by you may leave the server.";
    }

    /** Refusals since the start (Admin, Lakes). */
    public long refusals() {
        return refusals.get();
    }

    /** Downloads of memory-only data since the start (each also audited by its endpoint). */
    public long downloads() {
        return downloads.get();
    }
}

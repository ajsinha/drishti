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
package com.ash.drishti.server.live;

import com.ash.drishti.live.LiveRules;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Each loaded kind's live rules, read from the {@code live:} block of its contract (docs/architecture/LIVE_CONNECTORS_DESIGN.md section
 * 2.3) and checked when the packs load: every field the block names is in the contract, every value is one of its words. A kind whose
 * block has a problem takes no live feed until it is fixed, and Health lists the problem (DRS-1060). Read again whenever the loaded packs
 * change ({@link PackRegistry#version()}); the first pack that holds a kind's contract is its owner, as for every other reader of contracts.
 */
@Component
public final class LiveContracts implements com.ash.drishti.live.KindContracts {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private record Snapshot(long version, Map<String, LiveRules> rules, Map<String, Map<String, Object>> contracts, Map<String, List<String>> problems) {}

    private final PackRegistry packs;
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bindings.BindingService> bindings;
    private final Set<String> restateKinds;
    private volatile Snapshot snapshot = new Snapshot(-1, Map.of(), Map.of(), Map.of());

    public LiveContracts(PackRegistry packs, Environment env, com.ash.drishti.engine.source.SourceRegistry registry,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bindings.BindingService> bindings) {
        this.packs = packs;
        this.bindings = bindings;
        String listed = env.getProperty("drishti.live.restate-kinds", String.join(",", LiveRules.RESTATE_KINDS));
        this.restateKinds = Set.copyOf(Arrays.stream(listed.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        registry.liveContracts(this);                                    // the live connectors read their kinds' rules from here
    }

    /** The kind's contract as read; empty when no loaded pack holds one. */
    @Override
    public Map<String, Object> contract(String kind) {
        Map<String, Object> c = current().contracts().get(kind);
        return c == null ? Map.of() : c;
    }

    /**
     * The live rules of {@code kind}: its contract's block, else the defaults (a kind with no contract: arrival order, no key); an overlay
     * kind whose site binding says {@code live: {apply: both}} is landed by window too (section 2.4, decision Q7).
     */
    @Override
    public LiveRules rules(String kind) {
        LiveRules r = current().rules().get(kind);
        r = r != null ? r : LiveRules.defaults(kind, List.of(), false);
        return r.allowed() && r.apply() == LiveRules.Apply.OVERLAY && both(kind) ? r.withApply(LiveRules.Apply.BOTH) : r;
    }

    private boolean both(String kind) {
        com.ash.drishti.server.bindings.BindingService b = bindings == null ? null : bindings.getIfAvailable();
        com.ash.drishti.packs.bindings.Bindings.Binding binding = b == null ? null : b.applied().byKind().get(kind);
        return binding != null && binding.liveBoth();
    }

    /** The problems of every contract's {@code live:} block, by kind; empty when every block is good. */
    public Map<String, List<String>> problems() {
        return current().problems();
    }

    /** The kinds whose contract has a usable {@code live:} block or none, with their rules, by kind. */
    public Map<String, LiveRules> all() {
        return current().rules();
    }

    private Snapshot current() {
        long v = packs.version();
        Snapshot s = snapshot;
        if (s.version() == v) {
            return s;
        }
        synchronized (this) {
            if (snapshot.version() != v) {
                snapshot = read(v);
            }
            return snapshot;
        }
    }

    private Snapshot read(long version) {
        Map<String, LiveRules> rules = new TreeMap<>();
        Map<String, Map<String, Object>> contracts = new TreeMap<>();
        Map<String, List<String>> problems = new TreeMap<>();
        for (Pack p : packs.packs()) {
            Path dir = p.resolve("contracts");
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.contract.yaml")) {
                for (Path f : files) {
                    String kind = f.getFileName().toString().replaceFirst("\\.contract\\.yaml$", "");
                    if (rules.containsKey(kind) || problems.containsKey(kind)) {
                        continue;                                        // the first pack that holds the contract owns it
                    }
                    contract(f).ifPresent(c -> {
                        LiveRules.Parsed parsed = LiveRules.parse(c, restateKinds);
                        rules.put(kind, parsed.rules());
                        contracts.put(kind, java.util.Collections.unmodifiableMap(c));
                        if (!parsed.ok()) {
                            problems.put(kind, parsed.problems().stream().map(m -> m + " [" + f + "]").toList());
                        }
                    });
                }
            } catch (IOException e) {
                problems.put("pack " + p.name(), List.of("its contracts (" + dir + ") cannot be listed: " + e.getMessage() + " (" + LiveRules.CODE + ")"));
            }
        }
        return new Snapshot(version, Map.copyOf(rules), Map.copyOf(contracts), java.util.Collections.unmodifiableMap(new LinkedHashMap<>(problems)));
    }

    private static Optional<Map<String, Object>> contract(Path f) {
        try {
            Map<String, Object> c = YAML.readValue(f.toFile(), new TypeReference<Map<String, Object>>() {});
            return Optional.ofNullable(c);
        } catch (IOException e) {
            return Optional.empty();                                     // an unreadable contract is the contract check's to report
        }
    }
}

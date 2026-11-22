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
package com.ash.drishti.net.digest;

import com.ash.tools.codec.Hashes;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a node offers (CLUSTER_GUIDE.md › Drishti*net: many servers, one Drishti, section 6), published at {@code GET /api/v1/net/digest} and pulled by peers when the
 * {@link #hash()} gossiped with the member entry changes. The hash leaves out the load and the build time, which move all the time,
 * so a peer pulls the digest again only when what the node serves changed: a pack, a kind, a source, a data version, a setting.
 *
 * @param nodeId who
 * @param url where
 * @param version the build
 * @param protocol the Drishti*net protocol
 * @param packs every pack switched on, with its version and content hash; {@code peerOnly} for a pack served to peers only
 * @param kinds every kind of those packs: its mnemonic, its pack, whether a Sutra renders it here, and its sources
 * @param load requests a second and latency over the last full minute, for choosing between peers
 * @param calendar the business calendar ({@code DRISHTI_CALENDAR}): an as-of date must mean the same day on both nodes
 * @param tags the node's tags
 * @param redact the field names this node masks ({@code drishti.security.redact}), for the warning that two nodes differ
 * @param access nodes per role as this node's access.yaml says, gossiped so Admin → Net can show where they differ
 * @param reports the BI report ids this node serves (a report on a model held elsewhere is answered there)
 * @param builtAt when it was built (epoch milliseconds)
 * @param services the state of the cluster services the node runs, by name, each as compact text (2.9.6: {@code var-shards}, the VaR
 *     shards it holds and is ready to serve, DATA_ARCHITECTURE.md#13-var-sharding-how-it-is-built); empty from a node that runs none or an older node
 * @param modules the modules this node booted (KERNEL_AND_MODULES.md section 4.2.1), in id order, each with its content hash (the SHA-256
 *     of its {@code DMOD.MF}: the module's identity), origin, switch, the parts placed here and its problems; a package the boot refused is
 *     listed too (state {@code refused} or {@code stale}); empty before the modules loaded and from an older node
 * @param modulesFingerprint the fingerprint of the modules this node booted ({@code ModuleHomes.fingerprint()}: equal on two nodes exactly
 *     when they booted the same modules at the same versions with the same content); empty when not reported
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Digest(String nodeId, String url, String version, int protocol, List<PackEntry> packs, List<KindEntry> kinds, Load load,
        String calendar, List<String> tags, List<String> redact, Map<String, List<String>> access, List<String> reports, long builtAt,
        Map<String, String> services, List<ModuleEntry> modules, String modulesFingerprint) {

    /** A digest without modules (a test, the shape before modules were reported). */
    public Digest(String nodeId, String url, String version, int protocol, List<PackEntry> packs, List<KindEntry> kinds, Load load,
            String calendar, List<String> tags, List<String> redact, Map<String, List<String>> access, List<String> reports, long builtAt,
            Map<String, String> services) {
        this(nodeId, url, version, protocol, packs, kinds, load, calendar, tags, redact, access, reports, builtAt, services, List.of(), "");
    }

    /** A digest without services (an older node, a test). */
    public Digest(String nodeId, String url, String version, int protocol, List<PackEntry> packs, List<KindEntry> kinds, Load load,
            String calendar, List<String> tags, List<String> redact, Map<String, List<String>> access, List<String> reports, long builtAt) {
        this(nodeId, url, version, protocol, packs, kinds, load, calendar, tags, redact, access, reports, builtAt, Map.of());
    }

    /** One pack: name, short code, version, a hash of its files, whether it is served to peers only. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackEntry(String name, String code, String version, String hash, boolean peerOnly) {}

    /** One kind: its mnemonic, label, pack, whether a Sutra renders it here, and the sources that hold its data. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KindEntry(String kind, String mnemonic, String label, String pack, boolean renders, List<SourceEntry> sources) {
        public KindEntry {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /**
     * One source of a kind.
     *
     * @param connector its name
     * @param type the plugin
     * @param table where its data lives, the same on every node that reads it (empty: unique to this node)
     * @param latestDate the newest business date it holds, or empty
     * @param earliestDate the oldest business date it holds, or empty (a node that is down counts only for the dates between the two)
     * @param dataVersion the version of the kind's data, or empty
     * @param columnar whether it keeps columns (searches read them without documents)
     * @param dated whether it holds data by business date
     * @param live whether it pushes updates
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceEntry(String connector, String type, String table, String earliestDate, String latestDate, String dataVersion,
            boolean columnar, boolean dated, boolean live) {
        public SourceEntry {
            connector = connector == null ? "" : connector;
            type = type == null ? "" : type;
            table = table == null ? "" : table;
            earliestDate = earliestDate == null ? "" : earliestDate;
            latestDate = latestDate == null ? "" : latestDate;
            dataVersion = dataVersion == null ? "" : dataVersion;
        }

        /**
         * Whether the source may hold the business date: a source that keeps no dates, or cannot say its first or last date, may hold any;
         * else only the dates from its oldest to its newest (as its digest last said).
         */
        public boolean mayHold(java.time.LocalDate date) {
            if (!dated || date == null) {
                return true;
            }
            try {
                return (earliestDate.isBlank() || !date.isBefore(java.time.LocalDate.parse(earliestDate)))
                        && (latestDate.isBlank() || !date.isAfter(java.time.LocalDate.parse(latestDate)));
            } catch (java.time.format.DateTimeParseException e) {
                return true;
            }
        }
    }

    /**
     * One module of a node's boot.
     *
     * @param id          the module's id (a refused file whose id could not be read: its file name)
     * @param version     its version (null for a refused file whose manifest could not be read)
     * @param contentHash the SHA-256 of its {@code DMOD.MF}, lower-case hex: what two nodes compare; null for a development folder or a
     *                    refused package
     * @param origin      {@code first-party}, {@code third-party}, {@code development}; null for a refused package
     * @param state       {@code loaded}, {@code off} (switched off, or a module it requires is off), {@code not-placed} (no part on this
     *                    node), {@code refused} (a check failed), {@code stale} (built for another kernel)
     * @param parts       the parts of it placed on this node ({@code api}, {@code console}, {@code compute}, …)
     * @param problems    what is wrong or worth a look, each with its kind and words
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModuleEntry(String id, String version, String contentHash, String origin, String state, List<String> parts,
            List<Problem> problems) {
        public ModuleEntry {
            parts = parts == null ? List.of() : List.copyOf(parts);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** What two nodes compare for this module: its content hash, or its state when it has none (refused, stale, development). */
        @JsonIgnore
        public String identity() {
            return contentHash != null && !contentHash.isBlank() ? contentHash
                    : "development".equals(origin) ? "development " + version : state;
        }
    }

    /**
     * A module's problem on a node.
     *
     * @param kind     {@code stale} (built for another kernel), {@code refused} (a check failed), {@code need} (a module it needs is
     *                 missing, refused or off), {@code library} (it carries its own copy of a library the kernel ships: the kernel's is
     *                 used), {@code resource} (more memory than the module budget), {@code development} (an unverified source folder)
     * @param severity {@code problem} (it does not run) or {@code warning} (it runs)
     * @param text     the words, as Health says them
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Problem(String kind, String severity, String text) {}

    /** Requests a second and the average latency over the last full minute. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Load(double requestsPerSecond, double latencyMs) {
        public static final Load NONE = new Load(0, 0);
    }

    private static final ObjectMapper CANON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public Digest {
        packs = packs == null ? List.of() : List.copyOf(packs);
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
        load = load == null ? Load.NONE : load;
        calendar = calendar == null ? "" : calendar;
        tags = tags == null ? List.of() : List.copyOf(tags);
        redact = redact == null ? List.of() : List.copyOf(redact);
        access = access == null ? Map.of() : Map.copyOf(access);
        reports = reports == null ? List.of() : List.copyOf(reports);
        services = services == null ? Map.of() : Map.copyOf(services);
        modules = modules == null ? List.of() : List.copyOf(modules);
        modulesFingerprint = modulesFingerprint == null ? "" : modulesFingerprint;
    }

    /** True when the node reported its modules (an older node, or one whose modules have not loaded yet, does not). */
    @JsonIgnore
    public boolean modulesReported() {
        return !modulesFingerprint.isBlank();
    }

    public Optional<ModuleEntry> module(String id) {
        return modules.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** The hash gossiped with the member entry: everything but the load and the build time. */
    @JsonIgnore
    public String hash() {
        try {
            Digest stable = new Digest(nodeId, url, version, protocol, packs, kinds, Load.NONE, calendar, tags, redact, access, reports, 0, services, modules,
                    modulesFingerprint);
            byte[] h = Hashes.of(Hashes.SHA_256, CANON.writeValueAsString(stable));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<KindEntry> kind(String k) {
        return kinds.stream().filter(e -> e.kind().equals(k)).findFirst();
    }

    public Optional<PackEntry> pack(String p) {
        return packs.stream().filter(e -> e.name().equals(p)).findFirst();
    }

    /** The kind a mnemonic names here ({@code TRD} is {@code trade}), or empty. */
    public Optional<String> kindOfMnemonic(String code) {
        return kinds.stream().filter(e -> e.mnemonic() != null && e.mnemonic().equalsIgnoreCase(code)).map(KindEntry::kind).findFirst();
    }
}

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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a node offers (DRISHTI_NET.md section 6), published at {@code GET /api/v1/net/digest} and pulled by peers when the
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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Digest(String nodeId, String url, String version, int protocol, List<PackEntry> packs, List<KindEntry> kinds, Load load,
        String calendar, List<String> tags, List<String> redact, Map<String, List<String>> access, List<String> reports, long builtAt) {

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
     * @param dataVersion the version of the kind's data, or empty
     * @param columnar whether it keeps columns (searches read them without documents)
     * @param dated whether it holds data by business date
     * @param live whether it pushes updates
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceEntry(String connector, String type, String table, String latestDate, String dataVersion, boolean columnar, boolean dated,
            boolean live) {
        public SourceEntry {
            connector = connector == null ? "" : connector;
            type = type == null ? "" : type;
            table = table == null ? "" : table;
            latestDate = latestDate == null ? "" : latestDate;
            dataVersion = dataVersion == null ? "" : dataVersion;
        }
    }

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
    }

    /** The hash gossiped with the member entry: everything but the load and the build time. */
    @JsonIgnore
    public String hash() {
        try {
            Digest stable = new Digest(nodeId, url, version, protocol, packs, kinds, Load.NONE, calendar, tags, redact, access, reports, 0);
            byte[] h = MessageDigest.getInstance("SHA-256").digest(CANON.writeValueAsString(stable).getBytes(StandardCharsets.UTF_8));
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

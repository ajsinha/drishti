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
package com.ash.drishti.identity;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.collab.Seals;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The audit trail in the content store (CONTENT_STORE.md section 9): append-only, so no two nodes ever write the same document. Each node
 * appends to its own per-day segments {@code audit/<yyyy-MM-dd>/<node>-<nnnn>.jsonl} (a new segment past 512 KB), one JSON line per event,
 * each chained to the node's previous line by hash ({@code hash = sha256(prev, fields)}); {@code audit-heads/<node>.json} holds the node's
 * count and newest hash, so removed rows show. A read merges every node's segments, newest first.
 */
public final class ContentAuditLog {

    static final String ROOT = "audit/";
    static final String HEADS = "audit-heads/";
    static final int SEGMENT_BYTES = 512 * 1024;

    private final ContentStore store;
    private final String node;
    private final ReentrantLock lock = new ReentrantLock();
    private String day;
    private int segment;
    private String segmentVersion;
    private StringBuilder segmentText;
    private boolean loaded;

    public ContentAuditLog(ContentStore store, String node) {
        this.store = store;
        this.node = ContentDocs.name(node == null || node.isBlank() ? "node" : node);
    }

    /** One stored event with the node that wrote it and its place in that node's chain. */
    record Line(AuditLog.Event event, String node, long n, String hash) {}

    private static String dayOf(Instant at) {
        return at.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    private String segmentKey(String d, int seg) {
        return ROOT + d + "/" + node + "-" + String.format("%04d", seg) + ".jsonl";
    }

    private static String hashOf(String prev, AuditLog.Event e, String node, long n) {
        return Seals.sha(prev + '\u001e' + Seals.join(node, n, e.at(), e.actor(), e.action(), e.subject(), e.detail()));
    }

    public void record(String actor, String action, String subject, String detail) {
        recordAt(new AuditLog.Event(Instant.now(), actor == null ? "" : actor, action, subject == null ? "" : subject, detail == null ? "" : detail));
    }

    /** Appends an event at its own time (the import of older events). */
    public void recordAt(AuditLog.Event e) {
        lock.lock();
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    append(e);
                    return;
                } catch (DrishtiException ex) {
                    if (ex.errorCode() != ErrorCode.CONTENT_CONFLICT || attempt >= 5) {
                        throw ex;
                    }
                    loaded = false;                                         // another process writes as this node: re-read and retry
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void append(AuditLog.Event e) {
        Optional<ContentStore.Item> headItem = store.get(HEADS + node + ".json");
        long count = 0;
        String prev = "";
        if (headItem.isPresent()) {
            JsonNode h = read(headItem.get().bytes());
            count = h.path("count").asLong();
            prev = h.path("hash").asText("");
        }
        String d = dayOf(e.at());
        if (!loaded || !d.equals(day)) {
            openSegment(d);
        }
        if (segmentText.length() > SEGMENT_BYTES) {
            segment++;
            segmentVersion = null;
            segmentText = new StringBuilder();
        }
        long n = count + 1;
        String hash = hashOf(prev, e, node, n);
        ObjectNode line = ContentDocs.JSON.createObjectNode();
        line.put("at", e.at().toString());
        line.put("actor", e.actor());
        line.put("action", e.action());
        line.put("subject", e.subject());
        line.put("detail", e.detail());
        line.put("node", node);
        line.put("n", n);
        line.put("hash", hash);
        String text = segmentText + line.toString() + "\n";
        String v = store.put(segmentKey(day, segment), text.getBytes(StandardCharsets.UTF_8), segmentVersion);
        segmentVersion = v;
        segmentText = new StringBuilder(text);
        ObjectNode head = ContentDocs.JSON.createObjectNode();
        head.put("count", n);
        head.put("hash", hash);
        store.put(HEADS + node + ".json", head.toString().getBytes(StandardCharsets.UTF_8), headItem.map(ContentStore.Item::version).orElse(null));
    }

    private void openSegment(String d) {
        day = d;
        int last = -1;
        String p = ROOT + d + "/" + node + "-";
        for (String k : store.list(p)) {
            String rest = k.substring(p.length());
            if (rest.endsWith(".jsonl")) {
                try {
                    last = Math.max(last, Integer.parseInt(rest.substring(0, rest.length() - ".jsonl".length())));
                } catch (NumberFormatException ignored) {
                    // not ours
                }
            }
        }
        segment = Math.max(0, last);
        Optional<ContentStore.Item> it = store.get(segmentKey(d, segment));
        segmentVersion = it.map(ContentStore.Item::version).orElse(null);
        segmentText = new StringBuilder(it.map(ContentStore.Item::text).orElse(""));
        loaded = true;
    }

    private static JsonNode read(byte[] bytes) {
        try {
            return ContentDocs.JSON.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Line> lines(String text) {
        List<Line> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            if (l.isBlank()) {
                continue;
            }
            JsonNode j = read(l.getBytes(StandardCharsets.UTF_8));
            out.add(new Line(new AuditLog.Event(Instant.parse(j.path("at").asText()), j.path("actor").asText(""), j.path("action").asText(""),
                    j.path("subject").asText(""), j.path("detail").asText("")), j.path("node").asText(""), j.path("n").asLong(), j.path("hash").asText("")));
        }
        return out;
    }

    /** Newest first, at most {@code limit}, optionally only events about or by {@code subject}: every node's segments, merged. */
    public List<AuditLog.Event> recent(int limit, String subject) {
        TreeMap<String, List<String>> days = new TreeMap<>(Comparator.reverseOrder());
        for (String k : store.list(ROOT)) {
            String rest = k.substring(ROOT.length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                days.computeIfAbsent(rest.substring(0, slash), x -> new ArrayList<>()).add(k);
            }
        }
        List<AuditLog.Event> out = new ArrayList<>();
        for (List<String> keys : days.values()) {
            List<Line> all = new ArrayList<>();
            for (String k : keys) {
                store.get(k).ifPresent(it -> all.addAll(lines(it.text())));
            }
            all.sort(Comparator.comparing((Line l) -> l.event().at()).thenComparing(Line::node).thenComparingLong(Line::n).reversed());
            for (Line l : all) {
                if (subject == null || subject.isBlank() || subject.equals(l.event().subject()) || subject.equals(l.event().actor())) {
                    out.add(l.event());
                    if (out.size() >= Math.max(1, limit)) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /** What the chains say is wrong (empty when they hold): a changed line, a removed line, or removed newest lines, per node. */
    public List<String> verify(int max) {
        Map<String, List<String>> byNode = new TreeMap<>();
        for (String k : store.list(ROOT)) {
            String name = k.substring(k.lastIndexOf('/') + 1);
            int dash = name.lastIndexOf('-');
            if (dash > 0) {
                byNode.computeIfAbsent(name.substring(0, dash), x -> new ArrayList<>()).add(k);
            }
        }
        TreeSet<String> nodes = new TreeSet<>(byNode.keySet());
        for (String k : store.list(HEADS)) {
            nodes.add(k.substring(HEADS.length(), k.length() - ".json".length()));
        }
        List<String> problems = new ArrayList<>();
        for (String n : nodes) {
            String prev = "";
            long count = 0;
            for (String k : byNode.getOrDefault(n, List.of())) {                // keys sort by day, then segment
                for (Line l : store.get(k).map(it -> lines(it.text())).orElse(List.of())) {
                    count++;
                    if ((l.n() != count || !hashOf(prev, l.event(), n, l.n()).equals(l.hash())) && problems.size() < max) {
                        problems.add("audit line " + n + "#" + l.n() + " was changed, or a line before it was removed");
                    }
                    prev = l.hash();
                }
            }
            Optional<ContentStore.Item> head = store.get(HEADS + n + ".json");
            if (head.isPresent()) {
                JsonNode h = read(head.get().bytes());
                if ((h.path("count").asLong() != count || !h.path("hash").asText("").equals(prev)) && problems.size() < max) {
                    problems.add("the audit trail of " + n + " lost its newest lines (sealed " + h.path("count").asLong() + ", found " + count + ")");
                }
            }
        }
        return problems;
    }
}

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
package com.ash.drishti.server.edit;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.net.RunCluster;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the edit log lives and who owns which lane (WRITE_BACK.md section 11). The <b>auto rule</b>, in order: the Drishti database is
 * PostgreSQL → {@code postgres} (shared: lanes are spread over the members by the ring, ownership is taken in the store, fenced by the
 * epoch, a change submitted anywhere is forwarded to its lane's owner); this node is alone → {@code sqlite} (it owns every lane);
 * {@code edit.primary} is set → the primary owns every lane in its own database and every other node forwards edits to it (no
 * failover: while it is unreachable edits pause, reading carries on); otherwise edits are refused in the cluster. An explicit
 * {@code edit.log} is checked: {@code postgres} needs a PostgreSQL Drishti database, {@code sqlite} in a cluster needs
 * {@code edit.primary}; otherwise the server refuses to start. (The S3 log is designed, not built.)
 */
public final class EditCluster {

    private static final Logger LOG = LoggerFactory.getLogger(EditCluster.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How this node takes part. */
    public enum Mode {
        /** One node: it owns every lane in its own database. */
        SINGLE,
        /** A cluster sharing a PostgreSQL Drishti database: lanes spread by the ring, any node forwards to the owner. */
        SHARED,
        /** This node is the edit primary: it owns every lane. */
        PRIMARY,
        /** Another node is the edit primary: edits are forwarded to it, overlays followed from it. */
        FOLLOWER,
        /** A cluster with no shared edit log: edits are refused, reads carry on. */
        REFUSED
    }

    /**
     * The mode and why.
     *
     * @param store the store in use for Admin → Edits ({@code sqlite}, {@code postgres})
     */
    public record Plan(Mode mode, String store, String why, String primary) {}

    private final RunCluster cluster;
    private final Plan plan;
    private final int lanes;

    public EditCluster(RunCluster cluster, Plan plan, int lanes) {
        this.cluster = cluster;
        this.plan = plan;
        this.lanes = lanes;
    }

    /**
     * The auto rule (or the explicit setting, checked).
     *
     * @param postgres the Drishti database is PostgreSQL
     * @param clustered Drishti*net is on and this node has (or expects) other members
     * @param self this node's id
     */
    public static Plan plan(EditSettings s, boolean postgres, boolean clustered, String self) {
        String primary = s.primary();
        switch (s.log()) {
            case "postgres" -> {
                if (!postgres) {
                    throw new IllegalStateException("edit.log is postgres, but the server's database is SQLite: use PostgreSQL for it, or edit.log: auto");
                }
                return new Plan(clustered ? Mode.SHARED : Mode.SINGLE, "postgres", "edit.log: postgres", "");
            }
            case "sqlite" -> {
                if (clustered && primary.isBlank()) {
                    throw new IllegalStateException("edit.log is sqlite in a cluster: name the edit primary (edit.primary: <node id>), or use edit.log: auto");
                }
                if (!clustered) {
                    return new Plan(Mode.SINGLE, postgres ? "postgres" : "sqlite", "edit.log: sqlite, one node", "");
                }
                return primaryPlan(primary, self, postgres, "edit.log: sqlite with an edit primary");
            }
            default -> {
                if (postgres) {
                    return new Plan(clustered ? Mode.SHARED : Mode.SINGLE, "postgres", "auto: the server's database is PostgreSQL", "");
                }
                if (!clustered) {
                    return new Plan(Mode.SINGLE, "sqlite", "auto: this node is alone", "");
                }
                if (!primary.isBlank()) {
                    return primaryPlan(primary, self, false, "auto: edit.primary is set (no failover: edits pause while it is down)");
                }
                return new Plan(Mode.REFUSED, "none", "auto: this cluster has no shared edit log: use PostgreSQL for the server's database, "
                        + "or name an edit.primary", "");
            }
        }
    }

    private static Plan primaryPlan(String primary, String self, boolean postgres, String why) {
        return primary.equals(self) ? new Plan(Mode.PRIMARY, postgres ? "postgres" : "sqlite", why + " (this node)", primary)
                : new Plan(Mode.FOLLOWER, "the edit primary's", why + " (" + primary + ")", primary);
    }

    public Plan plan() {
        return plan;
    }

    /** True when this node appends and applies (it may own lanes). */
    public boolean owns() {
        return plan.mode() == Mode.SINGLE || plan.mode() == Mode.SHARED || plan.mode() == Mode.PRIMARY;
    }

    /** The lanes the ring suggests this node take: all of them alone or as the primary; its share of the ring when shared. */
    public Set<Integer> suggested() {
        return switch (plan.mode()) {
            case SINGLE, PRIMARY -> IntStream.range(0, lanes).boxed().collect(Collectors.toCollection(LinkedHashSet::new));
            case SHARED -> {
                if (cluster == null || !cluster.on()) {
                    yield Set.of();                                   // not joined yet: the ring cannot suggest; the lanes wait for it
                }
                var dir = cluster.directory();
                yield IntStream.range(0, lanes).filter(i -> cluster.self().equals(dir.owner("edit-lane-" + i))).boxed()
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
            default -> Set.of();
        };
    }

    /** Appends on the lane's owner, another node, as the person (signed, on behalf of them); returns {@code {lane, seq}}. */
    public long[] forward(String owner, Change c, Principal p) {
        if (cluster == null || !cluster.on() || owner == null) {
            throw new DrishtiException(ErrorCode.EDIT_PAUSED, "edits are paused: no node owns this change's lane yet; your changes are kept as drafts. "
                    + "Reading is not affected.");
        }
        try {
            PeerHttp.Answer a = cluster.call(owner, "POST", "/api/v1/net/edits/append", c.json().getBytes(StandardCharsets.UTF_8), p, Duration.ofSeconds(15));
            if (a.status() / 100 != 2) {
                JsonNode err = JSON.readTree(a.body() == null ? new byte[0] : a.body());
                throw new DrishtiException(a.status() == 503 ? ErrorCode.EDIT_BUSY : ErrorCode.EDIT_PAUSED, "the owner of the lane (" + owner + ") said: "
                        + err.path("detail").asText("HTTP " + a.status()));
            }
            JsonNode ok = JSON.readTree(a.body());
            return new long[] {ok.path("lane").asLong(), ok.path("seq").asLong()};
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.EDIT_PAUSED, "edits are paused: the node that owns this change's lane (" + owner + ") is not reachable; "
                    + "your changes are kept as drafts. Reading is not affected.");
        }
    }

    /** Tells every other alive member that a change was applied (their caches and viewers; the notice for their consoles). */
    public void broadcast(Map<String, Object> notice) {
        if (cluster == null || !cluster.on()) {
            return;
        }
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(notice);
        } catch (IOException e) {
            return;
        }
        Principal system = new Principal("drishti-edit", List.of());
        for (String node : cluster.peers()) {
            if (node.equals(cluster.self())) {
                continue;
            }
            Thread.ofVirtual().name("edit-broadcast").start(() -> {
                try {
                    cluster.call(node, "POST", "/api/v1/net/edits/changed", body, system, Duration.ofSeconds(5));
                } catch (IOException e) {
                    LOG.debug("write-back: {} was not told of {}: {}", node, notice.get("id"), e.getMessage());
                }
            });
        }
    }

    public RunCluster runCluster() {
        return cluster;
    }
}

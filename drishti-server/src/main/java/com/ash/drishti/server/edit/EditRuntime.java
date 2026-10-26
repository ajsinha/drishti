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
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.net.RunCluster;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Write-back on this server, put together (WRITE_BACK.md): the edit log and the records it leaves in the Drishti database, the layer the
 * router lays over every document, the lanes this node owns and their handler, the outbox of source commands, the triggers of the reactive
 * connectors, the events on the consoles' streams, and the part this node plays in a cluster (section 11). With {@code edit.enabled:
 * false} (as shipped) nothing starts: no lane, no worker, no pencil; overlays applied earlier are still laid over the documents.
 */
public final class EditRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EditRuntime.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EditSettings settings;
    private final String node;
    private final EditLog log;
    private final EditRecords records;
    private final ChangeRequests requests;
    private final EditLayer layer;
    private final EditHandler handler;
    private final EditService service;
    private final EditFeed feed;
    private final EditTriggers triggers;
    private final EditCluster cluster;
    private final ConnectorWrites writes;
    private final AuditLog audit;
    private volatile Lanes lanes;
    private volatile Outbox outbox;
    private final ScheduledExecutorService chores = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("edit-chores").factory());

    /**
     * @param clustered Drishti*net is on for this node (the auto rule asks whether it is alone)
     * @param writes the connectors' writes (null: source targets are refused)
     * @param connectorOfKind the connector whose writes a source target of a kind uses when the form names none
     */
    public EditRuntime(EditSettings settings, DataSource ds, SourceRouter router, EditableFields fields, Entitlements entitlements,
            Function<String, Principal> principals, PreferenceStore drafts, AuditLog audit, Supplier<LocalDate> today, RunCluster runCluster,
            String node, boolean clustered, ConnectorWrites writes, Function<String, Optional<String>> connectorOfKind) {
        this.settings = settings;
        this.node = node;
        this.audit = audit;
        this.writes = writes;
        this.log = new EditLog(ds);
        this.records = new EditRecords(ds);
        this.requests = new ChangeRequests(ds);
        this.layer = new EditLayer(records, settings.enabled());
        router.layer(layer);
        EditCluster.Plan plan = EditCluster.plan(settings, log.postgres(), clustered, node);
        this.cluster = new EditCluster(runCluster, plan, settings.lanes());
        this.handler = new EditHandler(log, records, fields, principals, entitlements, router, () -> settings, today, audit);
        this.service = new EditService(records, log, requests, fields, entitlements, drafts, audit, () -> settings, today);
        this.feed = new EditFeed(entitlements, service::approver);
        this.triggers = new EditTriggers(log, EditTriggers.hub(), n -> {
            feed.publish(n);
            cluster.broadcast(n);
        });
        if (writes != null) {
            service.commands(writes::unusable, connectorOfKind);
        }
        service.onNotice(n -> {
            feed.publish(n);
            cluster.broadcast(n);
        });
        handler.onEnd((row, state, reason, version) -> {
            service.ended(row, state, reason, version);
            if (EditLog.APPLIED.equals(state)) {
                changed(row.change());
                triggers.applied(row);
                Outbox o = outbox;
                if (o != null && "source".equals(row.change().target())) {
                    o.kick();
                }
            }
        });
    }

    /** Starts what this node runs: its lanes and their workers, the outbox, the follower of an edit primary, retention. */
    public void start() {
        if (!settings.enabled()) {
            LOG.info("write-back: off ({}); every page reads as before", settings.origin());
            return;
        }
        EditCluster.Plan plan = cluster.plan();
        LOG.info("write-back: on; edit log {} ({}), {} lanes, node {}", plan.store(), plan.why(), settings.lanes(), node);
        if (cluster.owns()) {
            Lanes l = new Lanes(log, settings, node, handler, cluster::suggested);
            l.listen(new Lanes.Listener() {
                @Override
                public void gained(int lane, long epoch) {
                    triggers.replay(lane);
                }
            });
            lanes = l;
            l.start();
            if (writes != null) {
                Outbox o = new Outbox(log, records, writes, l, audit, this::commandEnded, () -> Duration.ofSeconds(1));
                outbox = o;
                o.start();
            }
            service.appender(this::append);
        } else if (plan.mode() == EditCluster.Mode.FOLLOWER) {
            chores.scheduleWithFixedDelay(this::followQuietly, settings.follow().toMillis(), settings.follow().toMillis(), TimeUnit.MILLISECONDS);
            service.appender((c, p) -> {
                throw new DrishtiException(ErrorCode.EDIT_PAUSED, "edits are made on the edit primary (" + plan.primary() + "); this node forwards them");
            });
        } else {
            service.appender((c, p) -> {
                throw new DrishtiException(ErrorCode.EDIT_PAUSED, plan.why() + ". Reading is not affected.");
            });
        }
        chores.scheduleWithFixedDelay(this::retention, 1, 24 * 60, TimeUnit.MINUTES);
        if (plan.mode() == EditCluster.Mode.SHARED) {
            chores.scheduleWithFixedDelay(records::forgetAll, 30, 30, TimeUnit.SECONDS);   // a missed broadcast heals within 30 s
        }
    }

    /** Appends where the lane is owned: here, or forwarded to its owner (signed, as the person). */
    long[] append(Change c, Principal p) {
        Lanes l = lanes;
        if (l == null) {
            throw new DrishtiException(ErrorCode.EDIT_PAUSED, "edits are paused: the lanes are not running on this node; your changes are kept as drafts");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                long seq = l.append(c);
                return new long[] {l.laneOf(c.key()), seq};
            } catch (Lanes.NotOwner e) {
                if (e.owner() == null || e.owner().equals(node)) {
                    l.tend();                                          // perhaps ours to take now
                    if (attempt == 0) {
                        continue;
                    }
                    throw new DrishtiException(ErrorCode.EDIT_PAUSED, "edits are paused: no node owns lane " + e.lane() + " yet; your changes are kept "
                            + "as drafts. Reading is not affected.");
                }
                return cluster.forward(e.owner(), c, p);
            } catch (EditLog.Fenced e) {
                if (attempt == 1) {
                    throw new DrishtiException(ErrorCode.EDIT_PAUSED, "the lane moved to another node while appending: try again");
                }
            }
        }
        throw new DrishtiException(ErrorCode.EDIT_PAUSED, "edits are paused: try again in a moment");
    }

    /** A change appended here on behalf of another node (it forwarded the person's change to this lane owner). */
    public long[] appendForwarded(Change c, Principal p) {
        if (!c.by().equals(p.user())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "a forwarded change is appended only as the person who made it");
        }
        Lanes l = lanes;
        if (l == null) {
            throw new DrishtiException(ErrorCode.EDIT_PAUSED, "write-back is not running on node " + node);
        }
        try {
            return new long[] {l.laneOf(c.key()), l.append(c)};
        } catch (Lanes.NotOwner e) {
            throw new DrishtiException(ErrorCode.EDIT_PAUSED, "node " + node + " does not own lane " + e.lane() + " (now " + e.owner() + "): try again");
        } catch (EditLog.Busy e) {
            throw new DrishtiException(ErrorCode.EDIT_BUSY, e.getMessage());
        }
    }

    /** The views of a record (and of the page its form row is on) read it again. */
    void changed(Change c) {
        layer.changed(c.kind(), c.entity());
        if (c.origin("pageKind") != null && c.origin("pageId") != null) {
            layer.changed(c.origin("pageKind"), c.origin("pageId"));
        }
    }

    private void commandEnded(EditLog.Row row, boolean confirmed, String reason) {
        triggers.command(row, confirmed);
        if (!confirmed) {
            changed(row.change());                                 // its overlay was withdrawn: the views show the source again
        }
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", confirmed ? "confirmed" : "refused-at-source");
        n.put("id", row.changeId());
        n.put("kind", row.change().kind());
        n.put("entity", row.change().entity());
        n.put("by", row.change().by());
        n.put("target", row.change().target());
        n.put("paths", row.change().paths());
        n.put("reason", reason);
        feed.publish(n);
        cluster.broadcast(n);
    }

    /** Another node applied a change (or withdrew an overlay): drop what is cached, tell the views here, and the consoles here. */
    public void peerChanged(Map<String, Object> notice) {
        String kind = String.valueOf(notice.get("kind"));
        String entity = String.valueOf(notice.get("entity"));
        if (cluster.plan().mode() == EditCluster.Mode.FOLLOWER) {
            followQuietly();                                        // the overlays are copied before the views read again
            Object id = notice.get("id");
            if (id != null && ("refused-at-source".equals(notice.get("type")) || "withdrawn-overlay".equals(notice.get("type")))) {
                followChange(String.valueOf(id));
            }
        }
        layer.changed(kind, entity);
        if (notice.get("pageKind") != null && notice.get("pageId") != null) {
            layer.changed(String.valueOf(notice.get("pageKind")), String.valueOf(notice.get("pageId")));
        }
        feed.publish(notice);
    }

    /** An administrator withdraws a change's overlays; returns how many were shown. */
    public int withdraw(String changeId) {
        List<EditRecords.Overlay> shown = records.ofChange(changeId).stream().filter(o -> EditRecords.ACTIVE.equals(o.state())).toList();
        if (shown.isEmpty()) {
            return 0;
        }
        log.tx(c -> {
            records.withdraw(c, changeId);
            return null;
        }, "withdraw " + changeId);
        for (EditRecords.Overlay o : shown) {
            layer.changed(o.kind(), o.entity());
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", "withdrawn-overlay");
            n.put("id", changeId);
            n.put("kind", o.kind());
            n.put("entity", o.entity());
            n.put("by", o.by());
            n.put("paths", List.of(o.path()));
            feed.publish(n);
            cluster.broadcast(n);
        }
        return shown.size();
    }

    // ---------------------------------------------------------------------------------------------------- following an edit primary

    /** What a follower copies: the applied changes after its cursor, each with its overlays and owned values (the primary answers it). */
    public Map<String, Object> follow(Map<Integer, Long> after, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int lane = 0; lane < settings.lanes() && out.size() < limit; lane++) {
            for (EditLog.Row r : log.appliedAfter(lane, after.getOrDefault(lane, 0L), limit - out.size())) {
                out.add(entry(r));
            }
        }
        return Map.of("changes", out);
    }

    /** One change's entry for a follower. */
    public Map<String, Object> entry(EditLog.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lane", r.lane());
        m.put("seq", r.seq());
        m.put("change", r.change());
        m.put("overlays", records.ofChange(r.changeId()));
        m.put("owned", records.ownedOfChange(r.changeId()));
        m.put("version", records.version(r.key()));
        return m;
    }

    private void followQuietly() {
        try {
            follow();
        } catch (RuntimeException e) {
            LOG.debug("write-back: could not follow the edit primary: {}", e.getMessage());
        }
    }

    private final java.util.concurrent.locks.ReentrantLock following = new java.util.concurrent.locks.ReentrantLock();

    /** Reads the primary's applied changes after this node's cursors and copies their overlays and owned values, in order. */
    void follow() {
        RunCluster rc = cluster.runCluster();
        String primary = cluster.plan().primary();
        if (rc == null || !rc.on() || !following.tryLock()) {
            return;
        }
        try {
            StringBuilder q = new StringBuilder();
            for (int lane = 0; lane < settings.lanes(); lane++) {
                long at = log.cursor("follow", lane);
                if (at > 0) {
                    q.append(q.isEmpty() ? "" : ",").append(lane).append(':').append(at);
                }
            }
            var a = rc.call(primary, "GET", "/api/v1/net/edits/follow?after=" + q, null, new Principal("drishti-edit", List.of()), Duration.ofSeconds(10));
            if (a.status() != 200) {
                return;
            }
            JsonNode body = JSON.readTree(a.body());
            for (JsonNode e : body.path("changes")) {
                copy(e);
            }
        } catch (java.io.IOException e) {
            LOG.debug("write-back: the edit primary {} is not reachable: {}", primary, e.getMessage());
        } finally {
            following.unlock();
        }
    }

    private void followChange(String id) {
        RunCluster rc = cluster.runCluster();
        try {
            var a = rc.call(cluster.plan().primary(), "GET", "/api/v1/net/edits/follow?change=" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8),
                    null, new Principal("drishti-edit", List.of()), Duration.ofSeconds(10));
            if (a.status() == 200) {
                for (JsonNode e : JSON.readTree(a.body()).path("changes")) {
                    copy(e);
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.debug("write-back: could not re-read {} from the edit primary: {}", id, e.getMessage());
        }
    }

    /** Copies one entry into this node's own Drishti database (idempotent by change id), then moves the cursor. */
    void copy(JsonNode e) {
        Change c = Change.JSON.convertValue(e.path("change"), Change.class);
        log.tx(cn -> {
            for (JsonNode o : e.path("overlays")) {
                EditRecords.Overlay ov = new EditRecords.Overlay(o.path("changeId").asText(), o.path("path").asText(), o.path("kind").asText(),
                        o.path("entity").asText(), JSON.convertValue(o.get("value"), Object.class), JSON.convertValue(o.get("sourceValue"), Object.class),
                        LocalDate.parse(o.path("businessDate").asText()), o.hasNonNull("until") ? LocalDate.parse(o.path("until").asText()) : null,
                        o.path("state").asText(EditRecords.ACTIVE), o.path("by").asText(), Instant.parse(o.path("appliedAt").asText()));
                records.overlay(cn, ov);
                records.state(cn, ov.changeId(), ov.path(), ov.state());
            }
            for (JsonNode o : e.path("owned")) {
                records.owned(cn, new EditRecords.Owned(o.path("kind").asText(), o.path("entity").asText(), o.path("path").asText(),
                        JSON.convertValue(o.get("value"), Object.class), o.path("changeId").asText(), o.path("by").asText(), Instant.parse(o.path("at").asText())));
            }
            records.bump(cn, c.key(), c.id(), Instant.now());
            return null;
        }, "copy " + c.id() + " from the edit primary");
        log.cursor("follow", e.path("lane").asInt(), e.path("seq").asLong());
        changed(c);
    }

    // ---------------------------------------------------------------------------------------------------- chores

    private void retention() {
        try {
            int n = log.purge(Instant.now().minus(settings.keep()));
            if (n > 0) {
                audit.record("system", "edit-log-purged", "edit-log", n + " entries older than " + settings.keep().toDays() + " days");
            }
        } catch (RuntimeException e) {
            LOG.warn("write-back: retention could not run: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------------- parts

    public EditSettings settings() {
        return settings;
    }

    public String node() {
        return node;
    }

    public EditLog log() {
        return log;
    }

    public EditRecords records() {
        return records;
    }

    public EditLayer layer() {
        return layer;
    }

    public EditService service() {
        return service;
    }

    public EditFeed feed() {
        return feed;
    }

    public EditCluster cluster() {
        return cluster;
    }

    public EditHandler handler() {
        return handler;
    }

    public ConnectorWrites writes() {
        return writes;
    }

    public Lanes lanes() {
        return lanes;
    }

    public Outbox outbox() {
        return outbox;
    }

    @Override
    public void close() {
        chores.shutdownNow();
        Outbox o = outbox;
        if (o != null) {
            o.close();
        }
        Lanes l = lanes;
        if (l != null) {
            l.close();
        }
        triggers.close();
    }
}

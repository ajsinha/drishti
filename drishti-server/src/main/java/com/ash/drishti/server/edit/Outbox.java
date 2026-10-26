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

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.write.WriteCommand;
import com.ash.drishti.api.write.WriteResult;
import com.ash.drishti.identity.AuditLog;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Source commands through the outbox (WRITE_BACK.md section 5.3). An applied change with a {@code source} target leaves its log row
 * {@code command: pending}, committed in the same transaction as the apply; this sender, on the lane's owner, sends pending commands
 * in seq order, each carrying the change id, and records {@code sent} (fenced). Two commands for one record are never in flight
 * together: the next waits for the previous one's confirmation, so the source sees them in order. A slow source never blocks a lane:
 * the lane goes on applying. The confirmation is what the command's {@code confirm:} says: the synchronous <b>reply</b>, or the
 * <b>next load</b> showing the value (read from the connector itself, under any overlay); {@code ack-topic} is read as a reply until a
 * messaging plugin writes. It marks the row {@code applied}, or {@code refused} with the source's reason; a refusal withdraws the
 * change's overlay and the triggers recompute again. A new owner resends every pending and sent-but-unconfirmed command of its lanes
 * with the same change id: the source applies it at most once (the idempotency clause).
 */
public final class Outbox implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Outbox.class);

    /** Told when a command ends: confirmed (true) or refused at the source (false). */
    @FunctionalInterface
    public interface Ended {
        void ended(EditLog.Row row, boolean confirmed, String reason);
    }

    private final EditLog log;
    private final EditRecords records;
    private final ConnectorWrites writes;
    private final Lanes lanes;
    private final AuditLog audit;
    private final Ended ended;
    private final Supplier<Duration> every;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("edit-outbox").factory());
    /** Commands this run of the server sent (a {@code sent} row not in here was sent by an earlier owner: resend it). */
    private final Set<String> sentHere = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> backoffUntil = new ConcurrentHashMap<>();

    public Outbox(EditLog log, EditRecords records, ConnectorWrites writes, Lanes lanes, AuditLog audit, Ended ended, Supplier<Duration> every) {
        this.log = log;
        this.records = records;
        this.writes = writes;
        this.lanes = lanes;
        this.audit = audit;
        this.ended = ended;
        this.every = every;
    }

    public void start() {
        long ms = Math.max(100, every.get().toMillis());
        loop.scheduleWithFixedDelay(this::round, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** Sends at once (an apply just left a command pending). */
    public void kick() {
        loop.execute(this::round);
    }

    /** One pass over the owned lanes. */
    void round() {
        for (Map.Entry<Integer, Long> lane : lanes.owned().entrySet()) {
            try {
                lane(lane.getKey(), lane.getValue());
            } catch (EditLog.Fenced e) {
                LOG.info("write-back outbox: lane {} is not ours any more", lane.getKey());
            } catch (RuntimeException e) {
                LOG.warn("write-back outbox: lane {}: {}", lane.getKey(), e.getMessage());
            }
        }
    }

    private void lane(int lane, long epoch) {
        Set<String> busy = new HashSet<>();                       // records with an earlier command still unconfirmed
        for (EditLog.Row r : log.unconfirmed(lane)) {
            if (!busy.add(r.key())) {
                continue;
            }
            Long wait = backoffUntil.get(r.changeId());
            if (wait != null && wait > System.currentTimeMillis()) {
                continue;
            }
            ConnectorWrites.Command cmd;
            try {
                cmd = writes.command(r.change().connector(), r.change().command());
            } catch (IllegalStateException e) {
                if (EditLog.CMD_PENDING.equals(r.command())) {
                    LOG.warn("write-back outbox: {} waits: {}", r.changeId(), e.getMessage());
                    later(r, 30_000);
                }
                continue;
            }
            if (EditLog.CMD_SENT.equals(r.command()) && sentHere.contains(r.changeId())) {
                if ("next-load".equals(cmd.confirm())) {
                    confirmByLoad(r, cmd, lane, epoch);
                }
                continue;                                         // in flight here: wait for its confirmation
            }
            send(r, cmd, lane, epoch);
        }
    }

    private void send(EditLog.Row r, ConnectorWrites.Command cmd, int lane, long epoch) {
        Optional<SourcePlugin> plugin = writes.plugin(cmd.connector());
        if (plugin.isEmpty()) {
            later(r, 10_000);
            return;
        }
        log.command(r, lanes.node(), epoch, EditLog.CMD_SENT, null, true, null);
        sentHere.add(r.changeId());
        WriteResult res;
        try {
            res = null;
            List<Change.Item> items = r.change().items();
            for (int i = 0; i < items.size(); i++) {
                WriteCommand wc = cmd.fill(params(r.change(), items.get(i), items.size() == 1 ? r.changeId() : r.changeId() + "#" + (i + 1)));
                WriteResult one = plugin.get().write(wc);
                if (one.status() != WriteResult.Status.APPLIED && one.status() != WriteResult.Status.ALREADY && one.status() != WriteResult.Status.SENT) {
                    res = one;
                    break;
                }
                res = one;
            }
        } catch (Exception e) {
            int attempts = r.attempts() + 1;
            LOG.warn("write-back outbox: {} to {} failed (attempt {}): {}; it is sent again", r.changeId(), cmd.connector(), attempts, e.getMessage());
            log.command(r, lanes.node(), epoch, EditLog.CMD_PENDING, "attempt " + attempts + ": " + e.getMessage(), false, null);
            sentHere.remove(r.changeId());
            later(r, Math.min(60_000, 1_000L << Math.min(attempts, 6)));
            return;
        }
        if (res == null) {
            return;
        }
        switch (res.status()) {
            case APPLIED, ALREADY -> {
                if ("next-load".equals(cmd.confirm())) {
                    return;                                       // sent; the next load confirms it
                }
                confirm(r, lane, epoch, res.status() == WriteResult.Status.ALREADY ? "already at the source (a resend)" : "the source replied");
            }
            case SENT -> {
                // the confirmation comes later (the next load)
            }
            case CONFLICTED, REFUSED -> refuse(r, lane, epoch, res.status() == WriteResult.Status.CONFLICTED
                    ? "conflicted at the source: " + res.reason() : res.reason());
        }
    }

    /** The next load shows the value: read from the connector itself (no overlay), every item equal to what was sent. */
    private void confirmByLoad(EditLog.Row r, ConnectorWrites.Command cmd, int lane, long epoch) {
        Optional<SourcePlugin> plugin = writes.plugin(cmd.connector());
        if (plugin.isEmpty()) {
            return;
        }
        try {
            Optional<EntityDocument> d = plugin.get().fetch(EntityRef.of(r.change().kind(), r.change().entity()));
            if (d.isPresent() && r.change().items().stream().allMatch(it -> EditHandler.same(EditPaths.get(d.get().data(), it.path()).unwrap(), it.to()))) {
                confirm(r, lane, epoch, "the next load shows the value");
            }
        } catch (Exception e) {
            LOG.debug("write-back outbox: {} could not be read back: {}", r.changeId(), e.getMessage());
        }
    }

    private void confirm(EditLog.Row r, int lane, long epoch, String how) {
        log.command(r, lanes.node(), epoch, EditLog.CMD_APPLIED, how, false, null);
        sentHere.remove(r.changeId());
        audit.record(r.change().by(), "edit-confirmed", r.key(), r.changeId() + " at " + r.change().connector() + ": " + how);
        ended.ended(r, true, how);
    }

    private void refuse(EditLog.Row r, int lane, long epoch, String why) {
        log.command(r, lanes.node(), epoch, EditLog.CMD_REFUSED, why, false, c -> records.withdraw(c, r.changeId()));
        sentHere.remove(r.changeId());
        audit.record(r.change().by(), "edit-refused-at-source", r.key(), r.changeId() + " at " + r.change().connector() + ": " + why);
        ended.ended(r, false, why);
    }

    private void later(EditLog.Row r, long ms) {
        backoffUntil.put(r.changeId(), System.currentTimeMillis() + ms);
    }

    /** What Drishti supplies to a command ({@link WriteCommand#SUPPLIED}) and the change's own parameters. */
    static Map<String, Object> params(Change c, Change.Item it, String changeId) {
        Map<String, Object> m = new LinkedHashMap<>(c.params());
        m.put("entity", c.entity());
        m.put("kind", c.kind());
        m.put("businessDate", c.businessDate().toString());
        m.put("value", it.to());
        m.put("from", it.from());
        m.put("basedOn", c.basedOn());
        m.put("changeId", changeId);
        m.put("by", c.by());
        m.put("reason", c.reason());
        m.put("at", c.at() == null ? Instant.now().toString() : c.at().toString());
        m.put("field", EditPaths.field(it.path()));
        return m;
    }

    /** Commands sent longer ago than their timeout and not confirmed (Admin → Edits calls them unconfirmed). */
    public List<EditLog.Row> unconfirmed(Duration timeout) {
        Instant before = Instant.now().minus(timeout);
        return log.unconfirmed().stream().filter(r -> EditLog.CMD_SENT.equals(r.command()) && r.commandSentAt() != null && r.commandSentAt().isBefore(before)).toList();
    }

    @Override
    public void close() {
        loop.shutdownNow();
    }
}

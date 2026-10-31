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

import com.ash.drishti.api.compute.ComputeEvent;
import com.ash.drishti.compute.reactive.ReactiveHub;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An applied change triggers the reactive connectors (WRITE_BACK.md › The design and its reasons, section 18.3; DATA_ARCHITECTURE.md › Reactive connectors, section 7): after its transaction
 * commits, the lane owner publishes {@code edit.applied} as a {@code write-back} event ({@code POST /api/v1/compute/events} semantics):
 * the change id as the event id (a connector ignores one it has seen), the kind, entity and business date, the fields changed (never
 * their values) and the watermark {@code L<lane> -> seq}, so a recompute says exactly which changes it includes. A source target fires
 * again when the source confirms ({@code when: confirmed}) or refuses ({@code when: refused}). A conflicted, refused or withdrawn change
 * fires nothing.
 *
 * <p><b>Durable because it is the edit log:</b> how far the triggers have been told is kept per lane ({@code drishti_edit_cursor},
 * consumer {@code reactive}); a lane gained after a restart or a failover replays the applied changes after it, in seq order, so none is
 * missed. Events are published in order on one thread, never on a lane's worker (a slow connector never holds a lane).
 */
public final class EditTriggers implements AutoCloseable {

    static final String CONSUMER = "reactive";
    private static final Logger LOG = LoggerFactory.getLogger(EditTriggers.class);

    private final EditLog log;
    private final Function<ComputeEvent, Map<String, Object>> publish;
    private final Consumer<Map<String, Object>> told;
    private final ExecutorService ordered = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("edit-triggers").factory());

    /**
     * @param publish delivers an event to the reactive connectors (the hub's publish), returning per connector what it did
     * @param told hears, per change, which connectors were told and what they did (the submitter's recompute status)
     */
    public EditTriggers(EditLog log, Function<ComputeEvent, Map<String, Object>> publish, Consumer<Map<String, Object>> told) {
        this.log = log;
        this.publish = publish;
        this.told = told;
    }

    /** The server's hub. */
    public static Function<ComputeEvent, Map<String, Object>> hub() {
        return e -> ReactiveHub.get().publish(e);
    }

    /** The event a change sends at a moment ({@code applied}, {@code confirmed} or {@code refused}). */
    static ComputeEvent event(EditLog.Row row, String when) {
        Change c = row.change();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("when", when);
        payload.put("changeId", c.id());
        payload.put("lane", row.lane());
        payload.put("seq", row.seq());
        payload.put("target", c.target());
        String id = "applied".equals(when) ? c.id() : c.id() + "/" + when;
        return new ComputeEvent(id, ComputeEvent.WRITE_BACK, c.kind(), c.entity(), c.businessDate(), Map.of("L" + row.lane(), row.seq()),
                row.appliedAt() == null ? java.time.Instant.now() : row.appliedAt(), c.by(), c.paths(), payload);
    }

    /** A change was applied: tell the triggers (in order), then move the lane's cursor. */
    public void applied(EditLog.Row row) {
        ordered.execute(() -> send(row, "applied", true));
    }

    /** A source confirmed or refused a change's command. */
    public void command(EditLog.Row row, boolean confirmed) {
        ordered.execute(() -> send(row, confirmed ? "confirmed" : "refused", false));
    }

    private void send(EditLog.Row row, String when, boolean moveCursor) {
        try {
            Map<String, Object> out = publish.apply(event(row, when));
            if (moveCursor) {
                log.cursor(CONSUMER, row.lane(), row.seq());
            }
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", "recompute");
            n.put("id", row.changeId());
            n.put("kind", row.change().kind());
            n.put("entity", row.change().entity());
            n.put("by", row.change().by());
            n.put("when", when);
            n.put("watermark", "L" + row.lane() + ":" + row.seq());
            n.put("recompute", out);
            told.accept(n);
        } catch (RuntimeException e) {
            LOG.warn("write-back: the triggers could not be told of {} ({}): {}; they catch up when the lane is next replayed", row.changeId(), when,
                    e.getMessage());
        }
    }

    /** A lane gained (start, failover): the applied changes the triggers were not told of yet, in seq order. */
    public void replay(int lane) {
        ordered.execute(() -> {
            try {
                long after = log.cursor(CONSUMER, lane);
                List<EditLog.Row> rows;
                do {
                    rows = log.appliedAfter(lane, after, 500);
                    for (EditLog.Row r : new ArrayList<>(rows)) {
                        send(r, "applied", true);
                        after = r.seq();
                    }
                } while (rows.size() == 500);
            } catch (RuntimeException e) {
                LOG.warn("write-back: lane {} could not be replayed to the triggers: {}", lane, e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        ordered.shutdownNow();
    }
}

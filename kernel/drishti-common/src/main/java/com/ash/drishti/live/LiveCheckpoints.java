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
package com.ash.drishti.live;

import com.ash.drishti.api.live.LivePosition;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a replay must not bring back (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 6): a transport that replays its log at every
 * start would re-apply rows a load already absorbed. Per kind: for {@code absorb: by-cutoff} the cut-off absorbed up to (a replayed row
 * changed on or before it is absorbed again), for {@code absorb: reset} the position of every stream partition at the reset (a replayed
 * message at or before it is dropped). {@code by-order} needs none: the lake's row wins on order. Kept in the Drishti database through a
 * {@link LiveCheckpointStore} (tables {@code drishti_live_checkpoint} and {@code drishti_live_position}), read when the feed starts and
 * written after every reconcile; without a store they live in memory only.
 */
public final class LiveCheckpoints {

    private static final System.Logger LOG = System.getLogger(LiveCheckpoints.class.getName());

    /** One kind's checkpoint: the cut-off absorbed up to, and the positions reset at (stream/partition to offset). */
    public record Kind(Instant cutoff, Map<String, Long> positions) {

        public Kind {
            positions = positions == null ? Map.of() : Map.copyOf(positions);
        }
    }

    private final String connector;
    private final LiveCheckpointStore store;
    private final Map<String, Kind> kinds = new ConcurrentHashMap<>();

    /** Checkpoints kept in memory only. */
    public static LiveCheckpoints memory() {
        return new LiveCheckpoints("", LiveCheckpointStore.NONE);
    }

    /**
     * @param connector this live connector's name
     * @param store     where its checkpoints are kept (null: in memory only); what it holds for the connector is read now
     */
    public LiveCheckpoints(String connector, LiveCheckpointStore store) {
        this.connector = connector == null ? "" : connector;
        this.store = store == null ? LiveCheckpointStore.NONE : store;
        try {
            kinds.putAll(this.store.load(this.connector));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "live checkpoints of " + this.connector + " cannot be read (" + e.getMessage()
                    + "): a replay may bring back rows a load absorbed until the next load");
        }
    }

    /** True when {@code r} lies before the kind's checkpoint: a load absorbed it already. */
    public boolean absorbed(LiveRecord r) {
        Kind k = kinds.get(r.kind());
        if (k == null) {
            return false;
        }
        if (k.cutoff() != null && r.changedAt() != null && !r.changedAt().isAfter(k.cutoff())) {
            return true;
        }
        LivePosition p = r.position();
        Long at = p == null ? null : k.positions().get(p.stream() + "/" + p.partition());
        return at != null && p.offset() >= 0 && p.offset() <= at;
    }

    /** Records a by-cutoff reconcile of {@code kind}. */
    public void cutoff(String kind, Instant cutoff) {
        save(kind, kinds.merge(kind, new Kind(cutoff, Map.of()), (a, b) -> new Kind(a.cutoff() == null || b.cutoff().isAfter(a.cutoff()) ? b.cutoff() : a.cutoff(),
                a.positions())));
    }

    /** Records a reset of {@code kind} at these positions (stream/partition to the last offset applied). */
    public void reset(String kind, Map<String, Long> positions) {
        save(kind, kinds.merge(kind, new Kind(null, positions), (a, b) -> {
            Map<String, Long> m = new LinkedHashMap<>(a.positions());
            b.positions().forEach((k, v) -> m.merge(k, v, Math::max));
            return new Kind(a.cutoff(), m);
        }));
    }

    /** One kind's checkpoint, or null. */
    public Kind of(String kind) {
        return kinds.get(kind);
    }

    private void save(String kind, Kind checkpoint) {
        try {
            store.save(connector, kind, checkpoint);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "live checkpoint of " + connector + "/" + kind + " cannot be written: " + e.getMessage());
        }
    }
}

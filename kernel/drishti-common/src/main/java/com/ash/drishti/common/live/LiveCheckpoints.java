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
package com.ash.drishti.common.live;

import com.ash.drishti.api.live.LivePosition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a replay must not bring back (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 6): a transport that replays its log at every
 * start would re-apply rows a load already absorbed. Per kind: for {@code absorb: by-cutoff} the cut-off absorbed up to (a replayed row
 * changed on or before it is absorbed again), for {@code absorb: reset} the position of every stream partition at the reset (a replayed
 * message at or before it is dropped). {@code by-order} needs none: the lake's row wins on order. Kept in one JSON file per live connector
 * ({@code <data folder>/live/checkpoints/<connector>.json}, absolute), written whole after every reconcile.
 */
public final class LiveCheckpoints {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(LiveCheckpoints.class.getName());

    /** One kind's checkpoint: the cut-off absorbed up to, and the positions reset at (stream/partition to offset). */
    public record Kind(Instant cutoff, Map<String, Long> positions) {

        public Kind {
            positions = positions == null ? Map.of() : Map.copyOf(positions);
        }
    }

    private final Path file;
    private final Map<String, Kind> kinds = new ConcurrentHashMap<>();

    /** @param file this connector's checkpoint file, absolute; null keeps them in memory only */
    public LiveCheckpoints(Path file) {
        if (file != null && !file.isAbsolute()) {
            throw new IllegalArgumentException("the live checkpoints' file must be absolute: " + file);
        }
        this.file = file;
        if (file != null && Files.isRegularFile(file)) {
            try {
                Map<String, Map<String, Object>> raw = JSON.readValue(file.toFile(), new TypeReference<Map<String, Map<String, Object>>>() {});
                raw.forEach((kind, m) -> {
                    Instant cutoff = m.get("cutoff") == null ? null : Instant.parse(String.valueOf(m.get("cutoff")));
                    Map<String, Long> pos = new LinkedHashMap<>();
                    if (m.get("positions") instanceof Map<?, ?> p) {
                        p.forEach((k, v) -> pos.put(String.valueOf(k), ((Number) v).longValue()));
                    }
                    kinds.put(kind, new Kind(cutoff, pos));
                });
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "live checkpoints " + file + " cannot be read (" + e.getMessage()
                        + "): a replay may bring back rows a load absorbed until the next load");
            }
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
        kinds.merge(kind, new Kind(cutoff, Map.of()), (a, b) -> new Kind(a.cutoff() == null || b.cutoff().isAfter(a.cutoff()) ? b.cutoff() : a.cutoff(),
                a.positions()));
        save();
    }

    /** Records a reset of {@code kind} at these positions (stream/partition to the last offset applied). */
    public void reset(String kind, Map<String, Long> positions) {
        kinds.merge(kind, new Kind(null, positions), (a, b) -> {
            Map<String, Long> m = new LinkedHashMap<>(a.positions());
            b.positions().forEach((k, v) -> m.merge(k, v, Math::max));
            return new Kind(a.cutoff(), m);
        });
        save();
    }

    /** One kind's checkpoint, or null. */
    public Kind of(String kind) {
        return kinds.get(kind);
    }

    private void save() {
        if (file == null) {
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        kinds.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cutoff", v.cutoff() == null ? null : v.cutoff().toString());
            m.put("positions", v.positions());
            out.put(k, m);
        });
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), out);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "live checkpoints " + file + " cannot be written: " + e.getMessage());
        }
    }
}

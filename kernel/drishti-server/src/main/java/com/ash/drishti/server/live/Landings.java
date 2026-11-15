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

import com.ash.drishti.kernel.api.data.KindLanding;
import com.ash.drishti.lakeload.LakeLoad;
import com.ash.drishti.live.Landing;
import com.ash.drishti.server.loads.LoadService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Where the live connectors' landed kinds go (docs/architecture/LIVE_CONNECTORS_DESIGN.md sections 2.4 and 7.5). The engine that owns a
 * kind provides its {@link KindLanding} (the VaR engine's for scenario PVs and P&amp;L vectors: its checks, the lake writer, the data-load
 * signal), and the kernel's live lander hands each window of that kind to it. A kind no engine takes is landed by the lake loader: the
 * window is written as one JSONL file under {@code <data folder>/live/landing/<connector>/}, appended to the kind's table of the
 * connector's {@code lake} (Delta, Iceberg or the file store, as the lake file says) as one batch, and announced as a data-load of the
 * connector's {@code pack}; a connector that names no lake lands nothing, said in its reject log. The landings are looked up at every
 * window, so an engine switched on or off is followed at once.
 */
@Component
public final class Landings implements Landing {

    /** The source of a load the live lander announces ({@code live:<connector>}); the reconciler leaves such loads alone. */
    public static final String SOURCE = "live:";

    private static final Logger LOG = LoggerFactory.getLogger(Landings.class);

    private final ObjectProvider<KindLanding> engines;
    private final ObjectProvider<LoadService> loads;

    public Landings(com.ash.drishti.engine.source.SourceRegistry registry, ObjectProvider<KindLanding> engines, ObjectProvider<LoadService> loads) {
        this.engines = engines;
        this.loads = loads;
        registry.landing(this);
    }

    /** The landing of {@code kind}, if an engine takes it. */
    public Optional<KindLanding> of(String kind) {
        return engines.orderedStream().filter(e -> e.kinds().contains(kind)).findFirst();
    }

    @Override
    public Landed land(Batch batch) {
        Optional<KindLanding> l = of(batch.kind());
        if (l.isEmpty()) {
            return lakeLoad(batch);
        }
        try {
            KindLanding.Landed r = l.get().land(new KindLanding.Batch(batch.connector(), batch.kind(), batch.date(), batch.batchId(),
                    batch.rows().stream().map(ObjectNode::toString).toList(), batch.settings()));
            return new Landed(r.written(), r.rejects().stream().map(x -> new Reject(x.key(), x.code(), x.why())).toList(), r.error());
        } catch (RuntimeException e) {
            return new Landed(0, List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** A kind no engine takes: one append of the window to the kind's table of the connector's lake, by the lake loader, then announced. */
    private Landed lakeLoad(Batch b) {
        String lake = b.settings().get("lake");
        if (lake == null || lake.isBlank()) {
            return new Landed(0, List.of(), "kind " + b.kind() + " has no landing on this server: no engine takes it and connector " + b.connector()
                    + " names no lake (lake: <a lake of config/lakes/>)");
        }
        Path dir = Path.of(com.ash.drishti.api.DataDir.under("live")).resolve("landing").resolve(b.connector());
        Path file = dir.resolve(b.batchId() + ".jsonl");
        try {
            Files.createDirectories(dir);
            List<String> lines = new ArrayList<>(b.rows().size());
            b.rows().forEach(r -> lines.add(r.toString()));
            Files.write(file, lines, StandardCharsets.UTF_8);
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            int code = LakeLoad.run(new String[] {"--set", "source.files=" + file, "--set", "target.lake=" + lake.strip(), "--kind", b.kind(),
                "--as-of", b.date().toString(), "--mode", "append", "--set", "target.batch-id=" + b.batchId(), "--no-run-log", "-q"},
                    new PrintStream(said, true, StandardCharsets.UTF_8));
            if (code != 0) {
                String why = said.toString(StandardCharsets.UTF_8).strip();
                return new Landed(0, List.of(), "the lake loader could not append the window to lake " + lake + " (exit " + code + "): "
                        + (why.length() > 500 ? why.substring(0, 500) + " ..." : why));
            }
            announce(b);
            return new Landed(b.rows().size(), List.of(), null);
        } catch (IOException | RuntimeException e) {
            return new Landed(0, List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.debug("{} not removed: {}", file, e.getMessage());
            }
        }
    }

    private void announce(Batch b) {
        LoadService l = loads.getIfAvailable();
        String pack = b.settings().get("pack");
        if (l == null || pack == null || pack.isBlank()) {
            return;                                                     // written; no pack named: the next read sees the new version
        }
        try {
            l.announce(pack.strip(), new LoadService.Announcement(b.kind(), b.date().toString(), null, "ready", (long) b.rows().size(), 0L,
                    SOURCE + b.connector(), b.batchId(), "a window of " + b.connector() + ": " + b.rows().size() + " intraday rows"), "live-lander");
        } catch (com.ash.drishti.common.DrishtiException e) {
            LOG.info("{}: the window {} of {} was written to its lake; announcing it to pack {} failed: {}", b.connector(), b.batchId(), b.kind(),
                    pack, e.getMessage());
        }
    }
}

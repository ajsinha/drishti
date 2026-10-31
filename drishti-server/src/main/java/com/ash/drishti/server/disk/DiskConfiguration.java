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
package com.ash.drishti.server.disk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/** The server's local disk (2.9.7, RESOURCES_AND_SCALE.md section 7): its settings and the housekeeping at start. */
@Configuration
@EnableConfigurationProperties(DiskProperties.class)
public class DiskConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DiskConfiguration.class);
    private final DiskProperties props;

    public DiskConfiguration(DiskProperties props) {
        this.props = props;
    }

    /**
     * The disk budget (O5): the hot files' tiers register themselves when made; here the folders measured but freed by their owners or
     * by hand: the heap dumps, the temporary files, and the server's log. Checked every {@code drishti.disk.check-interval}.
     */
    @org.springframework.context.annotation.Bean(destroyMethod = "")
    public DiskBudget diskBudget(org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.engine.QueryService> bi) {
        java.nio.file.Path root = java.nio.file.Path.of(com.ash.drishti.api.DataDir.root()).toAbsolutePath().normalize();
        DiskBudget b = new DiskBudget(props, root);
        b.register(DiskBudget.measured("dumps", Dumps.folder(props.dumpsDir()), () -> 0));
        b.register(DiskBudget.measured("tmp/server", java.nio.file.Path.of(com.ash.drishti.common.TempFiles.configuredBase()), () -> 0));
        b.register(DiskBudget.measured("cache (connectors)", root.resolve("cache"), () -> 0));
        b.register(new BiStoreFolder(bi));
        java.lang.ref.WeakReference<DiskBudget> me = new java.lang.ref.WeakReference<>(b);   // a context closed (tests) is not kept alive
        java.util.concurrent.ScheduledFuture<?>[] task = new java.util.concurrent.ScheduledFuture<?>[1];
        task[0] = CHECK.scheduleWithFixedDelay(() -> {
            DiskBudget x = me.get();
            if (x == null) {
                task[0].cancel(false);
                return;
            }
            try {
                x.trim();
            } catch (RuntimeException e) {
                LOG.debug("disk: the periodic check failed: {}", e.toString());
            }
        }, props.checkInterval().toMillis(), props.checkInterval().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        return b;
    }

    private static final java.util.concurrent.ScheduledExecutorService CHECK = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "drishti-disk-check");
        t.setDaemon(true);
        return t;
    });

    /** The BI store (its layers) in the disk budget: capped by {@code drishti.bi.query.store-max-gb}, freed by its retention. */
    private static final class BiStoreFolder implements DiskBudget.Folder {
        private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.engine.QueryService> bi;

        BiStoreFolder(org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.engine.QueryService> bi) {
            this.bi = bi;
        }

        public String name() {
            return "bi-store";
        }

        public java.nio.file.Path path() {
            com.ash.drishti.server.bi.engine.QueryService q = bi.getIfAvailable();
            return q == null ? java.nio.file.Path.of(com.ash.drishti.api.DataDir.under("bi-store")) : q.layers().root();
        }

        public long limitBytes() {
            com.ash.drishti.server.bi.engine.QueryService q = bi.getIfAvailable();
            return q == null ? 0 : (long) (q.props().storeMaxGb() * (1L << 30));
        }

        public long usedBytes() {
            return DiskBudget.bytes(path());
        }

        public long free(long target) {
            com.ash.drishti.server.bi.engine.QueryService q = bi.getIfAvailable();
            return q == null ? 0 : q.layers().trim(target, q.props().storeMaxDates()).freedBytes();
        }
    }

    /** At start: the heap dumps beyond {@code drishti.disk.max-dumps} go (the newest stay). */
    @EventListener(ApplicationReadyEvent.class)
    public void housekeeping() {
        java.nio.file.Path dumps = Dumps.folder(props.dumpsDir());
        int gone = Dumps.prune(dumps, props.maxDumps());
        if (gone > 0) {
            LOG.info("disk: {} heap dumps beyond the newest {} deleted from {}", gone, props.maxDumps(), dumps);
        }
    }
}

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
package com.ash.drishti.server.node;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The readiness gate of an engine's newest business date (CD1): ready when the engine is off, when no date has data, when the newest date is
 * open here, or when it cannot be opened (refused as not one run, or its load failed at this data version: the engine answers from the date
 * before or says why, and waiting would not change that). Otherwise it starts opening the date (one at a time, on a virtual thread) and
 * says it waits for it. A failed opening is not tried again by readiness until the date's data version changes.
 */
public final class NewestDate implements ReadinessGate {

    private static final Logger LOG = LoggerFactory.getLogger(NewestDate.class);

    /** What the gate reads of its engine. */
    public interface Engine {
        /** True when the engine is switched on here. */
        boolean enabled();

        /** The newest business date with data, or null. */
        LocalDate latest();

        /** True when the date is open here (loaded and answering). */
        boolean open(LocalDate date);

        /** True when the date was refused at its data version now (the date before answers). */
        boolean refused(LocalDate date);

        /** The date's data version now. */
        String version(LocalDate date);

        /** Opens the date and waits for it (throws when it cannot). */
        void openNow(LocalDate date);
    }

    private final String name;
    private final Engine engine;
    private final AtomicBoolean opening = new AtomicBoolean();
    private volatile String failed = "";

    public NewestDate(String name, Engine engine) {
        this.name = name;
        this.engine = engine;
    }

    /** An engine from functions (the services keep their methods package-private). */
    public static Engine engine(Supplier<Boolean> enabled, Supplier<LocalDate> latest, Predicate<LocalDate> open, Predicate<LocalDate> refused,
            Function<LocalDate, String> version, java.util.function.Consumer<LocalDate> openNow) {
        return new Engine() {
            public boolean enabled() {
                return Boolean.TRUE.equals(enabled.get());
            }

            public LocalDate latest() {
                return latest.get();
            }

            public boolean open(LocalDate d) {
                return open.test(d);
            }

            public boolean refused(LocalDate d) {
                return refused.test(d);
            }

            public String version(LocalDate d) {
                return version.apply(d);
            }

            public void openNow(LocalDate d) {
                openNow.accept(d);
            }
        };
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String kind() {
        return "engines";
    }

    @Override
    public List<String> waitingFor() {
        if (!engine.enabled()) {
            return List.of();
        }
        LocalDate latest;
        try {
            latest = engine.latest();
        } catch (RuntimeException e) {
            return List.of();                                           // its sources cannot say: the engine's own errors say why
        }
        if (latest == null || engine.open(latest) || engine.refused(latest)) {
            return List.of();
        }
        String key;
        try {
            key = latest + "|" + engine.version(latest);
        } catch (RuntimeException e) {
            key = latest + "|?";
        }
        if (key.equals(failed)) {
            return List.of();
        }
        if (opening.compareAndSet(false, true)) {
            String k = key;
            Thread.ofVirtual().name("drishti-ready-" + name + "-" + latest).start(() -> {
                try {
                    engine.openNow(latest);
                } catch (RuntimeException e) {
                    failed = k;
                    LOG.warn("readiness: {} could not open its newest date {} ({}); readiness does not wait for it", name, latest, e.getMessage());
                } finally {
                    opening.set(false);
                }
            });
        }
        return List.of(name + ": the newest business date " + latest + " is opening");
    }
}

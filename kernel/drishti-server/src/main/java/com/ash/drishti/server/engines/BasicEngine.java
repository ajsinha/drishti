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
package com.ash.drishti.server.engines;

import com.ash.drishti.kernel.api.engines.ComputeEngine;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * A {@link ComputeEngine} made of an engine's own parts: what {@code start} and {@code stop} do, what it holds and its threads' names. The
 * engine's configuration makes one bean of it (VaR, exposure, sensitivities, FRTB-SA, SIMM, the in-memory lake); its service asks
 * {@link #on(BooleanSupplier)} before any work.
 */
public final class BasicEngine implements ComputeEngine {

    private final String id;
    private Runnable start = () -> { };
    private Runnable stop = () -> { };
    private LongSupplier memory = () -> 0;
    private IntSupplier dates = () -> 0;
    private List<String> threads = List.of();
    private volatile Boolean running;

    public BasicEngine(String id) {
        this.id = id;
    }

    public BasicEngine onStart(Runnable r) {
        this.start = r;
        return this;
    }

    public BasicEngine onStop(Runnable r) {
        this.stop = r;
        return this;
    }

    public BasicEngine memory(LongSupplier bytes) {
        this.memory = bytes;
        return this;
    }

    public BasicEngine dates(IntSupplier open) {
        this.dates = open;
        return this;
    }

    public BasicEngine threads(String... prefixes) {
        this.threads = List.of(prefixes);
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void start() {
        start.run();
    }

    @Override
    public void stop() {
        stop.run();
    }

    @Override
    public long memoryBytes() {
        try {
            return memory.getAsLong();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public int datesOpen() {
        try {
            return dates.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public List<String> threadPrefixes() {
        return threads;
    }

    @Override
    public void running(boolean on) {
        this.running = on;
    }

    @Override
    public Boolean running() {
        return running;
    }

    /** Whether the engine may work now: as the registry says, else (no registry: a test of the service alone) the fallback. */
    public boolean on(BooleanSupplier fallback) {
        Boolean r = running;
        return r != null ? r : fallback.getAsBoolean();
    }
}

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
package com.ash.drishti.server.nativelib;

import com.ash.drishti.compute.linalg.Blas;
import com.ash.drishti.server.engines.ComputeEngine;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * "Linear algebra: OpenBLAS" as a switch of Admin -> Engines (docs/admin/OPERATIONS.md section 2.1): the engine-switch mechanism keeps the
 * administrator's choice ({@code config/engines/openblas.yaml}), audits it, requires the admin right and reaches other nodes; this engine
 * only acts on it, live. Off: the pure-Java path, chosen ({@code java (chosen by admin: switched off by <who> at <when>)}, a notice, not the
 * fallback warning). On: the self-test against Java first, then OpenBLAS; a failed self-test refuses the switch in words (the engine shows
 * the reason, the Java path stays). The members' paths meet in the cluster agreement ({@link NativeLibraries#agree}).
 */
@Component
public class OpenBlasEngine implements ComputeEngine {

    /** The engine's catalogue id. */
    public static final String ID = "openblas";

    private final NativeLibraries nativeLibraries;
    private volatile Boolean running;
    private volatile String change;

    public OpenBlasEngine(NativeLibraries nativeLibraries) {
        this.nativeLibraries = nativeLibraries;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void changing(String actor, String at, String reason) {
        change = (actor == null || actor.isBlank() ? "" : "by " + actor + " ") + "at " + at + (reason == null || reason.isBlank() ? "" : " (" + reason + ")");
    }

    /** On: nothing to do at start or when OpenBLAS already serves; after an administrator's off, the self-test and then OpenBLAS. */
    @Override
    public void start() {
        if (Blas.adminJava() == null) {
            return;
        }
        String refused = Blas.switchToNative();
        if (refused != null) {
            throw new IllegalStateException(refused);
        }
        change = null;
        nativeLibraries.switched();
    }

    /** Off: every call from now on runs on Java (the library stays loaded and is not called). */
    @Override
    public void stop() {
        String c = change;
        Blas.switchToJava("switched off " + (c != null ? c : "in config/engines/openblas.yaml at " + Instant.now()));
        change = null;
        nativeLibraries.switched();
    }

    @Override
    public void running(boolean on) {
        running = on;
    }

    @Override
    public Boolean running() {
        return running;
    }
}

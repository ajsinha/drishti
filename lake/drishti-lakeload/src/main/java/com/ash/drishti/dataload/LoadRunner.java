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
package com.ash.drishti.dataload;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs one load file through the loader and says how it ended: {@link LoaderProcess} (the child Java process) in the server; a test may
 * count the runs or stop the "server" after one.
 */
@FunctionalInterface
public interface LoadRunner {

    /**
     * How a load ended.
     *
     * @param code the loader's exit code ({@code LakeLoad.OK}, {@code PARTIAL}, {@code FAILED}, {@code LOCKED})
     * @param timedOut true when it was stopped (load-timeout, the server stopping)
     * @param detail what the loader said last, or why it was stopped
     */
    record Result(int code, boolean timedOut, String detail) {}

    /** Runs the loader on {@code config} with {@code extra} arguments ({@code --dry-run}), its output appended to {@code log}. */
    Result run(Path config, List<String> extra, Path log);
}

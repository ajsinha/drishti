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
package com.ash.drishti.server.pivot.functions;

import com.ash.drishti.bicompute.custom.CustomFunctions;
import com.ash.drishti.bicompute.harness.FunctionCheck;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Custom pivot functions' settings, {@code drishti.pivot.functions} (BI_GUIDE.md, custom functions; config/engines/pivot.yaml).
 *
 * @param formula DEL formulas as custom functions
 * @param sqlMacros SQL macros as custom functions
 * @param requireApproval sharing a function needs an approver who is not its author
 * @param approverRole the role whose members may approve (administrators always may)
 * @param holisticMaxRows the most rows of one cell a holistic custom function receives
 * @param holisticMaxMb the most megabytes of one cell's rows
 * @param harness the harness's tolerance, seeds and cost limit
 * @param python Python functions: off as shipped
 * @param java Java plug-ins: off as shipped
 */
@ConfigurationProperties("drishti.pivot.functions")
public record PivotFunctionProperties(Boolean formula, Boolean sqlMacros, Boolean requireApproval, String approverRole, Long holisticMaxRows,
        Long holisticMaxMb, Harness harness, Python python, Java java) {

    /** {@code drishti.pivot.functions.harness}. */
    public record Harness(Double tolerance, Integer seeds, Long maxNsPerRow) {}

    /**
     * {@code drishti.pivot.functions.python}.
     *
     * @param enabled Python functions on this node
     * @param interpreter the Python to run (blank: python3, python on Windows)
     * @param workers worker processes in the {@code pivot-fn} pool
     * @param timeoutSeconds the longest one call (a batch, a merge) may take
     * @param memoryMb memory per worker
     * @param batchRows rows in each batch a function is fed
     */
    public record Python(Boolean enabled, String interpreter, Integer workers, Integer timeoutSeconds, Integer memoryMb, Integer batchRows) {}

    /** {@code drishti.pivot.functions.java}: plug-in jars in {@code dir}. */
    public record Java(Boolean enabled, String dir) {}

    public PivotFunctionProperties {
        formula = formula == null || formula;
        sqlMacros = sqlMacros == null || sqlMacros;
        requireApproval = requireApproval == null || requireApproval;
        approverRole = approverRole == null || approverRole.isBlank() ? "pivot-approver" : approverRole.strip();
        holisticMaxRows = holisticMaxRows == null || holisticMaxRows <= 0 ? 5_000_000L : holisticMaxRows;
        holisticMaxMb = holisticMaxMb == null || holisticMaxMb <= 0 ? 512L : holisticMaxMb;
        harness = harness == null ? new Harness(null, null, null) : harness;
        harness = new Harness(harness.tolerance() == null || harness.tolerance() <= 0 ? 1e-12 : harness.tolerance(),
                harness.seeds() == null || harness.seeds() <= 0 ? 5 : harness.seeds(), harness.maxNsPerRow() == null ? 2000L : harness.maxNsPerRow());
        python = python == null ? new Python(false, "", 2, 30, 1024, 65_536) : python;
        python = new Python(Boolean.TRUE.equals(python.enabled()), python.interpreter() == null ? "" : python.interpreter(),
                python.workers() == null || python.workers() <= 0 ? 2 : python.workers(),
                python.timeoutSeconds() == null || python.timeoutSeconds() <= 0 ? 30 : python.timeoutSeconds(),
                python.memoryMb() == null || python.memoryMb() <= 0 ? 1024 : python.memoryMb(),
                python.batchRows() == null || python.batchRows() <= 0 ? 65_536 : python.batchRows());
        java = java == null ? new Java(false, "./plugins/pivot-functions") : java;
        java = new Java(Boolean.TRUE.equals(java.enabled()), java.dir() == null || java.dir().isBlank() ? "./plugins/pivot-functions" : java.dir());
    }

    /** The module's settings. */
    public CustomFunctions.Settings settings() {
        return new CustomFunctions.Settings(formula, sqlMacros, python.enabled(), java.enabled(), requireApproval,
                new FunctionCheck.Options(harness.tolerance(), harness.seeds(), harness.maxNsPerRow(), 0, 0), holisticMaxRows, holisticMaxMb,
                python.batchRows());
    }
}

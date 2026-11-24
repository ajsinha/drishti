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
package com.ash.drishti.testkit;

/**
 * The slow-machine factor of a test's time budget. A budget is measured on the reference machine (8 or more processors); a machine
 * with fewer (a 4-processor CI runner, a 2-processor laptop) gets the budget times {@code 8 / processors}, never less than the
 * measured budget. {@code DRISHTI_TEST_SLOW_FACTOR} (environment) or {@code drishti.test.slow-factor} (system property) sets the
 * factor outright, for a machine slow for another reason.
 * <p>
 * Only the part of a budget that is waiting on the machine is scaled: a protocol's own times (a suspect-after, a timeout the test
 * checks) stay as they are, so the logic is checked exactly and only the slack grows.
 */
public final class MachineSpeed {

    /** The processors of the machine the budgets were measured on. */
    public static final int REFERENCE_PROCESSORS = 8;

    private MachineSpeed() {
    }

    /** 1 on the reference machine or a bigger one; {@code 8 / processors} on a smaller one; or the factor set outright. */
    public static double factor() {
        String set = System.getProperty("drishti.test.slow-factor", System.getenv("DRISHTI_TEST_SLOW_FACTOR"));
        if (set != null && !set.isBlank()) {
            return Math.max(1.0, Double.parseDouble(set.strip()));
        }
        return Math.max(1.0, (double) REFERENCE_PROCESSORS / Runtime.getRuntime().availableProcessors());
    }

    /** {@code budget} on this machine: the measured budget times {@link #factor()}. */
    public static double scaled(double budget) {
        return budget * factor();
    }

    /** {@code budgetMs} on this machine, in whole milliseconds. */
    public static long scaled(long budgetMs) {
        return Math.round(budgetMs * factor());
    }
}

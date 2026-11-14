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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A compare date such as "1 week" ago may hold P&amp;L but no scenario set: the explanation answers from the nearest earlier date that has both.
 */
class VarCompareDateRollbackTest {

    private static final TreeSet<LocalDate> HELD = new TreeSet<>(java.util.List.of(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2),
            LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 4)));

    @Test
    void rollsBackPastDatesWithPnlButNoSet() {
        TreeSet<LocalDate> withSet = new TreeSet<>(java.util.List.of(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2)));
        assertThat(VarLoader.nearestWithSet(LocalDate.of(2026, 6, 4), HELD::lower, withSet::contains)).isEqualTo(LocalDate.of(2026, 6, 2));
    }

    @Test
    void aDateWithBothIsItself() {
        assertThat(VarLoader.nearestWithSet(LocalDate.of(2026, 6, 3), HELD::lower, HELD::contains)).isEqualTo(LocalDate.of(2026, 6, 3));
    }

    @Test
    void noneEarlierWithASetIsNull() {
        assertThat(VarLoader.nearestWithSet(LocalDate.of(2026, 6, 4), HELD::lower, d -> false)).isNull();
    }
}

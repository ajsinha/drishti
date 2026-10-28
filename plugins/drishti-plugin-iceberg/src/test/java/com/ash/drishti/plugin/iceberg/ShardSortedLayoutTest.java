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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A table the parallel lake loader laid out by legal entity, desk and id ({@code drishti.layout.sort-by}) is left in that order by the
 * relayout of lake maintain (its files overlap in id by design); a table sorted by id alone is relaid as before.
 */
class ShardSortedLayoutTest {

    @Test
    void onlyATableSortedByMoreThanTheIdIsLeftInItsOrder() {
        assertThat(IcebergMaintenance.shardSorted(Map.of("drishti.layout.sort-by", "LEGAL_ENTITY,DESK,id"))).isTrue();
        assertThat(IcebergMaintenance.shardSorted(Map.of("drishti.layout.sort-by", "id"))).isFalse();
        assertThat(IcebergMaintenance.shardSorted(Map.of())).isFalse();
    }
}

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
package com.ash.drishti.engine.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchQueryTest {

    private static boolean holds(SearchQuery q, Map<String, Object> doc) {
        return Values.truthy(new ElCompiler().compile(q.condition()).eval(EvalContext.of(DataNode.of(doc), Formats.defaults())));
    }

    @Test
    void friendlySyntaxBecomesRachanaEl() {
        SearchQuery q = SearchQuery.parse("TRD where mtm > 1.5m and currency = 'EUR' or not live order by mtm desc limit 20");
        assertThat(q.head()).isEqualTo("TRD");
        assertThat(q.condition()).isEqualTo("$.mtm > 1500000 && $.currency == 'EUR' || ! $.live");
        assertThat(q.orderBy()).isEqualTo("$.mtm");
        assertThat(q.descending()).isTrue();
        assertThat(q.limit()).isEqualTo(20);
        assertThat(q.fields()).containsExactly("$.mtm", "$.currency", "$.live");
    }

    @Test
    void containsNestedPathsAndStringsWithKeywords() {
        SearchQuery q = SearchQuery.parse("trade where counterparty.name contains \"Meridian\" and legs[0].rate >= 0.04");
        assertThat(q.condition()).isEqualTo("contains($.counterparty.name, 'Meridian') && $.legs[0].rate >= 0.04");
        SearchQuery s = SearchQuery.parse("CPTY where name contains 'order by and limit 5'");
        assertThat(s.limit()).isEqualTo(SearchQuery.DEFAULT_LIMIT);          // keywords inside a string are text
        assertThat(holds(s, Map.of("name", "Acme ORDER BY and LIMIT 5 plc"))).isTrue();
    }

    @Test
    void evaluatesAgainstDocuments() {
        SearchQuery q = SearchQuery.parse("TRD where notional >= 250m and tags contains 'xva' and status <> 'Matured'");
        assertThat(holds(q, Map.of("notional", 300_000_000, "tags", List.of("rates", "XVA"), "status", "Live"))).isTrue();
        assertThat(holds(q, Map.of("notional", 200_000_000, "tags", List.of("xva"), "status", "Live"))).isFalse();
        assertThat(holds(q, Map.of("notional", 300_000_000, "tags", List.of("xva"), "status", "Matured"))).isFalse();
        assertThat(holds(SearchQuery.parse("TRD where startswith(id, 'irs-')"), Map.of("id", "IRS-48213"))).isTrue();
    }

    @Test
    void recognisesSearchesAndRejectsNonsense() {
        assertThat(SearchQuery.looksLikeSearch("TRD where mtm > 0")).isTrue();
        assertThat(SearchQuery.looksLikeSearch("TRD order by mtm desc")).isTrue();
        assertThat(SearchQuery.looksLikeSearch("TRD IRS-48213")).isFalse();
        assertThatThrownBy(() -> SearchQuery.parse("TRD where name = 'open")).isInstanceOf(DrishtiException.class).hasMessageContaining("not closed");
        assertThatThrownBy(() -> SearchQuery.parse("TRD where mtm > 5zz")).hasMessageContaining("suffix");
        assertThat(SearchQuery.parse("TRD where x > 1 limit 99999").limit()).isEqualTo(SearchQuery.MAX_LIMIT);
    }
}

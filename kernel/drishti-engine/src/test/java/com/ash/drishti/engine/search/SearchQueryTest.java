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
        assertThat(q.condition()).isEqualTo("$.mtm > 1500000 && lower($.currency) == lower('EUR') || ! $.live");
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
        assertThatThrownBy(() -> SearchQuery.parse("TRD where x > 1 limit 99999")).hasMessageContaining("from 1 to " + SearchQuery.MAX_LIMIT);
    }

    @Test
    void pickListsNameEntitiesByIdOrTitleOrByFieldValues() {
        SearchQuery prefix = SearchQuery.pick("TRD MX-200000 <GO>");
        assertThat(prefix.head()).isEqualTo("TRD");
        assertThat(prefix.idPattern()).isEqualTo("MX-200000");
        assertThat(prefix.condition()).isNull();
        assertThat(SearchQuery.matches("mx-200000", "MX-20000042", "Swap")).isTrue();
        assertThat(SearchQuery.matches("mx-200000", "MX-30000042", "Swap")).isFalse();
        assertThat(SearchQuery.matches("north", "CP-NORTHBRIDGE", "Northbridge Capital")).isTrue();    // the title
        assertThat(SearchQuery.matches("*100*", "X-21004", null)).isTrue();
        assertThat(SearchQuery.matches("MX-2*2", "MX-20000042", null)).isTrue();
        assertThat(SearchQuery.matches("MX-2*2", "MX-20000043", null)).isFalse();

        SearchQuery byValue = SearchQuery.pick("TRD productType=Revolver");
        assertThat(byValue.idPattern()).isNull();
        assertThat(byValue.condition()).isEqualTo("lower($.productType) == lower('Revolver')");      // case never matters
        assertThat(byValue.fields()).containsExactly("$.productType");
        assertThat(SearchQuery.pick("TRD productType = REVOLVER").condition()).isEqualTo("lower($.productType) == lower('REVOLVER')");
        assertThat(SearchQuery.pick("TRD notional > 10m and currency = usd").condition())
                .isEqualTo("$.notional > 10000000 && lower($.currency) == lower('usd')");

        SearchQuery both = SearchQuery.pick("TRD MX-2* desk=rates order by mtm desc limit 7");
        assertThat(both.idPattern()).isEqualTo("MX-2*");
        assertThat(both.condition()).isEqualTo("lower($.desk) == lower('rates')");
        assertThat(both.orderBy()).isEqualTo("$.mtm");
        assertThat(both.descending()).isTrue();
        assertThat(both.limit()).isEqualTo(7);
        assertThat(SearchQuery.pick("TRD MX-200000 limit 5").limit()).isEqualTo(5);
        assertThat(SearchQuery.pick("TRD").idPattern()).isNull();                                      // every trade
        assertThat(SearchQuery.pick("TRD where mtm > 1m").condition()).isEqualTo("$.mtm > 1000000");

        assertThat(SearchQuery.looksLikePick("TRD MX-20000001")).isFalse();                                // one entity: opened if it exists
        assertThat(SearchQuery.looksLikePick("TRD MX-2*")).isTrue();
        assertThat(SearchQuery.looksLikePick("TRD productType=Revolver")).isTrue();
        assertThat(SearchQuery.looksLikePick("TRD")).isFalse();
        assertThat(SearchQuery.pick("TRD not status = matured").condition()).isEqualTo("! (lower($.status) == lower('matured'))");
    }
}

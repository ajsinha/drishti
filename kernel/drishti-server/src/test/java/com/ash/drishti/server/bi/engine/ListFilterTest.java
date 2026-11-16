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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.server.bi.Layered;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The list filters of a BI query (has, any-of, all-of) over a list column, which the engine's table holds as the list's JSON text: each
 * answer is the count a brute-force pass over the same trades gives, so it agrees with the Rachana path ({@code has}, {@code any_of},
 * {@code all_of}).
 */
class ListFilterTest {

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        String yaml = TestLake.yaml().replace("      mtmK:", "      tags: list\n      mtmK:");
        DatasetParser.Result r = Layered.dataset(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), r.dataset(), "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    long count(String filter) throws Exception {
        QueryService.Caller who = new QueryService.Caller("u", Set.of("risk"), true, UserAttributes.of("u", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
        String body = "{\"measures\":[\"Trades\"],\"filters\":[" + filter + "],\"dates\":{\"from\":\"2026-10-09\"}}";
        QueryService.Result r = service.query(ds, QueryRequest.of(json.readTree(body)), who, true);
        return r.rows() == 0 ? 0 : ((Number) r.columns().get(0).values()[0]).longValue();
    }

    /** The trades of the day holding the tags, counted over the same tag sets the lake wrote. */
    long brute(java.util.function.Predicate<List<String>> test) {
        long n = 0;
        for (TestLake.Trade t : lake.trades.get(TestLake.DAY2)) {
            String tags = TestLake.tagsOf(Integer.parseInt(t.id().substring(t.id().lastIndexOf('-') + 1)));
            List<String> items = tags.equals("[]") ? List.of() : List.of(tags.substring(2, tags.length() - 2).split("\",\""));
            if (test.test(items)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void hasAnyOfAndAllOfAgreeWithAPlainPassOverTheTags() throws Exception {
        assertThat(count("{\"field\":\"trades.tags\",\"op\":\"has\",\"values\":[\"fx\"]}")).isEqualTo(brute(t -> t.contains("fx"))).isPositive();
        assertThat(count("{\"field\":\"trades.tags\",\"op\":\"has\",\"values\":[\"nope\"]}")).isZero();
        assertThat(count("{\"field\":\"trades.tags\",\"op\":\"any-of\",\"values\":[\"rates\",\"emea\"]}"))
                .isEqualTo(brute(t -> t.contains("rates") || t.contains("emea"))).isPositive();
        long all = count("{\"field\":\"trades.tags\",\"op\":\"all-of\",\"values\":[\"fx\",\"hedge\"]}");
        assertThat(all).isEqualTo(brute(t -> t.contains("fx") && t.contains("hedge"))).isPositive();
        assertThat(all).isLessThan(count("{\"field\":\"trades.tags\",\"op\":\"any-of\",\"values\":[\"fx\",\"hedge\"]}"));
    }

    @Test
    void nothingChosenHoldsEveryRowAndANonListFieldIsRefused() throws Exception {
        assertThat(count("{\"field\":\"trades.tags\",\"op\":\"any-of\",\"values\":[]}")).isEqualTo(lake.trades.get(TestLake.DAY2).size());
        assertThatThrownBy(() -> count("{\"field\":\"trades.notional\",\"op\":\"has\",\"values\":[\"fx\"]}")).hasMessageContaining("list column");
    }
}

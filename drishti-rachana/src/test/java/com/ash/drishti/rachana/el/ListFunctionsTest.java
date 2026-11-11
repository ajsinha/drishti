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
package com.ash.drishti.rachana.el;

import static com.ash.drishti.rachana.el.Fx.EL;
import static com.ash.drishti.rachana.el.Fx.compileError;
import static com.ash.drishti.rachana.el.Fx.dec;
import static com.ash.drishti.rachana.el.Fx.eval;
import static com.ash.drishti.rachana.el.Fx.evalError;
import static com.ash.drishti.rachana.el.Fx.list;
import static com.ash.drishti.rachana.el.Fx.masked;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The list functions (RACHANA_REFERENCE.md section F6): Excel's dynamic arrays over [*], with @ the element. */
class ListFunctionsTest {

    @Test
    void sortOrdersByAKeyWithEmptyKeysLast() {
        assertThat(list("sort($.NUMS)")).containsExactly(1.5, "2", 3L, 10L);  // number-like text sorts as a number
        assertThat(list("sort($.NUMS, @, true)")).containsExactly(10L, 3L, "2", 1.5);
        assertThat(list("sort($.EVENTS, @.EVENT_DATE)[*].EVENT_TYPE")).containsExactly("BOOKED", "CASH_SETTLEMENT", "AMENDED");
        assertThat(eval("take(sort($.EVENTS, @.EVENT_DATE, true), 1)[0].EVENT_TYPE")).isEqualTo("AMENDED");
        assertThat(list("sort($.UNDATED, @.D)[*].ID")).containsExactly("c", "a", "b");
        assertThat(list("sort($.UNDATED, @.D, true)[*].ID")).containsExactly("a", "c", "b");
        assertThat(list("sort($.MIXED)")).containsExactly(1L, "1", 1.0, "a", "a");   // equal keys keep their order
        assertThat(eval("sort($.NOPE)")).isNull();
        assertThat(masked("sort($.MASKED_LIST)")).isTrue();
        assertThat(masked("sort($.PVS, @.PV)")).isTrue();                       // an order by a hidden value would tell it
        assertThat(masked("sort($.NUMS, @, $.MTM)")).isTrue();
        evalError("sort($.TRADE_ID)", "sort", "'MX-20000001' is not a list");
    }

    @Test
    void uniqueKeepsTheFirstOfEach() {
        assertThat(list("unique($.MIXED)")).containsExactly(1L, "1", "a");
        assertThat(list("unique($.LEGS[*].CURRENCY)")).containsExactly("AUD");
        assertThat(eval("size(unique($.LEGS))")).isEqualTo(2L);
        assertThat(eval("unique($.NOPE)")).isNull();
        assertThat(masked("unique($.PVS[*].PV)")).isTrue();
        assertThat(masked("unique($.MASKED_LIST)")).isTrue();
        evalError("unique(5)", "unique", "5 is not a list");
    }

    @Test
    void takeAndDropFromEitherEnd() {
        assertThat(list("take($.NUMS, 2)")).containsExactly(3L, 1.5);
        assertThat(list("take($.NUMS, -1)")).containsExactly(10L);
        assertThat(list("take($.NUMS, 99)")).hasSize(4);
        assertThat(list("take($.NUMS, 0)")).isEmpty();
        assertThat(list("drop($.NUMS, 1)")).containsExactly(1.5, "2", 10L);
        assertThat(list("drop($.NUMS, -1)")).containsExactly(3L, 1.5, "2");
        assertThat(list("drop($.NUMS, 99)")).isEmpty();
        assertThat(eval("take($.NOPE, 1)")).isNull();
        assertThat(eval("drop($.NUMS, $.NOPE)")).isNull();
        assertThat(masked("take($.MASKED_LIST, 1)")).isTrue();
        evalError("take($.NUMS, 'x')", "take", "'x' is not a number");
        evalError("drop('abc', 1)", "drop", "'abc' is not a list");
    }

    @Test
    void sequenceCountsInDecimal() {
        assertThat(list("sequence(3)")).containsExactly(1L, 2L, 3L);
        assertThat(list("sequence(3, 0.1, 0.1)")).containsExactly(dec("0.1"), dec("0.2"), dec("0.3"));
        assertThat(list("sequence(4, 10, -5)")).containsExactly(10L, 5L, 0L, -5L);
        assertThat(list("sequence(0)")).isEmpty();
        assertThat(eval("size(sequence(12))")).isEqualTo(12L);
        assertThat(eval("sequence($.NOPE)")).isNull();
        assertThat(masked("sequence(3, $.MTM)")).isTrue();
        compileError("sequence(20000)", "sequence: the length 20000 is outside 0..10000");
        evalError("sequence($.QUANTITY)", "sequence", "outside 0..10000");
        evalError("sequence('x')", "sequence", "'x' is not a number");
    }

    @Test
    void mapTransformsEachElement() {
        assertThat(list("map($.LEGS, @.LEG_ID * 10)")).containsExactly(10L, 20L);
        assertThat(list("map($.LEGS, @.NOTIONAL / $.QUANTITY)")).containsExactly(1L, 1L);   // $ is still the document
        assertThat(list("map($.LEGS, concat(@.LEG_TYPE, '-', #index))")).containsExactly("FIXED-0", "FLOAT-1");
        assertThat(list("map(sequence(3), @ * @)")).containsExactly(1L, 4L, 9L);
        assertThat(eval("map($.NOPE, @)")).isNull();
        assertThat(masked("map($.MASKED_LIST, @)")).isTrue();
        Object v = EL.compile("map($.PVS, @.PV)").eval(Fx.ctx());
        assertThat(((DataNode.Arr) v).get(0).asDouble()).isEqualTo(5.0);
        assertThat(((DataNode.Arr) v).get(1).isMasked()).isTrue();             // only the hidden element is masked
        evalError("map('x', @)", "map", "'x' is not a list");
        TreeSet<String> paths = new TreeSet<>();
        EL.compile("map($.LEGS, @.NOTIONAL * $.QUANTITY)").paths(paths::add);
        assertThat(paths).containsExactly("$.LEGS", "$.QUANTITY");
    }

    @Test
    void reduceFoldsButNeverAddsUp() {
        assertThat(eval("reduce($.EVENTS, '', concat(@acc, left(@.EVENT_TYPE, 1)))")).isEqualTo("BAC");
        assertThat(eval("reduce($.NUMS, 0, greatest(@acc, @))")).isEqualTo(10L);
        assertThat(eval("reduce($.EVENTS, '', if(@acc == '', @.EVENT_TYPE, @acc))")).isEqualTo("BOOKED");
        assertThat(eval("reduce(sequence(0), 'none', @acc)")).isEqualTo("none");
        assertThat(eval("reduce($.NOPE, 0, @acc)")).isNull();
        assertThat(masked("reduce($.MASKED_LIST, 0, @acc)")).isTrue();
        assertThat(masked("reduce($.PVS, 0, greatest(@acc, @.PV))")).isTrue();
        compileError("reduce($.NUMS, 0, @acc + @)", "reduce does not add up a list: use sum(...)");
        compileError("reduce($.LEGS, 0, @.NOTIONAL - @acc)", "use sum(...)");
        compileError("reduce($.EVENTS, '', @acc + ', ' + @.EVENT_TYPE)", "concat(...) to join text");
        compileError("reduce($.LEGS, 0, @.LEG_TYPE == 'FIXED' ? @acc + @.NOTIONAL : @acc)", "use sum(...)");
        evalError("reduce('x', 0, @acc)", "reduce", "'x' is not a list");
        assertThat(eval("@acc")).isNull();                                      // outside reduce, @acc is a panel's events, as before
    }

    @Test
    void theOldListFormsKeepTheirResults() {
        assertThat(eval("size($.LEGS)")).isEqualTo(2L);
        assertThat(eval("first($.LEGS).LEG_TYPE")).isEqualTo("FIXED");
        assertThat(eval("last($.EVENTS).EVENT_TYPE")).isEqualTo("CASH_SETTLEMENT");
        assertThat(eval("size(flatten($.LEGS))")).isEqualTo(2L);
    }

    /**
     * Rachana implements no aggregate (docs/architecture/ARCHITECTURE.md part 2): without the pivot engine on the class path (this module's tests), an
     * aggregate compiles, so a Sutra loads, and is refused with the reason when evaluated; drishti-bicompute's RachanaAggregatesTest
     * tests the results.
     */
    @Test
    void aggregatesCompileAndAreRefusedWithTheReasonWithoutTheEngine() {
        EL.compile("sum($.LEGS, 'NOTIONAL') + count($.LEGS) + percentile($.LEGS[*].NOTIONAL, 0.9)");
        evalError("sum($.LEGS, 'NOTIONAL')", "sum", "pivot engine (drishti-bicompute)", "not on the class path");
        evalError("avg($.LEGS[*].NOTIONAL)", "avg", "pivot engine");
        evalError("min($.LEGS[*].NOTIONAL)", "min", "pivot engine");
        evalError("group($.LEGS, 'CURRENCY', 'NOTIONAL')", "group", "pivot engine");
        evalError("measure('VAR', 'DESK:DESK-COMM')", "measure", "pivot engine");
        assertThat(eval("min(3, 1, 2)")).isEqualTo(1L);                           // several arguments: least, Rachana's own
        assertThat(eval("group($.NOPE, 'x')")).isNull();                          // not a list: empty, as before
        assertThat(Functions.catalogue()).anySatisfy(e -> {
            assertThat(e.name()).isEqualTo("sum");
            assertThat(e.runs()).isEqualTo("E");
            assertThat(e.source()).isEqualTo("none on the class path");
        });
        assertThat(Functions.catalogue()).anySatisfy(e -> {
            assertThat(e.name()).isEqualTo("upper");
            assertThat(e.runs()).isEqualTo("R");
        });
        assertThat(Functions.catalogue()).noneMatch(e -> e.name().equals("startsWith"));
    }
}

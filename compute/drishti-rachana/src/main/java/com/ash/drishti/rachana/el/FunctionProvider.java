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

import java.util.List;

/**
 * A module that adds functions to Rachana's one function table (HOW_IT_FITS.md parts 1 and 2), found once when the table is first
 * used, by {@link java.util.ServiceLoader} ({@code META-INF/services/com.ash.drishti.rachana.el.FunctionProvider}), the same pattern as
 * the lake's storage providers. The pivot engine ({@code drishti-bicompute}) is the provider of every aggregate: {@code sum},
 * {@code count}, {@code avg}, {@code min} and {@code max} over a list, {@code median}, {@code percentile}, {@code distinct},
 * {@code wavg}, {@code variance}, {@code stddev}, the {@code _once} forms, the grouping behind {@code group(...)}, and
 * {@code measure(...)} and {@code cell(...)}. Rachana implements no aggregate itself: where no provider is on the class path, an
 * aggregate in an expression compiles (a Sutra still loads) and is refused with the reason when it is evaluated.
 *
 * <p>Rachana depends on no provider; a provider depends on Rachana. A provider's functions are pure and thread-safe, keep Rachana's
 * rules (an empty argument gives an empty result unless the function says otherwise; a masked value makes the result masked) and
 * never take a name Rachana's own families have, except {@code min} and {@code max}, whose one-list form Rachana hands to the
 * provider (with several arguments they are the old spelling of {@code least} and {@code greatest}).
 */
public interface FunctionProvider {

    /** Who provides the functions, as the catalogue and a refusal name it ({@code the pivot engine (drishti-bicompute)}). */
    String source();

    /** The functions provided, each name once. */
    List<Provided> functions();

    /** The grouping behind {@code group(list, key, [aggregate])}; null when this provider does not group. */
    default Grouping grouping() {
        return null;
    }

    /**
     * One function a provider adds.
     *
     * @param name its one name
     * @param minArgs the fewest arguments
     * @param maxArgs the most arguments
     * @param args its arguments as the catalogue writes them ({@code list, [field]})
     * @param form {@code mergeable} or {@code holistic} for an aggregate, {@code measure} for a figure from another engine, {@code list}
     *     for a function from a list to a list resting on the provider's aggregates ({@code cumsum}, {@code movmean})
     * @param description what it means, one line
     * @param example an example on the demo data
     * @param function the implementation, its arguments evaluated
     */
    record Provided(String name, int minArgs, int maxArgs, String args, String form, String description, String example,
            ElFunction function) {}

    /** The grouping of a list's elements by a key, each group's values aggregated by a provided aggregate. */
    @FunctionalInterface
    interface Grouping {

        /**
         * The groups, in key order (text order, so ISO dates order), at most {@code maxGroups} (the first keys met; later new keys are
         * left out).
         *
         * @param keys the key of each element; a null or blank key leaves the element out
         * @param columns the aggregate's inputs, one column per input, each holding one value per element ({@code sum(@.NOTIONAL)}:
         *     one column; {@code wavg(@.PRICE, @.QUANTITY)}: two); empty for a count of the elements
         * @param aggregate a provided aggregate's name ({@code sum}, {@code avg}, {@code percentile} …), or {@code count}
         * @param options the aggregate's further literal arguments ({@code 0.95} for a percentile)
         * @param maxGroups the most groups returned
         */
        List<Group> group(List<String> keys, List<List<Object>> columns, String aggregate, List<Object> options, int maxGroups);
    }

    /**
     * One group: its key, its aggregated value (null when it has none; masked when a value it reads is masked) and how many elements it
     * holds.
     */
    record Group(String key, Object value, long count) {}
}

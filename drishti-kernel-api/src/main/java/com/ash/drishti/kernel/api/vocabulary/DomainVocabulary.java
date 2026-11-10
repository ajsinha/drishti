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
package com.ash.drishti.kernel.api.vocabulary;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The words of a domain the kernel's generic code consults without naming them (docs/architecture/KERNEL_AND_MODULES.md section 2.3): a
 * module contributes one with the {@link java.util.ServiceLoader}, before any bean exists. Every method has an empty default, so a kernel
 * with no modules consults nothing.
 */
public interface DomainVocabulary {

    /** Names a person may not give a custom function, because they stand for a figure the module publishes officially. */
    default Collection<String> reservedFunctionNames() {
        return Set.of();
    }

    /** Field names that count as a kind's main measure in a typed phrase (after the kernel's own {@code balance}, in this order). */
    default Collection<String> phraseMeasures() {
        return Set.of();
    }

    /** The connector plugins that write into a lake, named by a {@code lake:} folder that adopting a demo lake rewrites. */
    default Collection<String> namedLakeWriters() {
        return Set.of();
    }

    /** Engine id to the clause that says why it needs the engines it needs, as the refusal to turn it off reads it. */
    default Map<String, String> engineDependencyNotes() {
        return Map.of();
    }

    /** True when the difference a non-additive function leaves against a sum is diversification, not merely "not additive". */
    default boolean diversifies(String function, int inputs) {
        return false;
    }

    /** The union of one part over every module, in load order. */
    static Set<String> union(Function<DomainVocabulary, Collection<String>> part) {
        Set<String> out = new LinkedHashSet<>();
        Vocabularies.ALL.forEach(v -> out.addAll(part.apply(v)));
        return java.util.Collections.unmodifiableSet(out);
    }

    /** The maps of one part over every module, a later module's entry winning. */
    static Map<String, String> notes(Function<DomainVocabulary, Map<String, String>> part) {
        Map<String, String> out = new LinkedHashMap<>();
        Vocabularies.ALL.forEach(v -> out.putAll(part.apply(v)));
        return java.util.Collections.unmodifiableMap(out);
    }

    /** True when some module's vocabulary says so. */
    static boolean anyMatch(Predicate<DomainVocabulary> test) {
        return Vocabularies.ALL.stream().anyMatch(test);
    }
}

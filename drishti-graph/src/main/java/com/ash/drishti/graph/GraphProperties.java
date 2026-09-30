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
package com.ash.drishti.graph;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.graph.*}: how identifiers become links between entities.
 *
 * @param idPatterns identifier regex to kind, tried in order, for values whose kind is not stated
 * @param fields document field name to the kind and label of the entity it references
 * @param badges kind to a Sutra-EL expression over the target document, shown beside the link ({@code EE 4.1m})
 * @param linkBudget how long a view waits for linked entities before showing them as pending
 */
@ConfigurationProperties("drishti.graph")
public record GraphProperties(
        List<IdPattern> idPatterns, Map<String, FieldRef> fields, Map<String, String> badges, Duration linkBudget) {

    public GraphProperties {
        idPatterns = idPatterns == null ? List.of() : List.copyOf(idPatterns);
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        badges = badges == null ? Map.of() : Map.copyOf(badges);
        linkBudget = linkBudget == null ? Duration.ofMillis(40) : linkBudget;
    }

    /**
     * @param pattern a regular expression over identifiers
     * @param kind the kind of entities it identifies
     */
    public record IdPattern(String pattern, String kind) {}

    /**
     * @param kind the kind of the referenced entity
     * @param label the label in the linked-entities panel; defaults to the humanised field name
     */
    public record FieldRef(String kind, String label) {}
}

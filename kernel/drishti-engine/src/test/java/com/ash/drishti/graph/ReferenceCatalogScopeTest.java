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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** A graph field scoped to some kinds ({@code from}, a pack's {@code scope: pack}) links from their documents only. */
class ReferenceCatalogScopeTest {

    /** What the pack loader writes for world-economy's {@code JURISDICTION: { kind: jurisdiction, scope: pack }}, bound as the server binds it. */
    static ReferenceCatalog catalog() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("packs", Map.of(
                "drishti.graph.fields.JURISDICTION.kind", "jurisdiction", "drishti.graph.fields.JURISDICTION.label", "Country",
                "drishti.graph.fields.JURISDICTION.from[0]", "party", "drishti.graph.fields.JURISDICTION.from[1]", "election",
                "drishti.graph.fields.LEI_GROUP.kind", "lei-group")));
        return new ReferenceCatalog(GraphProperties.of(env));
    }

    @Test
    void aScopedFieldLinksFromItsOwnKindsOnly() {
        ReferenceCatalog c = catalog();
        DataNode doc = DataNode.of(Map.of("JURISDICTION", "JUR-ARG", "LEI_GROUP", "HWUPKR0MPOU8FGXBT394"));
        assertThat(c.discover(doc, EntityRef.of("party", "PRTY-1"))).extracting(l -> l.target().kind())
                .containsExactlyInAnyOrder("jurisdiction", "lei-group");
        assertThat(c.discover(doc, EntityRef.of("lei-entity", "HWUPKR0MPOU8FGXBT394"))).extracting(l -> l.target().kind())
                .containsExactly("lei-group");                                       // gleif's US-CA is data, not a jurisdiction link
        assertThat(c.kindOfField("JURISDICTION", "election")).contains("jurisdiction");
        assertThat(c.kindOfField("JURISDICTION", "lei-entity")).isEmpty();
        assertThat(c.kindOfField("JURISDICTION")).contains("jurisdiction");          // no kind known: the field's kind
        assertThat(c.kindOfField("LEI_GROUP", "anything")).contains("lei-group");     // unscoped: every kind
    }
}

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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The hierarchies' configuration (2.9.5, VAR_GUIDE.md section 16): read from a pack's {@code hierarchies:} block or the
 * {@code drishti.var.hierarchies} setting; the levels written {@code "A > B"} or as a list; the defaults; and what is refused (a
 * duplicate id or word, a reserved word, a column hierarchy without levels, an unknown source or ids mode).
 */
class HierarchyConfigTest {

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    @Test
    void theManifestsBlockIsRead() {
        List<HierarchySpec> h = HierarchySpec.parse(m(
                "MGMT", m("title", "Management", "source", "risk-hierarchy"),
                "LEGAL", m("title", "Legal entities"),
                "LL", m("title", "Limit letters", "levels", "LIMIT_LETTER > DESK", "word", "ll", "limits", "var-limit")));
        assertThat(h).extracting(HierarchySpec::id).containsExactly("MGMT", "LEGAL", "LL");
        assertThat(h.get(1).source()).isEqualTo("risk-hierarchy");
        HierarchySpec ll = h.get(2);
        assertThat(ll.columns()).isTrue();
        assertThat(ll.levels()).containsExactly("LIMIT_LETTER", "DESK");
        assertThat(ll.levelNames()).containsExactly("limit-letter", "desk");
        assertThat(ll.word()).isEqualTo("LL");
        assertThat(ll.limits()).isEqualTo("var-limit");
        assertThat(ll.rootNodeId()).isEqualTo("LL~BANK");
        assertThat(ll.rootName()).isEqualTo("Limit letters");
        assertThat(ll.nodeId(List.of("LL-0042", "DESK-RATES"), 2)).isEqualTo("LL~LL-0042/DESK-RATES");
        assertThat(Cells.columns(h)).containsExactly("LIMIT_LETTER", "DESK");
        assertThat(HierarchySpec.digest(h)).contains("LL:columns");
    }

    @Test
    void levelsAsAListLevelNamesLeafIdsAndARoot() {
        HierarchySpec s = HierarchySpec.of("MGMT", m("levels", List.of("DIVISION", "DESK", "TT_BOOK"), "level-names", List.of("division", "desk", "book"),
                "ids", "leaf", "root", m("id", "BANK", "name", "Drishti Bank")));
        assertThat(s.leafIds()).isTrue();
        assertThat(s.levelNames()).containsExactly("division", "desk", "book");
        assertThat(s.nodeId(List.of("DIV-MARKETS", "DESK-RATES"), 2)).isEqualTo("MGMT~DESK-RATES");
        assertThat(s.rootName()).isEqualTo("Drishti Bank");
        assertThat(Cells.columns(List.of(s))).containsExactly("DIVISION", "DESK");
        // levels bound by Spring from indexed keys (levels[0], levels[1])
        HierarchySpec bound = HierarchySpec.of("LL", m("levels", m("0", "LIMIT_LETTER", "1", "DESK")));
        assertThat(bound.levels()).containsExactly("LIMIT_LETTER", "DESK");
    }

    @Test
    void theDefaultIsMgmtAndLegalFromTheRiskHierarchyAndAddsNothingToTheDigest() {
        assertThat(HierarchySpec.defaults()).extracting(HierarchySpec::id).containsExactly("MGMT", "LEGAL");
        assertThat(HierarchySpec.defaults()).allMatch(s -> !s.columns() && s.word() == null);
        assertThat(HierarchySpec.digest(HierarchySpec.defaults())).isEmpty();
        assertThat(Cells.columns(HierarchySpec.defaults())).isEmpty();
    }

    @Test
    void whatIsRefused() {
        assertThatThrownBy(() -> HierarchySpec.parse(m("LL", m("levels", "LIMIT_LETTER", "word", "DESK"))))
                .hasMessageContaining("the engine's own");
        assertThatThrownBy(() -> HierarchySpec.parse(m("A", m("levels", "X", "word", "W"), "B", m("levels", "Y", "word", "W"))))
                .hasMessageContaining("names two hierarchies");
        assertThatThrownBy(() -> HierarchySpec.parse(m("LL", m("source", "columns")))).hasMessageContaining("names no levels");
        assertThatThrownBy(() -> HierarchySpec.parse(m("LL", m("source", "csv")))).hasMessageContaining("source is");
        assertThatThrownBy(() -> HierarchySpec.parse(m("LL", m("levels", "X", "ids", "short")))).hasMessageContaining("ids is path or leaf");
        assertThatThrownBy(() -> HierarchySpec.parse(m("ll-1", m("levels", "X")))).hasMessageContaining("not a name");
        assertThatThrownBy(() -> HierarchySpec.parse(m("LL", m("levels", "X > X")))).hasMessageContaining("twice");
        assertThatThrownBy(() -> HierarchySpec.parse(m("LL", "letters"))).hasMessageContaining("not a map");
        assertThatThrownBy(() -> HierarchySpec.check(List.of(HierarchySpec.riskHierarchy("MGMT", null), HierarchySpec.riskHierarchy("mgmt", null))))
                .hasMessageContaining("configured twice");
    }
}

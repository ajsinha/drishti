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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.StripItem;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraBlocks;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The under-construction registry (docs/admin/UNDER_CONSTRUCTION.md): every location it names exists in the packs' Sutras, and a matched
 * Sutra honours each item's treatment: the value replaced by "Under construction" (tone uc), or left out.
 */
class UnderConstructionTest {

    static final Path FILE = Path.of("..", "config", "under-construction.yaml");

    @AfterEach
    void noKit() {
        SutraBlocks.kit(null);
        SutraBlocks.load(List.of());
    }

    private static SutraRegistry packs() {
        SutraBlocks.kit(Path.of("..", "config", "ui-kit"));
        List<String> dirs = new ArrayList<>();
        for (String p : List.of("core", "counterparty-risk", "trading", "market-data")) {
            dirs.add(Path.of("..", "config", "packs", p, "sutras").toString());
        }
        return new SutraRegistry(new RachanaProperties(dirs, false, null, null, null, null, null, null, null, null), new ElCompiler());
    }

    private static Stream<Column> columns(List<Panel> panels) {
        return panels.stream().flatMap(p -> Stream.concat(p.columns().stream(), columns(p.parts())));
    }

    private static Stream<Panel> all(List<Panel> panels) {
        return panels.stream().flatMap(p -> Stream.concat(Stream.of(p), all(p.parts())));
    }

    @Test
    void everyRegisteredLocationExistsAndEveryMatchedSutraHonoursItsTreatment() {
        UnderConstruction uc = UnderConstruction.load(FILE);
        assertThat(uc.items()).isNotEmpty();
        try (SutraRegistry r = packs()) {
            for (UnderConstruction.Item item : uc.items()) {
                for (UnderConstruction.SutraPlace place : item.sutras()) {
                    List<Sutra> found = r.forKind(place.kind());
                    assertThat(found).as(item.id() + ": a Sutra of " + place.kind()).isNotEmpty();
                    for (Sutra s : found) {
                        List<String> binds = Stream.concat(s.strip().stream().map(StripItem::bind), columns(s.panels()).map(Column::bind)).toList();
                        List<String> ids = all(s.panels()).map(Panel::id).toList();
                        assertThat(binds).as(item.id() + " in " + s.name()).containsAll(place.binds());
                        assertThat(ids).as(item.id() + " in " + s.name()).containsAll(place.panels());
                        Sutra shown = uc.apply(s);
                        List<String> after = Stream.concat(shown.strip().stream().map(StripItem::bind), columns(shown.panels()).map(Column::bind)).toList();
                        assertThat(after).as(item.id() + ": no registered value is bound any more").doesNotContainAnyElementsOf(place.binds());
                        for (String id : place.panels()) {
                            List<Panel> p = all(shown.panels()).filter(x -> id.equals(x.id())).toList();
                            if (item.hidden()) {
                                assertThat(p).as(item.id() + ": " + id + " hidden").isEmpty();
                            } else {
                                assertThat(p).singleElement().satisfies(x -> assertThat(x.columns()).singleElement()
                                        .satisfies(c -> assertThat(c.tone()).isEqualTo(UnderConstruction.TONE)));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void underConstructionShowsTheWordsAndHiddenLeavesTheItemOut() {
        UnderConstruction uc = UnderConstruction.parse("""
                items:
                  - { id: a, what: field, release: x, treatment: under-construction, sutras: [{ kind: thing, binds: [$.A, "@.B"] }] }
                  - { id: b, what: field, release: x, treatment: hidden, sutras: [{ kind: thing, binds: [$.C], panels: [gone] }] }
                """);
        Sutra s = new Sutra("thing", 1, null, new com.ash.drishti.rachana.model.Match("thing", null, 10), null,
                List.of(new StripItem("A", "$.A", "compact", null, true, null), new StripItem("C", "$.C", null, null, false, null),
                        new StripItem("D", "$.D", null, null, false, null)),
                List.of(new Panel("t", com.ash.drishti.rachana.model.PanelKind.TABLE, "T", null, null, null, false,
                                List.of(new Column("B", "@.B", "compact", "sign", true, false), new Column("E", "@.E", null, null, false, false)), null,
                                java.util.Map.of("rows", "$.ROWS"), null),
                        new Panel("gone", com.ash.drishti.rachana.model.PanelKind.KV, "G", null, null, null, false, List.of(), null, java.util.Map.of(), null)),
                java.util.Map.of(), null);
        Sutra shown = uc.apply(s);
        assertThat(shown.strip()).extracting(StripItem::label).containsExactly("A", "D");
        assertThat(shown.strip().get(0).bind()).isEqualTo("'Under construction'");
        assertThat(shown.strip().get(0).tone()).isEqualTo("uc");
        assertThat(shown.panels()).extracting(Panel::id).containsExactly("t");
        assertThat(shown.panels().get(0).columns().get(0).bind()).isEqualTo("'Under construction'");
        assertThat(shown.panels().get(0).columns().get(1).bind()).isEqualTo("@.E");
        assertThat(uc.apply(new Sutra("other", 1, null, new com.ash.drishti.rachana.model.Match("other", null, 10), null, List.of(), List.of(),
                java.util.Map.of(), null))).as("another kind: untouched").isNotNull();
        assertThatThrownBy(() -> UnderConstruction.parse("items: [{ id: x, treatment: maybe }]")).hasMessageContaining("hidden or under-construction");
        assertThat(UnderConstruction.load(Path.of("no-such-file.yaml")).items()).isEmpty();
    }
}

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
package com.ash.drishti.enginehost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.ColumnSet;
import com.ash.engine.Aggregate;
import com.ash.engine.Descriptor;
import com.ash.engine.Descriptors;
import com.ash.engine.EngineException;
import com.ash.engine.Filter;
import com.ash.engine.GroupScan;
import com.ash.engine.InputTable;
import com.ash.engine.Scan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The host's parts on their own: the inputs over the read API, the grouped scan, the licence file, version ranges, pack uses. */
class HostPartsTest {

    private static final String DESCRIPTOR = """
            engine: 1
            id: probe
            version: 1.0.0
            title: Probe
            about: A descriptor for the host's tests.
            publisher: { id: test, name: Test }
            product: test
            api: "0.1"
            inputs:
              items:
                contract: sample/item@1
                about: items
                key: [ ID ]
                columns: [ ID, GROUP, QTY, AMOUNT, OWNER ]
                scope-columns: [ GROUP ]
            outputs:
              totals:
                title: Totals
                about: totals
                key: [ GROUP ]
                columns:
                  - { name: GROUP, type: string, nullable: false }
                  - { name: OWNER, type: string, nullable: true, passes: items.OWNER }
            """;

    private static LakeInputs inputs(ViewerAccess viewer, Map<String, RowRule> wheres, Fixtures.Kinds data) {
        Descriptor d = Descriptors.parse(DESCRIPTOR);
        Map<String, InputBinder.Bound> bound = Map.of("items", new InputBinder.Bound("items", "sample/item@1", List.of("item"), 1, "named",
                Map.of("GROUP", "TT_GROUP"), null));
        return new LakeInputs(d, bound, data, viewer, null, wheres, new AtomicLong());
    }

    private static Fixtures.Kinds owned() {
        Fixtures.Kinds k = new Fixtures.Kinds();
        k.put("item", new ColumnSet(new String[] {"i1", "i2", "i3"}, Map.of("QTY", new double[] {1, 2, 3}, "AMOUNT", new double[] {0.1, 0.2, 0.3}),
                Map.of("TT_GROUP", new String[] {"A", "B", "A"}, "OWNER", new String[] {"ann", "bob", "cy"}), LocalDate.of(2026, 11, 20)));
        return k;
    }

    @Test
    void anUnfilteredScanHandsTheReadersArraysOverAndTheColumnMapRenames() throws Exception {
        Fixtures.Kinds data = owned();
        InputTable t = inputs(Fixtures.viewer("ann", Map.of(), Set.of()), Map.of(), data).table("items");
        assertThat(t.present()).isTrue();
        assertThat(t.columns()).containsExactlyInAnyOrder("ID", "GROUP", "QTY", "AMOUNT", "OWNER");
        List<double[]> seen = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        t.scan(new Scan(List.of("ID", "GROUP", "QTY"), Filter.ALL, 100), b -> {
            seen.add(b.numbers("QTY"));
            for (int i = 0; i < b.rows(); i++) {
                groups.add(b.texts("ID")[i] + ":" + b.texts("GROUP")[i] + ":" + b.integers("QTY")[i]);
            }
        });
        assertThat(seen.get(0)).as("by reference, not copied").isSameAs(data.sets.get("item").numbers().get("QTY"));
        assertThat(groups).containsExactly("i1:A:1", "i2:B:2", "i3:A:3");
        assertThat(data.reads.get()).as("read once per session").isEqualTo(1);
    }

    @Test
    void rowRulesTheSitesWhereAndMasksAreAppliedBeforeDelivery() throws Exception {
        ViewerAccess bob = Fixtures.viewer("bob", Map.of("item", Fixtures.eq("TT_GROUP", "A")), Set.of("OWNER"));
        InputTable t = inputs(bob, Map.of("items", Fixtures.eq("OWNER", "cy")), owned()).table("items");
        List<String> rows = new ArrayList<>();
        t.scan(new Scan(List.of("ID", "OWNER"), Filter.ALL, 1), b -> {
            for (int i = 0; i < b.rows(); i++) {
                rows.add(b.texts("ID")[i] + ":" + b.texts("OWNER")[i] + ":" + b.isNull("OWNER", i));
            }
        });
        assertThat(rows).containsExactly("i3:null:true");
        assertThat(t.rows()).isEqualTo(1);
    }

    @Test
    void anUndeclaredColumnIsRefusedAndTheScopeFilterNarrows() throws Exception {
        InputTable t = inputs(Fixtures.viewer("ann", Map.of(), Set.of()), Map.of(), owned()).table("items");
        assertThatThrownBy(() -> t.scan(new Scan(List.of("SECRET"), Filter.ALL, 10), b -> { })).isInstanceOf(EngineException.class)
                .satisfies(e -> assertThat(((EngineException) e).code()).isEqualTo("ENG-2003"));
        List<String> ids = new ArrayList<>();
        t.scan(new Scan(List.of("ID"), new Filter.Eq("GROUP", "B"), 10), b -> {
            for (int i = 0; i < b.rows(); i++) {
                ids.add(b.texts("ID")[i]);
            }
        });
        assertThat(ids).containsExactly("i2");
    }

    @Test
    void theGroupedScanIsOfferedOnlyWhenExactAndAppliesTheRulesFirst() throws Exception {
        ViewerAccess bob = Fixtures.viewer("bob", Map.of("item", Fixtures.eq("TT_GROUP", "A")), Set.of());
        InputTable t = inputs(bob, Map.of(), owned()).table("items");
        GroupScan g = new GroupScan(List.of(List.of("GROUP")), List.of(new Aggregate("N", Aggregate.Fn.COUNT, null, null),
                new Aggregate("Q", Aggregate.Fn.SUM, "QTY", null), new Aggregate("A", Aggregate.Fn.SUM, "AMOUNT", null)), null, 10);
        assertThat(t.offers(g)).isTrue();
        assertThat(t.offers(new GroupScan(List.of(List.of("GROUP")), List.of(new Aggregate("Q", Aggregate.Fn.SUM, "QTY * 2", null)), null, 10)))
                .as("an expression is not pushed down").isFalse();
        List<String> out = new ArrayList<>();
        t.groups(g, b -> {
            for (int i = 0; i < b.rows(); i++) {
                out.add(b.texts("GROUP")[i] + ":" + b.integers("N")[i] + ":" + b.integers("Q")[i] + ":" + Math.round(b.numbers("A")[i] * 1e6));
            }
        });
        assertThat(out).containsExactly("A:2:4:400000");
    }

    @Test
    void theLicenceFileGrantsItsProductsAndNoFileIsTheDevelopmentLicence(@TempDir Path dir) throws Exception {
        FileLicence none = FileLicence.read(dir.resolve("licence.yaml"));
        assertThat(none.development()).isTrue();
        assertThat(none.grants("anything")).isTrue();
        Files.writeString(dir.resolve("licence.yaml"), "licence:\n  customer: Bank\n  products: [ drishti-risk ]\n  limits: { drishti-risk: { nodes: 3 } }\n");
        FileLicence l = FileLicence.read(dir.resolve("licence.yaml"));
        assertThat(l.grants("drishti-risk")).isTrue();
        assertThat(l.grants("drishti-engine-samples")).isFalse();
        assertThat(l.limits()).containsKey("drishti-risk");
        Files.writeString(dir.resolve("licence.yaml"), "licence:\n  customer: Bank\n");
        FileLicence bad = FileLicence.read(dir.resolve("licence.yaml"));
        assertThat(bad.grants("drishti-risk")).isFalse();
        assertThat(bad.problem()).contains("lists no products");
    }

    @Test
    void versionRangesAndPackUses() {
        assertThat(VersionRange.contains(">=1.0 <2", "1.4.0")).isTrue();
        assertThat(VersionRange.contains(">=1.0 <2", "2.1.0")).isFalse();
        assertThat(VersionRange.contains("", "0.1.0")).isTrue();
        assertThat(VersionRange.contains("1.0.0", "1.0.0-SNAPSHOT")).isTrue();
        Map<String, Object> use = Map.of("engine", "group-totals", "version", ">=1.0 <2", "outputs", List.of("totals"),
                "as", Map.of("totals", Map.of("kind", "group-total", "mnemonic", "GTOT")));
        Map<String, Object> manifest = Map.of("uses", Map.of("engines", List.of(use)));
        PackUses u = PackUses.of(Map.of("shop", manifest));
        assertThat(u.of("group-totals")).singleElement().satisfies(x -> {
            assertThat(x.pack()).isEqualTo("shop");
            assertThat(x.as().get("totals")).containsEntry("mnemonic", "GTOT");
        });
        assertThat(u.byPack()).containsEntry("shop", List.of("group-totals"));
    }

    @Test
    void durations() {
        assertThat(EngineHost.duration("30s").toSeconds()).isEqualTo(30);
        assertThat(EngineHost.duration("5m").toMinutes()).isEqualTo(5);
        assertThat(EngineHost.duration("PT2H").toHours()).isEqualTo(2);
    }
}

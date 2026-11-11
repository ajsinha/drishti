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
package com.ash.drishti.server.datamap;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.packs.Pack;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data map is inferred, never configured: a pack written to a temporary folder (pack.yaml and its contracts, nothing about the map)
 * shows up with its kinds, the joins its contracts and graph fields declare, counts from the data, and each join's coverage checked
 * against the ids that exist, as columns where the source keeps them and from the documents otherwise.
 */
class DataMapTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @TempDir Path dir;

    @SuppressWarnings("unchecked")
    private Pack pack(String name, String manifest, Map<String, String> contracts) throws Exception {
        Path p = Files.createDirectories(dir.resolve(name).resolve("contracts"));
        Files.writeString(dir.resolve(name).resolve("pack.yaml"), manifest);
        for (Map.Entry<String, String> c : contracts.entrySet()) {
            Files.writeString(p.resolve(c.getKey() + ".contract.yaml"), c.getValue());
        }
        Map<String, Object> m = YAML.readValue(manifest, Map.class);
        return new Pack(name, "1.0.0", String.valueOf(m.get("title")), "", dir.resolve(name), m);
    }

    private List<Pack> acme() throws Exception {
        return List.of(pack("acme", """
                pack: acme
                title: Acme widgets
                kinds: [widget, maker, widget-risk]
                mnemonics:
                  WID: {kind: widget, label: Widget}
                  MAKR: {kind: maker, label: Maker}
                graph:
                  fields:
                    SECOND_MAKER: {kind: maker, label: Second maker}
                """, Map.of(
                "widget", """
                        kind: widget
                        title: Widget
                        description: One widget a day.
                        class: fundamental
                        key: [WIDGET_ID]
                        fields:
                        - {name: WIDGET_ID, type: id}
                        - {name: MAKER, type: id, link: maker}
                        - {name: SECOND_MAKER, type: id}
                        - {name: PARTS, type: record list}
                        nested:
                          PARTS[]:
                          - {name: MAKER, type: id, link: maker}
                        links:
                        - {field: WIDGET_ID, kind: widget}
                        - {field: MAKER, kind: maker}
                        """,
                "maker", """
                        kind: maker
                        title: Maker
                        class: fundamental
                        key: [MAKER_ID]
                        fields:
                        - {name: MAKER_ID, type: id}
                        """,
                "widget-risk", """
                        kind: widget-risk
                        title: Widget risk
                        class: computed
                        key: [WIDGET]
                        fields:
                        - {name: WIDGET, type: id, link: widget}
                        """)));
    }

    /** Four widgets: MAKER as a column (M-1, M-1, M-2 and M-9, which no maker has), the rest in the documents. */
    private static final class Lake implements DataMap.Lake {
        final List<String> read = new ArrayList<>();

        @Override
        public DataMap.Ids ids(String kind, int limit) {
            read.add("ids " + kind);
            Set<String> ids = new LinkedHashSet<>(kind.equals("maker") ? List.of("M-1", "M-2", "M-3") : List.of("W-1", "W-2", "W-3", "W-4"));
            return new DataMap.Ids(ids, false, ids.iterator().next(), null);
        }

        @Override
        public Set<String> columnar(String kind) {
            return kind.equals("widget") ? Set.of("WIDGET_ID", "MAKER") : Set.of();
        }

        @Override
        public Optional<List<Object[]>> columns(String kind, List<String> fields, int limit) {
            read.add("columns " + kind + " " + fields);
            return Optional.of(List.of(new Object[] {"M-1"}, new Object[] {"M-1"}, new Object[] {"M-2"}, new Object[] {"M-9"}));
        }

        /** Ids that open although the listing does not hold them (a source that answers by id). */
        Set<String> openable = Set.of();

        @Override
        public Set<String> exists(String kind, List<String> ids) {
            read.add("exists " + kind + " " + ids);
            return ids.stream().filter(openable::contains).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public boolean fetch(String kind, List<String> ids, Predicate<DataNode> sink) {
            read.add("fetch " + kind + " " + ids);
            return each(sink);
        }

        /** False: the source cannot read a kind in one pass (the map then fetches its entities by id). */
        boolean whole = true;

        @Override
        public boolean documents(String kind, Predicate<DataNode> sink) {
            read.add("documents " + kind);
            return whole && each(sink);
        }

        private boolean each(Predicate<DataNode> sink) {
            List<Map<String, Object>> docs = List.of(
                    Map.of("WIDGET_ID", "W-1", "SECOND_MAKER", "M-3", "PARTS", List.of(Map.of("MAKER", "M-1"), Map.of("MAKER", "M-2"))),
                    Map.of("WIDGET_ID", "W-2", "PARTS", List.of()),
                    Map.of("WIDGET_ID", "W-3", "SECOND_MAKER", "M-4"),
                    Map.of("WIDGET_ID", "W-4", "SECOND_MAKER", "M-1"));
            for (Map<String, Object> d : docs) {
                if (!sink.test(DataNode.of(d))) {
                    break;
                }
            }
            return true;
        }
    }

    @Test
    void aNewPackAppearsWithItsKindsAndTheJoinsItsContractsDeclare() throws Exception {
        DataMap.Structure s = DataMap.read(acme());
        assertThat(s.kinds()).extracting(DataMap.Kind::kind).containsExactly("widget", "maker", "widget-risk");
        assertThat(s.kinds().get(0).code()).isEqualTo("WID");
        assertThat(s.kinds().get(0).label()).isEqualTo("Widget");
        assertThat(s.kinds().get(2).computed()).as("class: computed is drawn dashed: computed on demand").isTrue();
        assertThat(s.joins()).extracting(j -> j.from() + "." + j.field() + "->" + j.to())
                .as("the contract's links and link: fields, nested ones, and a graph field; never the kind's own key")
                .containsExactly("widget.MAKER->maker", "widget.PARTS[].MAKER->maker", "widget.SECOND_MAKER->maker", "widget-risk.WIDGET->widget");
    }

    @Test
    void eachJoinIsVerifiedAgainstTheIdsThatExist() throws Exception {
        DataMap.Result r = new DataMap.Result(DataMap.read(acme()), "2026-10-27");
        assertThat(r.coverage.get("widget|MAKER").state()).isEqualTo("pending");
        Lake lake = new Lake();
        DataMap.build(lake, 1000, r, tasks -> tasks.forEach(Runnable::run));
        assertThat(r.state).isEqualTo("ready");
        assertThat(r.counts.get("widget").count()).isEqualTo(4);
        assertThat(r.counts.get("maker").count()).isEqualTo(3);
        assertThat(r.counts).as("a computed measure is never read").doesNotContainKey("widget-risk");

        DataMap.Coverage maker = r.coverage.get("widget|MAKER");
        assertThat(maker).isEqualTo(new DataMap.Coverage("checked", null, 4, 4, 4, 3, false));
        assertThat(r.coverage.get("widget|SECOND_MAKER")).as("from the documents: M-4 names no maker")
                .isEqualTo(new DataMap.Coverage("checked", null, 4, 3, 3, 2, false));
        assertThat(r.coverage.get("widget|PARTS[].MAKER")).isEqualTo(new DataMap.Coverage("checked", null, 4, 1, 2, 2, false));
        assertThat(r.coverage.get("widget-risk|WIDGET").state()).isEqualTo("computed");
        assertThat(lake.read).contains("columns widget [MAKER]", "documents widget").doesNotContain("documents widget-risk", "ids widget-risk");
    }

    @Test
    void aSourceThatCannotReadAKindWholeIsReadEntityByEntity() throws Exception {
        DataMap.Result r = new DataMap.Result(DataMap.read(acme()), "2026-10-27");
        Lake lake = new Lake();
        lake.whole = false;
        DataMap.build(lake, 1000, r, tasks -> tasks.forEach(Runnable::run));
        assertThat(lake.read).contains("fetch widget [W-1, W-2, W-3, W-4]");
        assertThat(r.coverage.get("widget|SECOND_MAKER")).isEqualTo(new DataMap.Coverage("checked", null, 4, 3, 3, 2, false));
    }

    @Test
    void anIdTheListingDoesNotHoldIsAskedOfTheTargetBeforeItCountsAsMissing() throws Exception {
        DataMap.Result r = new DataMap.Result(DataMap.read(acme()), "2026-10-27");
        Lake lake = new Lake();
        lake.openable = Set.of("M-9");
        DataMap.build(lake, 1000, r, tasks -> tasks.forEach(Runnable::run));
        assertThat(lake.read).contains("exists maker [M-9]", "exists maker [M-4]");
        assertThat(r.coverage.get("widget|MAKER")).isEqualTo(new DataMap.Coverage("checked", null, 4, 4, 4, 4, false));
        assertThat(r.coverage.get("widget|SECOND_MAKER").matched()).as("M-4 opens nothing").isEqualTo(2);
    }

    @Test
    void aSampleBeyondMaxRowsIsMarkedPartial() throws Exception {
        DataMap.Result r = new DataMap.Result(DataMap.read(acme()), "2026-10-27");
        DataMap.build(new Lake(), 2, r, tasks -> tasks.forEach(Runnable::run));
        assertThat(r.coverage.get("widget|SECOND_MAKER").rows()).isEqualTo(2);
        assertThat(r.coverage.get("widget|SECOND_MAKER").partial()).isTrue();
    }
}

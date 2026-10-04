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
package com.ash.drishti.rachana.design.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every operation on every panel kind, over the all-panels showcase and the ten other examples. */
class OpApplierTest {

    private static final Path EXAMPLES = Path.of("..", "docs", "guides", "examples");
    private final OpApplier applier = new OpApplier();
    private final SutraParser parser = new SutraParser();

    private List<Path> examples() throws IOException {
        try (Stream<Path> s = Files.list(EXAMPLES)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".sutra.yaml")).sorted().toList();
        }
    }

    /** The example with a comment above every panel and one inside it, so loss of comments shows. */
    private static String annotated(String text) {
        List<String> out = new ArrayList<>();
        boolean inPanels = false;
        int n = 0;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("panels:")) {
                inPanels = true;
            } else if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("#") && !line.startsWith("-")) {
                inPanels = false;
            }
            if (inPanels && line.startsWith("  - ")) {
                out.add("  # note " + (n++));
            }
            out.add(line);
        }
        return String.join("\n", out);
    }

    private static List<String> comments(String text) {
        return text.lines().filter(l -> l.strip().startsWith("#")).map(String::strip).toList();
    }

    private Sutra parse(String text) {
        return parser.parse(text, SutraParser.STUDIO, "test");
    }

    private static String shape(Panel p) {
        return p.id() + "|" + p.kind() + "|" + p.title() + "|" + p.options() + "|" + p.columns();
    }

    private static Map<String, Object> required(PanelKind k) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (String r : k.required()) {
            o.put(r, "text".equals(r) ? "hello" : "rows".equals(r) || "each".equals(r) || "nodes".equals(r) || "value".equals(r) ? "$.items" : "a");
        }
        if (k == PanelKind.TABS) {
            o.put("body", Map.of("kind", "kv", "columns", List.of(Map.of("label", "A", "bind", "@.a"))));
        }
        return o;
    }

    private void same(String before, OpResult r, String touched) {
        assertThat(r.problems()).isEmpty();
        Sutra a = parse(before);
        Sutra b = parse(r.yaml());
        for (Panel p : a.panels()) {
            if (!p.id().equals(touched)) {
                assertThat(b.panels().stream().filter(x -> x.id().equals(p.id())).map(OpApplierTest::shape).findFirst())
                        .as("panel %s is untouched", p.id()).contains(shape(p));
            }
        }
        assertThat(b.strip()).isEqualTo(a.strip());
        assertThat(b.keys()).isEqualTo(a.keys());
        assertThat(b.title()).isEqualTo(a.title());
    }

    @Test
    void everyOperationOnEveryKindKeepsCommentsAndOrder() throws Exception {
        Set<PanelKind> seen = EnumSet.noneOf(PanelKind.class);
        for (Path file : examples()) {
            String text = annotated(Files.readString(file));
            List<String> notes = comments(text);
            Sutra s = parse(text);
            List<String> ids = s.panels().stream().map(Panel::id).toList();
            for (Panel p : s.panels()) {
                seen.add(p.kind());
                String where = file.getFileName() + " / " + p.id() + " (" + p.kind().id() + ")";

                OpResult title = applier.apply(text, List.of(new SetOption(p.id(), "title", "Renamed \"by\" op: #1")));
                same(text, title, p.id());
                assertThat(parse(title.yaml()).panels().stream().filter(x -> x.id().equals(p.id())).findFirst().orElseThrow().title())
                        .as(where).isEqualTo("Renamed \"by\" op: #1");
                assertThat(comments(title.yaml())).as(where).isEqualTo(notes);
                assertThat(parse(title.yaml()).panels().stream().map(Panel::id).toList()).as(where).isEqualTo(ids);

                OpResult size = applier.apply(text, List.of(new SetOption(p.id(), "span", 6L), new SetOption(p.id(), "height", 7L),
                        new SetOption(p.id(), "span", null)));
                same(text, size, p.id());
                Panel sized = parse(size.yaml()).panels().stream().filter(x -> x.id().equals(p.id())).findFirst().orElseThrow();
                assertThat(sized.span()).as(where).isEmpty();
                assertThat(sized.height()).as(where).contains(7);
                assertThat(comments(size.yaml())).as(where).isEqualTo(notes);

                OpResult moved = applier.apply(text, List.of(new Move(p.id(), "right", ids.get(0).equals(p.id()) ? null : ids.get(0), null, 4, 5)));
                same(text, moved, p.id());
                Panel at = parse(moved.yaml()).panels().stream().filter(x -> x.id().equals(p.id())).findFirst().orElseThrow();
                assertThat(at.area().name()).as(where).isEqualTo("RIGHT");
                assertThat(at.span()).as(where).contains(4);
                assertThat(comments(moved.yaml()).stream().sorted().toList()).as(where).isEqualTo(notes.stream().sorted().toList());
                assertThat(parse(moved.yaml()).panels()).as(where).hasSize(ids.size());

                OpResult added = applier.apply(text, List.of(new AddPanel("new-one", p.kind().id(), new AddPanel.At(null, null, p.id(), 6, 8), required(p.kind()))));
                same(text, added, null);
                List<String> after = parse(added.yaml()).panels().stream().map(Panel::id).toList();
                assertThat(after).as(where).hasSize(ids.size() + 1);
                assertThat(after.get(after.indexOf(p.id()) + 1)).as(where).isEqualTo("new-one");
                assertThat(comments(added.yaml())).as(where).isEqualTo(notes);

                OpResult bound = applier.apply(text, List.of(new Bind(p.id(), "extra.field", null)));
                if (bound.problems().isEmpty()) {
                    same(text, bound, p.id());
                    assertThat(comments(bound.yaml())).as(where).isEqualTo(notes);
                } else {
                    assertThat(bound.problems().get(0).op()).isZero();
                    assertThat(bound.yaml()).as(where).isEqualTo(text);
                }

                if (ids.size() > 1) {
                    OpResult gone = applier.apply(text, List.of(new Remove(p.id())));
                    same(text, gone, p.id());
                    assertThat(parse(gone.yaml()).panels().stream().map(Panel::id).toList()).as(where).doesNotContain(p.id());
                    assertThat(comments(gone.yaml()).size()).as(where).isEqualTo(notes.size() - 1);
                }
            }
        }
        assertThat(seen).as("the examples together use all twenty kinds").containsExactlyInAnyOrder(PanelKind.values());
    }

    @Test
    void theSutrasOwnKeysAreSetAndRemovedInPlace() throws Exception {
        String text = annotated(Files.readString(EXAMPLES.resolve("all-panels-showcase.sutra.yaml")));
        OpResult r = applier.apply(text, List.of(
                new SetTitle(Map.of("pill", "Trade", "id", "$.tradeId")),
                new SetMatch(Map.of("kind", "trade")),
                new SetStrip(List.of(Map.of("label", "Notional", "bind", "$.notional"))),
                new SetKeys(Map.of("F2", "terms"))));
        assertThat(r.problems()).isEmpty();
        Sutra s = parse(r.yaml());
        assertThat(s.title().pill()).isEqualTo("Trade");
        assertThat(s.match().kind()).isEqualTo("trade");
        assertThat(s.strip()).hasSize(1);
        assertThat(s.keys()).containsEntry("F2", "terms");
        assertThat(comments(r.yaml())).isEqualTo(comments(text));
        assertThat(s.panels()).hasSameSizeAs(parse(text).panels());

        OpResult removed = applier.apply(r.yaml(), List.of(new SetStrip(List.of()), new SetKeys(Map.of())));
        assertThat(removed.problems()).isEmpty();
        assertThat(parse(removed.yaml()).strip()).isEmpty();
        assertThat(parse(removed.yaml()).keys()).isEmpty();
        assertThat(removed.yaml()).doesNotContain("strip:").doesNotContain("keys:");

        OpResult put = applier.apply(removed.yaml(), List.of(new SetStrip(List.of(Map.of("label", "A", "bind", "$.a"))), new SetKeys(Map.of("F3", "terms"))));
        assertThat(put.problems()).isEmpty();
        assertThat(put.yaml().indexOf("strip:")).isLessThan(put.yaml().indexOf("panels:"));
        assertThat(put.yaml().indexOf("keys:")).isGreaterThan(put.yaml().indexOf("panels:"));
    }

    @Test
    void setStripKeepsTheCommentsInsideTheStripBlock() {
        String text = "rachana: 1\nsutra: strip-keep\nversion: 1\nmatch: { kind: k }\ntitle: { id: $.id }\nstrip:\n  # figures the desk reads first\n"
                + "  - { label: A, bind: $.a }\n  # b is shown for the audit\n  - { label: B, bind: $.b }\npanels:\n  - id: t\n    kind: kv\n    columns:\n      - { label: A, bind: $.a }\n";
        OpResult r = applier.apply(text, List.of(new SetStrip(List.of(Map.of("label", "C", "bind", "$.c")))));
        assertThat(r.problems()).isEmpty();
        assertThat(r.yaml()).contains("# figures the desk reads first").contains("# b is shown for the audit").contains("label: C");
        assertThat(parse(r.yaml()).strip()).hasSize(1);
    }

    @Test
    void aListOrMappingForAOptionThatTakesOneValueIsRefused() throws Exception {
        String text = Files.readString(EXAMPLES.resolve("all-panels-showcase.sutra.yaml"));
        OpResult r = applier.apply(text, List.of(
                new SetOption("terms", "area", Map.of("a", 1)),
                new SetOption("terms", "title", Map.of("a", 1)),
                new SetOption("pnl", "x", List.of(1L)),
                new SetOption("terms", "code", "TRM")));
        assertThat(r.problems()).extracting(OpProblem::code).containsExactly(OpException.BAD_VALUE, OpException.BAD_VALUE, OpException.BAD_VALUE);
        assertThat(r.applied()).isEqualTo(1);
    }

    @Test
    void theTablesPivotMappingIsAccepted() throws Exception {
        String text = Files.readString(EXAMPLES.resolve("all-panels-showcase.sutra.yaml"));
        OpResult r = applier.apply(text, List.of(new SetOption("versions", "pivot", Map.of("heat", true))));
        assertThat(r.problems()).isEmpty();
        assertThat(r.yaml()).contains("heat: true");
    }

    @Test
    void aBadOperationIsALocatedProblemAndNeverCorruptsTheText() throws Exception {
        String text = Files.readString(EXAMPLES.resolve("all-panels-showcase.sutra.yaml"));
        List<Op> ops = List.of(
                new SetOption("terms", "title", "Terms"),                                  // 0 ok
                new SetOption("nope", "title", "x"),                                       // 1 no such panel
                new SetOption("terms", "agg", "sum"),                                      // 2 option the kind does not accept
                new AddPanel(null, "pivot", null, Map.of("rows", "$.x", "by", "g", "across", "h", "agg", "median")),   // 3 bad value
                new SetOption("terms", "span", 13L),                                       // 4 span out of range
                new AddPanel("terms", "kv", null, Map.of()),                               // 5 duplicate id
                new AddPanel(null, "hologram", null, Map.of()),                            // 6 unknown kind
                new AddPanel("t2", "table", null, Map.of()),                               // 7 a table needs rows
                new Move("terms", "left", null, null, null, null),                         // 8 bad area
                new Bind("terms", "x", "wibble"),                                          // 9 role the kind does not take
                new Remove("ghost"),                                                       // 10
                new SetOption("terms", "id", "other"),                                     // 11 id cannot be set
                new SetOption("terms", "code", "TRM"));                                    // 12 ok
        OpResult r = applier.apply(text, ops);
        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.problems()).extracting(OpProblem::op).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(r.problems()).extracting(OpProblem::code).startsWith(OpException.NO_PANEL, OpException.NOT_ACCEPTED, OpException.BAD_VALUE);
        assertThat(r.problems().get(6).name()).isEqualTo("addPanel");
        assertThat(r.problems().get(6).code()).isEqualTo("DRS-2022");              // the parser's own code for the missing rows
        assertThat(r.problems().get(6).message()).contains("rows");
        Panel terms = parse(r.yaml()).panels().get(0);
        assertThat(terms.title()).isEqualTo("Terms");
        assertThat(terms.code()).isEqualTo("TRM");
        assertThat(parse(r.yaml()).panels()).hasSameSizeAs(parse(text).panels());
    }

    @Test
    void textReplacesTheWholeSutraOrIsRefused() throws Exception {
        String text = Files.readString(EXAMPLES.resolve("market-charts.sutra.yaml"));
        String other = Files.readString(EXAMPLES.resolve("relationships.sutra.yaml"));
        OpResult r = applier.apply(text, List.of(new Text(other)));
        assertThat(r.problems()).isEmpty();
        assertThat(r.yaml()).isEqualTo(other);
        OpResult bad = applier.apply(text, List.of(new Text("rachana: 1\nsutra: [")));
        assertThat(bad.problems()).hasSize(1);
        assertThat(bad.problems().get(0).code()).isEqualTo("DRS-2001");
        assertThat(bad.yaml()).isEqualTo(text);
        OpResult fixed = applier.apply("not a sutra", List.of(new SetOption("a", "title", "x"), new Text(other)));
        assertThat(fixed.problems()).hasSize(1);
        assertThat(fixed.yaml()).isEqualTo(other);
    }

    @Test
    void bindsPathsInTheRolesAKindTakes() {
        String text = String.join("\n", "rachana: 1", "sutra: bind-test", "version: 1", "match: { kind: trade }", "title: { pill: T, id: $.id }", "panels:",
                "  # the legs", "  - id: legs", "    kind: table", "    rows: $.legs", "    columns:", "      - { label: A, bind: \"@.a\" }",
                "  - { id: kvs, kind: kv }", "  - { id: g, kind: pivot, rows: $.legs, by: x, across: y }", "  - { id: st, kind: status }", "");
        OpResult r = applier.apply(text, List.of(new Bind("legs", "notional", null), new Bind("kvs", "tradeId", null), new Bind("st", "state", null), new Bind("g", "book", "by"),
                new Bind("g", "desk", "by"), new Bind("g", "ccy", "across"), new Bind("g", "pv", "value")));
        assertThat(r.problems()).isEmpty();
        Sutra s = parse(r.yaml());
        assertThat(s.panels().get(0).columns()).extracting(c -> c.bind()).containsExactly("@.a", "@.notional");
        assertThat(s.panels().get(1).columns()).extracting(c -> c.bind()).containsExactly("$.tradeId");      // a kv reads columns, not fields
        assertThat(s.panels().get(1).options()).doesNotContainKey("fields");
        assertThat(s.panels().get(3).options().get("fields")).isNotNull();                                      // a status reads fields
        assertThat(s.panels().get(2).options()).containsEntry("by", List.of("x", "book", "desk")).containsEntry("across", "ccy").containsEntry("value", "pv");
        assertThat(r.yaml()).contains("# the legs");
        assertThat(Bind.roles(PanelKind.TABLE)).contains("rows", "column");
        assertThat(Bind.roles(PanelKind.LINKS)).isEmpty();
    }

    @Test
    void operationsReadAndWriteJson() {
        String json = """
                [{"op":"addPanel","kind":"table","at":{"area":"right","after":"legs","span":6},"options":{"rows":"$.legs","limit":5}},
                 {"op":"move","panel":"legs","area":"main","before":"x"},
                 {"op":"setOption","panel":"legs","option":"title","value":null},
                 {"op":"bind","panel":"legs","path":"notional","role":"column"},
                 {"op":"remove","panel":"legs"},
                 {"op":"setTitle","title":{"pill":"T","id":"$.id"}},
                 {"op":"setStrip","items":[{"label":"A","bind":"$.a"}]},
                 {"op":"setKeys","keys":{"F2":"legs"}},
                 {"op":"setMatch","match":{"kind":"trade"}},
                 {"op":"text","yaml":"rachana: 1"}]""";
        List<Op> ops = Ops.parse(json);
        assertThat(ops).hasSize(10).hasOnlyElementsOfTypes(AddPanel.class, Move.class, SetOption.class, Bind.class, Remove.class, SetTitle.class,
                SetStrip.class, SetKeys.class, SetMatch.class, Text.class);
        assertThat(((AddPanel) ops.get(0)).options()).containsEntry("limit", 5L);
        assertThat(Ops.parse(Ops.toJson(ops))).isEqualTo(ops);
        assertThat(Ops.toJson(ops).get(2).has("value")).isTrue();

        assertThatThrownBy(() -> Ops.parse("[{\"op\":\"remove\",\"panel\":\"a\"},{\"op\":\"zap\"}]")).isInstanceOf(Ops.FormatException.class)
                .hasMessageContaining("operation 1").hasMessageContaining("unknown operation 'zap'");
        assertThatThrownBy(() -> Ops.parse("[{\"op\":\"remove\"}]")).hasMessageContaining("operation 0").hasMessageContaining("\"panel\"");
        assertThatThrownBy(() -> Ops.parse("[{\"op\":\"remove\",\"panel\":\"a\",\"why\":1}]")).hasMessageContaining("no field 'why'");
        assertThatThrownBy(() -> Ops.parse("[{\"op\":\"move\",\"panel\":\"a\",\"span\":\"wide\"}]")).hasMessageContaining("\"span\" must be a whole number");
        assertThatThrownBy(() -> Ops.parse("{\"op\":\"remove\"}")).hasMessageContaining("'ops' must be a list");
        assertThatThrownBy(() -> Ops.parse("[{\"op\":\"setOption\",\"panel\":\"a\",\"option\":\"b\"}]")).hasMessageContaining("needs a \"value\"");
        assertThatThrownBy(() -> Ops.parse("nonsense")).hasMessageContaining("not JSON");
    }
}

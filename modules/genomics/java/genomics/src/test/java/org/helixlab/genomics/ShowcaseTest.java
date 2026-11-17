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

package org.helixlab.genomics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The showcase's answers over a small fixture of the fourteen kinds (src/test/resources/showcase/<kind>.jsonl: 60 samples in three
 * clusters, 300 variants on 30 genes, a 40-residue protein with its scan, three design rounds): every figure is the server's, a selection
 * narrows the call, the joins between the packs hold, and missing data is said in words.
 */
@SuppressWarnings("unchecked")
class ShowcaseTest {

    static ShowcaseData data;
    static GenomicsBoard board;
    static ProteinBench bench;
    static ShowcaseController api;

    @BeforeAll
    static void load() {
        data = new ShowcaseData();
        ObjectMapper json = new ObjectMapper();
        data.attach((kind, sink) -> {
            InputStream in = ShowcaseTest.class.getResourceAsStream("/showcase/" + kind + ".jsonl");
            if (in == null) {
                return false;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    if (!line.isBlank()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = json.readValue(line, LinkedHashMap.class);
                        sink.accept(row);
                    }
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return true;
        });
        board = new GenomicsBoard(data);
        bench = new ProteinBench(data, board);
        api = new ShowcaseController(data, board, bench);
    }

    static Selection all() {
        return Selection.of("ONC-1", null, null, null);
    }

    @Test
    void everyKindIsReadAndNoneMissing() {
        Map<String, Object> s = api.status(false);
        assertThat(s.get("missing")).isEqualTo(List.of());
        assertThat((Map<String, Object>) s.get("rows")).containsEntry("study-sample", 60).containsEntry("dms-score", 1680).containsEntry("design", 60);
    }

    @Test
    void theSummaryNarrowsWithTheSelectionAndComparesCarriers() {
        Map<String, Object> whole = board.summary(all(), "HLXK7");
        assertThat(whole.get("samples")).isEqualTo(60);
        assertThat((double) whole.get("responseIfAltered")).isLessThan((double) whole.get("responseIfNot"));
        Map<String, Object> hot = board.summary(Selection.of("ONC-1", "C3 immune-hot", null, null), "HLXK7");
        assertThat(hot.get("samples")).isEqualTo(20);
        assertThat(hot.get("cohortSamples")).isEqualTo(60);
        assertThat(hot.get("altered")).isEqualTo(20);
        Map<String, Object> ex = (Map<String, Object>) hot.get("explain");
        assertThat((String) ex.get("sentence")).contains("20 of 20 samples carry a change in HLXK7");
        assertThat((List<?>) ex.get("steps")).hasSize(3);
    }

    @Test
    void aBoxOnTheMapSelectsBySamplePosition() {
        Map<String, Object> map = board.sampleMap(Selection.of("ONC-1", null, "-5,5,1.5,5", null));
        assertThat((List<?>) map.get("rows")).hasSize(60);
        assertThat((List<?>) map.get("clusters")).hasSize(3);
        List<?> sel = (List<?>) map.get("selected");
        assertThat(sel).isNotEmpty().hasSizeLessThanOrEqualTo(20);
        assertThatThrownBy(() -> Selection.of("ONC-1", null, "1,2,3", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("four numbers");
    }

    @Test
    void theAssociationPlotHasTheChromosomesInOrderAndThePeakOnHlxk7() {
        Map<String, Object> a = board.associations("RESPONSE");
        assertThat(((List<Map<String, Object>>) a.get("chromosomes")).stream().map(c -> c.get("CHROM")).toList()).containsExactly("1", "2", "3");
        assertThat(a.get("total")).isEqualTo(300);
        assertThat(a.get("traits")).isEqualTo(List.of("RESPONSE", "TOXICITY"));
        Map<String, Object> peak = ((List<Map<String, Object>>) a.get("peaks")).get(0);
        assertThat(peak.get("GENE")).isEqualTo("HLXK7");
        assertThat((double) peak.get("NEG_LOG10_P")).isGreaterThan(GenomicsBoard.GENOME_WIDE);
        assertThatThrownBy(() -> board.associations("HEIGHT")).isInstanceOf(NoSuchElementException.class).hasMessageContaining("RESPONSE, TOXICITY");
        assertThat(board.associations("RESPONSE")).isSameAs(a);          // cached by its query
    }

    @Test
    void theCohortMatrixSortsCarriersTogetherWithTracks() {
        Map<String, Object> o = board.oncoprint(all(), 5, "HLXK7");
        List<String> genes = (List<String>) o.get("genes");
        assertThat(genes).contains("HLXK7").hasSizeLessThanOrEqualTo(6);
        List<String> samples = (List<String>) o.get("samples");
        assertThat(samples).hasSize(60);
        List<Map<String, Object>> cells = (List<Map<String, Object>>) o.get("rows");
        assertThat(cells).allSatisfy(c -> assertThat(GenomicsBoard.ALTERATIONS).contains((String) c.get("ALTERATION")));
        String first = genes.get(0);
        Set<String> carriers = new java.util.HashSet<>();
        cells.stream().filter(c -> c.get("GENE").equals(first)).forEach(c -> carriers.add((String) c.get("SAMPLE_ID")));
        assertThat(samples.subList(0, carriers.size())).containsExactlyInAnyOrderElementsOf(carriers);   // the first gene's carriers lead
        assertThat((List<?>) o.get("tracks")).hasSize(3);
        assertThat(GenomicsBoard.alteration("stop_gained")).isEqualTo("truncating");
        assertThat(GenomicsBoard.alteration("synonymous_variant")).isNull();
    }

    @Test
    void theLollipopJoinsEachChangeToTheScan() {
        Map<String, Object> l = board.lollipop("HLXK7", all(), null);
        assertThat(l.get("protein")).isEqualTo("HELIXASE7");
        assertThat(l.get("assay")).isEqualTo("ACTIVITY");
        assertThat((List<?>) l.get("domains")).hasSize(2);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) l.get("rows");
        assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(r.get("SCORE")).isNotNull());
        assertThat(rows.stream().filter(r -> "*".equals(r.get("AA_ALT")))).allSatisfy(r -> assertThat(r.get("EFFECT")).isEqualTo("damaging"));
        Map<String, Object> ex = board.explainVariant((String) rows.get(0).get("VARIANT_ID"), all(), null);
        assertThat((String) ex.get("sentence")).contains("the laboratory scan measures this exact change");
        assertThat(((Map<String, Object>) ex.get("records")).get("count")).isEqualTo(rows.get(0).get("CARRIERS"));
    }

    @Test
    void theScanStructureAndResidue() {
        Map<String, Object> d = bench.dms("HLXK7", null);
        assertThat(d.get("assays")).isEqualTo(List.of("ACTIVITY", "STABILITY"));
        assertThat((List<?>) d.get("rows")).hasSize(840);
        assertThat(((List<?>) d.get("aas")).get(20)).isEqualTo("*");
        assertThat((List<?>) d.get("residues")).hasSize(40);
        assertThat(bench.structure("HELIXASE7").get("activeSite")).isEqualTo(List.of(12, 27));
        Map<String, Object> r = bench.residue("HLXK7", 7);
        assertThat(r.get("designs")).isEqualTo(60);
        Map<String, Object> cell = bench.explainCell("HLXK7", 7, "K");
        assertThat((String) cell.get("sentence")).startsWith(((String) r.get("AA")) + "7K helps the protein");
        assertThatThrownBy(() -> bench.dms("HLXK7", "BINDING")).isInstanceOf(NoSuchElementException.class).hasMessageContaining("ACTIVITY, STABILITY");
    }

    @Test
    void theCampaignRoundsAndLeaderboard() {
        Map<String, Object> c = bench.campaign("HLXK7", 17, "D");
        assertThat(c.get("rounds")).isEqualTo(List.of(1, 2, 3));
        assertThat((List<?>) c.get("rows")).hasSize(60);
        assertThat((List<?>) c.get("best")).hasSize(3);
        assertThat((List<?>) ((Map<String, Object>) c.get("field")).get("rows")).isNotEmpty();
        assertThat(c.get("carryingTotal")).isEqualTo(40);
        List<Map<String, Object>> rounds = (List<Map<String, Object>>) bench.rounds("HLXK7").get("rows");
        assertThat(rounds).hasSize(3).allSatisfy(r -> assertThat((double) r.get("Q1")).isLessThanOrEqualTo((double) r.get("MEDIAN")));
        List<Map<String, Object>> top = (List<Map<String, Object>>) bench.leaderboard("HLXK7", 2, 5).get("rows");
        assertThat(top).hasSize(5).allSatisfy(r -> assertThat((int) r.get("ROUND")).isLessThanOrEqualTo(2));
        Map<String, Object> design = bench.design("HLXK7", (String) top.get(0).get("DESIGN_ID"));
        assertThat((List<?>) design.get("mutations")).hasSize((int) top.get(0).get("N_CHANGES"));
        assertThat((String) ((Map<String, Object>) design.get("explain")).get("sentence")).contains("times the natural protein's activity");
        assertThat(ProteinBench.q(new double[] {1, 2, 3, 4}, 0.5)).isEqualTo(2.5);
    }

    /**
     * The answers the browser test of the screens draws (drishti-console/tests/test_genomics_walk_browser.py intercepts the module's API
     * with them): the committed src/test/resources/showcase/answers.json must equal what this server answers over the fixture. Write it
     * again with -Dshowcase.answers=write after a change to an answer.
     */
    @Test
    void theBrowserFixtureIsTheServersAnswers() throws Exception {
        Selection all = all();
        List<Map<String, Object>> loll = (List<Map<String, Object>>) board.lollipop("HLXK7", all, null).get("rows");
        Map<String, Object> answers = new java.util.TreeMap<>();
        answers.put("showcase/status", board.status());
        answers.put("associations", board.associations(null));
        answers.put("cohorts/ONC-1/summary", board.summary(all, "HLXK7"));
        answers.put("cohorts/ONC-1/oncoprint", board.oncoprint(all, 24, "HLXK7"));
        answers.put("cohorts/ONC-1/samples/map", board.sampleMap(all));
        answers.put("genes/HLXK7/lollipop", board.lollipop("HLXK7", all, null));
        answers.put("explain/variant/", board.explainVariant((String) loll.get(0).get("VARIANT_ID"), all, null));
        answers.put("explain/site/", board.explainSite((String) loll.get(0).get("VARIANT_ID")));
        answers.put("explain/cluster/", board.explainCluster("C3 immune-hot", "ONC-1"));
        answers.put("proteins/HLXK7/dms", bench.dms("HLXK7", null));
        answers.put("proteins/HLXK7/structure", bench.structure("HLXK7"));
        answers.put("proteins/HLXK7/residues/", bench.residue("HLXK7", 7));
        answers.put("proteins/HLXK7/designs", bench.campaign("HLXK7", null, null));
        answers.put("proteins/HLXK7/rounds", bench.rounds("HLXK7"));
        answers.put("proteins/HLXK7/leaderboard", bench.leaderboard("HLXK7", null, 20));
        String best = (String) ((List<Map<String, Object>>) bench.leaderboard("HLXK7", null, 1).get("rows")).get(0).get("DESIGN_ID");
        answers.put("proteins/HLXK7/designs/", bench.design("HLXK7", best));
        answers.put("explain/cell/", bench.explainCell("HLXK7", 7, "K"));
        answers.put("explain/design/", bench.design("HLXK7", best).get("explain"));
        answers.put("explain/residue/", bench.residue("HLXK7", 7).get("explain"));
        ObjectMapper json = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode made = json.readTree(json.writeValueAsString(answers));
        java.nio.file.Path file = java.nio.file.Path.of(com.ash.drishti.testroots.TestRoots.module(), "src", "test", "resources", "showcase", "answers.json");
        if ("write".equals(System.getProperty("showcase.answers"))) {
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), made);
        }
        assertThat(java.nio.file.Files.exists(file)).as("write it: -Dshowcase.answers=write").isTrue();
        assertThat(json.readTree(file.toFile())).isEqualTo(made);
    }

    @Test
    void missingDataIsSaidInWords() {
        ShowcaseData empty = new ShowcaseData();
        empty.attach((kind, sink) -> false);
        GenomicsBoard b = new GenomicsBoard(empty);
        assertThatThrownBy(() -> b.associations(null)).isInstanceOf(NoSuchElementException.class).hasMessageContaining("no rows of variant, association");
        assertThat((List<?>) b.status().get("missing")).hasSize(14);
        assertThat(api.missing(new NoSuchElementException("x")).getStatusCode().value()).isEqualTo(404);
    }
}

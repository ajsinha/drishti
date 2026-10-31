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
package com.ash.drishti.server.bi.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.TestLake;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.runs.Run;
import com.ash.drishti.server.bi.runs.RunMetrics;
import com.ash.drishti.server.bi.runs.RunPromotion;
import com.ash.drishti.server.bi.runs.RunRecords;
import com.ash.drishti.server.bi.runs.RunService;
import com.ash.drishti.server.bi.runs.RunState;
import com.ash.drishti.server.bi.runs.RunStore;
import com.ash.drishti.server.bi.runs.RunsProperties;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Promoting a run (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), sections 9.4 and 19.3): the snapshot is the promoter's view (a column masked for them is stored as the mask in
 * every row, never a value, and the dataset declares it masked); the draft dataset's YAML parses with {@code source: {run}}; the snapshot is read only by
 * the dataset it was promoted to (its name and token); a second promotion is the next version of the same draft, and only versions-keep are kept.
 */
class RunPromotionTest {

    static final String BY_CPTY = "{ mq: 1, model: desk-risk, select: { fields: [trades.cpty], measures: [Trades, MTM] } }";

    @TempDir Path store;
    @TempDir Path data;
    TestLake lake;
    QueryService service;
    MqService mq;
    MqHarness h;
    ModelView model;
    RunService runs;
    RunPromotion promotion;
    final List<String> drafts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        mq = new MqService(MqProperties.defaults());
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(MqEngineTest.MODEL, MqEngineTest.TRADES, MqEngineTest.DESKS, MqEngineTest.LIMITS);
        RunsProperties p = RunsProperties.defaults(data);
        runs = new RunService(p, new RunRecords.Memory(), new RunStore(p), Clock.systemUTC(), "java-1", RunMetrics.NONE);
        promotion = new RunPromotion(runs);
        promotion.drafter((who, item, kind, title, body) -> {
            drafts.add(who.user() + " " + kind + " " + (item == null ? "new" : item) + "\n" + body);
            return item != null ? item : "bi-" + kind + "-" + drafts.size();
        });
    }

    @AfterEach
    void tearDown() {
        runs.close();
        service.close();
    }

    Run ran(MqHarness.Reader who) throws Exception {
        MqService.Bound b = h.bind(model, who, "m");
        ModelQuery q = MqParser.parse(BY_CPTY);
        String canon = "{\"type\":\"mq\",\"model\":\"desk-risk\"}";
        RunService.Question question = new RunService.Question(new Principal(who.user(), who.roles()), "mq", "desk-risk", null, "Trades by counterparty", null,
                "desk-risk", canon, null, b.signature(), "v1", false, null, () -> {
                    MqService.Answer a = mq.ask(b, q, false);
                    return new RunService.Answer(a.body(), a.result().columns(), a.result().rows());
                });
        String id = runs.submit(question, new RunService.Options(Duration.ZERO, true, true)).run().id();
        for (int i = 0; i < 300; i++) {
            Optional<Run> r = runs.get(id);
            if (r.isPresent() && r.get().state() == RunState.DONE) {
                return r.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run " + id + " did not finish");
    }

    @Test
    void aMaskedColumnIsStoredAsTheMaskAndDeclaredMaskedForEveryReader() throws Exception {
        Run r = ran(MqHarness.ANN);                                               // the counterparty is masked for ann
        RunPromotion.Promoted p = promotion.promote(r, new Principal("ann", MqHarness.ANN.roles()), "cpty-snapshot", null, "dataset");
        assertThat(p.version()).isEqualTo(1);
        // the file holds the mask in every row of the masked column: never a counterparty
        String file = Files.readString(data.resolve("bi-runs").resolve("snapshots").resolve("cpty-snapshot").resolve("v1").resolve("answer.json"));
        for (String cpty : List.of("CP-0", "CP-1", "CP-2", "CP-3")) {
            assertThat(file).doesNotContain("\"" + cpty + "\"");
        }
        DatasetDefParser.Result parsed = DatasetDefParser.parse(p.yaml());
        assertThat(parsed.ok()).as(p.yaml() + parsed.problems()).isTrue();
        DatasetDef d = parsed.dataset();
        assertThat(d.source().type()).isEqualTo(DatasetDef.Source.RUN);
        assertThat(d.source().runName()).isEqualTo("cpty-snapshot");
        assertThat(d.source().runVersion()).isEqualTo("1");
        assertThat(d.security().masked()).containsExactly("cpty");                 // masked for the promoter: masked always
        assertThat(p.yaml()).contains("computedAs: \"ann\"");
        // read as the dataset it was promoted to: the promoter's rows, the mask where she saw the mask
        ModelCatalog.Snapshot s = promotion.read("cpty-snapshot", "cpty-snapshot", "1", d.source().runToken());
        ArrowIpcWriter.Column cpty = s.result().columns().get(0);
        assertThat(s.result().rows()).isPositive();
        assertThat(cpty.values()).allSatisfy(v -> assertThat(v).isIn(DataNode.MASK, null));
        assertThat(runs.get(r.id()).orElseThrow().promotedTo()).isEqualTo("cpty-snapshot@1");
    }

    @Test
    void anUnmaskedPromoterKeepsTheValuesAndNothingIsDeclaredMasked() throws Exception {
        Run r = ran(MqHarness.RITA);
        RunPromotion.Promoted p = promotion.promote(r, new Principal("rita", MqHarness.RITA.roles()), "cpty-raw", "Raw", "report");
        DatasetDef d = DatasetDefParser.parse(p.yaml()).dataset();
        assertThat(d.security().masked()).isEmpty();
        ModelCatalog.Snapshot s = promotion.read("cpty-raw", "cpty-raw", "latest", d.source().runToken());
        assertThat(s.result().columns().get(0).values()).noneMatch(DataNode.MASK::equals);
        assertThat(p.reportItem()).isNotNull();
        assertThat(drafts.get(drafts.size() - 1)).contains("type: table").contains("cpty_raw.cpty");
    }

    @Test
    void onlyTheDatasetItWasPromotedToReadsASnapshot() throws Exception {
        Run r = ran(MqHarness.RITA);
        RunPromotion.Promoted p = promotion.promote(r, new Principal("rita", MqHarness.RITA.roles()), "rita-book", null, "dataset");
        String token = DatasetDefParser.parse(p.yaml()).dataset().source().runToken();
        // another author's dataset pointing at it: without the token, or under another name, it is refused
        assertThatThrownBy(() -> promotion.read("rita-book", "rita-book", "1", "guess")).isInstanceOf(MqException.class).hasMessageContaining("may not read it");
        assertThatThrownBy(() -> promotion.read("my-copy", "rita-book", "1", token)).isInstanceOf(MqException.class).hasMessageContaining("may not read it");
        assertThatThrownBy(() -> promotion.read("nothing", "nothing", "1", "")).isInstanceOf(MqException.class).hasMessageContaining("DRS-9108");
        // another person cannot take the name
        Run anns = ran(MqHarness.ANN);
        assertThatThrownBy(() -> promotion.promote(anns, new Principal("ann", MqHarness.ANN.roles()), "rita-book", null, "dataset"))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("taken");
    }

    @Test
    void promotingAgainIsTheNextVersionOfTheSameDraftAndOldVersionsGo() throws Exception {
        Run r = ran(MqHarness.RITA);
        Principal rita = new Principal("rita", MqHarness.RITA.roles());
        RunPromotion.Promoted first = promotion.promote(r, rita, "kept", null, "dataset");
        for (int i = 2; i <= 7; i++) {
            RunPromotion.Promoted again = promotion.promote(r, rita, "kept", null, "dataset");
            assertThat(again.version()).isEqualTo(i);
            assertThat(again.datasetItem()).isEqualTo(first.datasetItem());        // the same draft dataset, a new draft of it
        }
        assertThat(runs.store().snapshotVersions("kept")).containsExactly(3, 4, 5, 6, 7);   // promote.versions-keep: 5
    }

    @Test
    void aReportVisualRunCannotBePromoted() {
        Run report = Run.planned("r-java-1-01JA0000000000000000000000", "rita", "rita", "report", "{}", "k", "s", com.ash.drishti.server.bi.runs.Tier.INTERACTIVE,
                "local", "{}", "java-1", java.time.Instant.now());
        assertThatThrownBy(() -> promotion.promote(report, new Principal("rita", List.of("risk")), "x", null, "dataset"))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("cannot be promoted");
        assertThat(Map.of()).isEmpty();
    }
}
